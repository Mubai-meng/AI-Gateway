package dev.aigw.desktop

import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewayStatus
import dev.aigw.core.gateway.GroupMember
import dev.aigw.core.gateway.ProviderInfo
import dev.aigw.core.gateway.RoutingGroup
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.usage.CallStatus
import java.awt.AWTEvent
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.datatransfer.StringSelection
import java.awt.MouseInfo
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JSplitPane
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.JTableHeader
import kotlin.system.exitProcess

/** 单实例锁：与网关端口分离，避免和 8790 冲突。 */
private const val LOCK_PORT = 8791
private const val APP_VERSION = "0.7.0"

/** 每日自动签到的钟点（本地时区整点）。 */
private const val AUTO_CHECKIN_HOUR = 10
private var instanceLock: ServerSocket? = null

fun main(args: Array<String>) {
    instanceLock = try {
        ServerSocket(LOCK_PORT, 1, InetAddress.getByName("127.0.0.1"))
    } catch (_: Exception) {
        showKMessage(null, "AI Gateway 已在运行（锁端口 $LOCK_PORT）", "AI Gateway")
        exitProcess(1)
    }
    val dataDir = resolveDataDir(args)
    if (!dataDir.exists()) dataDir.mkdirs()
    K.p = if (themeFile(dataDir).readTextOrEmpty().trim() == "light") KLIGHT else KDARK
    installThemeDefaults()
    SwingUtilities.invokeLater { GatewayWindow(dataDir) }
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { instanceLock?.close() } })
}

private fun themeFile(dataDir: File) = File(dataDir, "ui-theme.txt")

private fun File.readTextOrEmpty(): String = runCatching { if (isFile) readText() else "" }.getOrDefault("")

private fun resolveDataDir(args: Array<String>): File {
    val i = args.indexOf("--data")
    if (i >= 0 && i + 1 < args.size) return File(args[i + 1])
    System.getProperty("aigw.data")?.let { return File(it) }
    System.getenv("AIGW_DATA")?.let { return File(it) }
    val base = System.getenv("APPDATA") ?: System.getProperty("user.home")
    return File(base, "AIGateway/data")
}

/**
 * Windows 桌面壳。只做 UI 与生命周期，所有网关能力直接调 GatewayEngine —— 与 CLI 共用同一份 :core，
 * 登录后的账号即时在内存账号池生效，不需要像 CLI 那样重启进程重新读盘。
 *
 * 布局按 design/ai-gateway-ui 落地：自绘标题栏 + 侧栏导航 + 卡片式内容区，颜色全部走 [K] 令牌。
 */
private class GatewayWindow(private val dataDir: File) {

    private val engine = GatewayEngine(
        createDesktopStore(dataDir),
        preloadCallLogs = false,
        // 调用记录全文不落盘，只留 8K 摘要，避免敏感内容以明文溢出到加密存储之外
        spillDir = null,
        onLog = { println("[aigw] $it") },
    )
    private val autoRoute = AutoRouteManager(engine, dataDir)

    private val frame = JFrame("AI Gateway · Windows").apply { isUndecorated = true }
    private val root = RootPanel()
    private val pages = JPanel(CardLayout())
    private val navItems = mutableMapOf<String, NavItem>()

    private var statusDot: JLabel? = null
    private var statusText: JLabel? = null
    private var pill: KPill? = null
    private var runButton: KButton? = null
    private var metricAccounts: MetricCard? = null
    private var metricRequests: MetricCard? = null
    private var metricTokens: MetricCard? = null
    private var metricSuccess: MetricCard? = null
    private var metricModels: MetricCard? = null
    private var metricCredits: MetricCard? = null
    private var topologyCanvas: TopologyCanvas? = null
    private var capacityBar: ContextCapacityBar? = null
    private var trendChart: TrendChart? = null
    private var topoPhaseTimer: Timer? = null
    private var baseUrlValue: JLabel? = null
    private var activityBox: JPanel? = null
    private var activityHint: JLabel? = null
    private var autoCheckinTimer: Timer? = null
    private val chainListModel = DefaultTableModel(arrayOf("顺序", "供应商", "模型"), 0)
    private var chainListTable: JTable? = null
    private val poolListModel = DefaultTableModel(arrayOf("供应商", "模型 ID"), 0)
    private var poolListTable: JTable? = null
    private var smartStatePill: KPill? = null
    private var smartHint: JTextArea? = null
    /** 工作副本：页面上的候选链，操作后立即写回路由组。 */
    private var chainWorking: MutableList<GroupMember> = mutableListOf()
    /** 模型池行 → (注册表基础 providerId, 模型 id)，与 poolListModel 行一一对应。 */
    private var poolRows: MutableList<Pair<String, String>> = mutableListOf()
    private var logFilter = "全部"
    private var allLogLines: List<dev.aigw.core.usage.LogLine> = emptyList()
    private val logTabs = mutableListOf<KPill>()

    /** 日志分类关键词：首条命中即归类；WARN/ERROR 一律进「错误」。
     *  关键词对齐 core 真实文案（GatewayHttpServer）：首字出站/流式转发结束/上游已应答/凭证刷新/账号限速。 */
    private fun dev.aigw.core.usage.LogLine.category(): String = when {
        level == "ERROR" || level == "WARN" -> "错误"
        text.contains("签到") -> "签到"
        text.contains("额度") || text.contains("积分") -> "额度"
        text.contains("首字出站") || text.contains("流式转发结束") || text.contains("上游已应答") ||
            text.contains("凭证刷新") || text.contains("账号限速") || text.contains("/v1/chat") ||
            text.contains("收到请求") || text.contains("故障转移") || text.contains("熔断") ||
            text.contains("冷却") || text.contains("超时") -> "调用"
        else -> "系统"
    }

    private val logCategoryNames = listOf("全部", "错误", "签到", "额度", "调用", "系统")


    // ------------------------------------------------------------ 智能路由页


    private val providerModel = DefaultTableModel(arrayOf("ID", "名称", "登录方式", "账号", "可用", "能力"), 0)
    private val accountModel = DefaultTableModel(arrayOf("供应商", "昵称", "余额", "状态", "UID"), 0)
    private val modelModel = DefaultTableModel(arrayOf("模型 ID", "名称"), 0)
    private var providerTable: JTable? = null
    private var accountTable: JTable? = null
    private var modelTable: JTable? = null
    private var logArea: JTextArea? = null
    private var pageTitle: JLabel? = null
    private var pageSubtitle: JLabel? = null

    private var trayIcon: TrayIcon? = null
    private var timer: Timer? = null
    private var currentPage = "overview"

    /**
     * 拖拽/缩放窗口期间挂起一切界面刷新。
     *
     * glassPane 派发（AWT 事件线程）与 Swing Timer 的刷新在同一线程上串行，
     * 一次 refreshAll 要遍历日志/日志分类/表格/拓扑再加数次 repaint，
     * 拖动时每帧都被抢线程 → 掉帧、手感「拖两下拖不动」。拖拽期间全部跳过，
     * 松手后补一次即可。
     */
    private var suspendUiRefresh = false

    /** 上一帧刷新时间戳：refreshAll 自身也有 200ms 节流兜底，防拖拽外的高频重入。 */
    private var lastRefreshAt = 0L

    /** 「刷新额度」按钮集合：概览页与供应商页各一个，刷新时统一改状态。 */
    private val creditButtons = mutableListOf<KButton>()

    /** 额度刷新进行中：按钮置灰 + 文案改「刷新中…」，并发防护避免连点叠请求。 */
    private var refreshingCredits = false

    /** 额度刷新结果回显（就地显示，不弹窗）。 */
    private var creditStatus: JLabel? = null

    /** 最近调用卡的重建签名：内容没变就不重排组件，避免 2 秒一拍无谓的 removeAll/add/revalidate。 */
    private var activitySignature = ""

    /** 日志页渲染窗口：只保留最近这么多行（增量插入 + 尾部裁剪，代价恒定）。 */
    private val LOG_WINDOW = 160

    /** 上次渲染的 seq 序列（newest-first），用来判定能否走增量路径。 */
    private var logRenderedSeqs: List<Long> = emptyList()
    /** 标题栏引用：glassPane 重派发时，标题栏空白区的事件要给 bar 本体（窗口拖动），不能给最深子组件。 */
    private var titleBarPanel: JPanel? = null
    /** 页面组件按 key 索引：selectPage 时递归 revalidate 用。 */
    private val pageCards = mutableMapOf<String, Component>()
    /** 最大化/半屏前的窗口边界：还原用。 */
    private var normalBounds: Rectangle? = null

    /** 统一的最大化/还原入口：标题栏双击、窗口按钮、snap 共用。 */
    internal fun toggleMaximize() {
        if (frame.extendedState and JFrame.MAXIMIZED_BOTH != 0) {
            frame.extendedState = JFrame.NORMAL
            normalBounds?.let { frame.bounds = it }
        } else {
            normalBounds = frame.bounds
            frame.extendedState = JFrame.MAXIMIZED_BOTH
        }
    }

