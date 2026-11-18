package dev.aigw.desktop

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicScrollBarUI
import javax.swing.plaf.metal.MetalLookAndFeel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.JTableHeader

/**
 * KDesign 令牌：与 design/ai-gateway-ui 的浅色 / 暗黑两套取值一一对应。
 * 界面所有颜色都从这里取，禁止在组件里硬编码色值，否则换主题会漏。
 */
data class KPalette(
    val bg: Color,
    val top: Color,
    val plate: Color,
    val group: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val line: Color,
    val lineLight: Color,
    val brand: Color,
    val brandLight: Color,
    val brandText: Color,
    val success: Color,
    val successBg: Color,
    val warn: Color,
    val warnBg: Color,
)

val KLIGHT = KPalette(
    bg = Color(0xF0F0F0), top = Color(0xFFFFFF), plate = Color(0xF5F5F5), group = Color(0xFAFAFA),
    text = Color(0x0D0D0D), text2 = Color(0x6B6B6B), text3 = Color(0x0D, 0x0D, 0x0D, 0x75),
    line = Color(0x0D, 0x0D, 0x0D, 0x1F), lineLight = Color(0x0D, 0x0D, 0x0D, 0x0F),
    brand = Color(0x1F69E0), brandLight = Color(0xEDF4FF), brandText = Color(0x1E5FC7),
    success = Color(0x418F1F), successBg = Color(0xD5F3DA),
    warn = Color(0xE2651A), warnBg = Color(0xFDE0CE),
)

val KDARK = KPalette(
    bg = Color(0x121212), top = Color(0x2E2E2E), plate = Color(0x242424), group = Color(0x1F1F1F),
    text = Color(0xF5F5F5), text2 = Color(0x8A8A8A), text3 = Color(0xF5, 0xF5, 0xF5, 0x75),
    line = Color(0xF5, 0xF5, 0xF5, 0x24), lineLight = Color(0xF5, 0xF5, 0xF5, 0x14),
    brand = Color(0x2572E6), brandLight = Color(0x283549), brandText = Color(0x438CFA),
    success = Color(0x459025), successBg = Color(0x1C5440),
    warn = Color(0xD0601D), warnBg = Color(0x774322),
)

/** 当前生效的调色板；切主题时整体替换，界面随重建重新取值。 */
object K {
    @Volatile
    var p: KPalette = KDARK
}

/**
 * 金色系派生令牌（额度数字、在途高亮用）。
 *
 * 不塞进 [KPalette] 是因为它是强调色而非结构色：只有少数组件用，
 * 加进主令牌会让每个调色板构造点都要多填两行，收益不抵噪音。
 */
val KPalette.goldStart: Color get() = if (this === KLIGHT) Color(0xB8801F) else Color(0xE0B15C)
val KPalette.goldText: Color get() = if (this === KLIGHT) Color(0x8A6418) else Color(0xF2CD85)
val KPalette.goldBg: Color get() = if (this === KLIGHT) Color(0xFCF2DA) else Color(0x3B301C)

/**
 * 供应商分支色板（实时拓扑用）：同一供应商在不同主题下取同一档位色，
 * 保证「同色 = 同家」的视觉约定不随主题跳变。
 */
private val PALETTE_LIGHT = listOf(
    Color(0x1F69E0), Color(0x2E9E6B), Color(0xC08A2E), Color(0x8B5CF6),
    Color(0xDB2777), Color(0x0E7490), Color(0xB45309), Color(0x4D7C0F),
)
private val PALETTE_DARK = listOf(
    Color(0x6FA8FF), Color(0x5FD3A0), Color(0xE5B968), Color(0xA78BFA),
    Color(0xF47FB0), Color(0x53C7DB), Color(0xE08B4B), Color(0x9CC751),
)

fun providerColor(index: Int): Color {
    val list = if (K.p === KLIGHT) PALETTE_LIGHT else PALETTE_DARK
    return list[index % list.size]
}

private val FAMILIES: Set<String> by lazy {
    GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
}

val UI_FAMILY: String by lazy {
    listOf("Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", "Dialog")
        .firstOrNull { it in FAMILIES } ?: Font.SANS_SERIF
}

fun kFont(size: Int, style: Int = Font.PLAIN): Font = Font(UI_FAMILY, style, size)

fun kMono(size: Int, style: Int = Font.PLAIN): Font =
    Font(if ("Consolas" in FAMILIES) "Consolas" else Font.MONOSPACED, style, size)

/**
 * 日志 / JSON 用字体：必须能显示中文。
 * 本 JDK 的逻辑 Monospaced 复合字体不含 CJK 回退（实测中文渲染成空心方框），
 * 物理字体 Consolas 同样没有 CJK；只有 UI_FAMILY（微软雅黑）可靠，直接用它。
 */
fun kMonoCjk(size: Int, style: Int = Font.PLAIN): Font = Font(UI_FAMILY, style, size)

fun antiAlias(g: Graphics): Graphics2D = (g as Graphics2D).apply {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
}

/** 圆角面板：卡片 / 分组容器的唯一基类。非 opaque，靠 paintComponent 自绘。 */
open class KRounded(
    fill: Color,
    private val stroke: Color? = null,
    private val radius: Int = 8,
    private val strokeWidth: Float = 1f,
) : JPanel() {
    var fillColor: Color = fill
    var strokeColor: Color? = stroke

    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = antiAlias(g)
        g2.color = fillColor
        g2.fillRoundRect(0, 0, width - 1, height - 1, radius * 2, radius * 2)
        strokeColor?.let {
            g2.color = it
            g2.stroke = BasicStroke(strokeWidth)
            g2.drawRoundRect(0, 0, width - 1, height - 1, radius * 2, radius * 2)
        }
    }
}

/** 圆点图标（状态灯）。 */
class DotIcon(private val color: Color, private val size: Int = 8) : Icon {
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        antiAlias(g).color = color
        g.fillOval(x, y, size, size)
    }

    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size
}

