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
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class TcpService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null
    private var socket: Socket? = null
    private var writer: OutputStream? = null
    @Volatile private var otaMode = false
    @Volatile private var connected = false
    private var otaBytes = Channel<Byte>(Channel.UNLIMITED)
    private val writeLock = Any()

    var onLineReceived: ((String) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean, String) -> Unit)? = null

    inner class LocalBinder : Binder() { fun getService(): TcpService = this@TcpService }
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    fun startConnection(host: String, port: Int) {
        connectionJob?.cancel()
        connectionJob = serviceScope.launch {
            closeSocket()
            withContext(Dispatchers.Main) { onConnectionStateChanged?.invoke(false, "Connessione in corso a $host:$port...") }
            try {
                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(host, port), 5000)
                newSocket.tcpNoDelay = true
                socket = newSocket
                writer = newSocket.getOutputStream()
                connected = true
                withContext(Dispatchers.Main) { onConnectionStateChanged?.invoke(true, "CONNESSO a $host:$port") }

                val input = newSocket.getInputStream()
                val buffer = ByteArray(512)
                val line = StringBuilder()
                while (isActive && !newSocket.isClosed) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    for (i in 0 until count) {
                        val byte = buffer[i]
                        if (otaMode) {
                            otaBytes.trySend(byte)
                        } else if (byte == '\n'.code.toByte()) {
                            val completed = line.toString().trimEnd('\r')
                            line.setLength(0)
                            withContext(Dispatchers.Main) { onLineReceived?.invoke(completed) }
                        } else if (line.length < 4096) {
                            line.append(byte.toInt().toChar())
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) withContext(Dispatchers.Main) {
                    onConnectionStateChanged?.invoke(false, "Errore connessione: ${e.localizedMessage}")
                }
            } finally {
                connected = false
                closeSocket()
                if (isActive) withContext(Dispatchers.Main) {
                    onConnectionStateChanged?.invoke(false, "DISCONNESSO")
                }
            }
        }
    }

    fun sendCommand(cmd: String) {
        serviceScope.launch {
            try { sendBytes((cmd + "\n").toByteArray(Charsets.US_ASCII)) }
            catch (e: Exception) { withContext(Dispatchers.Main) { onConnectionStateChanged?.invoke(false, "Err Invio: ${e.message}") } }
        }
    }

    suspend fun uploadFirmware(hexText: String, progress: (Int, String) -> Unit) = withContext(Dispatchers.IO) {
        check(connected && socket?.isClosed == false) { "Non c'è una connessione TCP attiva." }
        val image = IntelHex.parse(hexText)
        check(image.isNotEmpty()) { "Il file HEX non contiene dati firmware." }
        check(image.keys.all { it in 0 until APP_LIMIT }) { "Il file contiene dati oltre l'area dello sketch (limite 0x3E000)." }

        otaMode = true
        otaBytes = Channel(Channel.UNLIMITED)
        var sequence = 0
        var shownProgress = 0
        try {
            progress(0, "Riavvio scheda e attesa bootloader…")
            sendBytes("REBOOT\n".toByteArray(Charsets.US_ASCII))
            // Give the remote TCP/DT06 path time to deliver the reset command and let the MCU restart.
            delay(1000)

            suspend fun transact(payload: ByteArray, phase: String): ByteArray {
                val seq = sequence++ and 0xff
                val frame = Stk500V2.frame(seq, payload)
                progress(shownProgress, phase)
                sendBytes(frame)
                // DT06/TCP may add substantial latency; tolerate delayed STK500v2 replies.
                val response = try {
                    withTimeout(10000) { Stk500V2.readFrame(otaBytes) }
                } catch (e: TimeoutCancellationException) {
                    throw IllegalStateException("Nessuna risposta entro 10 s: $phase.", e)
                }
                check(response.first == seq) { "Risposta STK500v2 con sequenza inattesa." }
                val body = response.second
                check(body.size >= 2 && body[1].toInt() == 0) {
                    "Il bootloader ha rifiutato il comando 0x%02X (stato %s).".format(payload[0], body.getOrNull(1)?.toUHex() ?: "assente")
                }
                return body
            }

            val signOn = transact(byteArrayOf(0x01), "Attendo identificazione del bootloader…")
            check(signOn.size >= 11) { "Risposta di identificazione bootloader incompleta." }
            transact(byteArrayOf(0x10, 0xC4.toByte(), 0, 0, 0, 0x53, 0, 0, 0, 0, 0, 0), "Configuro il bootloader…")

            // The bootloader erases pages in order from address zero, so send every page
            // through the highest used page, filling Intel HEX gaps with erased bytes.
            val pages = 0..(image.keys.maxOrNull()!! / PAGE_SIZE)
            pages.forEachIndexed { index, page ->
                val start = page * PAGE_SIZE
                val data = ByteArray(PAGE_SIZE) { offset -> image[start + offset] ?: 0xFF.toByte() }
                val wordAddress = start / 2
                val pageLabel = "pagina ${page + 1}/${pages.count()}"
                transact(byteArrayOf(0x06, (wordAddress ushr 24).toByte(), (wordAddress ushr 16).toByte(), (wordAddress ushr 8).toByte(), wordAddress.toByte()), "Imposto indirizzo per $pageLabel…")
                val pageCommand = byteArrayOf(
                    0x13, 0x01, 0x00, 0xC1.toByte(), 0x0A, 0x40, 0x4C, 0x20, 0xF4.toByte(), 0
                ) + data
                transact(pageCommand, "Scrivo $pageLabel…")
                transact(byteArrayOf(0x06, (wordAddress ushr 24).toByte(), (wordAddress ushr 16).toByte(), (wordAddress ushr 8).toByte(), wordAddress.toByte()), "Preparo verifica $pageLabel…")
                val readBack = transact(byteArrayOf(0x14, 0x01, 0x00, 0x20), "Verifico $pageLabel…")
                check(readBack.size == PAGE_SIZE + 3 && readBack.copyOfRange(2, PAGE_SIZE + 2).contentEquals(data)) {
                    "Verifica fallita alla pagina ${page}."
                }
                shownProgress = ((index + 1) * 100 / pages.count())
                progress(shownProgress, "Scrittura e verifica: ${index + 1}/${pages.count()} pagine")
            }
            transact(byteArrayOf(0x11), "Riavvio il firmware aggiornato…")
            progress(100, "Firmware scritto. La scheda sta riavviando.")
        } catch (e: Exception) {
            progress(-1, "Aggiornamento interrotto: ${e.message ?: "errore di comunicazione"}")
            throw e
        } finally {
            otaMode = false
            otaBytes.close()
        }
    }

    private fun sendBytes(bytes: ByteArray) {
        val out = writer ?: error("Connessione TCP chiusa.")
        synchronized(writeLock) { out.write(bytes); out.flush() }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        connected = false
    }

    private fun startForegroundNotification() {
        val channelId = "fv_monitor_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "FV Monitor Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FV Monitor Active")
            .setContentText("Monitoraggio TCP in esecuzione")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1001, notification)
    }

    override fun onDestroy() {
        closeSocket()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object { private const val PAGE_SIZE = 256; private const val APP_LIMIT = 0x3E000 }
}

