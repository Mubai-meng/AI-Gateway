package dev.aigw.server

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.Properties

/**
 * 「一个数据目录 = 一个实例」的隔离规则。
 *
 * 旧实现把所有实例身份绑在三个**固定端口**上：8791（单实例锁）、8792（UI API）。
 * 于是「换数据目录跑第二个实例」永远做不成——就算数据完全分开，第二个进程也会在
 * 第一步启动锁端口时就退出。实际后果是每次验证新行为都得先把正式实例杀掉，验证完
 * 再拉起来：两个目录的数据本来互不可见，进程却互相排斥。
 *
 * 现在改成：
 *   1. **锁 = 数据目录下的文件锁**（[LOCK_FILE]）。同一目录第二个进程被挡，不同目录
 *      互不影响。文件锁比端口锁更贴近真实语义：要防的是「两个进程写同一份加密存储」，
 *      而不是「同机只能开一个网关」。
 *   2. **UI 端口按目录派生**。默认目录仍用历史上的 8792，行为完全不变；其它目录从一条
 *      稳定哈希落到 [派生区间][base, base+SPAN)，真的撞上时依次往后探到空位。
 *   3. **端口写回 [PORT_FILE]**，外壳据此连 UI API——它不再硬编码 8792。
 *
 * 网关端口（用户可在网关页改，存在 settings/gateway.json）**不在这里硬改**：
 * 默认目录完全沿用配置值；只有派生目录才在启动失败时依次换下一个空闲端口，
 * 见 [nextFreePort]。这样老用户升级后行为零变化。
 */
object InstanceScope {

    const val LOCK_FILE = ".instance.lock"
    const val PORT_FILE = ".instance.json"

    /** 仓库里 `%APPDATA%\AIGateway\data` 的默认目录用历史端口，老用户升级后行为不变。 */
    private const val DEFAULT_DATA_SUBDIR = "AIGateway/data"

    private const val UI_PORT_DEFAULT = 8792

    /**
     * 派生区间起点与宽度。
     *
     * 100 个位置意味着最多 100 个派生目录能并存；实测本机验证从来不超过 3 个实例，
     * 这个量级够用。开在 18000 以上是为了不和 8790/8791/8792 这些「默认目录专用」
     * 的端口混在一起——看到 18xxx 就知道是个派生实例。
     */
    private const val SPAN = 100
    private const val DERIVED_BASE = 18100

