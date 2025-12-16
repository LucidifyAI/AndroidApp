package com.example.eeglabeler.ui
data class ParsedPacket(
    val channels: FloatArray, // 8 channels
    val batteryVoltage: Float?,
    val batteryPercent: Float?
)

object PatchPacketParser {
    private const val SYNC: Byte = 0xFF.toByte()
    private const val PACKET_SIZE = 36
    private const val CHANNEL_OFFSET = 2
    private const val CHANNEL_COUNT = 10
    private const val BATTERY_OFFSET = 33

    fun tryParse(packet: ByteArray): ParsedPacket? {
        if (packet.size != PACKET_SIZE || packet[0] != SYNC) return null

        val full = FloatArray(CHANNEL_COUNT)
        for (ch in 0 until CHANNEL_COUNT) {
            val i = CHANNEL_OFFSET + ch * 3
            if (i + 2 >= packet.size) continue

            val b0 = packet[i].toInt() and 0xFF
            val b1 = packet[i + 1].toInt() and 0xFF
            val b2 = packet[i + 2].toInt() and 0xFF
            var value = (b0 shl 16) or (b1 shl 8) or b2
            if (value and 0x800000 != 0) value = value or -0x1000000
            val shifted = value shl 8
            full[ch] = shifted * (4.8f / (6.0f * 16777216f)) * 10f
        }

        // Derive your EEG channels
        val afz = full[0]
        val fp2 = full[1]

        val channels = floatArrayOf(
            fp2 - afz,     // Fp1_d
            -fp2,          // Fp2_d
            afz,           // Afz
            full[2],       // Imp_Ch1
            full[3],       // Imp_Ch2
            full[7],       // ACCX
            full[8],       // ACCY
            full[9]        // ACCZ
        )

        // Battery
        val battByte = packet.getOrNull(BATTERY_OFFSET)?.toInt()?.and(0xFF)
        val battVoltage = battByte?.let { it / 25f }
        val battPercent = battVoltage?.let { voltageToPercent(it) }

        return ParsedPacket(channels, battVoltage, battPercent)
    }

    private fun voltageToPercent(v: Float): Float {
        return when {
            v >= 4.15f -> 100f
            v <= 3.5f -> 0f
            else -> ((v - 3.5f) / (4.15f - 3.5f)) * 100f
        }
    }
}
