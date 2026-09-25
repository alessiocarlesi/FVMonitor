package com.aless.fvmonitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

// ============================================================================
// MODELLI DATI
// ============================================================================
data class GroupData(
    val id: Int,
    val vLow: Float = 0f,
    val vHighMeas: Float = 0f,
    val prot: String = "OK",
    val trig: String = "-",
    val mos: String = "OFF"
) {
    val vHighReal: Float get() = (vHighMeas - vLow).coerceAtLeast(0f)
    val vTotalGroup: Float get() = vHighMeas
}

data class TelemetryData(
    val uptimeStr: String = "0d 0h 0m 0s",
    val wifiRssi: Int = 0,
    val timestampMs: Long = 0,
    val state: String = "SCONOSCIUTO",
    val v24Raw: Float = 0f,
    val iBattTotal: Float = 0f,
    val iPv: Float = 0f,
    val pPv: Float = 0f,
    val pAc: Float = 0f,
    val pAcMem: Float = 0f,
    val eKWh: Float = 0f,
    val releSpring: String = "OFF",
    val groups: List<GroupData> = listOf(GroupData(1), GroupData(2), GroupData(3))
) {
    val v24Effective: Float
        get() {
            if (v24Raw > 5f) return v24Raw
            val activeGroups = groups.filter { it.vTotalGroup > 10f }
            return if (activeGroups.isNotEmpty()) {
                activeGroups.map { it.vTotalGroup }.average().toFloat()
            } else {
                0f
            }
        }
}

