package com.local.eyevuectl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Import par WiFi : sequence reproduite a partir de l'analyse d'interop
 * (bytecode de l'app constructeur + logs systeme de la session d'import du
 * 11/11 20:41), see protocol/PROTOCOL.md.
 *
 *   1. 0x39 ouvre l'AP des lunettes (payload observe sur NOTRE paire = [01]).
 *   2. scan repete en attendant l'AP (le log constructeur: 20 cycles max, 5 s).
 *      Le SSID BLE (0x25) differe du SSID reel de l'AP ("DH-Twi-..." cote BLE,
 *      "DH-TwI-..." cote AP) -> match INSENSIBLE A LA CASSE, on connecte le
 *      SSID du scan, pas celui du BLE.
 *   3. requestNetwork + WifiNetworkSpecifier (la voie utilisee par l'app
 *      constructeur, verifiee dans les logs: "WifiMatcher: match: SSID LITERAL"),
 *      puis bindProcessToNetwork. WPA2-PSK; cle = la constante relevee dans
 *      ModBleResponse, transmise a onWifiData(ssid, cle), jamais recue par BLE.
 *   4. sonde AP_HOST:80, 6 tentatives (log constructeur "probe ... 1/6, 27 ms").
 *   5. GET sequentiels avec Range (reprise Content-Range), Connection: close,
 *      Accept-Encoding: identity, ecriture "rwd" (l'AP tombe des que la liste
 *      est vide: la reprise evite de corrompre un fichier si le lien casse).
 *   6. fichier temp en cache puis MediaStore (dossier DCIM/100HUNTI, chemin
 *      canonique relevé dans le bytecode), visible dans la galerie.
 */
