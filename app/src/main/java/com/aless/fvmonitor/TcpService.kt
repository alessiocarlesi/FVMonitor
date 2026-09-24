package com.aless.fvmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class TcpService : Service() {

    private val binder = LocalBinder()
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile private var isRunning = false

    var onLineReceived: ((String) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean, String) -> Unit)? = null

    companion object {
        private const val TAG = "TcpService"
        private const val CHANNEL_ID = "TcpServiceChannel"
        private const val NOTIFICATION_ID = 1001
    }

    inner class LocalBinder : Binder() {
        fun getService(): TcpService = this@TcpService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Servizio FV Monitor in ascolto...")
        startForeground(NOTIFICATION_ID, notification)
        acquireWifiLock()
        return START_STICKY
    }

    private fun acquireWifiLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "FVMonitor::WifiLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "WifiLock acquisito.")
    }

    fun startConnection(host: String, port: Int) {
        stopConnection()
        isRunning = true

        thread(start = true, isDaemon = true) {
            while (isRunning) {
                try {
                    onConnectionStateChanged?.invoke(false, "Connessione a $host:$port...")
                    socket = Socket()
                    socket?.tcpNoDelay = true // Invia/riceve immediatamente senza buffering Nagle
                    socket?.connect(InetSocketAddress(host, port), 5000)
                    socket?.soTimeout = 12000

                    outputStream = socket?.getOutputStream()
                    val inputStream: InputStream = socket!!.getInputStream()

                    onConnectionStateChanged?.invoke(true, "CONNESSO ALL'ARDUINO MEGA!")
                    updateNotification("Connesso a $host:$port")

                    val buffer = ByteArray(2048)
                    val stringBuilder = StringBuilder()

                    while (isRunning && socket?.isClosed == false) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break

                        val chunk = String(buffer, 0, bytesRead, Charsets.UTF_8)
                        stringBuilder.append(chunk)

                        var newlineIndex = stringBuilder.indexOf("\n")
                        while (newlineIndex != -1) {
                            val line = stringBuilder.substring(0, newlineIndex).trim()
                            stringBuilder.delete(0, newlineIndex + 1)

                            if (line.isNotEmpty()) {
                                onLineReceived?.invoke(line)
                            }
                            newlineIndex = stringBuilder.indexOf("\n")
                        }

                        if (stringBuilder.length > 4096) {
                            stringBuilder.clear()
                        }
                    }
                } catch (e: Exception) {
                    onConnectionStateChanged?.invoke(false, "Errore/Disconnesso: ${e.message}")
                } finally {
                    closeSocket()
                    if (isRunning) {
                        Thread.sleep(3000)
                    }
                }
            }
        }
    }

    fun sendCommand(cmd: String) {
        thread(start = true) {
            try {
                if (socket?.isConnected == true && outputStream != null) {
                    val formatted = if (cmd.endsWith("\n")) cmd else "$cmd\r\n"
                    outputStream?.write(formatted.toByteArray(Charsets.UTF_8))
                    outputStream?.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore invio comando: ${e.message}")
            }
        }
    }

    fun stopConnection() {
        isRunning = false
        closeSocket()
    }

    private fun closeSocket() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) {}
        onConnectionStateChanged?.invoke(false, "Disconnesso")
    }

    override fun onDestroy() {
        stopConnection()
        if (wifiLock?.isHeld == true) {
            wifiLock?.release()
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FV Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BiciTrack FV Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }
}