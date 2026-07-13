package com.arm.aichat.mcp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Abstraction over the underlying transport for an MCP connection.
 *
 * Two implementations are provided:
 * - [LocalSocketTransport] — Android Unix domain socket (same-device IPC)
 * - [TcpSocketTransport] — TCP socket (remote/desktop MCP servers)
 */
interface McpTransport : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream

    companion object {
        /**
         * Create a transport from an [McpServerConfig].
         * Throws [IllegalArgumentException] if the config is invalid.
         */
        fun create(config: McpServerConfig): McpTransport {
            if (config.address != null) {
                return LocalSocketTransport(config.address)
            }
            if (config.host != null && config.port != null) {
                return TcpSocketTransport(config.host, config.port)
            }
            throw IllegalArgumentException(
                "Invalid McpServerConfig: need 'address' (local) or 'host'+'port' (TCP)"
            )
        }
    }
}

/**
 * LocalSocket transport — Android Unix domain socket for same-device IPC.
 *
 * The MCP server runs as a separate Android process (Service or standalone app)
 * and listens on a [LocalSocketAddress] with the given [address] name.
 */
class LocalSocketTransport(private val address: String) : McpTransport {
    private val socket = LocalSocket()

    override lateinit var inputStream: InputStream
        private set
    override lateinit var outputStream: OutputStream
        private set

    init {
        try {
            socket.connect(LocalSocketAddress(address))
            inputStream = socket.inputStream
            outputStream = socket.outputStream
            Log.i(TAG, "LocalSocket connected to '$address'")
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing LocalSocket '$address': ${e.message}")
        }
    }

    companion object {
        private const val TAG = "McpTransport"
    }
}

/**
 * TCP socket transport — connects to a remote MCP server over the network.
 */
class TcpSocketTransport(private val host: String, private val port: Int) : McpTransport {
    private val socket = Socket()

    override lateinit var inputStream: InputStream
        private set
    override lateinit var outputStream: OutputStream
        private set

    init {
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            inputStream = socket.inputStream
            outputStream = socket.outputStream
            Log.i(TAG, "TCP socket connected to '$host:$port'")
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TCP socket '$host:$port': ${e.message}")
        }
    }

    private companion object {
        private const val TAG = "McpTransport"
        private const val CONNECT_TIMEOUT_MS = 5_000
    }
}