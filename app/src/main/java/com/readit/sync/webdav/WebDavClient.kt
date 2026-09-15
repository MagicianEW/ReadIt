package com.readit.sync.webdav

import com.readit.core.util.ReadItLog
import com.readit.sync.http.Tls12SocketFactory
import okhttp3.ConnectionSpec
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * WebDAV 客户端（规范 §5.3 / F13 / R19）。
 *
 * - User-Agent: ReadIt-Sync-Agent/1.0（§0 规范）
 * - TLS 1.2 强制（API 19）
 * - ETag 优先；无 ETag 用 Last-Modified + 客户端版本号兜底；不静默覆盖
 */
class WebDavClient(
    baseUrl: String,
    private val user: String,
    private val password: String,
    remoteDir: String = ""
) {

    companion object {
        const val USER_AGENT = "ReadIt-Sync-Agent/1.0"
        private const val NS_DAV = "DAV:"
    }

    /** 规范化后的服务器根地址（无尾斜杠） */
    private val base: String = baseUrl.trim().trimEnd('/')

    /**
     * 书库集合的完整 URL（`base` + `remoteDir`），无尾斜杠。
     *
     * 只在这里解析一次用户配置的目录：`remoteDir` 语义是「相对服务器地址的目录」，
     * 与 PROPFIND 返回的 href（可能是路径绝对引用）不是一回事，混在一起就会出现
     * 路径重复拼接。参见 [DavUrl.resolve] 的注释。
     */
    private val collectionUrl: String = run {
        val dir = remoteDir.trim().trim('/')
        if (dir.isEmpty()) base else "$base/${DavUrl.encodePath(dir)}"
    }

    /** 书库目录下某个文件的完整 URL（相对目录拼装，名称会被编码） */
    fun urlOf(name: String): String = "$collectionUrl/${DavUrl.encodeSegment(name)}"

    /** 把来自 PROPFIND 的 href 解析成可直接请求的绝对 URL */
    private fun absolute(href: String): String = DavUrl.resolve("$collectionUrl/", href)

    data class DavResource(
        val href: String,
        val displayName: String,
        val isDirectory: Boolean,
        val etag: String?,
        val lastModified: String?,
        val contentLength: Long
    ) {
        val isBook: Boolean
            get() = !isDirectory && BookType.of(displayName) != BookType.UNKNOWN
    }

    enum class BookType(val ext: String) {
        TXT("txt"), EPUB("epub"), DOCX("docx"), PDF("pdf"), UNKNOWN("");

        companion object {
            fun of(name: String): BookType {
                val e = name.substringAfterLast('.', "").lowercase()
                return values().firstOrNull { it.ext == e } ?: UNKNOWN
            }
        }
    }

    data class DownloadResult(
        val file: File,
        val etag: String?,
        val lastModified: String?,
        val size: Long,
        /** true 表示服务端资源未变化（304） */
        val notModified: Boolean = false
    )

    private val client: OkHttpClient by lazy { buildClient() }

    // ---------------- public API ----------------

    /** PROPFIND 列书库目录（Depth: 1）。目录由构造参数 [remoteDir] 决定。 */
    @Throws(IOException::class)
    fun list(): List<DavResource> {
        val url = "$collectionUrl/"
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop>
            <d:displayname/><d:getetag/><d:getlastmodified/><d:getcontentlength/>
            <d:resourcetype/>
            </d:prop></d:propfind>""".trimIndent()
        val req = Request.Builder()
            .url(url)
            .header("Depth", "1")
            .method("PROPFIND", okhttp3.RequestBody.create(null, body))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("PROPFIND ${resp.code()} on $url")
            val payload = resp.body()?.byteStream()
                ?: throw IOException("empty PROPFIND body")
            return parseMultiStatus(payload)
        }
    }

    /**
     * 下载到目标文件，支持条件请求。
     *
     * @param etag 本地已有 ETag（有则 If-None-Match）
     * @param lastModified 本地已有 Last-Modified（无 ETag 时 If-Modified-Since）
     */
    @Throws(IOException::class)
    fun download(href: String, dest: File, etag: String? = null, lastModified: String? = null): DownloadResult {
        val url = absolute(href)
        val b = Request.Builder().url(url)
        if (!etag.isNullOrEmpty()) b.header("If-None-Match", etag)
        else if (!lastModified.isNullOrEmpty()) b.header("If-Modified-Since", lastModified)

        client.newCall(b.build()).execute().use { resp ->
            if (resp.code() == 304) {
                return DownloadResult(dest, etag, lastModified, dest.length(), notModified = true)
            }
            if (!resp.isSuccessful) throw IOException("GET ${resp.code()} on $url")
            val body = resp.body() ?: throw IOException("empty body")
            val tmp = File(dest.parentFile, dest.name + ".part")
            var size = 0L
            body.byteStream().use { input ->
                tmp.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        size += n
                    }
                }
            }
            if (dest.exists() && !dest.delete()) ReadItLog.w("cannot delete old: ${dest.name}")
            if (!tmp.renameTo(dest)) throw IOException("rename failed: ${tmp.name}")
            ReadItLog.i("WebDAV download ok: ${dest.name} (${size}B)")
            return DownloadResult(dest, resp.header("ETag"), resp.header("Last-Modified"), size)
        }
    }

    /** HEAD：取 ETag / Last-Modified / Content-Length（同步前探测） */
    fun stat(href: String): Triple<String?, String?, Long>? = statResolved(absolute(href))

    private fun statResolved(url: String): Triple<String?, String?, Long>? {
        return try {
            val req = Request.Builder().url(url).head().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                Triple(
                    resp.header("ETag"),
                    resp.header("Last-Modified"),
                    resp.header("Content-Length")?.toLongOrNull() ?: -1L
                )
            }
        } catch (e: Exception) {
            ReadItLog.w("stat failed: ${e.message}")
            null
        }
    }

    data class UploadResult(
        val etag: String?,
        val lastModified: String?,
        /** true 表示远端不存在、本次为新建 */
        val created: Boolean
    )

    /**
     * 上传本地文件（PUT），用于双向同步的本地上行（F13）。
     *
     * [name] 是书库目录下的文件名（无需编码，内部会处理）。
     *
     * 并发保护（R19「不静默覆盖」）：
     *  - [remoteEtag] 非空 → 带 `If-Match`，远端若已被别人改过返回 412，本次放弃
     *  - [remoteEtag] 为空且 [createOnly] → 带 `If-None-Match: *`，远端已存在则 412，绝不覆盖
     *
     * @throws ConflictException 412 条件失败（调用方按「冲突」上报，不得重试覆盖）
     */
    @Throws(IOException::class)
    fun upload(
        name: String,
        file: File,
        remoteEtag: String? = null,
        createOnly: Boolean = remoteEtag.isNullOrEmpty()
    ): UploadResult {
        if (!file.exists()) throw IOException("upload source missing: ${file.name}")
        val url = urlOf(name)
        val body = okhttp3.RequestBody.create(
            okhttp3.MediaType.parse("application/octet-stream"),
            file
        )
        val builder = Request.Builder().url(url).put(body)
        when {
            !remoteEtag.isNullOrEmpty() -> builder.header("If-Match", remoteEtag)
            createOnly -> builder.header("If-None-Match", "*")
        }

        val existing = if (remoteEtag.isNullOrEmpty()) statResolved(url) else null

        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code() == 412) throw ConflictException("remote changed or already exists: $name")
            if (!resp.isSuccessful) throw IOException("PUT ${resp.code()} on $url")
            ReadItLog.i("WebDAV upload ok: ${file.name} (${file.length()}B)")
            return UploadResult(
                etag = resp.header("ETag"),
                lastModified = resp.header("Last-Modified") ?: existing?.second,
                created = existing == null && remoteEtag.isNullOrEmpty()
            )
        }
    }

    /** 远端资源与本地预期不一致（ETag/If-None-Match 条件失败） */
    class ConflictException(message: String) : IOException(message)

    // ---------------- internals ----------------

    private fun buildClient(): OkHttpClient {
        val trustManager = defaultTrustManager()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)

        val spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(okhttp3.TlsVersion.TLS_1_2)
            .build()

        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .connectionSpecs(listOf(spec, ConnectionSpec.CLEARTEXT))
            .sslSocketFactory(Tls12SocketFactory(sslContext.socketFactory, trustManager), trustManager)
            .addInterceptor(Interceptor { chain ->
                val r = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Authorization", Credentials.basic(user, password))
                    .build()
                chain.proceed(r)
            })
            .build()
    }

    private fun defaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    /** 极简 MultiStatus 解析（XmlPullParser，平台内置，无额外依赖） */
    private fun parseMultiStatus(input: java.io.InputStream): List<DavResource> {
        val out = ArrayList<DavResource>()
        val parser = android.util.Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        var href = ""
        var name = ""
        var etag: String? = null
        var lastMod: String? = null
        var len = -1L
        var isDir = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "href" -> href = readText(parser)
                    "displayname" -> name = readText(parser)
                    "getetag" -> etag = readText(parser)
                    "getlastmodified" -> lastMod = readText(parser)
                    "getcontentlength" -> len = readText(parser).toLongOrNull() ?: -1L
                    "collection" -> isDir = true
                }
                XmlPullParser.END_TAG -> if (parser.name == "response") {
                    val display = name.ifEmpty { href.trimEnd('/').substringAfterLast('/') }
                    out.add(
                        DavResource(
                            href = href,
                            displayName = decode(display),
                            isDirectory = isDir,
                            etag = etag?.trim('"'),
                            lastModified = lastMod,
                            contentLength = len
                        )
                    )
                    href = ""; name = ""; etag = null; lastMod = null; len = -1L; isDir = false
                }
            }
            event = parser.next()
        }
        return out.filter { it.href.isNotEmpty() }
    }

    private fun readText(parser: XmlPullParser): String {
        var text = ""
        if (parser.next() == XmlPullParser.TEXT) {
            text = parser.text
            parser.nextTag()
        }
        return text
    }

    private fun decode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }
}
