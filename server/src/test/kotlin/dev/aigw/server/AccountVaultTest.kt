package dev.aigw.server

import com.google.gson.JsonParser
import dev.aigw.core.pool.AccountTransfer
import dev.aigw.core.provider.ProviderAccount
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 账号保险柜：自动快照的轮转、撞名、路径校验、内容未变跳过。
 *
 * 这一层的每个判断错了都是「用户以为有备份、其实没有」——
 * 所以全部用真实文件系统跑（不用内存桩），确认磁盘上的结果。
 */
class AccountVaultTest {

    private val root: File = File(System.getProperty("java.io.tmpdir"), "aigw-vault-${System.nanoTime()}")
    private val dataDir: File = File(root, "data").apply { mkdirs() }
    private val vault get() = AccountVault(dataDir)

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun payload(vararg accounts: ProviderAccount): String =
        AccountTransfer.encode(accounts.toList(), System.currentTimeMillis())

    private fun account(uid: String, secret: String = "s-$uid"): ProviderAccount =
        ProviderAccount("trae", uid, "号$uid", secret)

    @Test
    fun `快照目录在数据目录之外`() {
        // 这是整个功能成立的前提：用户删了数据目录，账号还得在。
        // 放在 dataDir 里面的话，这个断言会立刻失败。
        val v = vault
        assertTrue(!v.vaultDir.absolutePath.startsWith(dataDir.absolutePath), v.vaultDir.absolutePath)
        assertEquals(dataDir.absoluteFile.parentFile, v.vaultDir.absoluteFile.parentFile)
    }

    @Test
    fun `写入后能按时间倒序列出且带账号数`() {
        val v = vault
        val f1 = v.write(payload(account("u1")), AccountVault.REASON_STARTUP, "0.9.9")
        Thread.sleep(1100) // 时间戳精度到秒，必须跨秒才分得出先后
        val f2 = v.write(payload(account("u1"), account("u2")), AccountVault.REASON_CHANGE, "0.9.9")
        assertTrue(f1 != null && f2 != null)

        val list = v.list()
        val items = list.getAsJsonArray("items")
        assertEquals(2, items.size())
        // 新的在前
        assertEquals(f2!!.name, items[0].asJsonObject.get("name").asString)
        assertEquals(2, items[0].asJsonObject.get("accountCount").asInt)
        assertEquals(1, items[1].asJsonObject.get("accountCount").asInt)
    }

    @Test
    fun `同一秒内连写两次不会覆盖前一份`() {
        // 撞名的坑：renameTo 在目标存在时不覆盖（Windows 返 false），
        // 旧实现随后 copyTo(overwrite=true) 会把上一份吃掉。
        val v = vault
        val a = v.write(payload(account("u1")), AccountVault.REASON_MANUAL, "0.9.9")
        val b = v.write(payload(account("u2")), AccountVault.REASON_MANUAL, "0.9.9")
        assertTrue(a != null && b != null)
        assertTrue(a!!.name != b!!.name, "两次快照文件名必须不同：${a.name} / ${b.name}")
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
    fun `凭证变化时要写新快照`() {
        // token 轮换必须保住：这是最容易丢的东西
        val v = vault
        assertTrue(v.writeIfChanged(payload(account("u1", "s1")), AccountVault.REASON_STARTUP, "0.9.9") != null)
        assertTrue(v.writeIfChanged(payload(account("u1", "s2")), AccountVault.REASON_CHANGE, "0.9.9") != null)
        assertEquals(2, v.list().get("count").asInt)
    }

    @Test
    fun `日常快照按份数轮转但升级前那份永不删`() {
        val v = vault
        v.keepCount = 2
        // 先写一份「升级前」，再写 4 份日常
        val upgrade = v.write(payload(account("old")), AccountVault.REASON_UPGRADE, "0.9.9")
        assertTrue(upgrade != null)
        repeat(4) { i ->
            Thread.sleep(1100)
            v.write(payload(account("u$i")), AccountVault.REASON_CHANGE, "0.9.9")
        }
        val items = v.list().getAsJsonArray("items")
        // 2 份日常 + 1 份升级前 = 3
        assertEquals(3, items.size())
        val upgradeStillThere = items.any {
            it.asJsonObject.get("name").asString == upgrade!!.name
        }
        assertTrue(upgradeStillThere, "升级前快照被轮转删掉了：${items.map { it.asJsonObject.get("name") }}")
    }

    @Test
    fun `路径穿越与非法名一律拒绝`() {
        val v = vault
        v.write(payload(account("u1")), AccountVault.REASON_MANUAL, "0.9.9")
        val bad = listOf("../../evil.json", "..\\evil.json", "/etc/passwd", "accounts-x.json/../../a.json")
        for (name in bad) {
            assertNull(v.read(name), "应当拒绝：$name")
        }
        // 只认 accounts-*.json 形态：随便一个同目录文件名也不能读
        assertNull(v.read("vault-state.json"))
    }

    @Test
    fun `删除不存在或非法的快照返回 ok false`() {
        val v = vault
        assertTrue(!v.delete("../evil.json").get("ok").asBoolean)
        assertTrue(!v.delete("accounts-nope.json").get("ok").asBoolean)
    }

    @Test
    fun `写入的内容本身是一份合法可导入的导出文件`() {
        // 关键约定：保险柜文件 = aigw-accounts 信封 + 两个额外字段。
        // 用户把快照文件拷到另一台机器，直接「导入账号」就能用，不需要转换格式。
        val v = vault
        val f = v.write(payload(account("u1"), account("u2")), AccountVault.REASON_MANUAL, "0.9.9")
        assertTrue(f != null)
        val text = v.read(f!!.name)
        assertTrue(text != null)
        val decoded = AccountTransfer.decode(text!!)
        assertEquals(2, decoded.accounts.size)
        // 额外字段确实带上了，且不影响 decode（decode 只挑 format/version/accounts）
        val obj = JsonParser.parseString(text).asJsonObject
        assertEquals("0.9.9", obj.get("appVersion").asString)
        assertEquals(AccountVault.REASON_MANUAL, obj.get("reason").asString)
    }

    @Test
    fun `快照失败时状态里留下原因而不是静默`() {
        // 目录指向一个既不是目录也建不了的位置：把 vaultDir 的父级做成文件
        val blocker = File(root, "blocked")
        blocker.writeText("x")
        val badData = File(blocker, "data") // 父级是文件，mkdirs 必失败
        val v = AccountVault(badData)
        assertNull(v.write(payload(account("u1")), AccountVault.REASON_MANUAL, "0.9.9"))
        val list = v.list()
        val last = list.getAsJsonObject("last")
        assertTrue(last.has("message"), "失败原因必须写进状态：$last")
        assertTrue(!last.get("ok").asBoolean)
    }

    @Test
    fun `空账号池的内容不会被当成有效快照列出`() {
        // 空信封能 encode（accounts 是空数组）。启动路径已经做了空池跳过，
        // 这里确认 list 也不会把它算成一份「有内容的备份」误导用户。
        val v = vault
        v.write(AccountTransfer.encode(emptyList(), System.currentTimeMillis()), AccountVault.REASON_MANUAL, "0.9.9")
        val items = v.list().getAsJsonArray("items")
        // 仍然会列出来（文件确实存在），但账号数是 0——界面据此提示「没有可导入的内容」
        assertEquals(1, items.size())
        assertEquals(0, items[0].asJsonObject.get("accountCount").asInt)
    }
}