/** 主色 / 次色 / 幽灵按钮，统一圆角 6、高度 32。 */
class KButton(text: String, kind: Kind = Kind.SECONDARY) : JButton(text) {
    enum class Kind { PRIMARY, SECONDARY, QUIET }

    private val myKind = kind
    private var hover = false

    init {
        font = kFont(13, if (kind == Kind.PRIMARY) Font.BOLD else Font.PLAIN)
        foreground = if (kind == Kind.PRIMARY) Color.WHITE else K.p.text
        isFocusPainted = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = EmptyBorder(7, 16, 7, 16)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { if (isEnabled) { hover = true; repaint() } }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = antiAlias(g)
        val p = K.p
        val enabled = isEnabled
        val bg = when {
            // 禁用态（如「刷新额度」进行中）用弱底色 + 弱文字，避免看起来还能点
            !enabled -> p.group
            myKind == Kind.PRIMARY -> if (hover) p.brand.brighter() else p.brand
            myKind == Kind.SECONDARY -> if (hover) p.brandLight else p.plate
            else -> if (hover) p.brandLight else p.bg
        }
        g2.color = bg
        g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
        if (myKind == Kind.SECONDARY || !enabled) {
            g2.color = p.line
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
        }
        // 只在取值变化时才 setForeground：setForeground 会 firePropertyChange 并 repaint，
        // 每帧无条件赋值等于在 paintComponent 里请求重绘，形成重绘风暴（界面持续占 CPU）
        val want = when {
            !enabled -> p.text3
            myKind == Kind.PRIMARY -> Color.WHITE
            else -> p.text
        }
        if (foreground != want) foreground = want
        super.paintComponent(g)
    }

    override fun getPreferredSize(): Dimension {
        val d = super.getPreferredSize()
        return Dimension(d.width.coerceAtLeast(96), 32)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 32)
}

/** 侧栏导航项：选中态用主色浅底 + 主色文字，未选中用次要文字色。 */
class NavItem(val key: String, text: String) : JPanel() {
    private val label = JLabel(text)
    private var active = false
    private var hover = false
    var onClick: (() -> Unit)? = null

    init {
        layout = java.awt.BorderLayout()
        isOpaque = false
        border = EmptyBorder(7, 10, 7, 10)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        label.foreground = K.p.text2
        label.font = kFont(13)
        add(label, java.awt.BorderLayout.CENTER)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mousePressed(e: MouseEvent) { onClick?.invoke() }
        })
    }

    fun setActive(value: Boolean) {
        active = value
        label.foreground = if (value) K.p.brandText else K.p.text2
        label.font = kFont(13, if (value) Font.BOLD else Font.PLAIN)
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        if (active || hover) {
            val g2 = antiAlias(g)
            g2.color = if (active) K.p.brandLight else K.p.lineLight
            g2.fillRoundRect(0, 0, width - 1, height - 1, 16, 16)
        }
    }

    override fun getPreferredSize(): Dimension = Dimension(0, 34)

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 34)
}

/** 状态胶囊：运行中 / 待授权 / 未登录共用，颜色由调用方给。 */
/** 状态胶囊，可点击（用于主题切换等）。 */
open class KPill(text: String = "—", fg: Color = K.p.success, bg: Color = K.p.successBg) : JLabel(text) {
    private var pillBg = bg

    init {
        isOpaque = false
        font = kFont(12, Font.BOLD)
        foreground = fg
        horizontalAlignment = CENTER
        border = EmptyBorder(4, 12, 4, 12)
    }

    fun setStyle(fg: Color, bg: Color) {
        foreground = fg
        pillBg = bg
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = antiAlias(g)
        g2.color = pillBg
        val h = height - 1
        g2.fillRoundRect(0, 0, width - 1, h, h, h)
        super.paintComponent(g)
    }

    override fun getPreferredSize(): Dimension {
        val d = super.getPreferredSize()
        return Dimension(d.width.coerceAtLeast(60), 24)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 24)
}

/** 指标卡：小标签 + 大数字，高度 84。 */
class MetricCard(label: String, value: String, private val hint: String = "") :
    KRounded(K.p.top, K.p.line, 8) {
    val valueLabel = JLabel(value).apply {
        font = kFont(24, Font.BOLD)
        foreground = K.p.text
    }

    /** 卡片底部小备注（如「输入 12.3K · 输出 4.1K」「较昨日 +8.2%」）。 */
    val noteLabel = JLabel(" ").apply {
        font = kFont(11)
        foreground = K.p.text2
        isVisible = false
    }

    init {
        layout = java.awt.BorderLayout()
        border = EmptyBorder(11, 16, 11, 16)
        val topRow = JPanel(java.awt.BorderLayout()).apply {
            isOpaque = false
            add(JLabel(label).apply {
                font = kFont(12)
                foreground = K.p.text2
            }, java.awt.BorderLayout.CENTER)
            if (hint.isNotEmpty()) {
                add(JLabel(hint).apply {
                    font = kFont(11)
                    foreground = K.p.text3
                }, java.awt.BorderLayout.EAST)
            }
        }
        add(topRow, java.awt.BorderLayout.NORTH)
        add(valueLabel, java.awt.BorderLayout.CENTER)
        add(noteLabel, java.awt.BorderLayout.SOUTH)
    }

    /** 设置大数字；值未变时不动（避免每拍无谓 repaint）。 */
    fun setValue(value: String, color: Color? = null) {
        if (valueLabel.text != value) valueLabel.text = value
        val want = color ?: K.p.text
        if (valueLabel.foreground != want) valueLabel.foreground = want
    }

    /** 设置底部备注；空串即隐藏。 */
    fun setNote(text: String, color: Color? = null) {
        val want = color ?: K.p.text2
        if (noteLabel.isVisible != text.isNotEmpty()) noteLabel.isVisible = text.isNotEmpty()
        if (text.isNotEmpty() && noteLabel.text != text) noteLabel.text = text
        if (noteLabel.foreground != want) noteLabel.foreground = want
    }

    /** pref 宽度必须小（120）：概览页指标网格的列宽由它决定，
     *  过大会在窄窗口下把整个 BoxLayout 列撑爆导致右侧裁切；实际宽度由网格拉伸决定。 */
    override fun getPreferredSize(): Dimension = Dimension(120, CARD_HEIGHT)

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, CARD_HEIGHT)

    companion object {
        /** 指标卡统一高度（含标签行 / 大数字 / 备注行）。 */
        const val CARD_HEIGHT = 96
    }
}

