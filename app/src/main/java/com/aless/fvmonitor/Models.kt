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

            return if (iBattTotal > 0.2f) {
                // SOTTO CARICA: sottraiamo una sovratensione proporzionale alla corrente effettiva (Ampere)
                val sovratensione = iBattTotal * 0.035f
                val ocvCalc = v24Effective - sovratensione
                if (ocvCalc >= 28.6f) 28.6f else ocvCalc
            } else if (iBattTotal < -0.2f) {
                // SOTTO SCARICA: aggiungiamo la caduta resistiva proporzionale alla corrente
                v24Effective + (kotlin.math.abs(iBattTotal) * 0.045f)
            } else {
                // A RIPOSO: la tensione di bus coincide con l'OCV
                v24Effective
            }
        }

    // Stato di Carica (SoC) percentuale basato sul comportamento delle celle LiFePO4 8S
    val socPercent: Int
        get() {
            // Se la tensione ha raggiunto o superato i 29.0V (fine carica), è al 100% fisso
            if (v24Effective >= 29.0f) return 100

            val ocv = estimatedOcv
            if (ocv <= 0f) return 0

            return when {
                ocv >= 28.4f -> 100
                ocv >= 27.6f -> 95
                ocv >= 27.0f -> 90
                ocv >= 26.6f -> 80
                ocv >= 26.3f -> 70
                ocv >= 26.0f -> 50
                ocv >= 25.5f -> 30
                ocv >= 24.8f -> 15
                ocv >= 23.5f -> 5
                else -> 0
            }
        }
}