package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.pool.AccountTransfer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 跨机迁移：自定义供应商必须能**无损**跟着账号一起过去。
 *
 * 回归背景（用户明确要求「必须能够无损导入进去」）：
 * 早先导出只带账号（providerId / uid / secret），不带供应商定义。
 * `custom:*` 账号到了另一台机器上，`registry.get("custom:wpsai")` 返回 null
 * → 整条账号被判「未识别」丢掉。用户看到的「新增 12、未识别 1」就是它。
 *
 * 这不是可以接受的设计取舍：账号是靠 baseUrl 才能发请求的，
 * 不搬供应商定义等于搬来一把没有锁孔的钥匙。
 */
class CustomProviderTransferTest {

    private fun engine() = GatewayEngine(InMemoryKeyValueStore())

    private fun config(
        key: String = "wpsai",
        name: String = "WPSAI",
        baseUrl: String = "https://api.wpsai.example.com/v1",
        keys: List<String> = listOf("sk-wps-1"),
    ) = CustomProviderConfig(
        key = key, name = name, baseUrl = baseUrl, apiKeys = keys, models = listOf("gpt-4o-mini"),
    )

    // ------------------------------------------------------------ 导出带定义

    @Test
    fun `导出账号时带上自定义供应商定义`() {
        val engine = engine()
        engine.settingsRepository.saveCustomProviders(listOf(config()))
        engine.reloadCustomProviders()
        engine.syncCustomAccounts(config())

        val json = engine.exportAccounts()
        val env = AccountTransfer.decode(json)

        assertEquals(1, env.accounts.size, "应该导出一个账号")
        assertEquals("custom:wpsai", env.accounts[0].providerId)
        assertEquals(1, env.providers.size, "供应商定义必须一起带上，否则到了新机器用不了")

        val p = env.providers[0]
        assertEquals("custom:wpsai", p.providerId)
        assertEquals("wpsai", p.key)
        assertEquals("WPSAI", p.name)
        assertEquals("https://api.wpsai.example.com/v1", p.baseUrl, "baseUrl 是账号能发请求的前提")
        assertEquals(listOf("gpt-4o-mini"), p.models)
    }

    @Test
    fun `不导出 apiKeys 避免同一密钥在包里出现两次`() {
        val engine = engine()
        engine.settingsRepository.saveCustomProviders(listOf(config(keys = listOf("sk-a", "sk-b"))))
        engine.reloadCustomProviders()
        // 必须有账号才会带上 providers 段（providers 是按"这批账号用到哪些供应商"挑的）
        engine.syncCustomAccounts(config(keys = listOf("sk-a", "sk-b")))

        val json = engine.exportAccounts()
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        val provs = root.getAsJsonArray("providers")
        assertEquals(1, provs.size())
        val p = provs[0].asJsonObject
        // 明文 key 已经在 accounts[].secret 里。配置里再带一份会诱发
        // syncCustomAccounts 按配置增删账号池（见下一条测试）。
        assertTrue(p.has("apiKeys") == false, "providers 段不该带 apiKeys，实际 JSON：" + p.toString())
    }

    @Test
    fun `没有自定义账号时不带 providers 段`() {
        val engine = engine()
        val json = engine.exportAccounts()
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        assertTrue(root.get("providers") == null, "全是内置供应商时不该多出这一段")
    }

    @Test
    fun `只导出部分账号时也只带相关供应商`() {
        val engine = engine()
        engine.settingsRepository.saveCustomProviders(listOf(config(key = "wpsai"), config(key = "other")))
        engine.reloadCustomProviders()
        engine.syncCustomAccounts(config(key = "wpsai"))

        val only = engine.accounts("custom:wpsai").map { "custom:wpsai/${it.uid}" }.toSet()
        val json = engine.exportAccounts(selection = only)
        val env = AccountTransfer.decode(json)
        assertEquals(1, env.providers.size, "选了哪些账号就只带它们用的供应商，别把不相干的服务地址也带走")
        assertEquals("custom:wpsai", env.providers[0].providerId)
    }

    // ------------------------------------------------------------ 导入重建（核心）