/** 开关（替代 JCheckBox，Metal 勾选框在深色底上不可用）。 */class KSwitch(text: String) : JToggleButton() {
    private val caption = text
    private var hover = false

    init {
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        border = EmptyBorder(0, 0, 0, 0)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = antiAlias(g)
        val p = K.p
        val h = 20
        val w = 36
        val y = (height - h) / 2
        g2.color = if (isSelected) p.brand else if (hover) p.line else p.lineLight
        g2.fillRoundRect(0, y, w, h, h, h)
        g2.color = Color.WHITE
        val knob = if (isSelected) w - h + 1 else 1
        g2.fillOval(knob, y + 1, h - 2, h - 2)
        g2.color = p.text
        g2.font = kFont(13)
        val fm = g2.fontMetrics
        g2.drawString(caption, w + 10, (height + fm.ascent - fm.descent) / 2)
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(kFont(13))
        return Dimension(36 + 10 + fm.stringWidth(caption) + 2, 28)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 28)
}

/**
 * 滚动条自绘：Metal 的默认滚动条在暗黑模式下是一条亮白竖条，仅靠 UIManager 颜色覆盖压不住，
 * 必须给每个 JScrollBar 换 UI delegate。切主题会重建整棵界面树，这里取到的 K.p 就是新主题。
 */
fun JScrollPane.kStyleScrollBars() {
    val p = K.p
    fun build() = object : BasicScrollBarUI() {
        override fun configureScrollBarColors() {
            thumbColor = p.line
            thumbDarkShadowColor = p.line
            thumbHighlightColor = p.line
            thumbLightShadowColor = p.line
            trackColor = p.top
            trackHighlightColor = p.top
        }

        override fun createDecreaseButton(orientation: Int) = zero()
        override fun createIncreaseButton(orientation: Int) = zero()

        private fun zero(): JButton = JButton().apply {
            preferredSize = Dimension(0, 0)
            minimumSize = Dimension(0, 0)
            maximumSize = Dimension(0, 0)
            isFocusable = false
        }
    }
    verticalScrollBar.setUI(build())
    horizontalScrollBar.setUI(build())
    verticalScrollBar.preferredSize = Dimension(8, 0)
    horizontalScrollBar.preferredSize = Dimension(0, 8)
}

/** 自绘下拉框：圆角底 + 细描边 + 右侧三角。走 BasicComboBoxUI 但隐藏其箭头按钮，
 *  否则 Metal/Basic 的箭头钮会画在自定义底色之上（实测出现「文字框+分离小箭头钮」）。
 *  弹出列表用 UIManager 的 List 配色，与主题一致。 */
class KComboBox<E>(items: Array<E>) : JComboBox<E>(items) {

    private class KComboUI : javax.swing.plaf.basic.BasicComboBoxUI() {
        override fun createArrowButton(): JButton = JButton().apply {
            preferredSize = Dimension(0, 0)
            minimumSize = Dimension(0, 0)
            maximumSize = Dimension(0, 0)
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            isOpaque = false
            border = null
        }

        override fun paint(g: Graphics, c: JComponent) {
            val g2 = antiAlias(g)
            val p = K.p
            g2.color = p.top
            g2.fillRoundRect(0, 0, c.width - 1, c.height - 1, 12, 12)
            g2.color = p.line
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(0, 0, c.width - 1, c.height - 1, 12, 12)
            // 右侧下拉三角
            g2.color = p.text2
            val cx = c.width - 20
            val cy = c.height / 2 - 2
            g2.fillPolygon(intArrayOf(cx - 6, cx + 6, cx), intArrayOf(cy - 3, cy - 3, cy + 4), 3)
            // 选中项文字自绘（不依赖 UI 内部的 currentValueRect）
            val text = comboBox.selectedItem?.toString().orEmpty()
            if (text.isNotEmpty()) {
                g2.color = p.text
                g2.font = comboBox.font
                val fm = g2.fontMetrics
                g2.drawString(text, 12, (c.height + fm.ascent - fm.descent) / 2)
            }
        }
    }

    init {
        isOpaque = false
        font = kFont(13)
        foreground = K.p.text
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = EmptyBorder(0, 0, 0, 0)
        putClientProperty("JComboBox.isTableCellEditor", true)
        setUI(KComboUI())
    }

    override fun getPreferredSize(): Dimension {
        val d = super.getPreferredSize()
        return Dimension(d.width.coerceAtLeast(110), 32)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 32)
}

/** 自绘单行输入框：主题底色 + 圆角细描边，替代 Metal 的黑色方框。 */
open class KTextField(text: String, columns: Int = 0) : JTextField(text, columns) {
    init {
        isOpaque = false
        font = kFont(13)
        foreground = K.p.text
        caretColor = K.p.text
        selectionColor = K.p.brandLight
        selectedTextColor = K.p.brandText
        border = EmptyBorder(7, 10, 7, 10)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = antiAlias(g)
        val p = K.p
        g2.color = p.bg
        g2.fillRoundRect(0, 0, width - 1, height - 1, 12, 12)
        g2.color = p.line
        g2.stroke = BasicStroke(1f)
        g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
        super.paintComponent(g)
    }

    override fun getPreferredSize(): Dimension {
        val d = super.getPreferredSize()
        return Dimension(d.width.coerceAtLeast(120), 32)
    }
}