class WifiImport(
    private val ctx: Context,
    private val prefs: SharedPreferences,
    private val listener: Listener,
) {
    interface Listener {
        fun onPhase(phase: String, detail: String = "")
        /** appele sur le fil principal des que la liste de l'AP est lue. */
        fun onListReceived(files: List<MediaFile>) {}
        fun onProgress(done: Int, total: Int, filePct: Int)
        fun onFileDone(f: MediaFile)
        fun onFinished(okCount: Int, failed: List<String>)
        fun onLog(s: String)
    }

    @Volatile private var cancelled = false
    private var connThread: Thread? = null
    private var httpConn: HttpURLConnection? = null
    private var pendingCb: ConnectivityManager.NetworkCallback? = null
    private var scanReceiver: BroadcastReceiver? = null
    private val main = Handler(Looper.getMainLooper())

    /** SSID recu par 0x25, regle par l'appelant avant start(). */
    var bleSsid: String? = null

    companion object {
        const val TAG = "EyevueImport"
        const val AP_HOST = "192.168.169.1"      // l20.j0, releve dans les logs 20:41
        // l20.i0 : l20.getWifiBaseUrl() (smali_classes4/l20.smali:160) renvoie
        //   i0 = "http://192.168.1.254/" quand re7.s && re7.t (isTWifiApi : mode
        //   "T-WiFi", celui des lunettes AP de cette paire),
        //   j0 = "http://192.168.169.1/" quand re7.s seul,
        //   h0 = "http://192.168.49.207/" sinon (WiFi Direct / P2P).
        // Mesure du 11-12 13:36:38 : "http://192.168.169.1/?custom=1&cmd=3015 ->
        // http 404" corps "URL is err" -> le serveur joint repond, mais sur i0.
        // Les deux sont tries, dans l'ordre de getWifiBaseUrl().
        const val AP_HOST_T = "192.168.1.254"
        val AP_HOSTS = listOf(AP_HOST, AP_HOST_T)
        const val AP_PORT = 80
        const val DEFAULT_PSK = "12345678"       // constante ModBleResponse (analyse interop)
        const val GALLERY_DIR = "DCIM/100HUNTI"  // chemin canonique du bytecode
        const val PREF_LAST_SSID = "last_ap_ssid"

        // LISTING. L'interface Retrofit du constructeur (smali_classes4/w6j.smali)
        // declare, sur la base http://192.168.169.1/ :
        //   getDevicePhotoList()     -> @GET "?custom=1&cmd=3015"   <- AP classique
        //   getDevicePhotoList_Ap()  -> @GET "app/getfilelist"      <- modele "AP"
        // La racine nue "/" n'est JAMAIS demandee seule : le serveur embarque ne
        // repond qu'a ces query. D'ou le "http=000, 0 octet" en 35 s sur GET /.
        const val LIST_QUERY = "?custom=1&cmd=3015"
        const val LIST_PATH_AP = "app/getfilelist"

        // SUPPRESSION SUR LES LUNETTES. Extrait du bytecode du constructeur, pas devine :
        //   smali_classes4/w6j.smali:6-28  deleteFile(Ljava/lang/String;)
        //       .annotation runtime Lretrofit2/http/Url;   <- le parametre EST l'URL
        //       .annotation runtime Lretrofit2/http/GET;   <- donc GET, pas DELETE
        //   smali_classes4/jb4.smali:40-95 construit cette URL :
        //       isTWifiApi() -> "app/deletefile?file="            (mode T-WiFi)
        //       sinon        -> "?custom=1&cmd=4003&str=A:\DCIM\100HUNTI\"
        //   k30.smali:7-17 = table litterale de ces chaines.
        // Le constructeur passe un NOM, pas un chemin : le prefixe porte deja le
        // dossier (A:\DCIM\100HUNTI\), le nom est concatene derriere.
        const val DEL_CLASSIC = "?custom=1&cmd=4003&str=A:\\DCIM\\100HUNTI\\"
        // Variante encodee du MEME prefixe. Non optionnelle pour etre fidele : le
        // constructeur passe cette chaine par le @Url de Retrofit, donc par
        // okhttp3.HttpUrl, qui percent-encode le backslash dans une query. Ce que
        // l'AP recoit reellement du constructeur est donc "A:%5CDCIM%5C100HUNTI%5C".
        // Un serveur embarque qui decode mal les %5C rejette la forme nue : les deux
        // sont testees, et le journal dit laquelle a fait agir la commande.
        const val DEL_CLASSIC_ENC = "?custom=1&cmd=4003&str=A:%5CDCIM%5C100HUNTI%5C"
        const val DEL_T_WIFI = "app/deletefile?file="

        // Ordre de la supposition, etaye par la mesure : telecharger a marche sur
        // http://$AP_HOST/EVENT/<nom> (import reussi du 12-11), donc la base qui
        // sert les fichiers est $AP_HOST. Le 404 du 11-12 sur
        // "http://192.168.169.1/?custom=1&cmd=3015 -> URL is err" montre que le
        // serveur repond a TOUTE URL, y compris inconnue : le CRITERE EST LE CODE
        // HTTP, pas la taille du corps.
        val DEL_BASES = listOf(AP_HOST, AP_HOST_T)

        // Sonde AP : valeurs litterales du constructeur (voir probeAp).
        const val AP_PROBE_ATTEMPTS = 6
        const val AP_PROBE_INTERVAL_MS = 500L
        const val AP_PROBE_TIMEOUT_MS = 1500
    }

    fun cancel() {
        cancelled = true
        try { httpConn?.disconnect() } catch (_: Exception) {}
        connThread?.interrupt()
        release()
    }

    /**
     * Point d'entree. Ne prend PAS de liste de fichiers : la liste est lue sur
     * l'AP elle-meme, une fois l'AP associee (listing = HTTP GET sur la racine,
     * log constructeur 20:41:01.217 "Request succeeded: http://192.168.169.1/",
     * reponse de 6,9 ko, 2,2 s AVANT le premier GET de fichier).
     *
     * count = compteur 0x42, sert a l'affichage quand le listing n'a pas de SIZE.
     */
    fun start(count: Int) {
        cancelled = false
        connThread = Thread { runImport(count) }.also { it.isDaemon = true; it.start() }
    }

    /**
     * Predictat "ce media est deja dans la galerie", renseigne par l'ecran.
     * Mesure du 12-11 : 0x42 renvoie [00 05] APRES l'import des 5 fichiers (les
     * fichiers restent sur l'AP, l'import ne les efface pas). Sans ce garde-fou,
     * recliquer sur Importer retélécharge les memes fichiers et MediaStore cree
     * des doublons "DH-... (1).jpg". On saute ce qui est deja la, en le
     * journalisant (rien n'est simule : la decision vient de la cle canonique).
     */
    var alreadyImported: (String) -> Boolean = { false }

    // ---------------------------------------------------------------- sequence

    private fun runImport(count: Int) {
        var ok = 0
        val failed = ArrayList<String>()
        try {
            val apSsid = acquireAp()
            if (apSsid == null) {
                if (!cancelled) main.post { listener.onPhase("echec", "AP introuvable / connexion refus\u00e9e") }
                return
            }
            if (!probeAp()) {
                if (!cancelled) main.post { listener.onPhase("echec", "AP jointe mais $AP_HOST:$AP_PORT injoignable") }
                return
            }
            // Etape 4bis : LE LISTING. C'est lui qui donne les noms /EVENT/<ts>.jpg,
            // il n'existe qu'AP allumee : aucun chemin BLE ne peut le fournir.
            main.post { listener.onPhase("listing", "listing des fichiers de l'AP\u2026") }
            val files = fetchList()
            if (files == null) {
                if (!cancelled) main.post { listener.onPhase("echec", "listing illisible sur http://$AP_HOST/") }
                return
            }
            if (files.isEmpty()) {
                if (!cancelled) main.post { listener.onPhase("echec", "AP jointe, listing vide (\u00ab $count \u00bb annonc\u00e9 par 0x42)") }
                return
            }
            main.post { listener.onListReceived(files) }
            // Garde-fou double import (voir alreadyImported) : la liste de l'AP
            // ne change pas apres un import, ce qui est deja en galerie est ecarte.
            val todo = files.filter { f ->
                val done = try { alreadyImported(f.key) } catch (_: Exception) { false }
                if (done) Log.i(TAG, "deja en galerie, saute : ${f.name} (cle ${f.key})")
                !done
            }
            if (todo.isEmpty()) {
                if (!cancelled) main.post {
                    listener.onPhase("aucun",
                        "les ${files.size} fichiers de l'AP sont deja dans la galerie")
                }
                return
            }
            val total = todo.size
            todo.forEachIndexed { i, f ->
                if (cancelled) return@forEachIndexed
                main.post { listener.onPhase("importation", (i + 1).toString() + "/" + total) }
                when (val r = download(f, i + 1, total)) {
                    is Down.Ok -> { ok++; main.post { listener.onFileDone(f) } }
                    is Down.Err -> { failed.add(f.name + " : " + r.why) }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "runImport", t)
            failed.add(t.message ?: t.javaClass.simpleName)
        } finally {
            release()
            val n = ok; val fr = failed.toList()
            main.post { listener.onFinished(n, fr) }
        }
    }

    /** Etapes 1-3 : ouverture AP (envoyee par l'appelant) + scan + requestNetwork. */
    private fun acquireAp(): String? {
        val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        main.post { listener.onPhase("connexion", "ouverture de l'AP des lunettes\u2026") }

        // 0x25 (SSID) n'arrive que ~2,5 s apres 0x39 sur notre paire (log
        // 00:59:54.628 -> 00:59:57.107) : on lui laisse 8 s. Connu, il dispense
        // du scan systeme, qui est bride pour une app ciblee 33 sans
        // ACCESS_FINE_LOCATION (le scan ne remonte rien : "aucun SSID DH-*").
        var want: String? = bleSsid?.trim()?.takeIf { it.isNotEmpty() }
        var waited = 0
        while (want == null && !cancelled && waited < 16) {
            Thread.sleep(500); waited++
            want = bleSsid?.trim()?.takeIf { it.isNotEmpty() }
        }
        val scanSsid = if (want != null) {
            main.post { listener.onLog("SSID recu par BLE \u00ab $want \u00bb : association directe") }
            want
        } else scanForAp(wifi) ?: run {
            if (!cancelled) main.post { listener.onPhase("echec", "AP des lunettes non vue au scan (20 cycles de 5 s)") }
            return null
        }
        if (cancelled) return null
        main.post { listener.onPhase("connexion", "AP \u00ab $scanSsid \u00bb : accepte la fenetre de connexion Wi-Fi du telephone") }

        if (Build.VERSION.SDK_INT >= 26) {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val psk = prefs.getString("ap_psk", DEFAULT_PSK) ?: DEFAULT_PSK
            val spec = WifiNetworkSpecifier.Builder()
                .setSsid(scanSsid)
                .setWpa2Passphrase(psk)
                .build()
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
            val got = CountDownLatch(1)
            var connected = false
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connected = true
                    try { cm.bindProcessToNetwork(network) } catch (_: Exception) {}
                    got.countDown()
                }
                override fun onUnavailable() { got.countDown() }
            }
            pendingCb = cb
            // Le systeme met ~16 s a afficher sa fenetre d'association (log
            // 11-12 : requestNetwork a 01:38:20.853, WifiPickerActivity affiche a
            // 01:38:36.457). Un timeout de 15 s annulait la demande 0,5 s AVANT
            // l'affichage ("App released active request, cancelling" a
            // 01:38:35.906) : l'utilisateur trouvait un panneau vide. 120 s pour
            // laisser le temps de lire et de toucher le reseau.
            cm.requestNetwork(req, cb, 120000)
            got.await(125, TimeUnit.SECONDS)
            if (!connected || cancelled) {
                try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
                pendingCb = null
                if (cancelled) return null
                // Le supplicant rejette la cle (reason=3 dans le log, chemin
                // "mot de passe errone" prevu par le constructeur) : repli 1 geste,
                // la cle saisie est memorisee et reutilisee ensuite.
                openSystemPanel()
                main.post { listener.onPhase("echec", "association refus\u00e9e (cl\u00e9 \u00ab $psk \u00bb ?) \u2014 panneau WiFi ouvert ; connecte l'AP puis relance l'import") }
                return null
            }
            prefs.edit().putString(PREF_LAST_SSID, scanSsid).apply()
            return scanSsid
        }

        // API < 26 : pas de specifier possible. Repli panneau systeme.
        openSystemPanel()
        main.post { listener.onPhase("echec", "Android < 8 : connecte l'AP \u00ab $scanSsid \u00bb puis relance l'import") }
        return null
    }

    /**
     * Etape 2 : recherche de l'AP, 20 cycles de 5 s (valeurs relevees dans le log
     * constructeur : "已等待扫描次数: 20, 最大等待次数: 20").
     *
     * IMPORTANT (API 33 / cible 34) : le systeme ne diffuse plus
     * SCAN_RESULTS_AVAILABLE_ACTION aux apps ciblees 34, et startScan() est
     * limite. La boucle lit donc wifi.scanResults A CHAQUE tour (lecture directe,
     * seule voie qui fonctionne) ; startScan() n'est qu'un best-effort pour
     * rafraichir la liste.
     */
    private fun scanForAp(wifi: WifiManager): String? {
        val want = bleSsid?.trim()
        val lastSeen = AtomicReference<String?>(prefs.getString(PREF_LAST_SSID, null))
        val best = AtomicReference<ScanResult?>(null)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val b = pickScan(wifi, want, lastSeen.get())
                if (b != null) best.compareAndSet(null, b)
            }
        }
        scanReceiver = receiver
        try {
            ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        } catch (_: Exception) {}

        var cycle = 0
        while (best.get() == null && !cancelled && cycle < 20) {
            cycle++
            main.post { listener.onPhase("connexion", "recherche de l'AP des lunettes… (cycle ${cycle}/20)") }
            val started = try { wifi.startScan() } catch (e: Exception) { false }
            if (cycle == 1 || cycle == 20) {
                val all = try { wifi.scanResults } catch (e: Exception) { null }
                val n = all?.size ?: -1
                val sample = all?.mapNotNull { it.SSID }?.take(6)?.joinToString(", ") ?: "-"
                main.post { listener.onLog("scan startScan=$started, $n resultats, SSID vus: $sample") }
            }
            var tick = 0
            while (best.get() == null && !cancelled && tick < 10) {
                Thread.sleep(500); tick++
                val b = pickScan(wifi, want, lastSeen.get())
                if (b != null) best.set(b)
            }
        }
        try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        scanReceiver = null

        val b = best.get()
        var ssid = b?.SSID ?: lastSeen.get()
        // REPLI : le scan systeme est bride (startScan() limite, scanResults vides
        // pour les apps ciblees 33/34 sans ACCESS_FINE_LOCATION). Le SSID recu par
        // BLE (0x25) est EXACTEMENT celui de l'AP (0x25 = "DH-TwI-1A0F08", base
        // systeme: SSID="DH-TwI-1A0F08") : on peut donc lancer requestNetwork sans
        // scan prealable, le systeme scanne lui-meme pour son specifier.
        if (ssid == null && !want.isNullOrEmpty()) {
            ssid = want
            main.post { listener.onLog("scan muet : association directe sur le SSID BLE \u00ab $ssid \u00bb") }
        }
        if (ssid == null) {
            main.post { listener.onLog("aucun SSID « DH-* » vu au scan (20 cycles)") }
            return null
        }
        prefs.edit().putString(PREF_LAST_SSID, ssid).apply()
        return ssid          // le SSID du SCAN, sinon celui du BLE
    }

    /**
     * L'AP des lunettes : SSID en « DH-… », le plus fort. Le SSID annonce par BLE
     * (0x25) differe du SSID reel de l'AP ("DH-Twi-…" cote BLE, "DH-TwI-…" cote AP,
     * releve dans le log) -> comparaison INSENSIBLE A LA CASSE. Les SSID vus au
     * scan ne sont pas tous pairs (un autre modele peut passer) : on prefere le
     * SSID deja appaire par nous, sinon le plus fort qui correspond au BLE.
     */
    private fun pickScan(wifi: WifiManager, want: String?, lastSsid: String?): ScanResult? {
        val all = try { wifi.scanResults } catch (e: Exception) {
            main.post { listener.onLog("scan interdit : ${e.javaClass.simpleName}") }
            null
        } ?: return null
        val dh = all.filter { it.SSID != null && it.SSID.startsWith("DH-") }
        if (dh.isEmpty()) return null
        lastSsid?.let { s ->
            dh.firstOrNull { it.SSID == s }?.let { return it }
        }
        val matching = dh.filter { want == null || it.SSID.equals(want, ignoreCase = true) }
        val pool = matching.ifEmpty { dh }
        return pool.maxByOrNull { it.level }
    }

    /**
     * Etape 4 : sonde de joignabilite de l'AP. Valeurs prises dans le constructeur,
     * smali_classes4/com/eyevue/glassapp/helper/WifiApConnector.smali (init) :
     *   AP_REACHABILITY_PORT = 0x50 = 80
     *   AP_REACHABILITY_MAX_ATTEMPTS = 6, INTERVAL = 0x1f4 = 500 ms,
     *   AP_REACHABILITY_TIMEOUT_MS = 0x5dc = 1500 ms
     * Notre timeout de 3000 ms etait donc plus lent que le sien.
     */
    private fun probeAp(): Boolean {
        for (t in 1..AP_PROBE_ATTEMPTS) {
            if (cancelled) return false
            // Les deux hotes de getWifiBaseUrl() sont sondes a chaque cycle :
            // le cycle de la liaison ne dit pas quel hote le firmware sert.
            for (h in AP_HOSTS) {
                try {
                    Socket().use { it.connect(InetSocketAddress(h, AP_PORT), AP_PROBE_TIMEOUT_MS) }
                    main.post { listener.onLog("sonde AP ok sur $h ($t/$AP_PROBE_ATTEMPTS)") }
                    return true
                } catch (e: Exception) {
                    main.post { listener.onLog("sonde AP $h $t/$AP_PROBE_ATTEMPTS : ${e.message}") }
                }
            }
            Thread.sleep(AP_PROBE_INTERVAL_MS)
        }
        return false
    }

    // ---------------------------------------------------------------- listing

    /**
     * Etape 4bis : liste des fichiers stockes sur l'AP.
     *
     * URL = "http://192.168.169.1/?custom=1&cmd=3015" : le @GET de
     * getDevicePhotoList() dans l'interface Retrofit du constructeur
     * (smali_classes4/w6j.smali). C'est LA query qui declenche la reponse du
     * serveur embarque ; une GET sur "/" seule ne lui arrache rien (mesure du
     * 11-12 : TCP etabli sur le port 80, 0 octet rendu en 35 s). Le log
     * constructeur "Request succeeded: http://192.168.169.1/" (20:41:01.217,
     * tache de 6,9 ko) est la meme requete, Retrofit n'affichant que la base.
     *
     * Repli : "app/getfilelist" (getDevicePhotoList_Ap, modele dit "AP"), puis
     * la racine nue, pour couvrir les deux variantes de firmware.
     *
     * Retourne null si la requete echoue, une liste vide si elle ne contient
     * aucun element <File> ; dans les deux cas le corps brut est journalise
     * (hexa + texte) pour qu'on corrige le format sur la reelle reponse.
     */
    private fun fetchList(): List<MediaFile>? {
        // Ordre etabli par MESURE, pas par lecture. Sur cette paire, le cycle du
        // 11-12 14:07 donne : 1.254 INJOIGNABLE (2 timeouts de 5 s), puis
        // "http://192.168.169.1/app/getfilelist -> http 200, 635 octets". Donc
        // l'URL PROUVEE passe en tete : un import reussi coute 1 requete, pas
        // 10 s d'attente sur un hote qui ne repond pas. Les autres restent en
        // secours, y compris 1.254 (getWifiBaseUrl() mode T-WiFi, l20.smali:160),
        // qui est l'URL des firmwares "T".
        val body = httpGet(listOf(
            "http://$AP_HOST/$LIST_PATH_AP",
            "http://$AP_HOST/$LIST_QUERY",
            "http://$AP_HOST_T/$LIST_QUERY",
            "http://$AP_HOST_T/$LIST_PATH_AP",
            "http://$AP_HOST/"
        )) ?: return null
        val list = MediaFile.parseFileList(body)
        if (list.isEmpty()) dumpListing(body)
        return list
    }

    /**
     * Tente les URL dans l'ordre, rend le premier corps d'une reponse 2xx non vide.
     *
     * Le critere est le CODE HTTP, pas la taille du corps. Mesure du 11-12
     * 13:36:38 : "http://192.168.169.1/?custom=1&cmd=3015 -> http 404, 43 octets"
     * dont le corps "<html><body><p>URL is err</p></body></html>" est NON VIDE :
     * lancien critere "corps non vide" retournait donc cette page d'erreur et ne
     * tentait JAMAIS les URL suivantes. Le serveur des lunettes repond a toute URL,
     * y compris inconnue, par un 404 + "URL is err" : un corps n'est une reponse
     * QUE si le code est 2xx.
     */
    private fun httpGet(urls: List<String>): String? {
        for (u in urls) {
            val body = try {
                val c = URL(u).openConnection() as HttpURLConnection
                httpConn = c
                c.connectTimeout = 5000
                c.readTimeout = 20000
                c.setRequestProperty("Connection", "close")
                c.setRequestProperty("Accept-Encoding", "identity")
                c.connect()
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val bytes = stream?.readBytes() ?: ByteArray(0)
                c.disconnect()
                httpConn = null
                main.post { listener.onLog("listing $u -> http $code, ${bytes.size} octets") }
                if (code in 200..299) String(bytes, Charsets.UTF_8) else null
            } catch (e: Exception) {
                httpConn = null
                main.post { listener.onLog("listing $u : ${e.message}") }
                null
            }
            if (!body.isNullOrEmpty()) return body
        }
        return null
    }

    @Deprecated("remplace par httpGet")
    private fun fetchListOld(): List<MediaFile>? {
        val body = try {
            val c = URL("http://" + AP_HOST + "/").openConnection() as HttpURLConnection
            httpConn = c
            c.connectTimeout = 5000
            c.readTimeout = 10000
            c.setRequestProperty("Connection", "close")
            c.setRequestProperty("Accept-Encoding", "identity")
            c.connect()
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.readBytes() ?: ByteArray(0)
            c.disconnect()
            httpConn = null
            main.post { listener.onLog("listing http $code, ${bytes.size} octets") }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            httpConn = null
            main.post { listener.onLog("listing : ${e.message}") }
            return null
        }
        val list = MediaFile.parseFileList(body)
        if (list.isEmpty()) dumpListing(body)
        return list
    }

    /** Journalise la reelle reponse de l'AP quand aucun <File> n'est trouve. */
    private fun dumpListing(body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val hex = bytes.take(96).joinToString(" ") { "%02x".format(it) }
        val text = body.take(600).replace("\r", " ").replace("\n", " ")
        main.post { listener.onLog("listing sans <File> : hex[$hex] texte[$text]") }
        Log.w(TAG, "listing brut (${bytes.size} o): $text")
    }

    // ---------------------------------------------------------------- download

    sealed class Down {
        class Ok(val uri: android.net.Uri) : Down()
        class Err(val why: String) : Down()
    }

    sealed class ApRes {
        class Ok(val detail: String) : ApRes()
        class Err(val why: String) : ApRes()
    }

    /**
     * Suppression d'UN fichier sur les lunettes, puis VERIFICATION par listing.
     *
     * Le GET d'appel ne prouve rien : le serveur embarque repond 200 a des URLs
     * qu'il ne connait pas (constat du 11-12). Seule la disparition du fichier du
     * listing prouve que la commande a agi. Donc : listing avant, si le fichier
     * n'y est pas deja -> reussi sans action ; listing apres, s'il y est encore ->
     * echec, la commande n'a rien fait.
     *
     * Le nom est pris TEL QUEL du listing AP (ex. "20261111204012731.jpg") :
     * c'est celui que le constructeur concatene apres son prefixe. La forme exacte
     * acceptee (avec/sans extension) n'est pas tranchee par le code ; les quatre
     * candidates sont testees dans l'ordre et le journal dit laquelle a fait
     * disparaitre le fichier. Rien n'est declare reussi sans le listing.
     *
     * APPEL IRREVERSIBLE : ce qui est efface des lunettes n'est pas recuperable.
     */
    fun deleteOnAp(name: String): ApRes {
        if (name.isEmpty()) return ApRes.Err("nom de fichier vide")
        // La cle canonique est SEUL critere de reconnaissance : le nom que
        // l'appelant connait est local ("DH-261111-204012731-20261111204012731.jpg")
        // et le nom du listing est "20261111204012731.jpg". Comparer les noms a
        // directement tort : un "after.none { it.name == name }" serait toujours
        // VRAI, donc une commande refusee serait annoncee supprimee. La comparaison
        // se fait sur MediaFile.keyOf des deux cotes (17 chiffres communs).
        val key = MediaFile.keyOf(name)
        val apSsid = acquireAp()
            ?: return ApRes.Err("AP des lunettes non jointe : rien n'est supprime sur les lunettes")
        try {
            val before = fetchList()
                ?: return ApRes.Err("listing AVANT illisible : suppression AP non lancee")
            fun present(list: List<MediaFile>) = list.any { MediaFile.keyOf(it.name) == key }
            val target = before.firstOrNull { MediaFile.keyOf(it.name) == key }
            if (target == null) return ApRes.Ok("$name n'etait plus sur les lunettes")

            // Le nom ENVOYE est celui du LISTING, resolu ici a partir de la cle :
            // l'appelant ne le connait pas toujours (le visualiseur ne connait que
            // le nom local "DH-261111-204012731-20261111204012731.jpg", l'AP ne
            // connait que "20261111204012731.jpg"). Envoyer le nom local fait
            // rejeter la commande par l'AP ; le nom du listing est le seul que le
            // constructeur concatene apres son prefixe.
            val forms = ArrayList<String>()
            forms.add(target.name)
            forms.add(target.name.substringBeforeLast('.'))
            if (name != target.name) { forms.add(name); forms.add(name.substringBeforeLast('.')) }
            var lastWhy = "aucune URL testee"
            for (base in DEL_BASES) {
                for (form in forms) {
                    for (q in listOf(DEL_CLASSIC, DEL_CLASSIC_ENC, DEL_T_WIFI)) {
                        val url = "http://" + base + "/" + q + form
                        main.post { listener.onPhase("listing", "suppression AP : $name") }
                        val code = httpCode(url)
                        main.post { listener.onLog("delete $url -> http $code") }
                        lastWhy = "http $code"
                        // 4xx/5xx = l'AP a repondu et REJETE cet endpoint : c'est
                        // une reponse, pas une absence de reponse. On ne reliste
                        // pas (1 a 2 s par listing) et on passe a la forme suivante.
                        if (code in 400..599) { lastWhy = "http $code (endpoint rejete)"; continue }
                        if (code !in 200..299) continue    // injoignable : base suivante
                        val after = fetchList()
                            ?: return ApRes.Err("http $code mais listing de controle illisible : " +
                                "suppression NON verifiee")
                        if (!present(after)) return ApRes.Ok("$name efface des lunettes ($url)")
                        lastWhy = "http $code, fichier toujours present au listing"
                    }
                }
            }
            return ApRes.Err("suppression AP sans effet : $lastWhy")
        } catch (t: Throwable) {
            return ApRes.Err(t.message ?: t.javaClass.simpleName)
        } finally {
            release()
        }
    }

    /** Code HTTP d'un GET, journalise par l'appelant. Ne leve pas sur 4xx/5xx. */
    private fun httpCode(u: String): Int = try {
        val c = URL(u).openConnection() as HttpURLConnection
        httpConn = c
        c.connectTimeout = 5000
        c.readTimeout = 15000
        c.setRequestProperty("Connection", "close")
        c.setRequestProperty("Accept-Encoding", "identity")
        c.connect()
        val code = c.responseCode
        (if (code in 200..299) c.inputStream else c.errorStream)?.readBytes()
        c.disconnect()
        httpConn = null
        code
    } catch (e: Exception) {
        httpConn = null
        -1
    }

    /**
     * Etapes 5-6 : GET sur l'AP. L'URL complete observee dans le log constructeur
     * est "http://" + AP_HOST + "/EVENT/<YYYYMMDDHHMMSSmmm>.jpg" ; le chemin
     * canonique du bytecode est /DCIM/100HUNTI/. On telecharge f.path tel quel.
     */
    private fun download(f: MediaFile, idx: Int, total: Int): Down {
        val url = "http://" + AP_HOST + (if (f.path.startsWith("/")) "" else "/") + f.path
        val tmp = File(ctx.cacheDir, f.name)
        var start = if (tmp.exists()) tmp.length() else 0L
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            httpConn = conn
            conn.connectTimeout = 5000
            conn.readTimeout = 15000
            conn.setRequestProperty("Connection", "close")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (start > 0) conn.setRequestProperty("Range", "bytes=" + start + "-")
            conn.connect()
            val code = conn.responseCode
            val resume: Boolean
            if (code == 206) resume = true
            else if (code == 200) { resume = false; start = 0L }
            else { conn.disconnect(); return Down.Err("HTTP $code") }
            val totalLen = if (conn.contentLength > 0) conn.contentLength + start else 0L
            RandomAccessFile(tmp, "rwd").use { raf ->
                if (!resume) raf.setLength(0)
                raf.seek(if (resume) start else 0L)
                val inp = conn.inputStream
                val buf = ByteArray(16 * 1024)
                var readTotal = start
                var n = inp.read(buf)
                var lastPct = -1
                while (n >= 0) {
                    if (cancelled) { inp.close(); conn.disconnect(); return Down.Err("annul\u00e9") }
                    raf.write(buf, 0, n)
                    readTotal += n
                    if (totalLen > 0) {
                        val pct = (readTotal * 100 / totalLen).toInt()
                        if (pct / 5 != lastPct / 5) {
                            lastPct = pct
                            main.post { listener.onProgress(idx, total, pct) }
                        }
                    }
                    n = inp.read(buf)
                }
                inp.close()
            }
            conn.disconnect()
            httpConn = null
            val uri = publish(tmp, f) ?: return Down.Err("\u00e9criture galerie refus\u00e9e")
            tmp.delete()
            Down.Ok(uri)
        } catch (e: Exception) {
            httpConn = null
            Down.Err(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Etape 6 : MediaStore (dossier galerie), calque du log (fichier temp -> galerie). */
    private fun publish(tmp: File, f: MediaFile): android.net.Uri? {
        val name = f.localName()
        val resolver = ctx.contentResolver
        val mime = mimeFor(f.ext)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, GALLERY_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val ext = if (mime.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                      else if (mime.startsWith("audio")) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                      else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(ext, values) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            } ?: return null
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DCIM), "100HUNTI")
        if (!dir.exists() && !dir.mkdirs()) return null
        val dst = File(dir, name)
        tmp.copyTo(dst, overwrite = true)
        android.media.MediaScannerConnection.scanFile(
            ctx, arrayOf(dst.absolutePath), arrayOf(mime), null)
        return android.net.Uri.fromFile(dst)
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        "3gp" -> "video/3gpp"
        "mov" -> "video/quicktime"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    /** Annulation / fin : libere le reseau (le constructeur fait pareil avant de rafraichir). */
    private fun release() {
        scanReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        scanReceiver = null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        pendingCb?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        pendingCb = null
        try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
    }

    /** Repli 1 geste quand la cle pre-remplie est refusee (1 geste = le panneau). */
    private fun openSystemPanel() {
        try {
            @Suppress("DEPRECATION")
            val i = if (Build.VERSION.SDK_INT >= 29)
                Intent(WifiManager.ACTION_PICK_WIFI_NETWORK)
            else Intent("android.settings.WIFI_SETTINGS")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            main.post { listener.onLog("panneau WiFi indisponible : ${e.message}") }
        }
    }

    // ==========================================================================
    // TRADUCTION : recuperer la PHOTO PLEINE RESOLUTION, pas la vignette BLE.
    //
    // Pourquoi passer par l'AP alors que le BLE sait declencher la prise de vue :
    // la vignette que le BLE rend (trame 0x45/0x42, PTakePhoto.Thumbnail=0x30)
    // fait 720x300 pour ~5,7 ko. Les pleines resolutions mesurees sur ce depot
    // font 1 280x960 a 3 200x2 400 pour 129 ko a 379 ko, soit 53 fois plus de
    // pixels. Un texte de 50 caracteres sur une page A4 tient dans ~300 pixels de
    // large : sur la vignette il est sous-echantillone, aucun OCR ne le sort.
    // Le BLE declenche (0x22, PTakePhoto.HighQuality=0x31) ; l'AP rend le fichier.
    //
    // Choix de la photo : le listing de l'AP n'est PAS trie, et MediaFile.keyOf
    // donne une IDENTITE (les 17 chiffres), pas un ordre. Le seul critere sur
    // lequel s'appuyer est epochSec, et il est compare a l'instant LOCAL du
    // declenchement : les fichiers deja presentes sur les lunettes sont donc
    // ecartees par construction, pas par hypothese.
    // ==========================================================================

    sealed class Snap {
        class Ok(val uri: android.net.Uri, val bytes: ByteArray, val f: MediaFile) : Snap()
        class Err(val why: String) : Snap()
    }

    /**
     * Prend la photo pleine resolution la plus recente, l'amene dans la galerie,
     * rend ses octets. Appeler sur fil secondaire : bloquant (association AP =
     * ~20 s, dont la fenetre systeme que l'utilisateur doit toucher).
     *
     * @param triggerEpochSec heure LOCALE du declenchement, en SECONDES (la
     *   conversion epochSec <-> millisecondes est explicite ici : epochSec est en
     *   secondes, voir Fabricants.kt, epochMs = epochSec*1000).
     */
    fun captureLatestPhoto(triggerEpochSec: Long, done: (Snap) -> Unit) {
        cancelled = false
        connThread = Thread {
            val r = try { captureSequence(triggerEpochSec) }
                catch (t: Throwable) { Snap.Err(t.message ?: t.javaClass.simpleName) }
            main.post { done(r) }
        }
        connThread?.start()
    }

    private fun captureSequence(trigger: Long): Snap {
        main.post { listener.onPhase("connexion", "ouverture de l'AP des lunettes\u2026") }
        val ssid = acquireAp() ?: return Snap.Err(
            "AP des lunettes non jointe. Sans l'AP il n'y a PAS de pleine resolution : " +
                "la vignette BLE (720x300) ne rend pas du texte lisible, la traduction " +
                "est impossible par ce chemin")
        listener.onLog("AP « $ssid » associee")
        // release() est OBLIGATOIRE en finally : acquireAp() a appele
        // bindProcessToNetwork(network). Tant que ce lien tient, TOUT le trafic
        // HTTP de l'app sort par l'AP des lunettes, qui ne donne pas acces au
        // reseau local. Le serveur IA est sur le reseau local : sans release(),
        // la requete de traduction part dans l'AP et echoue, avec un message
        // d'erreur qui ne dit rien de la vraie cause.
        try {
            val files = fetchList() ?: return Snap.Err(
                "listing de l'AP indisponible : aucune des 2 bases n'a repondu " +
                    "(10 tentatives, 2,4 s). Le serveur embarque ne repond qu'aux " +
                    "query du constructeur, jamais a la racine nue")
            if (files.isEmpty()) return Snap.Err("listing vide : aucune photo sur les lunettes")

            // Fraiches = parties APRÈS le declenchement. C'est ce filtre qui
            // ecarte les photos deja presentes, et il ne fonctionne que si
            // l'epoch du listing est comparable a l'heure du telephone : c'est
            // pour cela que l'app envoie TIME (0x16) a la connexion (Fabrice).
            val fresh = files.filter { it.epochSec != null && it.epochSec >= trigger }
            val pool = if (fresh.isNotEmpty()) fresh
            // Repli honnete : si AUCUN fichier n'est marque posterieur, le
            // declenchement n'a pas cree de fichier (modele qui ne prend pas en
            // charge la prise de vue a distance, ou epoch du listing caler sur
            // une autre horloge que celle du telephone). On ne choisit alors pas
            // une photo au hasard sans le dire : on prend les 3 plus recentes et
            // la suite affiche qu'elle vient du listing, pas de la commande.
            else files.filter { it.epochSec != null }
                .sortedBy { it.epochSec }.takeLast(3)
                .also { listener.onLog("aucun fichier marque posterieur au declenchement : " +
                    "l'epoch du listing est sans doute cale sur une autre horloge que " +
                    "celle du telephone (0x16) ; repli sur les 3 plus recentes") }

            val newest = pool.filter { it.tab == MediaFile.TAB_PHOTO }
                .maxByOrNull { it.epochSec!! }
                ?: return Snap.Err(
                    "aucune IMAGE dans le listing de l'AP (${files.size} entrees) : la " +
                    "commande de prise de vue n'a produit aucun fichier. Verifier que le " +
                    "modele accepte la prise de vue a distance (capacites 0x95)")

            listener.onLog("photo retenue : ${newest.name}, ${newest.size} octets, " +
                "epoch ${newest.epochSec} (" +
                (if (fresh.isNotEmpty()) "declenchee par la commande" else "PLUS RECENTE DU LISTING, pas la capture") + ")")

            main.post { listener.onPhase("importation", "1/1") }
            // download() publie dans la galerie (publish -> MediaStore) et supprime
            // le temp : le fichier est donc rendu par son Uri, pas par un chemin.
            // C'est ce qui evite de lire un temp en cours d'ecriture.
            when (val d = download(newest, 1, 1)) {
                is Down.Ok -> {
                    val bytes = ctx.contentResolver.openInputStream(d.uri)
                        ?.use { it.readBytes() }
                        ?: return Snap.Err("fichier importe mais MediaStore le rend illisible")
                    // Garde-fou : une vignette fait ~5,7 ko. Un fichier de cette
                    // taille veut dire que l'AP n'a sert que la vignette, auquel cas
                    // la traduction echouerait sans que le motif soit visible.
                    if (bytes.size < 20_000) return Snap.Err(
                        "${bytes.size} octets seulement : ce fichier est une vignette, pas " +
                            "la pleine resolution. La traduction de texte est inutilisable " +
                            "sur une vignette (720x300)")
                    listener.onLog("${bytes.size} octets lus depuis la galerie")
                    return Snap.Ok(d.uri, bytes, newest)
                }
                is Down.Err -> return Snap.Err("telechargement depuis l'AP impossible : " + d.why)
            }
        } finally {
            release()
            listener.onLog("AP relachee (bindProcessToNetwork annule) : acces reseau local rendu")
        }
    }
}
