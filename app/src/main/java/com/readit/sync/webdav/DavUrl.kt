package com.readit.sync.webdav

import java.net.URLEncoder

/**
 * WebDAV URL 规则（F13 / R19）。
 *
 * 之所以单独抽出来：这一层最容易出错，而且错误表现为「同步什么都没发生」这种
 * 无从排查的静默失败。做成无 Android、无网络依赖的纯逻辑后可以在 JVM 上跑边界用例。
 *
 * 关键背景：RFC 4918 规定 `D:href` 是一个 **URI 引用**，主流服务端（sabre/dav、
 * Nextcloud、Apache mod_dav、IIS）实际返回的是 **路径绝对引用**（以 `/` 开头，
 * 形如 `/remote.php/dav/files/u/ReadIt/book.txt`）。
 * 如果把它当成「相对 base 的路径」去拼接，结果会变成
 * `https://host/remote.php/dav/files/u/ReadIt/remote.php/dav/files/u/ReadIt/book.txt`
 * —— 请求必然 404。所以必须按 RFC 3986 §5.3 解析，而不是简单拼接。
 */
object DavUrl {

    /** 是否已带 scheme */
    fun hasScheme(url: String): Boolean {
        val u = url.trim()
        return u.startsWith("http://", ignoreCase = true) ||
            u.startsWith("https://", ignoreCase = true)
    }

    /** 明文（非 TLS）地址。用于设置页给出提示 —— 明文 WebDAV 凭据是明码传输的 */
    fun isCleartext(url: String): Boolean =
        url.trim().startsWith("http://", ignoreCase = true)

    /**
     * 规范化用户输入的服务器地址：
     *  - 去首尾空白
     *  - 缺 scheme 时补 `http://`（局域网自建 WebDAV 绝大多数就是明文 http）
     *  - 去掉尾部斜杠，避免后续拼接出现 `//`
     */
    fun normalizeInput(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val withScheme = if (hasScheme(t)) t else "http://$t"
        return withScheme.trimEnd('/')
    }

    /**
     * 按 RFC 3986 §5.3 解析引用（这里只覆盖实际会遇到的三种形态）。
     *
     * @param base 基准 URL，通常是书库集合 URL（建议以 `/` 结尾）
     * @param href 待解析的引用
     *  - 绝对 URL                → 原样返回
     *  - 以 `/` 开头（路径绝对） → 换掉 base 的路径部分，保留 scheme + authority
     *  - 其他（相对引用）        → 拼到 base 目录下
     */
    fun resolve(base: String, href: String): String {
        val h = href.trim()
        if (h.isEmpty()) return base
        if (hasScheme(h)) return h
        val origin = originOf(base) ?: return base
        return if (h.startsWith("/")) {
            origin + h
        } else {
            val b = if (base.endsWith("/")) base else "$base/"
            b + h
        }
    }

    /** `scheme://host[:port]`；无法解析时返回 null */
    fun originOf(url: String): String? {
        val i = url.indexOf("://")
        if (i <= 0) return null
        val start = i + 3
        val slash = url.indexOf('/', start)
        val origin = if (slash < 0) url else url.substring(0, slash)
        return origin.trimEnd('/').ifEmpty { null }
    }

    /** 逐段编码路径（保留 `/` 分隔） */
    fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encodeSegment(it) }

    /** 编码单个路径段；空格编成 `%20` 而不是 `+`（`+` 在路径里是字面量） */
    fun encodeSegment(segment: String): String = try {
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    } catch (e: Exception) {
        segment
    }
}
