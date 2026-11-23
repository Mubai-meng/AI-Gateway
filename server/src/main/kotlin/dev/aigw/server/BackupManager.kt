package dev.aigw.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 数据目录的备份与恢复。
 *
 * 这一层是纯文件操作，不认识 HTTP，也不碰 core —— 它只保证一件事：
 * **任何一次替换都可回滚**。磁盘上的凭证在 `secure/` 下、由 `master.key` 加密；
 * **备份包里出的是明文**（见 [BackupCodec] 的说明：个人自用、无跨人流转需求，
 * 而密文熵饱和导致 deflate 完全压不动）。
 *
 * 五条硬约定：
 * 1. **备份目录默认在数据目录之外**（`<dataDir>/../backups`），但用户可以
 *    点「浏览…」把这一份存到任意文件夹（默认位置仍记在清单里参与扫描）。
 *    放在数据目录里的话，恢复时"先清空再解压"会把备份自己删掉。
 * 2. **不备份 `webview2/`**。它占 80 MB（Chromium 缓存，与本项目数据无关），
 *    混进去会让一次备份从 2 MB 变成 80 MB，用户不会再用第二次。
 * 3. **不备份调用记录**（`secure/logs/`）。用户明确要求：那是可以重新产生的日志，
 *    混进包里只会让备份从几十 KB 涨到 2 MB。恢复时也**不动**现有调用记录。
 * 4. **恢复不立即替换**：只登记一个 `.restore-pending.json`，由下次进程启动
 *    在拿到实例锁之后、打开加密存储之前执行。正在跑的进程持有 `secure/` 下的
 *    文件句柄，边跑边替换会得到半个账号池。
 * 5. **解压必须防 zip-slip**：条目名里出现 `..` 或绝对路径一律整包拒绝。
 *    本服务只监听 127.0.0.1，但浏览器里任意页面都能发请求过来。
 */
internal class BackupManager(private val dataDir: File) {

    /** 备份默认存放目录：数据目录的兄弟目录，绝不在数据目录内部。 */
    val backupDir: File = File(dataDir.absoluteFile.parentFile, "backups")

    /**
     * 实际可能存放备份的目录清单。
     *
     * 允许用户自选文件夹之后，列表不能再只扫一个目录——否则
     * 「我刚备份到 D 盘」和「列表里只有旧的」会同时成立。
     */
    private val dirs = BackupDirs(defaultDir = backupDir, storeFile = File(dataDir, "backup-dirs.json"))

    /** 把界面传来的目录字符串解析成绝对路径；空串=上次那个，相对路径返回 null。 */
    fun parseDir(raw: String?): File? = dirs.parse(raw)

    /** 当前默认（上次使用）的备份目录。 */
    fun currentDir(): File = dirs.primary()

    /** 全部扫描目录。 */
    fun dirList(): List<File> = dirs.all()

    /** 主密钥：只读不造（造密钥是启动期 `EncryptedKeyValueStore` 的职责）。 */
    private val keyFile: File = File(dataDir, "master.key")

    /** 懒加载一次，避免每个条目都读一遍 key 文件。 */
    private var keyBytes: ByteArray? = null

    private val pendingFile: File = File(dataDir, PENDING_NAME)

    /** 上次启动实际恢复的记录（供界面显示"昨天那次恢复做了什么"）。 */
    private val lastRestoreFile: File = File(dataDir, LAST_RESTORE_NAME)

    // ---------------------------------------------------------------- 快照范围

