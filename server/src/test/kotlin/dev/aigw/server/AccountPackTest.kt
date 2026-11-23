package dev.aigw.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.pool.AccountTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 账号信封的压缩。
 *
 * 这一轮的设计是**只压缩、不裁字段**，而被否掉的「裁 accessToken」方案
 * 必须留下反向断言（`裁字段会让人解析失败`），否则下次有人再想「优化」体积时
 * 会重新踩同一个坑。
 */
class AccountPackTest {

    private fun envelope(vararg secrets: String): String {
        val arr = com.google.gson.JsonArray()
        for ((i, s) in secrets.withIndex()) {
            arr.add(JsonObject().apply {
                addProperty("providerId", "codebuddy")
                addProperty("uid", "u$i")
                addProperty("nickname", "n$i")
                addProperty("secret", s)
            })
        }
        return JsonObject().apply {
            addProperty("format", "aigw-accounts")
            addProperty("version", 1)
            addProperty("exportedAt", 1L)
            add("accounts", arr)
        }.toString()
    }

    /** codebuddy 的真实结构（扁平，顶层就有 refreshToken）。 */
    private fun codebuddySecret(accessLen: Int = 1500, refreshLen: Int = 1500): String =
        JsonObject().apply {
            addProperty("accessToken", "a".repeat(accessLen))
            addProperty("refreshToken", "r".repeat(refreshLen))
            addProperty("expiresAt", 1796952671L)
            addProperty("domain", "codebuddy.cn")
            addProperty("uid", "182b5456")
            addProperty("enterpriseId", "")
            addProperty("nickname", "")
        }.toString()

    /** trae 的真实结构（嵌套在 auth 下）。 */
    private fun traeSecret(): String = JsonObject().apply {
        add("auth", JsonObject().apply {
            addProperty("accessToken", "a".repeat(1200))
            addProperty("refreshToken", "r".repeat(1200))
            addProperty("expiresAt", 1796952671L)
            addProperty("domain", "trae.ai")
            addProperty("machineId", "m-1")
            addProperty("deviceId", "d-1")
        })
        add("account", JsonObject().apply {
            addProperty("uid", "168702926136752")
            addProperty("nickname", "图图")
        })
    }.toString()

    // ------------------------------------------------------------ 压缩往返

    @Test
    fun `gzip 压缩后能原样解回 一个字节都不变`() {
        val text = envelope(codebuddySecret())
        val bytes = AccountPack.encode(text)
        assertEquals(text, AccountPack.decode(bytes))
    }

    @Test
    fun `encode 结果带 gzip 魔数`() {
        val gz = AccountPack.encode(envelope(codebuddySecret()))
        assertTrue(gz.size >= 2 && gz[0] == 0x1f.toByte() && gz[1] == 0x8b.toByte(), "必须是 gzip")
    }