// ============================================================================
// VIEWMODEL
// ============================================================================
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
        tcpService?.sendCommand(cmd)
    }

    private fun processRawChunk(line: String) {
        try {
            if (line.contains("TEL;")) {
                val cleanStr = line.substring(line.indexOf("TEL;"))
                parseTelemetry(cleanStr)
                _isConnected.value = true
            } else if (line.contains("EVENTO;")) {
                addLog("EVENTO: ${line.substring(line.indexOf("EVENTO;"))}")
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

            val g1 = GroupData(
                id = 1,
                vLow = parseV("G1_Vb", _calG1.value),
                vHighMeas = parseV("G1_Va", 1.0f),
                prot = map["G1_pr"] ?: map["G1_prot"] ?: "OK",
                trig = map["G1_tr"] ?: map["G1_trig"] ?: "-",
                mos = map["G1_m"] ?: map["G1_mos"] ?: "ON"
            )

            val g2 = GroupData(
                id = 2,
                vLow = parseV("G2_Vb", _calG2.value),
                vHighMeas = parseV("G2_Va", 1.0f),
                prot = map["G2_pr"] ?: map["G2_prot"] ?: "OK",
                trig = map["G2_tr"] ?: map["G2_trig"] ?: "-",
                mos = map["G2_m"] ?: map["G2_mos"] ?: "ON"
            )

            val g3 = GroupData(
                id = 3,
                vLow = parseV("G3_Vb", _calG3.value),
                vHighMeas = parseV("G3_Va", 1.0f),
                prot = map["G3_pr"] ?: map["G3_prot"] ?: "OK",
                trig = map["G3_tr"] ?: map["G3_trig"] ?: "-",
                mos = map["G3_m"] ?: map["G3_mos"] ?: "ON"
            )

            _telemetry.value = TelemetryData(
                uptimeStr = map["uptime"] ?: "0d 0h 0m 0s",
                wifiRssi = map["rssi"]?.toIntOrNull() ?: 0,
                timestampMs = map["ms"]?.toLongOrNull() ?: 0L,
                state = map["stato"] ?: "SCONOSCIUTO",
                v24Raw = parseV("V24", _calV24.value),
                iBattTotal = map["IbatTot"]?.toFloatOrNull() ?: 0f,
                iPv = map["IPv"]?.toFloatOrNull() ?: 0f,
                pPv = map["PFV"]?.toFloatOrNull() ?: 0f,
                pAc = map["PAC_stim"]?.toFloatOrNull() ?: 0f,
                pAcMem = map["PAC_mem"]?.toFloatOrNull() ?: 0f,
                eKWh = map["EkWh"]?.toFloatOrNull() ?: 0f,
                releSpring = map["rele_spring"] ?: "OFF",
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

// ============================================================================
// MAIN ACTIVITY
// ============================================================================
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModelFactory(applicationContext) }
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TcpService.LocalBinder
            isBound = true
            viewModel.bindTcpService(binder.getService())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        val serviceIntent = Intent(this, TcpService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DashboardScreen(viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {}
            isBound = false
        }
        super.onDestroy()
    }
}

// ============================================================================
// COMPOSABLE UI
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val telemetry by viewModel.telemetry.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val currentHost by viewModel.host.collectAsState()
    val currentPort by viewModel.port.collectAsState()

    val calV24 by viewModel.calV24.collectAsState()
    val calG1 by viewModel.calG1.collectAsState()
    val calG2 by viewModel.calG2.collectAsState()
    val calG3 by viewModel.calG3.collectAsState()

    var showSettingsDialog by remember { mutableStateOf(false) }

    if (showSettingsDialog) {
        SettingsDialog(
            initialHost = currentHost,
            initialPort = currentPort,
            initialV24 = calV24,
            initialG1 = calG1,
            initialG2 = calG2,
            initialG3 = calG3,
            onDismiss = { showSettingsDialog = false },
            onSave = { newHost, newPort, cV24, cG1, cG2, cG3 ->
                viewModel.updateSettings(newHost, newPort, cV24, cG1, cG2, cG3)
                showSettingsDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FV Monitor", fontWeight = FontWeight.Bold) },
                actions = {
                    if (isConnected && telemetry.wifiRssi != 0) {
                        val rssiColor = when {
                            telemetry.wifiRssi > -70 -> Color.Green
                            telemetry.wifiRssi > -85 -> Color(0xFFFFC107)
                            else -> Color.Red
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = "Segnale Wi-Fi",
                                tint = rssiColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = "${telemetry.wifiRssi} dBm",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = rssiColor
                            )
                        }
                    }

                    Text(
                        text = if (isConnected) "ONLINE" else "OFFLINE",
                        color = if (isConnected) Color.Green else Color.Red,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Impostazioni")
                    }
                    IconButton(onClick = { viewModel.connect() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Riconnetti")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StateHeaderCard(telemetry)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Bus 24V", "%.2f V".format(telemetry.v24Effective), Modifier.weight(1f), Color(0xFF2196F3))
                MetricCard("Solare FV", "%.1f W".format(telemetry.pPv), Modifier.weight(1f), Color(0xFFFFC107), "%.1f A".format(telemetry.iPv))
                MetricCard("Carico AC", "%.1f W".format(telemetry.pAc), Modifier.weight(1f), Color(0xFFFF5722), "Mem: %.0fW".format(telemetry.pAcMem))
            }

            BatteryTotalCard(iBattTotal = telemetry.iBattTotal, eKWh = telemetry.eKWh)

            Text("Gruppi Batterie (Dettaglio Bassa/Alta)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                telemetry.groups.forEach { group ->
                    GroupCard(group, modifier = Modifier.weight(1f))
                }
            }

            Text("Registro Eventi ($currentHost:$currentPort)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Gray)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black)
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(logs) { log ->
                        Text(
                            text = log,
                            color = when {
                                log.contains("PARSE OK") || log.contains("CONNESSO") -> Color.Green
                                log.contains("EVENTO") -> Color.Yellow
                                log.contains("WARN") || log.contains("Err") || log.contains("Timeout") -> Color.Red
                                else -> Color.White
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StateHeaderCard(t: TelemetryData) {
    val raw = t.state.trim().uppercase()

    val pBattScarica = if (t.iBattTotal > 0.2f) t.v24Effective * t.iBattTotal else 0f
    val pTotaleFornita = t.pPv + pBattScarica
    val quotaSolare = if (pTotaleFornita > 10f) (t.pPv / pTotaleFornita) else 0f

    val isMixedState = raw.contains("FV") && raw.contains("CARICO") && (raw.contains("BAT") || raw.contains("BATT"))
    val isInsufficientSolar = t.pPv < 20f || quotaSolare < 0.15f

    val formattedState = when {
        isMixedState && isInsufficientSolar -> "SOLO BATTERIE"
        isMixedState -> "SOLARE + BATTERIE"
        raw.contains("SOLO_FV") -> "SOLO SOLARE"
        raw.contains("CARICA_BATTERIE") || raw.contains("CARICA_BATERIE") -> "CARICA BATTERIE"
        raw.contains("CARICO") && (raw.contains("BAT") || raw.contains("BATT")) -> "SOLO BATTERIE"
        raw.contains("RETE") -> "RETE ELETTRICA"
        raw.contains("STANDBY") -> "STANDBY"
        else -> t.state
    }

    val stateColor = when {
        isMixedState && isInsufficientSolar -> Color(0xFF7B1FA2)
        isMixedState -> Color(0xFF0288D1)
        raw.contains("SOLO_FV") -> Color(0xFF4CAF50)
        raw.contains("CARICA_BATTERIE") || raw.contains("CARICA_BATERIE") -> Color(0xFFFF9800)
        raw.contains("CARICO") && (raw.contains("BAT") || raw.contains("BATT")) -> Color(0xFF7B1FA2)
        else -> Color(0xFF424242)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = stateColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("STATO OPERATIVO", fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
                if (t.wifiRssi != 0) {
                    Text(
                        text = "Wi-Fi: ${t.wifiRssi} dBm",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
            Text(formattedState, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Relè Spring: ${t.releSpring}", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Uptime: ${t.uptimeStr}", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun BatteryTotalCard(iBattTotal: Float, eKWh: Float) {
    val absCurrent = abs(iBattTotal)
    val (statusLabel, statusColor) = when {
        iBattTotal > 0.2f -> Pair("⬇️ Scarica", Color(0xFFFF5722))
        iBattTotal < -0.2f -> Pair("⬆️ Carica", Color(0xFF4CAF50))
        else -> Pair("⏸ Riposo", Color.Gray)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Batterie Totale", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "%.2f A".format(absCurrent),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusLabel,
                        fontSize = 13.sp,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Accumulo", fontSize = 12.sp, color = Color.Gray)
                    Text(
                        text = "%.4f kWh".format(eKWh),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    initialHost: String,
    initialPort: Int,
    initialV24: Float,
    initialG1: Float,
    initialG2: Float,
    initialG3: Float,
    onDismiss: () -> Unit,
    onSave: (String, Int, Float, Float, Float, Float) -> Unit
) {
    var hostText by remember { mutableStateOf(initialHost) }
    var portText by remember { mutableStateOf(initialPort.toString()) }
    var v24Text by remember { mutableStateOf(initialV24.toString()) }
    var g1Text by remember { mutableStateOf(initialG1.toString()) }
    var g2Text by remember { mutableStateOf(initialG2.toString()) }
    var g3Text by remember { mutableStateOf(initialG3.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurazione & Taratura") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = hostText,
                    onValueChange = { hostText = it },
                    label = { Text("Indirizzo IP / Host") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("Porta TCP") },
                    singleLine = true
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Coefficienti Moltiplicativi Taratura:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedTextField(
                    value = v24Text,
                    onValueChange = { v24Text = it },
                    label = { Text("Coeff. Bus V24") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = g1Text,
                    onValueChange = { g1Text = it },
                    label = { Text("Coeff. G1 VLow") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = g2Text,
                    onValueChange = { g2Text = it },
                    label = { Text("Coeff. G2 VLow") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = g3Text,
                    onValueChange = { g3Text = it },
                    label = { Text("Coeff. G3 VLow") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull() ?: 8888
                    val cV24 = v24Text.toFloatOrNull() ?: 1.0f
                    val cG1 = g1Text.toFloatOrNull() ?: 1.0f
                    val cG2 = g2Text.toFloatOrNull() ?: 1.0f
                    val cG3 = g3Text.toFloatOrNull() ?: 1.0f
                    onSave(hostText.trim(), port, cV24, cG1, cG2, cG3)
                }
            ) {
                Text("Salva e Connetti")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        }
    )
}

@Composable
fun MetricCard(title: String, value: String, modifier: Modifier = Modifier, accentColor: Color, subValue: String? = null) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accentColor, shape = RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(title, fontSize = 12.sp, color = Color.Gray)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (subValue != null) {
                Text(subValue, fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun GroupCard(g: GroupData, modifier: Modifier = Modifier) {
    val isFault = g.prot != "OK"
    val cardBg = if (isFault) Color(0xFF3E2723) else MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("G${g.id}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = g.mos,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (g.mos == "ON") Color.Green else Color.Red
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Inf:  %.2fV".format(g.vLow), fontSize = 12.sp)
            Text("Sup:  %.2fV".format(g.vHighReal), fontSize = 12.sp)
            Text("Tot:  %.2fV".format(g.vTotalGroup), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF64B5F6))

            if (isFault) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("PROT: ${g.prot}", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("TRIG: ${g.trig}", color = Color.Red, fontSize = 10.sp)
            }
        }
    }
}