    /**
     * 打包/替换的条目白名单。
     *
     * 用白名单而不是"全部"：`webview2/`、`.instance.*`、`shell-launcher.log`、
     * `.update-cache.json` 都是可再生的进程状态，带进备份只会让包变大、
     * 让恢复后的实例身份变得可疑（`.instance.json` 里写死了旧端口）。
     *
     * **调用记录（`logs/` 与 `secure/logs/`）不在名单里**：用户明确要求不要备份——
     * 那份东西每次对话都会长，占旧包的九成以上，而它对"能不能用"毫无帮助。
     */
    private fun snapshotTopLevel(): List<String> = buildList {
        add("secure")           // 账号、凭证、设置、用量（全在这里，加密）
        add("master.key")       // 没有它，secure/ 就是一堆无法解密的字节
        add("antigravity-oauth.json")
        add("ui-theme.txt")
        add("auto-route.txt")
        // 明文遗留目录：正常情况为空（迁移后已清），但旧版本可能还在用。
        // 注意没有 logs / usage 里的日志类内容——调用记录一律不进包。
        add("account"); add("pool"); add("usage"); add("login")
    }.filter { File(dataDir, it).exists() }

    /** 目录里只有调用记录的子路径：打包、清理、恢复三处都按这两条前缀排除。 */
    /**
     * 是不是调用记录。**按路径里的 `logs` 段判，不按前缀**。
     *
     * 只写 `secure/logs/` 会漏掉迁移遗留的那份：老版本把明文日志挪到
     * `secure/legacy-backup/logs/calls/` 下，实测 13 账号的包 33.6 KB 里有 26 KB 是它
     * （4 个 chatcmpl-*）。用户要求"调用记录不要备份"，那就该一个都不带。
     */
    private fun isCallLog(rel: String): Boolean = rel.split('/').any { it == "logs" }

    // ---------------------------------------------------------------- 创建

    /**
     * 写一份**自动**备份：固定落默认目录，不需要（也不允许）用户选地方。
     *
     * 触发时机由 EngineHost 管（启动一次、账号变更后 3 秒合并一次）。
     *
     * @param fingerprint 账号集合指纹。一样就跳过——自动刷新 token 会频繁触发，
     *   每次都落一份会把 10 份历史全刷成「最近几秒」，真出事时反而没有时间纵深。
     */
    fun autoSnapshot(fingerprint: String): JsonObject? {
        if (fingerprint.isBlank()) return null
        if (fingerprint == readFingerprint()) return null
        val r = create(dir = backupDir, auto = true)
        if (r.get("ok")?.asBoolean != true) return null
        writeFingerprint(fingerprint)
        rotateAuto()
        return r
    }

    /** 自动备份只留最近 [AUTO_KEEP] 份；手动备份一份都不删。 */
    private fun rotateAuto() {
        val autos = dirList().flatMap { d ->
            d.listFiles { f -> f.isFile && f.name.startsWith("$AUTO_PREFIX-") && f.name.endsWith(".zip") }
                ?.toList().orEmpty()
        }.sortedByDescending { readManifest(it)?.first ?: it.lastModified() }
        autos.drop(AUTO_KEEP).forEach { runCatching { it.delete() } }
    }

    /** 上次自动备份对应的账号指纹。放在数据目录里，不进备份包。 */
    private val fingerprintFile: File = File(dataDir, ".auto-backup-fingerprint")

    private fun readFingerprint(): String =
        runCatching { fingerprintFile.takeIf { it.isFile }?.readText()?.trim().orEmpty() }.getOrDefault("")

    private fun writeFingerprint(fp: String) {
        runCatching { fingerprintFile.writeText(fp, Charsets.UTF_8) }
    }