/** 表格主题化：Main 与 SettingsPanel 的 JTable 统一走这一套（行高 / 表头 / 隔行 / 选中态）。 */
fun JTable.applyKTable() {
    rowHeight = 28
    font = kFont(12)
    foreground = K.p.text
    background = K.p.top
    gridColor = K.p.lineLight
    setShowGrid(false)
    intercellSpacing = Dimension(0, 0)
    autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
    fillsViewportHeight = true
    setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION)
    tableHeader = object : JTableHeader(columnModel) {
        init {
            background = K.p.top
            foreground = K.p.text2
            font = kFont(11, Font.BOLD)
            preferredSize = Dimension(0, 30)
            isOpaque = true
        }
    }
    setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
            border = EmptyBorder(0, 12, 0, 12)
            c.font = kFont(12)
            if (isSelected) {
                c.background = K.p.brandLight
                c.foreground = K.p.brandText
            } else {
                c.background = if (row % 2 == 0) K.p.top else K.p.group
                c.foreground = K.p.text
            }
            c.isOpaque = true
            return c
        }
    })
}

/**
 * 固定表格各列宽度（配合 AUTO_RESIZE_OFF 使用）：列宽 = 传入的像素值，
 * 最后一列之外都锁死 min=max=width，窄窗口时表格保持总宽、由 JScrollPane 出横向滚动，
 * 不会出现表头被压烂或某列消失。调用时机：列模型已就绪（表刚建好）即可。
 */
fun JTable.kFixColumnWidths(vararg widths: Int) {
    val cm = columnModel
    for (i in 0 until minOf(widths.size, cm.columnCount)) {
        val col = cm.getColumn(i)
        val w = widths[i]
        col.minWidth = w
        col.preferredWidth = w
        col.maxWidth = w
    }
}

// ------------------------------------------------------------ KDialog：替代 JOptionPane 的 Metal 弹窗

/** 弹窗消息文字：支持 \n 换行。columns 必须固定——lineWrap 开启且 columns=0 时
 *  JTextArea 的 preferredWidth 在 pack 前不可靠（实测会把对话框压到 100 逻辑像素宽）。 */
private fun kMessageArea(message: String): JTextArea = JTextArea(message).apply {
    isEditable = false
    isFocusable = false
    font = kFont(13)
    foreground = K.p.text
    background = K.p.top
    lineWrap = true
    wrapStyleWord = true
    columns = 38
    border = EmptyBorder(4, 2, 4, 2)
}

/** KDialog 的骨架：卡片容器 + 底部按钮排，模态，居中于宿主窗口。 */
private fun openKDialog(
    parent: Component?,
    title: String,
    body: JComponent,
    buttons: List<Pair<String, () -> Unit>>,
    defaultIndex: Int = 0,
): JDialog {
    val owner: Window? = parent?.let { SwingUtilities.getWindowAncestor(it) }
    val dlg = JDialog(owner, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL)
    dlg.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
    val card = object : KRounded(K.p.top, K.p.line, 8) {
        init {
            layout = BorderLayout()
        }
    }
    val south = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }
    buttons.forEachIndexed { i, (label, action) ->
        south.add(KButton(label, if (i == defaultIndex) KButton.Kind.PRIMARY else KButton.Kind.SECONDARY).apply {
            addActionListener { action(); dlg.dispose() }
        })
    }
    val content = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(16, 18, 14, 18)
        add(body, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)
    }
    card.add(content, BorderLayout.CENTER)
    dlg.contentPane = JPanel(java.awt.GridBagLayout()).apply {
        background = K.p.bg
        add(card, java.awt.GridBagConstraints().apply { weightx = 1.0; weighty = 1.0; fill = java.awt.GridBagConstraints.BOTH })
    }
    dlg.rootPane.defaultButton = null
    // Esc 关闭
    val im = dlg.rootPane.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
    im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "esc")
    dlg.rootPane.actionMap.put("esc", object : javax.swing.AbstractAction() {
        override fun actionPerformed(e: java.awt.event.ActionEvent) = dlg.dispose()
    })
    dlg.pack()
    // 限宽兜底：JTextArea 已按 columns 折行，这里只防极端情况；setSize 后不能再 pack（会还原宽度）
    if (dlg.width > 560) dlg.setSize(560, dlg.height)
    dlg.setLocationRelativeTo(owner)
    dlg.isVisible = true
    return dlg
}

/** 信息 / 错误提示弹窗（替代 showMessageDialog）。 */
fun showKMessage(parent: Component?, message: String, title: String, error: Boolean = false) {
    val body = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(JLabel(if (error) DotIcon(K.p.warn, 10) else DotIcon(K.p.success, 10)).apply {
            border = BorderFactory.createEmptyBorder(0, 0, 0, 10)
        }, BorderLayout.WEST)
        add(kMessageArea(message), BorderLayout.CENTER)
    }
    openKDialog(parent, title, body, listOf("确定" to {}))
}

/** 输入弹窗（替代 showInputDialog），取消/关闭返回 null。 */
fun showKInput(parent: Component?, message: String, title: String, initial: String = ""): String? {
    val result = arrayOf<String?>(null)
    val field = KTextField(initial, 24).apply {
        preferredSize = Dimension(360, 32)
        selectAll()
    }
    val body = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JLabel(message).apply { font = kFont(13); foreground = K.p.text; alignmentX = Component.LEFT_ALIGNMENT })
        add(javax.swing.Box.createVerticalStrut(10))
        add(field.apply { alignmentX = Component.LEFT_ALIGNMENT })
    }
    openKDialog(
        parent, title, body,
        listOf(
            "确定" to { result[0] = field.text.trim() },
            "取消" to {},
        ),
    )
    return result[0]
}

/** 是/否确认弹窗（替代 showConfirmDialog），返回是否点了「是」。 */
fun showKConfirm(parent: Component?, message: String, title: String = "确认"): Boolean {
    val result = arrayOf(false)
    openKDialog(
        parent, title,
        kMessageArea(message),
        listOf(
            "是" to { result[0] = true },
            "否" to {},
        ),
    )
    return result[0]
}

/**
 * 自动折行的只读文本块：替代超长单行 JLabel。
 * BoxLayout 列的宽度会被最长子项撑爆（整列内容右侧裁切），单行长文本必须用
 * 定宽 columns 折行。columns 固定保证 pack 前 preferredWidth 稳定。
 */
