package com.aless.fvmonitor

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

// ============================================================================
// MODELLI DATI
// ============================================================================
data class GroupData(
    val id: Int,
    val vLow: Float = 0f,
    val vHigh: Float = 0f,
    val prot: String = "OK",
    val trig: String = "-",
    val mos: String = "OFF"
)

data class TelemetryData(
    val uptimeStr: String = "0d 0h 0m 0s",
    val wifiRssi: Int = 0,
    val timestampMs: Long = 0,
    val state: String = "SCONOSCIUTO",
    val v24: Float = 0f,
    val iBattTotal: Float = 0f,
    val iPv: Float = 0f,
    val pPv: Float = 0f,
    val pAc: Float = 0f,
    val pAcMem: Float = 0f,
    val eKWh: Float = 0f,
    val releSpring: String = "OFF",
    val groups: List<GroupData> = listOf(GroupData(1), GroupData(2), GroupData(3))
)

// ============================================================================
// VIEWMODEL CON SALVATAGGIO IP/PORTA
// ============================================================================
class MainViewModel(context: Context) : ViewModel() {

    private val prefs = context.getSharedPreferences("fv_monitor_prefs", Context.MODE_PRIVATE)

    private val _host = MutableStateFlow(prefs.getString("ip_host", "192.168.1.100") ?: "192.168.1.100")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _port = MutableStateFlow(prefs.getInt("ip_port", 8888))
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _telemetry = MutableStateFlow(TelemetryData())
    val telemetry: StateFlow<TelemetryData> = _telemetry.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var connectionJob: Job? = null

    init {
        connect()
    }

    fun updateConnectionSettings(newHost: String, newPort: Int) {
        prefs.edit().putString("ip_host", newHost).putInt("ip_port", newPort).apply()
        _host.value = newHost
        _port.value = newPort
        addLog("Nuova configurazione salvata: $newHost:$newPort")
        connect()
    }

    fun connect() {
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch(Dispatchers.IO) {
            val currentHost = _host.value
            val currentPort = _port.value

            while (isActive) {
                try {
                    addLog("Connessione a $currentHost:$currentPort...")
                    val socket = Socket()
                    socket.connect(InetSocketAddress(currentHost, currentPort), 5000)
                    _isConnected.value = true
                    addLog("CONNESSO!")

                    val inputStream: InputStream = socket.getInputStream()
                    val buffer = ByteArray(2048)
                    val stringBuilder = StringBuilder()

                    while (isActive) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break

                        val chunk = String(buffer, 0, bytesRead, Charsets.UTF_8)
                        Log.d("FV_TCP", "RAW RECV: $chunk")
                        stringBuilder.append(chunk)

                        var newlineIndex = stringBuilder.indexOf("\n")
                        while (newlineIndex != -1) {
                            val line = stringBuilder.substring(0, newlineIndex).trim()
                            stringBuilder.delete(0, newlineIndex + 1)
                            if (line.isNotEmpty()) processRawChunk(line)
                            newlineIndex = stringBuilder.indexOf("\n")
                        }

                        var telIndex = stringBuilder.indexOf("TEL;", 4)
                        while (telIndex != -1) {
                            val line = stringBuilder.substring(0, telIndex).trim()
                            stringBuilder.delete(0, telIndex)
                            if (line.isNotEmpty()) processRawChunk(line)
                            telIndex = stringBuilder.indexOf("TEL;", 4)
                        }

                        if (stringBuilder.length > 2048) {
                            stringBuilder.clear()
                        }
                    }
                } catch (e: Exception) {
                    _isConnected.value = false
                    addLog("Errore/Disconnesso: ${e.message}")
                } finally {
                    _isConnected.value = false
                }
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    private fun processRawChunk(line: String) {
        if (line.contains("TEL;")) {
            val cleanStr = line.substring(line.indexOf("TEL;"))
            parseTelemetry(cleanStr)
        } else if (line.contains("EVENTO;")) {
            addLog("EVENTO: ${line.substring(line.indexOf("EVENTO;"))}")
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

            val g1 = GroupData(
                id = 1,
                vLow = map["G1_Vb"]?.toFloatOrDefault(0f) ?: 0f,
                vHigh = map["G1_Va"]?.toFloatOrDefault(0f) ?: 0f,
                prot = map["G1_pr"] ?: map["G1_prot"] ?: "OK",
                trig = map["G1_tr"] ?: map["G1_trig"] ?: "-",
                mos = map["G1_m"] ?: map["G1_mos"] ?: "ON"
            )

            val g2 = GroupData(
                id = 2,
                vLow = map["G2_Vb"]?.toFloatOrDefault(0f) ?: 0f,
                vHigh = map["G2_Va"]?.toFloatOrDefault(0f) ?: 0f,
                prot = map["G2_pr"] ?: map["G2_prot"] ?: "OK",
                trig = map["G2_tr"] ?: map["G2_trig"] ?: "-",
                mos = map["G2_m"] ?: map["G2_mos"] ?: "ON"
            )

            val g3 = GroupData(
                id = 3,
                vLow = map["G3_Vb"]?.toFloatOrDefault(0f) ?: 0f,
                vHigh = map["G3_Va"]?.toFloatOrDefault(0f) ?: 0f,
                prot = map["G3_pr"] ?: map["G3_prot"] ?: "OK",
                trig = map["G3_tr"] ?: map["G3_trig"] ?: "-",
                mos = map["G3_m"] ?: map["G3_mos"] ?: if (map.containsKey("G3_Vb")) "ON" else "OFF"
            )

            val telemetryParsed = TelemetryData(
                uptimeStr = map["uptime"] ?: "0d 0h 0m 0s",
                wifiRssi = map["rssi"]?.toIntOrNull() ?: 0,
                timestampMs = map["ms"]?.toLongOrNull() ?: 0L,
                state = map["stato"] ?: "SCONOSCIUTO",
                v24 = map["V24"]?.toFloatOrDefault(0f) ?: 0f,
                iBattTotal = map["IbatTot"]?.toFloatOrDefault(0f) ?: 0f,
                iPv = map["IPv"]?.toFloatOrDefault(0f) ?: 0f,
                pPv = map["PFV"]?.toFloatOrDefault(0f) ?: 0f,
                pAc = map["PAC_stim"]?.toFloatOrDefault(0f) ?: 0f,
                pAcMem = map["PAC_mem"]?.toFloatOrDefault(0f) ?: 0f,
                eKWh = map["EkWh"]?.toFloatOrDefault(0f) ?: 0f,
                releSpring = map["rele_spring"] ?: "OFF",
                groups = listOf(g1, g2, g3)
            )

            _telemetry.value = telemetryParsed

        } catch (e: Exception) {
            addLog("Err Parsing: ${e.message}")
        }
    }

    private fun addLog(msg: String) {
        val timeSec = (System.currentTimeMillis() % 100000) / 1000
        _logs.value = (listOf("${timeSec}s: $msg") + _logs.value).take(50)
    }

    private fun String.toFloatOrDefault(default: Float): Float = this.toFloatOrNull() ?: default
}

class MainViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(context) as T
    }
}

