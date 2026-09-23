#!/usr/bin/env python3
"""从设备截图里定位对话框按钮，直接给出可用的 `input tap` 坐标。

## 为什么需要它

`uiautomator dump` 在部分机型上不可用（实测 **小米 Civi2 / ziyi 2209129SC，API35**：
dump 无响应或极慢），而墨水屏机型上 `screencap` 又全黑。剩下的路只有**截图 + 像素分析**：

- **LCD 机型**：`adb exec-out screencap -p` 直接拿彩色/灰度图本文件即可处理。
- **墨水屏机型**：screencap 全黑，改用 `uiautomator dump`。所以两条路是互补的，不是替代。

另外，盲写坐标靠不住 —— 同一段 UI 在 480×600 与 1080×2400 上坐标差 2.25 倍以上，
而且**截图被阅读器缩放过**，肉眼估的坐标会系统性偏小（本项目实测偏了 30%+，点击静默失效）。

## 用法

    python tools/ui_tap.py shot.png right        # 取最右一个按钮（通常是「确定 / 加书签 / 保存」）
    python tools/ui_tap.py shot.png left         # 取最左一个按钮（通常是「取消 / 删除」）

把 stdout 的 `x y` 喂给 adb：

    COORD=$(python tools/ui_tap.py shot.png right)
    adb shell -T "input tap $COORD"

诊断信息（对话框矩形、文字带、按钮簇）走 stderr，方便人工核对。

## 算法

1. 用「亮像素行数」找出**对话框白底矩形**（浅色对话框 + 深色遮罩是 Android 的默认形态）。
   暗色主题（如本项目 F27 反色后的界面）会把阈值反过来，因此 `--dark` 可切换。
2. 在矩形内按行切成**文字带**（行间空隙 > 10px 分段）；**丢掉高度 < 12px 的带** ——
   对话框底边/阴影常形成一条 1~3px 的伪文字带，不剔除会把按钮行认错。
3. 取**最后一条文字带**（= 按钮行），在其中按列聚簇（间隙 > 30px 分段），过滤宽度 < 15px 的噪声。
4. 输出最左 / 最右那个簇的中心。

## 已知边界

- 假设「浅底深字」。深底浅字（反色主题）加 `--dark`。
- 假设按钮在对话框内的**最后一行**。若底部还有页脚文字，会认错 —— 此时用 stderr 的簇列表人工挑。
- 只在「能截到图」的前提下成立；墨水屏请走 `uiautomator dump`。
"""

from __future__ import annotations

import argparse
import sys

import numpy as np
from PIL import Image

MIN_BAND_H = 12     # 文字带最小高度（滤掉对话框底边/阴影造成的伪带）
MIN_CLUSTER_W = 15  # 按钮文字簇最小宽度（滤掉 1px 边缘噪声）


def find_dialog(img: np.ndarray, dark_mode: bool) -> tuple[int, int, int, int]:
    """返回对话框矩形 (x0, x1, y0, y1)。"""
    body = (img < 128) if dark_mode else (img > 200)
    rowsum = body.sum(axis=1)
    rows = np.where(rowsum > 500)[0]
    if len(rows) == 0:
        raise SystemExit("没有找到对话框：整屏都是同一色（遮罩/全黑？先确认已截到内容）")
    y0, y1 = int(rows.min()), int(rows.max())
    colsum = body[y0:y1].sum(axis=0)
    cols = np.where(colsum > (y1 - y0) * 0.5)[0]
    if len(cols) == 0:
        raise SystemExit("没有找到对话框：列投影为空")
    return int(cols.min()), int(cols.max()), y0, y1


def bands_of(ink: np.ndarray, gap: int = 10) -> list[tuple[int, int]]:
    """把「有墨行」切成文字带。"""
    rows = np.where(ink.sum(axis=1) > 0)[0]
    if len(rows) == 0:
        return []
    out: list[tuple[int, int]] = []
    start = prev = int(rows[0])
    for r in rows[1:]:
        r = int(r)
        if r - prev > gap:
            out.append((start, prev))
            start = r
        prev = r
    out.append((start, prev))
    return [b for b in out if b[1] - b[0] >= MIN_BAND_H]


def clusters_of(ink: np.ndarray, gap: int = 30) -> list[tuple[int, int]]:
    """把「有墨列」切成簇。"""
    cols = np.where(ink.sum(axis=0) > 0)[0]
    if len(cols) == 0:
        return []
    out: list[tuple[int, int]] = []
    start = prev = int(cols[0])
    for c in cols[1:]:
        c = int(c)
        if c - prev > gap:
            out.append((start, prev))
            start = c
        prev = c
    out.append((start, prev))
    return [c for c in out if c[1] - c[0] >= MIN_CLUSTER_W]


def locate(path: str, pick: str, dark_mode: bool) -> tuple[int, int]:
    img = np.asarray(Image.open(path).convert("L"))
    x0, x1, y0, y1 = find_dialog(img, dark_mode)
    ink = ((img[y0 : y1 + 1, x0 : x1 + 1] > 128) if dark_mode else (img[y0 : y1 + 1, x0 : x1 + 1] < 128))
    bands = bands_of(ink)
    print(f"# dialog x:{x0}..{x1} y:{y0}..{y1} 文字带(相对y)={bands}", file=sys.stderr)
    if not bands:
        raise SystemExit("对话框里找不到文字带")
    by0, by1 = bands[-1]  # 最下面一条 = 按钮行
    cl = clusters_of(ink[by0 : by1 + 1, :])
    print(f"# 按钮簇={[(x0 + c[0], x0 + c[1]) for c in cl]}", file=sys.stderr)
    if not cl:
        raise SystemExit("按钮行里找不到文字簇")
    c = cl[-1] if pick == "right" else cl[0]
    return x0 + (c[0] + c[1]) // 2, y0 + (by0 + by1) // 2


def main() -> None:
    ap = argparse.ArgumentParser(description="从截图里定位对话框按钮坐标")
    ap.add_argument("image", help="截图路径（adb exec-out screencap -p > shot.png）")
    ap.add_argument("pick", nargs="?", default="right", choices=["left", "right"], help="取最左还是最右的按钮")
    ap.add_argument("--dark", action="store_true", help="深底浅字（反色主题）")
    a = ap.parse_args()
    x, y = locate(a.image, a.pick, a.dark)
    print(f"{x} {y}")


if __name__ == "__main__":
    main()
