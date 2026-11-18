"""验收用：用 Win32 PrintWindow 抓取本机 AI Gateway 窗口并写成 PNG。
不依赖 PIL / 屏幕可见性；若 PrintWindow 拿不到像素会返回全黑，此时用 JVM 内自绘截图兜底。
用法: python win_capture.py out.png [light|dark]
"""
import ctypes
import struct
import sys
import time
import zlib
from ctypes import wintypes

u = ctypes.WinDLL('user32', use_last_error=True)
g = ctypes.WinDLL('gdi32', use_last_error=True)


class RECT(ctypes.Structure):
    _fields_ = [('left', wintypes.LONG), ('top', wintypes.LONG),
                ('right', wintypes.LONG), ('bottom', wintypes.LONG)]


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [('biSize', wintypes.DWORD), ('biWidth', wintypes.LONG),
                ('biHeight', wintypes.LONG), ('biPlanes', wintypes.WORD),
                ('biBitCount', wintypes.WORD), ('biCompression', wintypes.DWORD),
                ('biSizeImage', wintypes.DWORD), ('biXPelsPerMeter', wintypes.LONG),
                ('biYPelsPerMeter', wintypes.LONG), ('biClrUsed', wintypes.DWORD),
                ('biClrImportant', wintypes.DWORD)]


class BITMAPINFO(ctypes.Structure):
    _fields_ = [('bmiHeader', BITMAPINFOHEADER), ('bmiColors', wintypes.DWORD * 3)]


def find_window():
    found = []
    CB = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    def cb(hwnd, _):
        n = u.GetWindowTextLengthW(hwnd)
        if n > 0:
            buf = ctypes.create_unicode_buffer(n + 1)
            u.GetWindowTextW(hwnd, buf, n + 1)
            if 'AI Gateway' in buf.value:
                found.append(hwnd)
        return True

    u.EnumWindows(CB(cb), 0)
    return found[0] if found else None


def write_png(path, width, height, bgra):
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        row = bgra[y * stride:(y + 1) * stride]
        for x in range(width):
            b, gr, r = row[x * 4], row[x * 4 + 1], row[x * 4 + 2]
            raw += bytes((r, gr, b))

    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 6))
    png += chunk(b'IEND', b'')
    open(path, 'wb').write(png)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'win.png'
    hwnd = find_window()
    if not hwnd:
        print('NO WINDOW')
        sys.exit(1)
    u.ShowWindow(hwnd, 9)
    u.SetForegroundWindow(hwnd)
    time.sleep(0.6)
    r = RECT()
    u.GetClientRect(hwnd, ctypes.byref(r))
    logical_w, logical_h = r.right - r.left, r.bottom - r.top
    # 非 DPI 感知进程在 150% 缩放下会被系统放大渲染，PrintWindow 的 DIB 必须用物理像素，
    # 否则只会抓到左上角一块（右侧与底部被裁掉）。
    try:
        u.GetDpiForWindow.argtypes = [wintypes.HWND]
        dpi = u.GetDpiForWindow(hwnd) or 96
    except Exception:
        dpi = 96
    scale = dpi / 96.0
    w, h = int(logical_w * scale), int(logical_h * scale)
    print('client logical', logical_w, logical_h, 'dpi', dpi, 'capture', w, h)

    hdc = u.GetDC(hwnd)
    mem = g.CreateCompatibleDC(hdc)
    bi = BITMAPINFO()
    bi.bmiHeader.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bi.bmiHeader.biWidth = w
    bi.bmiHeader.biHeight = -h  # top-down
    bi.bmiHeader.biPlanes = 1
    bi.bmiHeader.biBitCount = 32
    bi.bmiHeader.biCompression = 0  # BI_RGB
    bits = ctypes.c_void_p()
    bmp = g.CreateDIBSection(mem, ctypes.byref(bi), 0, ctypes.byref(bits), None, 0)
    old = g.SelectObject(mem, bmp)
    ok = u.PrintWindow(hwnd, mem, 2)  # PW_RENDERFULLCONTENT
    data = ctypes.string_at(bits, w * h * 4)
    g.SelectObject(mem, old)
    g.DeleteObject(bmp)
    g.DeleteDC(mem)
    u.ReleaseDC(hwnd, hdc)
    print('PrintWindow', ok, 'nonblack pixels', sum(1 for i in range(0, len(data), 4)
                                                    if data[i] or data[i + 1] or data[i + 2]))
    write_png(out, w, h, data)


if __name__ == '__main__':
    main()
