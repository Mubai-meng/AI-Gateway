package dev.aigw.desktop

import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GroupMember
import dev.aigw.core.gateway.RoutingGroup
import java.io.File

/**
 * Auto 智能路由（纯手动排序版）。
 *
 * 实现完全建立在 core 已有的「路由组」数据面上（RoutingGroupSettings + 组内
 * priority 排序 + 失败自动转移下一候选 + 供应商/模型级熔断与冷却），
 * 本类不做任何调度与打分逻辑，只负责：
 *   1. 首次启用时按模型目录默认顺序生成一条初始候选链（之后完全交给用户手动排）；
 *   2. 账号池变化时清理已失效的候选（供应商被删/禁用），不打乱用户顺序；
 *   3. 客户端 `model="auto"` 或 `model="group/auto"` 命中该组，
 *      首选失败自动换组内下一候选（跨供应商、跨模型）。
 *
 * core 一行不改：sync-core 跟随上游更新时本能力零冲突。
 *
 * 已知边界（上游组路由现状，非本类缺陷）：
 * - 区域型供应商（codebuddy/workbuddy）的组成员不带 region 约束
 *   （上游 GatewayEngine 组分支构造 Route 时丢弃 region），多区域账号并存时
 *   选号按默认区域走。
 */
class AutoRouteManager(private val engine: GatewayEngine, dataDir: File) {

    /** 持久化偏好：文件内容为 "on" 或 "off"。 */
    private val prefFile = File(dataDir, "auto-route.txt")
    private val groupId = "auto"

    // ------------------------------------------------------------ 偏好读写

    fun enabled(): Boolean = readPref() != OFF

    /** 启用/停用。停用会保留 auto 组但置为 enabled=false（UI 与目录里可见但不再路由）。 */
    fun setEnabled(on: Boolean) {
        writePref(if (on) ON else OFF)
        if (on) {
            // 已有用户手动排过的链就原样保留，只在空链时按目录默认序生成初始链
            if (chainCount() == 0) rebuild(forceRefresh = false) else enableGroup()
        } else {
            disableGroup()
        }
    }

    private fun readPref(): String =
        runCatching { prefFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: OFF

    private fun writePref(value: String) {
        runCatching {
            val tmp = File(prefFile.parentFile, prefFile.name + ".tmp")
            tmp.writeText(value)
            if (!tmp.renameTo(prefFile)) {
                tmp.copyTo(prefFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    // ------------------------------------------------------------ 组维护

    /**
     * 按模型目录默认顺序生成初始候选链：每家供应商取目录前 PER_PROVIDER_CAP 个，
     * 供应商之间 round-robin 交错（失败换家时不至于连续撞同一家），总上限 MAX_MEMBERS。
     * modelsStreaming 会逐供应商拉目录（有缓存时非 force 不打上游），必须在后台线程调用。
     */
    fun rebuild(forceRefresh: Boolean): Int {
        val usable = engine.providers().filter { it.enabled && it.usableCount > 0 }
        val catalog = engine.modelsStreaming(forceRefresh) { }
        val byProvider = LinkedHashMap<String, MutableList<String>>()
        for (m in catalog.models) {
            if (usable.none { it.id == m.providerId }) continue
            // RoutedModel.providerId 是注册表里的基础 id（区域变体走 routePrefix 表达）；
            // 组成员用基础 id，registry.get 才能命中
            byProvider.getOrPut(m.providerId) { mutableListOf() }.add(m.model.id)
        }
        if (byProvider.isEmpty()) {
            disableGroup()
            return 0
        }

        val members = ArrayList<GroupMember>()
        var priority = 300
        var more = true
        while (more && members.size < MAX_MEMBERS) {
            more = false
            for ((pid, models) in byProvider) {
                if (members.count { it.providerId == pid } >= PER_PROVIDER_CAP) continue
                val used = members.filter { it.providerId == pid }.map { it.model }.toSet()
                val next = models.firstOrNull { it !in used } ?: continue
                members.add(GroupMember(pid, next, priority, 1))
                priority--
                more = true
                if (members.size >= MAX_MEMBERS) break
            }
        }

        val group = RoutingGroup(
            id = groupId,
            name = "Auto 智能路由",
            enabled = true,
            members = members,
        )
        engine.updateRoutingGroups(engine.routingGroupSettings().upsert(group))
        return members.size
    }

    /**
     * 账号池变化后清理失效候选：只删「供应商已不存在 / 被禁用 / 无可用账号」的成员，
     * 其余成员保持用户排好的顺序与优先级。同步执行，不打上游。
     * 返回删除的成员数；没有可删的返回 0 且不写组。
     */
    fun pruneInvalid(): Int {
        val existing = engine.routingGroupSettings().list().firstOrNull { it.id == groupId } ?: return 0
        if (existing.members.isEmpty()) return 0
        val usableIds = engine.providers().filter { it.enabled && it.usableCount > 0 }.map { it.id }.toSet()
        val kept = existing.members.filter { it.providerId in usableIds }
        val removed = existing.members.size - kept.size
        if (removed == 0) return 0
        // 保留原优先级相对序：重编号为等距值即可（组内只按 priority 排序）
        val renumbered = kept.mapIndexed { i, m -> m.copy(priority = kept.size - i, weight = 1) }
        engine.updateRoutingGroups(
            engine.routingGroupSettings().upsert(existing.copy(members = renumbered)),
        )
        return removed
    }

    /** 停用：保留组结构但不再参与路由。 */
    private fun disableGroup() {
        val existing = engine.routingGroupSettings().list().firstOrNull { it.id == groupId } ?: return
        engine.updateRoutingGroups(engine.routingGroupSettings().upsert(existing.copy(enabled = false)))
    }

    private fun enableGroup() {
        val existing = engine.routingGroupSettings().list().firstOrNull { it.id == groupId } ?: return
        engine.updateRoutingGroups(engine.routingGroupSettings().upsert(existing.copy(enabled = true)))
    }

    /** 当前候选数（组不存在或为空返回 0）。 */
    fun chainCount(): Int =
        engine.routingGroupSettings().list().firstOrNull { it.id == groupId }?.members?.size ?: 0

    /** 当前链路摘要（供应商/模型，按优先级序），供 UI 展示；组不存在返回空。 */
    fun chainSummary(limit: Int = 4): Pair<List<String>, Int> {
        val group = engine.routingGroupSettings().list().firstOrNull { it.id == groupId } ?: return emptyList<String>() to 0
        val names = group.members.take(limit).map { "${it.providerId}/${it.model}" }
        return names to group.members.size
    }

    fun groupEnabled(): Boolean =
        engine.routingGroupSettings().list().firstOrNull { it.id == groupId }?.enabled == true

    companion object {
        private const val OFF = "off"
        private const val ON = "on"
        private const val MAX_MEMBERS = 30
        private const val PER_PROVIDER_CAP = 8
    }
}
