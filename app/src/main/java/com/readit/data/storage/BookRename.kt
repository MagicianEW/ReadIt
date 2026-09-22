package com.readit.data.storage

/**
 * 「重命名书籍」的纯判定逻辑（无 Android 依赖，JVM 可直接测）。
 *
 * 为什么单独抽出来：文件名合法性 / 重名冲突是这个功能里唯一会出事的地方
 * （非法字符 → 改名直接失败；重名 → 把另一本书覆盖掉，属**静默数据丢失**），
 * 而这段判定一旦写在 Activity 里就再也测不到。
 *
 * 口径说明：
 *  - 用户只改**基名**，扩展名由当前文件名固定继承 —— 扩展名决定了解析链路
 *    （TXT / EPUB / DOCX / PDF），让用户顺手改掉就把书打不开了。
 *  - 但如果用户自己把扩展名也打进来了（`b.txt`），不再叠加成 `b.txt.txt`。
 *  - 首尾空白直接 trim：Linux 允许尾随空格，但用户在界面上看不见，留着只会困惑。
 *  - 前缀点（`.hidden`）不当扩展名看。
 */
object BookRename {

    /**
     * 单文件名上限。ext4/FAT32 都是 255 **字节**，中文按 UTF-8 占 3 字节/字，
     * 取 200 留出余量（改名过程中可能经过临时目录）。
     */
    const val MAX_BYTES = 200

    enum class Reason { OK, EMPTY, ILLEGAL_CHAR, TOO_LONG, UNCHANGED, EXISTS }

    data class Result(val reason: Reason, val newFileName: String? = null) {
        val ok: Boolean get() = reason == Reason.OK
    }

    /** 拆成（基名, 扩展名含点）。无扩展名 / 前缀点开头时扩展名为空串。 */
    fun splitExt(fileName: String): Pair<String, String> {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) fileName to "" else fileName.substring(0, dot) to fileName.substring(dot)
    }

    /**
     * 判定 [input] 能否作为 [currentFileName] 的新名字。
     *
     * @param exists 目标名是否已被占用。抽成函数是为了让判定保持纯净（不碰文件系统）。
     */
    fun decide(currentFileName: String, input: String, exists: (String) -> Boolean): Result {
        val base = input.trim()
        if (base.isEmpty()) return Result(Reason.EMPTY)
        // 只禁 `/` `\` 与控制字符：前者是目录穿越，后者会让设备端 shell / 日志出乱子。
        // 其余字符（`*?:<>|"` 等）在 Linux 上合法，不额外限制 —— 过度设限只会让人困惑。
        if (base.any { it == '/' || it == '\\' || it < ' ' || it == '\u007F' }) {
            return Result(Reason.ILLEGAL_CHAR)
        }

        val (_, ext) = splitExt(currentFileName)
        val candidate =
            if (ext.isNotEmpty() && base.length > ext.length && base.endsWith(ext, ignoreCase = true)) base
            else base + ext

        if (candidate == currentFileName) return Result(Reason.UNCHANGED)
        if (candidate.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return Result(Reason.TOO_LONG)
        if (exists(candidate)) return Result(Reason.EXISTS)
        return Result(Reason.OK, candidate)
    }
}
