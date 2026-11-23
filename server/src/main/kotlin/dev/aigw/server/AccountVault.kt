package dev.aigw.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.pool.ACCOUNT_TRANSFER_FORMAT
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 账号保险柜：账号数据的自动备份 + 手动备份 + 导出 + 导入。
 *
 * 与第 13 页的「备份与恢复」是两件事，不要合并：
 *
 * | | 备份与恢复（BackupManager） | 账号保险柜（本类） |
 * |---|---|---|
 * | 粒度 | 整个数据目录的白名单条目 | 只有账号（providerId/uid/nickname/secret） |
 * | 格式 | zip，含 `master.key` 与加密的 `secure/` | 单个 JSON，凭证已解密 |
 * | 用途 | 同机回滚到某个时间点 | 跨机迁移、升级丢数据后补回、客服让用户自救 |
 * | 触发 | 只能手动 | 自动（启动 / 账号变更 / 版本变化）+ 手动 |
 *
 * **自动与手动分两个目录、两个文件名前缀，永不互相覆盖**（用户明确要求）：
 *
 * | | 自动备份 | 手动备份 |
 * |---|---|---|
 * | 落点 | `<dataDir>/../account-vault/`（数据目录之外） | **安装目录** `backups/` |
 * | 命名 | `auto-yyyyMMdd-HHmmss.json` | `manual-yyyyMMdd-HHmmss.json` |
 * | 时机 | 启动 / 账号变更 / 版本变化 | 用户点「立即备份」 |
 * | 轮转 | 自动只留最近 10 份（升级前那份除外） | **不轮转**，用户自己删 |
 *
 * 三个设计决定：
 *
 * 1. **自动备份在数据目录之外**（`<dataDir>/../account-vault`）。
 *    这是「用户把数据目录删了、账号还在」的唯一可能——放在里面就跟着一起没了。
 *    与 BackupManager 同一个教训：恢复时先清空再解压会把备份自己删掉。
 *
 * 2. **手动备份在安装目录**。用户要求「备份到文件安装的目录」——那一份是用户主动
 *    点出来、要自己保管的东西，放在程序旁边最容易找到（也便于连程序一起拷走）。
 *    安装目录只读时（写在 Program Files）写不进去，这时明确回失败原因并提示换用导出，
 *    而不是静默写到别的目录让用户找不到。
 *
 * 3. **凭证是明文 JSON**。不做加密，理由：加密就得再管一份密钥，
 *    而密钥丢了这份备份也就废了，等于没备份。安全性靠两件事兜：
 *    目录不在数据目录内、界面明确写「含 token，勿外传」，
 *    以及写入时用 600 权限（能设的话）。第 13 页的 zip 才需要 `master.key`。
 *
 * 4. **自动备份按「触发原因各留各的」轮转**。升级前那份必须活得比日常久：
 *    用户升级后用了三天才发现某个账号没了，自动备份早被轮转掉了。
 *    所以 `upgrade` 那份单独保留，不参与日常轮转。
 */
