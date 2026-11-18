package dev.aigw.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 更新源（零服务器）。
 *
 * 仓库里只放一个 `update.json`，客户端直接用 raw CDN 拉。
 * 发布新版 = 改这个文件的 `version` 并 commit，所有已装实例下次检查就会看到。
 * 不需要任何后端、不需要域名、不需要 OSS 托管费用。
 *
 * **双通道**：raw 走 Fastly CDN，无速率限制但 commit 后有几分钟同步延迟；
 * Contents API 实时但没有 PAT 时每小时只有 60 次。更新检查是低频操作，
 * 先用 raw、失败再回落 API，两个问题都不撞。
 */
internal object UpdateSource {
    private const val OWNER = "Squemadylan"
    private const val REPO = "AI-Gateway-updata"
    private const val BRANCH = "main"
    private const val FILE = "update.json"

    /** 主通道：raw CDN。 */
    const val RAW_URL = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE"

    /** 备通道：Contents API（返回 JSON 包一层 base64 的 content）。 */
    const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/contents/$FILE"

    /** 发布页：清单拉不到时的兜底入口，永远可点。 */
    const val HOME_URL = "https://pan.quark.cn/s/8fd93162bcab"

    /** 本地缓存时效。更新是低频事件，30 分钟内重复打 GitHub 没有意义， */
    const val CACHE_MS = 30L * 60 * 1000

    /** 单次请求超时。超了就按「拉取失败」处理，绝不让界面等。 */
    const val TIMEOUT_MS = 8000
}

/**
 * 强制更新检查。
 *
 * 判定口径（三条，缺一不算强制）：
 * 1. 远端清单拿到了，且 `version` 严格大于本机 [APP_VERSION]；
 * 2. 清单里 `force == true`，或本机版本低于 `minVersion`（后者用于「老版本
 *    协议不兼容、必须升级」的场景，不用改 force 字段）；
 * 3. 清单里的 `downloadUrl` 非空（否则强制了也没地方去）。
 *
 * **拉取失败一律放行**：没网、GitHub 不可用、JSON 写坏了，都返回 `ok:false`
 * 且 `mandatory:false`。更新检查不该成为把用户挡在门外的理由。
 *
 * **磁盘缓存**：`raw.githubusercontent.com` 在国内经常连不上，只用内存缓存的话
 * 每次重启都要等满 8 秒超时才肯回落。把上一次成功结果连同时间戳落盘，
 * 重启后 30 分钟内直接读盘、不发请求；超过 30 分钟才重新拉。
 */
internal class UpdateChecker(private val dataDir: File? = null) {

    @Volatile
    private var cached: JsonObject? = null

    @Volatile
    private var cachedAt: Long = 0L

    /**
     * 强制更新闸门：true 时网关**必须保持停止**。
     *
     * 这是「强制」的实质，不是装饰：只弹窗不禁服务的话，客户端（Cursor、Claude Code…）
     * 照样能打得通 `127.0.0.1:8790/v1`，用户根本不需要理会那个窗口。
     * 一旦置位就**不可撤销**（同进程内不会回 false）：清单把版本改回去了也不放开，
     * 因为那一刻最该做的是装新版，不是继续用旧版跑。
     */
    @Volatile
    var gateClosed: Boolean = false
        private set

    /** 闸门关闭原因（供界面与日志引用）。 */
    @Volatile
    var gateReason: String = ""
        private set

    /**
     * 判定为强制更新时调用：落下闸门。
     *
     * 由 [check] 自动触发，不需要界面配合——界面可以被打补丁绕过，
     * 服务端这道闸才是真正生效的那道。
     *
     * **同时落一个独立的闸门文件**（[GATE_FILE]，不是那份 30 分钟的更新缓存）：
     * 缓存会过期、会被下一次检查覆盖，而闸门必须比它活得久。
     * 落盘后即使清单被改回去、GitHub 连不上、用户杀进程重启，闸门照样在。
     * 唯一解锁方式是装到清单要求的版本（[restoreGateFromDisk] 比对 APP_VERSION）。
     */
    private fun closeGate(reason: String, target: String) {
        if (gateClosed) return
        gateClosed = true
        gateReason = reason
        writeGateFile(target)
    }

