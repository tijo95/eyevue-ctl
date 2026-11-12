package com.local.eyevuectl

/**
 * Framing du protocole, fidele a Datagram.convertLeHeaderToBytes()/
 * convertSppHeaderToBytes() (relevé dans le bytecode, usage interop).
 *
 *   BLE  app->lunettes : AB 55 | len_hi len_lo | cmd | payload | crc
 *   BLE  lunettes->app  : AC 55 | ...
 *   SPP                : 52 58 | len_hi len_lo | cmd | payload | crc
 *   len = len(payload) + 2 ; crc = sum(cmd + payload) & 0xFF
 */
object Frame {
    val SOF_BLE_TX = byteArrayOf(0xAB.toByte(), 0x55)
    val SOF_BLE_RX = byteArrayOf(0xAC.toByte(), 0x55)
    val SOF_SPP = byteArrayOf(0x52, 0x58)
    val EOF_SPP = byteArrayOf(0x58, 0x52)

    fun crc(cmd: Int, payload: ByteArray): Int =
        ((cmd and 0xFF) + payload.sumOf { it.toInt() and 0xFF }) and 0xFF

    fun build(cmd: Int, payload: ByteArray, sof: ByteArray): ByteArray {
        val len = payload.size + 2
        val body = ByteArray(3 + payload.size)
        body[0] = (len shr 8).toByte()
        body[1] = (len and 0xFF).toByte()
        body[2] = (cmd and 0xFF).toByte()
        payload.copyInto(body, 3)
        return sof + body + byteArrayOf((crc(cmd, payload)).toByte())
    }

    /** Trame complete. [sof] = SOF_BLE_TX (défaut) ou SOF_SPP. */
    fun build(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        build(cmd, payload, SOF_BLE_TX)

    fun build(cmd: Int, param: Int?, sof: ByteArray = SOF_BLE_TX): ByteArray {
        val payload = when {
            param == null -> ByteArray(0)
            param in 0..255 -> byteArrayOf(param.toByte())
            else -> byteArrayOf((param shr 8).toByte(), (param and 0xFF).toByte())
        }
        return build(cmd, payload, sof)
    }

    /**
     * Decodage d'une trame recue. Renvoie null si le prefixe n'est pas un SOF
     * connu ou si la trame est trop courte (le flux peut arriver decoupe).
     */
    fun parse(bytes: ByteArray): Rx? {
        if (bytes.size < 5) return null
        val sof = bytes[0].toInt() and 0xFF
        val sof2 = bytes[1].toInt() and 0xFF
        val dir = when {
            sof == 0xAC && sof2 == 0x55 -> "RX"
            sof == 0xAB && sof2 == 0x55 -> "RX"
            sof == 0x52 && sof2 == 0x58 -> "RX"
            else -> null
        } ?: return null
        val len = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val cmd = bytes[4].toInt() and 0xFF
        val nPay = (len - 2).coerceAtLeast(0)
        if (bytes.size < 5 + nPay + 1) return null        // trame incomplete
        val payload = bytes.copyOfRange(5, 5 + nPay)
        val crc = bytes[5 + nPay].toInt() and 0xFF
        // Perimètre CONFIRME par le bytecode (Datagram.getBytesCrc ->
        // convertHeaderToCrcBytes = commandId + payload) et par les trames
        // relevees dans les logs de l'app d'origine :
        //   AB 55 00 03 70 31 A1 -> crc(cmd 0x70 + payload 0x31) = 0xA1
        //   AB 55 00 03 73 00 73 -> crc(0x73 + 0x00) = 0x73
        // Les octets de len ne sont DONC PAS inclus dans le crc.
        val calc = crc(cmd, payload)
        return Rx(dir, cmd, payload, crc, calc, crc == calc)
    }

    data class Rx(
        val dir: String,
        val cmd: Int,
        val payload: ByteArray,
        val crcRx: Int,
        val crcCalc: Int,
        val crcOk: Boolean,
    ) {
        val name: String get() = Cmd.nameOf(cmd)
        val payloadHex: String get() = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        override fun toString() = "cmd=0x%02X %s payload=[%s] crc=%s".format(
            cmd, name, payloadHex, if (crcOk) "ok" else "$crcRx!=$crcCalc")
    }
}
