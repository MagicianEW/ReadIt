#!/usr/bin/env python3
"""Locate accent-coloured dialog buttons in an Android screenshot.

Why this exists
---------------
On some devices (Civi2 / 2209129SC) `input tap` coordinates derived by eyeballing a
screenshot land ~30% off, and `uiautomator dump` is unreliable.  Dialog buttons are
the only *coloured* thing on an otherwise greyscale dialog, so we can find them by
saturation instead of by position.

Usage
-----
    python tools/ui_buttons.py shot.png [--min-sat 40] [--min-px 60]

Prints one line per button:
    <label>  center=(x,y)  bbox=(x0,y0,x1,y1)  px=<count>

`center` is in *device* pixels (the screenshot is assumed to be 1:1 with the panel),
so it can be fed straight to `adb shell input tap <x> <y>`.

Buttons are labelled `left` / `right` in left-to-right order; a dialog with a single
button prints `left`.
"""
from __future__ import annotations

import argparse
import sys

from PIL import Image


def find_saturated_boxes(path: str, min_sat: int, min_px: int):
    im = Image.open(path).convert("RGB")
    w, h = im.size
    px = im.load()

    # Collect saturated pixels (buttons are accent-coloured; text/background are grey).
    cols: dict[int, list[int]] = {}
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if max(r, g, b) - min(r, g, b) >= min_sat:
                cols.setdefault(y, []).append(x)

    if not cols:
        return []

    # Row bands: consecutive rows that contain saturated pixels (gap tolerance 8px).
    rows = sorted(cols)
    bands: list[list[int]] = []
    for y in rows:
        if bands and y - bands[-1][1] <= 8:
            bands[-1][1] = y
        else:
            bands.append([y, y])

    # A button band is compact (a text line, not a full-screen image). Keep bands whose
    # saturated pixels cluster in a narrow x-range.
    boxes = []
    for y0, y1 in bands:
        xs = sorted({x for y in range(y0, y1 + 1) for x in cols.get(y, [])})
        if len(xs) < min_px:
            continue
        # Split the band's x extent into clusters separated by > 60px gaps.
        clusters: list[list[int]] = []
        for x in xs:
            if clusters and x - clusters[-1][1] <= 60:
                clusters[-1][1] = x
            else:
                clusters.append([x, x])
        for cx0, cx1 in clusters:
            count = sum(
                1 for y in range(y0, y1 + 1) for x in cols.get(y, []) if cx0 <= x <= cx1
            )
            if count < min_px:
                continue
            boxes.append((cx0, y0, cx1, y1, count))

    boxes.sort(key=lambda b: b[0])
    return boxes


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("shot")
    ap.add_argument("--min-sat", type=int, default=40, help="channel spread threshold")
    ap.add_argument("--min-px", type=int, default=60, help="min pixels per button")
    args = ap.parse_args()

    boxes = find_saturated_boxes(args.shot, args.min_sat, args.min_px)
    if not boxes:
        print("no saturated button found", file=sys.stderr)
        return 1

    labels = ["left", "right", "extra"]
    for i, (x0, y0, x1, y1, n) in enumerate(boxes):
        label = labels[i] if i < len(labels) else f"btn{i}"
        print(f"{label}  center=({(x0 + x1) // 2},{(y0 + y1) // 2})  "
              f"bbox=({x0},{y0},{x1},{y1})  px={n}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
