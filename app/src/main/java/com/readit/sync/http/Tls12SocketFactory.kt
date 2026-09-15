package com.readit.sync.http

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
 */
class Tls12SocketFactory(
    private val delegate: SSLSocketFactory,
    private val trustManager: X509TrustManager
) : SSLSocketFactory() {

    companion object {
        private val ENABLED = arrayOf("TLSv1.2")
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
        if (socket is SSLSocket) {
            try {
                socket.enabledProtocols = ENABLED
            } catch (e: Exception) {
                com.readit.core.util.ReadItLog.w("force TLS1.2 failed: ${e.message}")
            }
        }
        return socket
    }
}
