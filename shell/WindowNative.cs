using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace AiGatewayWeb
{
    /// <summary>
    /// 命中判定：给定**客户区坐标**决定返回哪个 HT 常量。
    ///
    /// 三段区域，归属不同：
    /// 1. 四周 8px 缩放带 → HTLEFT/HTRIGHT/…（Form 自己的客户区负责；
    ///    WebView2 被 Padding 内缩，收不到这一圈的事件）
    /// 2. 顶部标题栏带、且不在窗口按钮矩形内 → HTCAPTION
    ///    ⇒ DefWindowProc 直接进入**系统拖动循环**（Aero Snap、双击最大化、
    ///    最大化后拖拽还原全部由系统完成）
    /// 3. 窗口按钮矩形内 → HTCLIENT（事件继续交给 TitleBarPanel 的鼠标处理）
    ///
    /// 抽成静态纯函数是为了可自测：跨进程 SendMessage(WM_NCHITTEST) 不可靠
    /// （Windows 会拿 lParam 与真实光标位置比对，不匹配就回 HTNOWHERE(0)）。
    ///
    /// 这是本版「拖动不生效」的最终解法的核心：**坐标必须由调用方从 lParam 取**，
    /// 不能用 Cursor.Position——系统在贴边吸附、键盘触发、DPI 切换时也会发本消息，
    /// 那时光标位置与待测点无关，判定就会全错。
    /// </summary>
    internal static class HitTest
    {
        internal const int HTCLIENT = 1;
        internal const int HTCAPTION = 2;
        internal const int HTLEFT = 10;
        internal const int HTRIGHT = 11;
        internal const int HTTOP = 12;
        internal const int HTTOPLEFT = 13;
        internal const int HTTOPRIGHT = 14;
        internal const int HTBOTTOM = 15;
        internal const int HTBOTTOMLEFT = 16;
        internal const int HTBOTTOMRIGHT = 17;

        /// <summary>缩放带的逻辑宽度（会按 DPI 放大）。</summary>
        internal const int EdgeDip = 8;

        internal static int Decide(Point p, Size client, float scale, bool maximized,
            int titleBarHeight, int buttonLeft)
        {
            if (p.X < 0 || p.Y < 0 || p.X >= client.Width || p.Y >= client.Height) return HTCLIENT;

            int band = (int)(EdgeDip * scale);
            bool left = p.X <= band;
            bool right = p.X >= client.Width - band;
            bool top = p.Y <= band;
            // 最大化时底部边缘不再当缩放条：那会让任务栏区域误触发
            bool bottom = !maximized && p.Y >= client.Height - band;

            // 角优先：角上同时命中两条边时取角
            if (top && left) return HTTOPLEFT;
            if (top && right) return HTTOPRIGHT;
            if (bottom && left) return HTBOTTOMLEFT;
            if (bottom && right) return HTBOTTOMRIGHT;
            if (left) return HTLEFT;
            if (right) return HTRIGHT;
            if (top) return HTTOP;
            if (bottom) return HTBOTTOM;

            // 标题栏：按钮区交给控件，其余交给系统（拖动 / 双击最大化）
            if (p.Y < titleBarHeight)
            {
                return p.X >= buttonLeft ? HTCLIENT : HTCAPTION;
            }
            return HTCLIENT;
        }

        /// <summary>从 WM_NCHITTEST 的 lParam 取屏幕坐标（低字 X、高字 Y，均按无符号 16 位）。</summary>
        internal static Point ScreenPointFromLParam(IntPtr lParam)
        {
            long raw = lParam.ToInt64();
            int x = (short)(raw & 0xFFFF);
            int y = (short)((raw >> 16) & 0xFFFF);
            return new Point(x, y);
        }
    }

    /// <summary>
    /// 窗口级 Win32 互操作：只保留真正用到的几个，集中在一处便于核对签名。
    /// </summary>
    internal static class Win32
    {
        internal const int WM_NCCALCSIZE = 0x0083;
        internal const int WM_NCHITTEST = 0x0084;
        internal const int WM_GETMINMAXINFO = 0x0024;
        internal const int WM_NCLBUTTONDOWN = 0x00A1;
        internal const int WM_EXITSIZEMOVE = 0x0232;

        /// <summary>窗口被移到 DPI 不同的显示器（PerMonitorV2 下必须重算标题栏）。</summary>
        internal const int WM_DPICHANGED = 0x02E0;

        internal const int SM_CXSIZEFRAME = 32;
        internal const int SM_CYSIZEFRAME = 33;
        internal const int SM_CXPADDEDBORDER = 92;

        [StructLayout(LayoutKind.Sequential)]
        internal struct RECT
        {
            public int left;
            public int top;
            public int right;
            public int bottom;
        }

        [DllImport("user32.dll")]
        internal static extern int GetSystemMetrics(int index);

        [DllImport("user32.dll")]
        internal static extern bool ReleaseCapture();

        [DllImport("user32.dll")]
        internal static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);

        internal const int MONITOR_DEFAULTTONEAREST = 2;

        [StructLayout(LayoutKind.Sequential)]
        internal struct POINT
        {
            public int x;
            public int y;
            public POINT(int px, int py) { x = px; y = py; }
        }

        [DllImport("user32.dll")]
        internal static extern IntPtr MonitorFromPoint(POINT pt, uint flags);

        /// <summary>有效 DPI（PerMonitorV2 进程内返回该显示器真实 DPI；旧系统返回 96）。
        /// 注意在 shcore.dll 而非 user32.dll（放错库会 EntryPointNotFound 静默回落）。</summary>
        [DllImport("shcore.dll")]
        internal static extern uint GetDpiForMonitor(IntPtr hmonitor, int dpiType, out uint dpiX, out uint dpiY);

        /// <summary>
        /// 光标所在显示器的真实 DPI。
        ///
        /// 构造阶段 `Form.DeviceDpi` 恒为 96（句柄未建、WinForms 未感知屏幕），
        /// 直接用它换算窗口尺寸在高缩放屏上会得到一个按倍数缩小的小窗口
        /// （实测 300% 屏 Bounds=1240 → 413 物理）。必须问显示器本身。
        /// MDT_EFFECTIVE_DPI = 2，与 WebView2 的 RasterizationScale 同源。
        /// </summary>
        internal static float ScreenScaleAt(Point p)
        {
            try
            {
                var mon = MonitorFromPoint(new POINT(p.X, p.Y), MONITOR_DEFAULTTONEAREST);
                uint dx, dy;
                if (mon != IntPtr.Zero && GetDpiForMonitor(mon, 2, out dx, out dy) == 0 && dx > 0)
                {
                    return dx / 96f;
                }
            }
            catch { /* 旧系统没有该 API：按 100% 处理 */ }
            return 1f;
        }
    }

    /// <summary>
    /// 进程内自测：核对命中判定表。`--selftest &lt;path&gt;` 启动时执行并退出。
    ///
    /// 为什么需要它：窗口交互只能在有交互桌面的机器上人工验证，而这台机器
    /// `GetForegroundWindow()==0`、光标恒为 (0,0)，Robot 注入无效。判定表是纯函数，
    /// 把边界条件（角、最大化后的底边、按钮区）固化成用例，至少保证逻辑不会改错。
    /// </summary>
    internal static class SelfTest
    {
        internal static bool Run(string outPath)
        {
            var sb = new System.Text.StringBuilder();
            Action<string> log = line => sb.AppendLine(line);

            // 150% 缩放下的典型客户区
            var size = new Size(827, 547);
            const float scale = 1.5f;
            int barH = 50;
            int buttonLeft = size.Width - 150;

            string[] names =
            {
                "edge-left", "edge-right", "edge-top", "edge-bottom",
                "corner-topleft", "corner-topright", "corner-botleft", "corner-botright",
                "content-center", "titlebar-band", "button-zone",
            };
            Point[] points =
            {
                new Point(2, 300), new Point(size.Width - 2, 300),
                new Point(300, 2), new Point(300, size.Height - 2),
                new Point(2, 2), new Point(size.Width - 2, 2),
                new Point(2, size.Height - 2), new Point(size.Width - 2, size.Height - 2),
                new Point(450, 400), new Point(300, 20), new Point(size.Width - 60, 20),
            };
            int[] want =
            {
                HitTest.HTLEFT, HitTest.HTRIGHT, HitTest.HTTOP, HitTest.HTBOTTOM,
                HitTest.HTTOPLEFT, HitTest.HTTOPRIGHT, HitTest.HTBOTTOMLEFT, HitTest.HTBOTTOMRIGHT,
                HitTest.HTCLIENT,
                HitTest.HTCAPTION,   // 标题栏空白 → 系统拖动
                HitTest.HTCLIENT,    // 窗口按钮区 → 控件自己处理
            };

            int pass = 0;
            for (int i = 0; i < names.Length; i++)
            {
                int got = HitTest.Decide(points[i], size, scale, false, barH, buttonLeft);
                bool ok = got == want[i];
                if (ok) pass++;
                log(string.Format("[selftest] {0,-18} got={1,3} want={2,3} {3}",
                    names[i], got, want[i], ok ? "PASS" : "FAIL"));
            }

            // 最大化态：顶边/上角仍可缩放，底边不再当缩放条
            int mTop = HitTest.Decide(new Point(400, 2), size, scale, true, barH, buttonLeft);
            int mBottom = HitTest.Decide(new Point(400, size.Height - 2), size, scale, true, barH, buttonLeft);
            int mTitle = HitTest.Decide(new Point(300, 20), size, scale, true, barH, buttonLeft);
            bool mOk = mTop == HitTest.HTTOP && mBottom == HitTest.HTCLIENT && mTitle == HitTest.HTCAPTION;
            if (mOk) pass++;
            log(string.Format(
                "[selftest] maximized          top={0}(want{1}) bottom={2}(want{3}) title={4}(want{5}) {6}",
                mTop, HitTest.HTTOP, mBottom, HitTest.HTCLIENT, mTitle, HitTest.HTCAPTION,
                mOk ? "PASS" : "FAIL"));

            int total = names.Length + 1;
            log(string.Format("[selftest] {0} / {1} passed", pass, total));
            if (outPath != null)
            {
                try { System.IO.File.WriteAllText(outPath, sb.ToString()); }
                catch (Exception ex) { Console.WriteLine("[selftest] write failed: " + ex.Message); }
            }
            return pass == total;
        }
    }
}
