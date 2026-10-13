package com.readit.sync.http

import android.os.Build
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * API 19 TLS 1.2 强制启用（规范 §5.3，R21）。
 *
 * 背景：Android 4.4 底层支持 TLS 1.2 但默认不启用，仅配置 ConnectionSpec 不够，
 * 必须在 createSocket 后对每个 SSLSocket 显式 setEnabledProtocols。
 *
 * **只对 API 19 收窄**（见 [enabledProtocolsFor]）：更低的 API 走不到这条路，
 * 更高的 API 应保留平台默认协议集，否则会把协议上限钉死在 1.2。
 */
class Tls12SocketFactory(
    private val delegate: SSLSocketFactory,
    private val trustManager: X509TrustManager
) : SSLSocketFactory() {

    companion object {
        /**
         * 目标协议集。
         *
         * 需求只要求**API 19** 补上 TLS 1.2（Android 4.4 底层支持但默认不启用）。
         * 原实现是 `arrayOf("TLSv1.2")` 且对**所有 API** 生效 —— 那等于把协议上限钉死在
         * 1.2：如今逐步只留 TLS 1.3 的公网 / 家宽 NAS 端点上，握手会持续失败，
         * 而且失败会被记成「同步失败」而不是「冲突」，用户无从判断原因。
         *
         * 正确做法：API 20+ 保留 socket 默认的协议集（由平台决定，本来就含 1.3），
         * 只在 API 19 这一档补 1.2。
         */
        @JvmStatic
        fun needsTls12Override(sdkInt: Int): Boolean = sdkInt <= Build.VERSION_CODES.KITKAT
    }

    fun trustManager(): X509TrustManager = trustManager

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    @Throws(IOException::class)
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        forceTls12(delegate.createSocket(s, host, port, autoClose))

    @Throws(IOException::class)
    override fun createSocket(host: String, port: Int): Socket =
        forceTls12(delegate.createSocket(host, port))

    @Throws(IOException::class)
    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket = forceTls12(delegate.createSocket(host, port, localHost, localPort))

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress, port: Int): Socket =
        forceTls12(delegate.createSocket(host, port))

    @Throws(IOException::class)
    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = forceTls12(delegate.createSocket(address, port, localAddress, localPort))

    private fun forceTls12(socket: Socket): Socket {
        // 只在 API 19 收窄。更高版本**完全不碰** enabledProtocols：
        // 赋空数组会让握手直接失败，而赋 ["TLSv1.2"] 等于把协议上限钉死在 1.2，
        // 只留 1.3 的服务端就再也连不上了。
        if (socket !is SSLSocket) return socket
        if (!needsTls12Override(Build.VERSION.SDK_INT)) return socket
        return try {
            socket.enabledProtocols = arrayOf("TLSv1.2")
            socket
        } catch (e: Exception) {
            com.readit.core.util.ReadItLog.w("force TLS1.2 failed: ${e.message}")
            socket
        }
    }
}
