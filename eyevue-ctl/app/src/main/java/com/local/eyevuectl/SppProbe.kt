package com.local.eyevuectl

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * SONDE SPP (R4) : capter les trames que le controleur des lunettes rend sur RFCOMM
 * pour piloter la LENTILLE. Ce sont des opcodes que nous n'avons JAMAIS observes :
 * 0x97/0x98/0x99 (PHOTO_START / PHOTO_TRANS / PHOTO_END, protocol/PROTOCOL.md:45) sont
 * absents de toutes nos captures BLE, et le format de 0x98 n'a jamais ete releve
 * (AlbumActivity.kt:38). Les decider sans les voir est du devinette ; la mesure est la.
 *
 * Pourquoi RFCOMM rend la capture possible sans root :
 *   - la capture HCI systeme (btsnoop, /sys/kernel/debug) demande root. Mesure du 11-15 :
 *     « adb shell su -c id » rend « su: inaccessible ou ne trouve » sur ce telephone.
 *     La voie systeme est donc FERMEE, il faut passer par une liaison applicative.
 *   - RFCOMM est un transport MULTICLIENT : la pile accepte plusieurs sockets sur le
 *     MEME canal vers le MEME appareil. Notre app ouvre son propre socket et ECOUTE,
 *     sans rien couper a notre liaison BLE (service aa12 / ecriture aa13 / notification
 *     aa14) ni a celle de l'app du constructeur.
 *
 * ROLE DU SOCKET, etabli par mesure dans le bytecode, pas devine :
 *   SppCore$ConnectThread.smali:50  -> createRfcommSocketToServiceRecord
 *   ConnectionSppThread.smali:269   -> createRfcommSocketToServiceRecord
 *   aucun listenUsingRfcomm* nulle part dans l'APK
 *   -> le TELEPHONE est CLIENT, les LUNETTES tiennent le serveur RFCOMM.
 * C'est pourquoi la sonde est cliente et pourquoi elle peut etre PASSIVE : un client
 * n'emet rien. La sonde n'emet donc RIEN, volontairement.
 *
 * Ce que le journal prouve, et ce qu'il ne prouve pas :
 *   - des trames qui arrivent sans que nous emettons rien  = le controleur rend ces
 *     trames de lui-meme (etat, ecran, vignettes) : elles sont exploitables telles quelles.
 *   - AUCUNE trame alors que le canal est ouvert          = le flux est declenche par
 *     l'emetteur (l'app du constructeur, ou une commande d'ecran). Second tour alors :
 *     emettre les commandes identifiees, pas deviner les payloads.
 *   - 0 trame et beaucoup d'octets                        = le tramage n'est pas celui
 *     de PROTOCOL.md:25-26. Le compteur d'octets rend ce cas separable du precedent ;
 *     sans lui, « 0 trame » se lirait « rien ne passe », ce qui est faux.
 */
