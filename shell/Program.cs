using System;
using System.IO;
using System.Windows.Forms;

namespace AiGatewayWeb
{
    /// <summary>
    /// AI Gateway 的 WebView2 外壳入口。
    ///
    /// 职责只有三件：拉起后端子进程、开一个窗口把 WebView2 指过去、把窗口交互交给系统。
    /// 网关逻辑全在 :server 模块（Kotlin，复用原有 core 引擎与加密存储），这里不碰任何业务。
    ///
    /// 为什么窗口交互不自己实现：旧 Swing 版自绘无边框窗口，每个鼠标事件都要自己算坐标、
    /// 自己调 SetWindowPos，结果是拖动掉帧、位置漂移、「拖两下拖不动」。这里全部交给系统：
    /// 缩放与贴边吸附 → 保留 WS_THICKFRAME + WM_NCCALCSIZE；标题栏拖动 → HTCAPTION。
    ///
    /// 文件划分（原先 1000 行全在 Program.cs 里）：
    /// - `ShellArgs.cs`        命令行参数、路径比较、JSON 取值、端口文件
    /// - `ServerLauncher.cs`   后端子进程启停与健康检查
    /// - `WindowNative.cs`     命中判定、Win32 互操作、自测
    /// - `ShellWindow.cs`      主窗口、WebView2 初始化、诊断钩子
    /// - `TitleBar.cs`         自绘标题栏控件
    /// </summary>
    internal static class Program
    {
        /// <summary>
        /// UI API 端口。后端按数据目录派生（默认目录 8792，派生目录换到 18xxx），
        /// 并把结果写进 `&lt;dataDir&gt;\.instance.json`；外壳启动后读这个文件来定端口。
        /// 因此两个不同数据目录的实例可以同时开着，互不抢端口。
        /// </summary>
        internal static int UiPort = DefaultUiPort;

        /// <summary>默认端口：端口文件缺失/损坏时的回落值（= 默认数据目录的历史端口）。</summary>
        internal const int DefaultUiPort = 8792;

        /// <summary>--screenshot &lt;path&gt;：页面渲染完成后把画面导出到该路径（验证用）。</summary>
        internal static string ShotPath;

        /// <summary>--page &lt;key&gt;：截图前先切到指定页面。</summary>
        internal static string ShotPage;

        /// <summary>--scroll &lt;px&gt;：截图前把内容区滚动到指定位置。</summary>
        internal static int ShotScroll;

        /// <summary>--eval &lt;path&gt;：截图前执行该 JS 文件并打印返回值（布局诊断）。</summary>
        internal static string EvalPath;

        /// <summary>--eval &lt;path&gt; --eval-out &lt;path&gt;：执行 JS 并把结果写入文件。</summary>
        internal static string EvalScript;
        internal static string EvalOut;

        /// <summary>--dragtest：加载完成后自动发起一次原生拖动（验证用）。</summary>
        internal static bool DragTest;

        /// <summary>
        /// --force-dpr &lt;n&gt;：强制按给定 devicePixelRatio 定稿缩放，忽略真实 dpr。
        ///
        /// 手边只有一块屏、一种缩放时，无法验证别的缩放档；这个诊断参数能在任何机器上
        /// 模拟 1 / 1.5 / 2 / 3 四档，用于确认标题栏与窗口在各档都不失衡。
        /// 只在诊断运行里生效（正常启动为 0）。
        /// </summary>
        internal static float ForceDpr;

        /// <summary>
        /// --quit：截图/求值完成后立刻退出。
        /// 批量页面巡检必须带它：正常路径是 Application.Run 消息循环，不关窗就不会返回，
        /// 循环里逐个启动会全部挂住（实测 11 页巡检只出第 1 张、进程累积 3 个以上）。
        /// </summary>
        internal static bool QuitAfterShot;

        /// <summary>
        /// 无交互运行（给了诊断参数）。
        /// 窗口层也要看它：`InitWebView` 失败时同样不能弹模态框，否则会挂住。
        /// </summary>
        internal static bool Headless;

        /// <summary>当前实例的 UI 地址。窗口导航与外部链接白名单共用它。</summary>
        internal static string UiUrl()
        {
            return "http://127.0.0.1:" + UiPort + "/";
        }

        [STAThread]
        private static void Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            var opts = ShellArgs.Parse(args);
            Headless = opts.Headless;
            ApplyDiagnosticOptions(opts);

