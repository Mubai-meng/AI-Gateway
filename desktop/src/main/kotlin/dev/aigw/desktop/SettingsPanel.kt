package dev.aigw.desktop

import com.google.gson.GsonBuilder
import dev.aigw.core.failover.ModelFailoverSettings
import dev.aigw.core.gateway.ClientKey
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.MiddlewareSettings
import dev.aigw.core.gateway.ModelExposureSettings
import dev.aigw.core.gateway.ProxySettings
import dev.aigw.core.gateway.RoutingGroupSettings
import dev.aigw.core.security.SecuritySettings
import dev.aigw.core.usage.PricingTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

private val GSON = GsonBuilder().setPrettyPrinting().create()

private class SettingBinding(
    private val label: String,
    private val load: () -> Any,
    private val apply: (String) -> Unit,
) {
    fun json(): String = GSON.toJson(load())
    fun save(text: String) = apply(text)
    override fun toString(): String = label
}

/**
 * 「高级设置」：上游所有设置对象都用同一套 xxxSettings()/updateXxx() 契约，
 * 这里用 JSON 直接读写，1:1 覆盖全部字段，不需要为每类结构单独写表单。
 */
fun buildAdvancedSettings(engine: GatewayEngine, status: JLabel): JComponent {
    val bindings = listOf(
        SettingBinding("网关设置 · GatewaySettings", { engine.settings() }) {
            engine.updateSettings(GSON.fromJson(it, GatewaySettings::class.java))
        },
        SettingBinding("并发与容错 · ModelFailoverSettings", { engine.failoverSettings() }) {
            engine.updateFailoverSettings(GSON.fromJson(it, ModelFailoverSettings::class.java))
        },
        SettingBinding("安全防护 · SecuritySettings", { engine.securitySettings() }) {
            engine.updateSecuritySettings(GSON.fromJson(it, SecuritySettings::class.java))
        },
        SettingBinding("请求改写 · MiddlewareSettings", { engine.middlewareSettings() }) {
            engine.updateMiddlewareSettings(GSON.fromJson(it, MiddlewareSettings::class.java))
        },
        SettingBinding("路由组 · RoutingGroupSettings", { engine.routingGroupSettings() }) {
            engine.updateRoutingGroups(GSON.fromJson(it, RoutingGroupSettings::class.java))
        },
        SettingBinding("模型价格 · PricingTable", { engine.pricingTable() }) {
            engine.updatePricingTable(GSON.fromJson(it, PricingTable::class.java))
        },
        SettingBinding("模型开放名单 · ModelExposureSettings", { engine.modelExposureSettings() }) {
            engine.updateModelExposure(GSON.fromJson(it, ModelExposureSettings::class.java))
        },
        SettingBinding("网络代理 · ProxySettings", { engine.proxySettings() }) {
            engine.updateProxySettings(GSON.fromJson(it, ProxySettings::class.java))
        },
        // ClientKeyStore 没有 update-all 入口，这里把整个数组做差异写回，限额/白名单都能直接编辑
        SettingBinding("客户端密钥与限额 · ClientKey[]", { engine.clientKeyStore.list() }) { text ->
            val desired = GSON.fromJson(text, Array<ClientKey>::class.java).orEmpty().toList()
            val current = engine.clientKeyStore.list()
            current.filter { old -> desired.none { it.id == old.id } }
                .forEach { engine.clientKeyStore.remove(it.id) }
            desired.forEach { engine.clientKeyStore.update(it) }
        },
    )

    val listModel = DefaultListModel<SettingBinding>()
    bindings.forEach { listModel.addElement(it) }
    val list = JList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        preferredSize = Dimension(260, 0)
        font = kFont(13)
        fixedCellHeight = 30
        cellRenderer = javax.swing.DefaultListCellRenderer().apply {
            horizontalTextPosition = javax.swing.SwingConstants.LEFT
        }
    }
    val editor = JTextArea().apply {
        // 逻辑 Monospaced 复合字体：JSON 等宽 + 中文注释不糊成方框
        font = kMonoCjk(12)
        foreground = K.p.text
        background = K.p.top
        caretColor = K.p.text
        selectionColor = K.p.brandLight
        selectedTextColor = K.p.brandText
        border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
    }
    list.addListSelectionListener {
        val b = list.selectedValue ?: return@addListSelectionListener
        editor.text = runCatching { b.json() }.getOrElse { "读取失败：${it.message}" }
        editor.caretPosition = 0
        status.text = "  ${b}"
    }

    val save = KButton("保存并生效", KButton.Kind.PRIMARY).apply {
        addActionListener {
            val b = list.selectedValue ?: return@addActionListener
            try {
                b.save(editor.text)
                status.text = "  已保存：${b}"
            } catch (e: Exception) {
                showKMessage(null, "保存失败：${e.message}", "高级设置", error = true)
            }
        }
    }
    val reload = KButton("重新读取").apply {
        addActionListener { list.selectedValue?.let { editor.text = it.json(); editor.caretPosition = 0 } }
    }
    val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 6)).apply {
        isOpaque = false
        add(save); add(reload)
    }

    val right = JPanel(BorderLayout(4, 4)).apply {
        add(JScrollPane(editor).apply { kStyleScrollBars() }, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }
    val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(list).apply { kStyleScrollBars() }, right).apply {
        dividerLocation = 270
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    }
    val header = JPanel(GridLayout(2, 1)).apply {
        add(JLabel("  直接编辑 JSON，保存后走引擎原生 update 方法（含 reconfigureProviders 等副作用）"))
        add(status)
    }
    return JPanel(BorderLayout()).apply {
        add(header, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
    }
}