fun kWrapArea(columns: Int, color: Color = K.p.text2): JTextArea = JTextArea().apply {
    isEditable = false
    isFocusable = false
    font = kFont(12)
    foreground = color
    background = null
    isOpaque = false
    lineWrap = true
    wrapStyleWord = true
    this.columns = columns
    border = EmptyBorder(2, 0, 2, 0)
}

/**
 * 纵向卡片列 + Scrollable：getScrollableTracksViewportWidth()=true 让 JViewport
 * 强制列宽 = 视口实际宽度。普通 JPanel 放进 JScrollPane 时，view 不会收缩到
 * preferred 宽以下——任何子卡 pref 超宽就把整列撑出视口、右侧被裁切（宽度自适应
 * 失效的根因）。
 *
 * 内部用 GridBagLayout（fill=HORIZONTAL + weightx=1）而不是 BoxLayout：
 * BoxLayout Y 轴对 alignmentX 不一致的子组件会用「最大子项的对齐」全局对齐，
 * 实测把没铺满的子卡推到右缘且不拉伸（智能路由页首屏错位的根因）。
 * GridBag 的宽度行为完全确定；用 row() 添加子项，结尾调 stretch() 吸收剩余高度。
 */
class KScrollColumn : JPanel(), javax.swing.Scrollable {
    private val rowC = java.awt.GridBagConstraints().apply {
        gridx = 0
        fill = java.awt.GridBagConstraints.HORIZONTAL
        weightx = 1.0
    }

    init {
        layout = java.awt.GridBagLayout()
        isOpaque = false
    }

    /** 追加一行：强制占满整列宽，高度用组件 preferred。 */
    fun row(c: Component) = add(c, rowC.clone() as java.awt.GridBagConstraints)

    /** 结尾吸满剩余高度，避免子项在长视口里垂直居中。 */
    fun stretch() {
        val g = java.awt.GridBagConstraints().apply {
            gridx = 0
            fill = java.awt.GridBagConstraints.BOTH
            weightx = 1.0
            weighty = 1.0
        }
        add(JPanel().apply { isOpaque = false }, g)
    }

    override fun getPreferredScrollableViewportSize(): Dimension = super.getPreferredSize()
    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = 18
    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) = 60
    override fun getScrollableTracksViewportWidth(): Boolean = true
    override fun getScrollableTracksViewportHeight(): Boolean = false
}

/**
 * 把 Swing 默认组件（设置页 / 高级设置 / 表格 / 对话框）一次性刷成当前主题。
 * 用 Metal 而不是系统 L&F：Windows L&F 会忽略 UIManager 的颜色覆盖，深色做不出来。
 */
fun installThemeDefaults() {
    runCatching { UIManager.setLookAndFeel(MetalLookAndFeel::class.java.name) }
    val p = K.p
    UIManager.put("Panel.background", p.bg)
    UIManager.put("OptionPane.background", p.top)
    UIManager.put("OptionPane.messageForeground", p.text)
    UIManager.put("Label.foreground", p.text)
    UIManager.put("Label.background", p.bg)
    UIManager.put("TextField.background", p.top)
    UIManager.put("TextField.foreground", p.text)
    UIManager.put("TextField.caretForeground", p.text)
    UIManager.put("TextField.selectionBackground", p.brandLight)
    UIManager.put("TextField.selectionForeground", p.brandText)
    UIManager.put("TextArea.background", p.top)
    UIManager.put("TextArea.foreground", p.text)
    UIManager.put("TextArea.caretForeground", p.text)
    UIManager.put("TextArea.selectionBackground", p.brandLight)
    UIManager.put("Table.background", p.plate)
    UIManager.put("Table.foreground", p.text)
    UIManager.put("Table.gridColor", p.lineLight)
    UIManager.put("Table.selectionBackground", p.brandLight)
    UIManager.put("Table.selectionForeground", p.brandText)
    UIManager.put("TableHeader.background", p.top)
    UIManager.put("TableHeader.foreground", p.text2)
    UIManager.put("Viewport.background", p.plate)
    UIManager.put("ScrollPane.background", p.plate)
    UIManager.put("ScrollBar.background", p.plate)
    UIManager.put("ScrollBar.track", p.plate)
    UIManager.put("ScrollBar.thumb", p.line)
    UIManager.put("ScrollBar.thumbShadow", p.lineLight)
    UIManager.put("ScrollBar.thumbHighlight", p.lineLight)
    UIManager.put("List.background", p.top)
    UIManager.put("List.foreground", p.text)
    UIManager.put("List.selectionBackground", p.brandLight)
    UIManager.put("List.selectionForeground", p.brandText)
    UIManager.put("SplitPane.background", p.bg)
    UIManager.put("Button.background", p.plate)
    UIManager.put("Button.foreground", p.text)
    UIManager.put("CheckBox.background", p.bg)
    UIManager.put("CheckBox.foreground", p.text)
    UIManager.put("ComboBox.background", p.top)
    UIManager.put("ComboBox.foreground", p.text)
    UIManager.put("ComboBox.selectionBackground", p.brandLight)
    UIManager.put("ComboBox.selectionForeground", p.brandText)
    UIManager.put("ComboBox.buttonBackground", p.top)
    UIManager.put("ComboBox.buttonForeground", p.text2)
    UIManager.put("ComboBox.buttonDarkShadow", p.text2)
    UIManager.put("ComboBox.buttonHighlight", p.lineLight)
    // 提示气泡与弹出菜单（tooltip 黄底 / Metal 菜单描边都是残留丑样式）
    UIManager.put("ToolTip.background", p.top)
    UIManager.put("ToolTip.foreground", p.text)
    UIManager.put("ToolTip.border", BorderFactory.createLineBorder(p.line))
    UIManager.put("ToolTip.font", kFont(12))
    UIManager.put("PopupMenu.background", p.top)
    UIManager.put("PopupMenu.foreground", p.text)
    UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(p.line))
    UIManager.put("MenuItem.background", p.top)
    UIManager.put("MenuItem.foreground", p.text)
    UIManager.put("MenuItem.selectionBackground", p.brandLight)
    UIManager.put("MenuItem.selectionForeground", p.brandText)
    // 所有 *.font 默认键统一成雅黑；等宽字体另行覆盖
    val font = kFont(13)
    val keys = UIManager.getDefaults().keys()
    while (keys.hasMoreElements()) {
        val key = keys.nextElement()
        val name = key?.toString() ?: continue
        if (name.endsWith(".font")) UIManager.getDefaults()[key] = font
    }
}

