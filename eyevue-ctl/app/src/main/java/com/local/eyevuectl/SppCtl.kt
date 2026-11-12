package com.local.eyevuectl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Canal SPP (Bluetooth Classic) pour le gros volume : photos, video, audio,
 * fond d'ecran, OTA. Le framing utilise 52 58 ... 58 52 (SOF/EOF 'RX').
 *
 * La prise de controle (appairage + role SDP) est reprise du bytecode :
 *   - si le device n'est pas appaire : createBond()
 *   - sinon : fetchUuidsWithSdp(), puis la 1re UUID de la liste
 *   - listenUsingRfcommWithServiceRecord(uuid) cote serveur
 *
 * ⚠ Le role (serveur/client) exact par type de transfert est a confirmer par
 * la capture : voir NOTES, section SPP.
 */
@SuppressLint("MissingPermission")
object SppCtl {
    val SDP_UUID = UUID.fromString("00001101-0000-1000-8000-00005f9b34fb")  // SerialPort

    class Session(private val adapter: BluetoothAdapter, val device: BluetoothDevice) {
        private var socket: android.bluetooth.BluetoothSocket? = null
        private var server: android.bluetooth.BluetoothServerSocket? = null
        private val alive = AtomicBoolean(true)

        val inputStream get() = socket?.inputStream
        val outputStream get() = socket?.outputStream

        /** Etablit le canal. [asServer] = true -> ecoute ; false -> connexion. */
        fun open(asServer: Boolean = true, name: String = "EyevueCtl") {
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                device.createBond()          // le code original fait de meme
                throw IOException("device non appaire : createBond() lance, reessayer")
            }
            if (asServer) {
                server = adapter.listenUsingRfcommWithServiceRecord(name, SDP_UUID)
                socket = server!!.accept()
            } else {
                socket = device.createRfcommSocketToServiceRecord(SDP_UUID)
                socket!!.connect()
            }
            socket?.inputStream?.let { /* lire en boucle cote appelant */ }
        }

        fun write(bytes: ByteArray) {
            val out = outputStream ?: throw IOException("canal SPP ferme")
            out.write(bytes)
            out.flush()
        }

        /** Lit une trame complete (jusqu'a EOF 58 52), avec timeout. */
        fun readFrame(timeoutMs: Int = 5000): Frame.Rx? {
            val inp = inputStream ?: throw IOException("canal SPP ferme")
            val buf = ArrayList<Byte>(512)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val b = inp.read()
                if (b == -1) return null
                buf.add(b.toByte())
                val n = buf.size
                if (n >= 2 && buf[n - 2] == 0x58.toByte() && buf[n - 1] == 0x52.toByte()) {
                    return Frame.parse(ByteArray(n) { buf[it] })
                }
            }
            return Frame.parse(ByteArray(buf.size) { buf[it] })
        }

        fun close() {
            alive.set(false)
            try { socket?.close() } catch (_: Exception) {}
            try { server?.close() } catch (_: Exception) {}
            socket = null; server = null
        }
    }
}