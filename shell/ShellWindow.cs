using System;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace AiGatewayWeb
{
    /// <summary>
    /// 无边框但**保留系统窗口行为**的主窗口。
    ///
    /// 三个消息构成全部「窗口手感」：
    /// 1. `CreateParams` 补回 WS_CAPTION / WS_THICKFRAME / WS_MAXIMIZEBOX：
    ///    FormBorderStyle.None 会把这些风格也去掉，那样就没有缩放、没有 Aero Snap、
    ///    最大化还会盖住任务栏。
    /// 2. `WM_NCCALCSIZE` 返回 0：把系统标题栏高度抹成 0，视觉上是无边框，但风格仍在。
    ///    最大化时需要把工作区边界扣回来，否则客户区等于整块屏幕、被任务栏挡住底部。
    /// 3. `WM_NCHITTEST`：边缘 → 缩放常量、标题栏 → HTCAPTION、其余 → HTCLIENT。
    ///    返回 HTCAPTION 之后，拖动与双击最大化由系统在非客户区处理，**完全不经过 WebView2**，
    ///    这是旧版拖动卡顿从根上消失的原因。
    ///
    /// 布局是这一版能不能拖动的**根本**：
    /// WebView2 的子窗口会铺满它自己的边界，凡是它覆盖到的像素，鼠标消息都由
    /// Chromium 进程处理，**父窗口的 WM_NCHITTEST 永远不会被调用**。
    /// 所以标题栏必须是 Form 的普通子控件（[TitleBarPanel]），WebView2 只占下面那块。
    /// 代价是标题栏由 C# 绘制而非 HTML——视觉上按同一套令牌配色，交互上完全原生。
    /// </summary>
    internal sealed class ShellWindow : Form
    {
        // 窗口风格位
        private const int WS_CAPTION = 0x00C00000;
        private const int WS_THICKFRAME = 0x00040000;
        private const int WS_MINIMIZEBOX = 0x00020000;
        private const int WS_MAXIMIZEBOX = 0x00010000;
        private const int WS_SYSMENU = 0x00080000;

        /// <summary>
        /// 标题栏高度（CSS 像素），与 TitleBarPanel 的绘制基准一致。
        ///
        /// 42 是**偏厚**的：Windows 11 原生标题栏 32，Chrome/VS Code 约 40。
        /// 1080p + 150% 缩放时逻辑高只有 720，42 CSS 会撑成 63 物理像素 = 逻辑高的
        /// 8.8%，用户直接反馈「标题栏过高」。取 36 后同一场景降到 54 物理 / 7.5%，
        /// 4K@300% 上仍是 108×2160 = 5%，两头都不失衡。
        /// </summary>
        private const int TitleBarCss = 36;

        /// <summary>期望 CSS 尺寸（物理尺寸 = 该值 × DPI 缩放，被工作区封顶）。</summary>
        private const int PreferredWidth = 1240;
        private const int PreferredHeight = 820;

        private readonly WebView2 _web = new WebView2();
        private readonly TitleBarPanel _titleBar;
        private readonly string _dataDir;

        /// <summary>
        /// 当前 UI 缩放（= WebView2 的 devicePixelRatio，96 基准）。
        ///
        /// **唯一可信来源是 WebView2 自己报的 dpr**：`GetDpiForMonitor` 在 4K 混缩放
        /// 环境里返回的可能是系统推荐值（实测 1.7），而 WebView2 实际按 3.0 光栅化，
        /// 两者不一致就会出现「内容区正常、标题栏还是小」——标题栏必须跟着**同一个**
        /// 数字缩放，不能用另一套口径去猜。
        /// </summary>
        private float _uiScale = 1f;

        /// <summary>用户是否手动调整过窗口尺寸：调整过就不再按缩放重算窗口大小。</summary>
        private bool _userResized;

        /// <summary>
        /// 跨屏缩放重算的票据：每次 WM_DPICHANGED 自增，延迟回调只认最新那张，
        /// 否则拖动经过多块屏时会连续触发一串重算（标题栏来回跳）。
        /// </summary>
        private int _resyncTicket;

        /// <summary>真正退出（托盘菜单 / 系统关机 / 诊断模式），用来区分「关闭=最小化到托盘」。</summary>
        private bool _reallyExit;

        private NotifyIcon _tray;

        internal ShellWindow(string dataDir)
        {
            _dataDir = dataDir;
            Text = "AI Gateway";
            // 应用图标：从 exe 自身取（build-shell.bat 用 /win32icon:app.ico 嵌入），
            // 不额外读外部文件——安装目录里再多一个 ico 只会跟 exe 版本错配。
            // 同一份图标也给任务栏与托盘用，三处才一致。
            try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); }
            catch (Exception ex) { ServerLauncher.Log("[icon] extract failed: " + ex.Message); }
            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.Manual;
            // 最小尺寸按「CSS 期望」声明，OnLoad 后按真实 DPI 换算成物理值
            // （构造时 DeviceDpi 还不可靠，见 DpiScaleOf 注释）。
            MinimumSize = new Size(720, 480);

            bool dark = ThemeFile.IsDark(dataDir);
            BackColor = dark
                ? Color.FromArgb(0x1F, 0x1F, 0x1F)
                : Color.FromArgb(0xF0, 0xF0, 0xF0);

            // 首屏尺寸：物理像素 = 期望 CSS 尺寸 × 本屏 DPI 缩放，且不超过工作区。
            //
            // 为什么不能只给固定 1240×820：net48 + PerMonitorV2 下 Bounds 是**设备像素**，
            // 但高 DPI 屏（200%/250%/300%）上 WinForms 的窗口创建/迁移路径存在
            // 把初始 Bounds 按缩放倍数**反向除回去**的行为（实测 dpi=288 时
            // Bounds=1240 得到 413 物理的窗口）——窗口小了一圈，WebView2 的 CSS 视口
            // 跟着变小，13px 的字被高倍光栅化后视觉巨大。这正是用户遇到的
            // 「分辨率越大显示越大」。
            // 显式按 DPI 乘出目标物理尺寸，并按工作区 90% 封顶：任何分辨率/缩放下
            // 窗口都铺开到合理大小，内容由 CSS 视口自适应。
            var work = Screen.FromPoint(Cursor.Position).WorkingArea;
            // 窗口要居中落在 work 中心，问「work 中心」那块屏的 DPI（无头环境光标恒 (0,0)，
            // 不能拿光标位置当落屏依据）。
            var workCenter = new Point(work.X + work.Width / 2, work.Y + work.Height / 2);
            float scale = Win32.ScreenScaleAt(workCenter);
            _uiScale = scale;
            // 下限取工作区的 70%：本函数拿到的 scale 在高缩放屏上可能偏小
            // （GetDpiForMonitor 与 Chromium 的实际 dpr 不一致），只按 scale 算会得到一个
            // 偏窄的窗口；先给足面积，等 WebView2 报出真实 dpr 后再精确校正
            // （见 SyncScaleFromWebViewAsync）。上限仍是工作区 90%。
            int w = Math.Min(Math.Max((int)(PreferredWidth * scale), work.Width * 7 / 10), work.Width * 9 / 10);
            int h = Math.Min(Math.Max((int)(PreferredHeight * scale), work.Height * 7 / 10), work.Height * 9 / 10);
            w = Math.Max(w, (int)(720 * scale) + 16);
            h = Math.Max(h, (int)(480 * scale) + 16);
            Bounds = new Rectangle(work.X + (work.Width - w) / 2, work.Y + (work.Height - h) / 2, w, h);
            ServerLauncher.Log("[dpi] work=" + work.Width + "x" + work.Height
                + " scale=" + scale + " deviceDpi=" + DeviceDpi
                + " -> bounds=" + w + "x" + h);

            // 四周 8px 环留给 Form 自己：边缘缩放要落在 Form 的客户区上
            Padding = new Padding(HitTest.EdgeDip);

            // 标题栏高度：**构造期故意只取保守值**。
            //
            // 这里拿到的 scale 来自 `GetDpiForMonitor`，实测在混缩放环境会给出与 Chromium
            // 不一致的数字（4K 屏返回 1.7 而实际 dpr=3）。如果照它算，窗口一旦最终落在
            // 一块 100%/150% 的屏上，就会顶着一条为 4K 准备的高标题栏（用户反馈的
            // 「1080P 上标题栏过高」）。所以构造期夹在 [1.0, 1.5]，等 WebView2 报出
            // 真实 dpr 后再让 ApplyUiScale 定稿——那一帧的差异用户看不到。
            int barH = (int)(TitleBarCss * Clamp(scale, 1.0f, 1.5f));
            _titleBar = new TitleBarPanel(dark) { Owner = this, Height = barH, Dock = DockStyle.Top };
            // 把真实缩放注入标题栏：它内部的字号/按钮/间距全靠这个值换算
            // （控件自身 DeviceDpi 在构造期恒 96，不能信）。
            _titleBar.SetScale(Clamp(scale, 1.0f, 1.5f));
            _web.Dock = DockStyle.Fill;

            // Dock 顺序（关键，踩过坑）：WinForms 的 Dock 布局从 Controls 的**末尾向前**处理。
            // 必须「先加 Fill、后加 Top」——反过来 Fill 会先把客户区占满，
            // Top 再布局时已无空间，标题栏高度为 0，表现就是「看不见、点不到、拖不动」。
            Controls.Add(_web);
            Controls.Add(_titleBar);

            Load += OnLoadLayoutLog;
            Load += async (s, e) => await InitWebView();
            // 托盘**启动就建**，不等第一次隐藏。两个理由：
            // ① 隐藏后再建图标会「晚一拍」出现，用户会以为程序没了；
            // ② 它是「关窗后唯一的恢复入口」，必须在关窗之前就存在。
            Load += delegate { EnsureTray(); };
        }

        internal WebView2 WebView { get { return _web; } }

        // ---------------------------------------------------------------- 托盘

        /// <summary>
        /// 托盘图标：右下角常驻，双击打开窗口。
        ///
        /// 用户反馈「关闭直接就关了，没有右下角的图标」——桌面常驻后端应该像
        /// 其它本地服务一样：关窗只收进托盘，进程与网关继续跑，点图标随时恢复。
        /// </summary>
        private void EnsureTray()
        {
            if (_tray != null) return;
            var icon = Icon ?? SystemIcons.Application;
            var menu = new ContextMenuStrip();
            menu.Items.Add("打开 AI Gateway", null, delegate { RestoreFromTray(); });
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("退出", null, delegate { ExitApp(); });

            _tray = new NotifyIcon
            {
                Icon = icon,
                Text = "AI Gateway",
                Visible = true,
                ContextMenuStrip = menu,
            };
            _tray.DoubleClick += delegate { RestoreFromTray(); };
        }

        /// <summary>从托盘恢复窗口（隐藏后 Show 回来，并置前）。</summary>
        internal void RestoreFromTray()
        {
            Show();
            WindowState = FormWindowState.Normal;
            Activate();
            BringToFront();
        }

        /// <summary>
        /// 关闭窗口但保留后台服务。标题栏的 X 与 Alt+F4 走这里。
        ///
        /// **这是一个真 bug 的修法，不是新功能**：原来标题栏 X 调的是 `Owner.Close()`，
        /// 而 `Form.Close()` 的 `CloseReason` 是 `None` 而不是 `UserClosing`，
        /// 于是 `OnFormClosing` 里那条「收进托盘」的分支根本不命中，直接真退出、
        /// 连后端进程一起被 finally 里的 `ServerLauncher.Stop()` 杀掉。
        /// 用户看到的现象就是「点 X 后台就没了、托盘图标也消失」。
        ///
        /// 不靠 `CloseReason` 判定（它由调用方决定，不可控），改成显式方法：
        /// 谁想关窗口就调它，语义明确，不会再随 WinForms 的关闭原因漂移。
        /// </summary>
        internal void HideToTray()
        {
            if (_reallyExit) return;      // 正在退出流程里，不要再拦
            EnsureTray();
            Hide();
        }

        /// <summary>真正退出：托盘菜单「退出」用。会连带停掉自己拉起的后端。</summary>
        internal void ExitApp()
        {
            _reallyExit = true;
            DisposeTray();
            Close();
        }

        private void DisposeTray()
        {
            if (_tray == null) return;
            _tray.Visible = false;
            _tray.Dispose();
            _tray = null;
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            // 真退出只留三条：托盘菜单「退出」、系统关机/注销、诊断模式。
            //
            // **刻意不再把 `TaskManagerClosing` 当强制退出**：它会在任务管理器
            // 「结束任务」时触发，而本程序是常驻服务——用户从任务管理器关窗口
            // 本意往往是「把窗口清掉」，不是「停掉网关」。原来那一条会让网关
            // 在用户完全没意识到的情况下中断。真要停服务，托盘菜单「退出」是明确入口。
            //
            // 上一次实测踩到的坑：用 `PostMessage(WM_CLOSE)` 模拟点 X，系统给的
            // `CloseReason` 是 `TaskManagerClosing`（不是 UserClosing），于是走真退出——
            // 那是我测法不对；真实点 X 走 `WM_SYSCOMMAND/SC_CLOSE`，reason=UserClosing。
            bool forceQuit = _reallyExit
                || Program.QuitAfterShot
                || Program.Headless
                || e.CloseReason == CloseReason.WindowsShutDown;
            ServerLauncher.Log("[close] reason=" + e.CloseReason
                + " forceQuit=" + forceQuit + " reallyExit=" + _reallyExit
                + " headless=" + Program.Headless + " quitAfterShot=" + Program.QuitAfterShot);
            if (!forceQuit)
            {
                e.Cancel = true;
                EnsureTray();
                Hide();
                ServerLauncher.Log("[close] -> hidden to tray, form visible=" + Visible);
                return;
            }
            ServerLauncher.Log("[close] -> real exit");
            base.OnFormClosing(e);
        }

        protected override void OnFormClosed(FormClosedEventArgs e)
        {
            DisposeTray();
            base.OnFormClosed(e);
        }

        /// <summary>窗口首次显示后按真实 DPI 修正最小尺寸（物理像素）。</summary>
        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            // MinimumSize 声明时按 96 DPI 写的 720×480；高 DPI 屏上 WinForms 可能已自动缩放，
            // 也可能没有（取决于版本与缩放时机）。这里以「不小于 CSS 720×480 × 本屏缩放」为准
            // 显式抬高，保证用户怎么缩窗口都不会小到内容不可用。
            float s = DeviceDpi / 96f;
            var need = new Size((int)(720 * s), (int)(480 * s));
            if (MinimumSize.Width < need.Width || MinimumSize.Height < need.Height)
            {
                MinimumSize = new Size(Math.Max(MinimumSize.Width, need.Width),
                                       Math.Max(MinimumSize.Height, need.Height));
            }
        }

        /// <summary>
        /// 真实屏幕 DPI 缩放（96 基准）。
        ///
        /// 构造期 DeviceDpi 恒 96；窗口创建后会随落屏更新，但为稳妥统一走
        /// GetDpiForMonitor（窗口中心所在屏），与初始尺寸/标题栏共用同一口径。
        /// </summary>
        private float DpiScale
        {
            get
            {
                var center = new Point(Location.X + Width / 2, Location.Y + Height / 2);
                var s = Win32.ScreenScaleAt(center);
                return s > 0f ? s : DeviceDpi / 96f;
            }
        }

        /// <summary>把小数夹进区间（net48 的 Math 没有 Clamp）。</summary>
        private static float Clamp(float v, float lo, float hi)
        {
            return v < lo ? lo : (v > hi ? hi : v);
        }

        /// <summary>标题栏在客户区里占的像素高度（含顶部 padding）。</summary>
        private int TitleBarPixelHeight
        {
            get { return Padding.Top + (_titleBar == null ? 0 : _titleBar.Height); }
        }

        /// <summary>窗口按钮区起始 x（客户区坐标），与 TitleBarPanel 的按钮布局对齐。</summary>
        private int ButtonLeftPixel
        {
            get { return ClientSize.Width - TitleBarPanel.TotalButtonsWidth(DpiScale) - Padding.Right; }
        }

        // ---------------------------------------------------------------- 窗口消息

        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                // 补回被 FormBorderStyle.None 去掉的风格：有它们才有系统缩放、
                // Aero Snap 与正确的最大化行为
                cp.Style |= WS_CAPTION | WS_THICKFRAME | WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_SYSMENU;
                return cp;
            }
        }

        protected override void WndProc(ref Message m)
        {
            switch (m.Msg)
            {
                case Win32.WM_NCCALCSIZE:
                    if (m.WParam != IntPtr.Zero)
                    {
                        if (WindowState == FormWindowState.Maximized)
                        {
                            // 最大化时把工作区边界扣回来（否则客户区 = 整屏，底部被任务栏遮住）
                            var r = (Win32.RECT)Marshal.PtrToStructure(m.LParam, typeof(Win32.RECT));
                            int bx = Win32.GetSystemMetrics(Win32.SM_CXSIZEFRAME)
                                     + Win32.GetSystemMetrics(Win32.SM_CXPADDEDBORDER);
                            int by = Win32.GetSystemMetrics(Win32.SM_CYSIZEFRAME)
                                     + Win32.GetSystemMetrics(Win32.SM_CXPADDEDBORDER);
                            r.left += bx;
                            r.right -= bx;
                            r.top += by;
                            r.bottom -= by;
                            Marshal.StructureToPtr(r, m.LParam, false);
                        }
                        // 返回 0：非客户区全部并入客户区 → 系统标题栏高度归零，视觉无边框
                        m.Result = IntPtr.Zero;
                        return;
                    }
                    break;

                case Win32.WM_GETMINMAXINFO:
                    // 最大化尺寸交给系统算（已保留 WS_THICKFRAME，系统会正确避开任务栏）
                    break;

                case Win32.WM_EXITSIZEMOVE:
                    // 用户手动拖拽/缩放结束：记住「他自己调过大小」，之后 DPI 校正不再改窗口尺寸。
                    // 用这条消息而不是 Resize 事件：我们自己的 Bounds 赋值也会触发 Resize，
                    // 那样首屏一次缩放就把标记置上了，自动校正永远失效。
                    _userResized = true;
                    break;

                case Win32.WM_DPICHANGED:
                    // 窗口被拖到不同 DPI 的显示器：标题栏、按钮、字号都必须按新屏重算。
                    // 不做的话，4K(300%) 上撑到 126px 高的标题栏会被原样带到 1080p(100%) 屏，
                    // 在一屏只有 ~1080 高的情况下，一条 126px 的黑条就是「标题栏过高」。
                    // 拖动过程中这条消息会连发，用票据 + 定时器只执行最后一次。
                    ScheduleScaleResync("dpi-changed");
                    break;

                case Win32.WM_NCHITTEST:
                    {
                        // **坐标必须取自 lParam**：不能用 Cursor.Position。
                        // 系统在贴边吸附、键盘触发、DPI 切换时也会发本消息，
                        // 那时光标位置与待测点无关，判定就会全错。
                        Point screen = HitTest.ScreenPointFromLParam(m.LParam);
                        Point p = PointToClient(screen);
                        m.Result = (IntPtr)HitTest.Decide(
                            p, ClientSize, DpiScale,
                            WindowState == FormWindowState.Maximized,
                            TitleBarPixelHeight, ButtonLeftPixel);
                        return;
                    }
            }
            base.WndProc(ref m);
        }

        /// <summary>
        /// 启动系统原生窗口拖动循环。
        ///
        /// 步骤：① `ReleaseCapture()` 让出标题栏控件当前持有的鼠标捕获；
        /// ② 向本窗口补一条 `WM_NCLBUTTONDOWN(HTCAPTION)`。
        /// DefWindowProc 看到「标题栏被按下」就直接进入系统自己的**模态移动循环**：
        /// 后续光标跟踪、贴边吸附（Aero Snap）、最大化后拖拽还原、双击最大化
        /// 全部由 Windows 完成，与真实标题栏同一套代码，因此不可能掉帧。
        ///
        /// 这是唯一可行的路径，前面两种都实测失败：
        /// - Form 的 WM_NCHITTEST：WebView2 子窗口铺满客户区，父窗口收不到那些鼠标消息；
        /// - CSS app-region + IsNonClientRegionSupportEnabled：CSS 侧确实算出 drag，
        ///   但窗口不动（该开关在当前 Runtime 上没接管拖动）。
        /// </summary>
        internal void BeginNativeDrag()
        {
            ServerLauncher.Log("[drag] BeginNativeDrag state=" + WindowState);
            Win32.ReleaseCapture();
            Point p = Cursor.Position;
            IntPtr lParam = (IntPtr)((p.Y << 16) | (p.X & 0xFFFF));
            Win32.SendMessage(Handle, Win32.WM_NCLBUTTONDOWN, (IntPtr)HitTest.HTCAPTION, lParam);
        }

        // ---------------------------------------------------------------- 布局诊断

        private void OnLoadLayoutLog(object sender, EventArgs e)
        {
            // 「点击不到标题栏」靠肉眼猜不出来，只能打印 bounds。
            ServerLauncher.Log("[layout] form client=" + ClientSize.Width + "x" + ClientSize.Height
                + " padding=" + Padding);
            ServerLauncher.Log("[layout] titlebar bounds=" + _titleBar.Bounds
                + " visible=" + _titleBar.Visible);
            ServerLauncher.Log("[layout] webview bounds=" + _web.Bounds
                + " visible=" + _web.Visible);
        }

        // ---------------------------------------------------------------- WebView2

        private async Task InitWebView()
        {
            try
            {
                // 用户数据目录放到数据目录下：不污染安装目录，也方便清理缓存
                var env = await CoreWebView2Environment.CreateAsync(
                    null, Path.Combine(_dataDir, "webview2"));
                await _web.EnsureCoreWebView2Async(env);

                var core = _web.CoreWebView2;
                // DevTools 保留可用（F12 可以直接排查前端问题）；其余一律关掉，
                // 这些是「浏览器痕迹」的来源：状态栏、右键菜单、缩放、手势导航。
                core.Settings.AreDevToolsEnabled = true;
                core.Settings.IsStatusBarEnabled = false;
                core.Settings.AreDefaultContextMenusEnabled = false;
                core.Settings.IsZoomControlEnabled = false;
                core.Settings.IsPinchZoomEnabled = false;
                core.Settings.IsSwipeNavigationEnabled = false;

                // 非客户区支持：让 Chromium 把 app-region 识别为标题栏。
                // 本版标题栏已改由 C# 绘制，这个开关不再是拖动的前提，
                // 但保留它可让「未来若有人把某个 HTML 区域标成 drag」仍然有效。
                try { core.Settings.IsNonClientRegionSupportEnabled = true; }
                catch { /* 旧 Runtime 不支持该属性，忽略 */ }

                core.WebMessageReceived += OnWebMessage;
                core.NewWindowRequested += OnNewWindowRequested;
                core.NavigationStarting += OnNavigationStarting;
                // 页面加载完成后用 WebView2 实测的 dpr 校正标题栏尺寸——
                // GetDpiForMonitor 在混缩放/4K 环境会给出与 Chromium 不同的值（实测 1.7 vs 3.0），
                // 只有 dpr 才是内容区真正的缩放倍数，标题栏必须跟它一致。
                core.NavigationCompleted += async (s, e) => await SyncScaleFromWebViewAsync();

                core.Navigate(Program.UiUrl());

                HookDiagnostics(core);
            }
            catch (Exception ex)
            {
                MessageBox.Show(
                    "WebView2 初始化失败：\n\n" + ex.Message +
                    "\n\n请确认已安装 Microsoft Edge WebView2 Runtime（Win10/11 通常自带）。",
                    "AI Gateway", MessageBoxButtons.OK, MessageBoxIcon.Error);
                Close();
            }
        }

        /// <summary>
        /// 用 WebView2 报告的 devicePixelRatio 校正标题栏与窗口尺寸。
        ///
        /// 为什么必须走这条路：4K + 混合缩放环境下 `GetDpiForMonitor` 可能返回
        /// 系统推荐值（实测 1.7），而 Chromium 实际按 3.0 光栅化——内容区字是 3 倍放大的，
        /// 标题栏只放 1.7 倍，于是「内容正常、标题还是小」，正是用户反复反馈的现象。
        /// dpr 与内容区是同源数字，用它缩放标题栏才能和正文对齐。
        /// 顺带把窗口尺寸也按 dpr 校正一次（用户没手动调过大小才动）。
        ///
        /// **跨屏时必须重新读**：窗口从 4K(300%) 拖到 1080p(100%) 后，Chromium 要过一会儿
        /// 才把 dpr 从 3 改回 1，早读会拿到旧值把标题栏继续按 3 倍画——用户看到的就是
        /// 「1080p 上标题栏特别高」。所以这里连读两次、中间留一小段间隔，取后一次。
        /// </summary>
        private async Task SyncScaleFromWebViewAsync(bool forceFit = false)
        {
            try
            {
                var core = _web.CoreWebView2;
                if (core == null) return;
                // 诊断覆盖：--force-dpr 时不再问 WebView2，直接把给定档位定稿，
                // 这样一块屏也能验证 1/1.5/2/3 四种缩放下标题栏与窗口是否失衡。
                float forced = Program.ForceDpr;
                if (forced > 0f)
                {
                    ServerLauncher.Log("[dpi] forced dpr=" + forced);
                    ApplyUiScale(forced, forceFit);
                    return;
                }
                float dpr = await ReadDprAsync(core);
                if (dpr <= 0f)
                {
                    ServerLauncher.Log("[dpi] dpr unparsable, keep current scale");
                    return;
                }
                ServerLauncher.Log("[dpi] webview dpr=" + dpr + " (monitor scale=" + _uiScale + ")");
                ApplyUiScale(dpr, forceFit);
            }
            catch (Exception ex)
            {
                ServerLauncher.Log("[dpi] sync from webview failed: " + ex.Message);
            }
        }

        /// <summary>读 devicePixelRatio，读两次取后者（跨屏切换后 Chromium 有延迟）。</summary>
        private async Task<float> ReadDprAsync(CoreWebView2 core)
        {
            float first = await ReadDprOnceAsync(core);
            await Task.Delay(280);
            float second = await ReadDprOnceAsync(core);
            if (second > 0f && Math.Abs(second - first) > 0.01f)
            {
                ServerLauncher.Log("[dpi] dpr settled " + first + " -> " + second);
            }
            return second > 0f ? second : first;
        }

        private async Task<float> ReadDprOnceAsync(CoreWebView2 core)
        {
            try
            {
                string raw = await core.ExecuteScriptAsync("window.devicePixelRatio || 1");
                float dpr;
                if (float.TryParse(raw, NumberStyles.Float, CultureInfo.InvariantCulture, out dpr) && dpr >= 0.5f)
                {
                    return dpr;
                }
            }
            catch { /* 页面正在切换时可能失败，交给调用方取另一次 */ }
            return 0f;
        }

        /// <summary>
        /// 跨屏/DPI 变化后延迟重算缩放。
        /// WM_DPICHANGED 在拖动过程中会连发多条，每条都立即同步会打出大量日志并让标题栏抖，
        /// 所以只保留最后一次（ticket 递增，过期的丢弃）。
        /// </summary>
        private void ScheduleScaleResync(string reason)
        {
            int ticket = ++_resyncTicket;
            var timer = new System.Windows.Forms.Timer { Interval = 600 };
            timer.Tick += async (s, e) =>
            {
                timer.Stop();
                timer.Dispose();
                if (ticket != _resyncTicket) return;
                ServerLauncher.Log("[dpi] resync after " + reason);
                await SyncScaleFromWebViewAsync(forceFit: true);
            };
            timer.Start();
        }

        /// <summary>把实测缩放应用到标题栏（高度 + 字号 + 按钮），必要时同步窗口大小。</summary>
        /// <param name="forceFit">
        /// 跨屏后强制把窗口夹进新屏的工作区。<paramref name="_userResized"/> 只保护
        /// 「同一块屏上的自动放大」，从 4K 拖到 1080p 时 3672px 宽的窗口在新屏根本放不下，
        /// 必须强制适配，否则窗口一半在屏外。
        /// </param>
        private void ApplyUiScale(float dpr, bool forceFit = false)
        {
            // dpr 合理性夹取：正常桌面环境落在 1.0–3.0；收到异常值（0.5/6）时宁可按 1 处理，
            // 也不要让标题栏变成一条巨物或细线。
            if (dpr < 0.8f) dpr = 0.8f;
            if (dpr > 3.5f) dpr = 3.5f;

            _uiScale = dpr;
            int barH = (int)(TitleBarCss * dpr);
            // 这里**故意不加**「按屏高百分比封顶」的保护：试过两版都误伤——
            // 用 ClientSize 时布局未完成读到旧值（4K 的 108 被压成 82），
            // 用 Screen.FromRectangle 时窗口刚被 resize 到 4K 尺寸、Bounds 跨屏了，
            // 取到的却是 1080p 那块屏（108 被压成 85）。而它本来要防的
            // 「dpr 读错导致标题过高」，已经由构造期夹取 + 跨屏 WM_DPICHANGED 重算
            // + 连读两次 dpr 三层覆盖，不需要再叠一条会误伤的兜底。
            bool barChanged = _titleBar.Height != barH;
            _titleBar.Height = barH;
            _titleBar.SetScale(dpr);
            ServerLauncher.Log("[dpi] apply uiScale=" + dpr + " barH=" + barH + (barChanged ? " (changed)" : ""));

            // 窗口尺寸同步校正：用户手动调过就不再抢（否则会把他的窗口大小改回去）。
            // 跨屏（forceFit）例外——新屏装不下旧尺寸，必须重排。
            if (_userResized && !forceFit) return;
            var work = Screen.FromRectangle(Bounds).WorkingArea;
            // 目标窗口用**网页视口**算：dpr 已由 WebView2 给出，内容区 CSS 宽 = 物理 / dpr。
            // 这里要的是「平均一台机器上 1240 CSS 的内容区」，即物理 1240 × dpr。
            int w = Math.Min((int)(PreferredWidth * dpr), work.Width * 9 / 10);
            int h = Math.Min((int)(PreferredHeight * dpr), work.Height * 9 / 10);
            // 4K/300% 屏上 1240×3 = 3720 已超过工作区 90%，取工作区上限即可（内容仍是全宽）。
            w = Math.Max(w, work.Width * 6 / 10);
            h = Math.Max(h, work.Height * 6 / 10);
            // 只在差异明显（>8%）时才动窗口，避免一两像素的抖动
            if (Math.Abs(w - Width) < Width * 0.08 && Math.Abs(h - Height) < Height * 0.08) return;
            var cur = Bounds;
            var next = new Rectangle(
                cur.X + (cur.Width - w) / 2,
                cur.Y + (cur.Height - h) / 2,
                w, h);
            if (next.Right > work.Right) next.X = work.Right - next.Width;
            if (next.Bottom > work.Bottom) next.Y = work.Bottom - next.Height;
            if (next.X < work.X) next.X = work.X;
            if (next.Y < work.Y) next.Y = work.Y;
            Bounds = next;
            ServerLauncher.Log("[dpi] window resized to " + next.Width + "x" + next.Height);
        }

        /// <summary>前端发来的窗口指令。目前的窗口按钮都由 C# 侧直接处理，
        /// 这里只剩 dragStart 一条（保留是因为 HTML 里可能仍有入口）。
        /// </summary>
        private void OnWebMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
        {
            string raw = "";
            try { raw = e.TryGetWebMessageAsString() ?? ""; }
            catch { /* 消息不是字符串（是 JSON 对象），下面再取 */ }
            string json = "";
            try { json = e.WebMessageAsJson ?? ""; }
            catch { /* 同上 */ }
            string cmd = CommandOf(raw.Length > 0 ? raw : json);
            ServerLauncher.Log("[msg] cmd=" + cmd);
            switch (cmd)
            {
                case "minimize":
                    WindowState = FormWindowState.Minimized;
                    break;
                case "toggleMaximize":
                    ToggleMaximize();
                    break;
                case "close":
                    Close();
                    break;
                case "dragStart":
                    BeginNativeDrag();
                    break;
                // 备份落点 / 恢复来源：页面拿不到绝对路径（`<input type=file>` 只给 File 对象），
                // 只有外壳能弹 Windows 公共对话框。选完由 Reply 把路径发回页面。
                case "pickFolder":
                    // 延到消息循环下一拍再弹：WebView2 的消息回调里同步开模态对话框
                    // 会把对话框套在自己的消息泵里，实测偶发「点了没反应」。
                    BeginInvoke((Action)(() => PickPath(true)));
                    break;
                case "pickFile":
                    BeginInvoke((Action)(() => PickPath(false)));
                    break;
            }
        }

        /// <summary>
        /// 弹「选文件夹 / 选文件」对话框，把结果回给页面。
        ///
        /// 为什么必须是外壳做这件事：备份是由后端按**绝对路径**写到那个目录里的，
        /// 而浏览器的文件选择器只交出 File 对象、不给路径字符串。
        ///
        /// **用户点取消也要回一条空路径**：页面那边在等 Promise，
        /// 不回就是按钮永远挂在「选择中…」——这类"看起来卡住"的故障最难自查。
        /// </summary>
        private void PickPath(bool folder)
        {
            string cmd = folder ? "pickedFolder" : "pickedFile";
            string picked = "";
            try
            {
                if (folder)
                {
                    using (var dlg = new FolderBrowserDialog())
                    {
                        dlg.Description = "选择备份存放的文件夹";
                        dlg.ShowNewFolderButton = true;
                        if (dlg.ShowDialog(this) == DialogResult.OK) picked = dlg.SelectedPath;
                    }
                }
                else
                {
                    using (var dlg = new OpenFileDialog())
                    {
                        dlg.Title = "选择备份文件";
                        dlg.Filter = "备份文件 (*.zip;*.json;*.json.gz)|*.zip;*.json;*.gz|所有文件 (*.*)|*.*";
                        dlg.CheckFileExists = true;
                        if (dlg.ShowDialog(this) == DialogResult.OK) picked = dlg.FileName;
                    }
                }
            }
            catch (Exception ex)
            {
                ServerLauncher.Log("[pick] " + ex.Message);
            }
            ReplyPick(cmd, picked);
        }

        /// <summary>选择结果回页面。路径里的反斜杠交给 <see cref="JsonField.JsString"/> 转义。</summary>
        private void ReplyPick(string cmd, string path)
        {
            string json = "{\"cmd\":" + JsonField.JsString(cmd) + ",\"path\":" + JsonField.JsString(path) + "}";
            try
            {
                WebView.CoreWebView2.PostWebMessageAsString(json);
            }
            catch (Exception ex)
            {
                ServerLauncher.Log("[pick] reply failed: " + ex.Message);
            }
        }

        /// <summary>从 `{cmd:"close"}` / `"close"` 两种消息形态里取出指令。</summary>
        private static string CommandOf(string s)
        {
            if (string.IsNullOrEmpty(s)) return "";
            int i = s.IndexOf("cmd", StringComparison.OrdinalIgnoreCase);
            if (i < 0) return s.Trim().Trim('"');
            int colon = s.IndexOf(':', i);
            if (colon < 0) return "";
            int start = s.IndexOf('"', colon);
            if (start < 0) return "";
            int end = s.IndexOf('"', start + 1);
            if (end < 0) return "";
            return s.Substring(start + 1, end - start - 1);
        }

        internal void ToggleMaximize()
        {
            WindowState = WindowState == FormWindowState.Maximized
                ? FormWindowState.Normal
                : FormWindowState.Maximized;
        }

        /// <summary>外部链接（授权页等）交给系统浏览器，不在应用内开新窗。</summary>
        private static void OnNewWindowRequested(object sender, CoreWebView2NewWindowRequestedEventArgs e)
        {
            e.Handled = true;
            // **必须去重**：WebView2 对一次 `window.open(url,'_blank')` 会触发两次
            // NewWindowRequested（窗口创建 + 初始导航各报一次）。不去重的话
            // 点一下「立即更新」会打开两个浏览器标签页（实测日志里同一毫秒两条）。
            // 用「同一 URL + 1.2 秒窗口」判重：正常用户点击间隔远大于这个值。
            ServerLauncher.Log("[external] new-window uri=" + e.Uri);
            if (!ShouldOpenOnce(e.Uri)) return;
            OpenExternal(e.Uri);
        }

        /// <summary>同一 URL 在极短时间内只放行一次（WebView2 会重复上报）。</summary>
        private static string _lastOpenUri;
        private static long _lastOpenMs;
        private const int DedupWindowMs = 1200;

        private static bool ShouldOpenOnce(string uri)
        {
            // 用 DateTime.UtcNow 而不是 Environment.TickCount64：
            // 后者是 .NET Core 才有的 API，本项目跑在 .NET Framework 4.8（csc 编译）上。
            long now = DateTime.UtcNow.Ticks / TimeSpan.TicksPerMillisecond;
            if (uri == _lastOpenUri && now - _lastOpenMs < DedupWindowMs)
            {
                ServerLauncher.Log("[external] dedup-skip uri=" + uri);
                return false;
            }
            _lastOpenUri = uri;
            _lastOpenMs = now;
            ServerLauncher.Log("[external] open uri=" + uri);
            return true;
        }

        /// <summary>
        /// 只允许停留在本地管理面的页面。
        ///
        /// 拖文件进窗口、或页面里有人写了 location.href=外部地址时，
        /// WebView2 会真的去导航——那样外壳就变成一个不受控的浏览器了。
        /// 外部地址一律转给系统浏览器打开。
        /// </summary>
        private void OnNavigationStarting(object sender, CoreWebView2NavigationStartingEventArgs e)
        {
            string local = "http://127.0.0.1:" + Program.UiPort;
            if (e.Uri.StartsWith(local, StringComparison.OrdinalIgnoreCase)) return;
            e.Cancel = true;
            ServerLauncher.Log("[external] nav-blocked uri=" + e.Uri);
            OpenExternal(e.Uri);
        }

        private static void OpenExternal(string uri)
        {
            try
            {
                System.Diagnostics.Process.Start(
                    new System.Diagnostics.ProcessStartInfo(uri) { UseShellExecute = true });
            }
            catch (Exception ex)
            {
                ServerLauncher.Log("open external failed: " + ex.Message);
            }
        }

        // ---------------------------------------------------------------- 诊断钩子

        /// <summary>
        /// 命令行诊断（--dragtest / --screenshot / --eval）。**只用于自动化验证**，
        /// 正常启动时这一段完全不参与。
        ///
        /// 为什么保留：这台机器没有交互桌面（GetForegroundWindow==0、光标恒为 (0,0)），
        /// Robot 注入无效。想验证「拖动是否真的发起了」「页面渲染对不对」，
        /// 只能靠这些从进程内部触发的钩子 + CapturePreview 导出真实像素。
        /// </summary>
        private void HookDiagnostics(CoreWebView2 core)
        {
            if (Program.DragTest)
            {
                core.NavigationCompleted += (s, e) =>
                {
                    ServerLauncher.Log("[dragtest] armed");
                    var t = new Thread(delegate()
                    {
                        Thread.Sleep(4000);
                        try
                        {
                            BeginInvoke((MethodInvoker)delegate { BeginNativeDrag(); });
                            ServerLauncher.Log("[dragtest] issued");
                        }
                        catch (Exception ex)
                        {
                            ServerLauncher.Log("[dragtest] failed: " + ex.Message);
                        }
                    });
                    t.IsBackground = true;
                    t.Start();
                };
            }

            if (Program.ShotPath == null) return;

            // 屏幕 BitBlt / PrintWindow 对 Chromium 合成的画面都会给出空白或别的内容
            // （GPU 合成不走 GDI），只有 CapturePreview 拿到的才是真渲染。
            core.NavigationCompleted += async (s, e) => await CaptureAsync(core);
        }

        private async Task CaptureAsync(CoreWebView2 core)
        {
            await Task.Delay(1800);   // 等首屏心跳把数据填上
            try
            {
                if (Program.ShotPage != null)
                {
                    await core.ExecuteScriptAsync("go(" + JsonField.JsString(Program.ShotPage) + ")");
                    await Task.Delay(1200);
                }
                if (Program.ShotScroll > 0)
                {
                    await core.ExecuteScriptAsync(
                        "document.querySelector('.page-body').scrollTop = " + Program.ShotScroll);
                    await Task.Delay(700);
                }
                if (Program.EvalPath != null && File.Exists(Program.EvalPath))
                {
                    string script = File.ReadAllText(Program.EvalPath);
                    Console.WriteLine("[eval] " + await core.ExecuteScriptAsync(script));
                }
                if (Program.EvalScript != null && Program.EvalOut != null)
                {
                    File.WriteAllText(Program.EvalOut, await core.ExecuteScriptAsync(Program.EvalScript));
                    Console.WriteLine("[shell] eval -> " + Program.EvalOut);
                }
                using (var fs = File.Create(Program.ShotPath))
                {
                    await core.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, fs);
                }
                Console.WriteLine("[shell] screenshot -> " + Program.ShotPath);
            }
            catch (Exception ex)
            {
                Console.WriteLine("[shell] screenshot failed: " + ex.Message);
            }
            // --quit：批量巡检用。CapturePreviewAsync 已 await 完成，这里退出不会截到半截文件；
            // 用 Exit 而非 Close 是为了跳过后端优雅收尾的等待，巡检进程本就不该长期占端口。
            if (Program.QuitAfterShot)
            {
                Console.Out.Flush();
                Environment.Exit(0);
            }
        }
    }

    /// <summary>
    /// 主题文件：`&lt;dataDir&gt;\ui-theme.txt` 里只有一个 `light` / `dark`。
    ///
    /// 谁写它：前端（点标题栏主题按钮时调 `/ui/theme`）。
    /// 谁读它：外壳启动时决定标题栏与窗口底色。
    /// 两边必须一致，否则会出现「页面浅色、标题栏深色」这种一眼可见的割裂。
    /// </summary>
    internal static class ThemeFile
    {
        internal const string FileName = "ui-theme.txt";

        internal static bool IsDark(string dataDir)
        {
            try
            {
                string f = Path.Combine(dataDir, FileName);
                if (!File.Exists(f)) return true;
                return File.ReadAllText(f).Trim() != "light";
            }
            catch
            {
                return true;   // 读不到按深色（默认主题）
            }
        }
    }
}
