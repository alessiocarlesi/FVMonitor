package com.aless.fvmonitor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(context: Context) : ViewModel() {

    private val prefs = context.getSharedPreferences("fv_monitor_prefs", Context.MODE_PRIVATE)

    private val _host = MutableStateFlow(prefs.getString("ip_host", "192.168.10.222") ?: "192.168.10.222")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow(prefs.getInt("ip_port", 8888))
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _calV24 = MutableStateFlow(prefs.getFloat("cal_v24", 1.0f))
    val calV24: StateFlow<Float> = _calV24.asStateFlow()

    private val _calG1 = MutableStateFlow(prefs.getFloat("cal_g1", 1.0f))
    val calG1: StateFlow<Float> = _calG1.asStateFlow()

    private val _calG2 = MutableStateFlow(prefs.getFloat("cal_g2", 1.0f))
    val calG2: StateFlow<Float> = _calG2.asStateFlow()

    private val _calG3 = MutableStateFlow(prefs.getFloat("cal_g3", 1.0f))
    val calG3: StateFlow<Float> = _calG3.asStateFlow()

    private val _telemetry = MutableStateFlow(TelemetryData())
    val telemetry: StateFlow<TelemetryData> = _telemetry.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var tcpService: TcpService? = null

    fun bindTcpService(service: TcpService) {
        tcpService = service
        tcpService?.onLineReceived = { line ->
            processRawChunk(line)
        }
        tcpService?.onConnectionStateChanged = { connected, msg ->
            _isConnected.value = connected
            addLog(msg)
        }
        addLog("Service collegato. Avvio connessione...")
        connect()
    }

    fun updateSettings(newHost: String, newPort: Int, cV24: Float, cG1: Float, cG2: Float, cG3: Float) {
        prefs.edit()
            .putString("ip_host", newHost)
            .putInt("ip_port", newPort)
            .putFloat("cal_v24", cV24)
            .putFloat("cal_g1", cG1)
            .putFloat("cal_g2", cG2)
            .putFloat("cal_g3", cG3)
            .apply()

        _host.value = newHost
        _port.value = newPort
        _calV24.value = cV24
        _calG1.value = cG1
        _calG2.value = cG2
        _calG3.value = cG3

        addLog("Impostazioni salvate: V24=$cV24, G1=$cG1, G2=$cG2, G3=$cG3")
        connect()
    }

    fun connect() {
        if (tcpService != null) {
            tcpService?.startConnection(_host.value, _port.value)
        } else {
            addLog("Attesa binding del servizio...")
        }
    }

    fun sendCommand(cmd: String) {
        if (tcpService != null && _isConnected.value) {
            tcpService?.sendCommand(cmd)
            addLog("TX CMD: $cmd")
        } else {
            addLog("Impossibile inviare '$cmd': Non connesso")
        }
    }

    private fun processRawChunk(line: String) {
        try {
            if (line.contains("TEL;")) {
                val cleanStr = line.substring(line.indexOf("TEL;"))
                parseTelemetry(cleanStr)
                _isConnected.value = true
            } else if (line.contains("EVENTO;")) {
                addLog("EVENTO: ${line.substring(line.indexOf("EVENTO;"))}")
            } else if (line.contains("ACK") || line.contains("OK") || line.contains("ERR")) {
                addLog("RISPOSTA: $line")
            }
        } catch (e: Exception) {
            addLog("Err Proc Chunk: ${e.message}")
        }
    }

    private fun parseTelemetry(line: String) {
        try {
            val map = mutableMapOf<String, String>()
            val mainTokens = line.removePrefix("TEL;").split(";")

            for (token in mainTokens) {
                if (token.contains("=") && (token.startsWith("G1") || token.startsWith("G2") || token.startsWith("G3"))) {
                    val groupPrefix = token.take(2)
                    val subTokens = token.substring(3).split("_")
                    for (sub in subTokens) {
                        val kv = sub.split("=")
                        if (kv.size == 2) {
                            map["${groupPrefix}_${kv[0].trim()}"] = kv[1].trim()
                        }
                    }
                } else {
                    val kv = token.split("=")
                    if (kv.size == 2) {
                        map[kv[0].trim()] = kv[1].trim()
                    }
                }
            }

            fun parseV(key: String, factor: Float): Float {
                val valRaw = map[key]?.toFloatOrNull() ?: 0f
                return if (valRaw > 0.5f) valRaw * factor else 0f
            }

            val v24Val = parseV("V24", _calV24.value)

            val g1 = GroupData(
                id = 1,
                vLow = parseV("G1_Vb", _calG1.value),
                bus24 = v24Val,
                prot = map["G1_pr"] ?: map["G1_prot"] ?: "OK",
                trig = map["G1_tr"] ?: map["G1_trig"] ?: map["G1_cause"] ?: "-",
                mos = map["G1_m"] ?: map["G1_mos"] ?: "ON"
            )

            val g2 = GroupData(
                id = 2,
                vLow = parseV("G2_Vb", _calG2.value),
                bus24 = v24Val,
                prot = map["G2_pr"] ?: map["G2_prot"] ?: "OK",
                trig = map["G2_tr"] ?: map["G2_trig"] ?: map["G2_cause"] ?: "-",
                mos = map["G2_m"] ?: map["G2_mos"] ?: "ON"
            )

            val g3 = GroupData(
                id = 3,
                vLow = parseV("G3_Vb", _calG3.value),
                bus24 = v24Val,
                prot = map["G3_pr"] ?: map["G3_prot"] ?: "OK",
                trig = map["G3_tr"] ?: map["G3_trig"] ?: map["G3_cause"] ?: "-",
                mos = map["G3_m"] ?: map["G3_mos"] ?: "ON"
            )

            _telemetry.value = TelemetryData(
                uptimeStr = map["uptime"] ?: "0d 0h 0m 0s",
                wifiRssi = map["rssi"]?.toIntOrNull() ?: 0,
                timestampMs = map["ms"]?.toLongOrNull() ?: 0L,
                state = map["stato"] ?: "SCONOSCIUTO",
                lastEvent = map["last_evt"] ?: "OK",
                v24Raw = v24Val,
                iBattTotal = map["IbatTot"]?.toFloatOrNull() ?: 0f,
                iPv = map["IPv"]?.toFloatOrNull() ?: 0f,
                pPv = map["PFV"]?.toFloatOrNull() ?: 0f,
                pAc = map["PAC_stim"]?.toFloatOrNull() ?: 0f,
                pAcMem = map["PAC_mem"]?.toFloatOrNull() ?: 0f,
                eKWh = map["EkWh"]?.toFloatOrNull() ?: 0f,
                releSpring = map["rele_spring"] ?: map["spring"] ?: "OFF",
                groups = listOf(g1, g2, g3)
            )

        } catch (e: Exception) {
            addLog("Err Parsing: ${e.message}")
        }
    }

    fun addLog(msg: String) {
        val timeSec = (System.currentTimeMillis() % 100000) / 1000
        _logs.value = (listOf("${timeSec}s: $msg") + _logs.value).take(50)
    }
}

class MainViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return MainViewModel(context) as T
    }
}