    /**
     * 闸门落盘。只记「拦到的版本」与原因，不依赖更新缓存的寿命。
     */
    private fun writeGateFile(target: String) {
        val f = gateFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val obj = JsonObject().apply {
                addProperty("closedAt", System.currentTimeMillis())
                addProperty("installedVersion", APP_VERSION)
                addProperty("requiredVersion", target)
                addProperty("reason", gateReason)
            }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(GSON.toJson(obj), StandardCharsets.UTF_8)
            runCatching { f.delete() }
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }

    /**
     * 启动时恢复闸门。
     *
     * 口径：文件在 **且** 本机 [APP_VERSION] 仍低于文件里记录的 `requiredVersion`
     * → 立刻落闸（这时还没开始联网，网关那步自然不就启动）。
     * 反过来，装到新版后 APP_VERSION 不小于 requiredVersion → 文件作废并删掉，
     * 这就是「更新到最新版后才能正常使用」的解锁条件。
     *
     * 注意不能拿 `installedVersion` 来比：用户可能装了个中间版本，
     * 只要还够不上 requiredVersion 就必须继续拦。
     */
    private fun restoreGateFromDisk() {
        val f = gateFile ?: return
        if (!f.isFile) return
        runCatching {
            val obj = JsonParser.parseString(f.readText(StandardCharsets.UTF_8)).asJsonObject
            val need = obj.str("requiredVersion").trim()
            if (need.isEmpty()) {
                // 文件写坏了：删掉，宁可放行也不要把用户锁在一个打不开的状态里
                runCatching { f.delete() }
                return
            }
            if (compareVersion(APP_VERSION, need) < 0) {
                gateClosed = true
                gateReason = "必须更新到 v$need 才能继续使用（已安装 v$APP_VERSION）"
                // 顺手把细粒度原因补上，便于排查
                obj.str("reason").takeIf { it.isNotBlank() }?.let { gateReason = it }
            } else {
                // 已装到够新的版本：闸门自动解除，删文件免得下次开机又读一遍
                runCatching { f.delete() }
            }
        }
    }

    private val lock = Any()

    private val cacheFile: File? get() = dataDir?.let { File(it, CACHE_FILE) }

    private val gateFile: File? get() = dataDir?.let { File(it, GATE_FILE) }

    init {
        restoreFromDisk()
        restoreGateFromDisk()
    }

    /**
     * 检查更新。
     *
     * @param refresh true 时忽略缓存（用户手动点「检查更新」走这条）
     * @return 永远是一个可直接给前端渲染的 JSON，不抛异常
     */
    fun check(refresh: Boolean): JsonObject {
        synchronized(lock) {
            val hit = cached
            val age = System.currentTimeMillis() - cachedAt
            if (!refresh && hit != null && age in 0 until UpdateSource.CACHE_MS) {
                // 缓存命中也要检查一次闸门：缓存里可能已经是 mandatory:true
                // （上一次判定写下的），但闸门文件因故没写成 / 被删了。
                // 之前这里直接 return，是「强制态偶尔不生效」的一个真实缺口。
                if (!gateClosed && hit.bool("mandatory")) {
                    val need = hit.str("latest").trim()
                        .ifBlank { hit.str("minVersion").trim() }
                        .ifBlank { APP_VERSION }
                    closeGate("强制更新 已安装 v$APP_VERSION → 最新 v$need", need)
                }
                return hit.deepCopy().apply {
                    addProperty("cached", true)
                    addProperty("ageMs", age)
                    addProperty("gateClosed", gateClosed)
                    addProperty("gateReason", gateReason)
                }
            }

            val started = System.currentTimeMillis()
            val manifest = fetchManifest()
            val latency = System.currentTimeMillis() - started

            val out = if (manifest == null) {
                // 拉不到：放行。前端据此显示「检查失败」但绝不弹强制窗。
                JsonObject().apply {
                    addProperty("ok", false)
                    addProperty("current", APP_VERSION)
                    addProperty("hasUpdate", false)
                    addProperty("mandatory", false)
                    addProperty("downloadUrl", UpdateSource.HOME_URL)
                    addProperty("message", "连不上更新源（GitHub 可能不可达），可直接打开下载页")
                    addProperty("checkedAt", System.currentTimeMillis())
                    addProperty("latencyMs", latency)
                    // 老缓存还在就把它带上：界面能继续显示上一次已知的新版本
                    hit?.let { old ->
                        old.get("latest")?.let { add("lastKnownLatest", it) }
                    }
                }
            } else {
                buildResult(manifest, latency)
            }

            // 只有成功结果进缓存：失败也缓存住会导致「断网一次，半小时内重试全是失败」
            if (manifest != null) {
                cached = out.deepCopy()
                cachedAt = System.currentTimeMillis()
                saveToDisk(out)
                // 命中强制更新就落闸（并落盘一个长期闸门文件，见 closeGate 注释）。
                // 放在这里而不是让界面去调：缓存命中路径（上面那条 early return）
                // 也必须能触发，否则第二次进页面时闸门已经关了却又被短路成「没更新」。
                if (out.bool("mandatory")) {
                    val need = out.str("latest").trim()
                        .ifBlank { out.str("minVersion").trim() }
                        .ifBlank { APP_VERSION }
                    closeGate("强制更新 已安装 v$APP_VERSION → 最新 v$need", need)
                }
            }
            // 闸门状态无论成功失败都带上：界面手动点「检查更新」时拿到的这一份
            // 必须和下一拍心跳的 state.update 说同一件事，否则版本条会闪一下。
            return out.apply {
                addProperty("gateClosed", gateClosed)
                addProperty("gateReason", gateReason)
            }
        }
    }

