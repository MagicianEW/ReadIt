# -*- coding: utf-8 -*-
"""生成 P0 验收基准语料 + §9.3 边界夹具。

产出：
  app/src/test/resources/bench/txt/*.txt + truth.tsv   -> F02 章节识别率
  app/src/test/resources/bench/pdf/*.pdf + truth.tsv   -> F08 扫描版检出率/误判率
  app/src/test/resources/bench/pdf_hetero/*.pdf + truth.tsv -> F08 异质形态（采样页数的代价）
  app/src/test/resources/fixtures/readit_mixed_*.pdf  -> §9.3 混合版
  app/src/test/resources/fixtures/readit_damaged_*.pdf-> §9.3 损坏 PDF
  app/src/test/resources/fixtures/readit_outline*.pdf -> F06 书签解析

truth.tsv 用制表符分隔，Kotlin 侧不引 JSON 依赖即可解析：
  <文件名>\\t<ground truth>

用法: <python> tools/gen_bench_corpus.py
"""

import os
import random
import sys

sys.stdout.reconfigure(encoding="utf-8")

import fitz  # PyMuPDF
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "app", "src", "test", "resources")
BENCH_TXT = os.path.join(RES, "bench", "txt")
BENCH_PDF = os.path.join(RES, "bench", "pdf")
BENCH_HETERO = os.path.join(RES, "bench", "pdf_hetero")
FIX = os.path.join(RES, "fixtures")

for d in (BENCH_TXT, BENCH_PDF, BENCH_HETERO, FIX):
    os.makedirs(d, exist_ok=True)

RNG = random.Random(20260922)

# ---------------------------------------------------------------------------
# 共用素材
# ---------------------------------------------------------------------------

BODY_POOL = [
    "雨下了整整三天，山道上的泥被踩成了一层薄薄的浆。",
    "他把断剑背在身后，剑柄处的布条已经磨得发白。",
    "庙里的老和尚抬头看了他一眼，又把目光落回经书上。",
    "风穿过窗棂，烛火晃了两下，墙上的影子跟着抖。",
    "她没有回头，只是把伞往他那边偏了偏。",
    "那封信道谢的话写得极客气，末尾却连名字都没有落。",
    "镇上的铁匠铺从早响到晚，火星溅在青石板上很快灭了。",
    "少年把最后一块干粮掰成两半，递了一半过去。",
    "河面上的雾散得很慢，船撑出去老远还看得见桅杆。",
    "先生说他性子太急，急则生乱，乱则失据。",
    "他想起那年冬天，灶膛里的火一直没熄过。",
    "马在厩里刨着蹄子，稻草被翻得沙沙响。",
    "账房先生的算盘珠子拨得飞快，声音像落雨。",
    "天亮之前他们翻过了那道梁，身后是灰白色的天。",
    "她把信折好塞进袖袋，指尖有些凉。",
    "酒是浑的，碗沿缺了一块，他还是一口喝了下去。",
    "山路两侧的白桦叶子已经落尽，枝子上挂着霜。",
    "他记不清这是第几次路过这个渡口了。",
    "老人的手抖得厉害，茶水洒在了桌面上。",
    "远处的钟声敲了三下，惊起一群乌鸦。",
    "他把灯笼提低了些，让光只照见脚下的路。",
    "书页边缘卷了角，墨迹被水洇开一小片。",
    "她说这话时没有看任何人，声音很轻。",
    "门轴吱呀一声开了，冷气顺着门缝挤进来。",
    "他站在檐下等雨停，鞋已经湿透了。",
    "那把刀鞘上的漆掉了大半，露出底下的木头。",
    "孩子在门槛上坐着，两只脚悬空晃荡。",
    "药味很重，屋子里闷得让人发困。",
    "他把银子推回去，只收了那袋干粮。",
    "天边裂开一道口子，光落在水面上晃得人睁不开眼。",
]

# 正样本标题池
TITLE_POOL = [
    "山下的少年", "断剑", "雨夜", "老庙", "渡口", "白桦林", "铁匠铺",
    "一封没有署名的信", "霜降", "过梁", "灯笼", "檐下", "灶膛",
    "算盘", "钟声", "缺口", "归途", "旧账", "雪落", "灯下",
]

