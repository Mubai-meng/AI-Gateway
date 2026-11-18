package dev.aigw.desktop

import java.io.File

/**
 * Windows 开机自启：写 HKCU Run 项，不需要管理员权限。
 * 只有 jpackage 打包版才有稳定的 exe 路径；开发态（java/javaw 直接跑）不启用。
 */
object Startup {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "AIGateway"

    /** jpackage app-image / MSI 安装后：<app>/runtime 与 <app>/AI Gateway.exe 同级。 */
    fun launcherCommand(): String? {
        val javaHome = System.getProperty("java.home") ?: return null
        val appRoot = File(javaHome).parentFile ?: return null
        val exe = File(appRoot, "AI Gateway.exe")
        return if (exe.isFile) "\"${exe.absolutePath}\"" else null
    }

    fun isEnabled(): Boolean {
        if (launcherCommand() == null) return false
        val (code, _) = reg("query", "HKCU\\$RUN_KEY", "/v", VALUE_NAME)
        return code == 0
    }

    fun setEnabled(on: Boolean): Boolean {
        val command = launcherCommand() ?: return false
        val result = if (on) {
            reg("add", "HKCU\\$RUN_KEY", "/v", VALUE_NAME, "/t", "REG_SZ", "/d", command, "/f")
        } else {
            reg("delete", "HKCU\\$RUN_KEY", "/v", VALUE_NAME, "/f")
        }
        return result.first == 0
    }

    /** 自检：验证 reg.exe 调用链可用，用临时项，不改动真实的 AIGateway 值。 */
    fun registryRoundTrip(): Boolean {
        val probe = "${VALUE_NAME}SelfTest"
        val added = reg("add", "HKCU\\$RUN_KEY", "/v", probe, "/t", "REG_SZ", "/d", "probe", "/f").first == 0
        val present = reg("query", "HKCU\\$RUN_KEY", "/v", probe).first == 0
        reg("delete", "HKCU\\$RUN_KEY", "/v", probe, "/f")
        return added && present
    }

    private fun reg(vararg args: String): Pair<Int, String> = try {
        val process = ProcessBuilder("reg", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor() to output
    } catch (e: Exception) {
        -1 to (e.message ?: "")
    }
}