    /**
     * 本机未检查过时返回一份「还没查」的占位结果。
     *
     * 供状态快照使用——不能在那里直接调 [check]：心跳 2 秒一拍，
     * 会把 8 秒的 GitHub 请求压到界面上。
     */
    fun peek(): JsonObject {
        val hit = cached ?: return JsonObject().apply {
            addProperty("ok", true)
            addProperty("current", APP_VERSION)
            addProperty("hasUpdate", false)
            addProperty("mandatory", false)
            addProperty("gateClosed", gateClosed)
            addProperty("gateReason", gateReason)
            addProperty("downloadUrl", UpdateSource.HOME_URL)
            addProperty("checked", false)
        }
        return hit.deepCopy().apply {
            addProperty("cached", true)
            addProperty("ageMs", System.currentTimeMillis() - cachedAt)
            // 闸门状态必须每拍都如实上报：界面靠它决定是否锁死全部操作
            addProperty("gateClosed", gateClosed)
            addProperty("gateReason", gateReason)
        }
    }

    // ---------------------------------------------------------------- 磁盘缓存

    private fun restoreFromDisk() {
        val f = cacheFile ?: return
        if (!f.isFile) return
        runCatching {
            val obj = JsonParser.parseString(f.readText(StandardCharsets.UTF_8)).asJsonObject
            val at = obj.get("fetchedAt")?.asLong ?: return
            val body = obj.getAsJsonObject("result") ?: return
            // 版本号变了（用户刚装了新版）就别用旧缓存短路：那一刻最该重新拉一次
            if (body.str("current") != APP_VERSION) return
            cached = body
            cachedAt = at
            // 开机就要判一次闸门：缓存里可能已经是 mandatory（上次判定写下的），
            // 而闸门文件恰好没写成。这一步在 startGateway **之前**跑完（构造期 init），
            // 于是「网关根本不起」而非「起来再停」，没有中间窗口期。
            // 闸门文件仍然是权威（restoreGateFromDisk 先跑，优先级更高）。
            if (!gateClosed && body.bool("mandatory")) {
                val need = body.str("latest").trim()
                    .ifBlank { body.str("minVersion").trim() }
                    .ifBlank { APP_VERSION }
                closeGate("强制更新 已安装 v$APP_VERSION → 最新 v$need", need)
            }
        }
    }