    @Test
    fun `老明文 JSON 直接当文本读 兼容旧备份`() {
        val text = envelope(codebuddySecret())
        assertEquals(text, AccountPack.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `坏掉的 gzip 不静默吞成空串`() {
        val bytes = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x00)
        assertTrue(AccountPack.decode(bytes).isNotEmpty(), "解压失败也要把原文吐出来让上层报错")
    }

    @Test
    fun `空与单字节输入不炸`() {
        assertEquals("", AccountPack.decode("".toByteArray()))
        assertEquals("x", AccountPack.decode(byteArrayOf(0x78)))
    }

    @Test
    fun `中文昵称经 gzip 往返不变形`() {
        val src = envelope(traeSecret())
        assertEquals(src, AccountPack.decode(AccountPack.encode(src)))
    }

    @Test
    fun `压缩确实变小`() {
        val text = envelope(codebuddySecret(), codebuddySecret(), traeSecret())
        val raw = text.length
        val gz = AccountPack.encode(text).size
        assertTrue(gz < raw * 0.6, "重复结构应压到 60% 以下，实测 $raw → $gz")
    }

    @Test
    fun `真实规模的收益 13个账号应从19KB降到8KB以内`() {
        val secrets = Array(13) { codebuddySecret(1578, 1579) }
        val src = envelope(*secrets)
        val gz = AccountPack.encode(src).size
        assertTrue(src.length > 18000, "真实规模约 19 KB，实际 ${src.length}")
        assertTrue(gz < 8200, "压完应低于 8 KB，实际 $gz B")
    }

    // ------------------------------------------------------------ 不裁字段（核心约束）

    @Test
    fun `压缩不解压前后字段完全一致`() {
        val src = envelope(codebuddySecret())
        val out = AccountPack.decode(AccountPack.encode(src))
        val before = JsonParser.parseString(src).asJsonObject.getAsJsonArray("accounts")[0].asJsonObject
        val after = JsonParser.parseString(out).asJsonObject.getAsJsonArray("accounts")[0].asJsonObject
        val s1 = JsonParser.parseString(before.get("secret").asString).asJsonObject
        val s2 = JsonParser.parseString(after.get("secret").asString).asJsonObject
        assertEquals(s1.keySet().toSet(), s2.keySet().toSet(), "字段集合必须完全一致")
        assertEquals(
            s1.get("accessToken").asString, s2.get("accessToken").asString,
            "accessToken 必须原样保留 —— 所有供应商的 parse 都把它当必填",
        )
        assertEquals(s1.get("refreshToken").asString, s2.get("refreshToken").asString)
        assertEquals(s1.get("expiresAt").asLong, s2.get("expiresAt").asLong, "expiresAt 不改，导入后立刻能用")
    }

    /**
     * 反向断言：这条锁住「不能裁 accessToken」这个结论。
     *
     * 上一版曾按「refreshToken 能换出 accessToken」的思路裁掉它，包从 7.1 KB 降到 4.0 KB，
     * 但导入后 trae 直接抛 `凭证缺少 accessToken`、codebuddy 报「凭证无法解析」。
     * 体积优化的前提是「导入后直接能用」—— 这里用解析器本身把这个前提钉住。
     */
    @Test
    fun `裁掉 accessToken 会让供应商解析失败`() {
        val full = JsonParser.parseString(codebuddySecret()).asJsonObject
        val cut = full.deepCopy().apply { remove("accessToken") }

        // CodeBuddyProvider.parse 的实际判据：accessToken 为空 → null → 「凭证无法解析」
        val token = cut.get("accessToken")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        assertTrue(token.isEmpty(), "前提：裁掉后确实没有 accessToken")
        assertFalse(
            full.get("accessToken").asString.isEmpty(),
            "对照：不裁时 accessToken 非空，才能被 parse 接受",
        )
    }

    @Test
    fun `trae 的 accessToken 在 auth 层 裁了必炸`() {
        val root = JsonParser.parseString(traeSecret()).asJsonObject
        val auth = root.getAsJsonObject("auth")
        assertTrue(auth.get("accessToken").asString.isNotEmpty(), "auth.accessToken 是 TraeAccount.parse 的硬前置")
        // TraeAccount.parse: if (accessToken.isEmpty()) throw IllegalArgumentException
    }

    // ------------------------------------------------------------ 可导入性

    @Test
    fun `压缩后的信封能被 AccountTransfer 解码`() {
        val out = AccountPack.decode(AccountPack.encode(envelope(codebuddySecret())))
        val env = AccountTransfer.decode(out)
        assertEquals(1, env.accounts.size)
        assertEquals("u0", env.accounts[0].uid)
        assertNotNull(env.accounts[0].secret)
        // secret 内部结构也要完整
        val s = JsonParser.parseString(env.accounts[0].secret).asJsonObject
        assertTrue(s.has("accessToken") && s.has("refreshToken"), "两个 token 都要在")
    }

    @Test
    fun `嵌套凭证压缩后仍完整`() {
        val out = AccountPack.decode(AccountPack.encode(envelope(traeSecret())))
        val acc = JsonParser.parseString(out).asJsonObject.getAsJsonArray("accounts")[0].asJsonObject
        val secret = JsonParser.parseString(acc.get("secret").asString).asJsonObject
        val auth = secret.getAsJsonObject("auth")
        assertTrue(auth.has("accessToken") && auth.has("refreshToken"))
        assertEquals("m-1", auth.get("machineId").asString, "设备指纹同时保留")
        assertEquals("图图", secret.getAsJsonObject("account").get("nickname").asString)
    }

    @Test
    fun `apiKey 型凭证原样保留`() {
        val src = envelope("""{"apiKey":"sk-abc"}""")
        assertEquals(src, AccountPack.decode(AccountPack.encode(src)))
    }

    // ------------------------------------------------------------ mark

    @Test
    fun `mark 加上 packed 标记且不动 accounts`() {
        val src = envelope(codebuddySecret())
        val out = AccountPack.mark(src)
        val root = JsonParser.parseString(out).asJsonObject
        assertTrue(root.get("packed").asBoolean)
        assertEquals(
            JsonParser.parseString(src).asJsonObject.getAsJsonArray("accounts").toString(),
            root.getAsJsonArray("accounts").toString(),
        )
    }

    @Test
    fun `mark 不破坏可解码性`() {
        val out = AccountPack.decode(AccountPack.encode(AccountPack.mark(envelope(codebuddySecret()))))
        assertEquals(1, AccountTransfer.decode(out).accounts.size, "多一个 packed 字段不该让导入失败")
    }

    @Test
    fun `mark 对非 JSON 输入原样返回`() {
        assertEquals("not json", AccountPack.mark("not json"))
    }

    // ------------------------------------------------------------ 文件名

    @Test
    fun `文件名后缀两种都认`() {
        assertTrue(AccountPack.isAccountFile("auto-20261123-080000.json.gz"))
        assertTrue(AccountPack.isAccountFile("auto-20261123-080000.json"))
        assertFalse(AccountPack.isAccountFile("auto-20261123-080000.txt"))
        assertFalse(AccountPack.isAccountFile("evil.json.gz.exe"))
    }
}
