package dev.aigw.server

import com.google.gson.JsonParser
import dev.aigw.core.pool.AccountTransfer
import dev.aigw.core.provider.ProviderAccount
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 账号保险柜：自动备份与手动备份**分开两个目录、两个前缀**，永不互相覆盖。
 *
 * 这一层的每个判断错了都是「用户以为有备份、其实没有」——所以全部用
 * 真实文件系统跑（不用内存桩），确认磁盘上的落点与文件名。
 */
class AccountVaultTest {

    private val root: File = File(System.getProperty("java.io.tmpdir"), "aigw-vault-${System.nanoTime()}")
    private val dataDir: File = File(root, "data").apply { mkdirs() }

    /** 模拟安装目录（手动备份写这里）。 */
    private val installDir: File = File(root, "install").apply { mkdirs() }

    /** 自动备份目录也跟着隔离：真跑生产口径会写到用户真实的 %TEMP%，用例之间互相污染。 */
    private val autoDir: File = File(root, "auto")

    private val vault get() = AccountVault(dataDir, installDir, autoDir)

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun payload(vararg accounts: ProviderAccount): String =
        AccountTransfer.encode(accounts.toList(), System.currentTimeMillis())

    private fun account(uid: String, secret: String = "s-$uid"): ProviderAccount =
        ProviderAccount("trae", uid, "号$uid", secret)

    // ------------------------------------------------------------ 落点与命名

    @Test
    fun `自动备份写在数据目录之外 且默认落系统临时目录`() {
        // 这是整个功能成立的前提：用户删了数据目录，账号还得在。
        val v = vault
        assertTrue(!v.vaultDir.absolutePath.startsWith(dataDir.absolutePath), v.vaultDir.absolutePath)
        // 生产口径（不传 autoDir）必须落在系统临时目录：用户要求「直接存 C 盘临时文件夹」。
        // 只比父目录名而不用全路径 startsWith：这台机器的 TEMP 是 8.3 短名
        // （C:\Users\SQUEMA~1\...），而 vaultDir 会被换成长名，字面比较必然不等。
        val prod = AccountVault(dataDir, installDir)
        assertEquals("aigw-account-vault", prod.vaultDir.name)
        assertTrue(
            prod.vaultDir.parentFile?.name.equals("Temp", ignoreCase = true),
            "应落在系统临时目录下：${prod.vaultDir.absolutePath}",
        )
        assertTrue(!prod.vaultDir.absolutePath.startsWith(dataDir.absolutePath), prod.vaultDir.absolutePath)
        assertTrue(prod.vaultDir.path.contains("~") == false, "短名要换成长名：${prod.vaultDir.absolutePath}")
    }

    @Test
    fun `手动备份写在安装目录下的 backups`() {
        // 用户明确要求「手动备份到文件安装的目录」。
        val v = vault
        assertEquals(File(installDir.absoluteFile, "backups"), v.manualDir.absoluteFile)
    }

    @Test
    fun `自动备份文件名是 auto-时间 手动是 manual-时间`() {
        val v = vault
        val auto = v.writeAuto(payload(account("u1")), AccountVault.REASON_STARTUP, "0.9.9")
        val manual = v.writeManual(payload(account("u1")), "0.9.9")
        assertNotNull(auto)
        assertNotNull(manual)
        // 形状：auto-20261123-080000.json.gz / manual-20261123-080000.json.gz
        // 1.0.2 起是 gzip（瘦身后 18.8 KB → 约 4 KB），后缀随之变成 .json.gz
        assertTrue(Regex("""^auto-\d{8}-\d{6}(-\d+)?\.json\.gz$""").matches(auto!!.name), auto.name)
        assertTrue(Regex("""^manual-\d{8}-\d{6}(-\d+)?\.json\.gz$""").matches(manual!!.name), manual.name)
        // 落在各自目录
        assertEquals(v.vaultDir.absoluteFile, auto.absoluteFile.parentFile)
        assertEquals(v.manualDir.absoluteFile, manual.absoluteFile.parentFile)
    }

    @Test
    fun `手动备份不参与轮转 连写多份都在`() {
        // 用户要求「手动备份不要被自动备份覆盖」。手动那份只由用户删。
        val v = vault
        v.keepCount = 1
        repeat(5) { i ->
            Thread.sleep(1100)
            val f = v.writeManual(payload(account("u$i")), "0.9.9")
            assertNotNull(f, "第 $i 份手动备份必须成功")
        }
        val manualFiles = v.manualDir.listFiles { f -> f.name.startsWith("manual-") }!!.size
        assertEquals(5, manualFiles, "手动备份被轮转掉了")
    }