    private fun saveToDisk(result: JsonObject) {
        val f = cacheFile ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val wrapper = JsonObject().apply {
                addProperty("fetchedAt", System.currentTimeMillis())
                addProperty("source", UpdateSource.RAW_URL)
                add("result", result)
            }
            // 先写临时文件再原子替换：中途被杀进程不会留下半截 JSON
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(GSON.toJson(wrapper), StandardCharsets.UTF_8)
            runCatching { f.delete() }
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun buildResult(manifest: JsonObject, latency: Long): JsonObject {
        val latest = manifest.str("version").trim()
        val minVersion = manifest.str("minVersion").trim()
        val forceFlag = manifest.bool("force")
        val url = manifest.str("downloadUrl").trim().ifEmpty { UpdateSource.HOME_URL }

        val hasUpdate = latest.isNotEmpty() && compareVersion(APP_VERSION, latest) < 0
        val belowMin = minVersion.isNotEmpty() && compareVersion(APP_VERSION, minVersion) < 0
        // 强制成立必须同时有「确实更新」和「有下载入口」，否则只能当普通提示
        val mandatory = hasUpdate && (forceFlag || belowMin) && url.isNotEmpty()

        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("current", APP_VERSION)
            addProperty("latest", latest)
            addProperty("hasUpdate", hasUpdate)
            addProperty("mandatory", mandatory)
            addProperty("force", forceFlag)
            addProperty("minVersion", minVersion)
            addProperty("downloadUrl", url)
            addProperty("downloadName", manifest.str("downloadName"))
            addProperty("releaseNotes", manifest.str("notes"))
            addProperty("publishedAt", manifest.str("publishedAt"))
            addProperty("checkedAt", System.currentTimeMillis())
            addProperty("latencyMs", latency)
            addProperty("cached", false)
        }
    }

    /** 先 raw 后 API；都失败返回 null。 */
    private fun fetchManifest(): JsonObject? {
        fetchText(UpdateSource.RAW_URL, api = false)?.let { body ->
            parseManifest(body)?.let { return it }
        }
        val apiBody = fetchText(UpdateSource.API_URL, api = true) ?: return null
        return parseApiBody(apiBody)
    }

    private fun parseManifest(body: String): JsonObject? =
        runCatching { JsonParser.parseString(body).asJsonObject.takeIf { it.has("version") } }
            .getOrNull()

    /**
     * Contents API 把文件内容 base64 放在 `content` 字段里，且带换行符，
     * 必须先清掉再解——用 basic 解码器直接喂带 `\n` 的串会抛异常。
     */
    private fun parseApiBody(body: String): JsonObject? = runCatching {
        val obj = JsonParser.parseString(body).asJsonObject
        val b64 = obj.get("content")?.asString ?: return null
        val cleaned = b64.replace("\n", "").replace("\r", "")
        val text = String(java.util.Base64.getDecoder().decode(cleaned), StandardCharsets.UTF_8)
        parseManifest(text)
    }.getOrNull()

    private fun fetchText(url: String, api: Boolean): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = UpdateSource.TIMEOUT_MS
                readTimeout = UpdateSource.TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                // GitHub API 不带 UA 会 403；raw 不强制但带上不损失什么
                setRequestProperty("User-Agent", "aigw-windows/" + APP_VERSION)
                if (api) {
                    setRequestProperty("Accept", "application/vnd.github+json")
                } else {
                    // 手动检查时希望 commit 后立刻见效，别被 CDN 挡住
                    setRequestProperty("Cache-Control", "no-cache")
                }
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 版本比较：`0.9.10` 必须大于 `0.9.9`（纯字符串比会判反）。
     *
     * 按 `.` 切段，两边同段都取数值就按数值比，任一段不是数字则退回字典序。
     * 段数不等时缺的按 0 补（`1.0` == `1.0.0`）。
     */
    companion object {
        private const val CACHE_FILE = ".update-cache.json"

        /**
         * 强制更新闸门文件。
         *
         * **必须和 [CACHE_FILE] 分开**：缓存 30 分钟就过期、每次检查都整份覆盖，
         * 一旦用它承载强制状态，就会出现「断网或清单临时改回去 → 闸门自己开了」。
         * 闸门文件只在判定为强制时写入、只在装到足够新的版本时删除。
         */
        private const val GATE_FILE = ".update-gate.json"

        fun compareVersion(a: String, b: String): Int {
            val pa = splitVersion(a)
            val pb = splitVersion(b)
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val x = pa.getOrElse(i) { "0" }
                val y = pb.getOrElse(i) { "0" }
                val xn = x.toIntOrNull()
                val yn = y.toIntOrNull()
                val cmp = if (xn != null && yn != null) xn.compareTo(yn) else x.compareTo(y)
                if (cmp != 0) return cmp
            }
            return 0
        }

        /** 只取版本主体：`0.9.6-beta2+build7` → `0.9.6`（预发布段不参与排序）。 */
        private fun splitVersion(v: String): List<String> =
            v.trim()
                .substringBefore('-')
                .substringBefore('+')
                .split('.')
                .filter { it.isNotBlank() }
    }
}
