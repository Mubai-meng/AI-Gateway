package dev.aigw.desktop

import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.provider.DeviceAuthPoll
import java.awt.Desktop
import java.io.File
import java.net.URI
import kotlin.system.exitProcess

/**
 * WorkBuddy / CodeBuddy 设备码登录独立入口。
 *
 * 上游是「打开授权页 → 轮询换 token」，没有本地回调端口；CLI 的 browser-login 遇到
 * 轮询型会直接 return，所以这里单独给一条能一直跑完的链路。用系统浏览器打开授权页，
 * 每 3 秒轮询一次，最多等 6 分钟；成功后凭证写进加密存储，并打印可粘贴导入的 JSON。
 *
 * 用法：
 *   java -cp（install/lib 下全部 jar）dev.aigw.desktop.DeviceCodeLoginKt --data DIR [--region cn|global]
 */
fun main(args: Array<String>) {
    val providerId = option(args, "--provider") ?: "codebuddy"
    val region = option(args, "--region") ?: ""
    val dataDir = File(option(args, "--data") ?: defaultDataDir())
    if (!dataDir.exists()) dataDir.mkdirs()

    val engine = GatewayEngine(
        createDesktopStore(dataDir),
        preloadCallLogs = false,
        spillDir = null,
        onLog = { println("[aigw] $it") },
    )
    engine.migrateAccounts()

    val ticket = try {
        engine.startDeviceAuth(providerId, region)
    } catch (e: Exception) {
        println("创建设备授权失败：${e.message}")
        exitProcess(1)
    }

    println("供应商 : $providerId")
    println("区域   : ${region.ifBlank { "默认（国内 copilot.tencent.com）" }}")
    println("state  : ${ticket.state}")
    println("授权链接: ${ticket.loginUrl}")
    println()
    println("在浏览器完成授权后，本窗口自动轮询（每 3 秒，最多 6 分钟）…")
    System.out.flush()
    runCatching { Desktop.getDesktop().browse(URI(ticket.loginUrl)) }
        .onFailure { println("无法自动打开浏览器，请手动复制上面的链接") }

    val deadline = System.currentTimeMillis() + 6 * 60 * 1000L
    var attempt = 0
    while (System.currentTimeMillis() < deadline) {
        attempt++
        when (val result = engine.pollDeviceAuth(providerId, ticket.state, region)) {
            is DeviceAuthPoll.Success -> {
                val uid = result.account.uid
                println()
                println("登录成功：uid=$uid  nickname=${result.account.nickname}")
                println("凭证已写入加密存储：$dataDir")
                println("--- 可粘贴导入的凭证 JSON（GUI「粘贴凭证导入」用）---")
                println(engine.exportAccounts(setOf("$providerId/$uid")))
                println("--- JSON 结束 ---")
                println("桌面版若正在运行：重启一次即可看到账号，或直接粘贴上面的 JSON 即时生效")
                System.out.flush()
                exitProcess(0)
            }
            is DeviceAuthPoll.Failed -> {
                println("轮询失败：${result.error}")
                System.out.flush()
                exitProcess(1)
            }
            DeviceAuthPoll.Pending -> {
                if (attempt <= 3 || attempt % 10 == 0) println("等待授权… 已轮询 $attempt 次")
                System.out.flush()
                Thread.sleep(3000)
            }
        }
    }
    println("等待授权超时（6 分钟），state 可能已失效，重新运行本脚本即可。")
    exitProcess(2)
}

private fun option(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private fun defaultDataDir(): String {
    System.getenv("AIGW_DATA")?.let { return it }
    val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
    return File(base, "AIGateway/data").absolutePath
}
