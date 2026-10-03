package com.aless.fvmonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

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
                            telemetry.wifiRssi > -70 -> Color(0xFF4CAF50)
                            telemetry.wifiRssi > -85 -> Color(0xFFFFC107)
                            else -> Color(0xFFF44336)
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
                        color = if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336),
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
            LastEventBanner(
                lastEvent = telemetry.lastEvent,
                isConnected = isConnected,
                onClearClick = { viewModel.sendCommand("CLEAR_EVT") }
            )

            StateHeaderCard(telemetry)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Bus 24V", "%.2f V".format(telemetry.v24Effective), Modifier.weight(1f), Color(0xFF2196F3))
                MetricCard("Solare FV", "%.1f W".format(telemetry.pPv), Modifier.weight(1f), Color(0xFFFFC107), "%.1f A".format(telemetry.iPv))
                MetricCard("Carico AC", "%.1f W".format(telemetry.pAc), Modifier.weight(1f), Color(0xFFFF5722), "Mem: %.0fW".format(telemetry.pAcMem))
            }

            BatteryTotalCard(
                iBattTotal = telemetry.iBattTotal,
                eKWh = telemetry.eKWh,
                socPercent = telemetry.socPercent,
                ocvVoltage = telemetry.estimatedOcv
            )

            Text("Controllo Carico e Comandi", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            CommandControlCard(
                releSpringState = telemetry.releSpring,
                isConnected = isConnected,
                onSendCommand = { cmd -> viewModel.sendCommand(cmd) }
            )

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
                    itemsIndexed(logs, key = { index, _ -> index }) { _, log ->
                        Text(
                            text = log,
                            color = getLogColor(log),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

private fun getLogColor(log: String): Color {
    return when {
        log.contains("PARSE OK") || log.contains("CONNESSO") -> Color(0xFF4CAF50)
        log.contains("EVENTO") -> Color(0xFFFFEB3B)
        log.contains("TX CMD") -> Color(0xFF64B5F6)
        log.contains("WARN") || log.contains("Err") || log.contains("Timeout") -> Color(0xFFF44336)
        else -> Color.White
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
    var showLegenda by remember { mutableStateOf(false) }

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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                TextButton(
                    onClick = { showLegenda = !showLegenda },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (showLegenda) "📖 Nascondi Legenda Comandi" else "📖 Legenda Comandi Custom (Avanzati)")
                }

                if (showLegenda) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Sintassi Comandi Custom:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("• REBOOT / RESET_HARDWARE : Riavvio completo del firmware Arduino via Watchdog", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFD32F2F))
                            Text("• RESET / RESET_PROT : Sblocco e riarmo automatico dei LATCH di protezione batterie", fontSize = 11.sp)
                            Text("• CLEAR_EVT / CLEAR_EVENT : Azzeramento messaggio ultimo evento/errore", fontSize = 11.sp)
                            Text("• LOAD:ON / LOAD:OFF / LOAD:AUTO : Controllo relè carico Spring", fontSize = 11.sp)
                            Text("• CAL:V24=x.xxxx : Taratura al volo del fattore di scala Bus V24", fontSize = 11.sp)
                            Text("• CAL:G1=x.xxxx : Taratura fattore VLow Gruppo 1", fontSize = 11.sp)
                            Text("• CAL:G2=x.xxxx : Taratura fattore VLow Gruppo 2", fontSize = 11.sp)
                            Text("• CAL:G3=x.xxxx : Taratura fattore VLow Gruppo 3", fontSize = 11.sp)
                            Text("• RSSI=xx : Aggiornamento manuale del livello segnale Wi-Fi (dBm)", fontSize = 11.sp)
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text("Coefficienti Moltiplicativi Taratura:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                OutlinedTextField(
                    value = v24Text,
                    onValueChange = { v24Text = it },
                    label = { Text("Coeff. Bus V24") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = g1Text,
                    onValueChange = { g1Text = it },
                    label = { Text("Coeff. G1 VLow") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = g2Text,
                    onValueChange = { g2Text = it },
                    label = { Text("Coeff. G2 VLow") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = g3Text,
                    onValueChange = { g3Text = it },
                    label = { Text("Coeff. G3 VLow") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
fun LastEventBanner(
    lastEvent: String,
    isConnected: Boolean,
    onClearClick: () -> Unit
) {
    val isFault = remember(lastEvent) {
        lastEvent.contains("ALLARME") || lastEvent.contains("PROTEZIONE") || lastEvent.contains("STACCO")
    }
    val bannerColor = if (isFault) Color(0xFFB71C1C) else Color(0xFF2E7D32)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bannerColor)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isFault) "⚠️ ANOMALIA REGISTRATA:" else "ℹ️ ULTIMO EVENTO:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.9f)
                )
                Text(
                    text = lastEvent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.White
                )
            }

            if (isFault || lastEvent != "OK") {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onClearClick,
                    enabled = isConnected,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.4f)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("CANCELLA", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CommandControlCard(
    releSpringState: String,
    isConnected: Boolean,
    onSendCommand: (String) -> Unit
) {
    var customCmdText by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Relè Spring / Carico:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    text = releSpringState,
                    fontWeight = FontWeight.Bold,
                    color = if (releSpringState.contains("ON")) Color(0xFF4CAF50) else Color(0xFFF44336),
                    fontSize = 14.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { onSendCommand("LOAD:ON") },
                    enabled = isConnected,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
                ) {
                    Text("FORCE ON", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { onSendCommand("LOAD:OFF") },
                    enabled = isConnected,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("FORCE OFF", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { onSendCommand("LOAD:AUTO") },
                    enabled = isConnected,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Text("AUTO", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customCmdText,
                    onValueChange = { customCmdText = it },
                    label = { Text("Comando Custom (es. REBOOT)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        if (customCmdText.isNotBlank()) {
                            onSendCommand(customCmdText.trim())
                            customCmdText = ""
                        }
                    },
                    enabled = isConnected && customCmdText.isNotBlank()
                ) {
                    Text("Invia")
                }
            }
        }
    }
}

@Composable
fun StateHeaderCard(t: TelemetryData) {
    val (formattedState, stateColor) = remember(t.state, t.pPv, t.iBattTotal, t.v24Effective) {
        val raw = t.state.trim().uppercase()
        val pBattScarica = if (t.iBattTotal > 0.2f) t.v24Effective * t.iBattTotal else 0f
        val pTotaleFornita = t.pPv + pBattScarica
        val quotaSolare = if (pTotaleFornita > 10f) (t.pPv / pTotaleFornita) else 0f

        val isMixedState = raw.contains("FV") && raw.contains("CARICO") && (raw.contains("BAT") || raw.contains("BATT"))
        val isInsufficientSolar = t.pPv < 20f || quotaSolare < 0.15f

        val label = when {
            isMixedState && isInsufficientSolar -> "SOLO BATTERIE"
            isMixedState -> "SOLARE + BATTERIE"
            raw.contains("SOLO_FV") -> "SOLO SOLARE"
            raw.contains("CARICA_BATTERIE") || raw.contains("CARICA_BATERIE") -> "CARICA BATTERIE"
            raw.contains("CARICO") && (raw.contains("BAT") || raw.contains("BATT")) -> "SOLO BATTERIE"
            raw.contains("RETE") -> "RETE ELETTRICA"
            raw.contains("STANDBY") -> "STANDBY"
            else -> t.state
        }

        val color = when {
            isMixedState && isInsufficientSolar -> Color(0xFF7B1FA2)
            isMixedState -> Color(0xFF0288D1)
            raw.contains("SOLO_FV") -> Color(0xFF4CAF50)
            raw.contains("CARICA_BATTERIE") || raw.contains("CARICA_BATERIE") -> Color(0xFFFF9800)
            raw.contains("CARICO") && (raw.contains("BAT") || raw.contains("BATT")) -> Color(0xFF7B1FA2)
            else -> Color(0xFF424242)
        }

        Pair(label, color)
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
fun BatteryTotalCard(iBattTotal: Float, eKWh: Float, socPercent: Int, ocvVoltage: Float) {
    val absCurrent = abs(iBattTotal)
    val (statusLabel, statusColor) = remember(iBattTotal) {
        when {
            iBattTotal > 0.2f -> Pair("⬇️ Scarica", Color(0xFFFF5722))
            iBattTotal < -0.2f -> Pair("⬆️ Carica", Color(0xFF4CAF50))
            else -> Pair("⏸ Riposo", Color.Gray)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Batterie Totale (LiFePO4)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = "$socPercent%",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        socPercent > 20 -> Color(0xFF4CAF50)
                        socPercent > 10 -> Color(0xFFFFC107)
                        else -> Color(0xFFF44336)
                    }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { socPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .padding(vertical = 4.dp),
                color = Color(0xFF4CAF50),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "%.2f A".format(absCurrent),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = statusLabel,
                        fontSize = 12.sp,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("OCV Stimata: %.2fV".format(ocvVoltage), fontSize = 11.sp, color = Color.Gray)
                    Text(
                        text = "%.4f kWh".format(eKWh),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
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
                    color = if (g.mos == "ON") Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("Inf:  %.2fV".format(g.vLow), fontSize = 12.sp)
            Text("Sup:  %.2fV".format(g.vHighReal), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF64B5F6))

            if (isFault) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("PROT: ${g.prot}", color = Color(0xFFF44336), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("TRIG: ${g.trig}", color = Color(0xFFF44336), fontSize = 10.sp)
            }
        }
    }
}