// ------------------------------------------------------------ 概览可视化组件

/**
 * 一条拓扑分支的渲染数据。
 *
 * [weight] 决定连线粗细与光斑大小（0.08~1.0）：有请求时按请求占比，
 * 无请求时退化为可用账号占比——两种口径在同一张图上不会打架，因为同一时刻只有一种生效。
 */
data class TopoBranch(
    val providerId: String,
    val displayName: String,
    val usable: Int,
    val total: Int,
    val weight: Float,
    val activeCount: Int,
    val colorIndex: Int,
)

/**
 * 实时调度拓扑画布：左侧中枢（网关）+ 右侧分支（供应商），贝塞尔扇出连线。
 *
 * 纯 Swing 绘制（不引外部图库）：中枢节点运行中高亮、停止置灰；分支按 [TopoBranch.weight]
 * 定线宽与亮度；有在途请求的分支高亮金色并沿曲线跑光斑（[phase] 0→1 循环）。
 * 超过 4 家时取前 3 家 + 「+N 其它」合并行，避免窄屏挤成一团。
 */
class TopologyCanvas : JPanel() {
    private var branches: List<TopoBranch> = emptyList()
    private var running = false

    /** 光斑相位 0..1，由外部 Timer 推进；只在绘制阶段读，不触发 layout。 */
    var phase: Float = 0f
        set(value) {
            field = value
            if (isShowing) repaint()
        }

    init {
        isOpaque = false
        preferredSize = Dimension(320, 190)
        minimumSize = Dimension(240, 170)
    }