            string dataDir = PathUtil.ResolveDataDir(opts.DataDir);
            Directory.CreateDirectory(dataDir);
            ServerLauncher.LogPath = Path.Combine(dataDir, "shell-launcher.log");
            ServerLauncher.Log("=== shell start pid="
                + System.Diagnostics.Process.GetCurrentProcess().Id + " ===");

            // 自测分支：只验证命中判定表，不启动后端、不开窗
            if (opts.SelfTestOut != null)
            {
                Environment.ExitCode = SelfTest.Run(opts.SelfTestOut) ? 0 : 1;
                return;
            }

            // 端口解析顺序：--port > <dataDir>\.instance.json > 默认端口。
            //
            // **followPortFile 判的是「有没有显式给端口」，不是「文件在不在」**。
            // 这两者不是一回事：全新数据目录第一次启动时文件还不存在，
            // 那时仍必须跟随后端写出的新文件——把它当成「不跟随」，
            // 外壳会一直盯默认的 8792 而永远等不到后端（本轮实测踩到：
            // 冷启动卡满 90 秒超时，窗口一个都不出）。
            bool followPortFile = opts.Port <= 0;
            if (followPortFile)
            {
                int filePort = InstancePortFile.ReadUiPort(dataDir);
                // 没有文件就先用默认值：它只是「等待期间猜的起点」，
                // 真正的端口由下面 WaitReady 每轮重读文件拿到。
                UiPort = filePort > 0 ? filePort : DefaultUiPort;
            }
            else
            {
                // 显式给了 --port：直接用它，且**不再读文件**（读文件会把它覆盖掉）。
                // 这一行曾经在拆分文件时丢掉，表现是 `--port 18138` 完全不生效、
                // 外壳去连默认 8792 然后报「后端启动即退出」——诊断参数无效比崩溃更难查。
                UiPort = opts.Port;
                ServerLauncher.Log("[args] 使用显式端口 " + UiPort);
            }

            // 端口文件可能是上一轮留下的：先删，再等后端写出新文件。
            // 不删的话会把旧端口当成新实例的地址，一直连不上直到超时。
            if (followPortFile && !ServerLauncher.IsAliveFor(dataDir, UiPort))
            {
                InstancePortFile.Delete(dataDir);
            }

            bool weStartedIt = false;
            if (!ServerLauncher.IsAliveFor(dataDir, UiPort))
            {
                weStartedIt = ServerLauncher.TryStart(dataDir);
                if (!weStartedIt)
                {
                    Fail(opts,
                        "未能启动后端服务。\n\n请先在项目目录执行：\n" +
                        "    gradlew :server:installDist\n\n" +
                        "或确认 runtime\\bin\\javaw.exe 与 app\\aigw-server.jar 已随包安装。",
                        ExitNoBackend);
                    return;
                }
                // 端口由后端派生时每轮重读文件：它算出的端口可能与本轮文件里的值不同
                var wait = ServerLauncher.WaitReady(dataDir, delegate
                {
                    if (!followPortFile) return UiPort;
                    int p = InstancePortFile.ReadUiPort(dataDir);
                    if (p > 0 && p != UiPort)
                    {
                        UiPort = p;
                        ServerLauncher.Log("[wait] 后端端口 -> " + p);
                    }
                    return UiPort;
                });

                // 后端「刚启动就退出」最常见的原因是上一个实例还在收尾、
                // 数据目录的文件锁没放开（两个进程重叠的那一两秒）。
                // 这种情况重试一次基本都能成，不必让用户看到失败提示。
                if (wait == ServerLauncher.WaitResult.Exited)
                {
                    ServerLauncher.Log("[wait] 后端启动即退出，1.5 秒后重试一次（可能是上一实例未收尾）");
                    System.Threading.Thread.Sleep(1500);
                    if (ServerLauncher.IsAliveFor(dataDir, UiPort))
                    {
                        RunWindow(dataDir, weStartedIt);
                        return;
                    }
                    weStartedIt = ServerLauncher.TryStart(dataDir) || weStartedIt;
                    if (weStartedIt)
                    {
                        wait = ServerLauncher.WaitReady(dataDir, delegate
                        {
                            if (!followPortFile) return UiPort;
                            int p = InstancePortFile.ReadUiPort(dataDir);
                            if (p > 0 && p != UiPort) UiPort = p;
                            return UiPort;
                        });
                    }
                }

                if (wait != ServerLauncher.WaitResult.Ready)
                {
                    ServerLauncher.Log("[wait] " + wait + "：dataDir=" + dataDir + " port=" + UiPort);
                    string head = wait == ServerLauncher.WaitResult.Exited
                        ? "后端进程已退出（不是超时）。\n\n常见原因：数据目录被另一个实例锁住、"
                          + "端口被占用、或凭证存储损坏。\n\n"
                        : "后端服务启动超时（90 秒）。\n\n";
                    Fail(opts,
                        head + "数据目录：" + dataDir + "\n" +
                        "诊断日志：<数据目录>\\shell-launcher.log",
                        ExitNoBackend);
                    if (weStartedIt) ServerLauncher.Stop();
                    return;
                }
            }

