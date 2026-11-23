package dev.aigw.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

/**
 * 旧明文备份就地重压成 `.json.gz`。
 *
 * 这是**会删文件**的操作，所以测试的重点不是「转换成功」，而是：
 * 转换失败/校验不过时，旧文件必须还在。宁可多占 19 KB，不能让备份消失。
 */
class VaultMigrateTest {

    /** 同 BackupCodecTest：临时目录必须在用例结束后递归删。 */
    private val roots = mutableListOf<File>()

    @AfterTest
    fun cleanupRoots() {
        roots.forEach { runCatching { it.deleteRecursively() } }
        roots.clear()
    }

    /**
     * 每个测试一套独立的目录树。
     *
     * **必须建 `root/data` + `root/auto`**：生产口径把自动备份写在系统临时目录
     * （`%TEMP%/aigw-account-vault`），那是**所有测试共用一个**——上一个测试残留的
     * auto-*.json.gz 会让下一个测试判成「同名 gz 已存在」而失败。
     * 实测踩过：13 个用例里 5 个红，全是这个原因。所以这里显式传 autoDir。
     */
    private fun newRoot(): File {
        val root = Files.createTempDirectory("aigw-vaultmig").toFile()
        roots.add(root)
        File(root, "data").mkdirs()
        File(root, "install").mkdirs()
        return root
    }

    private fun dataDirOf(root: File): File = File(root, "data")

    private fun vault(dataDir: File): AccountVault = AccountVault(
        dataDir,
        File(dataDir.parentFile, "install"),
        File(dataDir.parentFile, "auto"),
    )

    /** 造一个合法的 aigw-accounts 信封，账号数可控。 */
    private fun envelope(n: Int): String {
        val arr = com.google.gson.JsonArray()
        repeat(n) { i ->
            arr.add(JsonObject().apply {
                addProperty("providerId", "codebuddy")
                addProperty("uid", "u$i")
                addProperty("nickname", "n$i")
                addProperty("secret", """{"accessToken":"t$i","refreshToken":"r$i","expiresAt":1}""")
            })
        }
        return JsonObject().apply {
            addProperty("format", "aigw-accounts")
            addProperty("version", 1)
            addProperty("exportedAt", 1L)
            add("accounts", arr)
        }.toString()
    }

    private fun countOf(file: File): Int? = runCatching {
        JsonParser.parseString(AccountPack.decode(file.readBytes())).asJsonObject
            .get("accounts")?.takeIf { it.isJsonArray }?.asJsonArray?.size()
    }.getOrNull()

    /** 账号数一致才算转换成功（这是删除旧文件的前置条件）。 */
    @Test
    fun `旧 json 被压成 json_gz 且账号数一致`() {
        val root = newRoot()
        val v = vault(dataDirOf(root))
        v.vaultDir.mkdirs()
        val old = File(v.vaultDir, "auto-20261122-080000.json")
        old.writeText(envelope(13), Charsets.UTF_8)

        val n = v.migrateLegacyPlaintext()
        assertEquals(1, n, "应转换 1 份")

        val gz = File(v.vaultDir, "auto-20261122-080000.json.gz")
        assertTrue(gz.isFile, "新文件必须在")
        assertFalse(old.exists(), "校验通过后才删旧文件")
        assertEquals(13, countOf(gz), "账号数必须与原文件一致")
        assertTrue(gz.length() < 0.6 * 4096, "压完应明显变小，实际 ${gz.length()} B")
    }

    @Test
    fun `新版写出的 json_gz 不受影响`() {
        val root = newRoot()
        val v = vault(dataDirOf(root))
        v.vaultDir.mkdirs()
        val payload = envelope(3)
        v.writeAuto(payload, AccountVault.REASON_MANUAL, "1.0.2")   // 落 .json.gz

        val n = v.migrateLegacyPlaintext()
        assertEquals(0, n, "本该没有旧格式可转，不该动新文件")
        // 只数带 auto-/manual- 前缀的：`vault-state.json` 也满足 isAccountFile 的后缀判定，
        // 但它不是备份（scanDir 靠前缀排除它）。这里按前缀数才是"备份有几份"。
        assertEquals(
            1,
            v.vaultDir.listFiles()!!.count {
                it.isFile && AccountPack.isAccountFile(it.name) && it.name.startsWith("auto-")
            },
            "新格式备份应该还是 1 份",
        )
    }

    @Test
    fun `内容坏掉的旧文件不动 也不删`() {
        val root = newRoot()
        val v = vault(dataDirOf(root))
        v.vaultDir.mkdirs()
        val bad = File(v.vaultDir, "auto-20261122-080000.json")
        bad.writeText("not a json at all", Charsets.UTF_8)

        val n = v.migrateLegacyPlaintext()
        assertEquals(0, n, "解析不出账号数的文件不能转换")
        assertTrue(bad.exists(), "读不出也必须原样留着，绝不删")
        assertFalse(File(v.vaultDir, "auto-20261122-080000.json.gz").exists(), "不该产生半成品")
    }