    fun setData(running: Boolean, branches: List<TopoBranch>) {
        this.running = running
        this.branches = branches
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return

            val pad = 14
            val hubW = 96
            val hubX = pad
            val hubY = h / 2 - 30
            val hubH = 60
            val branchX = hubX + hubW + 26
            val branchW = (w - branchX - pad).coerceAtLeast(60)

            val shown = if (branches.size > 4) branches.take(3) else branches
            val hidden = branches.size - shown.size
            val rows = shown.size + (if (hidden > 0) 1 else 0)
            val rowH = if (rows == 0) 0 else ((h - pad * 2) / rows).coerceAtMost(44)
            val startY = (h - rowH * rows) / 2

            // ---- 连线（先画，压在节点下面）
            val hubCX = hubX + hubW
            val hubCY = h / 2
            var y = startY
            for (b in shown) {
                val cy = y + rowH / 2
                val color = providerColor(b.colorIndex)
                val strokeW = 1.2f + b.weight * 4.2f
                val alpha = (70 + b.weight * 150).toInt().coerceIn(50, 235)
                g2.color = Color(color.red, color.green, color.blue, alpha)
                g2.stroke = BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(bezier(hubCX, hubCY, branchX, cy))
                // 在途光斑：沿同一条贝塞尔跑
                if (b.activeCount > 0 && running) {
                    val p = bezierPoint(hubCX, hubCY, branchX, cy, phase)
                    val r = 3.0 + b.weight * 3.0
                    g2.color = K.p.goldStart
                    g2.fillOval((p.first - r).toInt(), (p.second - r).toInt(), (r * 2).toInt(), (r * 2).toInt())
                }
                y += rowH
            }
            if (hidden > 0) {
                g2.color = Color(K.p.text2.red, K.p.text2.green, K.p.text2.blue, 90)
                g2.stroke = BasicStroke(1.2f)
                g2.draw(bezier(hubCX, hubCY, branchX, y + rowH / 2))
            }

            // ---- 中枢节点
            g2.color = if (running) K.p.brandLight else K.p.plate
            g2.fillRoundRect(hubX, hubY, hubW, hubH, 10, 10)
            g2.color = if (running) K.p.brand else K.p.line
            g2.stroke = BasicStroke(1f)
            g2.drawRoundRect(hubX, hubY, hubW, hubH, 10, 10)
            g2.font = kFont(11, Font.BOLD)
            g2.color = if (running) K.p.brandText else K.p.text2
            val label = "网关"
            g2.drawString(label, hubX + (hubW - g2.fontMetrics.stringWidth(label)) / 2, hubY + 24)
            g2.font = kMono(10)
            g2.color = K.p.text2
            val port = ":" + GatewayPortHolder.port
            g2.drawString(port, hubX + (hubW - g2.fontMetrics.stringWidth(port)) / 2, hubY + 42)

            // ---- 分支节点
            y = startY
            for (b in shown) {
                val color = providerColor(b.colorIndex)
                val active = b.activeCount > 0 && running
                g2.color = if (active) K.p.goldBg else K.p.plate
                g2.fillRoundRect(branchX, y + 4, branchW, rowH - 8, 8, 8)
                g2.color = if (active) K.p.goldStart else K.p.line
                g2.stroke = BasicStroke(1f)
                g2.drawRoundRect(branchX, y + 4, branchW, rowH - 8, 8, 8)
                // 色点
                g2.color = color
                val d = 7
                g2.fillOval(branchX + 10, y + 4 + (rowH - 8 - d) / 2, d, d)
                // 名称
                g2.font = kFont(12)
                g2.color = K.p.text
                g2.drawString(clipText(g2, b.displayName, branchW - 74), branchX + 24, y + rowH / 2 + 4)
                // 账号数 / 在途
                g2.font = kMono(10)
                g2.color = if (active) K.p.goldText else K.p.text2
                val right = if (active) "${b.activeCount} 在途" else "${b.usable}/${b.total}"
                g2.drawString(right, branchX + branchW - 10 - g2.fontMetrics.stringWidth(right), y + rowH / 2 + 4)
                y += rowH
            }
            if (hidden > 0) {
                g2.font = kFont(11)
                g2.color = K.p.text2
                g2.drawString("+$hidden 其它", branchX + 10, y + rowH / 2 + 4)
            }

            if (branches.isEmpty()) {
                g2.font = kFont(12)
                g2.color = K.p.text2
                val msg = "暂无供应商账号"
                g2.drawString(msg, (w - g2.fontMetrics.stringWidth(msg)) / 2, h / 2)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun clipText(g2: Graphics2D, text: String, max: Int): String {
        val fm = g2.fontMetrics
        if (fm.stringWidth(text) <= max) return text
        var t = text
        while (t.isNotEmpty() && fm.stringWidth(t + "…") > max) t = t.dropLast(1)
        return t + "…"
    }

    /** 从中枢右缘到分支左缘的 S 形三次贝塞尔（控制点各偏一半水平距离）。 */
    private fun bezier(x1: Int, y1: Int, x2: Int, y2: Int): java.awt.geom.CubicCurve2D.Double {
        val dx = (x2 - x1).toDouble()
        return java.awt.geom.CubicCurve2D.Double(
            x1.toDouble(), y1.toDouble(),
            x1 + dx * 0.42, y1.toDouble(),
            x2 - dx * 0.42, y2.toDouble(),
            x2.toDouble(), y2.toDouble(),
        )
    }

    /** 取贝塞尔上 t∈[0,1] 处的点（展开式，只算位置不需要切线）。 */
    private fun bezierPoint(x1: Int, y1: Int, x2: Int, y2: Int, t: Float): Pair<Double, Double> {
        val tt = t.coerceIn(0f, 1f).toDouble()
        val dx = (x2 - x1).toDouble()
        val c1x = x1 + dx * 0.42
        val c2x = x2 - dx * 0.42
        fun c(p0: Double, p1: Double, p2: Double, p3: Double): Double {
            val u = 1 - tt
            return u * u * u * p0 + 3 * u * u * tt * p1 + 3 * u * tt * tt * p2 + tt * tt * tt * p3
        }
        return c(x1.toDouble(), c1x, c2x, x2.toDouble()) to
            c(y1.toDouble(), y1.toDouble(), y2.toDouble(), y2.toDouble())
    }
}

/** 拓扑中枢要显示端口，但画布不该持有引擎引用：由外观层在构造时写一次。 */
object GatewayPortHolder {
    @Volatile
    var port: Int = 8790
}

/**
 * 上下文容量分布：每个供应商一段水平堆叠条 + 图例。
 *
 * 口径与上游一致——按**模型数**分（不是 token 数）：条形长度 = 该供应商模型数 / 总计。
 * 每段颜色取 [providerColor]，图例在下方两列排布。没有模型时显示空态提示。
 */
class ContextCapacityBar : JPanel() {
    private data class Seg(val name: String, val count: Long, val colorIndex: Int)

    private var segs: List<Seg> = emptyList()

    init {
        isOpaque = false
        preferredSize = Dimension(320, 108)
        minimumSize = Dimension(240, 96)
    }

    fun setData(list: List<Triple<String, Long, Int>>) {
        segs = list.map { Seg(it.first, it.second, it.third) }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width
            if (w <= 0) return
            val total = segs.sumOf { it.count }
            val barH = 16
            val barY = 6
            if (total <= 0) {
                g2.font = kFont(12)
                g2.color = K.p.text2
                g2.drawString("暂无模型目录", 4, barY + barH)
                return
            }
            var x = 0.0
            val barW = (w - 2).toDouble()
            for ((i, s) in segs.withIndex()) {
                val segW = barW * (s.count.toDouble() / total)
                val color = providerColor(s.colorIndex)
                g2.color = color
                val rx = x.toInt()
                val rw = segW.toInt().coerceAtLeast(1)
                // 首尾段带圆角，中间段直角拼接（视觉上仍是一条连续条）
                if (segs.size == 1) {
                    g2.fillRoundRect(rx, barY, rw, barH, 8, 8)
                } else if (i == 0) {
                    g2.fillRoundRect(rx, barY, rw + 8, barH, 8, 8)
                } else if (i == segs.lastIndex) {
                    g2.fillRoundRect(rx - 8, barY, rw + 8, barH, 8, 8)
                } else {
                    g2.fillRect(rx, barY, rw + 1, barH)
                }
                x += segW
            }
            // 图例：两列，最多 8 项
            g2.font = kFont(11)
            val legendTop = barY + barH + 12
            val rowH = 18
            val colW = w / 2
            var idx = 0
            for (s in segs.take(8)) {
                val col = idx % 2
                val row = idx / 2
                val lx = 2 + col * colW
                val ly = legendTop + row * rowH
                g2.color = providerColor(s.colorIndex)
                g2.fillOval(lx, ly - 7, 6, 6)
                g2.color = K.p.text
                val pct = s.count.toDouble() / total * 100
                val text = s.name + " " + s.count + " · " + String.format("%.1f", pct) + "%"
                val fm = g2.fontMetrics
                val maxW = colW - 18
                var t = text
                while (t.isNotEmpty() && fm.stringWidth(t + "…") > maxW) t = t.dropLast(1)
                g2.drawString(if (t == text) text else t + "…", lx + 10, ly)
                idx++
            }
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 近 N 天 tokens 消耗趋势：输入 / 输出两条折线 + 日期刻度。
 *
 * 纯 Swing 绘制。数据源 `CallLogStore.dailyStats(now, days)`——末位是今天，
 * 与上游 Android 端同口径。全部为 0 时显示空态（避免画一条贴着底边的死线）。
 */
class TrendChart : JPanel() {
    private var days: List<Long> = emptyList()
    private var prompt: List<Long> = emptyList()
    private var completion: List<Long> = emptyList()

    init {
        isOpaque = false
        preferredSize = Dimension(320, 152)
        minimumSize = Dimension(240, 140)
    }

    fun setData(promptTokens: List<Long>, completionTokens: List<Long>) {
        prompt = promptTokens
        completion = completionTokens
        days = promptTokens.indices.map { it.toLong() }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return
            val padL = 8
            val padR = 8
            val padT = 12
            val padB = 22
            val plotW = (w - padL - padR).coerceAtLeast(10)
            val plotH = (h - padT - padB).coerceAtLeast(10)

            val n = prompt.size
            if (n < 2 || (prompt.all { it == 0L } && completion.all { it == 0L })) {
                g2.font = kFont(12)
                g2.color = K.p.text2
                val msg = if (n < 2) "暂无数据" else "近 ${n} 天暂无 token 消耗"
                g2.drawString(msg, (w - g2.fontMetrics.stringWidth(msg)) / 2, h / 2)
                return
            }

            val maxV = maxOf(prompt.maxOrNull() ?: 0L, completion.maxOrNull() ?: 0L, 1L)
            // 底边基线 + 三条淡网格
            g2.color = K.p.lineLight
            for (i in 0..3) {
                val y = padT + plotH * i / 3
                g2.drawLine(padL, y, padL + plotW, y)
            }

            fun xOf(i: Int): Int = padL + if (n == 1) 0 else plotW * i / (n - 1)
            fun yOf(v: Long): Int = padT + plotH - (plotH.toDouble() * v / maxV).toInt()

            // 输入（品牌蓝）/ 输出（成功绿）两条折线
            drawSeries(g2, prompt, ::xOf, ::yOf, K.p.brand)
            drawSeries(g2, completion, ::xOf, ::yOf, K.p.success)

            // 末点标注今天数值
            g2.font = kMono(10)
            val todayPt = prompt.lastOrNull() ?: 0L
            val todayOut = completion.lastOrNull() ?: 0L
            val label = "今 " + fmtTokens(todayPt) + " / " + fmtTokens(todayOut)
            g2.color = K.p.text2
            g2.drawString(label, padL, h - 6)
            val legend = "输入"
            g2.color = K.p.brand
            g2.drawString(legend, padL + 78, h - 6)
            g2.color = K.p.success
            g2.drawString("输出", padL + 106, h - 6)
        } finally {
            g2.dispose()
        }
    }

    private fun drawSeries(
        g2: Graphics2D,
        values: List<Long>,
        xOf: (Int) -> Int,
        yOf: (Long) -> Int,
        color: Color,
    ) {
        if (values.isEmpty()) return
        g2.color = color
        g2.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (i in 0 until values.size - 1) {
            g2.drawLine(xOf(i), yOf(values[i]), xOf(i + 1), yOf(values[i + 1]))
        }
        // 末点圆点
        g2.fillOval(xOf(values.size - 1) - 2, yOf(values.last()) - 2, 4, 4)
    }

    companion object {
        /** tokens 缩写：1.2K / 34.5K / 1.2M。 */
        fun fmtTokens(v: Long): String = when {
            v >= 1_000_000 -> String.format("%.1fM", v / 1_000_000.0)
            v >= 1_000 -> String.format("%.1fK", v / 1_000.0)
            else -> v.toString()
        }
    }
}

/**
 * 等分列布局：把容器的**可用宽度**严格等分给所有可见子组件（可指定间距）。
 *
 * 为什么不直接用 GridLayout：GridLayout 的容器 preferred 宽度取「子项 preferred 之和」，
 * 指标卡一多就会把整行撑到视口之外（实测 3 列被算成 1284px，而视口只有 900px，
 * 第 3 列直接被横向裁掉）。本布局只用容器的实际宽度做均分，**永不溢出**，
 * 且在任意窗口宽度下都保持「n 列等宽」的视觉约定。
 *
 * 高度：所有子项取容器高度的可用值（配合外部固定 maximumSize）。
 */
class EqualColumnsLayout(
    private val count: Int,
    private val gap: Int = 12,
) : java.awt.LayoutManager {

    override fun addLayoutComponent(name: String?, comp: Component?) = Unit
    override fun removeLayoutComponent(comp: Component?) = Unit

    override fun preferredLayoutSize(parent: java.awt.Container): Dimension {
        val visible = parent.components.count { it.isVisible }
        if (visible == 0) return Dimension(0, 0)
        val maxW = parent.components.filter { it.isVisible }.maxOf { it.preferredSize.width }
        val maxH = parent.components.filter { it.isVisible }.maxOf { it.preferredSize.height }
        return Dimension(maxW * visible + gap * (visible - 1), maxH)
    }

    override fun minimumLayoutSize(parent: java.awt.Container): Dimension = preferredLayoutSize(parent)

    override fun layoutContainer(parent: java.awt.Container) {
        val visible = parent.components.filter { it.isVisible }
        if (visible.isEmpty()) return
        val n = visible.size
        val insets = parent.insets
        val usableW = parent.width - insets.left - insets.right
        val usableH = parent.height - insets.top - insets.bottom
        if (usableW <= 0 || usableH <= 0) return
        // 宽度均分（间距从总宽里扣）：colW 至少 1，避免 0 宽导致不显示
        val colW = ((usableW - gap * (n - 1)) / n).coerceAtLeast(1)
        val h = usableH.coerceAtLeast(1)
        var x = insets.left
        for (c in visible) {
            c.setBounds(x, insets.top, colW, h)
            x += colW + gap
        }
    }
}

/**
 * 只在文本真的变化时才写入。
 *
 * `JLabel.setText` 无条件触发 firePropertyChange → revalidate → repaint，即使新旧值相同。
 * 界面每 2 秒一拍全量刷新时，几十个标签的「无变化 setText」会累积成持续重绘
 * （实测空闲 CPU 约 4%，其中大半来自这类空赋值）。所有定时刷新的赋值都走这个扩展。
 */
fun JLabel.setTextIfChanged(value: String?) {
    val v = value ?: ""
    if (text != v) text = v
}

/** 只在颜色真的变化时才写入（setForeground 同样会引发重绘）。 */
fun JLabel.setForegroundIfChanged(c: Color) {
    if (foreground != c) foreground = c
}