    /**
     * 创建一份备份。
     *
     * 包内 `secure/` 条目是**明文**（`encrypted: false` 写进清单）。个人自用工具，
     * 备份只在自己机器之间流转，没有"他人拿到包"这个威胁模型；而密文熵饱和，
     * deflate 压缩比实测 1.00。磁盘上的 `secure/` 仍然加密，那一层保护的是别的场景。
     *
     * **不含调用记录**（[isCallLog] 排除），所以包只有几十 KB。
     *
     * 旧版本生成的加密包**仍可恢复**：包内自带 `master.key` + 密文，搬回数据目录后
     * 同一个 key 解得开。恢复逻辑不关心包内是密文还是明文。
     *
     * @param dir 用户点「浏览…」选定的目录；null 表示用上次那个（默认备份目录）。
     *   成功后会记住这个目录，下次列表里仍然看得到它。
     */
    fun create(dir: File? = null, auto: Boolean = false): JsonObject {
        val outDir = dir?.absoluteFile ?: currentDir()
        outDir.mkdirs()
        if (!outDir.isDirectory) {
            return resultJson(false, "备份目录无法创建：${outDir.absolutePath}")
        }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault()).format(Instant.now())
        // 同一秒内连点两次会撞名，而 renameTo 在这条路径上**不覆盖**（Windows 上返回 false，
        // 代码随后 copyTo(overwrite=true) 就把上一份删了）——实测连点两次只剩一份。
        // 名字里带序号，保证每次创建都是新文件。
        val name = uniqueName(outDir, (if (auto) AUTO_PREFIX else MANUAL_PREFIX) + "-$stamp")
        val target = File(outDir, name)
        val tmp = File(outDir, "$name.tmp")