    /** 拿到的实例身份。锁句柄必须全程持有，进程退出（哪怕崩溃）系统才会释放。 */
    class Scope(
        val dataDir: File,
        val uiPort: Int,
        /** 派生实例的首选网关端口；默认目录为 null（沿用 settings/gateway.json 里的值）。 */
        val gatewayPort: Int?,
        /** true 表示端口是从目录哈希派生出来的（非默认目录），给日志与界面用。 */
        val derived: Boolean,
        private val lockFile: RandomAccessFile,
        private val lock: FileLock,
        /**
         * 旧版 Swing 的单实例锁端口（仅默认目录持有）。
         *
         * 为什么要留：Swing 版还在仓库里、用户随时可能回退运行，它只会抢 8791、
         * 不认识文件锁。默认目录两边都会用，所以那一个 8791 必须继续占住——
         * 否则 Swing 版和 WebView2 版会同时写同一份加密存储。
         * 派生目录与 Swing 版无关（它没法指定数据目录），不占。
         */
        private val legacyLock: ServerSocket? = null,
    ) {
        fun release() {
            runCatching { lock.release() }
            runCatching { lockFile.close() }
            runCatching { legacyLock?.close() }
        }

        /**
         * 端口文件写盘：外壳（独立进程）只能靠它知道该连哪个端口。
         *
         * [uiPort] 必须传**实际监听**的端口，不能直接用 [Scope.uiPort]：
         * 命令行 `--port` 会覆盖派生值，写派生值会让外壳连到一个没人监听的号上。
         */
        fun writePortFile(gatewayPort: Int, uiPort: Int = this.uiPort) {
            val tmp = File(dataDir, "$PORT_FILE.tmp")
            val props = Properties().apply {
                setProperty("uiPort", uiPort.toString())
                setProperty("gatewayPort", gatewayPort.toString())
                setProperty("pid", ProcessHandle.current().pid().toString())
                setProperty("dataDir", dataDir.absolutePath)
                setProperty("derived", derived.toString())
            }
            runCatching {
                tmp.outputStream().use { props.store(it, "AI Gateway instance ports") }
                // 原子替换：外壳可能正好在这一刻读，半截文件会让它解析失败
                java.nio.file.Files.move(
                    tmp.toPath(), File(dataDir, PORT_FILE).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }

    /** 同一目录已被占用时抛这个，调用方负责给出「已在运行」的提示。 */
    class AlreadyRunning(val dataDir: File) :
        Exception("数据目录已被另一个实例占用：${dataDir.absolutePath}")

    /**
     * 抢占数据目录。
     *
     * 先拿文件锁再挑端口：端口是稀缺资源，锁才是身份。顺序反过来时两个进程可能
     * 同时探到同一个空端口，失败方白占一个号一段时间。
     */
    fun acquire(dataDir: File): Scope {
        if (!dataDir.exists()) dataDir.mkdirs()
        val lockPath = File(dataDir.absoluteFile, LOCK_FILE)
        val raf = RandomAccessFile(lockPath, "rw")
        val fileLock = try {
            raf.channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null // 同 JVM 内重复调用（测试里会出现），按已占用处理
        } ?: run {
            raf.close()
            throw AlreadyRunning(dataDir)
        }

        val canonical = canonicalOf(dataDir)
        val isDefault = isDefaultDataDir(canonical)
        val start = if (isDefault) {
            UI_PORT_DEFAULT
        } else {
            DERIVED_BASE + hashSeed(canonical) * 2
        }
        val ui = fitPort(start, preferUiNoLock = true) ?: run {
            runCatching { fileLock.release() }
            runCatching { raf.close() }
            throw IllegalStateException("端口区间内已无空闲端口（数据目录：$canonical）")
        }

        // 派生实例的网关端口也必须避开默认实例的 8790：两个实例同时开着时，
        // 第二个实例如果直接用配置里的 8790 就必然绑不上，等于没隔离。
        val gw: Int? = if (isDefault) null else fitPort(ui + 1, skip = setOf(ui))

        // 默认目录额外占住旧 Swing 版的锁端口：它是唯一「两边都会用到」的目录。
        val legacy = if (isDefault) try {
            ServerSocket(8791, 1, InetAddress.getByName("127.0.0.1"))
        } catch (_: Exception) {
            runCatching { fileLock.release() }
            runCatching { raf.close() }
            throw AlreadyRunning(dataDir)
        } else null

        return Scope(dataDir.absoluteFile, ui, gw, !isDefault, raf, fileLock, legacy)
    }

    /**
     * 从 [start] 起找第一个能真正绑上的端口。
     *
     * 必须实际 `ServerSocket` 监听再关掉：只看 `isBound` 之类会漏掉 TIME_WAIT
     * 与「别的进程刚释放」的错觉。这里要的是确定性的空位。
     *
     * [preferUiNoLock] 用于 UI 端口——跳过 8791（旧 Swing 版的锁端口），绝不能占。
     */
    fun fitPort(start: Int, preferUiNoLock: Boolean = false, skip: Set<Int> = emptySet()): Int? {
        for (offset in 0 until SPAN) {
            val candidate = start + offset
            if (candidate !in 1..65535) continue
            if (preferUiNoLock && candidate == 8791) continue
            if (candidate in skip) continue
            if (isFree(candidate)) return candidate
        }
        return null
    }

    /** 网关端口换一个：只在启动失败（被占用）时用，默认目录也一样会走到这里。 */
    fun nextFreePort(after: Int, skip: Set<Int>): Int? = fitPort(after + 1, false, skip)

    private fun isFree(port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { true }
    } catch (_: Exception) {
        false
    }

    /** 稳定哈希：同一路径每次都派生同一组端口，重启后界面不用重新找地址。 */
    private fun hashSeed(path: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray())
        val first4 = digest.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        return (first4 % SPAN).toInt()
    }

    private fun canonicalOf(dir: File): String =
        runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)

    private fun isDefaultDataDir(canonical: String): Boolean {
        val d = defaultDataDir() ?: return false
        return canonicalOf(d) == canonical
    }

    /** 与 [resolveDataDir] 保持同一口径：只有这个目录算「主实例」。 */
    fun defaultDataDir(): File? {
        val base = System.getenv("APPDATA") ?: System.getProperty("user.home") ?: return null
        return File(base, DEFAULT_DATA_SUBDIR)
    }

    /** 外壳侧读取：找不到或内容损坏就回 null，由外壳回落到默认端口。 */
    fun readPortFile(dataDir: File): Pair<Int, Int>? {
        val f = File(dataDir, PORT_FILE)
        if (!f.isFile) return null
        return runCatching {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            val ui = props.getProperty("uiPort")?.toIntOrNull() ?: return null
            val gw = props.getProperty("gatewayPort")?.toIntOrNull() ?: return null
            ui to gw
        }.getOrNull()
    }
}
