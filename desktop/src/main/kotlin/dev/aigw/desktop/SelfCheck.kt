package dev.aigw.desktop

import com.google.gson.GsonBuilder
import dev.aigw.core.failover.ModelFailoverSettings
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.MiddlewareSettings
import dev.aigw.core.gateway.ModelExposureSettings
import dev.aigw.core.gateway.ProxySettings
import dev.aigw.core.gateway.RoutingGroupSettings
import dev.aigw.core.security.SecuritySettings
import dev.aigw.core.usage.PricingTable
import java.io.File
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * 自检入口：验证加密存储往返，以及「高级设置」页 8 类设置对象的 Gson 读写能否真正走通。
 * 用临时目录，不碰真实账号数据。
 *
 * 运行：java -cp（install/lib 下全部 jar）dev.aigw.desktop.SelfCheckKt
 */
fun main() {
    val gson = GsonBuilder().setPrettyPrinting().create()
    val dir = File(System.getProperty("java.io.tmpdir"), "aigw-selfcheck-${System.nanoTime()}")
    dir.mkdirs()
    val store = createDesktopStore(dir)
    val engine = GatewayEngine(store, preloadCallLogs = false, onLog = {})
    var failed = 0

    fun check(name: String, block: () -> Unit) {
        try {
            block()
            println("PASS  $name")
        } catch (e: Exception) {
            failed++
            println("FAIL  $name : ${e::class.simpleName}: ${e.message}")
        }
    }

    check("Windows 注册表读写（开机自启链路）") {
        require(Startup.registryRoundTrip()) { "reg.exe 调用链不可用" }
    }

    check("KDesign 浅色 / 暗黑双令牌") {
        require(KLIGHT.bg != KDARK.bg && KLIGHT.top != KDARK.top) { "两套背景未区分" }
        require(KLIGHT.text != KDARK.text && KLIGHT.brand != KDARK.brand) { "文字/主色未区分" }
        require(KLIGHT.success != KDARK.success && KLIGHT.successBg != KDARK.successBg) { "语义色未区分" }
    }

    check("双主题 UIDefaults 安装") {
        for (palette in listOf(KLIGHT, KDARK)) {
            K.p = palette
            installThemeDefaults()
            require(UIManager.get("Panel.background") == palette.bg) { "Panel.background 未跟随主题" }
            require(UIManager.get("Label.foreground") == palette.text) { "Label.foreground 未跟随主题" }
            require(UIManager.get("TextField.background") == palette.top) { "TextField.background 未跟随主题" }
        }
        K.p = KDARK
        installThemeDefaults()
    }

    check("加密存储读写与枚举") {
        store.write("selftest/key", "敏感凭证-DO-NOT-LEAK")
        require(store.read("selftest/key") == "敏感凭证-DO-NOT-LEAK")
        require(store.keys("selftest/").contains("selftest/key"))
        store.delete("selftest/key")
        require(store.read("selftest/key") == null)
    }

    check("落盘密文不含明文") {
        store.write("selftest/secret", "SECRET_TOKEN_ABC")
        val bytes = File(dir, "secure/selftest/secret").readBytes()
        require(bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x41, 0x47, 0x57, 0x31))) { "缺少 AGW1 魔数" }
        require(!String(bytes, Charsets.UTF_8).contains("SECRET_TOKEN_ABC")) { "密文里出现明文" }
        require(File(dir, "master.key").readBytes().size == 32) { "主密钥长度异常" }
        store.delete("selftest/secret")
    }

    check("GatewaySettings JSON 往返") {
        engine.updateSettings(gson.fromJson(gson.toJson(engine.settings()), GatewaySettings::class.java))
    }
    check("ModelFailoverSettings JSON 往返") {
        engine.updateFailoverSettings(gson.fromJson(gson.toJson(engine.failoverSettings()), ModelFailoverSettings::class.java))
    }
    check("SecuritySettings JSON 往返") {
        engine.updateSecuritySettings(gson.fromJson(gson.toJson(engine.securitySettings()), SecuritySettings::class.java))
    }
    check("MiddlewareSettings JSON 往返") {
        engine.updateMiddlewareSettings(gson.fromJson(gson.toJson(engine.middlewareSettings()), MiddlewareSettings::class.java))
    }
    check("RoutingGroupSettings JSON 往返") {
        engine.updateRoutingGroups(gson.fromJson(gson.toJson(engine.routingGroupSettings()), RoutingGroupSettings::class.java))
    }
    check("PricingTable JSON 往返") {
        engine.updatePricingTable(gson.fromJson(gson.toJson(engine.pricingTable()), PricingTable::class.java))
    }
    check("ModelExposureSettings JSON 往返") {
        engine.updateModelExposure(gson.fromJson(gson.toJson(engine.modelExposureSettings()), ModelExposureSettings::class.java))
    }
    check("ProxySettings JSON 往返") {
        engine.updateProxySettings(gson.fromJson(gson.toJson(engine.proxySettings()), ProxySettings::class.java))
    }
    check("客户端密钥增删") {
        val key = engine.clientKeyStore.add("selfcheck")
        require(engine.clientKeyStore.byId(key.id) != null)
        engine.clientKeyStore.remove(key.id)
        require(engine.clientKeyStore.byId(key.id) == null)
    }
    check("客户端密钥 JSON 往返（含白名单与限额）") {
        val key = engine.clientKeyStore.add(
            "selfcheck-json",
            allowedModels = listOf("trae-cn/*"),
            limits = listOf(
                dev.aigw.core.gateway.KeyLimit(
                    period = dev.aigw.core.gateway.LimitPeriod.DAY,
                    maxTokens = 1000L,
                )
            ),
        )
        val json = gson.toJson(engine.clientKeyStore.list())
        val restored = gson.fromJson(json, Array<dev.aigw.core.gateway.ClientKey>::class.java)
        require(restored.any { it.id == key.id && it.limits.size == 1 && it.allowedModels == listOf("trae-cn/*") }) {
            "反序列化后白名单/限额丢失"
        }
        engine.clientKeyStore.remove(key.id)
    }

    println(if (failed == 0) "ALL PASS" else "$failed FAILED")
    exitProcess(if (failed == 0) 0 else 1)
}
