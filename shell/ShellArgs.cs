using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;

namespace AiGatewayWeb
{
    /// <summary>
    /// 命令行参数。
    ///
    /// 单独成类而不是在 Main 里写一长串 `if (args[i] == "--xxx")`：
    /// 原版把所有开关散在 Main 的 for 循环里，其中 `--eval` 甚至写了两次
    /// （一次存路径、一次读文件），第二次会覆盖第一次且没人看得出来。
    ///
    /// 规则：`--key value` 成对出现；`--key` 单独出现视为开关。
    /// 越界的取值一律忽略而不是抛异常——诊断参数写错不该让程序起不来。
    /// </summary>
    internal sealed class ShellArgs
    {
        /// <summary>数据目录；未指定时为 %APPDATA%\AIGateway\data。</summary>
        internal string DataDir;

        /// <summary>UI 端口；0 表示未显式指定（由 .instance.json 或默认值决定）。</summary>
        internal int Port;

        internal string ShotPath;
        internal string ShotPage;
        internal int ShotScroll;

        /// <summary>--eval &lt;file&gt;：截图前执行该 JS 文件并打印返回值。</summary>
        internal string EvalPath;

        /// <summary>--eval-out &lt;file&gt;：把 eval 的返回值写进文件（批量布局诊断用）。</summary>
        internal string EvalOut;

        internal string SelfTestOut;
        internal bool DragTest;
        internal bool Quit;

        /// <summary>
        /// 诊断：强制按给定 devicePixelRatio 定稿标题栏/窗口（`--force-dpr 1`）。
        ///
        /// 存在的理由：手边往往只有一块屏、一种缩放，而这块 UI 最容易出问题的恰恰是
        /// 「别的缩放档」。给一个强制值就能在任意机器上验证 100%/150%/200%/300% 四种档位，
        /// 不用真的换显示器。仅诊断用，正常启动不读它。
        /// </summary>
        internal float ForceDpr;

        /// <summary>端口是否由命令行显式给出（给了就完全不读 .instance.json）。</summary>
        internal bool PortExplicit
        {
            get { return Port > 0; }
        }

        /// <summary>
        /// 「无交互」模式：出现任何诊断参数即视为自动化运行。
        ///
        /// 为什么必须有这个概念：WinForms 的 `MessageBox.Show` 是**模态**的。
        /// 在没有交互桌面的环境里（CI、远程会话、本机这种
        /// `GetForegroundWindow()==0` 的机器）它永远不会被点掉，
        /// 进程既不退出也不报错，只挂在任务管理器里等一个不存在的人来点。
        /// 诊断模式下一律把错误写进 `shell-launcher.log` 并返回非 0 退出码。
        /// </summary>
        internal bool Headless
        {
            get
            {
                return ShotPath != null || EvalPath != null || SelfTestOut != null || DragTest
                    || ForceDpr > 0f;
            }
        }

        internal static ShellArgs Parse(string[] args)
        {
            var a = new ShellArgs();
            for (int i = 0; i < args.Length; i++)
            {
                string key = args[i];
                string val = (i + 1 < args.Length) ? args[i + 1] : null;
                switch (key)
                {
                    case "--data": a.DataDir = Take(val, ref i); break;
                    case "--port": a.Port = TakeInt(val, ref i); break;
                    case "--screenshot": a.ShotPath = Take(val, ref i); break;
                    case "--page": a.ShotPage = Take(val, ref i); break;
                    case "--scroll": a.ShotScroll = TakeInt(val, ref i); break;
                    case "--eval": a.EvalPath = Take(val, ref i); break;
                    case "--eval-out": a.EvalOut = Take(val, ref i); break;
                    case "--selftest": a.SelfTestOut = Take(val, ref i); break;
                    case "--dragtest": a.DragTest = true; break;
                    case "--force-dpr": a.ForceDpr = TakeFloat(val, ref i); break;
                    case "--quit": a.Quit = true; break;
                }
            }
            return a;
        }

        /// <summary>取下一个参数作为值，并把游标前移；值缺失时返回 null。</summary>
        private static string Take(string val, ref int i)
        {
            if (val == null || val.StartsWith("--", StringComparison.Ordinal)) return null;
            i++;
            return val;
        }

        private static int TakeInt(string val, ref int i)
        {
            string raw = Take(val, ref i);
            int parsed;
            return (raw != null && int.TryParse(raw, out parsed)) ? parsed : 0;
        }

        private static float TakeFloat(string val, ref int i)
        {
            string raw = Take(val, ref i);
            float parsed;
            // 用 InvariantCulture：命令行里写 1.5，而中文区域的 float.TryParse 会当成
            // 千分位分隔符之外的字符并返回 false（1,5 才是本地格式）。
            return (raw != null && float.TryParse(raw, NumberStyles.Float,
                CultureInfo.InvariantCulture, out parsed)) ? parsed : 0f;
        }
    }

