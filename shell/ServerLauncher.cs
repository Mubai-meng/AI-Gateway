using System;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text;
using System.Threading;

namespace AiGatewayWeb
{
    /// <summary>
    /// 后端子进程的启动、健康检查与收尾。
    ///
    /// 外壳是 winexe（没有控制台），失败信息只能落盘；所以这里每一步都写日志，
    /// 出问题时用户把 `&lt;dataDir&gt;\shell-launcher.log` 发过来就能定位。
    ///
    /// 三种启动形态，按可靠性递减：
    /// 1. 随包形态：`runtime\bin\javaw.exe` + `app\*.jar`（发布版走这条）；
    /// 2. 开发态：`server\build\install\aigw-server\bin\aigw-server.bat`；
    /// 3. 兜底：PATH 里的 `java.exe`。
    /// </summary>
    internal static class ServerLauncher
    {
        private static Process _proc;

        /// <summary>等待后端就绪的上限。冷启动要迁移账号、拉额度，90 秒是保守值。</summary>
        private const int ReadyTimeoutSeconds = 90;

        /// <summary>健康检查的轮询间隔。</summary>
        private const int PollIntervalMs = 400;

        /// <summary>后端启动诊断日志路径（&lt;dataDir&gt;\shell-launcher.log）。</summary>
        internal static string LogPath;

        internal static void Log(string msg)
        {
            if (LogPath == null) return;
            try
            {
                File.AppendAllText(LogPath,
                    DateTime.Now.ToString("HH:mm:ss") + " " + msg + Environment.NewLine);
            }
            catch
            {
                // 日志写不进去不能反过来影响启动
            }
        }

        /// <summary>后端是否已经退出（启动过程中用来提前失败）。</summary>
        internal static bool HasExited
        {
            get { return _proc != null && _proc.HasExited; }
        }

        /// <summary>拉起后端。成功返回 true，日志里能看到走了哪条路径。</summary>
        internal static bool TryStart(string dataDir)
        {
            string exeDir = Path.GetDirectoryName(System.Windows.Forms.Application.ExecutablePath);
            if (string.IsNullOrEmpty(exeDir)) exeDir = Environment.CurrentDirectory;
            string runArgs = "--data \"" + dataDir + "\" --no-browser";
            Log("exeDir=" + exeDir);

            if (TryStartBundled(exeDir, runArgs)) return true;
            if (TryStartDevScript(exeDir, runArgs)) return true;
            return TryStartSystemJava(exeDir, runArgs);
        }

        /// <summary>形态 1：随包 runtime + jar（发布版）。</summary>
        private static bool TryStartBundled(string exeDir, string runArgs)
        {
            string javaw = Path.Combine(exeDir, "runtime", "bin", "javaw.exe");
            string appDir = Path.Combine(exeDir, "app");
            string mainJar = Path.Combine(appDir, "aigw-server.jar");
            Log("javaw=" + javaw + " exists=" + File.Exists(javaw));
            Log("mainJar=" + mainJar + " exists=" + File.Exists(mainJar));
            if (!File.Exists(javaw) || !File.Exists(mainJar)) return false;

            string cp = string.Join(";", Directory.GetFiles(appDir, "*.jar"));
            Log("classpath entries=" + Directory.GetFiles(appDir, "*.jar").Length);
            bool ok = Launch(javaw,
                "-Dfile.encoding=UTF-8 -cp \"" + cp + "\" dev.aigw.server.MainKt " + runArgs, exeDir);
            Log("launch javaw -> " + ok);
            return ok;
        }

        /// <summary>
        /// 形态 2：installDist 生成的启动脚本。
        /// 工作目录必须设为脚本所在目录，否则脚本里用 %DIRNAME% 算的 lib 路径找不到。
        /// </summary>
        private static bool TryStartDevScript(string exeDir, string runArgs)
        {
            string[] bats =
            {
                Path.Combine(exeDir, "server", "aigw-server.bat"),
                Path.Combine(exeDir, "..", "..", "server", "build", "install",
                    "aigw-server", "bin", "aigw-server.bat"),
            };
            foreach (string bat in bats)
            {
                if (!File.Exists(bat)) continue;
                string scriptDir = Path.GetDirectoryName(bat);
                bool ok = Launch("cmd.exe", "/c \"\"" + bat + "\" " + runArgs + "\"", scriptDir);
                Log("launch bat " + bat + " -> " + ok);
                if (ok) return true;
            }
            return false;
        }

        /// <summary>形态 3：依赖 PATH 里有 java。</summary>
        private static bool TryStartSystemJava(string exeDir, string runArgs)
        {
            string appDir = Path.Combine(exeDir, "app");
            string mainJar = Path.Combine(appDir, "aigw-server.jar");
            if (!File.Exists(mainJar)) return false;

            string cp = string.Join(";", Directory.GetFiles(appDir, "*.jar"));
            bool ok = Launch("java.exe",
                "-Dfile.encoding=UTF-8 -cp \"" + cp + "\" dev.aigw.server.MainKt " + runArgs, exeDir);
            Log("launch java.exe from PATH -> " + ok);
            return ok;
        }