        var entries = 0
        try {
            ZipOutputStream(FileOutputStream(tmp)).use { zos ->
                zos.setLevel(6)
                for (top in snapshotTopLevel()) {
                    val root = File(dataDir, top)
                    if (root.isFile) {
                        entries += addFile(zos, root, top)
                    } else if (root.isDirectory) {
                        root.walkTopDown().filter { it.isFile }.forEach { f ->
                            val rel = f.relativeTo(dataDir).invariantSeparatorsPath
                            entries += addFile(zos, f, rel)
                        }
                    }
                }
                // 清单条目：让恢复端能在解压前判断"这包是什么、什么时候、哪个版本"
                val manifest = JsonObject().apply {
                    addProperty("createdAtMillis", System.currentTimeMillis())
                    addProperty("appVersion", APP_VERSION)
                    // 字段保留但恒为 false：老版本界面据它显示「含调用记录」的提示，
                    // 现在的包一律不含。读包的人不必再分情况。
                    addProperty("includeCallLogs", false)
                    // 包内 secure/ 条目是否为明文。读包的人（或将来做导入功能的自己）
                    // 需要知道这件事，否则会去找 master.key 却不知道它其实只对旧包有用。
                    addProperty("encrypted", false)
                    addProperty("entryCount", entries)
                    addProperty("dataDirName", dataDir.absoluteFile.name)
                }
                zos.putNextEntry(ZipEntry(MANIFEST_NAME))
                zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            return resultJson(false, "备份失败：${e.javaClass.simpleName}: ${e.message}")
        }

        // 只有手动备份才记「用户选过哪个目录」：自动备份固定写默认目录，
        // 记进去会让界面以为用户已经选过位置，下次点「立即备份」就不弹窗了。
        if (!auto) dirs.remember(outDir)
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("message", "已备份 $entries 个条目（${sizeText(target.length())}）")
            addProperty("name", name)
            addProperty("path", target.absolutePath)
            addProperty("dir", outDir.absolutePath)
            addProperty("bytes", target.length())
        }
    }

    /** 同名时追加 `-2`、`-3`…，返回一个当下不存在的文件名（含 .zip）。 */
    private fun uniqueName(dir: File, base: String): String {
        var candidate = "$base.zip"
        var i = 2
        while (File(dir, candidate).exists()) {
            candidate = "$base-$i.zip"
            i++
        }
        return candidate
    }

    /** 单个条目入包；调用记录跳过。返回实际写入的条目数。 */
    private fun addFile(zos: ZipOutputStream, file: File, rel: String): Int {
        if (isCallLog(rel)) return 0
        // .tmp 残片是原子写的中转文件，带进备份没有意义
        if (rel.endsWith(".tmp")) return 0
        try {
            zos.putNextEntry(ZipEntry(rel))
            // secure/ 下的条目落盘时是 AGW1 密文，入包前解成明文。
            // 解不开（key 丢了 / 字节对不上）就原样写密文——宁可包里难看，
            // 也不能因为一个条目解不开就把它从备份里吞掉。
            val plain = if (rel.startsWith("secure/")) plainBytes(file) else null
            if (plain != null) {
                zos.write(plain)
            } else {
                FileInputStream(file).use { it.copyTo(zos) }
            }
            zos.closeEntry()
            return 1
        } catch (e: Exception) {
            // 单个文件读不出（被占用）→ 跳过而不是整包失败：
            // 少一个日志文件好过用户拿不到任何备份
            runCatching { zos.closeEntry() }
            return 0
        }
    }

    /**
     * 取某个 secure/ 条目的明文字节；不是密文返回原文，解密失败返回 null。
     *
     * 返回原文（而不是 null）是有意的：`EncryptedKeyValueStore` 有"非 AGW1 就当明文读"
     * 的兼容分支，磁盘上真存在未迁移的明文数据时，它也应该以明文形式进包。
     */
    private fun plainBytes(file: File): ByteArray? {
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (!BackupCodec.looksEncrypted(raw)) return raw
        val key = keyBytes ?: BackupCodec.loadKey(keyFile)?.also { keyBytes = it } ?: return null
        return BackupCodec.decrypt(raw, key)
    }

    // ---------------------------------------------------------------- 列举 / 删除

    fun list(): JsonObject {
        val arr = JsonArray()
        scanBackups().forEach { f ->
            val info = readManifest(f)
            arr.add(JsonObject().apply {
                addProperty("name", f.name)
                addProperty("path", f.absolutePath)
                addProperty("bytes", f.length())
                addProperty("sizeText", sizeText(f.length()))
                addProperty("atMillis", info?.first ?: f.lastModified())
                addProperty("appVersion", info?.second?.get("appVersion")?.takeIf { !it.isJsonNull }?.asString ?: "")
                addProperty("entryCount", info?.second?.get("entryCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0)
                // 老包的清单里可能是 true（那时还没禁止调用记录）。界面据此提示
                // 「这份是旧包、含调用记录」——新包一律 false。
                addProperty("includeCallLogs", info?.second?.get("includeCallLogs")?.takeIf { !it.isJsonNull }?.asBoolean ?: false)
                addProperty("auto", isAuto(f))
            })
        }
        val top = snapshotTopLevel()
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("dir", currentDir().absolutePath)
            // 自动备份固定写这里（不跟用户手选的目录走）
            addProperty("autoDir", backupDir.absolutePath)
            // 用户**显式选过**的目录；空串表示还没选过 → 前端第一次点「立即备份」时才弹窗
            addProperty("chosenDir", dirs.chosen()?.absolutePath ?: "")
            add("dirs", JsonArray().apply { dirList().forEach { add(it.absolutePath) } })
            addProperty("count", arr.size())
            add("items", arr)
            add("scope", JsonArray().apply { top.forEach { add(it) } })
            add("pending", pendingInfo())
            add("lastRestore", lastRestoreInfo())
        }
    }

    /**
     * 备份文件按创建时间倒序（最新的在最上面），并覆盖用户选过的全部目录。
     *
     * 不能按文件名字典序：同秒创建的 `…-1549.zip` 与 `…-1549-2.zip` 里，
     * `-`(45) 排在 `.`(46) 前面，字典序会把后创建的那份排到前面去——
     * 列表顺序会和"最新"直觉相反。
     */
    private fun scanBackups(): List<File> =
        dirList().flatMap { d ->
            d.listFiles { f ->
                f.isFile && (f.name.startsWith("$MANUAL_PREFIX-") || f.name.startsWith("$AUTO_PREFIX-")) &&
                    f.name.endsWith(".zip")
            }?.toList().orEmpty()
        }.sortedByDescending { readManifest(it)?.first ?: it.lastModified() }

    /** 条目是不是自动备份。名前缀是权威判据（目录可能被用户手工挪过）。 */
    private fun isAuto(file: File): Boolean = file.name.startsWith("$AUTO_PREFIX-")

    /**
     * 改「手动备份存到哪」——只记目录，不写备份。
     *
     * 界面上是备份列表里那句「手动：<路径> 改」，点开选完就走这里。
     * 不传空：空目录没有意义，用户想回默认就选那个默认目录。
     */
    fun setTarget(dir: File?): JsonObject {
        if (dir == null) return resultJson(false, "目录无效")
        dirs.remember(dir)
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("message", "手动备份将存到 ${dir.absolutePath}")
            addProperty("dir", dir.absolutePath)
        }
    }

    /** 按名字在所有已知目录里定位（删除/恢复用）。 */
    private fun find(name: String): File? {
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return dirs.resolve(name)
    }

    fun delete(name: String): JsonObject {
        val f = find(name) ?: return resultJson(false, "备份不存在：$name")
        return if (f.delete()) {
            resultJson(true, "已删除备份 $name")
        } else {
            resultJson(false, "删除失败（文件可能被占用）：$name")
        }
    }

    // ---------------------------------------------------------------- 恢复

    /**
     * 登记一次恢复请求。**不动任何现有文件**。
     *
     * 真正的替换发生在下次进程启动（[applyPending]）：那时旧进程已经退出、
     * 实例锁在手里、加密存储还没打开——三个条件同时成立才不会读到半个账号池。
     */
    fun requestRestore(name: String): JsonObject {
        val f = find(name) ?: return resultJson(false, "备份不存在：$name")
        return requestRestore(f, name)
    }

    /**
     * 按**绝对路径**登记恢复。用户从任意文件夹挑了一份自己的 zip 时走这条。
     *
     * 与按名字恢复的唯一差别是定位方式：这里不接受相对路径、不要求文件在已知目录里，
     * 但仍然跑同一套结构校验（有清单、路径安全）——"从别处拿来的包"更需要验。
     */
    fun requestRestorePath(path: String): JsonObject {
        val raw = path.trim()
        if (raw.isEmpty()) return resultJson(false, "没有指定备份文件")
        val f = File(raw)
        if (!f.isAbsolute) return resultJson(false, "请给出完整路径（例如 D:\\备份\\aigw-backup-xxx.zip）：$raw")
        if (!f.isFile) return resultJson(false, "文件不存在：${f.absolutePath}")
        if (!f.name.endsWith(".zip", ignoreCase = true)) {
            return resultJson(false, "只认本程序生成的 .zip 备份包：${f.name}")
        }
        return requestRestore(f, f.name)
    }

    private fun requestRestore(f: File, name: String): JsonObject {
        if (!f.isFile) return resultJson(false, "备份不存在：$name")
        val v = validateZip(f)
        if (!v.ok) return resultJson(false, "备份包不可用：${v.error}")
        // 手工挑进来的包也记住它所在的目录，下次列表里就能直接看到
        dirs.remember(f.absoluteFile.parentFile)
        val json = JsonObject().apply {
            addProperty("zip", f.absolutePath)
            addProperty("name", name)
            addProperty("requestedAtMillis", System.currentTimeMillis())
            addProperty("requestedByVersion", APP_VERSION)
        }
        return runCatching {
            pendingFile.writeText(json.toString())
            JsonObject().apply {
                addProperty("ok", true)
                addProperty("message", "已登记恢复：$name。重启应用后生效（重启前会自动快照当前数据）")
                addProperty("name", name)
                addProperty("path", f.absolutePath)
            }
        }.getOrElse { resultJson(false, "写入恢复标记失败：${it.message}") }
    }

    /** 取消已登记的恢复（用户改主意了）。 */
    fun cancelRestore(): JsonObject =
        if (!pendingFile.isFile) resultJson(true, "当前没有待恢复的请求")
        else if (pendingFile.delete()) resultJson(true, "已取消待恢复请求")
        else resultJson(false, "无法删除恢复标记：${pendingFile.absolutePath}")

    private fun pendingInfo(): JsonObject? {
        if (!pendingFile.isFile) return null
        val o = runCatching { JsonParser.parseString(pendingFile.readText()).asJsonObject }.getOrNull()
            ?: return null
        return JsonObject().apply {
            addProperty("name", o.str("name"))
            addProperty("path", o.str("zip"))
            addProperty("requestedAtMillis", o.long("requestedAtMillis", 0L))
            // 包还在不在：用户可能已经把 zip 挪走/删了，那种情况下重启会静默跳过，
            // 界面必须提前说"文件已不存在"，而不是等重启后什么都没发生
            addProperty("available", File(o.str("zip")).isFile)
        }
    }

    private fun lastRestoreInfo(): JsonObject? {
        if (!lastRestoreFile.isFile) return null
        return runCatching { JsonParser.parseString(lastRestoreFile.readText()).asJsonObject }.getOrNull()
    }

    // ---------------------------------------------------------------- 启动期执行

    /**
     * 启动时应用待恢复请求。**必须在本进程打开加密存储之前调用**。
     *
     * 步骤：
     * 1. 校验 zip（存在 + 结构合法 + 防穿越）；
     * 2. 把当前数据目录快照成 `pre-restore-<stamp>.zip`（回滚点，失败则整体放弃）；
     * 3. 先**解压到暂存目录** `dataDir/.restore-staging/`（不在白名单内，不会被清理误伤）；
     * 4. 暂存解压全部成功后，才清掉白名单内的现有条目并把暂存整体搬进数据目录；
     * 5. 写 `last-restore.json` 并把 pending 删掉。
     *
     * **顺序是"先解压、后清空"，不是"先清空、后解压"**：早先是先删后解，
     * 一旦包读到一半失败，数据目录已经是空的，只能靠回滚包手工救——
     * 用户看到的是"恢复失败，账号全没了"。暂存目录让"删"变成最后一步，
     * 那时所有字节都已落盘，只剩下同盘移动这种近乎不会失败的操作。
     *
     * 任一步失败都保留 pending（下次启动还能重试）并把原因写进 last-restore.json，
     * 界面在"上次恢复"一栏里能看见它——静默失败是最坏的结果。
     *
     * @return 一句话结果，null 表示没有待恢复请求。
     */
    fun applyPending(): String? {
        if (!pendingFile.isFile) return null
        val req = runCatching { JsonParser.parseString(pendingFile.readText()).asJsonObject }.getOrNull()
        val reqName = req?.str("name").orEmpty()
        val zipPath = req?.str("zip").orEmpty()
        val zip = File(zipPath)
        fun fail(msg: String): String {
            writeLast(true, msg, reqName)
            return msg
        }
        if (zipPath.isEmpty() || !zip.isFile) return fail("待恢复的备份文件已不存在：$zipPath")
        val v = validateZip(zip)
        if (!v.ok) return fail("备份包校验失败：${v.error}")

        // 1) 回滚点：先把现状整体备份一次。失败就不继续——没有回滚点的替换不接受。
        val rollback = create()
        if (rollback.get("ok")?.asBoolean != true) {
            return fail("恢复前的自动快照失败，已放弃恢复：${rollback.str("message")}")
        }
        val rollbackName = rollback.str("name")

        // 2) 解压到暂存目录（现有数据此时一个字节都没动）
        val staging = File(dataDir, STAGING_DIR)
        runCatching { staging.deleteRecursively() }
        staging.mkdirs()
        var restored = 0
        try {
            ZipFile(zip).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    if (e.name == MANIFEST_NAME) continue
                    if (!safeEntry(e.name)) continue
                    val out = File(staging, e.name)
                    out.parentFile?.mkdirs()
                    zf.getInputStream(e).use { ins -> FileOutputStream(out).use { ins.copyTo(it) } }
                    restored++
                }
            }
        } catch (e: Exception) {
            runCatching { staging.deleteRecursively() }
            return fail("解压失败（现有数据未改动，可重试）：${e.message}")
        }
        if (restored == 0) {
            runCatching { staging.deleteRecursively() }
            return fail("备份包里没有任何可恢复条目（现有数据未改动）")
        }

        // 3) 到这里才清空 —— 所有字节已落盘，剩下的只是同盘移动。
        //    先给调用记录腾地方：它不在备份范围内，也不该被恢复抹掉。
        //    `secure/logs` 是 `secure/` 的子目录，直接 deleteRecursively 会连它一起删，
        //    所以先整体搬到白名单之外的暂存名，恢复完再搬回去。
        val keepLogs = File(dataDir, KEEP_LOGS_DIR)
        runCatching { keepLogs.deleteRecursively() }
        val liveLogs = File(dataDir, "secure/logs")
        val hadLogs = liveLogs.isDirectory
        if (hadLogs) {
            runCatching {
                keepLogs.parentFile?.mkdirs()
                if (!liveLogs.renameTo(keepLogs)) liveLogs.copyRecursively(keepLogs, overwrite = true)
            }
        }
        for (top in snapshotTopLevel()) {
            val f = File(dataDir, top)
            if (f.exists()) runCatching { f.deleteRecursively() }
        }
        // 4) 暂存整体搬进数据目录
        try {
            staging.listFiles()?.forEach { src ->
                val dst = File(dataDir, src.name)
                if (dst.exists()) dst.deleteRecursively()
                if (!src.renameTo(dst)) src.copyRecursively(dst, overwrite = true)
            }
        } catch (e: Exception) {
            return fail("移入失败（回滚包 $rollbackName 可完整还原）：${e.message}")
        }
        runCatching { staging.deleteRecursively() }

        // 4.5) 调用记录搬回去。包是旧的、里面带 logs 时也不覆盖现有记录：
        //      现有那份才是用户此刻的真实历史，旧包里的同名前缀文件按序号改名保留。
        if (hadLogs && keepLogs.isDirectory) {
            val dst = File(dataDir, "secure/logs")
            runCatching {
                dst.mkdirs()
                keepLogs.listFiles()?.forEach { src ->
                    var out = File(dst, src.name)
                    var i = 2
                    while (out.exists()) { out = File(dst, "${src.nameWithoutExtension}-$i.${src.extension}"); i++ }
                    if (!src.renameTo(out)) src.copyRecursively(out, overwrite = true)
                }
                keepLogs.deleteRecursively()
            }
        }

        // 5) **明文包必须重新加密**：包内是明文（`encrypted: false`），原样搬回去
        //    就等于把凭证铺在磁盘上。旧加密包不受影响（`looksEncrypted` 直接跳过）。
        //    放在这里、任何 `EncryptedKeyValueStore` 构造之前：此时没有活句柄。
        val reencrypted = BackupCodec.reencryptSecureTree(File(dataDir, "secure"), keyFile)

        pendingFile.delete()
        val msg = buildString {
            append("已从 $reqName 恢复 $restored 个条目")
            if (reencrypted > 0) append("（其中 $reencrypted 个明文条目已重新加密）")
            append("；恢复前快照为 $rollbackName")
        }
        writeLast(false, msg, reqName, rollbackName)
        return msg
    }

    private fun writeLast(
        failed: Boolean,
        message: String,
        source: String,
        rollback: String = "",
    ) {
        runCatching {
            lastRestoreFile.writeText(JsonObject().apply {
                addProperty("atMillis", System.currentTimeMillis())
                addProperty("failed", failed)
                addProperty("message", message)
                addProperty("source", source)
                addProperty("rollback", rollback)
                addProperty("appVersion", APP_VERSION)
            }.toString())
        }
    }

    // ---------------------------------------------------------------- 校验

    private data class Check(val ok: Boolean, val error: String = "")

    /**
     * 结构校验：能打开、有清单、所有条目路径安全。
     *
     * 不做"必须有 secure/ 或 master.key"这种强校验——空数据目录的备份是合法的
     * （用户可能就是想清空重来），拒绝它只会让"恢复出厂"没法走。
     */
    private fun validateZip(f: File): Check {
        return runCatching {
            ZipFile(f).use { zf ->
                var manifest = false
                var unsafe = ""
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.name == MANIFEST_NAME) manifest = true
                    if (!e.isDirectory && !safeEntry(e.name) && unsafe.isEmpty()) unsafe = e.name
                }
                when {
                    unsafe.isNotEmpty() -> Check(false, "包含非法路径：$unsafe")
                    !manifest -> Check(false, "不是本程序生成的备份（缺少 $MANIFEST_NAME）")
                    else -> Check(true)
                }
            }
        }.getOrElse { Check(false, "${it.javaClass.simpleName}: ${it.message}") }
    }

    /**
     * 条目路径安全：拒绝绝对路径、盘符、`..` 段。
     *
     * 只判断"是否落在数据目录内"不够——Windows 上 `C:\x` 与 `\x` 都是绝对路径，
     * 而 `File(dataDir, "C:\\x")` 在 JVM 上会**忽略父目录**直接指向 C 盘。
     */
    private fun safeEntry(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.contains('\u0000')) return false
        val norm = name.replace('\\', '/')
        if (norm.startsWith("/")) return false
        if (norm.length > 2 && norm[1] == ':') return false
        return norm.split('/').none { it == ".." || it == "." }
            && norm.split('/').firstOrNull() != ""
    }

    /** 读清单：返回 (创建时间, 清单对象)。读不出来就回落到文件 mtime。 */
    private fun readManifest(f: File): Pair<Long, JsonObject>? = runCatching {
        ZipFile(f).use { zf ->
            val e = zf.getEntry(MANIFEST_NAME) ?: return@use null
            val text = zf.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
            val o = JsonParser.parseString(text).asJsonObject
            (o.long("createdAtMillis", f.lastModified())) to o
        }
    }.getOrNull()

    companion object {
        /** 手动备份前缀。老包也叫这个，改不得（改了历史包会从列表里消失）。 */
        const val MANUAL_PREFIX = "aigw-backup"

        /** 自动备份前缀：`aigw-auto-20261123-101500.zip`。 */
        const val AUTO_PREFIX = "aigw-auto"

        /** 自动备份保留份数。手动备份不轮转。 */
        const val AUTO_KEEP = 10

        /** 清单条目名。带 `aigw-` 前缀避免与数据目录里任何真实文件名冲突。 */
        const val MANIFEST_NAME = "aigw-backup-manifest.json"

        /** 待恢复标记（放在数据目录里，启动时读）。 */
        const val PENDING_NAME = ".restore-pending.json"

        /** 上次恢复结果（界面展示用）。 */
        const val LAST_RESTORE_NAME = ".last-restore.json"

        /** 恢复解压的暂存目录：**必须在白名单之外**，否则"清空白名单"会把暂存一起删掉。 */
        const val STAGING_DIR = ".restore-staging"

        /**
         * 恢复期间调用记录的临时落脚点。同样必须在白名单之外。
         *
         * 调用记录不进备份，但恢复也不该把它抹掉——用户刚聊过的东西还在界面上，
         * 恢复一次账号配置就全没了，那是纯粹的损失。
         */
        const val KEEP_LOGS_DIR = ".restore-keep-logs"

        /** 人对人可读的体积。 */
        fun sizeText(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        }
    }
}
