package dev.aigw.desktop

import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.store.FileKeyValueStore
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.exitProcess

/**
 * 登录前置链路探针：不依赖真人授权，逐家验证 Windows 版能否生成合法授权 URL、
 * 拉起本地回调端口、或取得设备码 state。真人授权那一步留给使用者验证。
 *
 * 每家独立 engine + 10 秒超时：上游设备码端点挂起时不会拖死整轮探测。
 * 运行：java -cp（install/lib 下全部 jar）dev.aigw.desktop.LoginProbeKt
 */
fun main() {
    val dir = File(System.getProperty("java.io.tmpdir"), "aigw-loginprobe-${System.nanoTime()}")
    dir.mkdirs()
    val catalog = GatewayEngine(FileKeyValueStore(File(dir, "catalog")), onLog = {})
    val providers = catalog.providers()
    val ports = intArrayOf(51120, 51121, 51122, 1455)

    for (p in providers) {
        val out = StringBuilder("${p.id.padEnd(14)} ${p.authKind.name.padEnd(18)} ")
        when (p.authKind) {
            AuthKind.NONE -> out.append("SKIP  no login required (paste baseUrl + key)")
            AuthKind.SMS_CODE -> out.append("SKIP  needs a real phone number / SMS code")
            else -> {
                val engine = GatewayEngine(FileKeyValueStore(File(dir, p.id)), onLog = {})
                val result = withTimeout(10) { engine.beginBrowserLogin(p.id) }
                result.fold(
                    onSuccess = { ticket ->
                        val open = ports.filter { portOpen(it) }
                        out.append(
                            "url=${if (ticket.loginUrl.startsWith("http")) "OK" else "BAD"} " +
                                "len=${ticket.loginUrl.length} pollState=${ticket.pollState.isNotBlank()} " +
                                "region=${ticket.region.ifBlank { "-" }} listening=$open"
                        )
                        out.append("\n    ${ticket.loginUrl.take(180)}")
                        runCatching { engine.stopCallbackServer() }
                    },
                    onFailure = { e ->
                        out.append(
                            if (e is TimeoutException) "TIMEOUT  upstream did not answer within 10s"
                            else "FAIL  ${e::class.simpleName}: ${e.message}"
                        )
                        runCatching { engine.stopCallbackServer() }
                    },
                )
            }
        }
        println(out)
        System.out.flush()
        Thread.sleep(200)
    }
    println("providers=${providers.size}")
    System.out.flush()
    // LoopbackCallbackServer 可能留下非 daemon 线程，探测结束直接退出
    exitProcess(0)
}

private fun <T> withTimeout(seconds: Long, block: () -> T): Result<T> {
    val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "login-probe").apply { isDaemon = true } }
    return try {
        val future = pool.submit(Callable { block() })
        try {
            Result.success(future.get(seconds, TimeUnit.SECONDS))
        } catch (e: TimeoutException) {
            future.cancel(true)
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    } finally {
        pool.shutdownNow()
    }
}

private fun portOpen(port: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300); true }
} catch (_: Exception) {
    false
}