        private static bool Launch(string file, string args, string workDir)
        {
            try
            {
                var psi = new ProcessStartInfo(file, args)
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    WorkingDirectory = workDir,
                };
                _proc = Process.Start(psi);
                return _proc != null;
            }
            catch (Exception ex)
            {
                Log("Launch failed: " + ex.Message);
                return false;
            }
        }

        /// <summary>
        /// 结束后端。先请求正常退出，超时才强杀。
        ///
        /// 这里是「数据会不会丢」的关键点：后端存储把 `logs/`、`usage/`、`clientkey/`
        /// 的写入合并到 200ms 一次的批量提交里，只有 JVM 正常退出（shutdown hook）
        /// 或显式 flush 才会落盘。直接 `Kill()` 是 TerminateProcess，**一个 hook 都不跑**——
        /// 实测：发完请求立刻杀进程，重启后「今日请求数」少 1。
        ///
        /// 所以顺序是：先 `POST /ui/quit`（后端 flush 后自己 halt）→ 等 3 秒 →
        /// 再退回到原来的 `CloseMainWindow` + `Kill()` 兜底。旧版后端没有这个端点，
        /// 请求会 404，不影响后续流程。
        /// </summary>
        internal static void Stop()
        {
            try
            {
                if (_proc == null || _proc.HasExited) return;
                RequestQuit(Program.UiPort);
                if (_proc.WaitForExit(3000)) return;
                _proc.CloseMainWindow();
                if (!_proc.WaitForExit(2000)) _proc.Kill();
            }
            catch
            {
                // 进程可能已经自己退了
            }
        }

        /// <summary>
        /// 通知后端「正常退出」。失败（后端没起、端口不通、旧版无此端点）一律忽略：
        /// 这只是尽力而为的落盘机会，不是关窗的前置条件。
        /// </summary>
        internal static void RequestQuit(int uiPort)
        {
            if (uiPort <= 0) return;
            try
            {
                using (var c = new HttpClient { Timeout = TimeSpan.FromSeconds(2) })
                {
                    var body = new StringContent("{}", Encoding.UTF8, "application/json");
                    c.PostAsync("http://127.0.0.1:" + uiPort + "/ui/quit", body)
                        .GetAwaiter().GetResult();
                }
            }
            catch
            {
                // 尽力而为
            }
        }

        // ---------------------------------------------------------------- 健康检查

        /// <summary>
        /// 「这个数据目录的后端活着吗」。
        ///
        /// 端口 + 数据目录两个条件都要对上：同机可能同时跑多个实例，只比端口会把
        /// 别人家的后端当成自己的（窗口连过去后看到的是别人的账号）。
        /// 拿不到 dataDir 字段（老版本后端/接口变了）时退回按端口判断，宁可多连也不卡死。
        /// </summary>
        internal static bool IsAliveFor(string dataDir, int uiPort)
        {
            try
            {
                using (var c = new HttpClient { Timeout = TimeSpan.FromSeconds(2) })
                {
                    var r = c.GetAsync("http://127.0.0.1:" + uiPort + "/ui/state").GetAwaiter().GetResult();
                    if (!r.IsSuccessStatusCode) return false;
                    string body = r.Content.ReadAsStringAsync().GetAwaiter().GetResult();
                    string reported = JsonField.String(body, "dataDir");
                    if (reported == null) return true;
                    return PathUtil.Same(reported, dataDir);
                }
            }
            catch
            {
                return false;
            }
        }

        /// <summary>
        /// 等待结果。区分「超时」与「后端自己退了」，因为两者该给的提示完全不同：
        /// 超时多半是端口被占/后端慢；自退基本是配置或数据有问题，得去看日志。
        /// </summary>
        internal enum WaitResult
        {
            Ready,
            Exited,
            Timeout,
        }

        /// <summary>
        /// 等后端起来。
        ///
        /// 每轮先调 `readPort()`：端口由后端派生时，它算出的端口可能与上一轮
        /// .instance.json 里的值不同（换了目录、或上次的端口被别的东西占了），
        /// 只盯旧端口会一直等到超时。是否重读文件由调用方在 `readPort` 里决定——
        /// 显式给了 `--port` 时就不该看文件。
        ///
        /// 存活判定必须按数据目录校验（见 [IsAliveFor]）——否则「等自己的后端」
        /// 会被邻实例的后端满足，立刻返回并把窗口指到别人的数据上。
        /// </summary>
        internal static WaitResult WaitReady(string dataDir, Func<int> readPort)
        {
            var sw = Stopwatch.StartNew();
            var limit = TimeSpan.FromSeconds(ReadyTimeoutSeconds);
            while (sw.Elapsed < limit)
            {
                Thread.Sleep(PollIntervalMs);
                int port = readPort();
                if (IsAliveFor(dataDir, port)) return WaitResult.Ready;
                if (HasExited) return WaitResult.Exited;
            }
            return WaitResult.Timeout;
        }
    }
}
