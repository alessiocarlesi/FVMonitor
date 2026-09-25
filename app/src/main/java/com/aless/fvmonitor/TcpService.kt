package com.aless.fvmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class TcpService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null

    private var socket: Socket? = null
    private var writer: OutputStream? = null

    var onLineReceived: ((String) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean, String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): TcpService = this@TcpService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    fun startConnection(host: String, port: Int) {
        // Annulla sempre eventuali tentativi precedenti e chiude il vecchio socket
        connectionJob?.cancel()

        connectionJob = serviceScope.launch {
            closeSocket()

            withContext(Dispatchers.Main) {
                onConnectionStateChanged?.invoke(false, "Connessione in corso a $host:$port...")
            }

            try {
                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(host, port), 5000)

                synchronized(this@TcpService) {
                    socket = newSocket
                    writer = newSocket.getOutputStream()
                }

                withContext(Dispatchers.Main) {
                    onConnectionStateChanged?.invoke(true, "CONNESSO a $host:$port")
                }

                val reader = BufferedReader(InputStreamReader(newSocket.getInputStream()))

                while (isActive && newSocket.isConnected && !newSocket.isClosed) {
                    val line = reader.readLine() ?: break
                    withContext(Dispatchers.Main) {
                        onLineReceived?.invoke(line)
                    }
                }

            } catch (e: Exception) {
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        onConnectionStateChanged?.invoke(false, "Errore connessione: ${e.localizedMessage}")
                    }
                }
            } finally {
                closeSocket()
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        onConnectionStateChanged?.invoke(false, "DISCONNESSO")
                    }
                }
            }
        }
    }

    fun sendCommand(cmd: String) {
        serviceScope.launch {
            try {
                writer?.let {
                    it.write((cmd + "\n").toByteArray())
                    it.flush()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onConnectionStateChanged?.invoke(false, "Err Invio: ${e.message}")
                }
            }
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        writer = null
    }

    private fun startForegroundNotification() {
        val channelId = "fv_monitor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "FV Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FV Monitor Active")
            .setContentText("Monitoraggio TCP in esecuzione")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(1001, notification)
        }
    }

    override fun onDestroy() {
        closeSocket()
        serviceScope.cancel()
        super.onDestroy()
    }
}