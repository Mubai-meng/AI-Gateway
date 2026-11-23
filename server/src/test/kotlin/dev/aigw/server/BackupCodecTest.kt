package dev.aigw.server

import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 备份出明文 / 恢复后重新加密。
 *
 * 这两条是同一个承诺的两面，缺一面就破：
 * - 出包时不解密 → 包还是密文，压缩比 1.00，体积问题没解决；
 * - 恢复后不加密 → 明文凭证落在磁盘上，「secure/ 一律加密」被一次恢复打破。
 */
class BackupCodecTest {

    private val magic = byteArrayOf(0x41, 0x47, 0x57, 0x31)

    private fun tempDir(): File =
        Files.createTempDirectory("aigw-backupcodec").toFile().also { it.deleteOnExit() }

    private fun newKey(): ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }

    // ------------------------------------------------------------ 编解码基础

    @Test
    fun `明文进包 密文能解回原文`() {
        val key = newKey()
        val text = """{"providerId":"codebuddy","secret":"token-xyz"}"""
        val plain = text.toByteArray(Charsets.UTF_8)
        val enc = BackupCodec.encrypt(plain, key, java.security.SecureRandom())
        assertTrue(enc != null, "加密应成功")
        assertTrue(BackupCodec.looksEncrypted(enc!!), "结果应带 AGW1 魔数")
        assertEquals(text, String(BackupCodec.decrypt(enc, key)!!, Charsets.UTF_8))
    }

    @Test
    fun `已经是密文的原样返回 不重复加密`() {
        val key = newKey()
        val enc = BackupCodec.encrypt("x".toByteArray(), key, java.security.SecureRandom())!!
        val again = BackupCodec.encrypt(enc, key, java.security.SecureRandom())!!
        assertEquals(
            String(enc, Charsets.ISO_8859_1),
            String(again, Charsets.ISO_8859_1),
            "重复加密会把密文再包一层，解密端就坏了",
        )
    }

    @Test
    fun `不是密文时 decrypt 返回 null 而不是猜`() {
        assertNull(BackupCodec.decrypt("plain text".toByteArray(), newKey()))
    }

    @Test
    fun `key 不对时 decrypt 返回 null 不抛`() {
        val enc = BackupCodec.encrypt("secret".toByteArray(), newKey(), java.security.SecureRandom())!!
        assertNull(BackupCodec.decrypt(enc, ByteArray(32) { 1 }))
    }

    @Test
    fun `缺 key 文件时 loadKey 返回 null 绝不生成`() {
        val dir = tempDir()
        val keyFile = File(dir, "master.key")
        assertNull(BackupCodec.loadKey(keyFile))
        assertFalse(keyFile.exists(), "备份层不能顺手造密钥——会与运行期那把不一致")
    }

    @Test
    fun `key 长度不是 32 时拒绝`() {
        val dir = tempDir()
        val keyFile = File(dir, "master.key")
        keyFile.writeBytes(ByteArray(16))
        assertNull(BackupCodec.loadKey(keyFile))
    }

    // ------------------------------------------------------------ 恢复后重新加密

    @Test
    fun `恢复明文包后 secure 下不再有明文`() {
        val dir = tempDir()
        val secure = File(dir, "secure").also { it.mkdirs() }
        val keyFile = File(dir, "master.key").also { it.writeBytes(newKey()) }

        // 模拟恢复结果：包里搬出来的明文条目
        File(secure, "account").mkdirs()
        File(secure, "account/codebuddy-1").writeText("""{"uid":"u1"}""")
        File(secure, "logs").mkdirs()
        File(secure, "logs/requests.json").writeText("""[{"level":"INFO"}]""")

        val done = BackupCodec.reencryptSecureTree(secure, keyFile)
        assertEquals(2, done, "两个明文条目都应被加密")

        val all = secure.walkTopDown().filter { it.isFile }.toList()
        assertEquals(2, all.size)
        all.forEach { f ->
            val head = f.readBytes().copyOfRange(0, 4)
            assertTrue(head.contentEquals(magic), "${f.name} 仍是明文")
        }
    }

    @Test
    fun `已加密条目不被二次加密`() {
        val dir = tempDir()
        val secure = File(dir, "secure").also { it.mkdirs() }
        val raw = newKey()
        val keyFile = File(dir, "master.key").also { it.writeBytes(raw) }
        val enc = BackupCodec.encrypt("""{"uid":"u1"}""".toByteArray(), raw, java.security.SecureRandom())!!
        File(secure, "acc").writeBytes(enc)

        val done = BackupCodec.reencryptSecureTree(secure, keyFile)
        assertEquals(0, done, "库里本来就是密文，不该动它")
        assertTrue(File(secure, "acc").readBytes().contentEquals(enc), "字节必须原样保留")
    }

    @Test
    fun `恢复后重新加密的数据 用同一个 key 解得开`() {
        val dir = tempDir()
        val secure = File(dir, "secure").also { it.mkdirs() }
        val raw = newKey()
        val keyFile = File(dir, "master.key").also { it.writeBytes(raw) }
        val payload = """{"providerId":"codebuddy","secret":"tk"}"""
        File(secure, "acc").writeText(payload)

        BackupCodec.reencryptSecureTree(secure, keyFile)
        val decrypted = BackupCodec.decrypt(File(secure, "acc").readBytes(), raw)
        assertEquals(payload, decrypted?.let { String(it, Charsets.UTF_8) })
    }

    @Test
    fun `没有 key 时不动任何文件`() {
        val dir = tempDir()
        val secure = File(dir, "secure").also { it.mkdirs() }
        File(secure, "acc").writeText("plain")
        val done = BackupCodec.reencryptSecureTree(secure, File(dir, "master.key"))
        assertEquals(0, done)
        assertEquals("plain", File(secure, "acc").readText(), "没密钥就把明文留着，不删")
    }

    // ------------------------------------------------------------ 整包

    @Test
    fun `建出来的包是明文 且清单标了 encrypted=false`() {
        val dataDir = tempDir()
        val secure = File(dataDir, "secure").also { it.mkdirs() }
        val raw = newKey()
        File(dataDir, "master.key").writeBytes(raw)

        // 磁盘上是密文，与真实运行状态一致
        val enc = BackupCodec.encrypt("""{"uid":"u1"}""".toByteArray(), raw, java.security.SecureRandom())!!
        File(secure, "acc").writeBytes(enc)
        File(dataDir, "ui-theme.txt").writeText("dark")

        val bm = BackupManager(dataDir)
        val res = bm.create(includeCallLogs = true)
        assertTrue(res.get("ok")?.asBoolean == true, "备份应成功：${res.get("message")}")

        val zipName = res.get("name").asString
        java.util.zip.ZipFile(File(bm.backupDir, zipName)).use { zf ->
            val entry = zf.getEntry("secure/acc")
            requireNotNull(entry) { "包内缺 secure/acc" }
            val bytes = zf.getInputStream(entry).use { it.readBytes() }
            assertFalse(
                bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(magic),
                "包里不该是 AGW1 密文",
            )
            assertEquals("""{"uid":"u1"}""", String(bytes, Charsets.UTF_8))

            val manifest = zf.getInputStream(zf.getEntry("aigw-backup-manifest.json"))
                .use { com.google.gson.JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject }
            assertFalse(
                manifest.get("encrypted")?.asBoolean ?: true,
                "清单必须标 encrypted=false，否则读包的人会去找 master.key",
            )
            assertTrue(manifest.get("includeCallLogs").asBoolean)
        }
    }

    @Test
    fun `关掉调用记录时包内不含 logs`() {
        val dataDir = tempDir()
        val secure = File(dataDir, "secure").also { it.mkdirs() }
        File(dataDir, "master.key").writeBytes(newKey())
        File(secure, "logs").mkdirs()
        File(secure, "logs/calls").mkdirs()
        File(secure, "logs/calls/chatcmpl-1").writeText("payload")
        File(secure, "acc").writeText("keep")

        val bm = BackupManager(dataDir)
        val res = bm.create(includeCallLogs = false)
        assertTrue(res.get("ok")?.asBoolean == true)

        java.util.zip.ZipFile(File(bm.backupDir, res.get("name").asString)).use { zf ->
            assertNull(zf.getEntry("secure/logs/calls/chatcmpl-1"), "logs 应被跳过")
            assertTrue(zf.getEntry("secure/acc") != null, "其余条目必须照常带上")
        }
    }

    @Test
    fun `清单里的 entryCount 与实际条目数一致`() {
        val dataDir = tempDir()
        val secure = File(dataDir, "secure").also { it.mkdirs() }
        File(dataDir, "master.key").writeBytes(newKey())
        repeat(3) { File(secure, "k$it").writeText("v") }
        File(dataDir, "ui-theme.txt").writeText("dark")

        val bm = BackupManager(dataDir)
        val res = bm.create(includeCallLogs = true)
        val declared = res.get("message").asString
            .substringAfter("已备份 ").substringBefore(" 个条目").toInt()
        java.util.zip.ZipFile(File(bm.backupDir, res.get("name").asString)).use { zf ->
            val files = zf.entries().asSequence().count { !it.isDirectory }
            // 包内 = 数据条目 + 清单 1 个；message 只报数据条目数
            assertEquals(files - 1, declared, "message 的条数应等于包内文件数减去清单")
            val manifest = zf.getInputStream(zf.getEntry("aigw-backup-manifest.json"))
                .use { com.google.gson.JsonParser.parseString(it.readBytes().toString(Charsets.UTF_8)).asJsonObject }
            assertEquals(declared, manifest.get("entryCount").asInt, "清单的 entryCount 必须与 message 一致")
        }
    }
}