// ============================================================================
// INTERFACCIA GRAFICA
// ============================================================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val viewModel: MainViewModel = viewModel(factory = MainViewModelFactory(context))

            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DashboardScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val telemetry by viewModel.telemetry.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val currentHost by viewModel.host.collectAsState()
    val currentPort by viewModel.port.collectAsState()

    var showSettingsDialog by remember { mutableStateOf(false) }

    if (showSettingsDialog) {
        SettingsDialog(
            initialHost = currentHost,
            initialPort = currentPort,
            onDismiss = { showSettingsDialog = false },
            onSave = { newHost, newPort ->
                viewModel.updateConnectionSettings(newHost, newPort)
                showSettingsDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FV Monitor", fontWeight = FontWeight.Bold) },
                actions = {
                    // Badge Wi-Fi RSSI
                    if (isConnected && telemetry.wifiRssi != 0) {
                        val rssiColor = when {
                            telemetry.wifiRssi > -70 -> Color.Green
                            telemetry.wifiRssi > -85 -> Color(0xFFFFC107) // Giallo
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
                        Icon(Icons.Default.Settings, contentDescription = "Impostazioni IP")
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
                MetricCard("Bus 24V", "%.2f V".format(telemetry.v24), Modifier.weight(1f), Color(0xFF2196F3))
                MetricCard("Solare FV", "%.1f W".format(telemetry.pPv), Modifier.weight(1f), Color(0xFFFFC107), "%.1f A".format(telemetry.iPv))
                MetricCard("Carico AC", "%.1f W".format(telemetry.pAc), Modifier.weight(1f), Color(0xFFFF5722), "Mem: %.0fW".format(telemetry.pAcMem))
            }

            BatteryTotalCard(iBattTotal = telemetry.iBattTotal, eKWh = telemetry.eKWh)

            Text("Gruppi Batterie (12V + 12V)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                                log.contains("PARSE OK") -> Color.Green
                                log.contains("EVENTO") -> Color.Yellow
                                log.contains("WARN") || log.contains("Err") -> Color.Red
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

// ============================================================================
// COMPONENTI DI FORMATTAZIONE E SCHERMATA
// ============================================================================

@Composable
fun StateHeaderCard(t: TelemetryData) {
    val raw = t.state.trim().uppercase()

    // Calcolo potenza erogata dalle batterie in scarica (P = V * I)
    val pBattScarica = if (t.iBattTotal > 0.2f) t.v24 * t.iBattTotal else 0f
    val pTotaleFornita = t.pPv + pBattScarica

    // Quota percentuale fornita dal solare
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
        isMixedState && isInsufficientSolar -> Color(0xFF7B1FA2) // Viola (Solo Batterie)
        isMixedState -> Color(0xFF0288D1) // Blu (Solare + Batterie)
        raw.contains("SOLO_FV") -> Color(0xFF4CAF50) // Verde
        raw.contains("CARICA_BATTERIE") || raw.contains("CARICA_BATERIE") -> Color(0xFFFF9800) // Arancione
        raw.contains("CARICO") && (raw.contains("BAT") || raw.contains("BATT")) -> Color(0xFF7B1FA2) // Viola
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
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit
) {
    var hostText by remember { mutableStateOf(initialHost) }
    var portText by remember { mutableStateOf(initialPort.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Configurazione Rete") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull() ?: 8888
                    onSave(hostText.trim(), port)
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
            Text("Bassa: %.2fV".format(g.vLow), fontSize = 12.sp)
            Text("Alta:  %.2fV".format(g.vHigh), fontSize = 12.sp)
            Text("Tot:   %.2fV".format(g.vLow + g.vHigh), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

            if (isFault) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("PROT: ${g.prot}", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("TRIG: ${g.trig}", color = Color.Red, fontSize = 10.sp)
            }
        }
    }
}