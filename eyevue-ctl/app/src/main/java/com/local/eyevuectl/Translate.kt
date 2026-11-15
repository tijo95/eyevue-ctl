package com.local.eyevuectl

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Traduction du texte visible par les lunettes, lue a voix haute.
 *
 * Chaine complete, et ce que chaque maillon coute :
 *
 *   1. declencher la prise de vue ........ BLE 0x22, payload 0x31 (PTakePhoto.HighQuality)
 *   2. attendre que la photo EXISTE ...... BLE 0x42 (compteur de fichiers SUR L'AP)
 *   3. aller la chercher ................. Wi-Fi AP 192.168.169.1, HTTP ( WifiImport.captureLatestPhoto )
 *   4. la traduire ....................... POST /v1/chat/completions, image en base64 ( Vision )
 *   5. la lire ........................... TextToSpeech, route A2DP ( Tts )
 *
 * Le maillon 2 est celui qui rend la chaine honnetement utilisable, et il est
 * nouveau ici. L'AP ne publie JAMAIS les noms de fichiers par le BLE : le BLE ne
 * donne qu'un compteur (0x42) et un SSID (0x25) ; les noms ne viennent que du
 * listing HTTP. On ne peut donc pas savoir par le BLE quel fichier la commande
 * vient de creer. En revanche le compteur, lui, est un etat de l'AP : le cycle
 * 0x44 -> 0x42 est observe dans nos journaux apres un telechargement (le
 * constructeur le fait, voir AlbumActivity). Demander 0x42 avant le declenchement
 * et apres, c'est connaitre le nombre de fichiers avant et apres la prise de vue.
 * Si le compteur monte, la photo est la : le listing est filtre par date, et
 * l'attente est bornee par un fait, pas par un sommeil arbitraire.
 *
 * Le pivot Wi-Fi est obligatoire, et ce n'est pas un detail : quand le telephone
 * est associe a l'AP des lunettes, l'AP ne donne PAS acces au reseau local, et le
 * serveur IA est sur le reseau local. La traduction ne peut donc PAS tourner pen-
 * dant l'association. D'ou l'ordre impose : sortir de l'AP (release, deja en
 * finally dans captureLatestPhoto), PUIS traduire. C'est aussi pour cela que la
 * pleine resolution s'obtient par l'AP : la vignette BLE (720x300, ~5,7 ko) ne
 * rend pas du texte, voir le commentaire de WifiImport.captureSequence.
 *
 * Ce qui n'existe pas chez le constructeur, et n'existe donc nulle part a recopier :
 * la synthese vocale. "Landroid/speech/tts/TextToSpeech" dans tout le bytecode de
 * l'app Eyevue (smali + smali_classes2..6) : ZERO occurrence. L'app du construc-
 * teur ecoute l'A2DP (CONNECTION_STATE_CHANGED / PLAYING_STATE_CHANGED, les
 * libelles "A2DP device connected", "A2DP audio playing") et lit du son via
 * MediaPlayer, mais ne parle jamais. Le role "traduire + dire" jouait, dans la
 * version patchee, par com.watchfun.transcommon.net.PhotoOpenAiInterceptor
 * (smali_classes6, sur la base h30.j = base REST du constructeur) : c'etait de
 * l'interception de requetes. Ici il n'y a rien a intercepter, la requete est
 * construite par nous.
 */
