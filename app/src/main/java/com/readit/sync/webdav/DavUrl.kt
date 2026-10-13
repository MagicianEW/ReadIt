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
     *  - **剥掉 URL 里的 userinfo**（`http://user:pass@nas` -> `http://nas`）
     *  - 去掉尾部斜杠，避免后续拼接出现 `//`
     */
    fun normalizeInput(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val withScheme = if (hasScheme(t)) t else "http://$t"
        return stripUserInfo(withScheme).trimEnd('/')
    }

    /** 用户是否在地址里内嵌了用户名/密码（设置页据此提示改到下面单独填） */
    fun hasUserInfo(raw: String): Boolean = userInfoOf(raw.trim()) != null

    /**
     * 剥掉 `scheme://user:pass@host` 里的 userinfo。
     *
     * 为什么必须剥：凭据本来就存在 `webdav_password` 这个独立字段里，写在 URL 里没有任何好处，
     * 反而有两个具体后果 ——
     *  1. 任何一次 HTTP 失败抛出的 `UnknownHostException` / `SSLHandshakeException` 消息里
     *     都带着完整 URL，密码直接进 logcat（`adb logcat` 在已 root / 维修模式下人人可读）；
     *  2. `DavUrl.resolve()` 走 [originOf] 拼路径，userinfo 会被带进**每一个**请求 URL。
     *
     * 注意只认 authority 段里的 `@`：路径或 query 里的 `@`（`/dav/user@host/x`）不能被吃掉。
     */
    fun stripUserInfo(url: String): String {
        val t = url.trim()
        val at = authorityUserInfoEnd(t) ?: return t
        val start = t.indexOf("://") + 3
        return t.substring(0, start) + t.substring(at + 1)
    }

    /** 取 authority 段里最后一个 `@` 的下标；没有 userinfo 则 null */
    private fun authorityUserInfoEnd(url: String): Int? {
        // 缺 scheme 时（用户在输入框里直接写 `u:p@nas.local`）也要认，
        // 否则设置页的「已内嵌凭据」提示对最常见的那种输入方式不生效。
        val i = url.indexOf("://")
        val start = if (i > 0) i + 3 else 0
        var authorityEnd = url.length
        var k = start
        while (k < url.length) {
            val c = url[k]
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = k
                break
            }
            k++
        }
        val at = url.lastIndexOf('@', authorityEnd - 1)
        return if (at >= start) at else null
    }

    /** 取 authority 段里的 userinfo（`user` 或 `user:pass`）；没有则 null */
    private fun userInfoOf(url: String): String? {
        val at = authorityUserInfoEnd(url) ?: return null
        val i = url.indexOf("://")
        val start = if (i > 0) i + 3 else 0
        return url.substring(start, at).takeIf { it.isNotEmpty() }
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

    /**
     * `scheme://host[:port]`；无法解析时返回 null。
     *
     * 这里也过一遍 [stripUserInfo]：它是 [resolve] 拼路径的唯一出口，
     * 在这里兜住就保证**任何**进到请求 URL 的地址都不带凭据，而不只是设置页走过的那条路。
     */
    fun originOf(url: String): String? {
        val clean = stripUserInfo(url.trim())
        val i = clean.indexOf("://")
        if (i <= 0) return null
        val start = i + 3
        val slash = clean.indexOf('/', start)
        val origin = if (slash < 0) clean else clean.substring(0, slash)
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