# 负样本：句中出现「第X章」但并非标题（必须被拒绝）
NEG_CHAPTER_IN_SENTENCE = [
    "他把书翻到第三章，指着那一行字问她认不认得。",
    "先生的讲义里写着，第八章讲的全是礼数，最是难背。",
    "这一卷抄到第十二章便断了，后面的纸页不知去了哪里。",
    "她只记得第五章里那场雪，别的都记不清了。",
    "老人说，第二章才是真正要紧的地方，第一章不过是引子。",
]

# 负样本：以数字开头、但以句号收尾的正文（必须被拒绝）
NEG_NUM_BODY = [
    "3. 他点了点头，转身走进雨里。",
    "7. 桌上那盏灯一直亮到后半夜。",
    "12. 这趟路走了六天，比预计的慢。",
]

# 负样本：超长行（>40 字符，绝不可能是标题）
NEG_LONG = [
    "他把这些年走过的路在心里从头数了一遍，从南边的渡口一直到北面那道山梁，中间漏掉的那几段怎么也想不起来了。",
]

# 负样本：目录页引导行（成串的点号是目录的指纹，正文标题不会这么写）
NEG_TOC = [
    "第一章 山下的少年············12",
    "第二章 断剑············27",
    "第三章 雨夜············41",
]

# 负样本：以「数字. 数字」形式出现的非标题（日期、编号碎片）
NEG_DATE = [
    "2023. 12",
    "1998. 7",
]

# 全角数字表
FULLWIDTH_DIGITS = "０１２３４５６７８９"

SPECIAL_TITLES = ["序言", "序", "前言", "引子", "楔子", "后记", "後記", "尾声", "尾聲", "附录", "附錄", "结束语"]

CN_DIGITS = "零一二三四五六七八九十"


