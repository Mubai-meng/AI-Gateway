"""截图指定窗口（按窗口标题匹配），输出 PNG。用 PrintWindow 抓后台窗口，不受遮挡影响。

用法：
  python shot_window.py "AI Gateway" out.png [--crop WxH+X+Y]
"""
import ctypes
import ctypes.wintypes as wt
import sys
import time
from PIL import Image

user32 = ctypes.windll.user32
gdi32 = ctypes.windll.gdi32

PW_RENDERFULLCONTENT = 0x00000002


def find_window(title_part: str):
    target = []

    @ctypes.WINFUNCTYPE(ctypes.c_bool, wt.HWND, wt.LPARAM)
    def cb(hwnd, _):
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length == 0:
            return True
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        if title_part.lower() in buf.value.lower():
            target.append((hwnd, buf.value))
        return True

    user32.EnumWindows(cb, 0)
    return target


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [
        ("biSize", wt.DWORD), ("biWidth", wt.LONG), ("biHeight", wt.LONG),
        ("biPlanes", wt.WORD), ("biBitCount", wt.WORD), ("biCompression", wt.DWORD),
        ("biSizeImage", wt.DWORD), ("biXPelsPerMeter", wt.LONG), ("biYPelsPerMeter", wt.LONG),
        ("biClrUsed", wt.DWORD), ("biClrImportant", wt.DWORD),
    ]


def capture(hwnd, path):
    rect = wt.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(rect))
    w = rect.right - rect.left
    h = rect.bottom - rect.top
    if w <= 0 or h <= 0:
        raise SystemExit("window size invalid")
    hdc = user32.GetWindowDC(hwnd)
    mem = gdi32.CreateCompatibleDC(hdc)
    bmp = gdi32.CreateCompatibleBitmap(hdc, w, h)
    gdi32.SelectObject(mem, bmp)
    ok = user32.PrintWindow(hwnd, mem, PW_RENDERFULLCONTENT)
    if not ok:
        user32.PrintWindow(hwnd, mem, 0)
    info = BITMAPINFOHEADER()
    info.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    info.biWidth = w
    info.biHeight = -h
    info.biPlanes = 1
    info.biBitCount = 32
    info.biCompression = 0
    buf = ctypes.create_string_buffer(w * h * 4)
    gdi32.GetDIBits(mem, bmp, 0, h, buf, ctypes.byref(info), 0)
    img = Image.frombuffer("RGBA", (w, h), buf, "raw", "BGRA", 0, 1).convert("RGB")
    img.save(path)
    gdi32.DeleteObject(bmp)
    gdi32.DeleteDC(mem)
    user32.ReleaseDC(hwnd, hdc)
    return w, h


def main():
    title = sys.argv[1] if len(sys.argv) > 1 else "AI Gateway"
    out = sys.argv[2] if len(sys.argv) > 2 else "shot.png"
    wins = find_window(title)
    if not wins:
        raise SystemExit("no window matching %r" % title)
    hwnd, name = wins[0]
    user32.ShowWindow(hwnd, 9)  # SW_RESTORE
    user32.SetForegroundWindow(hwnd)
    time.sleep(0.6)
    w, h = capture(hwnd, out)
    print("captured", name, f"{w}x{h}", "->", out)


if __name__ == "__main__":
    main()
