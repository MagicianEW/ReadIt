#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用「自己写字节」的方式给设备上的文件重命名，绕开 Windows/Git Bash 的中文参数转码。

背景：`adb push a.txt /sdcard/中文.txt` 会把中文按 **Windows ANSI(GBK)** 编码后发给
adb.exe，于是设备上落下一个 GBK 字节名。这种名字不是合法 UTF-8，Android 的
`File.listFiles()` 在 native `NewStringUTF` 处会：
  - debug 包（CheckJNI 开）→ JNI DETECTED ERROR + SIGABRT 全进程崩溃；
  - release 包 → 名字被替换式解码，`exists()` 变 false，文件实际打不开。

用法（名字以 **Unicode 转义** 或 **十六进制字节** 给出，全程 ASCII 参数，不会被转码）：

    # 1) 先推一个 ASCII 名
    adb push fixture.txt /sdcard/probe_ascii.txt

    # 2) 重命名成「测试书.txt」
    python tools/device_probe/utf8_rename.py /sdcard/probe_ascii.txt \
        --name-unicode "\u6d4b\u8bd5\u4e66.txt" | adb shell -T sh

    # 或直接给字节
    python tools/device_probe/utf8_rename.py /sdcard/probe_ascii.txt \
        --name-hex e6b58be8af95e4b9a62e747874 | adb shell -T sh

⚠️ 管道右侧两条硬要求：
  - **必须带 `-T`**（disable pty allocation），否则 shell 走 PTY 交互模式，
    读完 stdin 也不会退出，命令会一直挂住（得 `adb kill-server` 才解开）。
  - **`-T` 需要较新 adb（≥1.0.41 / platform-tools 30+）**。老 adb（如 1.0.31）没有该选项，
    管道 stdin 根本喂不进去（命令不执行，白跑）。用 SDK 的：
    `$ANDROID_SDK/platform-tools/adb.exe`。

校验落盘字节（应得合法 UTF-8）：
    adb shell "ls /sdcard | od -An -tx1"
"""
import argparse
import sys


def main():
    ap = argparse.ArgumentParser(add_help=True, description="给设备文件重命名为任意 UTF-8 名字")
    ap.add_argument("remote_ascii_path", help="设备上已存在的 ASCII 名文件（绝对路径）")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--name-unicode", help='新文件名，如 "\\u6d4b\\u8bd5.txt"')
    g.add_argument("--name-hex", help="新文件名的 UTF-8 字节（十六进制），如 e6b58be8af95")
    ap.add_argument("--parent", help="新文件的父目录；缺省沿用 remote_ascii_path 的目录")
    args = ap.parse_args()

    if args.name_unicode is not None:
        try:
            # "\\u6d4b\\u8bd5.txt" → b'\\u6d4b\\u8bd5.txt' → 真字符 → UTF-8 字节
            new_name = args.name_unicode.encode("ascii").decode("unicode_escape").encode("utf-8")
        except (UnicodeDecodeError, UnicodeEncodeError) as e:
            print("名字转义解析失败: %s" % e, file=sys.stderr)
            return 2
    else:
        try:
            new_name = bytes.fromhex(args.name_hex.strip().replace(" ", ""))
        except ValueError as e:
            print("十六进制解析失败: %s" % e, file=sys.stderr)
            return 2

    src = args.remote_ascii_path
    if args.parent:
        dst = args.parent.rstrip("/").encode("utf-8") + b"/" + new_name
    else:
        parts = src.rsplit("/", 1)
        if len(parts) != 2:
            print("remote_ascii_path 必须是绝对路径；或用 --parent 指定目标目录", file=sys.stderr)
            return 2
        dst = parts[0].encode("utf-8") + b"/" + new_name

    cmd = b"mv '" + src.encode("ascii", "strict") + b"' '" + dst + b"'\n"
    sys.stdout.buffer.write(cmd)
    sys.stdout.buffer.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