    init {
        engine.migrateAccounts()
        engine.onAccountsChanged = {
            SwingUtilities.invokeLater { refreshAll() }
            // 账号池变化（新登录/登出/禁用）后只清理 Auto 链里已失效的候选，
            // 其余成员保持用户手动排好的顺序，不重排、不打上游
            if (autoRoute.enabled()) {
                Thread {
                    val removed = runCatching { autoRoute.pruneInvalid() }.getOrDefault(0)
                    if (removed > 0) SwingUtilities.invokeLater { reloadSmartRoute() }
                }.apply {
                    isDaemon = true
                    start()
                }
            }
        }
        buildWindow()
        installTray()
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { engine.stop() }
            runCatching { engine.stopCallbackServer() }
        })
        startGateway(quiet = true)
        // 启动时只在候选链为空的情况下按目录默认序生成初始链，绝不覆盖用户手动排序
        if (autoRoute.enabled() && autoRoute.chainCount() == 0) rebuildAutoChain()
        // 每日自动签到：固定每天 10:00 批量跑一次（不是轮询）。
        // 用「算到下一个 10:00 的延时」的单发 Timer，触发后重新排下一次，
        // 中间完全不醒；闸门（一天一次/全部成功才记完成/失败 1h 退避/防重入）仍在 core 的 autoCheckin 里。
        scheduleDailyCheckin()
        timer = Timer(2000) { refreshAll() }.apply { start() }
        refreshAll()
        // 概览「模型数」卡：后台静默预拉一次模型目录，避免首屏一直显示「—」
        prewarmModelCount()
        frame.isVisible = true
        // 调试钩子：-Daigw.debug.page=<key> 启动直接切到指定页（自动化验证用）
        System.getProperty("aigw.debug.page")?.let { page ->
            SwingUtilities.invokeLater { selectPage(page) }
        }
        // 调试钩子：-Daigw.debug.overview=1 打印概览页关键容器的实际 bounds（排布局问题用）
        if (System.getProperty("aigw.debug.overview") != null) {
            Thread {
                Thread.sleep(5000)
                val f = java.io.File("E:/New/exeAIfandai/aigw-windows/design/overview-bounds.log")
                f.writeText("")
                // 递归打印到 4 层，重点看 KScrollColumn 内部行容器的真实宽度
                fun walk(c: java.awt.Container?, depth: Int, tag: String) {
                    if (c == null || depth > 4) return
                    f.appendText(
                        "${" ".repeat(depth * 2)}[$tag] ${c.javaClass.simpleName} " +
                            "bounds=${c.width}x${c.height} pref=${c.preferredSize.width} " +
                            "min=${c.minimumSize.width} layout=${c.layout?.javaClass?.simpleName} " +
                            "kids=${c.componentCount}\n"
                    )
                    for (child in c.components) {
                        if (child is java.awt.Container) {
                            walk(child, depth + 1, child.javaClass.simpleName)
                        } else {
                            f.appendText(
                                "${" ".repeat((depth + 1) * 2)}- ${child.javaClass.simpleName} " +
                                    "x=${child.x} w=${child.width} pref=${child.preferredSize.width} " +
                                    "vis=${child.isVisible}\n"
                            )
                        }
                    }
                }
                val ov = pageCards["overview"]
                walk(ov as? java.awt.Container, 0, "page")
                (ov?.parent as? java.awt.Container)?.let {
                    walk(it, 0, "cardLayoutHolder")
                }
                // 指标卡逐张打印（递归按类型抓，不依赖 overviewPage 的局部变量）
                f.appendText("\n=== MetricCard 实例 ===\n")
                fun findCards(c: java.awt.Container?, depth: Int) {
                    if (c == null || depth > 8) return
                    for (child in c.components) {
                        if (child is MetricCard) {
                            f.appendText(
                                "  in=${c.javaClass.simpleName}(w=${c.width},layout=" +
                                    "${c.layout?.javaClass?.simpleName}) " +
                                    "x=${child.x} y=${child.y} w=${child.width} h=${child.height} " +
                                    "pref=${child.preferredSize.width} vis=${child.isVisible}\n"
                            )
                        }
                        if (child is java.awt.Container) findCards(child, depth + 1)
                    }
                }
                findCards(ov as? java.awt.Container, 0)
                // 直接把概览页绘制到 BufferedImage 导出：不依赖屏幕截图，
                // 绕开 DPI 缩放 / 窗口被遮挡造成的假象（这是验证 Swing 布局的唯一可靠方式）
                if (ov != null) {
                    SwingUtilities.invokeLater {
                        runCatching {
                            val img = java.awt.image.BufferedImage(
                                918, minOf(ov.height, 2400), java.awt.image.BufferedImage.TYPE_INT_RGB
                            )
                            val g = img.createGraphics()
                            g.color = K.p.bg
                            g.fillRect(0, 0, img.width, img.height)
                            ov.printAll(g)
                            g.dispose()
                            javax.imageio.ImageIO.write(
                                img, "png",
                                java.io.File("E:/New/exeAIfandai/aigw-windows/design/overview-render.png"),
                            )
                            f.appendText("\n已导出 overview-render.png (${img.width}x${img.height})\n")
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
        }
        // 调试钩子：-Daigw.debug.snaptest=1 Robot 真实输入模拟「拖标题栏到屏幕顶」验证 snap
        if (System.getProperty("aigw.debug.snaptest") != null) {
            Thread {
                val out = java.io.File("E:/New/exeAIfandai/aigw-windows/design/snaptest.log")
                fun log(s: String) = out.appendText(s + System.lineSeparator())
                try {
                    Thread.sleep(3000)
                    SwingUtilities.invokeAndWait {
                        frame.setLocation(80, 0)
                        frame.toFront()
                    }
                    Thread.sleep(800)
                    val r = Robot()
                    fun state(tag: String) = log("[$tag] maximized=${frame.extendedState and JFrame.MAXIMIZED_BOTH != 0} bounds=${frame.bounds}")
                    // 1) 拖到顶部 → 最大化
                    SwingUtilities.invokeAndWait { frame.setLocation(80, 0) }
                    Thread.sleep(400)
                    var loc = frame.locationOnScreen
                    r.mouseMove(loc.x + 500, loc.y + 22); r.mousePress(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(200)
                    r.mouseMove(loc.x + 500, loc.y + 6); Thread.sleep(250)
                    r.mouseMove(loc.x + 500, loc.y + 0); Thread.sleep(300)
                    r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(1200)
                    state("snap-top")
                    // 2) 拖标题栏向下 → 还原 NORMAL
                    loc = frame.locationOnScreen
                    r.mouseMove(loc.x + 500, loc.y + 22); r.mousePress(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(200)
                    r.mouseMove(loc.x + 500, loc.y + 200); Thread.sleep(300)
                    r.mouseMove(loc.x + 500, loc.y + 400); Thread.sleep(300)
                    r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(1000)
                    state("drag-restore")
                    // 3) 拖到左边缘 → 左半屏
                    SwingUtilities.invokeAndWait { frame.setLocation(0, 120) }
                    Thread.sleep(400)
                    r.mouseMove(500, 142); r.mousePress(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(200)
                    r.mouseMove(200, 400); Thread.sleep(250)
                    r.mouseMove(0, 420); Thread.sleep(300)
                    r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(1200)
                    state("snap-left")
                    // 4) 双击标题栏 → 最大化
                    loc = frame.locationOnScreen
                    repeat(2) {
                        r.mouseMove(loc.x + 500, loc.y + 22); r.mousePress(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(60)
                        r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(120)
                    }
                    Thread.sleep(1200)
                    state("dblclick-max")
                    // 5) 再双击 → 还原
                    loc = frame.locationOnScreen
                    repeat(2) {
                        r.mouseMove(loc.x + 500, loc.y + 22); r.mousePress(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(60)
                        r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); Thread.sleep(120)
                    }
                    Thread.sleep(1200)
                    state("dblclick-restore")
                } catch (e: Exception) {
                    log("SNAPTEST EXCEPTION ${e.javaClass.name}: ${e.message}")
                }
            }.apply { isDaemon = true; start() }
        }
    }

    // ------------------------------------------------------------ 窗口骨架

    private fun buildWindow() {
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.minimumSize = Dimension(860, 540)
        // 高 DPI 屏上窗口物理尺寸会被放大：按可用工作区算，保证整窗放得下（1280×800@150% 也不会溢出）
        val usable = Toolkit.getDefaultToolkit().screenSize
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(frame.graphicsConfiguration)
        val availW = usable.width - insets.left - insets.right
        val availH = usable.height - insets.top - insets.bottom
        val w = minOf(1120, availW - 24).coerceAtLeast(860)
        val h = minOf(720, availH - 24).coerceAtLeast(540)
        frame.setBounds(
            insets.left + (availW - w) / 2,
            insets.top + (availH - h) / 2,
            w,
            h,
        )
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                if (trayIcon != null) frame.isVisible = false else exitApp()
            }
        })

        root.layout = BorderLayout()
        root.background = K.p.bg
        root.border = BorderFactory.createLineBorder(K.p.line)
        root.add(rebuildContent(), BorderLayout.CENTER)
        frame.contentPane = root
        installResizeHandles(frame)
    }

    /** 无边框窗口的边缘/四角拖拽调宽高：glassPane 捕获方案。
     *  glassPane 在全部内容之上必然收到鼠标事件；边缘热区（8px）内按下进入调宽高模式，
     *  其余点击原样重派发给最深子组件（按钮/页面交互不受影响）。
     *  旧方案对比：contentPane 监听被深层组件抢事件、layeredPane 热区收不到真实输入、
     *  AWTEventListener 在本环境收不到窗口真实输入（只收到托盘合成事件），均不可用。 */
    private fun installResizeHandles(frame: JFrame) {
        val glass = frame.rootPane.glassPane
        val T = 8          // 边缘热区宽度（逻辑像素）
        val MIN_W = 860
        val MIN_H = 540
        var mode = 0
        var press: Point? = null
        var start: Rectangle? = null
        /** 当前 hover 目标：glassPane 截断 ENTERED/EXITED，需要合成派发维持悬停特效。 */
        var hoverTarget: Component? = null
        var pressTarget: Component? = null
        var lastClickTarget: Component? = null
        var lastClickTime = 0L
        /** 标题栏拖动整套由 glass 层接管：移动 / Aero Snap（顶最大化、左右半屏）/
         *  最大化还原 / 双击切换。bar 的监听已移除——光标移出标题栏后事件到不了 bar，
         *  只有 glass 层能全程跟随（Windows 原生窗口的非客户区行为）。 */
        var titleDrag = false
        var titlePress: Point? = null
        var titleBase: Rectangle? = null
        /** 按下时光标在窗口内的坐标（抓取点）：拖动时保持不变，据此把窗口钉在光标上。 */
        var titleGrip: Point? = null
        var pendingEdge = 0
        var lastTitleRelease = 0L
        /** 上一次已处理的 mouseMoved 逻辑坐标：同一像素的重复事件直接丢弃（见 mouseMoved）。 */
        var lastMoveX = Int.MIN_VALUE
        var lastMoveY = Int.MIN_VALUE

        /** 找派发目标：layeredPane 内最深组件 + 沿父链上溯到第一个带鼠标监听的组件
         *  （NavItem/卡片等把监听装在自己身上，最深组件常是无监听内部 JLabel）。 */
        fun redispatchTarget(e: MouseEvent): Component? {
            val pane = frame.rootPane.layeredPane
            var target = SwingUtilities.getDeepestComponentAt(pane, e.x, e.y) ?: return null
            var c: Component? = target
            var withListener: Component? = null
            while (c != null && c !is java.awt.Window && c !== pane) {
                if (c.mouseListeners.isNotEmpty() || c.mouseMotionListeners.isNotEmpty() ||
                    c.mouseWheelListeners.isNotEmpty()
                ) {
                    withListener = c
                    break
                }
                c = c.parent
            }
            return withListener ?: target
        }
        fun redispatch(e: MouseEvent, target: Component?) {
            if (target == null) return
            target.dispatchEvent(SwingUtilities.convertMouseEvent(glass, e, target))
        }
        /** 合成 MOUSE_CLICKED：native 层才会在 press+release 配对时生成 CLICKED，
         *  dispatchEvent 不会自动产生——主题胶囊 / 表格双击等 mouseClicked 监听全靠它。 */
        fun fireClicked(e: MouseEvent, target: Component) {
            val now = e.getWhen()
            val clicks = if (target === lastClickTarget && now - lastClickTime < 600) 2 else 1
            lastClickTarget = target
            lastClickTime = now
            val synthetic = MouseEvent(glass, MouseEvent.MOUSE_CLICKED, now, e.modifiers, e.x, e.y, clicks, false)
            target.dispatchEvent(SwingUtilities.convertMouseEvent(glass, synthetic, target))
        }
        /** 合成 MOUSE_ENTERED/EXITED：底层组件原本收不到（glass 在上层），
         *  按钮/导航/胶囊的悬停变色全靠这两个事件。
         *  [target] 由调用方传入：mouseMoved 已算过一次命中，重复算就是两次深度遍历。 */
        fun updateHover(e: MouseEvent, target: Component?) {
            if (target == null) return
            if (target === hoverTarget) return
            val stamp = e.getWhen()
            hoverTarget?.let { old ->
                if (old.isShowing) {
                    val p = SwingUtilities.convertPoint(glass, e.x, e.y, old)
                    old.dispatchEvent(MouseEvent(old, MouseEvent.MOUSE_EXITED, stamp, e.modifiers, p.x, p.y, 1, false))
                }
            }
            val p2 = SwingUtilities.convertPoint(glass, e.x, e.y, target)
            target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_ENTERED, stamp, e.modifiers, p2.x, p2.y, 1, false))
            hoverTarget = target
        }
        fun clearHover(time: Long) {
            hoverTarget?.let { old ->
                if (old.isShowing) old.dispatchEvent(
                    MouseEvent(old, MouseEvent.MOUSE_EXITED, time, 0, 0, 0, 1, false)
                )
            }
            hoverTarget = null
        }
        fun modeOf(x: Int, y: Int): Int {
            // 标题栏拖动中一律不进 resize：拖到屏幕顶/左右做 snap 时光标必然越过窗口边缘
            if (titleDrag) return 0
            // 标题栏右上角控制区（最小化/最大化/关闭/主题胶囊）绝不进 resize 模式，
            // 否则热区把窗口按钮的点击吃掉——0.5.6 的「窗口按钮失效」就是这里漏了
            if (y < 48 && x >= frame.width - 180) return 0
            var m = 0
            if (x < T) m = m or 8       // W
            if (x >= frame.width - T) m = m or 4   // E
            if (y < T) m = m or 1       // N
            if (y >= frame.height - T) m = m or 2  // S
            val c = 16
            if ((x < c || x >= frame.width - c) && (y < c || y >= frame.height - c)) {
                if (x < c) m = m or 8 else if (x >= frame.width - c) m = m or 4
                if (y < c) m = m or 1 else if (y >= frame.height - c) m = m or 2
            }
            return m
        }
        fun cursorFor(m: Int): Cursor = when (m) {
            1 -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
            2 -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
            4 -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
            8 -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
            5 -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)
            9 -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
            6 -> Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
            10 -> Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR)
            else -> Cursor.getDefaultCursor()
        }
        /**
         * 窗口移动/缩放的**改帧器**：mouseDragged 只登记目标位置，真正的 SetWindowPos
         * 由 16ms 的 Timer 每帧合并执行一次。
         *
         * 之前每个 mouseDragged 事件都直接 `frame.setLocation`：一次拖动在 100+ 事件/秒下
         * 产生上百次同步 SetWindowPos + 整窗重排/重绘，EDT 被塞满 → 拖动掉帧、窗口落后于
         * 光标（相对位置漂移），指针被甩出窗口后 glass 收不到事件，表现就是「拖两下就拖不动」。
         */
        var pendingBounds: Rectangle? = null
        var moveTicker: Timer? = null

        /**
         * 取拖动用的屏幕坐标，**以真实光标为主、事件坐标为兜底**。
         *
         * 为什么不用纯事件坐标：AWT 的 `xOnScreen` 由「窗口当前位置 + 客户区坐标」算出，
         * 而客户区坐标是 Windows 按当前窗口位置给的，窗口一跟手就跟不上——高 DPI + 事件积压时
         * 会出现「窗口追光标 → 坐标被补偿 → 追不上」的抖动。
         * 为什么不能纯 MouseInfo：指针被别处占用 / 无人值守注入时 MouseInfo 拿不到真实位置。
         * 判据：两者差异超过 200px 视为 MouseInfo 不可信，回退事件坐标。
         */
        fun pointerLocation(eventScreen: Point): Point {
            val phys = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
                ?: return eventScreen
            val dx = kotlin.math.abs(phys.x - eventScreen.x)
            val dy = kotlin.math.abs(phys.y - eventScreen.y)
            return if (dx > 200 || dy > 200) eventScreen else phys
        }

        fun flushMoveFrame() {
            val b = pendingBounds ?: return
            pendingBounds = null
            val cur = frame.bounds
            when {
                // 尺寸没变 → 只挪位置。setLocation 不触发界面树的 revalidate/layout，
                // 而 setBounds 即使尺寸相同也会走一遍容器重排（高 DPI 下代价明显）。
                cur.width == b.width && cur.height == b.height ->
                    if (cur.x != b.x || cur.y != b.y) frame.setLocation(b.x, b.y)
                else -> frame.bounds = b
            }
        }

        fun pushBounds(b: Rectangle) {
            pendingBounds = b
            if (moveTicker?.isRunning != true) {
                moveTicker = Timer(16) { flushMoveFrame() }.apply { start() }
            }
            suspendUiRefresh = true
        }

        fun stopMoveTicker() {
            flushMoveFrame()
            moveTicker?.stop(); moveTicker = null
            suspendUiRefresh = false
        }

        /** 光标进入屏幕工作区边缘多少像素算 snap 预览。
         *  坐标一律用显示器**虚拟桌面绝对坐标**（b.x/b.y 起点），
         *  早先按 ins.left / 0 计算，副屏（原点不为 0）会把窗口吸到主屏上——即「贴边位置乱跳」。 */
        fun snapEdgeOf(scr: Point): Int {
            val gc = frame.graphicsConfiguration
            val b = gc.bounds
            val ins = Toolkit.getDefaultToolkit().getScreenInsets(gc)
            val workTop = b.y + ins.top
            val workBottom = b.y + b.height - ins.bottom
            val workLeft = b.x + ins.left
            val workRight = b.x + b.width - ins.right
            if (scr.y <= workTop + 1) return 1
            if (scr.x <= workLeft + 2 && scr.y < workBottom - 80) return 2
            if (scr.x >= workRight - 2 && scr.y < workBottom - 80) return 3
            return 0
        }

        /** 吸附到左/右半屏（先确保 NORMAL）。坐标同样走虚拟桌面绝对坐标。 */
        fun snapToHalf(left: Boolean) {
            if (frame.extendedState and JFrame.MAXIMIZED_BOTH != 0) frame.extendedState = JFrame.NORMAL
            normalBounds = frame.bounds
            val gc = frame.graphicsConfiguration
            val b = gc.bounds
            val ins = Toolkit.getDefaultToolkit().getScreenInsets(gc)
            val w = (b.width - ins.left - ins.right) / 2
            val h = b.height - ins.top - ins.bottom
            frame.bounds = Rectangle(b.x + (if (left) ins.left else ins.left + w), b.y + ins.top, w, h)
        }

        fun resizeRect(dx: Int, dy: Int): Rectangle? {
            val b0 = start ?: return null
            var x = b0.x; var y = b0.y; var w = b0.width; var h = b0.height
            if (mode and 4 != 0) w = b0.width + dx
            if (mode and 8 != 0) { x = b0.x + dx; w = b0.width - dx }
            if (mode and 2 != 0) h = b0.height + dy
            if (mode and 1 != 0) { y = b0.y + dy; h = b0.height - dy }
            if (w < MIN_W) { if (mode and 8 != 0) x -= (MIN_W - w); w = MIN_W }
            if (h < MIN_H) { if (mode and 1 != 0) y -= (MIN_H - h); h = MIN_H }
            return Rectangle(x, y, w, h)
        }
        glass.isVisible = true
        glass.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val m = modeOf(e.x, e.y)
                if (m != 0) {
                    mode = m
                    press = pointerLocation(Point(e.xOnScreen, e.yOnScreen))
                    start = frame.bounds
                    glass.cursor = cursorFor(m)
                } else if (e.y < 48 && e.x < frame.width - 180) {
                    // 标题栏拖动区（避开右上角窗口按钮/主题胶囊）：glass 全程接管
                    titleDrag = true
                    titleGrip = Point(e.x, e.y)
                    titlePress = pointerLocation(Point(e.xOnScreen, e.yOnScreen))
                    titleBase = normalBounds ?: frame.bounds
                    pendingEdge = 0
                } else {
                    val t = redispatchTarget(e)
                    pressTarget = t
                    redispatch(e, t)
                }
            }
            override fun mouseReleased(e: MouseEvent) {
                if (titleDrag) {
                    val now = e.getWhen()
                    stopMoveTicker() // 先把最后一帧位置落到位，再执行 snap/还原
                    when (pendingEdge) {
                        1 -> {
                            normalBounds = titleBase
                            frame.extendedState = JFrame.MAXIMIZED_BOTH
                        }
                        2 -> snapToHalf(true)
                        3 -> snapToHalf(false)
                    }
                    // 双击标题栏：600ms 内两次 release 同一标题栏 → 最大化/还原
                    if (pendingEdge == 0 && now - lastTitleRelease < 600) {
                        toggleMaximize()
                        lastTitleRelease = 0
                    } else {
                        lastTitleRelease = now
                    }
                    titleDrag = false
                    pendingEdge = 0
                    // 拖拽期间刷新被挂起，松手补一帧
                    SwingUtilities.invokeLater { refreshAll() }
                    return
                }
                if (mode != 0) {
                    mode = 0
                    press = null; start = null
                    stopMoveTicker()
                    glass.cursor = Cursor.getDefaultCursor()
                    SwingUtilities.invokeLater { refreshAll() }
                } else {
                    val t = redispatchTarget(e)
                    redispatch(e, t)
                    if (t != null && t === pressTarget) fireClicked(e, t)
                    pressTarget = null
                }
            }
            override fun mouseExited(e: MouseEvent) {
                if (mode == 0) {
                    glass.cursor = Cursor.getDefaultCursor()
                    clearHover(e.getWhen())
                }
            }
        })
        // 滚轮重派发：glassPane 不转发 MOUSE_WHEEL，右侧内容区、表格、日志框都滚不动
        glass.addMouseWheelListener { e ->
            if (mode == 0) redispatch(e, redispatchTarget(e))
        }
        glass.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                // ---- 状态自愈：release 事件丢失（拖快后指针跑到窗口外、焦点被抢、系统吞事件）
                // 会让 titleDrag / mode 残留 true，后续每次按下都被当成「继续拖/继续缩放」，
                // 表现正是「拖两下就再也拖不动」。按键已抬起就复位。
                if (e.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK == 0) {
                    if (titleDrag) {
                        titleDrag = false
                        pendingEdge = 0
                        stopMoveTicker()
                    }
                    if (mode != 0) {
                        mode = 0
                        press = null; start = null
                        stopMoveTicker()
                        glass.cursor = Cursor.getDefaultCursor()
                    }
                }
                if (mode == 0) {
                    // 同一像素内的重复 move 直接丢：Windows 高 DPI（1.25x/1.5x）下
                    // 一次物理移动会派生多个逻辑坐标相同的 MOUSE_MOVED，每个都要做
                    // 深度命中测试 + 事件重派发，是「主界面很卡」的隐形大头。
                    if (e.x == lastMoveX && e.y == lastMoveY) return
                    lastMoveX = e.x; lastMoveY = e.y
                    glass.cursor = cursorFor(modeOf(e.x, e.y))
                    // 悬停特效 + tooltip 依赖底层 move/entered/exited，glassPane 截断后必须自己补。
                    // 命中只算一次，hover 与重派发共用同一个目标。
                    val t = redispatchTarget(e)
                    updateHover(e, t)
                    redispatch(e, t)
                }
            }
            override fun mouseDragged(e: MouseEvent) {
                // 同一套自愈：拖拽事件里按键状态正常为按下；若已抬起说明 release 丢了
                if (e.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK == 0) {
                    titleDrag = false; pendingEdge = 0; mode = 0
                    press = null; start = null
                    stopMoveTicker()
                    return
                }
                if (titleDrag) {
                    val grip = titleGrip ?: return
                    val rb = titleBase ?: return
                    // 真实光标位置（不随窗口移动而变），断开「窗口追光标」的反馈环
                    val cur = pointerLocation(Point(e.xOnScreen, e.yOnScreen))
                    val edge = snapEdgeOf(cur)
                    if (edge != 0) {
                        // 进入 snap 预览区：停住等松手
                        pendingEdge = edge
                        return
                    }
                    pendingEdge = 0
                    if (frame.extendedState and JFrame.MAXIMIZED_BOTH != 0) {
                        // 最大化窗口拖动：先还原 NORMAL，窗口「粘」在光标上。
                        // 这是唯一必须即时改尺寸的一次性动作（之后才能节流位置）。
                        frame.extendedState = JFrame.NORMAL
                        val ratio = grip.x.toFloat() / rb.width.coerceAtLeast(1)
                        val w = rb.width
                        val restoredX = cur.x - (w * ratio).toInt()
                        titleBase = Rectangle(restoredX, cur.y - grip.y, w, rb.height)
                        frame.bounds = titleBase
                        titleGrip = Point(cur.x - restoredX, grip.y)
                        return
                    }
                    // 只登记目标位置，由 16ms 帧合并真正落位（见 pushBounds 注释）
                    pushBounds(Rectangle(cur.x - grip.x, cur.y - grip.y, rb.width, rb.height))
                    return
                }
                if (mode == 0) { redispatch(e, redispatchTarget(e)); return }
                val p0 = press ?: return
                val cur = pointerLocation(Point(e.xOnScreen, e.yOnScreen))
                resizeRect(cur.x - p0.x, cur.y - p0.y)?.let { pushBounds(it) }
            }
        })
    }

    /** 主题切换或首次构建：整棵界面树重建，避免逐个组件改色漏项。 */
    private fun rebuildContent(): JPanel {
        val shell = JPanel(BorderLayout())
        shell.isOpaque = false
        val bar = buildTitleBar()
        titleBarPanel = bar
        shell.add(bar, BorderLayout.NORTH)
        val body = JPanel(BorderLayout())
        body.isOpaque = false
        body.add(buildSidebar(), BorderLayout.WEST)
        body.add(buildPages(), BorderLayout.CENTER)
        shell.add(body, BorderLayout.CENTER)
        return shell
    }

    private fun rebuildAll() {
        timer?.stop()
        navItems.clear()
        pages.removeAll()
        creditButtons.clear()
        creditStatus = null
        providerTable = null
        accountTable = null
        modelTable = null
        logArea = null
        logRenderedSeqs = emptyList()
        activitySignature = ""
        root.removeAll()
        root.add(rebuildContent(), BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
        selectPage(currentPage)
        refreshAll()
        timer = Timer(2000) { refreshAll() }.apply { start() }
    }

    private fun buildTitleBar(): JPanel {
        val bar = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = K.p.top
            border = BorderFactory.createEmptyBorder(10, 16, 10, 10)
        }
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply { isOpaque = false }
        val dot = JLabel(DotIcon(K.p.success, 9)).apply { foreground = K.p.text }
        statusDot = dot
        statusText = JLabel("启动中").apply {
            font = kFont(12, Font.BOLD)
            foreground = K.p.text2
        }
        left.add(dot)
        left.add(JLabel("AI Gateway").apply {
            font = kFont(14, Font.BOLD)
            foreground = K.p.text
        })
        left.add(JLabel("Windows · v$APP_VERSION").apply {
            font = kFont(12)
            foreground = K.p.text2
        })
        left.add(statusText)
        bar.add(left, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false }
        right.add(JLabel("凭证已加密").apply {
            font = kFont(11)
            foreground = K.p.text3
        })
        // 主题徽标点击切换（设计稿里是一个「浅色/暗黑」小胶囊）
        right.add(object : KPill(if (K.p === KLIGHT) "浅色" else "暗黑", K.p.brandText, K.p.brandLight) {
            init {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "切换浅色 / 暗黑"
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = toggleTheme()
                })
            }
        })
        right.add(WindowButton(WindowButton.Kind.MIN))
        right.add(WindowButton(WindowButton.Kind.MAX))
        right.add(WindowButton(WindowButton.Kind.CLOSE))
        bar.add(right, BorderLayout.EAST)

        return bar
    }

    private fun buildSidebar(): JPanel {
        val side = object : KRounded(K.p.plate, null, 0) {
            init {
                // GridBagLayout 而不是 BoxLayout：实测 BoxLayout Y 轴对本场景子组件的
                // 宽度/alignmentX 分配不可靠（纯 JLabel 与 NavItem 同构却表现不同，
                // 分组标题被挤到水平中点）。GridBag 全部子组件 fill=HORIZONTAL + weightx=1，
                // 宽度行为完全确定，所有条目左缘天然对齐。
                layout = java.awt.GridBagLayout()
                border = BorderFactory.createEmptyBorder(14, 12, 14, 12)
                preferredSize = Dimension(200, 0)
            }
        }
        val gbc = java.awt.GridBagConstraints().apply {
            gridx = 0
            fill = java.awt.GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }
        fun addRow(c: Component) {
            side.add(c, gbc.clone() as java.awt.GridBagConstraints)
        }
        addRow(sectionLabel("工作台"))
        addRow(nav("overview", "概览"))
        addRow(nav("providers", "供应商"))
        addRow(nav("accounts", "账号"))
        addRow(nav("models", "模型目录"))
        addRow(gap(14))
        addRow(sectionLabel("配置"))
        addRow(nav("gateway", "网关设置"))
        addRow(nav("smartRoute", "智能路由"))
        addRow(nav("keys", "客户端密钥"))
        addRow(nav("advanced", "高级设置"))
        addRow(nav("logs", "运行日志"))
        val glue = java.awt.GridBagConstraints().apply {
            gridx = 0
            gridy = java.awt.GridBagConstraints.RELATIVE
            fill = java.awt.GridBagConstraints.BOTH
            weightx = 1.0
            weighty = 1.0
        }
        side.add(JPanel().apply { isOpaque = false }, glue)
        return side
    }

    /** 分组标题：比导航项小一号的次要色粗体，左缘与导航文字对齐。 */
    private fun sectionLabel(text: String) = JPanel(java.awt.BorderLayout()).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(7, 10, 7, 10)
        add(JLabel(text).apply {
            font = kFont(12, Font.BOLD)
            foreground = K.p.text2
            // 标签会被 GridBag 拉到全宽，必须显式左对齐文字
            horizontalAlignment = javax.swing.SwingConstants.LEFT
        }, java.awt.BorderLayout.CENTER)
    }

    private fun gap(h: Int) = JPanel().apply {
        isOpaque = false
        preferredSize = Dimension(1, h)
        maximumSize = Dimension(Int.MAX_VALUE, h)
    }

    private fun nav(key: String, text: String): NavItem = NavItem(key, text).also { item ->
        item.onClick = { selectPage(key) }
        navItems[key] = item
    }

    private fun buildPages(): JPanel {
        pages.isOpaque = false
        pages.layout = CardLayout(0, 0)
        fun add(key: String, comp: Component) {
            pages.add(comp, key)
            pageCards[key] = comp
        }
        add("overview", page("overview", "概览", "本地网关 · 127.0.0.1:8790", overviewPage(), scroll = true))
        add("providers", page("providers", "供应商", "登录、导入与额度刷新", providerPage()))
        add("accounts", page("accounts", "账号", "启停、删除与额度明细", accountPage()))
        add("models", page("models", "模型目录", "上游模型清单（按供应商前缀路由）", modelPage()))
        add("gateway", page("gateway", "网关设置", "端口、API Key 与局域网监听", gatewayPage()))
        add("smartRoute", page("smartRoute", "智能路由", "手动维护 model=\"auto\" 的候选模型与顺序", smartRoutePage(), scroll = true))
        add("keys", page("keys", "客户端密钥", "白名单与限额，与上游凭证分离", keysPage()))
        add("advanced", page("advanced", "高级设置", "引擎全部设置项的 JSON 直改", advancedPage()))
        add("logs", page("logs", "运行日志", "最近 300 条网关事件", logPage()))
        return pages
    }

    /** 递归标记容器树失效：pages.revalidate() 只影响祖先，隐藏卡里的
     *  JScrollPane/viewport 不会被标记，导致首次切页仍用启动时的旧宽度布局。 */
    private fun revalidateDeep(c: java.awt.Container) {
        for (i in 0 until c.componentCount) {
            val ch = c.getComponent(i)
            if (ch is java.awt.Container) revalidateDeep(ch)
        }
        if (c is JScrollPane) c.viewport.revalidate()
        c.revalidate()
    }

    private fun selectPage(key: String) {
        currentPage = key
        (pages.layout as CardLayout).show(pages, key)
        (pageCards[key] as? java.awt.Container)?.let { revalidateDeep(it) }
        pages.repaint()
        navItems.forEach { (k, item) -> item.setActive(k == key) }
        // 日志页平时被 drainLogs 跳过（省 EDT），切回来时强制重建一次文档
        if (key == "logs") {
            logRenderedSeqs = emptyList()
            logArea?.text = ""
        }
        // 表格只在可见页同步，切页必须强制刷一次把该页数据补齐
        refreshAll(force = true)
        when (key) {
            "overview" -> pageSubtitle?.text = "本地网关 · ${engine.localUrl()}"
            "providers" -> pageSubtitle?.text = "登录、导入与额度刷新"
            "accounts" -> pageSubtitle?.text = "启停、删除与额度明细"
            "models" -> pageSubtitle?.text = "上游模型清单（按供应商前缀路由）"
            "gateway" -> pageSubtitle?.text = "端口、API Key 与局域网监听"
            "smartRoute" -> { pageSubtitle?.text = "手动维护 model=\"auto\" 的候选模型与顺序"; reloadSmartRoute() }
            "keys" -> pageSubtitle?.text = "白名单与限额，与上游凭证分离"
            "advanced" -> pageSubtitle?.text = "引擎全部设置项的 JSON 直改"
            "logs" -> pageSubtitle?.text = "最近 300 条网关事件"
        }
    }

    /** 页面容器：顶部标题行（标题 + 副标题 + 状态胶囊 + 主操作），下方内容区。 */
    private fun page(
        key: String,
        title: String,
        subtitle: String,
        content: Component,
        scroll: Boolean = false,
    ): JPanel {
        val wrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            background = K.p.bg
            border = BorderFactory.createEmptyBorder(18, 24, 18, 24)
        }
        val header = JPanel(BorderLayout()).apply { isOpaque = false }
        val titles = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel(title).apply {
                font = kFont(18, Font.BOLD)
                foreground = K.p.text
            })
            add(JLabel(subtitle).apply {
                font = kFont(12)
                foreground = K.p.text2
                border = BorderFactory.createEmptyBorder(3, 0, 0, 0)
            })
        }
        header.add(titles, BorderLayout.WEST)
        if (key == "overview") {
            pageTitle = titles.getComponent(0) as JLabel
            pageSubtitle = titles.getComponent(1) as JLabel
            val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 6)).apply { isOpaque = false }
            actions.add(KPill("运行中").also { pill = it })
            actions.add(KButton("停止网关", KButton.Kind.SECONDARY).also { b ->
                runButton = b
                b.addActionListener { if (engine.isRunning()) stopGateway() else startGateway() }
            })
            header.add(actions, BorderLayout.EAST)
        }
        wrap.add(header, BorderLayout.NORTH)
        val body = if (scroll) {
            JScrollPane(content).apply {
                border = BorderFactory.createEmptyBorder()
                isOpaque = false
                viewport.isOpaque = false
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                verticalScrollBar.unitIncrement = 18
                kStyleScrollBars()
                border = BorderFactory.createEmptyBorder(12, 0, 0, 0)
            }
        } else {
            (content as? JPanel)?.border = BorderFactory.createEmptyBorder(12, 0, 0, 0)
            content
        }
        wrap.add(body, BorderLayout.CENTER)
        return wrap
    }

    // ------------------------------------------------------------ 概览页

    private fun overviewPage(): JPanel {
        // kScrollColumn：viewport 强制列宽=实际宽度，任何窗口宽度都不再横向裁切
        val col = KScrollColumn()

        // 指标网格 2×3。用自实现的 EqualColumnsLayout 而不是 GridLayout / GridBagLayout：
        // 前者的容器 preferred 宽度 = 子项 preferred 之和（3 卡被算成 1284px 直接溢出视口），
        // 后者的 weightx 在本页的多层 GridBag 嵌套里也没能均分。等分布局只按**可用宽度**切分，
        // 任何窗口宽度都严格 n 列等宽、永不溢出。
        val cardsRow1 = JPanel(EqualColumnsLayout(3, 12)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, MetricCard.CARD_HEIGHT)
        }
        val cardsRow2 = JPanel(EqualColumnsLayout(3, 12)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, MetricCard.CARD_HEIGHT)
        }
        metricAccounts = MetricCard("账号就绪", "0").also { cardsRow1.add(it) }
        metricRequests = MetricCard("今日请求", "0").also { cardsRow1.add(it) }
        metricTokens = MetricCard("今日 tokens", "0").also { cardsRow1.add(it) }
        metricModels = MetricCard("模型数", "—").also { cardsRow2.add(it) }
        metricCredits = MetricCard("剩余额度", "—").also { cardsRow2.add(it) }
        metricSuccess = MetricCard("成功率", "—").also { cardsRow2.add(it) }
        col.row(cardsRow1)
        col.row(gap(10))
        col.row(cardsRow2)
        col.row(gap(14))
        col.row(baseUrlBar())
        col.row(gap(14))

        // 实时调度拓扑 + 上下文容量分布：并排
        topologyCanvas = TopologyCanvas()
        val topoHint = JLabel("按近 20 次调用分布").apply {
            font = kFont(12)
            foreground = K.p.text2
        }
        capacityBar = ContextCapacityBar()
        val capHint = JLabel("按各家模型数占比").apply {
            font = kFont(12)
            foreground = K.p.text2
        }
        val viz = JPanel(EqualColumnsLayout(2, 12)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 236)
            preferredSize = Dimension(100, 236)
        }
        viz.add(cardRow("实时调度拓扑", topoHint, topologyCanvas!!, 0))
        viz.add(cardRow("上下文容量分布", capHint, capacityBar!!, 0))
        col.row(viz)
        col.row(gap(14))

        // 消耗趋势 + 最近调用：左右并排（与上方拓扑/容量的分配口径一致）
        trendChart = TrendChart()
        val trendHint = JLabel("近 14 天").apply { font = kFont(12); foreground = K.p.text2 }
        activityHint = JLabel("暂无调用").apply { font = kFont(12); foreground = K.p.text2 }
        activityBox = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        val bottomRow = JPanel(EqualColumnsLayout(2, 12)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 236)
            preferredSize = Dimension(100, 236)
        }
        bottomRow.add(cardRow("消耗趋势", trendHint, trendChart!!, 0))
        bottomRow.add(cardRow("最近调用", activityHint!!, activityBox!!, 0))
        col.row(bottomRow)
        col.row(gap(14))
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 34)
            add(KButton("登录供应商", KButton.Kind.PRIMARY).apply {
                addActionListener { selectPage("providers") }
            })
            add(KButton("拉取模型目录", KButton.Kind.SECONDARY).apply { addActionListener { loadModels() } })
            add(KButton("刷新额度", KButton.Kind.SECONDARY).apply {
                creditButtons += this
                addActionListener { refreshCredits() }
            })
            creditStatus = JLabel("").apply {
                font = kFont(11)
                foreground = K.p.text2
                border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
            }
            add(creditStatus)
        }
        col.row(actions)
        col.stretch()
        return col
    }

    private fun baseUrlBar(): JPanel {
        val bar = object : KRounded(K.p.top, K.p.line, 6) {
            init {
                layout = BorderLayout()
                border = BorderFactory.createEmptyBorder(8, 14, 8, 10)
                maximumSize = Dimension(Int.MAX_VALUE, 46)
            }
        }
        bar.add(JLabel("接入地址").apply {
            font = kFont(12)
            foreground = K.p.text2
        }, BorderLayout.WEST)
        baseUrlValue = JLabel(engine.localUrl()).apply {
            font = kMono(13)
            foreground = K.p.text
            border = BorderFactory.createEmptyBorder(0, 14, 0, 0)
        }
        bar.add(baseUrlValue, BorderLayout.CENTER)
        bar.add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(KButton("复制", KButton.Kind.SECONDARY).apply {
                preferredSize = Dimension(84, 28)
                addActionListener {
                    copyToClipboard(engine.localUrl())
                    showKMessage(frame, "已复制：${engine.localUrl()}", "AI Gateway")
                }
            })
        }, BorderLayout.EAST)
        return bar
    }

    /**
     * 后台按目录默认序生成 Auto 初始候选链（仅在链为空时由启动流程调用）。
     *
     * 概览页的 Auto 卡片已移除（开关与候选链在「配置 → 智能路由」页维护），
     * 因此这里不再回写任何 UI 控件，只在失败时记日志。
     */
    private fun rebuildAutoChain() {
        Thread {
            runCatching { autoRoute.rebuild(forceRefresh = false) }
                .onFailure { engine.requestLog.warn("重建 Auto 候选链失败：${it.message ?: "未知错误"}") }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun card(title: String, hint: String, body: Component): JPanel =
        cardRow(title, JLabel(hint).apply { font = kFont(12); foreground = K.p.text2 }, body, 0)

    private fun cardRow(title: String, hint: JLabel, body: Component, fixedHeight: Int): JPanel {
        val card = object : KRounded(K.p.top, K.p.line, 8) {
            init {
                layout = BorderLayout()
                if (fixedHeight > 0) {
                    maximumSize = Dimension(Int.MAX_VALUE, fixedHeight)
                    preferredSize = Dimension(100, fixedHeight)
                } else {
                    maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
                }
            }
        }
        val head = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(11, 16, 9, 16)
            add(JLabel(title).apply {
                font = kFont(13, Font.BOLD)
                foreground = K.p.text
            }, BorderLayout.WEST)
            add(hint, BorderLayout.EAST)
        }
        card.add(head, BorderLayout.NORTH)
        card.add(body, BorderLayout.CENTER)
        return card
    }

    private fun divider(): JPanel = object : JPanel() {
        init {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 1)
            preferredSize = Dimension(1, 1)
        }

        override fun paintComponent(g: Graphics) {
            g.color = K.p.lineLight
            g.drawLine(16, 0, width - 16, 0)
        }
    }.also { it.preferredSize = Dimension(1, 1) }



    // ------------------------------------------------------------ 表格页

    private fun styledTable(model: DefaultTableModel): JTable = JTable(model).apply {
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
                border = BorderFactory.createEmptyBorder(0, 12, 0, 12)
                c.font = kFont(12)
                if (isSelected) {
                    c.background = K.p.brandLight
                    c.foreground = K.p.brandText
                } else {
                    c.background = K.p.top
                    c.foreground = K.p.text
                }
                c.isOpaque = true
                return c
            }
        })
    }

    private fun tablePage(table: JTable, buttons: JPanel): JPanel {
        val p = JPanel(BorderLayout(0, 10)).apply { isOpaque = false }
        p.add(object : KRounded(K.p.top, K.p.line, 8) {
            init {
                layout = BorderLayout()
                border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
                add(JScrollPane(table).apply {
                    border = BorderFactory.createEmptyBorder()
                    viewport.background = K.p.top
                    isOpaque = false
                    viewport.isOpaque = true
                    kStyleScrollBars()
                }, BorderLayout.CENTER)
            }
        }, BorderLayout.CENTER)
        buttons.isOpaque = false
        p.add(buttons, BorderLayout.SOUTH)
        return p
    }

    private fun actionBar(vararg pairs: Pair<String, () -> Unit>): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            pairs.forEach { (label, action) ->
                add(KButton(label).apply {
                    addActionListener { action() }
                    if (label == "刷新额度") creditButtons += this
                })
            }
        }

    private fun providerPage(): JPanel {
        providerTable = styledTable(providerModel)
        val actions = actionBar(
            "登录选中供应商" to { loginSelected() },
            "粘贴凭证导入" to { importSelected() },
            "刷新额度" to { refreshCredits() },
            "刷新" to { refreshAll(force = true) },
        )
        return tablePage(providerTable!!, actions)
    }

    private fun accountPage(): JPanel {
        accountTable = styledTable(accountModel)
        val actions = actionBar(
            "启用 / 停用" to { toggleAccount() },
            "上移优先级" to { moveAccount(up = true) },
            "下移优先级" to { moveAccount(up = false) },
            "一键签到" to { checkinAll() },
            "删除账号" to { deleteAccount() },
            "刷新额度" to { refreshCredits() },
            "刷新" to { refreshAll(force = true) },
        )
        return tablePage(accountTable!!, actions)
    }

    /**
     * 「一键签到」：对**全部**支持签到的账号批量执行（不是选中谁签谁）。
     *
     * 直接复用 core 的 [dev.aigw.core.gateway.AutoCheckin.tick] 并 force=true：
     * 绕过「今天已跑过」与退避，但仍不绕过防重入（真有并发时只跑一次）。
     * 上游对已签到的账号回「今日已签到」（成功），不会重复领取。
     */
    private fun checkinAll() {
        Thread {
            val text = try {
                val report = engine.autoCheckin.tick(force = true)
                buildString {
                    append(report.summary())
                    if (report.messages.isNotEmpty()) {
                        append("\n\n")
                        append(report.messages.joinToString("\n"))
                    }
                }
            } catch (e: Exception) {
                "批量签到失败：${e.message}"
            }
            SwingUtilities.invokeLater {
                refreshAll()
                showKMessage(frame, text, "一键签到", error = text.contains("失败") || text.contains("异常"))
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 每日自动签到：固定每天 [AUTO_CHECKIN_HOUR] 点批量跑一次。
     *
     * 用「算到下一个 10:00 的延时」单发 Timer，触发后重排下一次——中间不唤醒，
     * 不做分钟级轮询（此前 60 秒一拍是纯浪费）。
     * 应用没在 10:00 运行时，启动时补一次：已过 10 点且今天还没成功签过就立即跑。
     */
    private fun scheduleDailyCheckin() {
        val cal = java.util.Calendar.getInstance()
        if (cal.get(java.util.Calendar.HOUR_OF_DAY) >= AUTO_CHECKIN_HOUR && !engine.autoCheckin.doneToday()) {
            Thread { runCatching { engine.autoCheckin.tick() }; SwingUtilities.invokeLater { refreshAll() } }
                .apply { isDaemon = true }.start()
        }
        armNextDailyCheckin()
    }

    /** 排下一次 10:00（单发 Timer，触发后自己排下一发）。 */
    private fun armNextDailyCheckin() {
        autoCheckinTimer?.stop()
        // Swing Timer 的 delay 是 Int 毫秒（≈24.8 天上限）；一天的延时（≤86_400_000）安全，
        // 但仍夹到 Int.MAX_VALUE 以防系统时间被改到很远的过去导致溢出为负
        val delay = millisUntilNextCheckin().coerceIn(1_000L, Int.MAX_VALUE.toLong()).toInt()
        autoCheckinTimer = Timer(delay) {
            Thread {
                runCatching { engine.autoCheckin.tick() }
                SwingUtilities.invokeLater {
                    refreshAll()
                    armNextDailyCheckin() // 跑完立刻排明天的 10:00
                }
            }.apply { isDaemon = true }.start()
        }.apply {
            isRepeats = false
            start()
        }
    }

    /** 距下一个 [AUTO_CHECKIN_HOUR]:00 的毫秒数（今天已过则算明天）。 */
    private fun millisUntilNextCheckin(): Long {
        val next = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, AUTO_CHECKIN_HOUR)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val now = System.currentTimeMillis()
        if (next.timeInMillis <= now) next.add(java.util.Calendar.DAY_OF_YEAR, 1)
        // Swing Timer 的 delay 是 Int 毫秒（上限约 24.8 天），一天的延时安全
        return next.timeInMillis - now
    }

    private fun modelPage(): JPanel {
        modelTable = styledTable(modelModel)
        val actions = actionBar(
            "拉取模型目录" to { loadModels() },
            "复制选中模型 ID" to {
                val row = modelTable?.selectedRow ?: -1
                if (row >= 0) copyToClipboard(modelModel.getValueAt(row, 0).toString())
            },
        )
        return tablePage(modelTable!!, actions)
    }

    private fun gatewayPage(): JPanel {
        val wrap = JPanel(BorderLayout()).apply { isOpaque = false }
        val card = object : KRounded(K.p.top, K.p.line, 8) {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createEmptyBorder(16, 18, 16, 18)
                alignmentX = Component.LEFT_ALIGNMENT
            }
        }
        val s = engine.settings()
        val portField = KTextField(s.port.toString(), 8)
        val apiKeyField = KTextField(s.apiKey, 26)
        val noKeySwitch = KSwitch("允许无 Key 调用（仅本机）").apply {
            isSelected = s.allowNoKey
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val lanSwitch = KSwitch("同时监听局域网（其他设备可用局域网地址接入）").apply {
            isSelected = s.exposeLan
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val status = JLabel("  ").apply { font = kFont(12); foreground = K.p.text2; alignmentX = Component.LEFT_ALIGNMENT }

        fun fieldRow(label: String, field: JTextField): JPanel = JPanel(BorderLayout(12, 4)).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 56)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel(label).apply { font = kFont(12); foreground = K.p.text2 }, BorderLayout.NORTH)
            add(field, BorderLayout.CENTER)
        }

        card.add(fieldRow("监听端口", portField))
        card.add(gap(8))
        card.add(fieldRow("API Key（关闭「允许无 Key」时生效）", apiKeyField))
        card.add(gap(10))
        card.add(noKeySwitch)
        card.add(gap(6))
        card.add(lanSwitch)
        card.add(gap(14))
        card.add(actionBar("保存并重启网关" to {
            val port = portField.text.trim().toIntOrNull()
            if (port == null || port !in 1..65535) {
                showKMessage(frame, "端口不合法", "AI Gateway", error = true)
            } else {
                val wasRunning = engine.isRunning()
                if (wasRunning) engine.stop()
                engine.updateSettings(
                    engine.settings().copy(
                        port = port,
                        apiKey = apiKeyField.text.trim(),
                        allowNoKey = noKeySwitch.isSelected,
                        exposeLan = lanSwitch.isSelected,
                    )
                )
                if (wasRunning) startGateway()
                status.text = "已保存，网关已按新配置重启"
                refreshAll()
            }
        }, "恢复当前配置" to {
            val cur = engine.settings()
            portField.text = cur.port.toString()
            apiKeyField.text = cur.apiKey
            noKeySwitch.isSelected = cur.allowNoKey
            lanSwitch.isSelected = cur.exposeLan
            status.text = "已恢复磁盘上的配置"
        }).apply { alignmentX = Component.LEFT_ALIGNMENT })
        card.add(gap(10))
        card.add(status)
        wrap.add(card, BorderLayout.NORTH)
        val hint = JLabel("局域网地址：" + (engine.lanUrls().joinToString("  ") .ifEmpty { "未开启" })).apply {
            font = kFont(12)
            foreground = K.p.text2
            border = BorderFactory.createEmptyBorder(12, 2, 0, 0)
        }
        wrap.add(hint, BorderLayout.CENTER)
        return wrap
    }

    // ------------------------------------------------------------ 智能路由页

    private fun smartRoutePage(): JPanel {
        val col = KScrollColumn()
        // 顶部控制条
        val statePill = KPill("已关闭", K.p.text2, K.p.plate).apply { preferredSize = Dimension(72, 22) }
        smartStatePill = statePill
        val hint = kWrapArea(56)
        smartHint = hint
        val top = object : KRounded(K.p.top, K.p.line, 8) {
            init {
                layout = java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 10, 8)
                alignmentX = Component.LEFT_ALIGNMENT
                // 高度不锁死：窄窗口 FlowLayout 换行成两行也不能被裁
                maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            }
        }
        top.add(KSwitch("启用 Auto").apply {
            isSelected = autoRoute.enabled()
            addItemListener {
                autoRoute.setEnabled(isSelected)
                reloadSmartRoute()
            }
        })
        top.add(statePill)
        top.add(KButton("刷新列表", KButton.Kind.SECONDARY).apply { addActionListener { reloadSmartRoute() } })
        col.row(top)
        col.row(gap(10))
        col.row(hint)
        col.row(gap(8))

        // 候选链卡片（左）与可用模型池卡片（右）左右分栏：一屏多显内容。
        // 窄窗口（拖到屏幕半屏）适配：表格列宽显式 + autoResize OFF 保持表宽，
        // 视口不够时走横向滚动而不是把表头/列压烂；split 两侧给足最小宽度。
        chainListTable = JTable(chainListModel).apply {
            applyKTable()
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION)
            kFixColumnWidths(48, 118, 210)
            minimumSize = Dimension(80, 80)
        }
        poolListTable = JTable(poolListModel).apply {
            applyKTable()
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION)
            kFixColumnWidths(118, 210)
            minimumSize = Dimension(80, 80)
            // 双击模型池行 = 直接加入候选链，免点按钮
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) addFromPool()
                }
            })
        }
        val chainCard = card(
            "候选链（自上而下依次故障转移）",
            "顺序即优先级，改动即时生效",
            JPanel(java.awt.BorderLayout()).apply {
                isOpaque = false
                add(JScrollPane(chainListTable).apply {
                    border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
                    viewport.background = K.p.top
                    kStyleScrollBars()
                    preferredSize = Dimension(100, 190)
                }, java.awt.BorderLayout.CENTER)
                add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 6)).apply {
                    isOpaque = false
                    add(KButton("上移").apply { addActionListener { moveChain(-1) } })
                    add(KButton("下移").apply { addActionListener { moveChain(1) } })
                    add(KButton("移除").apply { addActionListener { removeChain() } })
                    add(KButton("清空").apply { addActionListener { clearChain() } })
                }, java.awt.BorderLayout.SOUTH)
            },
        )
        val poolCard = card(
            "可用模型池（已登录供应商）",
            "选中后点「加入候选链」或直接双击行",
            JPanel(java.awt.BorderLayout()).apply {
                isOpaque = false
                add(JScrollPane(poolListTable).apply {
                    border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
                    viewport.background = K.p.top
                    kStyleScrollBars()
                    preferredSize = Dimension(100, 190)
                }, java.awt.BorderLayout.CENTER)
                add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 6)).apply {
                    isOpaque = false
                    add(KButton("加入候选链", KButton.Kind.PRIMARY).apply { addActionListener { addFromPool() } })
                }, java.awt.BorderLayout.SOUTH)
            },
        )
        // 候选链卡片（左）与可用模型池卡片（右）左右分栏：一屏多显内容。
        // 用 GridBagLayout 的 weightx 分配而不是 JSplitPane：JSplitPane 的 divider 在窗口
        // 变窄时不重算（实测拖到左半屏会把右栏挤成窄条甚至挤没），weightx 分配是
        // 确定性的——窄窗口两栏都在，表格自身横向滚动兜底内容。分隔条可拖拽调两栏比例。
        val columns = JPanel(java.awt.GridBagLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 520)
            preferredSize = Dimension(100, 430)
        }
        val leftC = java.awt.GridBagConstraints().apply {
            gridx = 0; gridy = 0
            fill = java.awt.GridBagConstraints.BOTH
            weightx = 0.5; weighty = 1.0
        }
        val barC = java.awt.GridBagConstraints().apply {
            gridx = 1; gridy = 0
            fill = java.awt.GridBagConstraints.BOTH
            weightx = 0.0; weighty = 1.0
        }
        val rightC = java.awt.GridBagConstraints().apply {
            gridx = 2; gridy = 0
            fill = java.awt.GridBagConstraints.BOTH
            weightx = 0.5; weighty = 1.0
        }
        val dividerBar = JPanel().apply {
            isOpaque = false
            preferredSize = Dimension(12, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
            var startX = 0
            var startLeft = 0
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    startX = e.xOnScreen
                    startLeft = chainCard.width
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseDragged(e: MouseEvent) {
                    val total = columns.width - 12
                    if (total <= 0) return
                    val target = (startLeft + (e.xOnScreen - startX)).coerceIn(150, total - 150)
                    val w = target.toDouble() / total
                    leftC.weightx = w
                    rightC.weightx = 1.0 - w
                    (columns.layout as java.awt.GridBagLayout).setConstraints(chainCard, leftC)
                    (columns.layout as java.awt.GridBagLayout).setConstraints(poolCard, rightC)
                    columns.revalidate()
                    columns.repaint()
                }
            })
        }
        columns.add(chainCard, leftC)
        columns.add(dividerBar, barC)
        columns.add(poolCard, rightC)
        col.row(columns)
        col.stretch()
        return col
    }

    /** 从引擎重载候选链与模型池（页面选择 / 显式刷新时调用）。链路读内存立即刷新；
     *  模型池可能打上游拉目录，放后台线程。 */
    private fun reloadSmartRoute() {
        val group = engine.routingGroupSettings().list().firstOrNull { it.id == "auto" }
        chainWorking = group?.members?.toMutableList() ?: mutableListOf()
        chainListModel.rowCount = 0
        chainWorking.forEachIndexed { i, m ->
            chainListModel.addRow(arrayOf("${i + 1}", m.providerId, m.model))
        }
        val on = autoRoute.groupEnabled()
        smartStatePill?.let {
            if (on) { it.text = "运行中"; it.setStyle(K.p.success, K.p.successBg) }
            else { it.text = "已关闭"; it.setStyle(K.p.text2, K.p.plate) }
        }
        smartHint?.text = when {
            !on -> "Auto 已停用：候选链仍保留，启用后按此顺序路由"
            chainWorking.isEmpty() -> "候选链为空：从右侧模型池选中一行，点「加入候选链」或双击行添加"
            else -> "客户端请求 model=\"auto\" 时按此顺序调用，首选失败自动切换下一候选；用「上移/下移」调整优先级"
        }
        smartHint?.revalidate()
        // 模型池：只列已启用且有可用账号的供应商；poolRows 记基础 providerId（区域前缀不是注册表 id）
        Thread {
            val usable = engine.providers().filter { it.enabled && it.usableCount > 0 }.map { it.id }.toSet()
            val rows = runCatching { engine.models() }.getOrDefault(emptyList())
                .filter { it.providerId in usable }
                .map { it.providerId to it.model.id }
            SwingUtilities.invokeLater {
                poolListModel.rowCount = 0
                poolRows.clear()
                rows.forEach { (pid, mid) ->
                    val name = runCatching { engine.providers().firstOrNull { p -> p.id == pid }?.displayName }.getOrNull() ?: pid
                    poolListModel.addRow(arrayOf(name, mid))
                    poolRows.add(pid to mid)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /** 把工作副本写回 auto 路由组：列表顺序 → 优先级严格递减，保证故障转移按用户所见顺序。 */
    private fun saveChain() {
        val ordered = chainWorking.mapIndexed { i, m -> m.copy(priority = chainWorking.size - i, weight = 1) }
        val existing = engine.routingGroupSettings().list().firstOrNull { it.id == "auto" }
        val group = RoutingGroup(
            id = "auto",
            name = existing?.name ?: "Auto 智能路由",
            enabled = existing?.enabled ?: true,
            members = ordered,
        )
        engine.updateRoutingGroups(engine.routingGroupSettings().upsert(group))
        chainListModel.rowCount = 0
        chainWorking.forEachIndexed { i, m ->
            chainListModel.addRow(arrayOf("${i + 1}", m.providerId, m.model))
        }
        // 即时反馈：链路改动立即同步提示与概览卡，不需要手动点刷新
        smartHint?.text = when {
            chainWorking.isEmpty() -> "候选链为空：从右侧模型池选中一行，点「加入候选链」或双击行添加"
            else -> "已生效：model=\"auto\" 按当前 ${chainWorking.size} 个候选的顺序调用，首选失败自动切换"
        }
        smartHint?.revalidate()
    }

    private fun selectedChainRow(): Int = chainListTable?.selectedRow ?: -1

    private fun moveChain(delta: Int) {
        val row = selectedChainRow()
        if (row < 0) { showKMessage(frame, "先选中一行", "AI Gateway"); return }
        val to = row + delta
        if (to < 0 || to >= chainWorking.size) return
        val item = chainWorking.removeAt(row)
        chainWorking.add(to, item)
        saveChain()
        chainListTable?.setRowSelectionInterval(to, to)
    }

    private fun removeChain() {
        val row = selectedChainRow()
        if (row < 0) { showKMessage(frame, "先选中一行", "AI Gateway"); return }
        chainWorking.removeAt(row)
        saveChain()
    }

    private fun clearChain() {
        if (chainWorking.isEmpty()) return
        if (!showKConfirm(frame, "清空全部 ${chainWorking.size} 个候选？")) return
        chainWorking.clear()
        saveChain()
    }

    private fun addFromPool() {
        val row = poolListTable?.selectedRow ?: -1
        if (row < 0) { showKMessage(frame, "先在模型池选中一行", "AI Gateway"); return }
        if (row >= poolRows.size) return
        val (providerId, model) = poolRows[row]
        if (chainWorking.any { it.providerId == providerId && it.model == model }) {
            showKMessage(frame, "$providerId/$model 已在候选链里", "AI Gateway")
            return
        }
        chainWorking.add(GroupMember(providerId, model, 100, 1))
        saveChain()
        chainListTable?.setRowSelectionInterval(chainWorking.size - 1, chainWorking.size - 1)
    }

    private fun keysPage(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        val status = JLabel("  ").apply { font = kFont(12); foreground = K.p.text2 }
        add(buildClientKeysPanel(engine, status), BorderLayout.CENTER)
    }

    private fun advancedPage(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        val status = JLabel("  选择左侧设置项查看 JSON").apply { font = kFont(12); foreground = K.p.text2 }
        add(buildAdvancedSettings(engine, status), BorderLayout.CENTER)
    }

    private fun logPage(): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        logArea = JTextArea().apply {
            isEditable = false
            // Consolas 没有 CJK 字形会渲染成空心方框，逻辑 Monospaced 是复合字体带中文回退
            font = kMonoCjk(12)
            foreground = K.p.text
            background = K.p.top
            caretColor = K.p.text
            border = BorderFactory.createEmptyBorder(8, 10, 8, 10)
        }
        add(object : KRounded(K.p.top, K.p.line, 8) {
            init {
                layout = BorderLayout()
                border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
                // 分类筛选：胶囊点击切换，计数随日志刷新
                val tabs = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 6)).apply { isOpaque = false }
                logCategoryNames.forEach { name ->
                    tabs.add(KPill(name, K.p.text2, K.p.plate).apply {
                        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        addMouseListener(object : MouseAdapter() {
                            override fun mousePressed(e: MouseEvent) {
                                logFilter = name
                                drainLogs()
                            }
                        })
                        logTabs.add(this)
                    })
                }
                add(tabs, BorderLayout.NORTH)
                add(JScrollPane(logArea).apply {
                    border = BorderFactory.createEmptyBorder()
                    isOpaque = false
                    viewport.isOpaque = true
                    viewport.background = K.p.top
                    kStyleScrollBars()
                }, BorderLayout.CENTER)
            }
        }, BorderLayout.CENTER)
        add(actionBar("清空日志" to {
            engine.requestLog.clear()
            allLogLines = emptyList()
            logArea?.text = ""
        }, "刷新" to { refreshAll() }).apply {
            border = BorderFactory.createEmptyBorder(10, 0, 0, 0)
        }, BorderLayout.SOUTH)
    }

    // ------------------------------------------------------------ 网关生命周期

    private fun startGateway(quiet: Boolean = false) {
        try {
            engine.start()
        } catch (e: Exception) {
            if (!quiet) showKMessage(frame, "启动失败：${e.message}", "AI Gateway", error = true)
        }
        refreshAll()
    }

    private fun stopGateway() {
        engine.stop()
        refreshAll()
    }

    private fun exitApp() {
        runCatching { engine.stop() }
        runCatching { engine.stopCallbackServer() }
        exitProcess(0)
    }

    private fun toggleTheme() {
        val next = if (K.p === KLIGHT) KDARK else KLIGHT
        K.p = next
        runCatching { themeFile(dataDir).writeText(if (next === KLIGHT) "light" else "dark") }
        installThemeDefaults()
        rebuildAll()
        frame.repaint()
    }

    // ------------------------------------------------------------ 视图刷新

    /**
     * 刷新整个界面（2 秒一拍的主入口）。
     *
     * @param force 跳过 200ms 节流。**动作类操作（刷新额度/拉模型/启停）必须用 force = true**：
     *   否则紧跟在动作之后的这次刷新会被节流吞掉，界面停在动作前的旧数据上，
     *   看起来就是「点了刷新但额度没变」。
     */
    private fun refreshAll(force: Boolean = false) {
        // 拖拽/缩放窗口期间跳过：AWT 事件线程被 mouseDragged 占满，再插刷新就掉帧
        if (suspendUiRefresh && !force) return
        // 200ms 节流：2s 定时器 / 松手补刷 / 动作回调会叠加触发，
        // 每次都全量走一遍（状态、6 张卡、3 块可视化、2 张表、日志）会在密集操作时叠成卡顿
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAt < 200) return
        lastRefreshAt = now
        val s = engine.status()
        val stateWord = if (s.running) "运行中" else "已停止"
        statusText?.setTextIfChanged(stateWord)
        statusDot?.setForegroundIfChanged(K.p.text)
        // 胶囊 setStyle 内部会 repaint：只在状态真变时调用（每拍无条件调用会持续重绘）
        if (pill?.text != stateWord) {
            pill?.let {
                it.text = stateWord
                it.setStyle(
                    if (s.running) K.p.success else K.p.text2,
                    if (s.running) K.p.successBg else K.p.plate,
                )
            }
        }
        runButton?.let { if (it.text != (if (s.running) "停止网关" else "启动网关")) it.text = if (s.running) "停止网关" else "启动网关" }
        baseUrlValue?.setTextIfChanged(s.localUrl)
        GatewayPortHolder.port = s.port

        // ---- 指标卡：六张（账号就绪 / 今日请求 / 今日 tokens / 模型数 / 剩余额度 / 成功率）
        metricAccounts?.apply {
            val total = s.pool.total
            setValue(if (total > 0) "${s.pool.usable}/$total" else "${s.pool.usable}")
            setNote(
                when {
                    total == 0 -> "池内暂无账号"
                    s.pool.usable == 0 -> "全部不可用"
                    s.pool.disabled > 0 -> "禁用 ${s.pool.disabled} 个"
                    else -> "全部可用"
                },
                if (s.pool.usable == 0 && total > 0) K.p.warn else null,
            )
        }
        metricRequests?.apply {
            setValue("${s.today.requests}")
            // 较昨日：daily 末位是今天，倒数第二位是昨天
            val y = runCatching { engine.callLogStore.dailyStats(System.currentTimeMillis(), 2) }
                .getOrNull()?.firstOrNull()?.requests ?: 0L
            if (y > 0 && s.today.requests > 0) {
                val delta = (s.today.requests - y).toDouble() / y * 100
                val up = delta >= 0
                setNote(
                    (if (up) "↑ " else "↓ ") + String.format("%.1f", kotlin.math.abs(delta)) + "% 较昨日",
                    if (up) K.p.success else K.p.warn,
                )
            } else {
                setNote(if (s.today.requests == 0L) "今日暂无调用" else "无昨日数据可对比")
            }
        }
        metricTokens?.apply {
            setValue(TrendChart.fmtTokens(s.today.totalTokens))
            setNote("输入 ${TrendChart.fmtTokens(s.today.promptTokens)} · 输出 ${TrendChart.fmtTokens(s.today.completionTokens)}")
        }
        metricCredits?.apply {
            if (s.pool.creditsKnown == 0) {
                setValue("—")
                setNote("尚未同步额度")
            } else {
                setValue(s.pool.totalCredits.toString(), K.p.goldText)
                setNote("已同步 ${s.pool.creditsKnown} 个账号")
            }
        }
        // 模型数：只读引擎内存快照（绝不为此打上游），快照空时显示「—」
        metricModels?.apply {
            val count = runCatching { engine.cachedExposedModels().size }.getOrDefault(0)
            setValue(if (count > 0) count.toString() else "—")
            setNote(if (count > 0) "各供应商目录合计" else "尚未拉取目录")
        }
        metricSuccess?.apply {
            val done = s.today.success + s.today.failed
            if (done == 0L) {
                setValue("—")
                setNote("今日暂无完成调用")
            } else {
                val pct = s.today.success.toDouble() / done * 100
                setValue(String.format("%.1f%%", pct), if (pct >= 95) K.p.success else K.p.warn)
                setNote(if (s.today.failed > 0) "失败 ${s.today.failed} 次" else "全部成功")
            }
        }

        refreshVisuals(s)
        refreshActivity()
        refreshTables()
        drainLogs()
        trayIcon?.image = trayImage(s.running)
        trayIcon?.toolTip = if (s.running) "AI Gateway · 运行中 :${s.port}" else "AI Gateway · 已停止"
    }

    /**
     * 刷新概览的三块可视化：实时拓扑、上下文容量分布、消耗趋势。
     *
     * 每 2 秒与 [refreshAll] 同拍；拓扑每拍顺带推进一帧光斑相位（不在其中开独立 Timer，
     * 避免两套节拍互相追帧）。全部读内存数据，不打上游。
     */
    private fun refreshVisuals(s: GatewayStatus) {
        // ---- 实时拓扑
        topologyCanvas?.let { canvas ->
            val providers = engine.providers()
            val recent = runCatching { engine.callLogStore.list().takeLast(20) }.getOrDefault(emptyList())
            val callCounts = recent.groupBy { it.providerId.lowercase() }.mapValues { it.value.size }
            val totalCalls = callCounts.values.sum()
            val active = engine.activeRequests.snapshot()
            val activeCounts = active.groupBy { it.providerId.lowercase() }.mapValues { it.value.size }

            val grouped = providers.filter { it.accountCount > 0 }
            val totalUsable = grouped.sumOf { it.usableCount }.coerceAtLeast(1)
            val sorted = grouped.sortedWith(
                compareByDescending<ProviderInfo> { callCounts[it.id.lowercase()] ?: 0 }
                    .thenByDescending { it.usableCount }
            )
            val branches = sorted.mapIndexed { index, p ->
                val calls = callCounts[p.id.lowercase()] ?: 0
                val weight = if (totalCalls > 0) {
                    (calls.toFloat() / totalCalls).coerceIn(0.08f, 1f)
                } else {
                    (p.usableCount.toFloat() / totalUsable).coerceIn(0.08f, 1f)
                }
                TopoBranch(
                    providerId = p.id,
                    displayName = p.displayName.ifEmpty { p.id.uppercase() },
                    usable = p.usableCount,
                    total = p.accountCount,
                    weight = weight,
                    activeCount = activeCounts[p.id.lowercase()] ?: 0,
                    colorIndex = index,
                )
            }
            canvas.setData(s.running, branches)
            canvas.phase = (canvas.phase + 1f / 16f) % 1f // 2s 一拍 → 32s 一圈，缓慢流动
        }

        // ---- 上下文容量分布（按模型数）
        capacityBar?.let { bar ->
            val models = runCatching { engine.cachedExposedModels() }.getOrDefault(emptyList())
            val byProvider = models.groupBy { it.providerId }.mapValues { it.value.size.toLong() }
            val segs = byProvider.entries
                .sortedByDescending { it.value }
                .mapIndexed { i, e ->
                    val name = engine.providers().firstOrNull { it.id == e.key }?.displayName ?: e.key
                    Triple(name, e.value, i)
                }
            bar.setData(segs)
        }

        // ---- 消耗趋势（近 14 天）
        trendChart?.let { chart ->
            val stats = runCatching { engine.callLogStore.dailyStats(System.currentTimeMillis(), 14) }
                .getOrDefault(emptyList())
            chart.setData(stats.map { it.promptTokens }, stats.map { it.completionTokens })
        }
    }



    private fun refreshActivity() {
        val box = activityBox ?: return
        val records = runCatching { engine.callLogStore.list().takeLast(4).reversed() }.getOrElse { emptyList() }
        val today = engine.status().today
        val hintText = when {
            today.requests == 0L -> "今日暂无调用"
            today.failed + today.aborted == 0L -> "今日 ${today.requests} 次 · 全部成功"
            else -> "今日 ${today.requests} 次 · 失败 ${today.failed}"
        }
        if (activityHint?.text != hintText) {
            activityHint?.text = hintText
            activityHint?.foreground = if (today.requests > 0 && today.failed == 0L) K.p.success else K.p.text2
        }
        // 签名比对：记录的变化才是重建的唯一理由
        val sig = records.joinToString("|") { "${it.id}/${it.status}/${it.totalTokens}" }
        if (sig == activitySignature) return
        activitySignature = sig
        box.removeAll()
        if (records.isEmpty()) {
            box.add(JLabel("暂无调用记录").apply {
                font = kFont(12)
                foreground = K.p.text2
                border = BorderFactory.createEmptyBorder(14, 16, 14, 16)
            })
        } else {
            records.forEachIndexed { index, r ->
                if (index > 0) box.add(divider())
                box.add(activityRow(r.model, "${r.totalTokens} tokens", "${r.durationMillis} ms", r.status))
            }
        }
        box.revalidate()
        box.repaint()
    }

    private fun activityRow(model: String, tokens: String, ms: String, status: CallStatus): JPanel {
        val ok = status == CallStatus.SUCCESS
        val fg = if (ok) K.p.success else K.p.warn
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(7, 16, 7, 14)
            maximumSize = Dimension(Int.MAX_VALUE, 34)
        }
        row.add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(JLabel(DotIcon(fg, 6)))
            add(JLabel(model).apply { font = kMono(12); foreground = K.p.text })
        }, BorderLayout.WEST)
        row.add(JPanel(FlowLayout(FlowLayout.RIGHT, 12, 0)).apply {
            isOpaque = false
            add(JLabel(tokens).apply { font = kFont(11); foreground = K.p.text2 })
            add(JLabel(ms).apply { font = kFont(11); foreground = K.p.text2 })
            add(JLabel(if (ok) "成功" else if (status == CallStatus.ABORTED) "中断" else "失败").apply {
                font = kFont(11, Font.BOLD)
                foreground = fg
            })
        }, BorderLayout.EAST)
        return row
    }

    /**
     * 把 [model] 同步成 [rows]，**内容没变就不动模型**。
     *
     * 之前是「rowCount = 0 再逐行 addRow」：2 秒一拍无条件清空重建，每拍都触发一次
     * structureChanged → 列宽重置、选中丢失、整表重绘，点按钮时尤其明显（点一次界面就
     * 卡半拍）。改成逐格差异更新（行数不变）或逐行增删（行数变化），两条路径都不发
     * structureChanged，列宽与选中保持稳定。
     */
    private fun syncModel(model: DefaultTableModel, rows: List<Array<Any>>) {
        if (model.rowCount != rows.size) {
            while (model.rowCount > rows.size) model.removeRow(model.rowCount - 1)
            while (model.rowCount < rows.size) model.addRow(rows[model.rowCount])
        }
        for (r in rows.indices) {
            val row = rows[r]
            for (c in row.indices) {
                if (c < model.columnCount && model.getValueAt(r, c) != row[c]) {
                    model.setValueAt(row[c], r, c)
                }
            }
        }
    }

    private fun refreshTables() {
        // 只同步当前可见页的表格：隐藏页的模型更新毫无意义，却要付 setValueAt 的
        // 逐格事件 + 表格重绘代价（11 行 × 5 列，2 秒一拍）。切页时 selectPage 会强制刷一次。
        if (currentPage == "providers") {
            syncModel(providerModel, engine.providers().map { p ->
                arrayOf<Any>(
                    p.id, p.displayName, p.authKind.name, p.accountCount, p.usableCount,
                    if (p.capabilities.isEmpty()) "" else p.capabilities.joinToString(",") { it.name },
                )
            })
        }
        if (currentPage == "accounts") {
            syncModel(accountModel, engine.accounts().map { a ->
                val state = when {
                    a.disabled -> "凭证失效"
                    !a.enabled -> "已停用"
                    a.reason.isNotEmpty() -> "额度异常"
                    else -> "可用"
                }
                // 余额列：拿不到就带上**原因**，而不是干巴巴一个「—」。
                // 之前的「余额一直刷不出来、也不知道为什么」就是因为 detail/reason 全被丢掉了。
                val credits = when {
                    a.creditsKnown -> "${a.credits} ${a.creditUnit}".trim()
                    a.reason.isNotEmpty() -> "—（${a.reason}）"
                    a.detail.isNotEmpty() -> "—（${a.detail}）"
                    else -> "—（未同步）"
                }
                arrayOf<Any>(a.providerId, a.nickname.ifEmpty { a.uid }, credits, state, a.uid)
            })
        }
    }

    private fun drainLogs() {
        val area = logArea ?: return
        // 日志页不可见时完全跳过：整段文档重排是 EDT 上最贵的一步，
        // 在概览/配置页时做它纯属浪费，也是「点刷新按钮卡半拍」的来源之一。
        if (currentPage != "logs") return
        // requestLog.lines() 是 newest-first，取 take 才是「最近 N 条」（原 takeLast 拿的是最旧的）
        allLogLines = engine.requestLog.lines().take(300)
        // 分类计数 + 选中态
        val counts = HashMap<String, Int>()
        allLogLines.forEach { counts[it.category()] = (counts[it.category()] ?: 0) + 1 }
        logTabs.forEachIndexed { i, p ->
            val name = logCategoryNames[i]
            val cnt = if (name == "全部") allLogLines.size else counts[name] ?: 0
            if (name == logFilter) {
                p.text = "$name · $cnt"
                p.setStyle(K.p.brandText, K.p.brandLight)
            } else {
                p.text = if (cnt > 0) "$name · $cnt" else name
                p.setStyle(K.p.text2, if (cnt > 0) K.p.brandLight else K.p.plate)
            }
        }
        val filtered = if (logFilter == "全部") allLogLines else allLogLines.filter { it.category() == logFilter }
        val target = filtered.take(LOG_WINDOW)
        val seqs = target.map { it.seq }
        if (seqs == logRenderedSeqs) return

        val oldSet = logRenderedSeqs.toHashSet()
        val newSet = seqs.toHashSet()
        val added = target.filter { it.seq !in oldSet }          // 新产生的行（在头部）
        val kept = target.filter { it.seq in oldSet }            // 仍留在窗口里的旧行
        // 可增量 = 有新增 且 旧行只被「尾部挤出」（kept 是上次序列的前缀，顺序未变）
        val incremental = added.isNotEmpty() && logRenderedSeqs.isNotEmpty() &&
            kept == logRenderedSeqs.take(kept.size) &&
            logRenderedSeqs.drop(kept.size).none { it in newSet }

        if (incremental) {
            val block = added.joinToString("\n") { it.render() }
            area.document.insertString(0, if (area.document.length > 0) "$block\n" else block, null)
            // 头部插了 added.size 行，尾部同步裁掉同等数量（窗口恒为 LOG_WINDOW 行）
            trimTrailingLines(area.document, added.size)
        } else {
            area.text = target.joinToString("\n") { it.render() }
        }
        logRenderedSeqs = seqs
        area.caretPosition = area.document.length
    }

    /** 从文档末尾往前删掉最后 [n] 行（连同其前导换行）。行数不足时删空。 */
    private fun trimTrailingLines(doc: javax.swing.text.Document, n: Int) {
        if (n <= 0) return
        var pos = doc.length
        var count = 0
        var cut = 0
        while (pos > 0) {
            pos--
            if (doc.getText(pos, 1) == "\n") {
                count++
                if (count == n) { cut = pos; break }
            }
        }
        doc.remove(cut, doc.length - cut)
    }

    private fun loadModels() {
        Thread {
            try {
                val models = engine.models()
                // 概览卡与 /v1/models 同口径（含路由组投影），表格只列真实供应商模型
                val exposed = runCatching { engine.exposedModels().size }.getOrElse { models.size }
                SwingUtilities.invokeLater {
                    modelModel.rowCount = 0
                    for (m in models) modelModel.addRow(arrayOf(m.fullId, m.model.name))
                    metricModels?.valueLabel?.text = exposed.toString()
                    refreshAll(force = true)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    showKMessage(frame, "拉取失败：${e.message}", "AI Gateway", error = true)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 概览「模型数」卡的填充策略：
     *
     * 1. [refreshAll]（2 秒一拍）里被动读 [GatewayEngine.cachedExposedModels] 快照——
     *    目录一旦被任何一次成功聚合填上（预拉/刷新按钮/首个 /v1/models 请求），
     *    卡片即自动跟上，不再显示「—」；快照为空显示「—」，绝不主动打上游。
     * 2. 启动后延迟 10 秒预拉一次（后台线程），把目录尽早填上；失败则每 60 秒
     *    重试直到拿到非空结果（有账号但网络慢的场景，比如某家域名被 RST 时
     *    聚合要等超时，一次预拉经常扑空——这就是此前「模型数 —」的根本原因）。
     */
    private var modelCountRetries = 0

    private fun prewarmModelCount() {
        Timer(10_000) {
            Thread {
                val count = runCatching { engine.cachedExposedModels().size }.getOrDefault(0)
                if (count > 0 || modelCountRetries >= 10) return@Thread
                // 快照为空：主动拉一次聚合（后台线程，拉完快照即有值，refreshAll 会跟上）
                modelCountRetries++
                runCatching { engine.exposedModels() }
                SwingUtilities.invokeLater { refreshAll() }
                // 没拿到就 60 秒后再试（Timer 单发，重排下一次）
                SwingUtilities.invokeLater { if (modelCountRetries < 10) prewarmRetry() }
            }.apply { isDaemon = true }.start()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun prewarmRetry() {
        Timer(60_000) {
            Thread {
                val count = runCatching { engine.cachedExposedModels().size }.getOrDefault(0)
                if (count > 0) return@Thread
                modelCountRetries++
                runCatching { engine.exposedModels() }
                SwingUtilities.invokeLater { refreshAll() }
                if (modelCountRetries < 10) SwingUtilities.invokeLater { prewarmRetry() }
            }.apply { isDaemon = true }.start()
        }.apply {
            isRepeats = false
            start()
        }
    }

    // ------------------------------------------------------------ 登录

    private fun selectedProvider(): String? {
        val table = providerTable ?: run {
            selectPage("providers")
            showKMessage(frame, "已切到「供应商」页，请选中一行后重试", "AI Gateway")
            return null
        }
        val row = table.selectedRow
        if (row < 0) {
            showKMessage(frame, "先在「供应商」里选中一行", "AI Gateway")
            return null
        }
        return providerModel.getValueAt(row, 0).toString()
    }

    private fun loginSelected() {
        if (providerTable == null) {
            selectPage("providers")
            return
        }
        val id = selectedProvider() ?: return
        val kind = engine.providers().firstOrNull { it.id == id }?.authKind ?: return
        when (kind) {
            AuthKind.NONE -> importSelected()
            AuthKind.SMS_CODE -> smsLogin(id)
            else -> Thread { browserLogin(id) }.apply { isDaemon = true }.start()
        }
    }

    private fun browserLogin(id: String) {
        try {
            val ticket = engine.beginBrowserLogin(id)
            openBrowser(ticket.loginUrl)
            if (ticket.pollState.isNotEmpty()) {
                // 设备授权型：打开页面后必须持续轮询，CLI 的 browser-login 在这里是直接 return 的
                pollDeviceAuth(id, ticket.pollState, ticket.region)
            } else {
                SwingUtilities.invokeLater {
                    showKMessage(frame, "已在浏览器打开授权页（端口已由引擎监听，5 分钟内有效）。\n完成后账号会自动出现在「账号」页。\n\n${ticket.loginUrl}", "登录 $id")
                }
            }
        } catch (e: Exception) {
            SwingUtilities.invokeLater {
                showKMessage(frame, "登录失败：${e.message}", "AI Gateway", error = true)
            }
        }
    }

    private fun pollDeviceAuth(id: String, state: String, region: String) {
        repeat(120) {
            when (val r = engine.pollDeviceAuth(id, state, region)) {
                is DeviceAuthPoll.Success -> {
                    SwingUtilities.invokeLater { refreshAll() }
                    return
                }

                is DeviceAuthPoll.Failed -> {
                    SwingUtilities.invokeLater {
                        showKMessage(frame, "设备授权失败：${r.error}", "AI Gateway", error = true)
                    }
                    return
                }

                DeviceAuthPoll.Pending -> Thread.sleep(3000)
            }
        }
        SwingUtilities.invokeLater {
            showKMessage(frame, "设备授权等待超时", "AI Gateway")
        }
    }

    private fun smsLogin(id: String) {
        val phone = showKInput(frame, "手机号（不带 +86）", "短信登录 $id") ?: return
        Thread {
            try {
                val msgid = engine.sendSmsCode(id, phone.trim())
                SwingUtilities.invokeLater {
                    val code = showKInput(frame, "输入收到的验证码", "短信登录 $id")
                    if (!code.isNullOrBlank()) {
                        Thread {
                            val r = engine.completeSmsLogin(id, phone.trim(), code.trim(), msgid)
                            SwingUtilities.invokeLater {
                                if (!r.ok) showKMessage(frame, "登录失败：${r.error}", "AI Gateway", error = true)
                                refreshAll()
                            }
                        }.apply { isDaemon = true }.start()
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    showKMessage(frame, "发送验证码失败：${e.message}", "AI Gateway", error = true)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun importSelected() {
        if (providerTable == null) {
            selectPage("providers")
            return
        }
        val id = selectedProvider() ?: return
        val raw = showKInput(frame, "粘贴凭证 JSON 或裸 Key", "导入 $id") ?: return
        val r = engine.importAccountJson(id, raw.trim())
        if (!r.ok) showKMessage(frame, "导入失败：${r.error}", "AI Gateway", error = true)
        refreshAll()
    }

    private fun openBrowser(url: String) {
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (e: Exception) {
            SwingUtilities.invokeLater {
                copyToClipboard(url)
                showKMessage(frame, "无法自动打开浏览器，链接已复制，请手动粘贴打开。", "AI Gateway")
            }
        }
    }

    private fun copyToClipboard(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    private fun selectedAccount(): Pair<String, String>? {
        val table = accountTable ?: run { selectPage("accounts"); return null }
        val row = table.selectedRow
        if (row < 0) {
            showKMessage(frame, "先在「账号」里选中一行", "AI Gateway")
            return null
        }
        return accountModel.getValueAt(row, 0).toString() to accountModel.getValueAt(row, 4).toString()
    }

    private fun toggleAccount() {
        val (provider, uid) = selectedAccount() ?: return
        val current = engine.accounts(provider).firstOrNull { it.uid == uid } ?: return
        engine.setAccountEnabled(provider, uid, !current.enabled)
        refreshAll(force = true)
    }

    private fun moveAccount(up: Boolean) {
        val (provider, uid) = selectedAccount() ?: return
        engine.moveAccountPriority(provider, uid, up)
        refreshAll(force = true)
    }

    private fun deleteAccount() {
        val (provider, uid) = selectedAccount() ?: return
        val ok = showKConfirm(frame, "删除账号 $provider/$uid？")
        if (ok) {
            engine.removeAccount(provider, uid)
            refreshAll(force = true)
        }
    }

    private fun refreshCredits() {
        if (refreshingCredits) return
        refreshingCredits = true
        val buttons = creditButtons
        buttons.forEach { it.isEnabled = false; it.text = "刷新中…" }
        Thread {
            var summary = ""
            try {
                // 并发度 2（原先默认 3）+ 逐账号进度回显：把「卡住没反应」变可见
                val results = engine.refreshAllCredits(parallelism = 2, onProgress = { p ->
                    SwingUtilities.invokeLater {
                        buttons.forEach { it.text = "刷新中 ${p.done}/${p.total}" }
                    }
                })
                val okCount = results.count { it.known }
                val failCount = results.size - okCount
                summary = if (results.isEmpty()) {
                    "没有可刷新的账号"
                } else {
                    "已刷新 $okCount 个账号" + if (failCount > 0) "，$failCount 个失败" else ""
                }
                // 失败明细进日志即可，不弹窗打断（点按钮时弹窗最影响手感）
                results.filter { !it.known }.forEach {
                    runCatching { engine.requestLog.warn("额度刷新失败 ${it.providerId}：${it.error}") }
                }
            } catch (e: Exception) {
                summary = "刷新额度失败：${e.message}"
            }
            SwingUtilities.invokeLater {
                refreshingCredits = false
                buttons.forEach { it.isEnabled = true; it.text = "刷新额度" }
                creditStatus?.text = summary
                creditStatus?.foreground = if (summary.startsWith("已刷新")) K.p.success else K.p.warn
                // force：跳过节流，保证动作后的数据立刻落到界面（否则 200ms 窗口内被吞）
                refreshAll(force = true)
            }
        }.apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------ 托盘

    private fun installTray() {
        if (!SystemTray.isSupported()) {
            frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            return
        }
        val icon = TrayIcon(trayImage(false), "AI Gateway").apply { isImageAutoSize = true }
        // 托盘菜单用 Swing JPopupMenu 而不是 AWT PopupMenu：本 JDK 的 AWT 原生菜单
        // 中文渲染成方块（系统菜单字体是微软雅黑仍坏，属 JDK AWT 渲染 bug），
        // Swing 自绘菜单中文正常（探针实测）。
        val trayMenu = JPopupMenu().apply { font = kFont(12) }
        fun smi(text: String, action: () -> Unit) = JMenuItem(text).apply {
            font = kFont(12)
            addActionListener {
                action()
                trayMenu.isVisible = false // 无 invoker 的弹窗点完菜单项不会自动收起，手动关
            }
        }
        trayMenu.add(smi("显示 / 隐藏") { frame.isVisible = !frame.isVisible })
        trayMenu.add(smi("启动 / 停止网关") {
            if (engine.isRunning()) stopGateway() else startGateway()
        })
        trayMenu.add(smi("复制接入地址") { copyToClipboard(engine.localUrl()) })
        trayMenu.addSeparator()
        trayMenu.add(JCheckBoxMenuItem("开机自启（仅安装版）").apply {
            font = kFont(12)
            isSelected = Startup.isEnabled()
            addItemListener {
                val ok = Startup.setEnabled(isSelected)
                if (!ok && isSelected) {
                    isSelected = false
                    SwingUtilities.invokeLater {
                        showKMessage(frame, "仅打包安装版支持开机自启（开发态没有稳定的 exe 路径）", "AI Gateway")
                    }
                }
            }
        })
        trayMenu.addSeparator()
        trayMenu.add(smi("退出") { exitApp() })
        // 右键托盘图标在光标处弹 Swing 菜单（不设 AWT popupMenu）
        icon.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShow(e)
            override fun mouseReleased(e: MouseEvent) = maybeShow(e)
            private fun maybeShow(e: MouseEvent) {
                if (!e.isPopupTrigger && e.button != MouseEvent.BUTTON3) return
                val p = MouseInfo.getPointerInfo().location
                SwingUtilities.invokeLater {
                    trayMenu.setLocation(p.x, p.y)
                    trayMenu.setVisible(true)
                }
            }
        })
        icon.addActionListener { frame.isVisible = true; frame.toFront() }
        runCatching { SystemTray.getSystemTray().add(icon) }
        trayIcon = icon
        // 无 invoker 的 JPopupMenu 是不可激活的 tool window：系统不给它发 WM_ACTIVATE，
        // WINDOW_DEACTIVATED 永远不来；点其他应用时事件也不进本 JVM 队列，
        // AWTEventListener 同样收不到。Swing 原生机制全部失效，用三层兜底：
        // 1) 主窗失活（应用可见时点别处）→ 收起；
        // 2) 指针轮询：菜单弹出后指针离开菜单区域 500ms → 收起（覆盖托盘-only 与跨进程点击）；
        // 3) AWTEventListener：应用内点击菜单外面 / ESC → 收起。
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowDeactivated(e: WindowEvent?) {
                trayMenu.isVisible = false
            }
        })
        var outsideSince = 0L
        // 轮询只在菜单显示期间运行：常驻 10Hz Timer 会让 EDT 反复唤醒（空闲也吃 1~2% CPU），
        // 菜单弹出到收起之间可能只有一两秒，没必要为它常驻一个节拍。
        val outsideWatch = Timer(200) {
            runCatching {
                if (!trayMenu.isShowing) {
                    outsideSince = 0L
                    return@Timer
                }
                val loc = trayMenu.locationOnScreen
                val size = trayMenu.size
                val p = MouseInfo.getPointerInfo().location
                val inside = p.x >= loc.x - 2 && p.x <= loc.x + size.width + 2 &&
                    p.y >= loc.y - 2 && p.y <= loc.y + size.height + 2
                if (inside) {
                    outsideSince = 0L
                } else {
                    val now = System.currentTimeMillis()
                    if (outsideSince == 0L) outsideSince = now
                    if (now - outsideSince >= 500) {
                        trayMenu.isVisible = false
                        outsideSince = 0L
                    }
                }
            }
        }.apply { isRepeats = true }
        trayMenu.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) {
                outsideSince = 0L
                if (outsideWatch.isRunning) outsideWatch.stop()
                outsideWatch.start()
            }
            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {
                outsideWatch.stop()
                outsideSince = 0L
            }
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) = Unit
        })
        Toolkit.getDefaultToolkit().addAWTEventListener({ raw ->
            if (!trayMenu.isShowing) return@addAWTEventListener
            when (raw.id) {
                MouseEvent.MOUSE_PRESSED -> {
                    val me = raw as MouseEvent
                    val loc = trayMenu.locationOnScreen
                    val size = trayMenu.size
                    val inside = me.xOnScreen >= loc.x && me.xOnScreen <= loc.x + size.width &&
                        me.yOnScreen >= loc.y && me.yOnScreen <= loc.y + size.height
                    if (!inside) trayMenu.isVisible = false
                }
                KeyEvent.KEY_PRESSED -> if ((raw as KeyEvent).keyCode == KeyEvent.VK_ESCAPE) {
                    trayMenu.isVisible = false
                }
            }
        }, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.KEY_EVENT_MASK)
        // 调试钩子：-Daigw.debug.traymenu=1 启动即在屏幕固定位置弹出托盘菜单（自动化验证用）
        if (System.getProperty("aigw.debug.traymenu") != null) {
            SwingUtilities.invokeLater {
                trayMenu.setLocation(500, 300)
                trayMenu.setVisible(true)
            }
        }
    }

    private fun trayImage(running: Boolean): BufferedImage {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = if (running) Color(0x2E, 0xA0, 0x43) else Color(0x8A, 0x8A, 0x8A)
        g.fillOval(1, 1, 14, 14)
        g.color = Color.WHITE
        g.font = Font("SansSerif", Font.BOLD, 10)
        g.drawString("A", 4, 12)
        g.dispose()
        return img
    }
}

/** 最小 / 最大化 / 关闭：无边框窗口需要自绘。 */
private class WindowButton(private val kind: Kind) : JPanel() {
    enum class Kind { MIN, MAX, CLOSE }

    private var hover = false
    private var pressed = false

    init {
        isOpaque = false
        preferredSize = Dimension(30, 22)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; pressed = false; repaint() }
            override fun mousePressed(e: MouseEvent) { pressed = true; repaint() }
            override fun mouseReleased(e: MouseEvent) {
                pressed = false
                repaint()
                val w = ownerFrame() ?: return
                when (kind) {
                    Kind.MIN -> w.setState(JFrame.ICONIFIED)
                    Kind.MAX -> (SwingUtilities.getWindowAncestor(this@WindowButton) as? GatewayWindow)?.toggleMaximize()

                    Kind.CLOSE -> w.dispatchEvent(WindowEvent(w, WindowEvent.WINDOW_CLOSING))
                }
            }
        })
    }

    private fun ownerFrame(): JFrame? = SwingUtilities.getWindowAncestor(this) as? JFrame

    override fun paintComponent(g: Graphics) {
        val g2 = antiAlias(g)
        val w = width - 1
        val h = height - 1
        if (hover) {
            g2.color = if (kind == Kind.CLOSE) Color(0xC42B1C) else K.p.line
            g2.fillRoundRect(0, 0, w, h, 8, 8)
        }
        g2.color = if (hover && kind == Kind.CLOSE) Color.WHITE else K.p.text2
        g2.stroke = BasicStroke(1.4f)
        val cx = w / 2
        val cy = h / 2
        when (kind) {
            Kind.MIN -> g2.drawLine(cx - 5, cy + 4, cx + 5, cy + 4)
            Kind.MAX -> g2.drawRect(cx - 5, cy - 5, 10, 10)
            Kind.CLOSE -> {
                g2.drawLine(cx - 5, cy - 5, cx + 5, cy + 5)
                g2.drawLine(cx - 5, cy + 5, cx + 5, cy - 5)
            }
        }
    }

    override fun getMaximumSize(): Dimension = Dimension(30, 22)
}

/**
 * 无边框窗口的根容器：负责四边/四角的拖动缩放（边缘区域属于容器本身，
 * 不会被内部组件吃掉鼠标事件）并绘制外描边。
 */
private class RootPanel : JPanel() {
    private val edge = 5
    private var mode: Int = 0
    private var origin: Point? = null
    private var originBounds: java.awt.Rectangle? = null

    init {
        isOpaque = true
    }

    override fun paintComponent(g: Graphics) {
        g.color = K.p.bg
        g.fillRect(0, 0, width, height)
        g.color = K.p.line
        g.drawRect(0, 0, width - 1, height - 1)
    }

    /** 由窗口调用：装鼠标监听，用于边缘缩放。 */
    fun installChrome(frame: JFrame) {
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                mode = edgeOf(e.x, e.y)
                if (mode != 0) {
                    origin = frame.locationOnScreen.let { Point(e.xOnScreen - it.x, e.yOnScreen - it.y) }
                    origin = Point(e.xOnScreen, e.yOnScreen)
                    originBounds = frame.bounds
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                mode = 0
                cursor = Cursor.getDefaultCursor()
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                cursor = when (edgeOf(e.x, e.y)) {
                    N or S -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
                    E or W -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
                    NE or SW -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)
                    NW or SE -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
                    else -> Cursor.getDefaultCursor()
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                val start = origin ?: return
                val b = originBounds ?: return
                val dx = e.xOnScreen - start.x
                val dy = e.yOnScreen - start.y
                var x = b.x
                var y = b.y
                var w = b.width
                var h = b.height
                if (mode and W != 0) { x = b.x + dx; w = b.width - dx }
                if (mode and E != 0) { w = b.width + dx }
                if (mode and N != 0) { y = b.y + dy; h = b.height - dy }
                if (mode and S != 0) { h = b.height + dy }
                w = w.coerceAtLeast(frame.minimumSize.width)
                h = h.coerceAtLeast(frame.minimumSize.height)
                if (mode and W != 0) x = b.x + b.width - w
                if (mode and N != 0) y = b.y + b.height - h
                frame.bounds = java.awt.Rectangle(x, y, w, h)
            }
        })
    }

    private fun edgeOf(x: Int, y: Int): Int {
        var m = 0
        if (x <= edge) m = m or W
        if (x >= width - edge) m = m or E
        if (y <= edge) m = m or N
        if (y >= height - edge) m = m or S
        return m
    }

    private companion object {
        const val N = 1
        const val S = 2
        const val W = 4
        const val E = 8
        const val NW = 16
        const val NE = 32
        const val SW = 64
        const val SE = 128
    }
}