    @Test
    fun `导入到空机器时自动补齐供应商并落账号`() {
        // 源机：有供应商、有账号，导出
        val src = engine()
        src.settingsRepository.saveCustomProviders(listOf(config()))
        src.reloadCustomProviders()
        src.syncCustomAccounts(config())
        val json = src.exportAccounts()

        // 目标机：全新，连配置都没有
        val dst = engine()
        assertTrue(dst.providers().none { it.id == "custom:wpsai" }, "前提：目标机确实没有这个供应商")

        val r = dst.importAccounts(json, overwrite = false)

        assertEquals(0, r.unknownProviders.size, "不该再有「未识别」——这是这次修复的核心")
        assertEquals(1, r.added.size, "账号应该真的进了池")
        assertEquals(listOf("custom:wpsai"), r.adoptedProviders, "要报出补了哪个供应商")
        assertEquals(1, dst.accounts("custom:wpsai").size, "账号池里查得到")
        // registry 也要真的登记上：不 reload 的话 providers() 里看不到
        assertTrue(dst.providers().any { it.id == "custom:wpsai" }, "供应商页要能看到它")
    }

    @Test
    fun `补齐的供应商配置能读回来且 apiKeys 为空`() {
        val src = engine()
        src.settingsRepository.saveCustomProviders(listOf(config()))
        src.reloadCustomProviders()
        src.syncCustomAccounts(config())
        val json = src.exportAccounts()

        val dst = engine()
        dst.importAccounts(json, overwrite = false)

        val saved = dst.settingsRepository.loadCustomProviders()
        assertEquals(1, saved.size, "配置必须落盘，否则重启就丢")
        val c = saved[0]
        assertEquals("wpsai", c.key)
        assertEquals("https://api.wpsai.example.com/v1", c.baseUrl)
        assertEquals(listOf("gpt-4o-mini"), c.models)
        // 关键：apiKeys 必须为空。非空会让 syncCustomAccounts 把用户自己另加的 key 当多余项删掉。
        assertTrue(c.apiKeys.isEmpty(), "补齐的配置不能带 apiKeys")
    }

    @Test
    fun `目标机已有同名供应商时不被覆盖`() {
        val src = engine()
        src.settingsRepository.saveCustomProviders(listOf(config(baseUrl = "https://old.example.com/v1")))
        src.reloadCustomProviders()
        src.syncCustomAccounts(config(baseUrl = "https://old.example.com/v1"))
        val json = src.exportAccounts()

        // 目标机：同 key 但 baseUrl 不同（用户自己配的）
        val dst = engine()
        val local = config(baseUrl = "https://my-local.example.com/v1", keys = emptyList())
        dst.settingsRepository.saveCustomProviders(listOf(local))
        dst.reloadCustomProviders()

        val r = dst.importAccounts(json, overwrite = false)

        assertEquals(0, r.adoptedProviders.size, "已有同名供应商就不该再补一个")
        val saved = dst.settingsRepository.loadCustomProviders()
        assertEquals(1, saved.size, "不能变成两份")
        assertEquals(
            "https://my-local.example.com/v1",
            saved[0].baseUrl,
            "用户自己调过的 baseUrl 不该被另一台机器的备份覆盖",
        )
        // 账号本身仍然要进池（providerId 一致，registry 已经有了）
        assertEquals(1, dst.accounts("custom:wpsai").size)
    }

    @Test
    fun `老备份没有 providers 段时行为不变 仍报未识别`() {
        // 模拟 v1 老文件：只有 accounts，没有 providers
        val legacy = """
            {"format":"aigw-accounts","version":1,"exportedAt":1,
             "accounts":[{"providerId":"custom:wpsai","uid":"key-abc","nickname":"WPSAI",
                          "secret":"{\"apiKey\":\"sk-wps\"}"}]}
        """.trimIndent()

        val dst = engine()
        val r = dst.importAccounts(legacy, overwrite = false)

        // 老包确实没法重建供应商（包里没有 baseUrl），只能如实报未识别——
        // 这是数据的限制，不是逻辑的缺陷。新包不会有这个现象。
        assertEquals(1, r.unknownProviders.size, "老包缺 baseUrl 只能报未识别")
        assertEquals(0, r.adoptedProviders.size)
        assertEquals(0, dst.accounts("custom:wpsai").size)
    }

    @Test
    fun `导入后账号能查到且 secret 完整`() {
        val src = engine()
        src.settingsRepository.saveCustomProviders(listOf(config()))
        src.reloadCustomProviders()
        src.syncCustomAccounts(config(keys = listOf("sk-roundtrip")))
        val json = src.exportAccounts()

        val dst = engine()
        dst.importAccounts(json, overwrite = false)

        val acc = dst.account("custom:wpsai", dev.aigw.core.provider.custom.CustomProvider.uidOf("sk-roundtrip"))
        assertTrue(acc != null, "账号要能按 uid 查到")
        assertTrue(acc!!.secret.contains("sk-roundtrip"), "apiKey 要完整搬过来，不能只剩空壳")
    }
}