class Translate(
    private val ctx: Context,
    private val prefs: SharedPreferences,
    private val shared: BleCtlShared,
    private val wifiImport: WifiImport,
    private val tts: Tts,
    private val log: (String) -> Unit
) {

    companion object {
        /** Langue par defaut : celle du telephone, pas "fr" code en dur. */
        fun defaultLangTag(prefs: SharedPreferences, device: Locale): String {
            prefs.getString(Vision.PREF_LANG, "")?.takeIf { it.isNotEmpty() }?.let { return it }
            return device.language
        }
    }

    private val vision = Vision(ctx, prefs)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var busy = false
    /** Compteur 0x42 lu par la derniere demande (voir le commentaire de classe). */
    @Volatile private var count42 = -1
    @Volatile private var frame42: CountDownLatch? = null
    /** État BLE suivi par ÉVÉNEMENTS, utile pour les transitions seulement. Le
     *  point de départ d'une chaîne ne vient JAMAIS de là : voir linkState() de
     *  BleCtl, qui interroge le système. Un listener ajouté après la connexion ne
     *  reçoit jamais CONNECTED (les événements ne partent qu'au changement), ce
     *  qui rendait le bouton de traduction muet. */
    @Volatile private var bleEventConnected = false

    /** A brancher sur BleCtlShared.listeners au onCreate, retire au onDestroy. */
    val bleListener = object : BleCtlShared.Listener {
        override fun onState(state: BleCtl.State, detail: String) {
            bleEventConnected = (state == BleCtl.State.CONNECTED)
        }
        override fun onFrame(rx: Frame.Rx) {
            // 0x25 APP_RECEIVE_WIFI_INFO : le SSID reel de l'AP, en ASCII. Decodé
            // ici comme dans AlbumActivity (trim('\u0000') : le payload est bourré de
            // zeros, et un String qui les garde ne correspond a rien en comparaison
            // d'un SSID). Sert a l'association directe, sans scan systeme.
            if (rx.cmd == Cmd.APP_RECEIVE_WIFI_INFO) {
                val s = String(rx.payload, Charsets.ISO_8859_1).trim('\u0000').trim()
                if (s.isNotEmpty()) {
                    wifiImport.bleSsid = s
                    log("SSID de l'AP recu par 0x25 : « $s »")
                }
            }
            if (rx.cmd == Cmd.APP_RECEIVE_THUMBNAIL_COUNT) {
                val n = if (rx.payload.size >= 2)
                    ((rx.payload[0].toInt() and 0xFF) shl 8) or (rx.payload[1].toInt() and 0xFF)
                else rx.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                count42 = n
                frame42?.countDown()
            }
        }
    }

    /** Le moteur de lecture est-il pret ? (l'ecran leaffiche, ce n'est pas devine) */
    val ttsReady: Boolean get() = tts.isReady

    /** Ou partira la voix, annonce avant toute lecture. */
    fun whereVoiceGoes(): String = tts.outputDescription()

    fun openTtsSettings() = tts.openTtsSettings(logListener())
    fun openVoiceInstall() = tts.openInstallDataScreen(logListener())

    private fun logListener() = object : Tts.Listener {
        override fun onLog(s: String) = log(s)
        override fun onReady(available: Boolean, why: String) {
            log(if (available) "synthese vocale prete" else "synthese vocale indisponible: $why")
        }
        override fun onSpeaking(speaking: Boolean) {}
    }

    /** Arret de la lecture en cours. */
    fun stopSpeaking() = tts.stop(logListener())

    /**
     * Le bouton. Prend la photo, la traduit, la lit. Sur fil secondaire : la
     * chaine complete prend 30 a 90 s (association AP ~20 s, modele vision local
     * several s par page).
     */
    fun captureTranslateSpeak(langTag: String) {
        // La lecture est reglable (prefs) : lire a voix haute n'est pas toujours
        // voulu, et sur ce telephone le TTS n'est pas installe, il faut donc
        // pouvoir lire a l'ecran sans que la chaine echoue.
        val speak = prefs.getBoolean(Vision.PREF_AUTO, true)
        if (busy) { log("traduction deja en cours ; le bouton est rendu a la fin"); return }
        busy = true
        Thread {
            val r = try { runChain(langTag, speak) } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            busy = false
            if (r.isNotEmpty()) main.post { log(r) }
        }.start()
    }

    /** Retourne un message a afficher (vide = tout s'est bien passe). */
    private fun runChain(langTag: String, speak: Boolean): String {
        val lang = Tts.langByTag(langTag)
            ?: return "langue « $langTag » absente de la liste (Reglages > Langue)"

        // L'état est interrogé AU CLIC, auprès du système. Il ne vient pas du
        // drapeau d'événements : ce drapeau ne peut pas connaître un état qui
        // existe depuis avant la création de ce listener.
        val (linked, how) = shared.ble.linkState()
        log("liaison BLE au déclenchement : " +
            (if (linked) "établie" else "pas établie") + " — source : $how ; " +
            "dernière trame d'état reçue par cette instance : " +
            (if (bleEventConnected) "CONNECTED" else "aucune"))
        if (!linked) return "lunettes déconnectées : rien à déclencher"

        // Liaison occupee par une AUTRE app : c'est la cause mesuree des echecs du 11-14
        // 22:46 (deux clients GATT sur C2:01:06:00:3A:A1 -> rejets 201 -> 0x39 perdu ->
        // AP eteinte -> 20 cycles de scan a vide). Elle est detectable : le systeme rend
        // l'appareil CONNECTE pour n'importe quel client, pas pour le notre.
        shared.ble.foreignLinkHint()?.let { log("AVERTISSEMENT : $it") }

        // L'horloge des lunettes estcalee sur celle du telephone AVANT de noter
        // l'heure du declenchement. Le filtre du listing (epochSec >= heure du
        // telephone) ne vaut que si les deux horloges sont comparables, et 0x59
        // (TIME) n'etait JAMAIS envoye en automate : compte mesure sur logcat = 0
        // trame 0x59, la fonction n'etait appelee que par le bouton « Sync horloge ».
        // Mesure du 11-13 : le declenchement n'a rien produit et le listing n'a rien
        // de posterieur au declenchement, sans qu'on puisse separer les deux causes.
        shared.eyevue.syncTime()
        log("horloge des lunettes calee sur celle du telephone (0x59) : le filtre par " +
            "date du listing peut jouer")

        // --- etape 2 : le compteur AVANT. C'est lui qui dit si la prise de vue a
        // produit un fichier. Sans ce point de depart, le "nouveau" fichier ne
        // peut pas etre distingue de ce qui etait deja sur l'AP.
        count42 = -1
        frame42 = CountDownLatch(1)
        shared.eyevue.pullThumbnailCount()
        val gotBefore = try { frame42!!.await(8, TimeUnit.SECONDS) } catch (e: Exception) { false }
        val before = count42
        log(if (gotBefore)
            "compteur AP avant prise de vue : $before fichier(s) (trame 0x42)"
        else
            "trame 0x42 non recue en 8 s : le nombre de fichiers avant la prise de vue " +
                "est inconnu, la photo retenue sera la plus recente du listing")

        // --- etape 1 : declencher en HAUTE QUALITE. Le defaut de takePhoto est
        // la vignette (0x30) ; la vignette est inutilisable pour du texte.
        val triggerSec = Calendar.getInstance().timeInMillis / 1000L
        val dropBefore = shared.ble.droppedFrames
        shared.eyevue.takePhoto(true)
        log("prise de vue declenchee (0x22 / 0x31, payload HauteQualite)")

        // La commande de declenchement est une commande de CONTROLE : si sa premiere
        // ecriture est rejetee, le backoff de BleCtl la re-emet en fin de file. On le
        // constate par le compteur de trames, et on relance 0x22 si necessaire, pour que
        // le declenchement ait bien ete recu par les lunettes (le bruit que l'utilisateur
        // entend en est la preuve physique, et le log le dit en chiffres).
        Thread.sleep(1_500)
        if (shared.ble.droppedFrames > dropBefore) {
            log((shared.ble.droppedFrames - dropBefore).toString() +
                " trame(s) jetee(s) pendant le declenchement : relance de 0x22 (0x31) pour " +
                "que la prise de vue soit garantie recue")
            shared.eyevue.takePhoto(true)
        }

        // --- etape 2 bis : attendre que le fichier existe. Bornee par un fait :
        // le compteur remonte, OU 0x44 (telechargement termine) parait. 0x44 n'est
        // PAS attendu ici parce que sur cette paire il signale la fin d'un
        // TELECHARGEMENT, ce qui vient apres qu'on ait telecharge : l'attendre avant
        // d'avoir telecharge ferait attendre 25 s pour rien. Les 15 s viennent de la
        // mesure de la prise de vue -> apparition dans le listing, pas d'un reglage.
        if (gotBefore) {
            // 0x42 n'est PAS une notification : c'est la REPONSE a une demande. Elle
            // n'arrive que quand on la demande. L'attente qui ne demandait rien ne
            // pouvait que tourner jusqu'a son timeout sur une valeur jamais
            // rafraichie : mesure du 11-13, le compteur est reste a 5 pendant toute
            // l'attente, et le seul 0x42 paru apres la prise de vue est arrive quand
            // la chaine, elle, etait sortie. Le pull est donc REEMIS a chaque tour.
            var waited = 0
            while (count42 <= before && waited < 40) {
                Thread.sleep(500); waited++
                shared.eyevue.pullThumbnailCount()
            }
            log(if (count42 > before)
                "le compteur est passe de $before a $count42 : la photo est sur l'AP"
            else
                "compteur toujours a $count42 apres " + (waited / 2) + " s, avec " + waited +
                    " relances de 0x40 : la prise de vue n'a rien produit sur l'AP. " +
                    "Causes separables : le modele accepte-t-il la prise de vue a " +
                    "distance (capacites 0x95), et 0x42 compte-t-il les photos en " +
                    "haute qualite ou seulement les vignettes ?")
        } else {
            Thread.sleep(15_000)
        }

        // Le release() de captureSequence rend l'acces au reseau local AVANT le
        // retour : sans lui l'etape 4 echouerait avec une erreur qui ne dit pas
        // la vraie cause. L'appel est SYNCHRONE (il bloquerait l'ecran sur le
        // thread UI ; ici nous sommes deja sur un fil secondaire, ce qui est le
        // cas voulu).
        // 0x39 OPEN_WIFI payload [01] : c'est LUI qui allume l'AP des lunettes, et
        // l'AP est ce qui rend la pleine resolution. Il etait JAMAIS envoye par cette
        // chaine : compte mesure sur logcat = 0 trame 0x39 ; openWifiAp() n'etait
        // appele que par AlbumActivity (ligne 228). Le libelle « ouverture de l'AP
        // des lunettes… » que rendait captureSequence etait une etiquette d'interface,
        // pas une commande : le telephone partait scanner un reseau qui n'emettait
        // pas. Mesure du 11-13 : « scan startScan=false, 0 resultats, SSID vus: » et
        // le scan SYSTEME lui-meme (cmd wifi start-scan, hors de l'app) ne rend
        // aucun reseau DH-* : 8 reseaux vus, aucun des lunettes. L'AP etait eteinte.
        // Meme sequence que l'Album : 0x39 d'abord, la suite Wi-Fi ensuite.
        val dropBeforeAp = shared.ble.droppedFrames
        shared.eyevue.openWifiAp()
        log("ouverture de l'AP demandee aux lunettes (0x39 payload [01], le seul " +
            "payload observe sur cette paire)")
        // 0x39 est LA commande qui allume l'AP : si sa premiere ecriture est rejetee, le
        // backoff la re-emet, mais la re-emission part en fin de file, c'est-a-dire apres
        // les commandes suivantes. L'AP etant la seule source de la pleine resolution, on
        // la redemande ici, immediatement, des que le compteur de trames signale une perte
        // (mesure du 11-14 22:47 : 0x39 perdu -> « scan startScan=false, 0 resultats »).
        Thread.sleep(1_500)
        if (shared.ble.droppedFrames > dropBeforeAp) {
            log((shared.ble.droppedFrames - dropBeforeAp).toString() +
                " trame(s) jetee(s) pendant l'ouverture AP : relanceImmediate de 0x39")
            shared.eyevue.openWifiAp()
        }

        val latch = CountDownLatch(1)
        var snap: WifiImport.Snap? = null
        wifiImport.captureLatestPhoto(triggerSec) { r -> snap = r; latch.countDown() }
        // 90 s : la fenetre systeme d'association que l'utilisateur doit toucher met
        // ~16 s a paraitre (mesure : requestNetwork 01:38:20.853, panneau 01:38:36.457),
        // plus le scan d'ouverture d'AP, plus le telechargement. Un delai plus court
        // annulerait des chained legitimes : la fin de la fenetre d'association doit
        // rester comprise dans cette attente.
        latch.await(90, TimeUnit.SECONDS)
        val s: WifiImport.Snap? = snap
        if (s == null) return "attente de la photo interrompue : la fenetre " +
            "d'association Wi-Fi a-t-elle ete acceptee ?"
        if (s is WifiImport.Snap.Err) return s.why

        val ok = s as WifiImport.Snap.Ok
        main.post { log("photo ${ok.f.name} : ${ok.bytes.size} octets, traduction…") }

        // --- etape 4 : traduction. Le nom du modele n'est JAMAIS suppose (il
        // change selon ce qui est charge sur le serveur) : Vision le lit via
        // /v1/models, et l'utilisateur le choisit dans les Reglages.
        val res = vision.translateImage(ok.bytes, langTag, prompt(), log)
        if (!res.ok) return "traduction impossible : " + res.error
        // Le contrat EMPTY : un modele qui n'a rien vu ne doit PAS etre lu.
        if (res.empty) return "le modele n'a pas su lire de texte sur cette image " +
            "(reponse « ${res.text?.take(60)} ») : rapprocher, eclairer, tenir la page " +
            "a plat ; la focale des lunettes est fixe"

        val text = res.text!!
        main.post { log("traduit (« ${lang.first} ») : ${text.take(160)}") }

        // --- etape 5 : lecture. Sans le routage A2DP de Tts, la voix sort du
        // telephone ; Tts l'annonce lui-meme avant d'emettre.
        if (!speak) return "traduit (lecture coupe) : ${text.take(160)}"
        val err = tts.speak(text, lang.second, logListener())
        return err ?: "lecture lancee"
    }

    private fun prompt(): String {
        val p = prefs.getString(Vision.PREF_PROMPT, "") ?: ""
        return if (p.isBlank()) Vision.DEFAULT_PROMPT else p
    }

    /** Sonde du serveur, affichee a l'ecran : "Connection refused" = serveur eteint. */
    fun probeServer() = vision.probe { main.post { log(it) } }

    fun listModels(cb: (List<String>) -> Unit) = vision.listModels { main.post { cb(it) } }
}