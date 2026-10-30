package com.aless.fvmonitor

data class GroupData(
    val id: Int,
    val vLow: Float = 0f,
    val bus24: Float = 0f,
    val prot: String = "OK",
    val trig: String = "-",
    val mos: String = "OFF"
) {
    val vHighReal: Float get() = (bus24 - vLow).coerceAtLeast(0f)
}

data class TelemetryData(
    val uptimeStr: String = "0d 0h 0m 0s",
    val wifiRssi: Int = 0,
    val timestampMs: Long = 0,
    val state: String = "SCONOSCIUTO",
    val lastEvent: String = "OK",
    val v24Raw: Float = 0f,
    val iBattTotal: Float = 0f,
    val iPv: Float = 0f,
    val pPv: Float = 0f,
    val pAc: Float = 0f,
    val pAcMem: Float = 0f,
    val eKWh: Float = 0f,
    val socPercent: Int = 50,
    val socWarning: Boolean = false,
    val lastCycleAh: Float? = null,
    val capacityCycleInProgress: Boolean = false,
    val simulationMode: Boolean = false,
    val releSpring: String = "OFF",
    val groups: List<GroupData> = listOf(GroupData(1), GroupData(2), GroupData(3))
) {
    val v24Effective: Float
        get() {
            if (v24Raw > 5f) return v24Raw
            return 0f
        }

    // Tensione a vuoto stimata (OCV) corretta dinamicamente per il banco LiFePO4
    val estimatedOcv: Float
        get() {
            if (v24Effective <= 0f) return 0f

            // Se la tensione sul bus si avvicina o tocca la soglia di fine carica (29.2V),
            // l'OCV si fissa sul valore massimo di targa delle celle.
            if (v24Effective >= 29.0f) return 28.8f

            return if (iBattTotal < -0.2f) {
                // UI: corrente negativa = carica; corregge la sovratensione
                val sovratensione = kotlin.math.abs(iBattTotal) * 0.035f
                val ocvCalc = v24Effective - sovratensione
                if (ocvCalc >= 28.6f) 28.6f else ocvCalc
            } else if (iBattTotal > 0.2f) {
                // UI: corrente positiva = scarica; compensa la caduta resistiva
                v24Effective + (iBattTotal * 0.045f)
            } else {
                // A RIPOSO: la tensione di bus coincide con l'OCV
                v24Effective
            }
        }

}
