package dev.aigw.server

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.File

/**
 * 「备份可能落在哪些目录」——手动备份允许用户自己选文件夹后的目录清单。
 *
 * 为什么需要这一层：一旦允许用户点「浏览…」把备份存到 `D:\我的备份`，
 * 备份列表就不能再只看一个固定目录，否则用户在下一次打开界面时会以为
 * 「我刚备份的东西没了」。这里记住最近用过的若干个目录，列表把它们并起来扫。
 *
 * 三条约定：
 * 1. **默认目录永远在第一位**，且只有它会被 `mkdirs` 创建——
 *    用户自己选的目录如果之后被删掉/拔了 U 盘，不该由我们在启动时重建。
 * 2. 清单落一个纯文本 JSON 文件（路径不是秘密），写失败只是丢了「记住」，
 *    不影响任何一次真实备份。
 * 3. 排序按最近使用倒序（`primary()` 是最新的那个），这样界面上
 *   「上一份备份存哪了」的默认值就是用户刚用过的位置。
 */
internal class BackupDirs(
    private val defaultDir: File,
    /** 清单文件。放在数据目录里，属于可再生的小状态。 */
    private val storeFile: File,
    /** 最多记几个。用户手动挑目录的频率很低，5 个足够，多了列表会变长。 */
    private val keep: Int = 5,
) {
    /** 最近使用的目录（含默认目录，去重，最近的在前）。 */
    private val recent = mutableListOf<File>()

    init {
        load()
    }

    /** 默认目录（安装目录 / 数据目录旁）。**永远存在，永远在清单里**。 */
    fun defaultDir(): File = defaultDir.absoluteFile

    /** 上次用的目录；没有记录时就是默认目录。 */
    fun primary(): File = recent.firstOrNull() ?: defaultDir()

    /** 全部候选目录，默认目录保证在内。 */
    fun all(): List<File> = (listOf(primary()) + recent + defaultDir()).map { it.absoluteFile }.distinct()

    /**
     * 记住一个用户新选的目录。
     *
     * 只接受**绝对路径**：相对路径会随进程工作目录漂移，
     * 下次启动就指向别处，用户会看到「备份还在但列表里没了」。
     */
    fun remember(dir: File?): Boolean {
        val abs = dir?.absoluteFile ?: return false
        if (!abs.isAbsolute) return false
        recent.removeAll { it.absoluteFile == abs }
        recent.add(0, abs)
        while (recent.size > keep) recent.removeAt(recent.size - 1)
        save()
        return true
    }

    /** 按文件名在清单目录里找（删除/读取用）。找不到返回 null。 */
    fun resolve(name: String): File? {
        for (d in all()) {
            val f = File(d, name)
            if (f.isFile) return f
        }
        return null
    }

    /**
     * 把界面传来的字符串解析成一个可写入的目录。
     *
     * 空串/null 表示「用上次那个」。`~` 开头的展开成用户目录——
     * 用户从别处抄路径时很常见，不展开会真的建出一个名为 `~` 的目录。
     */
    fun parse(raw: String?): File? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return primary()
        val expanded = if (s == "~" || s.startsWith("~/") || s.startsWith("~\\"))
            System.getProperty("user.home").orEmpty() + s.substring(1)
        else s
        val f = File(expanded)
        return if (f.isAbsolute) f else null
    }

    private fun load() {
        if (!storeFile.isFile) return
        runCatching {
            val arr = JsonParser.parseString(storeFile.readText(Charsets.UTF_8)).asJsonArray
            arr.forEach { el ->
                val p = runCatching { el.asString }.getOrNull() ?: return@forEach
                if (p.isNotBlank()) {
                    val f = File(p)
                    if (f.isAbsolute) recent.add(f)
                }
            }
        }
    }

    private fun save() {
        runCatching {
            storeFile.parentFile?.mkdirs()
            val arr = JsonArray()
            recent.forEach { arr.add(it.absolutePath) }
            val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(storeFile)) {
                tmp.copyTo(storeFile, overwrite = true)
                tmp.delete()
            }
        }
    }
}