/** 客户端密钥：与上游凭证完全分离，可设白名单与限额。 */
fun buildClientKeysPanel(engine: GatewayEngine, status: JLabel): JComponent {
    val model = DefaultTableModel(arrayOf("ID", "名称", "密钥", "启用", "模型白名单", "限额数"), 0)
    val table = JTable(model).apply { applyKTable() }

    fun refresh() {
        model.rowCount = 0
        for (k in engine.clientKeyStore.list()) {
            model.addRow(
                arrayOf(
                    k.id, k.name, k.maskedSecret, if (k.enabled) "是" else "否",
                    if (k.allowedModels.isEmpty()) "不限制" else k.allowedModels.joinToString(","),
                    k.limits.size,
                )
            )
        }
        status.text = "  共 ${model.rowCount} 个客户端密钥"
    }

    fun selected(): ClientKey? {
        val row = table.selectedRow
        if (row < 0) return null
        val id = model.getValueAt(row, 0).toString()
        return engine.clientKeyStore.byId(id)
    }

    fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    val add = KButton("新建", KButton.Kind.PRIMARY).apply {
        addActionListener {
            val name = showKInput(null, "密钥名称", "新建客户端密钥")
            if (!name.isNullOrBlank()) {
                val models = showKInput(null, "模型白名单（逗号分隔，留空不限制）", "新建客户端密钥") ?: ""
                val key = engine.clientKeyStore.add(
                    name.trim(),
                    allowedModels = models.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                )
                copy(key.secret)
                showKMessage(null, "已创建并复制完整密钥到剪贴板：\n${key.secret}", "新建客户端密钥")
                refresh()
            }
        }
    }
    val toggle = KButton("启用 / 停用").apply {
        addActionListener {
            val k = selected() ?: return@addActionListener
            engine.clientKeyStore.update(k.copy(enabled = !k.enabled))
            refresh()
        }
    }
    val rotate = KButton("轮换密钥").apply {
        addActionListener {
            val k = selected() ?: return@addActionListener
            val secret = engine.clientKeyStore.rotate(k.id)
            if (secret != null) {
                copy(secret)
                showKMessage(null, "新密钥已复制：\n$secret", "轮换密钥")
                refresh()
            }
        }
    }
    val remove = KButton("删除").apply {
        addActionListener {
            val k = selected() ?: return@addActionListener
            val yes = showKConfirm(null, "删除密钥「${k.name}」？")
            if (yes) { engine.clientKeyStore.remove(k.id); refresh() }
        }
    }
    val reload = KButton("刷新").apply { addActionListener { refresh() } }
    val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 6)).apply {
        isOpaque = false
        listOf(add, toggle, rotate, remove, reload).forEach { add(it) }
    }

    refresh()
    return JPanel(BorderLayout(4, 4)).apply {
        add(status, BorderLayout.NORTH)
        add(JScrollPane(table).apply { kStyleScrollBars() }, BorderLayout.CENTER)
        add(buttons, BorderLayout.SOUTH)
    }
}
