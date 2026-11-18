package dev.aigw.desktop

import dev.aigw.core.store.FileKeyValueStore
import dev.aigw.core.store.KeyValueStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Windows 侧加密存储。实现 core 的 KeyValueStore，引擎无需改动。
 *
 * - 每个键独立 AES-256-GCM 加密，密文格式 MAGIC(4) + nonce(12) + ciphertext+tag；
 * - 主密钥单独放在数据目录外的 master.key，首次启动生成，并用 icacls 把 ACL 收敛到当前用户；
 * - 写盘沿用「临时文件 + 原子替换」，避免中途失败留下半截凭证。
 */
class EncryptedKeyValueStore(
    private val root: File,
    private val keyFile: File,
) : KeyValueStore {

    private val secret: SecretKey
    private val random = SecureRandom()

    init {
        if (!root.exists()) root.mkdirs()
        secret = loadOrCreateKey()
    }

    private fun loadOrCreateKey(): SecretKey {
        keyFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val raw = if (keyFile.isFile) {
            keyFile.readBytes()
        } else {
            val kg = KeyGenerator.getInstance("AES")
            kg.init(256)
            val bytes = kg.generateKey().encoded
            val tmp = File(keyFile.parentFile, keyFile.name + ".tmp")
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), keyFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            restrictAcl(keyFile)
            bytes
        }
        require(raw.size == 32) { "主密钥长度异常：${raw.size} 字节" }
        return SecretKeySpec(raw, "AES")
    }

    /** 密钥文件只授权当前 Windows 用户；失败不阻断启动（目录本身在用户 %APPDATA% 下）。 */
    private fun restrictAcl(file: File) {
        val user = System.getenv("USERNAME") ?: return
        runCatching {
            ProcessBuilder("icacls", file.absolutePath, "/inheritance:r", "/grant:r", "$user:(F)")
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
    }

    override fun read(key: String): String? {
        val file = fileFor(key)
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size < MAGIC.size + NONCE_LEN) return null
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            // 兼容尚未迁移的明文文件，避免升级过程中读不到凭证
            return String(bytes, Charsets.UTF_8)
        }
        val nonce = bytes.copyOfRange(MAGIC.size, MAGIC.size + NONCE_LEN)
        val body = bytes.copyOfRange(MAGIC.size + NONCE_LEN, bytes.size)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, nonce))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun write(key: String, value: String) {
        val file = fileFor(key)
        file.parentFile?.mkdirs()
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, nonce))
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(MAGIC + nonce + body)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun delete(key: String) {
        val file = fileFor(key)
        if (file.isFile) file.delete()
    }

    override fun keys(prefix: String): List<String> {
        val base = root.absolutePath
        return root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .map {
                it.absolutePath.removePrefix(base)
                    .trimStart(File.separatorChar)
                    .replace(File.separatorChar, '/')
            }
            // 磁盘名是编码过的：先解回逻辑键再比前缀，否则调用方拿到的键
            // （如 `account/custom:xxx/…`）与它自己拼的键对不上，账号全部读不到。
            .map { decodeSegments(it) }
            .filter { it.startsWith(prefix) }
            .toList()
    }

    /**
     * 逻辑键 → 磁盘路径。
     *
     * **Windows 文件名不允许冒号**，而自定义供应商的 providerId 恰好是
     * `custom:<短名>`（账号键 `account/custom:xxx/key-xxxxxxxx`）。直接 `File(root, key)`
     * 会抛「文件名、目录名或卷标语法不正确」，表现为「保存自定义供应商 → 500」，
     * 且第一次保存就把整条链路断掉（配置写好、账号写不进去）。
     *
     * 只转义文件名里非法的字符，并转义 `%` 本身以防二次解码；
     * 不含这些字符的键（trae/… 、codebuddy/… 等全部内置供应商）编码结果与原文一致，
     * 存量数据不需要迁移。
     */
    private fun fileFor(key: String): File = File(root, encodeSegments(key))

    private fun encodeSegments(key: String): String =
        key.split('/').joinToString("/") { encodeSegment(it) }

    private fun decodeSegments(path: String): String =
        path.split('/').joinToString("/") { decodeSegment(it) }

    /** 单段编码：`%` 与 Windows 非法字符各转成 `%XX`。 */
    private fun encodeSegment(seg: String): String {
        val sb = StringBuilder(seg.length)
        for (ch in seg) {
            if (ch == '%' || ch in ILLEGAL) {
                sb.append('%').append(HEX[ch.code shr 4]).append(HEX[ch.code and 0xF])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 单段解码：非法百分号序列原样保留（旧数据里真有 `%` 也不会被吃掉）。 */
    private fun decodeSegment(seg: String): String {
        if (!seg.contains('%')) return seg
        val sb = StringBuilder(seg.length)
        var i = 0
        while (i < seg.length) {
            val c = seg[i]
            if (c == '%' && i + 2 < seg.length) {
                val hi = Character.digit(seg[i + 1], 16)
                val lo = Character.digit(seg[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    sb.append(((hi shl 4) or lo).toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private companion object {
        val MAGIC = byteArrayOf(0x41, 0x47, 0x57, 0x31) // "AGW1"
        const val NONCE_LEN = 12
        const val TAG_BITS = 128

        /** Windows 文件名非法字符（`/` 是键的分隔符，不在此列）。 */
        val ILLEGAL = charArrayOf(':', '*', '?', '\"', '<', '>', '|', '\u0000')
        const val HEX = "0123456789ABCDEF"
    }
}

/** 上游明文键布局。只有这些前缀属于凭证/设置数据，其余目录（secure/、备份、临时）不动。 */
private val LEGACY_PREFIXES = listOf(
    "account/", "pool/", "settings/", "logs/", "usage/", "clientkey/", "call/", "login/",
)

/**
 * 把 CLI 遗留的明文数据迁进加密存储。
 *
 * 回滚副本同样写进加密存储（前缀 legacy-backup/），磁盘上不留任何明文文件 ——
 * 明文备份会让「凭证加密」形同虚设。迁移成功后逐个删除已迁走的明文文件，其余文件不碰。
 * 返回迁移的键数量；0 表示没有可迁移的明文。
 */
fun migratePlaintext(dataDir: File, encrypted: EncryptedKeyValueStore): Int {
    if (!dataDir.isDirectory) return 0
    val plain = FileKeyValueStore(dataDir)
    val keys = LEGACY_PREFIXES.flatMap { plain.keys(it) }.distinct()
    if (keys.isEmpty()) return 0
    for (key in keys) {
        val value = plain.read(key) ?: continue
        encrypted.write(key, value)
        encrypted.write("legacy-backup/$key", value)
    }
    // 只删确实读过的文件；空目录保留，不递归清理用户目录
    for (key in keys) {
        val file = File(dataDir, key)
        if (file.isFile) file.delete()
    }
    return keys.size
}

/** 构造桌面版存储：加密目录 dataDir/secure，主密钥 dataDir/master.key。 */
fun createDesktopStore(dataDir: File): KeyValueStore {
    val store = EncryptedKeyValueStore(File(dataDir, "secure"), File(dataDir, "master.key"))
    val migrated = migratePlaintext(dataDir, store)
    if (migrated > 0) println("[desktop] 已把 $migrated 个明文键迁入加密存储（含加密回滚副本 legacy-backup/），磁盘明文已清除")
    return store
}
