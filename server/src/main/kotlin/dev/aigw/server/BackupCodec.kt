package dev.aigw.server

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份包内条目的明文转换：把 `secure/` 下的 AGW1 密文解成明文再入包。
 *
 * **为什么备份要出明文**：这是个人自用工具，备份只在自己机器/自己的网盘之间流转，
 * 没有"他人拿到包"这个威胁模型。而加密带来三个实际代价——
 * ① 密文熵饱和，deflate 压不动（实测压缩比 1.00，2.2 MB 的日志一点都不瘦）；
 * ② 包解不开就没法用编辑器看一眼（改个设置、捞个 token 都要走程序）；
 * ③ `master.key` 必须跟着包走，密钥一丢整包报废。
 *
 * 所以 1.0.0 起备份固定出明文。**磁盘上的 `secure/` 仍然加密**——这一层没动，
 * 保护的是"程序运行目录被翻"这个场景，与备份是两件事。
 *
 * 兼容方向只有一个：**旧加密包仍能恢复**。恢复逻辑是把包内条目原样搬回数据目录，
 * 旧包里自带 `master.key` + 密文，搬回去后 `EncryptedKeyValueStore` 用旧 key 解得开。
 * 反过来"新明文包装进旧版本"不成立，也不需要成立。
 */
internal object BackupCodec {

    /** 与 `EncryptedKeyValueStore.MAGIC` 必须逐字节一致。 */
    private val MAGIC = byteArrayOf(0x41, 0x47, 0x57, 0x31) // "AGW1"
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_LEN = 32

    /**
     * 只读主密钥。**文件不存在就返回 null，绝不生成**。
     *
     * 生成密钥是 [dev.aigw.desktop.EncryptedKeyValueStore] 的职责，它只在启动期跑。
     * 备份这层如果顺手造一个 key，会在"数据目录还没初始化就点了备份"这种场景下
     * 写出一把与后续运行不同的密钥，反而把数据搞坏。
     */
    fun loadKey(keyFile: File): ByteArray? {
        if (!keyFile.isFile) return null
        val raw = runCatching { keyFile.readBytes() }.getOrNull() ?: return null
        return if (raw.size == KEY_LEN) raw else null
    }

    fun looksEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size + NONCE_LEN &&
            bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** 解密；不是密文或解密失败都返回 null，由调用方按原字节处理（不丢条目）。 */
    fun decrypt(bytes: ByteArray, key: ByteArray): ByteArray? {
        if (!looksEncrypted(bytes)) return null
        val nonce = bytes.copyOfRange(MAGIC.size, MAGIC.size + NONCE_LEN)
        val body = bytes.copyOfRange(MAGIC.size + NONCE_LEN, bytes.size)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.doFinal(body)
        }.getOrNull()
    }

    /**
     * 恢复明文包之后，把 `secure/` 下的明文条目重新加密回 AGW1。
     *
     * **为什么必须有这一步**：备份包现在是明文，原样搬回数据目录就等于把明文凭证
     * 铺在磁盘上。虽然 `EncryptedKeyValueStore.read()` 有"非 AGW1 就当明文读"的兼容分支，
     * 功能不会立刻坏，但会留下一个长期混合状态——读过的键还是明文，写过的键才变密文，
     * 「磁盘上 secure/ 一律加密」这条承诺被一次恢复打破。
     *
     * 必须在 [BackupManager.applyPending] 里、且在任何 `EncryptedKeyValueStore` 构造之前调用：
     * 那时数据目录没有活句柄，写是安全的。
     *
     * @return 加密的条目数；读不出/加密失败的文件原样保留（不删，宁可留明文也不丢数据）。
     */
    fun reencryptSecureTree(secureDir: File, keyFile: File): Int {
        if (!secureDir.isDirectory) return 0
        val key = loadKey(keyFile) ?: return 0
        val random = SecureRandom()
        var done = 0
        secureDir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
            val raw = runCatching { f.readBytes() }.getOrNull() ?: return@forEach
            if (looksEncrypted(raw)) return@forEach
            val enc = encrypt(raw, key, random) ?: return@forEach
            val tmp = File(f.parentFile, f.name + ".tmp")
            runCatching {
                tmp.writeBytes(enc)
                java.nio.file.Files.move(
                    tmp.toPath(),
                    f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                done++
            }.onFailure { runCatching { tmp.delete() } }
        }
        return done
    }

    /**
     * 加密成 AGW1 格式；已经是密文的原样返回。
     *
     * 只在**恢复明文备份包之后**用一次：那时数据目录里躺的是明文（包里就是明文），
     * 必须当场加密回去，否则「磁盘上 secure/ 一律加密」这条承诺就被一次恢复打破，
     * 之后的运行会长期维持明文+密文混合状态。
     */
    fun encrypt(bytes: ByteArray, key: ByteArray, random: SecureRandom): ByteArray? {
        if (looksEncrypted(bytes)) return bytes
        return runCatching {
            val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            MAGIC + nonce + cipher.doFinal(bytes)
        }.getOrNull()
    }
}