    @Test
    fun `自动轮转不会删手动备份`() {
        val v = vault
        v.keepCount = 1
        val manual = v.writeManual(payload(account("keep-me")), "0.9.9")
        assertNotNull(manual)
        repeat(3) { i ->
            Thread.sleep(1100)
            v.writeAuto(payload(account("u$i")), AccountVault.REASON_CHANGE, "0.9.9")
        }
        assertTrue(manual!!.isFile, "自动轮转把手动备份删了")
    }

    @Test
    fun `手动备份与自动备份同日同秒也不冲突`() {
        // 两套前缀 + 两个目录，同日同秒也不冲突。
        val v = vault
        val auto = v.writeAuto(payload(account("a")), AccountVault.REASON_CHANGE, "0.9.9")
        val manual = v.writeManual(payload(account("m")), "0.9.9")
        assertNotNull(auto)
        assertNotNull(manual)
        assertTrue(auto!!.isFile && manual!!.isFile)
        assertTrue(auto.name != manual.name)
    }

    // ------------------------------------------------------------ 自动触发语义

    @Test
    fun `同一秒内连写两次不覆盖前一份`() {
        val v = vault
        val a = v.writeAuto(payload(account("u1")), AccountVault.REASON_CHANGE, "0.9.9")
        val b = v.writeAuto(payload(account("u2")), AccountVault.REASON_CHANGE, "0.9.9")
        assertTrue(a != null && b != null)
        assertTrue(a!!.name != b!!.name, "两次备份文件名必须不同：${a.name} / ${b.name}")
        assertTrue(a.isFile && b.isFile, "两份都必须真的落盘")
    }

    @Test
    fun `内容未变时 writeIfChanged 不再写一份`() {
        val v = vault
        val text = payload(account("u1"))
        assertTrue(v.writeIfChanged(text, AccountVault.REASON_STARTUP, "0.9.9") != null)
        // 同样的内容再调一次：不该产生新文件
        assertNull(v.writeIfChanged(text, AccountVault.REASON_CHANGE, "0.9.9"))
        assertEquals(1, v.list().get("count").asInt)
    }

    @Test
    fun `凭证变化时要写新备份`() {
        // token 轮换必须保住：这是最容易丢的东西
        val v = vault
        assertTrue(v.writeIfChanged(payload(account("u1", "s1")), AccountVault.REASON_STARTUP, "0.9.9") != null)
        assertTrue(v.writeIfChanged(payload(account("u1", "s2")), AccountVault.REASON_CHANGE, "0.9.9") != null)
        assertEquals(2, v.list().get("count").asInt)
    }

    @Test
    fun `升级前那份不参与自动轮转`() {
        val v = vault
        v.keepCount = 2
        val upgrade = v.writeAuto(payload(account("old")), AccountVault.REASON_UPGRADE, "0.9.8→0.9.9")
        assertNotNull(upgrade)
        repeat(4) { i ->
            Thread.sleep(1100)
            v.writeAuto(payload(account("u$i")), AccountVault.REASON_CHANGE, "0.9.9")
        }
        val items = v.list().getAsJsonArray("items")
        val names = (0 until items.size()).map { items[it].asJsonObject.get("name").asString }
        assertTrue(upgrade!!.name in names, "升级前备份被轮转删掉了：$names")
    }

    // ------------------------------------------------------------ 校验与安全

    @Test
    fun `路径穿越与非法名一律拒绝`() {
        val v = vault
        v.writeAuto(payload(account("u1")), AccountVault.REASON_STARTUP, "0.9.9")
        v.writeManual(payload(account("u1")), "0.9.9")
        val bad = listOf("../../evil.json", "..\\evil.json", "/etc/passwd",
            "auto-x.json/../../a.json", "vault-state.json", "accounts-old.json")
        for (name in bad) {
            assertNull(v.read(name), "应当拒绝：$name")
        }
    }

    @Test
    fun `删除不存在或非法的备份返回 ok false`() {
        val v = vault
        assertTrue(!v.delete("../evil.json").get("ok").asBoolean)
        assertTrue(!v.delete("auto-nope.json").get("ok").asBoolean)
        assertTrue(!v.delete("manual-nope.json").get("ok").asBoolean)
    }

    @Test
    fun `能按名字读回自动与手动两种备份`() {
        val v = vault
        val auto = v.writeAuto(payload(account("u1")), AccountVault.REASON_STARTUP, "0.9.9")
        val manual = v.writeManual(payload(account("u2")), "0.9.9")
        assertNotNull(auto)
        assertNotNull(manual)
        // read() 按前缀路由到对应目录，不能只认自动那份
        assertNotNull(v.read(auto!!.name), "读不到自动备份")
        assertNotNull(v.read(manual!!.name), "读不到手动备份")
    }

