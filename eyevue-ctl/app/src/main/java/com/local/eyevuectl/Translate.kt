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
    /** Etat BLE, suivi ici et non lu ailleurs : BleCtl n'expose PAS d'isConnected(). */
    @Volatile private var bleConnected = false

    /** A brancher sur BleCtlShared.listeners au onCreate, retire au onDestroy. */
    val bleListener = object : BleCtlShared.Listener {
        override fun onState(state: BleCtl.State, detail: String) {
            bleConnected = (state == BleCtl.State.CONNECTED)
        }
        override fun onFrame(rx: Frame.Rx) {
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

        if (!bleConnected) return "lunettes deconnectees : rien a declencher"

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
        shared.eyevue.takePhoto(true)
        log("prise de vue declenchee (0x22 / 0x31, payload HauteQualite)")

        // --- etape 2 bis : attendre que le fichier existe. Bornee par un fait :
        // le compteur remonte, OU 0x44 (telechargement termine) parait. 0x44 n'est
        // PAS attendu ici parce que sur cette paire il signale la fin d'un
        // TELECHARGEMENT, ce qui vient apres qu'on ait telecharge : l'attendre avant
        // d'avoir telecharge ferait attendre 25 s pour rien. Les 15 s viennent de la
        // mesure de la prise de vue -> apparition dans le listing, pas d'un reglage.
        if (gotBefore) {
            var waited = 0
            while (count42 <= before && waited < 30) {
                Thread.sleep(500); waited++
            }
            log(if (count42 > before)
                "le compteur est passe de $before a $count42 : la photo est sur l'AP"
            else
                "compteur toujours a $count42 apres 15 s : la prise de vue n'a peut-etre " +
                    "rien produit (le modele accepte-t-il la prise de vue a distance, " +
                    "capacites 0x95 ?)")
        } else {
            Thread.sleep(15_000)
        }

        // Le release() de captureSequence rend l'acces au reseau local AVANT le
        // retour : sans lui l'etape 4 echouerait avec une erreur qui ne dit pas
        // la vraie cause. L'appel est SYNCHRONE (il bloquerait l'ecran sur le
        // thread UI ; ici nous sommes deja sur un fil secondaire, ce qui est le
        // cas voulu).
        val latch = CountDownLatch(1)
        var snap: WifiImport.Snap? = null
        wifiImport.captureLatestPhoto(triggerSec) { r -> snap = r; latch.countDown() }
        // 90 s : 15 s de fenetre systeme d'association vue a l'ecran, plus le
        // telechargement. Un delai plus court annulerait des chained legitimes.
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