package com.local.eyevuectl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Client BLE de pilotage des lunettes.
 *
 * Logique reprise de BleCore (bytecode) : une seule ecriture a la fois,
 * file d'attente alimentee par onCharacteristicWrite, retry 100 ms quand
 * writeCharacteristic renvoie false, abandon apres N tentatives.
 *
 * UUIDs releves dans BlueConstant.smali (UUID_SERVICE / UUID_CMD_WRITE / UUID_CMD_NOTIFY) :
 *   service AA12 / write AA13 / notify AA14 / CCCD 2902.
 *   NB : fe010000-... existe aussi dans le bytecode mais N'EST PAS le service
 *   de commande (c'etait mon hypothese, elle faisait echouer la decouverte de
 *   service -> "service ... absent" apres CONNECTED, et tous les boutons
 *   partaient dans le vide). Corrige en serviceUuid = aa12.
 */
@SuppressLint("MissingPermission")
class BleCtl(
    private val ctx: Context,
    private val serviceUuid: UUID = UUID.fromString("0000aa12-0000-1000-8000-00805F9B34FB"),  // UUID_SERVICE de BlueConstant.smali (lu)
    private val writeUuid: UUID = UUID.fromString("0000aa13-0000-1000-8000-00805f9b34fb"),
    private val notifyUuid: UUID = UUID.fromString("0000aa14-0000-1000-8000-00805f9b34fb"),
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
) {
    interface Listener {
        fun onState(state: State, detail: String = "")
        fun onFrame(rx: Frame.Rx) {}
    }

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    private var gatt: BluetoothGatt? = null
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private var writing = false
    private var retries = 0
    private val handler = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    var mtu: Int = 23; private set

    fun setListener(l: Listener?) { listener = l }

    private fun emit(s: State, d: String = "") = handler.post { listener?.onState(s, d) }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emit(State.CONNECTED, "status=$status")
                g.requestMtu(517)                      // BleCore negocie MTU 517
            } else {
                writing = false
                queue.clear()
                emit(State.DISCONNECTED, "status=$status")
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = newMtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(serviceUuid)
            if (svc == null) { emit(State.ERROR, "service $serviceUuid absent"); return }
            val n = svc.getCharacteristic(notifyUuid)
            if (n != null) {
                g.setCharacteristicNotification(n, true)
                n.getDescriptor(cccdUuid)?.let { d ->
                    // API 33 : writeDescriptor(gatt, descriptor, value)
                    if (android.os.Build.VERSION.SDK_INT >= 33)
                        g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    else {
                        @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION") g.writeDescriptor(d)
                    }
                }
            }
            drain()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val rx = Frame.parse(c.value)
            if (rx != null) handler.post { listener?.onFrame(rx) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writing = false
            retries = 0
            drain()
        }
    }

    fun connect(dev: BluetoothDevice) {
        disconnect()
        emit(State.CONNECTING, dev.address)
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.let { it.close(); }
        gatt = null
        writing = false
        queue.clear()
    }

    /** Envoie une trame ; mise en file si une ecriture est en cours. */
    fun send(cmd: Int, param: Int? = null) {
        queue.add(Frame.build(cmd, param))
        drain()
    }

    /** Envoie une trame dont le payload est deja construit (TIME, OPEN_WIFI...). */
    fun send(cmd: Int, payload: ByteArray) {
        queue.add(Frame.build(cmd, payload))
        drain()
    }

    fun sendRaw(bytes: ByteArray) { queue.add(bytes); drain() }

    /** Pareille a BleCore : 1 ecriture a la fois, retry 100 ms, max 5 essais. */
    private fun drain() {
        if (writing) return
        val g = gatt ?: return
        val ch = g.getService(serviceUuid)?.getCharacteristic(writeUuid) ?: return
        val data = queue.poll() ?: return
        writing = true
        val ok: Int = if (android.os.Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION") ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION") ch.value = data
            @Suppress("DEPRECATION") val legacy = g.writeCharacteristic(ch)
            if (legacy) BluetoothGatt.GATT_SUCCESS else 1
        }
        if (ok != BluetoothGatt.GATT_SUCCESS && retries < 5) {
            retries++
            handler.postDelayed({ writing = false; queue.add(data); drain() }, 100)
        } else if (ok != BluetoothGatt.GATT_SUCCESS) {
            retries = 0
            writing = false
            emit(State.ERROR, "writeCharacteristic GATT=$ok, trame jetee")
        }
    }
}
