package `in`.isro.sih26173.itantramessage.data.nearby

import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

@Suppress("DEPRECATION")
class WifiDirectTransport private constructor(
    private val context: Context,
    private val deviceName: String,
    private val isGroupOwner: Boolean = false,
    private val overridePeerAddress: String? = null,
) : ByteLink {

    override val peerAddress: String?
        get() = overridePeerAddress ?: try {
            socket?.inetAddress?.hostAddress
        } catch (_: Exception) {
            null
        }

    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = manager?.initialize(context, context.mainLooper, null)

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private val outbox = LinkedBlockingQueue<ByteArray>()
    private val incomingBytes = Channel<ByteArray>(capacity = 256)

    override val incoming: Flow<ByteArray> = flow {
        for (bytes in incomingBytes) {
            emit(bytes)
        }
    }

    override val peerLabel: String
        get() = deviceName

    override val isOpen: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    override suspend fun send(bytes: ByteArray): Boolean {
        if (!isOpen) return false
        outbox.offer(bytes)
        return true
    }

    override fun close() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        readerThread?.interrupt()
        writerThread?.interrupt()
    }

    private fun startServer() {
        readerThread = Thread {
            try {
                serverSocket = ServerSocket(8988)
                socket = serverSocket?.accept()
                startIo()
            } catch (e: Exception) {
                Log.w(TAG, "server failed", e)
            }
        }.apply { start() }
    }

    private fun startClient(host: String) {
        readerThread = Thread {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, 8988), 5000)
                socket = s
                startIo()
            } catch (e: Exception) {
                Log.w(TAG, "client failed", e)
            }
        }.apply { start() }
    }

    private fun startIo() {
        val s = socket ?: return
        val inStream: InputStream = s.getInputStream()
        val outStream: OutputStream = s.getOutputStream()

        Thread {
            val buf = ByteArray(4096)
            try {
                while (!Thread.currentThread().isInterrupted && isOpen) {
                    val n = inStream.read(buf)
                    if (n <= 0) break
                    val copy = buf.copyOfRange(0, n)
                    incomingBytes.trySend(copy)
                }
            } catch (_: Exception) {
            }
        }.apply { readerThread = this; start() }

        Thread {
            try {
                while (!Thread.currentThread().isInterrupted && isOpen) {
                    val data = outbox.take()
                    outStream.write(data)
                    outStream.flush()
                }
            } catch (_: Exception) {
            }
        }.apply { writerThread = this; start() }
    }

    companion object {
        private const val TAG = "WifiDirectTransport"

        fun connect(context: Context, device: WifiP2pDevice, deviceName: String): Result<ByteLink> {
            val t = WifiDirectTransport(context, deviceName, isGroupOwner = false, overridePeerAddress = device.deviceAddress)
            t.startClient(device.deviceAddress)
            return Result.success(t)
        }

        fun accept(context: Context, deviceName: String): Result<ByteLink> {
            val t = WifiDirectTransport(context, deviceName, isGroupOwner = true)
            t.startServer()
            return Result.success(t)
        }
    }
}
