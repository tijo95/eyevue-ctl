package com.local.eyevuectl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
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

    /** Appareil de la liaison en cours, garde pour la query systeme (voir isLinked). */
    private var peer: BluetoothDevice? = null

    fun setListener(l: Listener?) { listener = l }

    private fun emit(s: State, d: String = "") {
        lastState = s
        handler.post { listener?.onState(s, d) }
    }

    @Volatile var lastState: State = State.IDLE; private set

    /**
     * La liaison est-elle ETABLIE LA, maintenant, et par quoi on le sait. Etat
     * faisant autorite, et non une deduction par evenements. Le second element du
     * pair est la source de la reponse : « query systeme » quand la query a
     * repondu, « repli » quand elle a leve (absence du service, permission
     * refusee) — la valeur rendue par un repli doit se lire a l'ecran, pas se
     * deviner.
     *
     * Pourquoi ce modele est indispensable : les evenements onConnectionStateChange
     * ne partent qu'au CHANGEMENT d'etat. Un listener ajoute apres la connexion
     * (le notre, dans Translate, cree au premier clic) ne recoit donc JAMAIS
     * CONNECTED, et un drapeau initialise a partir de ces evenements reste false
     * pour toujours. Defaut mesure 2026-11-13 : le bouton « Traduire le texte »
     * sortait sur « lunettes deconnectees : rien a declencher » alors que dumpsys
     * bluetooth_manager affichait ConnectionState: STATE_CONNECTED sur E09-3AA1.
     *
     * BluetoothManager.getConnectionState(device, BluetoothProfile.GATT) lit l'etat
     * que le systeme tient pour NOTRE liaison GATT (dumpsys en rend trace : la ligne
     * « 7: C2:01:06:00:3A:A1 appId=7 », profil 7 = GATT).
     */
    fun linkState(): Pair<Boolean, String> {
        val d = peer
        if (d == null)
            return (lastState == State.CONNECTED) to "repli: pas d'appareil connu pour cette instance"
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (mgr == null)
            return (lastState == State.CONNECTED) to "repli: service BLUETOOTH_SERVICE absent"
        val linked = try {
            mgr.getConnectionState(d, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
        } catch (t: Throwable) {
            return (lastState == State.CONNECTED) to "repli: query levee (${t.javaClass.simpleName})"
        }
        return linked to "query systeme (profil GATT)"
    }

    fun isLinked(): Boolean = linkState().first

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
        peer = dev
        emit(State.CONNECTING, dev.address)
        gatt = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.let { it.close(); }
        gatt = null
        peer = null
        writing = false
        queue.clear()
        lastState = State.DISCONNECTED
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

    /**
     * Donne le NOM de la constante Android correspondant au statut rendu par
     * writeCharacteristic. Le journal ne disait que « GATT=201 », ce qui ne permet
     * pas de separer un conflit de liaison d'une file d'appels saturee. Les constantes
     * de BluetoothGatt sont publiques et statiques : on les lit par reflection et on
     * cherche celle qui vaut la valeur observee. Mesure, pas supposition.
     */
    private fun nameOfGatt(v: Int): String {
        return try {
            val hits = BluetoothGatt::class.java.fields
                .filter { it.type == Int::class.java && it.get(null) == v }
                .joinToString("|") { it.name }
            // BluetoothGatt ne couvre que les statuts ATT/GATT (0x00-0xFF, 0x0101) : la
            // mesure du 11-14 23:22 rend « hits vide » pour 201, donc la valeur vient de
            // l'autre namespace, BluetoothStatusCodes (statuts rendus par la pile
            // Bluetooth du telephone, dont writeCharacteristic se sert aussi). Reflexion
            // par nom de classe : BluetoothStatusCodes est @hide, elle peut etre refusee,
            // d'ou le try.
            val stack = try {
                Class.forName("android.bluetooth.BluetoothStatusCodes").fields
                    .filter { it.type == Int::class.java && it.get(null) == v }
                    .joinToString("|") { it.name }
            } catch (e: Exception) { "BluetoothStatusCodes inaccessible (${e.javaClass.simpleName})" }
            if (hits.isNotEmpty()) return hits
            if (stack.isNotEmpty()) return "stack: $stack"
            "constante inconnue dans BluetoothGatt et BluetoothStatusCodes"
        } catch (e: Exception) { "reflection indisponible: ${e.javaClass.simpleName}" }
    }

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
            emit(State.ERROR, "writeCharacteristic GATT=$ok (" + nameOfGatt(ok) + "), trame jetee")
        }
    }
}
