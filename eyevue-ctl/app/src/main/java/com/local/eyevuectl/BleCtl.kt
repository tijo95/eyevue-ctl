package com.local.eyevuectl

import android.annotation.SuppressLint
import android.os.SystemClock
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

    /**
     * La trame en cours d'ecriture, gardee pour la re-emission. Les commandes de la
     * chaine de traduction sont des commandes de CONTROLE : si 0x39 (OPEN_WIFI) est
     * jetee, l'AP ne s'allume pas, le scan ne voit rien et la chaine perd 100 s pour
     * rien. Mesure du 11-14 22:47 : « trame jetee » a 22:46:51, 22:46:59, 22:47:14,
     * puis « scan startScan=false, 0 resultats, SSID vus: » et les 20 cycles epuises.
     * Une commande de controle perdue n'est donc pas une perte a accepter : la trame
     * est re-emise, et l'echec est remonte comme etat (retryable) a l'appelant.
     */
    private var inFlight: ByteArray? = null

    /** Nb de trames Jetees (re-émises incluses) depuis la connexion : journalise par la chaine. */
    var droppedFrames: Int = 0; private set

    /**
     * Plafond de re-EMISSION, par trame (la cle est le contenu hex de la trame).
     * Mesure du 11-15 01:03 qui condamne la version sans plafond : rejets journalises a
     * 01:03:25.303 (« jetees = 1 ») puis 01:03:35.303 (« jetees = 2 »), 10 s d'ecart, la
     * duree d'UN cycle de 10 essais (9 500 ms). Chaque trame re-émise en fin de file
     * repartait pour 9,5 s, echouait, repartait : BOUCLE INFINIE, aucune commande utile
     * ne passait. La reponse est double : UN seul plafond de re-émission par trame, et
     * quand une trame echoue APRES sa re-émission, ce n'est plus la trame qu'il faut
     * changer, c'est la LIAISON : on vide la file (elle ne partira jamais) et on refait
     * un connectGatt, seule operation qui rende une file neuve.
     *
     * Ce que le nom du statut dit, et qui est coherent avec le plafond : 201 =
     * ERROR_GATT_WRITE_REQUEST_BUSY, la pile refuse parce qu'UNE ecriture est DEJA en
     * attente pour ce client. Marteler la meme liaison ne vide pas cette attente. La
     * source mesuree de l'attente est la rafale de statut (requestStatus emet 3 trames
     * d'affile : GET_BATTERY, GET_CAPACITY, APP_GET_DEVICE_STATUS), la 2e et la 3e
     * trouvant la file occupee ; le cycle de 10 s du journal est celui du statut periodique.
     */
    private val repushes = HashMap<String, Int>()
    private var reconnects = 0

    /**
     * Espacement PLANCHER entre deux writeCharacteristic, en ms. Mesure du 11-15 01:03 :
     * les rejets 201 = ERROR_GATT_WRITE_REQUEST_BUSY tombent sur la 2e trame d'une rafale.
     * La rafale, c'est CONNECTED -> eyevue.requestStatus() (GET_BATTERY, GET_CAPACITY,
     * APP_GET_DEVICE_STATUS, 3 trames) + getFirmware() (SEND_ISP_VERSION), appeles d'un
     * coup dans le listener de MainActivity. Notre drapeau `writing` serialise nos appels,
     * mais il ne dit RIEN de la file INTERNE du controleur : celle-ci a une ecriture en
     * attente pendant que notre callback de confirmation n'est pas revenu, et elle rend
     * alors BUSY. Rien dans le code n'espacait les emissions : le premier drain() partait
     * immediatement apres la confirmation, donc collé a la trame precedente. Ce plancher,
     * mesure, est la seule correction qui agisse sur la cause (rythme d'emission) et non
     * sur ses effets (retry, re-émission).
     *
     * 60 ms : valeur initiale, pas une donnee du protocole. Elle est rendue reglable par
     * la cle `ble_spacing_ms` et journalisee, pour que la mesure suivante dise si 60 suffit.
     */
    var minSpacingMs: Long = 60L
    private var lastWriteAt = 0L

    /**
     * Le cmd d'une trame, pour le journal. Mesure du 11-15 01:03 : la premiere version
     * lisait b[0] et rendait « cmd=0xAB », qui est en realite le SOF_BLE_TX (la trame
     * commence par AB 55). Le format (Frame.build) est SOF(2) + LEN(2) + CMD(1) + payload
     * + CRC : le cmd est donc a l'index 4 de la trame complete, c'est-a-dire b[2] du
     * corps. Sans cette correction le journal ne dit AUCUN nom de commande exploitable.
     */
    private fun cmdOf(b: ByteArray): String {
        if (b.size < 6) return "trame trop courte (" + b.size + " o)"
        val cmd = b[4].toInt() and 0xFF
        val name = Cmd.NAMES["0x" + cmd.toString(16).padStart(2, '0')] ?: "?"
        return "0x" + cmd.toString(16).uppercase().padStart(2, '0') + " " + name +
            " (SOF " + (b[0].toInt() and 0xFF).toString(16).uppercase() + " " +
            (b[1].toInt() and 0xFF).toString(16).uppercase() + ", " + b.size + " o)"
    }
    private val handler = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    var mtu: Int = 23; private set

    /** Appareil de la liaison en cours, garde pour la query systeme (voir isLinked). */
    private var peer: BluetoothDevice? = null

    /**
     * La liaison de NOTRE instance est-elle etablie. Drapeau pose par le callback, pas
     * par lastState. Mesure du 11-15 11:14:12.406 qui condamne la version par lastState :
     * l'avertissement « un autre client tient cet appareil » est sorti alors que
     * com.eyevue.glassapp etait morte (force-stop, pidof vide) et que NOUS etions le seul
     * client, connecte a 11:11:46.649 (status=0). La raison : emit() ECRRASE lastState, et
     * le emit(State.ERROR) du backoff (11:11:46.597) avait laisse lastState = ERROR. Un
     * journal qui accuse a tort un client fantome est pire que pas de journal.
     */
    private var ourLinkUp = false

    /** Les services sont-ils decouverts : sans eux la caracteristique d'ecriture n'existe
     *  pas, et le controleur a deja son ecriture de decouverte/CCCD en cours. */
    private var servicesReady = false

    /**
     * L'ecriture du descripteur CCCD est-elle ACQUITTEE (onDescriptorWrite). C'est LE
     * discriminant que la mesure impose, et la cause exacte des 201 du demarrage :
     *   11:27:20.540 etat CONNECTED (status=0)
     *   11:27:20.684 writeCharacteristic 201 BUSY cmd=0x17 GET_BATTERY   (144 ms apres)
     * Le gate servicesReady n'a pas suffi : a 144 ms la decouverte est faite, mais
     * onServicesDiscovered emet LUI-MEME writeDescriptor(CCCD, ENABLE_NOTIFICATION). La
     * pile du controleur a donc une ecriture en attente pour ce client, exactement celle
     * que nomme ERROR_GATT_WRITE_REQUEST_BUSY. Cette ecriture est LA NOTRE : notre
     * activation des notifications. Notre plancher d'espacement ne la voyait pas parce
     * qu'il ne chronometre que NOS writeCharacteristic, pas le writeDescriptor.
     *
     * Le gate est donc l'acquittement du CCCD. S'il n'arrive pas (aucun descripteur, CCCD
     * non ecrit), un delai de securite autorise les emissions : sans lui drain() ne
     * s'executerait jamais, ce qui serait un defaut pire que celui qu'il corrige.
     */
    private var cccdSettled = false
    private var cccdDeadline = 0L

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

    /**
     * Detection d'une liaison tenue par une AUTRE app, ce qui est la cause mesuree des
     * rejets GATT=201 du 11-14 22:46 : pidof rendait com.eyevue.glassapp (24579) ET
     * com.local.eyevuectl (27182) vivants, et dumpsys bluetooth_manager rend
     * « 7: C2:01:06:00:3A:A1 appId=7 » : UNE file d'ecritures pour deux clients GATT.
     *
     * Ce qui le rend detectable depuis l'app : BluetoothManager.getConnectionState(device,
     * BluetoothProfile.GATT) repond pour l'APPAREIL, pas pour notre client. Il dit
     * STATE_CONNECTED meme quand ce n'est pas nous qui tenons la liaison. D'ou le signal,
     * materiement mesurable : « le systeme dit connecte » + « notre instance n'a pas de
     * GATT connecte » = l'appareil est occupe par quelqu'un d'autre.
     *
     * Retourne null quand la liaison est la notre, et quand peer est nul (le repli de
     * linkState sur lastState ne prouverait rien sur autrui).
     */
    fun foreignLinkHint(): String? {
        val d = peer ?: return null
        val ours = gatt != null && ourLinkUp
        val (sys, how) = linkState()
        if (!sys || ours) return null
        return "le systeme declare " + d.address + " CONNECTE (source : $how) alors que NOTRE " +
            "instance n'a aucune liaison GATT etablie : un autre client tient cet appareil. " +
            "La file d'ecritures est partagee, c'est la situation mesuree des rejets " +
            "GATT=201 (10 essais en backoff, trame re-emise). Fermer l'autre app est la " +
            "seule issue constatee : a 23:40, liaison seule, zero rejet et chaine complete."
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emit(State.CONNECTED, "status=$status")
                ourLinkUp = true
                // Liaison neuve = compteurs neufs : une file qui etait bloquee ne doit pas
                // condamner les trames de la liaison suivante (plafond par contenu de trame).
                repushes.clear()
                reconnects = 0
                servicesReady = false
                cccdSettled = false
                cccdDeadline = 0L
                // Le premier writeCharacteristic ne part PAS tout de suite : a 11:11:46.597
                // le premier appel etait rejete 201 BUSY 108 ms apres CONNECTED, pendant que
                // la pile etait occupee par la DECOUVERTE de services et l'ecriture du
                // descripteur CCCD (la trame qui suit la connexion). Poser la horloge
                // d'emission ici fait respecter le plancher d'espacement des la 1re trame.
                lastWriteAt = SystemClock.elapsedRealtime()
                g.requestMtu(517)                      // BleCore negocie MTU 517
            } else {
                writing = false
                queue.clear()
                ourLinkUp = false
                servicesReady = false
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
            servicesReady = true
            // La porte n'OUVRE qu'a l'acquittement du CCCD (voir cccdSettled) : ici la pile
            // vient de recevoir notre writeDescriptor, elle est occupee.
            cccdSettled = false
            cccdDeadline = SystemClock.elapsedRealtime() + 2000L
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

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // Le controleur a fini NOTRE ecriture de souscription : la file est videe, les
            // emissions peuvent commencer. Mesure : c'est cette ecriture qui rendait le
            // 1er writeCharacteristic 201 = BUSY.
            cccdSettled = true
            lastWriteAt = SystemClock.elapsedRealtime()
            handler.post({ drain() })
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
        // GATE de decouverte, mesure du 11-15 11:11 : le 1er writeCharacteristic de
        // l'app (cmd=0x17 GET_BATTERY) est rejete 201 = ERROR_GATT_WRITE_REQUEST_BUSY 108 ms
        // apres CONNECTED, c'est-a-dire PENDANT la decouverte de services et l'ecriture du
        // descripteur CCCD que le controleur mene lui-meme. Ce n'est donc pas notre rafale
        // qui congestionne la file : c'est notre 1re trame qui arrive pendant que la pile
        // est occupee par la preparation de la liaison. Sans services, la caracteristique
        // d'ecriture n'existe d'ailleurs pas.
        if (!servicesReady) {
            handler.postDelayed({ drain() }, 200)
            return
        }
        if (!cccdSettled) {
            // Delai de securite : un appareil sans descripteur CCCD ne rendra jamais
            // onDescriptorWrite. Sans repli, aucune trame ne partirait jamais. La duree est
            // mesuree comme suffisante : l'acquittement du CCCD est revenu bien avant la
            // 1re trame de la chaine de traduction (premieres emissions a +2 s, ici a +144 ms).
            val t = SystemClock.elapsedRealtime()
            if (cccdDeadline == 0L || t < cccdDeadline) {
                handler.postDelayed({ drain() }, 100)
                return
            }
            cccdSettled = true
        }
        // Plancher d'espacement : ne pas coller deux emissions (voir minSpacingMs).
        val sinceLast = SystemClock.elapsedRealtime() - lastWriteAt
        if (sinceLast < minSpacingMs) {
            handler.postDelayed({ drain() }, minSpacingMs - sinceLast)
            return
        }
        val g = gatt ?: return
        val ch = g.getService(serviceUuid)?.getCharacteristic(writeUuid) ?: return
        val data = queue.poll() ?: return
        writing = true
        lastWriteAt = SystemClock.elapsedRealtime()
        val ok: Int = if (android.os.Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION") ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION") ch.value = data
            @Suppress("DEPRECATION") val legacy = g.writeCharacteristic(ch)
            if (legacy) BluetoothGatt.GATT_SUCCESS else 1
        }
        if (ok != BluetoothGatt.GATT_SUCCESS) {
            // BACKOFF, et pas 5 essais a 100 ms puis abandon. Mesure du 11-14 22:46-22:47 :
            // les rejets GATT=201 (statut de la pile du controleur, absente des constantes
            // publiques BluetoothGatt et BluetoothStatusCodes) se succedaient a ~100 ms
            // d'intervalle, exactement le rythme du retry, donc le retry retombait dans la
            // meme congestion. Les 201 observes etaient tous emis pendant que les DEUX apps
            // (com.eyevue.glassapp pid 24579 et com.local.eyevuectl pid 27182) tenaient la
            // MEME liaison GATT vers C2:01:06:00:3A:A1 : dumpsys rend « 7: C2:01:06:00:3A:A1
            // appId=7 », une file d'ecritures pour deux clients. Un backoff qui double
            // laisse a la pile le temps de vider la sienne.
            val step = 100L * (1L shl retries.coerceAtMost(4))   // 100,200,400,800,1600,1600...
            if (retries < 9) {
                retries++
                inFlight = data
                // data a ete sortie de la file par le poll() du debut de drain() : la
                // re-emission doit la RE-INSCRIRE, sinon drain() ecrit la trame SUIVANTE
                // et la trame qui a echoue disparait. C'est ce que faisait deja le retry
                // d'origine (queue.add(data)); le garder est obligatoire.
                handler.postDelayed({ writing = false; queue.add(data); drain() }, step)
                if (retries == 1)
                    emit(State.ERROR, "writeCharacteristic GATT=$ok (" + nameOfGatt(ok) + ") cmd=" +
                        cmdOf(data) + ": re-emission en backoff x" + retries + " (" + step + " ms)")
            } else {
                retries = 0
                writing = false
                inFlight = null
                droppedFrames++
                // Le total est calcule, pas annonce aujugure : somme des pas appliques.
                val totalMs = (0..8).sumOf { 100L * (1L shl it.coerceAtMost(4)) }
                val key = data.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                val pushed = repushes[key] ?: 0
                if (pushed < 1) {
                    // UNE seule re-émission en fin de file par trame : la mesure du 11-15
                    // 01:03 montre qu'au-dela la file tourne en boucle sans jamais emitter
                    // une commande utile (cycle de 10 s, « jetees = 1 » puis « = 2 »).
                    repushes[key] = pushed + 1
                    queue.add(data)
                    emit(State.ERROR, "writeCharacteristic GATT=$ok (" + nameOfGatt(ok) + ") cmd=" +
                        cmdOf(data) + " : " + totalMs + " ms d'essais en backoff, re-émise en fin " +
                        "de file (1re et derniere fois pour cette trame), trames jetees = " +
                        droppedFrames + ". " + busyHint(ok))
                    handler.postDelayed({ drain() }, 400)
                } else {
                    // Deja re-émise, echoue encore : la file est morte, la vider et refaire
                    // la liaison. Garder la trame dans une file qui ne part jamais est
                    // exactement le defaut mesure du 11-15 01:03.
                    val n = queue.size
                    queue.clear()
                    emit(State.ERROR, "writeCharacteristic GATT=$ok (" + nameOfGatt(ok) + ") cmd=" +
                        cmdOf(data) + " : echec APRES sa re-émission (" + totalMs +
                        " ms d'essais), file VIDEE (" + n + " trame(s) abandonnee(s)), trames " +
                        "jetees = " + droppedFrames + ". " + busyHint(ok) + tryReconnect())
                }
            }
        } else {
            retries = 0
            inFlight = null
        }
    }

    /**
     * 201 = ERROR_GATT_WRITE_REQUEST_BUSY, nom resolu par reflection sur
     * BluetoothStatusCodes : la pile du controleur refuse l'appel parce qu'une ecriture
     * est DEJA en attente pour ce client. Ce que la mesure etablit : le rejet persiste
     * APRES la fermeture de com.eyevue.glassapp (pid 24579 tue a 01:03, pidof rendu vide,
     * liaison alors etablie status=0) ; ce n'est donc plus l'app d'aote qui tient la
     * liaison, c'est la file de NOTRE propre liaison, et la seule issue est d'en refaire
     * une. Les 201 du 11-14 22:46 (deux apps vivantes) etaient la meme cause, vue du
     * dehors : la liaison est EXCLUSIVE (mesure : status=62 a la connexion quand glassapp
     * est vivante).
     */
    private fun busyHint(ok: Int): String =
        if (ok == 201) "201 = ERROR_GATT_WRITE_REQUEST_BUSY : le controleur a deja une " +
            "ecriture en attente pour ce client ; marteler la meme liaison ne la vide pas. "
        else "statut $ok "

    /** Refait la liaison (file neuve), max 2 fois, pour ne pas remplacer une boucle de
     *  trames par une boucle de connexions. Le texte est journalise par l'appelant. */
    private fun tryReconnect(): String {
        val d = peer ?: return "Reconnexion impossible : aucun appareil connu."
        if (reconnects >= 2) return "Reconnexion deja tentée 2 fois, arretee : liaison " +
            "inutilisable, fermer l'app et relancer."
        reconnects++
        handler.post {
            emit(State.CONNECTING, "reconnexion n°$reconnects apres ecritures bloquees")
            val keep = peer
            gatt?.close()
            gatt = null
            writing = false
            queue.clear()
            peer = keep
            keep?.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        }
        return "Reconnexion tentée n°$reconnects (liaison refaite, file neuve)."
    }
}