    @Test
    fun `列表同时包含自动与手动并标注 auto 字段`() {
        val v = vault
        v.writeAuto(payload(account("a")), AccountVault.REASON_STARTUP, "0.9.9")
        Thread.sleep(1100)
        v.writeManual(payload(account("m")), "0.9.9")
        val d = v.list()
        val items = d.getAsJsonArray("items")
        assertEquals(2, items.size())
        val apps = (0 until items.size()).map { items[it].asJsonObject }
        assertTrue(apps.any { it.get("auto").asBoolean }, "缺少自动备份条目")
        assertTrue(apps.any { !it.get("auto").asBoolean }, "缺少手动备份条目")
        // 两个目录都报出来，界面要显示「手动那份在哪」
        assertTrue(d.get("dir").asString.isNotEmpty())
        assertTrue(d.get("manualDir").asString.isNotEmpty())
    }

    @Test
    fun `写入的内容本身是一份合法可导入的导出文件`() {
        // 保险柜文件 = aigw-accounts 信封 + 元字段。
        // 用户把文件拷到另一台机器，直接「导入账号」就能用，不需要转换格式。
        val v = vault
        val f = v.writeAuto(payload(account("u1"), account("u2")), AccountVault.REASON_STARTUP, "0.9.9")
        assertNotNull(f)
        val text = v.read(f!!.name)!!
        val decoded = AccountTransfer.decode(text)
        assertEquals(2, decoded.accounts.size)
        val obj = JsonParser.parseString(text).asJsonObject
        assertEquals("0.9.9", obj.get("appVersion").asString)
        assertEquals(AccountVault.REASON_STARTUP, obj.get("reason").asString)
    }

    @Test
    fun `备份失败时状态里留下原因而不是静默`() {
        // 自动目录指向一个建不出来的位置：把它的父级做成文件
        val blocker = File(root, "blocked").apply { writeText("x") }
        val badData = File(blocker, "data")
        // 自动目录的父级是那个文件 → 建不出来（生产口径在 %TEMP% 下，那里总是可写，
        // 所以这里必须显式把 autoDir 指到坏位置，才测得到失败分支）
        val v = AccountVault(badData, null, File(blocker, "auto"))
        assertNull(v.writeAuto(payload(account("u1")), AccountVault.REASON_STARTUP, "0.9.9"))
        val last = v.list().getAsJsonObject("last")
        assertTrue(last.has("message"), "失败原因必须写进状态：$last")
        assertTrue(!last.get("ok").asBoolean)
    }

    @Test
    fun `安装目录 backups 建不出来时手动备份明确失败而不是写到别处`() {
        // 装到 Program Files 时 backups 目录写不进去。这时必须回失败原因，
        // 不能静默改写到别的目录——用户按提示去安装目录找不到会更困惑。
        val roInstall = File(root, "ro-install").apply { mkdirs() }
        // 造一个「建不出子目录」的位置：把 backups 做成文件占位
        File(roInstall, "backups").apply { writeText("occupied") }
        val v = AccountVault(dataDir, roInstall)
        assertNull(v.writeManual(payload(account("u1")), "0.9.9"))
        val last = v.list().getAsJsonObject("last")
        assertTrue(!last.get("ok").asBoolean)
        assertTrue(last.get("message").asString.contains("备份"), last.toString())
    }

    @Test
    fun `没有安装目录时手动备份回落到数据目录旁`() {
        // 开发态/命令行直接跑：不知道安装目录。这时不能静默丢数据，
        // 而是回落到数据目录旁的 account-manual，界面会显示实际落点。
        val v = AccountVault(dataDir, null)
        assertEquals(File(dataDir.absoluteFile.parentFile, "account-manual"), v.manualDir.absoluteFile)
        val f = v.writeManual(payload(account("u1")), "0.9.9")
        assertNotNull(f)
        assertTrue(f!!.isFile)
    }

    @Test
    fun `空账号池的内容不会被当成有效备份列出`() {
        val v = vault
        v.writeAuto(AccountTransfer.encode(emptyList(), System.currentTimeMillis()),
            AccountVault.REASON_CHANGE, "0.9.9")
        val items = v.list().getAsJsonArray("items")
        // 仍然会列出来（文件确实存在），但账号数是 0——界面据此提示「没有可导入的内容」
        assertEquals(1, items.size())
        assertEquals(0, items[0].asJsonObject.get("accountCount").asInt)
    }
}
