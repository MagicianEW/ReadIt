# -*- coding: utf-8 -*-
"""
生成 ReadIt 的旧版（API < 26）栅格启动图标。

背景：P5 真机冒烟发现原 res/drawable/ic_launcher.xml（纯 vector）在 API 26+ 的
MIUI 桌面上被渲染成一个黑方块。自适应图标（mipmap-anydpi-v26/）已单独提供，
但 minSdk 19，API 26 以下系统只认栅格 PNG，必须补齐各密度回退图。

本脚本不依赖任何第三方库（当前沙箱装不上 Pillow），自己用 zlib + struct 写 PNG。
图形：
  - 圆角方形底（纯黑），SDF + 超采样抗锯齿
  - 一本摊开的书（纯白），与 drawable/ic_launcher_foreground.xml 同造型
  - 底边加一圈很淡的白描边，避免纯黑图标在深色壁纸上"糊"成一团
"""

import os
import struct
import zlib

OUT_ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "res")

# density 目录 -> 边长（px）
SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BG = (0, 0, 0)          # 黑底
FG = (255, 255, 255)    # 白书
BORDER_ALPHA = 0.30     # 描边不透明度
BORDER_W = 0.018        # 描边宽度（相对边长）

SS = 4                  # 每像素 4x4 超采样


def clamp(v, lo, hi):
    return lo if v < lo else (hi if v > hi else v)


def rounded_rect_sdf(px, py, cx, cy, hx, hy, r):
    """圆角矩形有符号距离场：<0 在内部，=0 在边界。坐标以像素为单位。"""
    qx = abs(px - cx) - (hx - r)
    qy = abs(py - cy) - (hy - r)
    ax = qx if qx > 0 else 0.0
    ay = qy if qy > 0 else 0.0
    outside = (ax * ax + ay * ay) ** 0.5
    inside = min(max(qx, qy), 0.0)
    return outside + inside - r


def point_in_poly(px, py, pts):
    """射线法。pts 为 [(x, y), ...]。"""
    n = len(pts)
    inside = False
    j = n - 1
    for i in range(n):
        xi, yi = pts[i]
        xj, yj = pts[j]
        if (yi > py) != (yj > py):
            x_at = (xj - xi) * (py - yi) / (yj - yi) + xi
            if px < x_at:
                inside = not inside
        j = i
    return inside


def book_polys(size):
    """返回左右两页的多边形顶点（像素坐标）。

    与 vector 前景同构：整体宽 0.54N、高 0.38N 居中；外侧边上下各内收 8%，
    中缝两侧各留 3% 的间隙。
    """
    n = float(size)
    cx = n / 2.0
    cy = n / 2.0
    w = 0.54 * n
    h = 0.38 * n
    left = cx - w / 2.0
    right = cx + w / 2.0
    top = cy - h / 2.0
    bottom = cy + h / 2.0
    inset = 0.08 * h
    spine = 0.03 * w

    left_page = [
        (left, top + inset),
        (cx - spine, top),
        (cx - spine, bottom),
        (left, bottom - inset),
    ]
    right_page = [
        (right, top + inset),
        (cx + spine, top),
        (cx + spine, bottom),
        (right, bottom - inset),
    ]
    return [left_page, right_page]


def render(size):
    n = size
    polys = book_polys(size)
    hx = hy = n / 2.0
    radius = 0.22 * n
    border_w = BORDER_W * n
    cx = cy = n / 2.0

    step = 1.0 / SS
    rows = []
    for y in range(n):
        row = bytearray()
        for x in range(n):
            r_acc = g_acc = b_acc = a_acc = 0.0
            for sy in range(SS):
                for sx in range(SS):
                    px = x + (sx + 0.5) * step
                    py = y + (sy + 0.5) * step

                    d = rounded_rect_sdf(px, py, cx, cy, hx, hy, radius)
                    # 底：黑色圆角方形，边缘 1px 平滑过渡
                    tile_a = clamp(0.5 - d, 0.0, 1.0)
                    if tile_a <= 0.0:
                        continue

                    in_book = False
                    for poly in polys:
                        if point_in_poly(px, py, poly):
                            in_book = True
                            break

                    if in_book:
                        col = FG
                    else:
                        col = BG
                        # 内描边：贴近边界的一圈淡淡提亮
                        if d > -border_w:
                            t = (1.0 - (-d) / border_w) * BORDER_ALPHA
                            col = tuple(
                                int(round(c + (255 - c) * t)) for c in col
                            )

                    r_acc += col[0]
                    g_acc += col[1]
                    b_acc += col[2]
                    a_acc += 255.0
            samples = float(SS * SS)
            a = a_acc / samples
            if a <= 0.0:
                row += b"\x00\x00\x00\x00"
            else:
                # 已按覆盖率加权（未覆盖的样本未累加颜色），直接除 a_acc 的反向权重
                cov = a_acc / 255.0
                r = r_acc / cov
                g = g_acc / cov
                b = b_acc / cov
                row += bytes((
                    int(round(clamp(r, 0, 255))),
                    int(round(clamp(g, 0, 255))),
                    int(round(clamp(b, 0, 255))),
                    int(round(clamp(a, 0, 255))),
                ))
        rows.append(bytes(row))
    return rows


def write_png(path, rows, size):
    raw = bytearray()
    for r in rows:
        raw.append(0)  # filter type 0
        raw += r

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        c += struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        return c

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)  # 8bit RGBA
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", header)
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)
    return len(png)


def main():
    for folder, size in sorted(SIZES.items(), key=lambda kv: kv[1]):
        rows = render(size)
        out = os.path.join(OUT_ROOT, folder, "ic_launcher.png")
        nbytes = write_png(out, rows, size)
        print(f"{folder}/ic_launcher.png  {size}x{size}  {nbytes} bytes")


if __name__ == "__main__":
    main()
