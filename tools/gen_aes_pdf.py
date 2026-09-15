# -*- coding: utf-8 -*-
"""用 PyMuPDF 造一个「AES-128 加密 + 空用户口令」的 PDF。

目的：验证 APK 中 BouncyCastle 的 AES 解密路径真的可用。
  - user_pw 留空  -> App 现有 UI 无需输密码即可打开
  - 内容流 AES 加密 -> PDFBox 必须真正调用 AES 解密（BouncyCastle provider）
若 PQC 资源裁剪误伤了 BC 的加密实现，这一步会立刻报错而不是静默通过。

用法：python gen_aes_pdf.py <src.pdf> <out.pdf>
"""

import sys
import os

sys.stdout.reconfigure(encoding="utf-8")

import fitz  # PyMuPDF

SRC = sys.argv[1] if len(sys.argv) > 1 else r"<LOCAL_HOME>\WorkBuddy\ReadIt\.workbuddy\tmp\testbooks\04_pdf_text_20p.pdf"
OUT = sys.argv[2] if len(sys.argv) > 2 else r"<LOCAL_HOME>\WorkBuddy\ReadIt\.workbuddy\tmp\testbooks\07_pdf_aes.pdf"

print("PyMuPDF", fitz.__doc__)
doc = fitz.open(SRC)
print("src pages:", doc.page_count)

perm = int(
    fitz.PDF_PERM_PRINT
    | fitz.PDF_PERM_COPY
    | fitz.PDF_PERM_ANNOTATE
)

doc.save(
    OUT,
    encryption=fitz.PDF_ENCRYPT_AES_128,
    owner_pw="owner",
    user_pw="",              # 空用户口令 -> 免密打开
    permissions=perm,
    garbage=4,
    deflate=True,
)
doc.close()

print("written:", OUT, os.path.getsize(OUT), "bytes")

# 回读校验：免密应能打开，且内容流是加密的
chk = fitz.open(OUT)
print("needs_pass:", chk.needs_pass, "pages:", chk.page_count, "is_encrypted:", chk.is_encrypted)
txt = chk[0].get_text()[:80].replace("\n", " ")
print("page0 text:", txt)
chk.close()