    @Test
    fun `同名 gz 已存在且账号数相同才删旧明文`() {
        val root = newRoot()
        val v = vault(dataDirOf(root))
        v.vaultDir.mkdirs()
        val base = "auto-20261122-080000"
        File(v.vaultDir, "$base.json").writeText(envelope(5), Charsets.UTF_8)
        File(v.vaultDir, "$base.json.gz").writeBytes(AccountPack.encode(envelope(5)))

        val n = v.migrateLegacyPlaintext()
        assertEquals(1, n)
        assertFalse(File(v.vaultDir, "$base.json").exists(), "内容等价，留 gz 就够")
        assertEquals(5, countOf(File(v.vaultDir, "$base.json.gz")))
    }

    @Test
    fun `同名 gz 账号数不同则保留旧明文`() {
        val root = newRoot()
        val v = vault(dataDirOf(root))
        v.vaultDir.mkdirs()
        val base = "auto-20261122-080000"
        File(v.vaultDir, "$base.json").writeText(envelope(5), Charsets.UTF_8)
        // 同时间戳但内容不同（5 vs 3）——可能是用户手工替换过的，不能自作主张删
        File(v.vaultDir, "$base.json.gz").writeBytes(AccountPack.encode(envelope(3)))

        val n = v.migrateLegacyPlaintext()
        assertEquals(0, n, "内容不等价就不能删旧明文")
        assertTrue(File(v.vaultDir, "$base.json").exists(), "旧明文必须还在")
    }

    @Test
    fun `不碰前缀不符的文件`() {
        val root = newRoot()
        val v = vault(dataDirOf(root))
        v.vaultDir.mkdirs()
        File(v.vaultDir, "vault-state.json").writeText("""{"ok":true}""")
        File(v.vaultDir, "random.json").writeText(envelope(1))
        File(v.vaultDir, "notes.txt").writeText("hello")

        val n = v.migrateLegacyPlaintext()
        assertEquals(0, n, "只有 auto-/manual-/accounts- 前缀才归它管")
        assertTrue(File(v.vaultDir, "vault-state.json").exists(), "状态文件不能碰")
        assertTrue(File(v.vaultDir, "random.json").exists())
        assertTrue(File(v.vaultDir, "notes.txt").exists())
    }

    @Test
    fun `手动目录的旧明文也一起转`() {
        val root = newRoot()
        val v = vault(dataDirOf(root))
        v.manualDir.mkdirs()
        val old = File(v.manualDir, "manual-20261122-080000.json")
        old.writeText(envelope(2), Charsets.UTF_8)

        val n = v.migrateLegacyPlaintext()
        assertEquals(1, n)
        assertFalse(old.exists())
        assertTrue(File(v.manualDir, "manual-20261122-080000.json.gz").isFile)
    }

    @Test
    fun `幂等 跑两次第二次无事可做`() {
        val root = newRoot()
        val v = vault(dataDirOf(root))
        v.vaultDir.mkdirs()
        File(v.vaultDir, "auto-20261122-080000.json").writeText(envelope(2), Charsets.UTF_8)

        assertEquals(1, v.migrateLegacyPlaintext())
        assertEquals(0, v.migrateLegacyPlaintext(), "第二次不该再转出东西")
        assertEquals(2, countOf(File(v.vaultDir, "auto-20261122-080000.json.gz")))
    }

    @Test
    fun `转换后列表能正常读出新文件`() {
        val root = newRoot()
        val v = vault(dataDirOf(root))
        v.vaultDir.mkdirs()
        File(v.vaultDir, "auto-20261122-080000.json").writeText(envelope(7), Charsets.UTF_8)
        v.migrateLegacyPlaintext()

        val listed = v.list()
        val items = listed.get("items")!!.asJsonArray
        assertEquals(1, items.size(), "转换后列表里应该还是 1 份（不是 0 也不是 2）")
        assertEquals(7, items[0].asJsonObject.get("accountCount").asInt)
        assertTrue(items[0].asJsonObject.get("name").asString.endsWith(".json.gz"))
    }

    @Test
    fun `accounts- 老命名的文件也认`() {
        val root = newRoot()
        val v = vault(dataDirOf(root))
        v.vaultDir.mkdirs()
        val old = File(v.vaultDir, "accounts-startup-20261120-001122.json")
        old.writeText(envelope(4), Charsets.UTF_8)

        val n = v.migrateLegacyPlaintext()
        assertEquals(1, n, "上一版命名 accounts-<reason>-<stamp>.json 也要迁")
        assertTrue(File(v.vaultDir, "accounts-startup-20261120-001122.json.gz").isFile)
    }
}