            RunWindow(dataDir, weStartedIt);
        }

        private const int ExitNoBackend = 3;
        private const int ExitWindowCrashed = 4;

        /// <summary>开窗并进入消息循环；窗口返回后按需收尾后端。</summary>
        private static void RunWindow(string dataDir, bool weStartedIt)
        {
            try
            {
                var win = new ShellWindow(dataDir);
                // **不能用 Application.Run(new Form())**：那个重载会在
                // 「所有窗体都不可见」时自动结束消息循环——于是点 X 收进托盘
                // （窗口 Hide）之后循环立刻返回，finally 把后端杀掉，整个进程退出。
                // 这正是「点 X 后台服务就没了」的直接原因，比 CloseReason 那层更根本。
                // 改用 ApplicationContext 持有窗体：循环的退出条件变成「显式 ExitThread」，
                // 与窗口可见性彻底解耦。
                var ctx = new TrayAppContext(win);
                Application.Run(ctx);
            }
            catch (Exception ex)
            {
                // 消息循环里崩了不能只是消失：至少把原因落盘，并把退出码标出来
                ServerLauncher.Log("[fatal] 窗口异常：" + ex);
                Environment.ExitCode = ExitWindowCrashed;
            }
            finally
            {
                if (weStartedIt) ServerLauncher.Stop();
            }
        }

        /// <summary>
        /// 让消息循环活到「托盘菜单退出」为止。
        ///
        /// 默认的 `Application.Run(Form)` 有三条隐式退出条件，窗口隐藏正好命中：
        /// 主窗体关闭 / 最后一个窗体关闭 / 所有窗体都不可见。桌面常驻型程序
        /// （关窗=收托盘、后台继续服务）必须绕开第三条，只留一条显式出口。
        /// </summary>
        private sealed class TrayAppContext : ApplicationContext
        {
            private readonly ShellWindow _win;

            internal TrayAppContext(ShellWindow win)
            {
                _win = win;
                // 窗体把「真的要退出」的时机告诉我们：托盘菜单退出时它已经
                // 标了 _reallyExit，会把 Closed 发出来，这里跟着结束循环。
                _win.FormClosed += delegate { ExitThread(); };
                _win.Show();
            }
        }

        /// <summary>
        /// 启动失败的统一出口。
        ///
        /// **诊断/无交互模式绝不弹模态框**：`MessageBox.Show` 在没有交互桌面的环境里
        /// 永远不会被点掉，进程既不退出也不报错，只挂在任务管理器里等一个不存在的人。
        /// 本轮实测踩到——批量截图时 8 个进程全部卡在同一个隐形的错误框上，
        /// 15 分钟没有任何输出。自动化运行一律写日志 + 返回退出码。
        /// </summary>
        private static void Fail(ShellArgs opts, string message, int exitCode)
        {
            ServerLauncher.Log("[fail] " + message.Replace("\n", " | "));
            Environment.ExitCode = exitCode;
            if (opts.Headless)
            {
                Console.Error.WriteLine("[shell] " + message);
                return;
            }
            MessageBox.Show(message, "AI Gateway", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }

        /// <summary>
        /// 把命令行诊断参数搬进静态字段（供 ShellWindow 读取）。
        ///
        /// 单独一步而不是在窗口里直接查 `AppDomain.CurrentDomain.GetData`：
        /// 诊断参数是进程级配置，集中搬一次比让每个消费点各自解析更清楚。
        /// </summary>
        private static void ApplyDiagnosticOptions(ShellArgs opts)
        {
            ShotPath = opts.ShotPath;
            ShotPage = opts.ShotPage;
            ShotScroll = opts.ShotScroll;
            EvalPath = opts.EvalPath;
            EvalOut = opts.EvalOut;
            DragTest = opts.DragTest;
            QuitAfterShot = opts.Quit;
            ForceDpr = opts.ForceDpr;
            if (opts.EvalPath != null)
            {
                try { EvalScript = File.ReadAllText(opts.EvalPath); }
                catch (Exception ex) { ServerLauncher.Log("read eval script failed: " + ex.Message); }
            }
        }
    }
}