private fun Byte.toUHex() = "%02X".format(toInt() and 0xff)


private object Stk500V2 {
    fun frame(sequence: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 0xffff)
        val frame = ByteArray(payload.size + 6)
        frame[0] = 0x1B
        frame[1] = sequence.toByte()
        frame[2] = (payload.size ushr 8).toByte()
        frame[3] = payload.size.toByte()
        frame[4] = 0x0E
        payload.copyInto(frame, 5)
        var checksum = 0
        for (i in 0 until frame.lastIndex) checksum = checksum xor (frame[i].toInt() and 0xff)
        frame[frame.lastIndex] = checksum.toByte()
        return frame
    }

    suspend fun readFrame(input: Channel<Byte>): Pair<Int, ByteArray> {
        suspend fun next(): Int = input.receive().toInt() and 0xff
        while (next() != 0x1B) { /* skip text emitted during reset */ }
        val sequence = next()
        val length = (next() shl 8) or next()
        check(next() == 0x0E) { "Token STK500v2 non valido." }
        val body = ByteArray(length)
        var checksum = 0x1B xor sequence xor (length ushr 8) xor (length and 0xff) xor 0x0E
        for (i in body.indices) { body[i] = next().toByte(); checksum = checksum xor (body[i].toInt() and 0xff) }
        check(next() == checksum) { "Controllo integrità STK500v2 non valido." }
        return sequence to body
    }
}

private object IntelHex {
    fun parse(text: String): Map<Int, Byte> {
        val bytes = mutableMapOf<Int, Byte>()
        var upper = 0
        var eof = false
        for ((lineNumber, raw) in text.lineSequence().withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            require(line.startsWith(":")) { "Riga HEX ${lineNumber + 1} non valida." }
            val record = line.drop(1).chunked(2).map { it.toInt(16) }
            require(record.size >= 5) { "Record HEX ${lineNumber + 1} incompleto." }
            val count = record[0]
            require(record.size == count + 5) { "Lunghezza errata alla riga ${lineNumber + 1}." }
            require((record.sum() and 0xff) == 0) { "Checksum errato alla riga ${lineNumber + 1}." }
            val address = (record[1] shl 8) or record[2]
            when (record[3]) {
                0 -> for (i in 0 until count) bytes[upper + address + i] = record[4 + i].toByte()
                1 -> { eof = true; break }
                2 -> { require(count == 2); upper = (((record[4] shl 8) or record[5]) shl 4) }
                4 -> { require(count == 2); upper = (((record[4] shl 8) or record[5]) shl 16) }
                3, 5 -> Unit
                else -> error("Tipo record HEX ${record[3]} non supportato.")
            }
        }
        require(eof) { "Il file HEX non termina con il record EOF." }
        return bytes
    }
}
