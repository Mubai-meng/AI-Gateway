# Window hit-test: validates WM_NCHITTEST results.
# Usage: powershell -File hit-test.ps1 -TargetPid <pid>
# NOTE: this file must stay pure ASCII. PowerShell 5.1 reads .ps1 as ANSI(GBK),
# so UTF-8 Chinese comments would corrupt the parser.
param([int]$TargetPid)

Add-Type @"
using System;using System.Runtime.InteropServices;using System.Text;
public class HT3 {
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr p);
  public delegate bool EnumProc(IntPtr h, IntPtr p);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
  [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool ScreenToClient(IntPtr h, ref POINT p);
  [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr h);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
}
"@

$script:found = [IntPtr]::Zero
$needle = $TargetPid
$cb = [HT3+EnumProc]{
    param($w, $p)
    $wp = 0
    [HT3]::GetWindowThreadProcessId($w, [ref]$wp) | Out-Null
    if ($wp -eq $needle) {
        $t = New-Object Text.StringBuilder 256
        [HT3]::GetWindowText($w, $t, 256) | Out-Null
        if ($t.ToString() -eq 'AI Gateway' -and [HT3]::IsWindowVisible($w)) {
            $script:found = $w
            return $false
        }
    }
    return $true
}
[HT3]::EnumWindows($cb, [IntPtr]::Zero) | Out-Null

$h = $script:found
if ($h -eq [IntPtr]::Zero) { "window not found for pid $TargetPid"; exit 1 }

$r = New-Object HT3+RECT
[HT3]::GetWindowRect($h, [ref]$r) | Out-Null
"hwnd=$h rect=$($r.Left),$($r.Top)-$($r.Right),$($r.Bottom) size=$($r.Right-$r.Left)x$($r.Bottom-$r.Top)"
"dpi=$([HT3]::GetDpiForWindow($h)) scale=$([HT3]::GetDpiForWindow($h)/96)"

function Hit([int]$sx, [int]$sy) {
    $p = New-Object HT3+POINT
    $p.X = $sx
    $p.Y = $sy
    [HT3]::ScreenToClient($h, [ref]$p) | Out-Null
    $lp = [IntPtr]((([int64]$p.Y -shl 16) -bor ([int64]$p.X -band 0xFFFF)))
    return [int]([HT3]::SendMessage($h, 0x0084, [IntPtr]::Zero, $lp))
}

$cx = $r.Left
$cy = $r.Top
$W = $r.Right - $r.Left
$H = $r.Bottom - $r.Top

$tests = @(
    @{ n = 'title-blank';     x = $cx + 300;      y = $cy + 20;      want = 2;  label = 'HTCAPTION' },
    @{ n = 'title-btn-right'; x = $cx + $W - 80;  y = $cy + 20;      want = 1;  label = 'HTCLIENT' },
    @{ n = 'edge-left';       x = $cx + 2;        y = $cy + 300;     want = 10; label = 'HTLEFT' },
    @{ n = 'edge-right';      x = $cx + $W - 2;   y = $cy + 300;     want = 11; label = 'HTRIGHT' },
    @{ n = 'edge-top';        x = $cx + 300;      y = $cy + 2;       want = 12; label = 'HTTOP' },
    @{ n = 'edge-bottom';     x = $cx + 300;      y = $cy + $H - 2;  want = 15; label = 'HTBOTTOM' },
    @{ n = 'corner-topleft';  x = $cx + 2;        y = $cy + 2;       want = 13; label = 'HTTOPLEFT' },
    @{ n = 'corner-botright'; x = $cx + $W - 2;   y = $cy + $H - 2;  want = 17; label = 'HTBOTTOMRIGHT' },
    @{ n = 'content-center';  x = $cx + 450;      y = $cy + 400;     want = 1;  label = 'HTCLIENT' }
)

$pass = 0
foreach ($t in $tests) {
    $got = Hit $t.x $t.y
    if ($got -eq $t.want) { $pass++; $ok = 'PASS' } else { $ok = 'FAIL' }
    "{0,-16} got={1,3} want={2,3} ({3}) {4}" -f $t.n, $got, $t.want, $t.label, $ok
}
"--- $pass / $($tests.Count) passed"