def cn_number(n: int) -> str:
    """1..999 -> 中文数字（仅覆盖基准语料需要的范围）"""
    if n < 10:
        return CN_DIGITS[n]
    if n < 20:
        return "十" + (CN_DIGITS[n % 10] if n % 10 else "")
    if n < 100:
        return CN_DIGITS[n // 10] + "十" + (CN_DIGITS[n % 10] if n % 10 else "")
    h, r = n // 100, n % 100
    s = CN_DIGITS[h] + "百"
    if r == 0:
        return s
    if r < 10:
        return s + "零" + CN_DIGITS[r]
    if r < 20:
        return s + "一十" + (CN_DIGITS[r % 10] if r % 10 else "")
    return s + CN_DIGITS[r // 10] + "十" + (CN_DIGITS[r % 10] if r % 10 else "")


def fullwidth(n: int) -> str:
    return "".join(FULLWIDTH_DIGITS[int(d)] for d in str(n))


def heading_maker(kind: int, n: int, title: str) -> str:
    cn = cn_number(n)
    fw = fullwidth(n)
    return [
        # 0-3：最常见写法
        "第{n}章 {t}".format(n=n, t=title),
        "第{n}章：{t}".format(n=n, t=title),
        "第{n}章".format(n=n),
        "第{cn}章 {t}".format(cn=cn, t=title),
        # 4-7：次要写法
        "第{n}节 {t}".format(n=n, t=title),
        "第{n}回 {t}".format(n=n, t=title),
        "卷{cn} {t}".format(cn=cn, t=title),
        "Chapter {n} {t}".format(n=n, t=title),
        # 8-9：编号式
        "{n}. {t}".format(n=n, t=title),
        "{n}、{t}".format(n=n, t=title),
        # 10-11：番外/附录
        "番外 {t}".format(t=title),
        "附录 {t}".format(t=title),
        # 12-15：网络 TXT 的真实噪声（书名号/引号包裹、全角数字、小节号）
        "【第{n}章 {t}】".format(n=n, t=title),
        "「第{n}章 {t}」".format(n=n, t=title),
        "第{fw}章 {t}".format(fw=fw, t=title),
        "{n}.1 {t}".format(n=n, t=title),
    ][kind % 16]


# ---------------------------------------------------------------------------
# F02：TXT 章节基准语料
# ---------------------------------------------------------------------------

def gen_txt_corpus(count: int = 60):
    rows = []
    for fi in range(count):
        lines = []
        positives = []
        base_kind = RNG.randrange(16)
        n_chapters = RNG.randint(3, 10)
        chap_no = RNG.randint(1, 40)
        for ci in range(n_chapters):
            # 标题（正样本）
            title = RNG.choice(TITLE_POOL)
            if RNG.random() < 0.12:
                head = RNG.choice(SPECIAL_TITLES)
            else:
                head = heading_maker(base_kind + (ci % 3), chap_no, title)
            positives.append(len(lines))
            lines.append(head)
            lines.append("")

            # 正文
            for _ in range(RNG.randint(4, 12)):
                lines.append(RNG.choice(BODY_POOL))
                lines.append("")
            # 负样本注入
            if RNG.random() < 0.6:
                lines.append(RNG.choice(NEG_CHAPTER_IN_SENTENCE))
                lines.append("")
            if RNG.random() < 0.4:
                lines.append(RNG.choice(NEG_NUM_BODY))
                lines.append("")
            if RNG.random() < 0.3:
                lines.append(RNG.choice(NEG_LONG))
                lines.append("")
            if RNG.random() < 0.35:
                lines.append(RNG.choice(NEG_TOC))
                lines.append("")
            if RNG.random() < 0.2:
                lines.append(RNG.choice(NEG_DATE))
                lines.append("")
            chap_no += 1

        name = "f02_%02d.txt" % fi
        with open(os.path.join(BENCH_TXT, name), "w", encoding="utf-8", newline="\n") as f:
            f.write("\n".join(lines))
        rows.append((name, ",".join(str(i) for i in positives), len(lines), len(positives)))

    with open(os.path.join(BENCH_TXT, "truth.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# file\tpositive_line_indices\n")
        for name, pos, total, npos in rows:
            f.write("%s\t%s\n" % (name, pos))
    print("[F02] %d 个 TXT，标题行合计 %d" % (len(rows), sum(r[3] for r in rows)))
    return rows


# ---------------------------------------------------------------------------
# F08：PDF 扫描版基准语料
# ---------------------------------------------------------------------------

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\simsun.ttc",
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
]

SCAN_W, SCAN_H = 560, 790


def load_font(size: int):
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()


def make_scan_image(seed_text_lines):
    """生成一张仿真扫描页（1-bit，压得很小）。"""
    img = Image.new("1", (SCAN_W, SCAN_H), 1)
    d = ImageDraw.Draw(img)
    f = load_font(18)
    y = 60
    for line in seed_text_lines:
        d.text((50, y), line, font=f, fill=0)
        y += 30
        if y > SCAN_H - 60:
            break
    # 一点噪声，避免纯白被过分成“无内容”
    for _ in range(400):
        d.point((RNG.randrange(SCAN_W), RNG.randrange(SCAN_H)), fill=0)
    return img


def write_scan_pdf(path, pages, tmp_png):
    doc = fitz.open()
    for _ in range(pages):
        page = doc.new_page(width=595, height=842)
        lines = [RNG.choice(BODY_POOL) for _ in range(24)]
        make_scan_image(lines).save(tmp_png, format="PNG", optimize=True)
        page.insert_image(page.rect, filename=tmp_png)
    doc.save(path, deflate=True)
    doc.close()


def write_text_pdf(path, pages, with_logo=False, with_bg=False):
    doc = fitz.open()
    for p in range(pages):
        page = doc.new_page(width=595, height=842)
        if with_bg:
            # 整页底图 + 文本层（图文混排杂志的典型形态）：
            # 图像信号满分，但文本也在 —— 判定必须是「非扫描」，用来压 AND 逻辑写反的风险
            make_scan_image([RNG.choice(BODY_POOL) for _ in range(20)]).save(BG_PNG, format="PNG", optimize=True)
            page.insert_image(page.rect, filename=BG_PNG)
        y = 60
        for r in range(38):
            page.insert_text((60, y), RNG.choice(BODY_POOL), fontname="china-s", fontsize=11)
            y += 20
        if with_logo:
            # 小图标：面积远小于整页，不足以构成“整页大图”
            page.insert_image(fitz.Rect(60, 40, 140, 120), filename=LOGO_PNG)
    doc.save(path, deflate=True)
    doc.close()


def write_empty_pdf(path, pages):
    doc = fitz.open()
    for _ in range(pages):
        doc.new_page(width=595, height=842)
    doc.save(path, deflate=True)
    doc.close()


LOGO_PNG = os.path.join(HERE, "_tmp_logo.png")
BG_PNG = os.path.join(HERE, "_tmp_bg.png")


def gen_pdf_corpus(n_scan=30, n_text=25, n_illus=5, n_empty=5, n_bgtext=5):
    tmp_png = os.path.join(HERE, "_tmp_scan.png")
    # 小图标
    Image.new("1", (80, 80), 1).save(LOGO_PNG, format="PNG")

    rows = []
    for i in range(n_scan):
        name = "f08_scan_%02d.pdf" % i
        write_scan_pdf(os.path.join(BENCH_PDF, name), RNG.randint(3, 12), tmp_png)
        rows.append((name, 1))
    for i in range(n_text):
        name = "f08_text_%02d.pdf" % i
        write_text_pdf(os.path.join(BENCH_PDF, name), RNG.randint(3, 12))
        rows.append((name, 0))
    for i in range(n_illus):
        name = "f08_illus_%02d.pdf" % i
        write_text_pdf(os.path.join(BENCH_PDF, name), RNG.randint(3, 8), with_logo=True)
        rows.append((name, 0))
    for i in range(n_bgtext):
        name = "f08_bgtext_%02d.pdf" % i
        write_text_pdf(os.path.join(BENCH_PDF, name), RNG.randint(3, 8), with_bg=True)
        rows.append((name, 0))
    for i in range(n_empty):
        name = "f08_empty_%02d.pdf" % i
        write_empty_pdf(os.path.join(BENCH_PDF, name), RNG.randint(3, 6))
        rows.append((name, 0))

    with open(os.path.join(BENCH_PDF, "truth.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# file\tscanned(1=yes,0=no)\n")
        for name, truth in rows:
            f.write("%s\t%d\n" % (name, truth))

    total = sum(os.path.getsize(os.path.join(BENCH_PDF, n)) for n, _ in rows) / 1048576
    print("[F08] %d 个 PDF（正样本 %d，负样本 %d），合计 %.2f MB"
          % (len(rows), sum(t for _, t in rows), len(rows) - sum(t for _, t in rows), total))

    for p in (tmp_png, LOGO_PNG, BG_PNG):
        if os.path.exists(p):
            os.remove(p)


# ---------------------------------------------------------------------------
# §9.3 边界夹具
# ---------------------------------------------------------------------------

def corrupt_last_outline_dest(path):
    """把最后一个书签条目的跳转目标改成指向不存在的对象（999 0 R）。

    目的：验证 PdfBoxBookmarks 在「单条目标页解析失败」时只让那一条 pageIndex=-1，
    而不是把整份大纲丢掉。set_toc 会钳位越界页码，所以只能存盘后改对象。
    注意 MuPDF 写的是 `/A << /S /GoTo /D [...] >>` 而不是 `/Dest`。
    """
    d = fitz.open(path)
    hits = []
    for xref in range(1, d.xref_length()):
        try:
            s = d.xref_object(xref)
        except Exception:
            continue
        if "/Title" in s and "/GoTo" in s:
            hits.append(xref)
    if not hits:
        print("  !! 未找到书签对象，无法构造坏目标夹具")
        d.close()
        return
    d.xref_set_key(hits[-1], "A/D", "[ 999 0 R /XYZ null null null ]")
    # 就地 save 到原路径会被 MuPDF 拒绝（非增量不行、增量又因对象流被拒），
    # 所以先存到临时文件再整体替换
    tmp_out = path + ".tmp"
    d.save(tmp_out, deflate=True, garbage=0)
    d.close()
    os.replace(tmp_out, path)
    print("  书签坏目标夹具：xref %d 的 /A /D 已改为 999 0 R" % hits[-1])


def gen_edge_fixtures():
    tmp_png = os.path.join(HERE, "_tmp_scan.png")

    # 1) 混合版：前半文本页，后半扫描页
    doc = fitz.open()
    for i in range(10):
        page = doc.new_page(width=595, height=842)
        if i < 5:
            y = 60
            for r in range(38):
                page.insert_text((60, y), RNG.choice(BODY_POOL), fontname="china-s", fontsize=11)
                y += 20
        else:
            make_scan_image([RNG.choice(BODY_POOL) for _ in range(24)]).save(tmp_png, format="PNG", optimize=True)
            page.insert_image(page.rect, filename=tmp_png)
    doc.save(os.path.join(FIX, "readit_mixed_10p.pdf"), deflate=True)
    doc.close()

    # 2) 扫描占多数的混合版（9/12 页为扫描）
    doc = fitz.open()
    for i in range(12):
        page = doc.new_page(width=595, height=842)
        if i < 3:
            y = 60
            for r in range(38):
                page.insert_text((60, y), RNG.choice(BODY_POOL), fontname="china-s", fontsize=11)
                y += 20
        else:
            make_scan_image([RNG.choice(BODY_POOL) for _ in range(24)]).save(tmp_png, format="PNG", optimize=True)
            page.insert_image(page.rect, filename=tmp_png)
    doc.save(os.path.join(FIX, "readit_mixed_scanheavy_12p.pdf"), deflate=True)
    doc.close()

    # 3) 损坏类
    good = os.path.join(FIX, "readit_text_20p.pdf")
    raw = open(good, "rb").read()
    with open(os.path.join(FIX, "readit_damaged_truncated.pdf"), "wb") as f:
        f.write(raw[: int(len(raw) * 0.55)])
    with open(os.path.join(FIX, "readit_damaged_badheader.pdf"), "wb") as f:
        f.write(b"GARBAGE!" + raw[8:])
    with open(os.path.join(FIX, "readit_damaged_zero.pdf"), "wb") as f:
        pass
    # xref 偏移改成越界值：考验 PdfBox 的重建能力
    broken_xref = raw.replace(b"startxref", b"startxrf", 1)
    with open(os.path.join(FIX, "readit_damaged_xref.pdf"), "wb") as f:
        f.write(broken_xref)

    # 4) AES 加密：两种都要。
    #    - 空用户口令：PdfBox 能解开（实测可正常打开），不能拿来验「加密不支持」
    #    - 带用户口令：才是真正的「打不开」，必须用这条验证 PdfOpenError
    doc = fitz.open(good)
    doc.save(
        os.path.join(FIX, "readit_encrypted_aes.pdf"),
        deflate=True,
        encryption=fitz.PDF_ENCRYPT_AES_256,
        owner_pw="owner",
        user_pw="",
        permissions=int(fitz.PDF_PERM_PRINT | fitz.PDF_PERM_COPY),
    )
    doc.close()

    doc = fitz.open(good)
    doc.save(
        os.path.join(FIX, "readit_encrypted_userpw.pdf"),
        deflate=True,
        encryption=fitz.PDF_ENCRYPT_AES_256,
        owner_pw="owner",
        user_pw="secret",
        permissions=int(fitz.PDF_PERM_PRINT | fitz.PDF_PERM_COPY),
    )
    doc.close()

    # 5) 书签夹具
    def build_with_toc(name, toc):
        d = fitz.open()
        for i in range(6):
            p = d.new_page(width=595, height=842)
            p.insert_text((60, 80), "第%d页 %s" % (i + 1, BODY_POOL[i % len(BODY_POOL)]),
                          fontname="china-s", fontsize=12)
        d.set_toc(toc)
        d.save(os.path.join(FIX, name), deflate=True)
        d.close()

    build_with_toc("readit_outline_flat.pdf", [
        [1, "第一章 山下的少年", 1],
        [1, "第二章 断剑", 3],
        [1, "第三章 雨夜", 5],
    ])
    build_with_toc("readit_outline_nested.pdf", [
        [1, "第一卷", 1],
        [2, "第一章 山下的少年", 1],
        [2, "第二章 断剑", 3],
        [1, "第二卷", 4],
        [2, "第三章 雨夜", 5],
    ])

    # 无书签
    d = fitz.open()
    for i in range(3):
        p = d.new_page(width=595, height=842)
        p.insert_text((60, 80), BODY_POOL[i], fontname="china-s", fontsize=12)
    d.save(os.path.join(FIX, "readit_outline_none.pdf"), deflate=True)
    d.close()

    # 书签目标页缺失（/Dest 指向不存在的页）-> 应得到 pageIndex=-1 而不是崩。
    # 注意：set_toc 会把越界页码静默钳到末页，所以必须在存盘后手工把 /Dest 改坏。
    d = fitz.open()
    for i in range(3):
        p = d.new_page(width=595, height=842)
        p.insert_text((60, 80), BODY_POOL[i], fontname="china-s", fontsize=12)
    d.set_toc([[1, "第一章 正常", 1], [1, "第二章 目标丢失", 2]])
    d.save(os.path.join(FIX, "readit_outline_brokendest.pdf"), deflate=True)
    d.close()
    corrupt_last_outline_dest(os.path.join(FIX, "readit_outline_brokendest.pdf"))

    print("[§9.3] 边界夹具已生成到 fixtures/")
    if os.path.exists(tmp_png):
        os.remove(tmp_png)


def write_plate_pdf(path, pages, plate_at, tmp_png):
    """文本文档 + 中间一页整页图版（书刊里插一整页图版的常见形态）。

    真值 = **0（非扫描）**：正文全是文本层，用户要的是文字版。
    `plate_at` 故意取 `pages//2`，即 `sampleIndices(total, 1)` 会命中的那一页——
    「单页采样」撞上图版就会把它误判成扫描版；3 页采样里图版只占 1/3，比例低于 0.5 阈值，不会误判。
    这是「采样 3→1」唯一的真实代价来源，别的同质语料都测不出来。
    """
    doc = fitz.open()
    for p in range(pages):
        page = doc.new_page(width=595, height=842)
        if p == plate_at:
            make_scan_image([RNG.choice(BODY_POOL) for _ in range(24)]).save(tmp_png, format="PNG", optimize=True)
            page.insert_image(page.rect, filename=tmp_png)
            continue
        y = 60
        for _ in range(38):
            page.insert_text((60, y), RNG.choice(BODY_POOL), fontname="china-s", fontsize=11)
            y += 20
    doc.save(path, deflate=True)
    doc.close()


def write_covertext_pdf(path, pages, tmp_png):
    """扫描书 + 文字扉页（第 0 页是文字，其余整页扫描）。

    真值 = **1（扫描版）**。它和 write_plate_pdf 是一对：
      - 采样「首页」-> 撞上文字扉页 -> 漏检；
      - 采样「中间页」-> 命中扫描正文 -> 正确。
    所以单页采样取 `total/2` 而不是第 0 页，是有依据的选择，这里把它钉住。
    扉页只写 5 行（约 100 余字），避免把 3 页采样的「平均字符数」抬过阈值反而漏检。
    """
    doc = fitz.open()
    for p in range(pages):
        page = doc.new_page(width=595, height=842)
        if p == 0:
            y = 120
            for _ in range(5):
                page.insert_text((60, y), RNG.choice(BODY_POOL), fontname="china-s", fontsize=11)
                y += 20
            continue
        make_scan_image([RNG.choice(BODY_POOL) for _ in range(24)]).save(tmp_png, format="PNG", optimize=True)
        page.insert_image(page.rect, filename=tmp_png)
    doc.save(path, deflate=True)
    doc.close()


def gen_hetero_corpus(n_plate=5, n_covertext=5):
    """异质语料：**页与页不一样**，专治上面那批同质语料的盲区。

    现有 `bench/pdf/` 每一类都是「每页形态相同」（扫描件每页都是扫描图、bgtext 每页都铺底图），
    所以采样 1 页和采样 5 页的结果必然一样 —— 实测 1/2/3/5 页全是 100% / 0%，
    这种一致是**构造出来的**，不能当作「少采样不影响准确率」的证据。
    这里补上两栖形态，才有资格回答「采样 3→1 会损失什么」。
    """
    tmp_png = os.path.join(HERE, "_tmp_hetero.png")

    rows = []
    for i in range(n_plate):
        pages = 5 + i                       # 5..9
        name = "f08_plate_%02d.pdf" % i
        write_plate_pdf(os.path.join(BENCH_HETERO, name), pages, pages // 2, tmp_png)
        rows.append((name, 0))
    for i in range(n_covertext):
        pages = 5 + i
        name = "f08_covertext_%02d.pdf" % i
        write_covertext_pdf(os.path.join(BENCH_HETERO, name), pages, tmp_png)
        rows.append((name, 1))

    with open(os.path.join(BENCH_HETERO, "truth.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("# file\tscanned(1=yes,0=no)\n")
        for name, truth in rows:
            f.write("%s\t%d\n" % (name, truth))

    total = sum(os.path.getsize(os.path.join(BENCH_HETERO, n)) for n, _ in rows) / 1048576
    print("[F08-异质] %d 个 PDF（正样本 %d，负样本 %d），合计 %.2f MB"
          % (len(rows), sum(t for _, t in rows), len(rows) - sum(t for _, t in rows), total))

    if os.path.exists(tmp_png):
        os.remove(tmp_png)


if __name__ == "__main__":
    gen_txt_corpus()
    gen_pdf_corpus()
    gen_edge_fixtures()
    # 放最后：RNG 是共享的，插在前面会改变 §9.3 边界夹具的随机内容（凭空多出一堆二进制 diff）
    gen_hetero_corpus()
    print("done")
