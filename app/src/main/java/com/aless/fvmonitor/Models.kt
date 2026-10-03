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

    // Tensione a vuoto stimata (OCV) compensando la caduta/salita di tensione dovuta alla corrente (I * R)
    // Resistenza interna indicativa del banco 24V (8S): ~0.045 Ohm
    val estimatedOcv: Float
        get() {
            if (v24Effective <= 0f) return 0f
            val internalResistance = 0.045f // Ohm
            return v24Effective + (iBattTotal * internalResistance)
        }

    // Stato di Carica (SoC) percentuale basato sulla curva caratteristica delle celle LiFePO4 8S
    val socPercent: Int
        get() {
            val ocv = estimatedOcv
            if (ocv <= 0f) return 0

            return when {
                ocv >= 28.4f -> 100
                ocv >= 27.2f -> 95
                ocv >= 26.8f -> 90
                ocv >= 26.5f -> 80
                ocv >= 26.3f -> 60
                ocv >= 26.1f -> 40
                ocv >= 25.8f -> 20
                ocv >= 25.0f -> 10
                ocv >= 23.5f -> 5
                else -> 0
            }
        }
}