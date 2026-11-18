using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace AiGatewayWeb
{
    /// <summary>
    /// 自绘标题栏（WinForms 控件，**不是** HTML）。
    ///
    /// 为什么必须是真实控件而不是 HTML：WebView2 的子窗口铺满它自己的边界，
    /// 凡是被它覆盖的像素，鼠标消息都进 Chromium 进程，父窗口的 WM_NCHITTEST
    /// 永远不会被调用。把标题栏做成 Form 的普通子控件，它不在 WebView2 覆盖范围内，
    /// 所以 NCHITTEST 能返回 HTCAPTION，拖动直接交给系统（Aero Snap / 双击最大化
    /// / 最大化后拖拽还原都由系统完成，不可能掉帧）。
    ///
    /// 配色对齐前端 `.titlebar` 的 CSS 变量，深浅主题各一套。主题切换只由前端发起
    /// （外壳收到 `window.aigwToggleTheme` 后不重绘自己）——标题栏颜色下次启动才变，
    /// 这是刻意的：两边都改会引入「谁先谁后」的竞态，而标题栏只在启动时定色一次。
    /// </summary>
    internal sealed class TitleBarPanel : Panel
    {
        // 逻辑像素尺寸（绘制时按 DPI 缩放）
        private const int ThemeBtnWidth = 30;
        private const int WindowBtnWidth = 34;
        private const int ButtonHeight = 24;
        private const int ButtonGap = 2;
        private const int RightPadding = 6;
        private const int DotX = 16;
        private const int DotSize = 9;
        private const int TitleGap = 16;

        /// <summary>四个按钮的索引：0=主题 1=最小化 2=最大化 3=关闭。</summary>
        private const int BtnTheme = 0;
        private const int BtnMin = 1;
        private const int BtnMax = 2;
        private const int BtnClose = 3;
        private const int ButtonCount = 4;

        /// <summary>按钮实际占位（= 逻辑值 × 真实缩放，SetScale 时重算）。</summary>
        private int _themeW;
        private int _btnW;
        private int _btnH;

        /// <summary>
        /// 真实 DPI 缩放（96 基准），由宿主窗口在构造时注入。
        ///
        /// 不能用控件自己的 <c>DeviceDpi</c>：Panel 在 ShellWindow 构造函数里创建，
        /// 那时尚未挂到已显示的窗口上，net48 PerMonitorV2 下恒返回 96——
        /// 高缩放屏上标题文字与按钮会按 100% 尺寸绘制，比内容区明显小一号
        /// （用户实测：窗口内容正常、标题小一圈，就是它）。
        /// </summary>
        private float _scale = 1f;

        /// <summary>宿主窗口在显示前注入真实屏幕缩放（见 Win32.ScreenScaleAt）。</summary>
        internal void SetScale(float scale)
        {
            _scale = scale < 0.5f ? 0.5f : scale;
            // 尺寸字段是 readonly 的逻辑值，绘制与命中判定全部经 ScaleDip 换算，
            // 这里只需按新比例重算实际占位，让 Dock 高度与按钮布局同步刷新。
            _themeW = (int)(ThemeBtnWidth * _scale);
            _btnW = (int)(WindowBtnWidth * _scale);
            _btnH = (int)(ButtonHeight * _scale);
            Invalidate();
        }

        private Color _bg, _fg, _fg2, _line, _brand, _hover;

        private int _hoverIndex = -1;
        private int _pressIndex = -1;

        /// <summary>宿主窗口。拖动与窗口操作都通过它执行，控件本身不碰 Form 状态。</summary>
        internal Form Owner { get; set; }

        internal TitleBarPanel(bool dark)
        {
            if (dark)
            {
                _bg = Color.FromArgb(0x2E, 0x2E, 0x2E);
                _fg = Color.FromArgb(0xF5, 0xF5, 0xF5);
                _fg2 = Color.FromArgb(0x8A, 0x8A, 0x8A);
                _line = Color.FromArgb(0x24, 0xF5, 0xF5, 0xF5);
                _brand = Color.FromArgb(0x25, 0x72, 0xE6);
                _hover = Color.FromArgb(0x1A, 0xF5, 0xF5, 0xF5);
            }
            else
            {
                _bg = Color.White;
                _fg = Color.FromArgb(0x0D, 0x0D, 0x0D);
                _fg2 = Color.FromArgb(0x6B, 0x6B, 0x6B);
                _line = Color.FromArgb(0x1F, 0x0D, 0x0D, 0x0D);
                _brand = Color.FromArgb(0x1F, 0x69, 0xE0);
                _hover = Color.FromArgb(0x14, 0x0D, 0x0D, 0x0D);
            }

            DoubleBuffered = true;
            SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.AllPaintingInWmPaint, true);
            Cursor = Cursors.Hand;
            // 默认按 100% 占位；真实缩放由 ShellWindow 通过 SetScale 注入后重算
            _themeW = ThemeBtnWidth;
            _btnW = WindowBtnWidth;
            _btnH = ButtonHeight;
        }

        /// <summary>
        /// 四个按钮加间距占用的总宽度（逻辑像素）。
        ///
        /// 抽成静态方法给 ShellWindow 用：WM_NCHITTEST 判定「按钮区」时需要知道
        /// 按钮区从哪儿开始，而它不能实例化控件去问。两边算同一个公式，避免
        /// 改宽度时漏改一处导致按钮区域算错（那样按钮会点不动或拖动区变窄）。
        /// </summary>
        internal static int TotalButtonsWidth(float scale)
        {
            return (int)((RightPadding + ThemeBtnWidth + ButtonGap + WindowBtnWidth * 3) * scale);
        }

        private int BtnX(int index)
        {
            int right = ClientSize.Width - ScaleDip(RightPadding);
            switch (index)
            {
                case BtnClose: return right - _btnW;
                case BtnMax: return right - _btnW * 2;
                case BtnMin: return right - _btnW * 3;
                default: return right - _btnW * 3 - _themeW - ScaleDip(ButtonGap);
            }
        }

        private int BtnWFor(int index)
        {
            return index == BtnTheme ? _themeW : _btnW;
        }

        private int ScaleDip(int dip)
        {
            return (int)(dip * _scale);
        }

        private int HitButton(int x, int y)
        {
            int top = (ClientSize.Height - _btnH) / 2;
            if (y < top || y > top + _btnH) return -1;
            for (int i = 0; i < ButtonCount; i++)
            {
                int bx = BtnX(i);
                int bw = BtnWFor(i);
                if (x >= bx && x < bx + bw) return i;
            }
            return -1;
        }

        // ---------------------------------------------------------------- 鼠标

        protected override void OnMouseMove(MouseEventArgs e)
        {
            base.OnMouseMove(e);
            // 拖动的兜底路径。
            // 判据只看「左键是否按下」——不能加 `&& _dragWatchMoved`，
            // 那个标志第一次调用时才被置 true，加了就永远进不来（本版踩过）。
            // 两条路径天然互斥：系统拖动循环一旦接管，鼠标消息被系统模态循环吃掉，
            // 根本不会再送到本控件的 OnMouseMove；只有它没接管时这里才会执行。
            if ((e.Button & MouseButtons.Left) != 0) FallbackMove(e);

            int idx = HitButton(e.X, e.Y);
            if (idx != _hoverIndex)
            {
                _hoverIndex = idx;
                Cursor = idx >= 0 ? Cursors.Hand : Cursors.Default;
                Invalidate();
            }
        }

        protected override void OnMouseLeave(EventArgs e)
        {
            base.OnMouseLeave(e);
            _hoverIndex = -1;
            _pressIndex = -1;
            Cursor = Cursors.Default;
            Invalidate();
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            if (e.Button != MouseButtons.Left) return;
            ServerLauncher.Log("[titlebar] MouseDown at " + e.X + "," + e.Y);

            int idx = HitButton(e.X, e.Y);
            if (idx >= 0)
            {
                _pressIndex = idx;
                Invalidate();
                return;   // 按钮区不发起拖动
            }

            // 空白区：主动发起系统拖动循环。
            // 在 OnMouseDown 里显式 ReleaseCapture + WM_NCLBUTTONDOWN(HTCAPTION)
            // 是自绘标题栏的标准做法（DefWindowProc 会进入模态移动循环，
            // 后续光标跟踪 / Aero Snap / 双击最大化 / 最大化后拖拽还原全由系统完成）。
            var shell = Owner as ShellWindow;
            if (shell != null) shell.BeginNativeDrag();

            // 兜底判定的起点：如果系统循环没接管（远程桌面/无交互桌面下会发生），
            // 就退回到「自己跟手移动」，保证至少能拖。
            _dragOrigin = new Point(e.X, e.Y);
            _dragWindowOrigin = Owner == null ? Point.Empty : Owner.Location;
            _dragActive = true;
        }

        private Point _dragOrigin;
        private Point _dragWindowOrigin;
        private bool _dragActive;

        /// <summary>
        /// 兜底：系统拖动循环若未接管，鼠标仍会继续送到本控件（捕获在本进程）。
        /// 一旦检测到位移就自己挪窗口——上限是「能用」，不是「好用」。
        /// </summary>
        private void FallbackMove(MouseEventArgs e)
        {
            var owner = Owner;
            if (owner == null || !_dragActive) return;
            if (owner.WindowState != FormWindowState.Normal) return;
            int dx = e.X - _dragOrigin.X;
            int dy = e.Y - _dragOrigin.Y;
            if (dx == 0 && dy == 0) return;
            owner.Location = new Point(_dragWindowOrigin.X + dx, _dragWindowOrigin.Y + dy);
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            base.OnMouseUp(e);
            _dragActive = false;

            int idx = _pressIndex;
            _pressIndex = -1;
            Invalidate();
            if (idx < 0 || Owner == null) return;

            switch (idx)
            {
                case BtnClose:
                    // 只关窗口，后台服务与托盘图标都保留。
                    // 原来这里调 Owner.Close()，但 Close() 的 CloseReason 是 None，
                    // FormClosing 里那条「UserClosing 才收进托盘」的分支不命中，
                    // 结果是直接真退出、后端进程一起被杀。改走显式方法。
                    var shellClose = Owner as ShellWindow;
                    if (shellClose != null) shellClose.HideToTray();
                    else Owner.Close();
                    break;
                case BtnMax:
                    var shellMax = Owner as ShellWindow;
                    if (shellMax != null) shellMax.ToggleMaximize();
                    break;
                case BtnMin:
                    Owner.WindowState = FormWindowState.Minimized;
                    break;
                case BtnTheme:
                    ToggleTheme();
                    break;
            }
        }

        protected override void OnMouseDoubleClick(MouseEventArgs e)
        {
            base.OnMouseDoubleClick(e);
            if (Owner == null) return;
            // 双击按钮不应触发最大化（用户连点两次关闭按钮时尤其明显）
            if (HitButton(e.X, e.Y) >= 0) return;
            var shell = Owner as ShellWindow;
            if (shell != null) shell.ToggleMaximize();
        }

        /// <summary>
        /// 主题切换：交给前端做，配色只由前端负责。
        ///
        /// 这样两边永远不会出现「一边深一边浅」——前端切完会通过 `/ui/theme`
        /// 把选择落盘，下次启动标题栏按新主题绘制。
        /// </summary>
        private void ToggleTheme()
        {
            var shell = Owner as ShellWindow;
            if (shell == null || shell.WebView == null || shell.WebView.CoreWebView2 == null) return;
            shell.WebView.CoreWebView2.ExecuteScriptAsync(
                "window.aigwToggleTheme && window.aigwToggleTheme()");
        }

        // ---------------------------------------------------------------- 绘制

        protected override void OnPaint(PaintEventArgs e)
        {
            var g = e.Graphics;
            g.Clear(_bg);
            g.SmoothingMode = SmoothingMode.AntiAlias;

            int midY = ClientSize.Height / 2;
            int dotX = ScaleDip(DotX);

            // 品牌点
            using (var b = new SolidBrush(_brand))
            {
                int d = ScaleDip(DotSize);
                g.FillEllipse(b, dotX, midY - d / 2, d, d);
            }

            // 标题
            // 标题字号与内容区正文对齐：13px CSS × 屏幕缩放。
            // 用 px 单位的 Font 构造（(int)px 视为 pixel），不受系统 pt→px 换算干扰。
            float fontSize = 13f * _scale;
            using (var f = new Font("Microsoft YaHei UI", fontSize, FontStyle.Bold, GraphicsUnit.Pixel))
            using (var b = new SolidBrush(_fg))
            {
                string title = "AI Gateway";
                SizeF sz = g.MeasureString(title, f);
                g.DrawString(title, f, b, dotX + ScaleDip(TitleGap), midY - sz.Height / 2f + 1f);
            }

            PaintButtons(g, midY);

            // 底部分隔线：与页面内容之间需要一条边界，否则标题栏和卡片区糊在一起
            using (var pen = new Pen(_line, 1f))
            {
                g.DrawLine(pen, 0, ClientSize.Height - 1, ClientSize.Width, ClientSize.Height - 1);
            }
        }

        private void PaintButtons(Graphics g, int midY)
        {
            int top = (ClientSize.Height - _btnH) / 2;
            for (int i = 0; i < ButtonCount; i++)
            {
                int bx = BtnX(i);
                int bw = BtnWFor(i);
                var rect = new Rectangle(bx, top, bw, _btnH);
                bool hot = _hoverIndex == i;
                bool pressed = _pressIndex == i;

                if (hot)
                {
                    // 关闭按钮悬停用危险色，其余用中性高亮：这是 Windows 的通用约定
                    Color fill = (i == BtnClose)
                        ? Color.FromArgb(0x33, 0xD6, 0x3D, 0x3D)
                        : _hover;
                    using (var sb = new SolidBrush(fill)) g.FillRectangle(sb, rect);
                }

                using (var pen = new Pen(pressed ? Color.White : _fg2, 1.2f))
                {
                    int cx = rect.X + bw / 2;
                    int cy = top + _btnH / 2;
                    int arm = ScaleDip(5);
                    switch (i)
                    {
                        case BtnTheme:
                            g.DrawEllipse(pen, cx - arm, cy - arm, arm * 2, arm * 2);
                            break;
                        case BtnMin:
                            g.DrawLine(pen, cx - arm, cy, cx + arm, cy);
                            break;
                        case BtnMax:
                            g.DrawRectangle(pen, cx - arm, cy - arm, arm * 2, arm * 2);
                            break;
                        case BtnClose:
                            g.DrawLine(pen, cx - arm, cy - arm, cx + arm, cy + arm);
                            g.DrawLine(pen, cx + arm, cy - arm, cx - arm, cy + arm);
                            break;
                    }
                }
            }
        }
    }
}