@SuppressLint("MissingPermission")
class SppProbe(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val onLine: (String) -> Unit,
    private val onDone: () -> Unit = {},
) {
    val SDP_UUID: UUID = SppCtl.SDP_UUID   // 00001101-0000-1000-8000-00005f9b34fb (SerialPort)

    private var socket: BluetoothSocket? = null
    private val alive = AtomicBoolean(false)
    private var worker: Thread? = null

    /** Octets bruts comptes : rend « rien ne passe » separable de « ca passe, pas trame ». */
    var rawBytes = 0; private set
    var frames = 0; private set
    var undecoded = 0; private set

    val isOpen get() = socket != null && socket!!.isConnected

    fun start() {
        if (alive.getAndSet(true)) return
        worker = thread(isDaemon = true, name = "spp-probe") { run() }
    }

    private fun run() {
        val inp = try {
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                onLine("SPP : appareil non appaire (bondState=${device.bondState}). createBond() " +
                    "lance (le bytecode du constructeur fait de meme, SppCore.smali) : relancer " +
                    "la sonde apres l'appairage.")
                device.createBond(); alive.set(false); onDone(); return
            }
            // Role CLIENT, mesure (voir l'en-tete). Pas de listenUsingRfcomm : le serveur
            // est cote lunettes. Le canal SPP du constructeur est ferme par cancel() sur
            // la meme UUID, donc il n'est pas tenu en permanence.
            val s = device.createRfcommSocketToServiceRecord(SDP_UUID)
            adapter.cancelDiscovery()      // la decouvertegene la connexion Classic
            s.connect()
            socket = s
            s.inputStream
        } catch (e: IOException) {
            // Le journal nomme les causes separables : c'est la seule facon de choisir la
            // prochaine action sans deviner.
            onLine("SPP : ouverture refusee " + e.javaClass.simpleName + ": " + e.message +
                ". Causes separables, par mesure : (a) pas de serveur RFCOMM 00001101 sur les " +
                "lunettes a cet instant (PROTOCOL.md:13 reserve SPP au gros volume : photos, " +
                "audio, video, OTA ; le constructeur le ferme apres un transfert) ; (b) canal " +
                "unique deja pris par com.eyevue.glassapp ; (c) liaison Classic non etablie " +
                "(dumpsys rend C2:01:06:00:3A:A1 [ DUAL ], donc le transport BR/EDR existe) ; " +
                "(d) appairage. Test qui separe (b) : fermer com.eyevue.glassapp puis relancer. " +
                "Test qui separe (a) : lancer une video ou un enregistrement micro par l'app du " +
                "constructeur et relancer la sonde pendant le transfert.")
            close(); onDone(); return
        }

        onLine("SPP : canal RFCOMM 00001101 OUVERT sur ${device.address}, sonde PASSIVE " +
            "(aucune emission de notre part). Lecture en cours...")

        val buf = ArrayList<Byte>(2048)
        var head = StringBuilder()
        try {
            while (alive.get()) {
                val b = inp.read()
                if (b == -1) {
                    onLine("SPP : flux coupe par le controleur (read() -> -1). Total : $rawBytes " +
                        "octets, $frames trame(s), $undecoded octets indecodables.")
                    break
                }
                rawBytes++
                buf.add(b.toByte())
                if (head.length < 200) head.append("%02X ".format(b))

                val n = buf.size
                // Borne de fin de trame SPP : 58 52 = EOF 'XR' (PROTOCOL.md:26).
                if (n >= 2 && buf[n - 2] == 0x58.toByte() && buf[n - 1] == 0x52.toByte()) {
                    report(ByteArray(n) { buf[it] })
                    buf.clear(); head = StringBuilder()
                } else if (n >= 600) {
                    // 600 octets sans borne : ce flux n'est pas du tramage SPP. Le rendre
                    // tel quel, avec les octets de tete, est ce qui permet de corriger le
                    // framing sur la donnee reelle (la meme discipline que pour le listing
                    // de l'AP, ou le corps brut est journalise).
                    undecoded += n
                    onLine("SPP : $n octets SANS borne 58 52 -> tramage different de " +
                        "PROTOCOL.md:25-26. Tete : " + head)
                    buf.clear(); head = StringBuilder()
                }
                if (rawBytes % 8192 == 0)
                    onLine("SPP : $rawBytes octets, $frames trame(s), $undecoded indecodables")
            }
        } catch (e: IOException) {
            onLine("SPP : lecture interrompue " + e.javaClass.simpleName + ": " + e.message +
                " ($rawBytes octets, $frames trame(s))")
        } finally {
            if (buf.isNotEmpty()) {
                undecoded += buf.size
                onLine("SPP : ${buf.size} octets finals sans borne. Tete : $head")
            }
            close()
            // Le fil rend sa fin : SANS ceci l'appelant garde une sonde morte dans son
            // champ et le bouton ne peut plus relancer d'ecoute. Defaut MESURE du
            // 11-15 14:01 : apres l'echec d'ouverture de 13:54, le tap de 14:01 a rendu
            // « SPP : sonde arretee » au lieu d'ouvrir : la sonde n'a RIEN ecoute, et la
            // mesure visée (ecouter pendant un flux video) n'a pas eu lieu.
            onDone()
        }
    }

    private fun report(raw: ByteArray) {
        val rx = Frame.parse(raw)
        if (rx == null) {
            undecoded += raw.size
            onLine("SPP : bloc de ${raw.size} o termine par 58 52 mais INDECODEABLE (SOF=" +
                "%02X %02X".format(raw[0].toInt() and 0xFF, raw[1].toInt() and 0xFF) + ") : " +
                raw.take(20).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
            return
        }
        frames++
        val n = rx.payload.size
        val extra = if (n > 24) "  payload=${n} o (tronque : " +
            rx.payloadHex.take(96) + "...)" else "  payload=[${rx.payloadHex}]"
        onLine("> SPP cmd=0x%02X %s crc=%s%s".format(
            rx.cmd, rx.name, if (rx.crcOk) "ok" else "${rx.crcRx}!${rx.crcCalc}", extra))
    }

    fun close() {
        alive.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        worker = null
    }
}