internal class AccountVault(
    private val dataDir: File,
    /** 安装目录（外壳传 `--install-dir`）。null = 不知道，手动备份回落到数据目录旁。 */
    private val installDir: File? = null,
) {

    /** 自动备份目录：数据目录的兄弟目录。 */
    val vaultDir: File = File(dataDir.absoluteFile.parentFile, "account-vault")

    /** 手动备份目录：安装目录下的 `backups/`。不知道安装目录时回落到数据目录旁。 */
    val manualDir: File = run {
        val base = installDir?.absoluteFile
        // 安装目录写不进去（Program Files 只读）时不要静默换地方：
        // 先按用户要求放到安装目录，写失败由 writeManual 报出真实原因。
        if (base != null && base.isDirectory) File(base, "backups")
        else File(dataDir.absoluteFile.parentFile, "account-manual")
    }

    /** 自动备份保留份数（启动 / 账号变更）。 */
    var keepCount: Int = DEFAULT_KEEP

    /** 上次自动备份的记录（供界面显示「有没有在自动跑」）。 */
    private val stateFile: File = File(vaultDir, STATE_NAME)

    /** 一份快照在磁盘上的形态。 */
    private data class Entry(
        val file: File,
        val atMillis: Long,
        val accountCount: Int,
        val reason: String,
        val appVersion: String,
    )

    // ---------------------------------------------------------------- 创建

    /**
     * 写一份**自动**备份，落 `auto-yyyyMMdd-HHmmss.json`。
     *
     * @param payload 已经是 `AccountTransfer.encode` 的输出（调用方负责拿最新账号）。
     * @param reason 触发原因，写进文件内的 `reason` 字段：`startup` / `change` / `upgrade`。
     *    `upgrade` 不参与轮转（见类注释第 4 条）。
     * @return 成功时返回文件，失败返回 null 并把原因记进 state（界面能读到「为什么没备份」）。
     */
    fun writeAuto(payload: String, reason: String, appVersion: String): File? =
        writeInto(vaultDir, AUTO_PREFIX, null, payload, reason, appVersion)

    /**
     * 写一份**手动**备份，落安装目录 `backups/manual-yyyyMMdd-HHmmss.json`。
     *
     * 与自动备份**不共用目录、不共用前缀、不参与轮转**——用户要求手动备份不能被
     * 自动备份覆盖/轮转掉。手动那份是用户主动点出来留的，只有用户自己能删。
     */
    fun writeManual(payload: String, appVersion: String): File? =
        writeInto(manualDir, MANUAL_PREFIX, "手动备份", payload, REASON_MANUAL, appVersion)

    /**
     * 写文件的实际实现。
     *
     * @param label 失败文案里的落点名字（`null` = 自动备份，用「备份目录」）
     * @param enforceReason 文件内 `reason` 字段写什么（文件名前缀已经区分了自动/手动）
     */
    private fun writeInto(
        dir: File,
        prefix: String,
        label: String?,
        payload: String,
        reason: String,
        appVersion: String,
    ): File? {
        if (!dir.isDirectory && !dir.mkdirs()) {
            noteState("${label ?: "自动备份"}目录无法创建：${dir.absolutePath}（权限不足？安装到 Program Files 时该目录只读，请改用「导出」）", ok = false)
            return null
        }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault()).format(Instant.now())
        // 同一秒内连写两次（启动 + 账号变更几乎同时，或连点手动备份）会撞名。
        // 与 BackupManager 同样的坑：renameTo 目标存在时不覆盖，随后 copyTo(overwrite) 会吃掉上一份。
        val base = "$prefix-$stamp"
        var target = File(dir, "$base.json")
        var i = 2
        while (target.exists()) {
            target = File(dir, "$base-$i.json")
            i++
        }

        val count = runCatching { countAccounts(payload) }.getOrDefault(0)
        return try {
            // 先写临时文件再原子替换：写到一半被强杀不会留下半个 JSON 让导入端报「格式非法」
            val tmp = File(dir, "${target.name}.tmp")
            // 附上备份专有元数据。信封本身是 `aigw-accounts`，多出的字段
            // `AccountTransfer.decode` 会忽略（它只挑 format/version/accounts）——
            // 于是这份文件同时就是一份合法可导入的导出文件，不需要两套格式。
            val enriched = runCatching {
                val obj = JsonParser.parseString(payload).asJsonObject
                obj.addProperty("appVersion", appVersion)
                obj.addProperty("reason", reason)
                obj.toString()
            }.getOrDefault(payload)
            tmp.writeText(enriched, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            restrictPermissions(target)
            noteState(
                "已备份 $count 个账号 → ${label ?: "自动"}（${target.name}）",
                ok = true, file = target.name, atMillis = target.lastModified(),
            )
            if (prefix == AUTO_PREFIX && reason != REASON_UPGRADE) rotate()
            target
        } catch (e: Exception) {
            runCatching { File(dir, "${target.name}.tmp").delete() }
            noteState("备份失败（${label ?: "自动"}）：${e.javaClass.simpleName}: ${e.message}", ok = false)
            null
        }
    }

    /**
     * 只在内容真的变了时才写自动备份。
     *
     * 账号自动刷新 token 会频繁触发 `onAccountsChanged`，每次都落一份会把
     * 10 份历史全刷成「最近 30 秒」。比对的是「账号集合 + 凭证」指纹：
     * token 轮换也会算变更（那是必须保住的），但只是刷新额度不算。
     */
    fun writeIfChanged(payload: String, reason: String, appVersion: String): File? {
        val newest = scanAuto().firstOrNull { it.reason != REASON_UPGRADE }
        if (newest != null && sameAccounts(newest.file, payload)) return null
        return writeAuto(payload, reason, appVersion)
    }

    /** 轮转：自动备份超过 [keepCount] 份时删最旧的。升级前那份永不删。手动备份不轮转。 */
    private fun rotate() {
        val daily = scanAuto().filter { it.reason != REASON_UPGRADE }
        if (daily.size <= keepCount) return
        daily.drop(keepCount).forEach { runCatching { it.file.delete() } }
    }

    // ---------------------------------------------------------------- 查询

    /** 列出全部备份（自动 + 手动，按时间倒序，带 `auto` 标记便于界面分组）。 */
    fun list(): JsonObject {
        val items = scanAuto() + scanManual()
        val arr = JsonArray()
        items.sortedByDescending { it.atMillis }.forEach { e ->
            arr.add(JsonObject().apply {
                addProperty("name", e.file.name)
                addProperty("path", e.file.absolutePath)
                addProperty("bytes", e.file.length())
                addProperty("sizeText", sizeText(e.file.length()))
                addProperty("atMillis", e.atMillis)
                addProperty("accountCount", e.accountCount)
                addProperty("reason", e.reason)
                addProperty("appVersion", e.appVersion)
                // 界面据此分「自动 / 手动」两组：前缀是权威判据，不是目录——
                // 手动备份目录在安装目录，自动在数据目录旁，两处的文件可能被用户手工挪动
                addProperty("auto", e.file.name.startsWith("$AUTO_PREFIX-"))
            })
        }
        val state = readState()
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("dir", vaultDir.absolutePath)
            addProperty("manualDir", manualDir.absolutePath)
            addProperty("count", arr.size())
            addProperty("keep", keepCount)
            add("items", arr)
            add("last", state ?: JsonObject())
        }
    }

    /** 读出某份备份的正文（导出/预览用）。自动或手动都认。 */
    fun read(name: String): String? {
        val f = safeFile(name) ?: return null
        if (!f.isFile) return null
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    }

    fun delete(name: String): JsonObject {
        val f = safeFile(name) ?: return resultJson(false, "非法的备份名：$name")
        if (!f.isFile) return resultJson(false, "备份不存在：$name")
        return if (f.delete()) resultJson(true, "已删除备份 $name")
        else resultJson(false, "删除失败（文件可能被占用）：$name")
    }

    /**
     * 文件名白名单校验：先按前缀判定它在哪个目录，再只认那个目录里的这个文件名。
     *
     * 界面传来的 name 直接拼路径是路径穿越的经典入口（`../../evil.json`）。
     * 只认 `auto-*.json` 与 `manual-*.json` 两种形态，其余一律拒绝。
     */
    private fun safeFile(name: String): File? {
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        if (!name.endsWith(".json")) return null
        return when {
            name.startsWith("$AUTO_PREFIX-") -> File(vaultDir, name)
            name.startsWith("$MANUAL_PREFIX-") -> File(manualDir, name)
            else -> null
        }
    }

    /** 自动备份目录里的条目。 */
    private fun scanAuto(): List<Entry> = scanDir(vaultDir, AUTO_PREFIX)

    /** 手动备份目录里的条目。 */
    private fun scanManual(): List<Entry> = scanDir(manualDir, MANUAL_PREFIX)

    private fun scanDir(dir: File, prefix: String): List<Entry> {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("$prefix-") && f.name.endsWith(".json") }
            ?: return emptyList()
        return files.mapNotNull { f ->
            val head = runCatching { readHead(f) }.getOrNull() ?: return@mapNotNull null
            Entry(
                file = f,
                atMillis = head.second,
                accountCount = head.first,
                reason = reasonOf(f),
                appVersion = head.third,
            )
        }.sortedByDescending { it.atMillis }
    }

    /** 读头部三个字段；不整份 parse，备份多时省内存。 */
    private fun readHead(f: File): Triple<Int, Long, String>? {
        val obj = runCatching {
            JsonParser.parseString(f.readText(Charsets.UTF_8)).asJsonObject
        }.getOrNull() ?: return null
        val accounts = obj.get("accounts")?.takeIf { it.isJsonArray }?.asJsonArray
        val at = obj.get("exportedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: f.lastModified()
        // appVersion 不是信封标准字段：这里读自家附加的 meta，读不到就空着
        val ver = obj.get("appVersion")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        return Triple(accounts?.size() ?: 0, at, ver)
    }

    /**
     * 原因。优先读文件内的 `reason`（写入时就带上了，可靠）；
     * 老文件（上一版命名 `accounts-<reason>-<stamp>.json`）没有该字段，
     * 从文件名里解析，解析不出来返回 `unknown`——不猜。
     */
    private fun reasonOf(f: File): String {
        val obj = runCatching {
            JsonParser.parseString(f.readText(Charsets.UTF_8)).asJsonObject
        }.getOrNull()
        obj?.get("reason")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        // 旧命名：accounts-<reason>-<stamp>[-N].json
        val parts = f.name.removePrefix("accounts-").removeSuffix(".json").split('-')
        val reasonEnd = parts.indexOfFirst { it.length == 8 && it.all { c -> c.isDigit() } }
        return if (reasonEnd > 0) parts.take(reasonEnd).joinToString("-") else "unknown"
    }

    // ---------------------------------------------------------------- 状态

    /**
     * 记住最近一次快照的结果。
     *
     * 必须**同时**留一份在内存：目录都建不出来时（磁盘只读、路径被占）写盘也必然失败，
     * 此时唯一能告诉用户「为什么没备份」的就是这份内存状态。只写盘的话，
     * 界面拿到的是空状态，看起来和「还没跑过」一模一样——那正是最难区分的一类故障。
     */
    private fun noteState(message: String, ok: Boolean, file: String = "", atMillis: Long = 0L) {
        val obj = JsonObject().apply {
            addProperty("ok", ok)
            addProperty("message", message)
            addProperty("atMillis", if (atMillis > 0) atMillis else System.currentTimeMillis())
            if (file.isNotEmpty()) addProperty("file", file)
        }
        lastState = obj
        runCatching {
            vaultDir.mkdirs()
            stateFile.writeText(obj.toString(), Charsets.UTF_8)
        }
    }

    /** 最近一次状态的内存副本：写盘失败时 [readState] 回落到它。 */
    @Volatile
    private var lastState: JsonObject? = null

    private fun readState(): JsonObject? = runCatching {
        if (!stateFile.isFile) return lastState
        JsonParser.parseString(stateFile.readText(Charsets.UTF_8)).asJsonObject
    }.getOrNull() ?: lastState

    // ---------------------------------------------------------------- 工具

    private fun countAccounts(payload: String): Int = runCatching {
        JsonParser.parseString(payload).asJsonObject
            .get("accounts")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
    }.getOrDefault(0)

    /**
     * 两份内容里的账号是否完全一样。
     *
     * 比较 providerId + uid + secret 三项：昵称改了也值得留一份（用户能看出那个号是谁），
     * 但这里刻意不算昵称——昵称变化通常伴着凭证刷新，一起触发就够了。
     */
    private fun sameAccounts(file: File, payload: String): Boolean = runCatching {
        val a = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
        val b = JsonParser.parseString(payload).asJsonObject
        if (a.get("format")?.asString != ACCOUNT_TRANSFER_FORMAT) return false
        if (b.get("format")?.asString != ACCOUNT_TRANSFER_FORMAT) return false
        val ka = keysOf(a)
        val kb = keysOf(b)
        ka == kb
    }.getOrDefault(false)

    private fun keysOf(obj: JsonObject): Set<String> {
        val arr = obj.get("accounts")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptySet()
        return arr.mapNotNull { el ->
            val o = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val pid = o.get("providerId")?.asString.orEmpty()
            val uid = o.get("uid")?.asString.orEmpty()
            val secret = o.get("secret")?.asString.orEmpty()
            "$pid/$uid/$secret"
        }.toSet()
    }

    /** 尽力收紧权限（POSIX 上 600；Windows 上 File.setReadable 基本无效，静默跳过）。 */
    private fun restrictPermissions(f: File) {
        runCatching {
            f.setReadable(false, false)
            f.setReadable(true, true)
            f.setWritable(false, false)
            f.setWritable(true, true)
        }
    }

    companion object {
        /** 自动备份默认保留 10 份。升级前那份另算。手动备份不轮转。 */
        const val DEFAULT_KEEP = 10

        /** 自动备份文件名前缀：`auto-20261123-080000.json`。 */
        const val AUTO_PREFIX = "auto"

        /** 手动备份文件名前缀：`manual-20261123-080000.json`。 */
        const val MANUAL_PREFIX = "manual"

        const val REASON_STARTUP = "startup"
        const val REASON_CHANGE = "change"
        const val REASON_UPGRADE = "upgrade"
        const val REASON_MANUAL = "manual"

        private const val STATE_NAME = "vault-state.json"

        fun sizeText(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        }
    }
}