    /// <summary>
    /// 路径工具：后端与外壳之间对「同一个目录」必须有一致的判断。
    ///
    /// 后端回的是 `File.absolutePath`（Windows 反斜杠 + 原样大小写），
    /// 外壳用的是命令行里的字符串。两边字面不同但指向同一处是常态，
    /// 直接比较字符串会把「自己的后端」误判成别人的。
    /// </summary>
    internal static class PathUtil
    {
        internal static string ResolveDataDir(string fromArgs)
        {
            if (!string.IsNullOrWhiteSpace(fromArgs)) return fromArgs;
            string appData = Environment.GetEnvironmentVariable("APPDATA")
                             ?? Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            return Path.Combine(appData, "AIGateway", "data");
        }

        /// <summary>忽略大小写与结尾斜杠的等价判定；失败时退回字符串比较。</summary>
        internal static bool Same(string a, string b)
        {
            if (a == null || b == null) return a == b;
            try
            {
                string na = Path.GetFullPath(a).TrimEnd('\\', '/');
                string nb = Path.GetFullPath(b).TrimEnd('\\', '/');
                return string.Equals(na, nb, StringComparison.OrdinalIgnoreCase);
            }
            catch
            {
                return string.Equals(a.TrimEnd('\\', '/'), b.TrimEnd('\\', '/'),
                    StringComparison.OrdinalIgnoreCase);
            }
        }
    }

    /// <summary>
    /// 极简 JSON 字段提取。
    ///
    /// 只为一件事存在：`IsUiAliveFor` 需要从 `/ui/state` 里读 `dataDir`。
    /// 为此引一个 JSON 库（几十 KB 依赖 + 版本兼容风险）不值当，
    /// 而 `{"dataDir":"..."}` 这种顶层字符串字段的提取规则是稳定的。
    /// 取不到（字段缺失/结构变了）返回 null，调用方按「不知道」处理。
    /// </summary>
    internal static class JsonField
    {
        internal static string String(string json, string key)
        {
            if (json == null) return null;
            string marker = "\"" + key + "\":";
            int i = json.IndexOf(marker, StringComparison.Ordinal);
            if (i < 0) return null;
            int q1 = json.IndexOf('"', i + marker.Length);
            if (q1 < 0) return null;
            int q2 = json.IndexOf('"', q1 + 1);
            if (q2 < 0) return null;
            string raw = json.Substring(q1 + 1, q2 - q1 - 1);
            // JSON 里的反斜杠是成对出现的，这里只需要比较路径是否指向同一处
            return raw.Replace("\\\\", "\\").Replace("\\/", "/");
        }

        /// <summary>把 .NET 字符串转成 JS 字符串字面量（页面 key 都是 ASCII，只需处理引号与反斜杠）。</summary>
        internal static string JsString(string s)
        {
            return "\"" + (s ?? "").Replace("\\", "\\\\").Replace("\"", "\\\"") + "\"";
        }
    }

    /// <summary>
    /// 读后端写下的 `&lt;dataDir&gt;\.instance.json`（Java Properties 格式）。
    ///
    /// 为什么不用命令行参数回传：后端是外壳自己拉起的子进程，端口只有它自己算得出来
    /// （要看目录哈希 + 端口占用），外壳没有同样的信息；文件是两边唯一都可靠读到的通道。
    /// 解析失败一律回 0 让调用方回落到默认端口——配置文件读坏不该变成「启动不了」。
    /// </summary>
    internal static class InstancePortFile
    {
        internal const string FileName = ".instance.json";

        internal static string PathFor(string dataDir)
        {
            return Path.Combine(dataDir, FileName);
        }

        internal static Dictionary<string, string> ReadAll(string dataDir)
        {
            var map = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            try
            {
                string path = PathFor(dataDir);
                if (!File.Exists(path)) return map;
                foreach (string raw in File.ReadAllLines(path))
                {
                    string line = raw.Trim();
                    if (line.Length == 0 || line[0] == '#' || line[0] == '!') continue;
                    int eq = line.IndexOf('=');
                    if (eq <= 0) continue;
                    map[line.Substring(0, eq).Trim()] = line.Substring(eq + 1).Trim();
                }
            }
            catch
            {
                // 配置读坏等同于「没有配置」：让调用方走默认值
            }
            return map;
        }

        internal static int ReadUiPort(string dataDir)
        {
            string raw;
            if (!ReadAll(dataDir).TryGetValue("uiPort", out raw)) return 0;
            int p;
            return int.TryParse(raw, out p) && p > 0 && p <= 65535 ? p : 0;
        }

        internal static void Delete(string dataDir)
        {
            try { File.Delete(PathFor(dataDir)); }
            catch { /* 删不掉就用旧文件，ReadUiPort 里还会校验 */ }
        }
    }
}
