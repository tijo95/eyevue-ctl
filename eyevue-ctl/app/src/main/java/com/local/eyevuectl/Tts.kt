package com.local.eyevuectl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Synthese vocale locale (on-device), routee vers les lunettes.
 *
 * Pourquoi c'est nous qui le faisons : la recherche
 * "Landroid/speech/tts/TextToSpeech" dans tout le bytecode de l'app Eyevue
 * (work/apktool_out, smali + smali_classes2..6) renvoie ZERO occurrence. L'app
 * d'origine ne fait JAMAIS de synthese vocale. En revanche elle ecoute l'A2DP
 * (BaseAsrTransActivity, BaseBluetoothFragment) :
 *   "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
 *   "android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED"
 * avec les libelles "A2DP device connected" / "A2DP audio playing". Le canal
 * audio existe donc (elle s'en sert via MediaPlayer / MusicPlayerManager),
 * mais le TTS n'existe pas chez elle.
 *
 * Le canal, mesure sur le telephone (dumpsys bluetooth_manager) :
 *   C2:01:06:00:3A:A1 [ DUAL ] E09-3AA1
 *   Profile: A2dpService -> mActiveDevice: C2:01:06:00:3A:A1
 *   STACK_EVENT EVENT_TYPE_AUDIO_STATE_CHANGED value1:STARTED / STOPPED
 * Les lunettes sont vues comme un casque A2DP (SBC 44,1 kHz stereo retenu ;
 * LHDC / LDAC / aptX proposes par le telephone).
 *
 * >>> Le point qui a fait echouer la premiere version, raison d'etre de ce
 * >>> commentaire : SANS le bloc setStreamVolume ci-dessous, la voix sort du
 * >>> TELEPHONE, pas des lunettes. TextToSpeech prend le haut-parleur bas par
 * >>> defaut et l'A2DP est coupe pendant l'emission. Un build "traduit puis lu"
 * >>> qui echoue de cette donne l'impression que le TTS est casse : il ne l'est
 * >>> pas, il sort au mauvais endroit. La correction est de monter STREAM_MUSIC
 * >>> au maximum : le systeme bascule alors sur le device A2DP connecte. Elle
 * >>> est appliquee a CHAQUE emission, pas une fois a l'init, parce que le
 * >>> systeme redescend ce volume des qu'un autre flux audio passe.
 *
 * Effet de bord assume : cela agit sur le volume SYSTEME du telephone (pas sur
 * le volume des lunettes, regle par 0x70 / 0x32). Si un casque classique est
 * connecte, la voix part dedans. C'est pourquoi outputDescription() est affiche
 * avant chaque lecture : ce choix n'est jamais implicite.
 */
class Tts(private val ctx: Context) {

    interface Listener {
        fun onLog(s: String)
        /** appele quand la synthese est prete, ou quand elle est indisponible. */
        fun onReady(available: Boolean, why: String)
        /** pour griser le bouton pendant la lecture. */
        fun onSpeaking(speaking: Boolean)
    }

    companion object {
        private const val TAG = "Tts"

        /**
         * Codes de isLanguageAvailable(), RELEVES dans l'API Android, et non
         * inventes. Le compilateur a refuse "TextToSpeech.NOT_AVAILABLE" : cette
         * constante n'existe pas. Les valeurs du framework sont LANG_AVAILABLE=0,
         * LANG_COUNTRY_AVAILABLE=1, LANG_COUNTRY_VAR_AVAILABLE=2,
         * LANG_MISSING_DATA=-2, LANG_NOT_SUPPORTED=-3, NOT_READY=-4.
         *
         * Le test "LANG_MISSING_DATA + LANG_NOT_SUPPORTED" que j'avais ecrit est
         * une SOMME (-5) : valeur que l'API ne renvoie jamais, donc branche morte,
         * supprimee. Un test sur une valeur impossible ne protege de rien.
         */
        const val LANG_AVAILABLE: Int = TextToSpeech.LANG_AVAILABLE
        const val LANG_COUNTRY_AVAILABLE: Int = TextToSpeech.LANG_COUNTRY_AVAILABLE
        const val LANG_COUNTRY_VAR_AVAILABLE: Int = TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        const val LANG_MISSING_DATA: Int = TextToSpeech.LANG_MISSING_DATA
        const val LANG_NOT_SUPPORTED: Int = TextToSpeech.LANG_NOT_SUPPORTED
        /**
         * NOT_READY n'a PAS ete retenu : "javap" sur android-34/android.jar montre
         * que TextToSpeech ne definit PAS cette constante (elle est dans
         * TextToSpeech.Database). L'erreur etait la mienne, pas celle du
         * compilateur, et elle est mesuree, pas supposee.
         * ERROR (-1) est utilise comme code d'echec par defaut ; le nom est celui
         * du framework, sa valeur vient du framework.
         */
        const val TTS_ERROR: Int = TextToSpeech.ERROR

        /**
         * Langues proposees. La 1ere string est ce qui est AFFICHE ; la Locale
         * est celle passee au moteur TTS ; le tag BCP-47 est celui envoye au
         * modele pour la traduction. Les deux sont distinctes : un modele traduit
         * dans presque n'importe quelle langue, un moteur TTS n'a que les voix
         * installees sur la machine.
         */
        val LANGS: List<Triple<String, Locale, String>> = listOf(
            Triple("Francais", Locale.FRENCH, "fr"),
            Triple("Anglais", Locale.ENGLISH, "en"),
            Triple("Anglais (US)", Locale.US, "en-US"),
            Triple("Allemand", Locale.GERMAN, "de"),
            Triple("Espagnol", Locale("es"), "es"),
            Triple("Italien", Locale.ITALIAN, "it"),
            Triple("Portugais (bresilien)", Locale("pt", "BR"), "pt-BR"),
            Triple("Neerlandais", Locale("nl"), "nl"),
            Triple("Chinois (simplifie)", Locale.CHINA, "zh-CN"),
            Triple("Japonais", Locale.JAPANESE, "ja"),
            Triple("Coreen", Locale.KOREAN, "ko"),
            Triple("Russe", Locale("ru"), "ru"),
            Triple("Arabe", Locale("ar"), "ar"),
            Triple("Turc", Locale("tr"), "tr"),
            Triple("Polonais", Locale("pl"), "pl")
        )

        fun langByTag(tag: String): Triple<String, Locale, String>? {
            LANGS.firstOrNull { it.third.equals(tag, ignoreCase = true) }?.let { return it }
            return LANGS.firstOrNull {
                it.third.substringBefore('-').equals(tag.substringBefore('-'), ignoreCase = true)
            }
        }
    }

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var lastInitError: String = ""
    @Volatile private var cancelled = false
    private val speaking = AtomicBoolean(false)
    private var adapter: BluetoothAdapter? = null
    private var a2dp: BluetoothProfile? = null

    // speak() est asynchrone. Decouper sans attendre ferait partir le QUEUE_ADD
    // d'un segment apres le QUEUE_FLUSH du suivant : les phrases se melangent. On
    // attend la fin de chaque segment avant d'emettre le suivant.
    private var segDone = CountDownLatch(1)

    fun init(l: Listener) {
        // Le proxy A2DP sert a DEUX choses : dire ou la voix va partir, et le
        // dire avant d'emettre (sinon la voix part dans le telephone sans que
        // personne ne le sache).
        adapter = try {
            @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
        } catch (e: Exception) { null }
        try {
            adapter?.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dp = proxy
                        l.onLog("proxy A2DP pret")
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) a2dp = null
                }
            }, BluetoothProfile.A2DP)
        } catch (e: Exception) {
            l.onLog("getProfileProxy(A2DP) refuse: $e")
        }

        // tts_default_synth sert de DIAMOST, pas d'ID de moteur a forcer : une
        // valeur absente annonce qu'aucun moteur n'est choisi, et l'init echouera.
        // Mesure sur ce telephone : cette valeur etait null (moteur installe
        // -- com.google.android.tts -- mais jamais selectionne). Dans ce cas on
        // ne code pas un contournement : on le dit et on ouvre l'ecran TTS.
        val def = try { Settings.Secure.getString(ctx.contentResolver, "tts_default_synth") }
            catch (e: Exception) { null }
        lastInitError = if (def.isNullOrBlank())
            "aucun moteur TTS par defaut (tts_default_synth = null)" else ""
        Log.i(TAG, "tts_default_synth = $def")

        val initListener = TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) {
                ready = false
                lastInitError = "onInit status=$status (SUCCESS=0)" +
                    (if (def.isNullOrBlank()) " ; aucun moteur par defaut" else "")
                l.onLog("TTS indisponible: $lastInitError")
                l.onReady(false, lastInitError)
                return@OnInitListener
            }
            val t = tts ?: run {
                ready = false; l.onReady(false, "objet TTS absent dans onInit"); return@OnInitListener
            }
            try {
                // PAS "t.language = ..." : setLanguage() renvoie Int, donc Kotlin
                // ne synthetise pas la propriete ; seule la forme methodes compile.
                t.setLanguage(Locale.FRENCH)
                t.setSpeechRate(1.0f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        speaking.set(true); l.onSpeaking(true)
                    }
                    override fun onDone(id: String?) {
                        speaking.set(false); l.onSpeaking(false); segDone.countDown()
                    }
                    @Deprecated("garde la signature de la classe de base")
                    override fun onError(id: String?) {
                        speaking.set(false); l.onSpeaking(false); segDone.countDown()
                    }
                })
                ready = true
                l.onLog("TTS pret (moteur: " + (def ?: "inconnu") + ")")
                l.onReady(true, "")
            } catch (e: Exception) {
                ready = false; lastInitError = e.message ?: e.javaClass.simpleName
                l.onLog("TTS indisponible: $lastInitError")
                l.onReady(false, lastInitError)
            }
        }
        tts = try {
            // Constructeur SANS engine explicite : c'est le moteur par defaut qui
            // est choisi. Forcer un engine par son nom est la faute classique
            // (service absent -> onInit renvoie ENGINE_MISSING_DATA).
            TextToSpeech(ctx, initListener)
        } catch (e: Exception) {
            l.onLog("TextToSpeech() refuse: $e"); null
        }
        if (tts == null) {
            ready = false
            l.onLog("constructeur TextToSpeech refuse")
            l.onReady(false, "constructeur TextToSpeech refuse")
        }
    }

    val isReady: Boolean get() = ready

    /**
     * Cause de l'echec d'init, affichee dans le dialogue. Indispensable : un
     * echec de TextToSpeech est MUET (onInit renvoie un code, aucune exception,
     * le systeme n'affiche rien). Sans cet accesseur l'utilisateur voit un
     * bouton qui ne dit rien et ne peut pas savoir que le moteur n'est pas
     * selectionne.
     */
    val initError: String get() = lastInitError

    /** Code d'etat brut, affiche a l'ecran : c'est lui qui dit pourquoi rien ne sort. */
    fun voiceStatus(loc: Locale): String = try {
        when (tts?.isLanguageAvailable(loc)) {
            LANG_AVAILABLE -> "voix disponible"
            LANG_COUNTRY_AVAILABLE -> "voix disponible (pays)"
            LANG_COUNTRY_VAR_AVAILABLE -> "voix disponible (variante)"
            LANG_MISSING_DATA ->
                "LANG_MISSING_DATA (-2) : langue connue, voix NON installee -> Reglages TTS > Telecharger les donnees voix"
            LANG_NOT_SUPPORTED ->
                "LANG_NOT_SUPPORTED (-3) : le moteur ne connait pas cette langue"
            TTS_ERROR -> "ERROR (-1) : le moteur n'a pas repondu a isLanguageAvailable"
            null -> "moteur non init (tts = null)"
            else -> "code inattendu " + tts?.isLanguageAvailable(loc)
        }
    } catch (e: Exception) { "erreur: $e" }

    /** Le moteur a-til la VOIX de cette langue (pas seulement la langue) ? */
    fun voiceAvailable(loc: Locale): Boolean {
        val t = tts ?: return false
        return try {
            val v = t.isLanguageAvailable(loc)
            v == LANG_AVAILABLE ||
                v == LANG_COUNTRY_AVAILABLE ||
                v == LANG_COUNTRY_VAR_AVAILABLE
        } catch (e: Exception) { false }
    }

    /**
     * Ou partira la voix. Annoncer SYSTEMATIQUEMENT avant de lire : ce telephone
     * a 12 appareils lies (JBL Clip 2, SE-MJ553BT, TUF, A8 pro...) et le device
     * A2DP actif est UNIQUE. La voix part dans ce device-la, pas "dans les
     * lunettes" par construction.
     */
    fun outputDescription(): String {
        val p = a2dp
        if (p == null) return "A2DP: proxy non pret -> la voix partira du telephone"
        val dev = try {
            p.getConnectedDevices().firstOrNull()
        } catch (e: SecurityException) {
            return "A2DP: permission BLUETOOTH_CONNECT refusee"
        } catch (e: Exception) {
            return "A2DP: lecture des devices refusee ($e)"
        }
        if (dev == null) return "aucun device A2DP connecte : la voix sortira du telephone"
        val name = try { dev.name } catch (e: Exception) { null }
        val isGlass = name != null && name.contains("E09", ignoreCase = true)
        return if (isGlass) "A2DP connecte: " + name + " -> la voix sort des lunettes"
        else "A2DP connecte: " + (name ?: dev.address) +
            " -> la voix sort de cet appareil, PAS forcement des lunettes"
    }

    /**
     * Lecture. Retourne null si la lecture a ete lancee, un message d'echec sinon.
     *
     * Decoupage : speak() est plafonne (~1000 caracteres, la limite exacte depend
     * du moteur ; la depasser fait echouer l'appel en silence sur certains
     * moteurs). Une traduction de page fait facilement 2000 caracteres. Coupe sur
     * les frontieres de phrase, puis de virgule, puis de mot : couper au milieu
     * d'un mot degrade la prononciation.
     *
     * QUEUE_FLUSH sur le 1er segment (vide la file : un appui precedent est
     * annule), QUEUE_ADD sur les suivants.
     */
    fun speak(text: String, loc: Locale, l: Listener): String? {
        val t = tts ?: return "moteur TTS non init"
        if (!ready) return "moteur TTS non pret: $lastInitError"
        if (text.isBlank()) return null
        val va = try { t.isLanguageAvailable(loc) } catch (e: Exception) { TTS_ERROR }
        if (va == LANG_MISSING_DATA || va == LANG_NOT_SUPPORTED || va == TTS_ERROR)
            return "voix absente pour " + loc.displayName + " -> " + voiceStatus(loc)

        val segs = split(text, 900)
        l.onLog("lecture: ${segs.size} segment(s), ${text.length} caracteres")

        // --- ROUTAGE VERS LES LUNETTES (see commentaire de classe). Sans ces
        // lignes la voix sort du telephone ; c'est ce que faisait le 1er build.
        val am = try { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
            catch (e: Exception) { null }
        if (am != null) {
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
                l.onLog("volume MEDIA monte a " +
                    am.getStreamVolume(AudioManager.STREAM_MUSIC) + "/" +
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) +
                    " (force la selection du device A2DP)")
            } catch (e: Exception) {
                l.onLog("setStreamVolume refuse: $e")
            }
        }
        Log.i(TAG, outputDescription())
        l.onLog(outputDescription())

        for ((i, seg) in segs.withIndex()) {
            if (cancelled) { l.onLog("lecture annulee au segment ${i + 1}"); break }
            segDone = CountDownLatch(1)
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val r = try {
                if (Build.VERSION.SDK_INT >= 21) t.speak(seg, mode, Bundle(), "seg$i")
                else @Suppress("DEPRECATION") t.speak(seg, mode, null, "seg$i")
            } catch (e: Exception) { l.onLog("speak() exception: $e"); TextToSpeech.ERROR }
            if (r != TextToSpeech.SUCCESS) {
                l.onLog("segment ${i + 1}: speak() a renvoye ERROR (code $r)")
                if (i == 0) return "lecture echouee des le 1er segment (code $r)"
                break
            }
            speaking.set(true); l.onSpeaking(true)
            // 45 s : un segment de 900 caracteres se lit en ~10-20 s ; une voix
            // absente ou un moteur bloque ne doit pas geler l'ecran.
            try { segDone.await(45, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        }
        speaking.set(false); l.onSpeaking(false)
        return null
    }

    /** Arret : vide la file et rend le bouton. */
    fun stop(l: Listener) {
        cancelled = true
        try { tts?.stop() } catch (_: Exception) {}
        segDone.countDown()
        speaking.set(false); l.onSpeaking(false)
        cancelled = false
    }

    /**
     * Ecran d'installation des donnees vocales. Cette constante n'existe PAS dans
     * le SDK (API publique retiree en Android 4) : le litteral est recopie tel
     * quel. C'est la sortie normale quand voiceStatus() dit LANG_MISSING_DATA.
     */
    fun openInstallDataScreen(l: Listener): Boolean = try {
        ctx.startActivity(Intent("com.android.tts.engine.INSTALL_TTS_DATA")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        l.onLog("ecran d'installation des voix ouvert")
        true
    } catch (e: Exception) {
        l.onLog("Intent INSTALL_TTS_DATA refuse: $e")
        openTtsSettings(l)
        false
    }

    /** Ecran des reglages TTS (moteur par defaut, vitesse, ton, telechargement). */
    fun openTtsSettings(l: Listener): Boolean = try {
        ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        l.onLog("reglages TTS ouverts")
        true
    } catch (e: Exception) {
        l.onLog("reglages TTS inaccessibles: $e")
        Toast.makeText(ctx,
            "Ouvrir Systeme > Langue et saisie > Sortie de la synthese vocale", Toast.LENGTH_LONG).show()
        false
    }

    /** Decoupage a max caracteres, en preferant phrase > virgule > mot. */
    private fun split(s: String, max: Int): List<String> {
        val out = ArrayList<String>()
        var rest = s.trim()
        while (rest.length > max) {
            val w = rest.substring(0, max)
            val cut = maxOf(w.lastIndexOf('.'), w.lastIndexOf('!'), w.lastIndexOf('?'),
                w.lastIndexOf(';'), w.lastIndexOf(','), w.lastIndexOf(' '))
            val at = if (cut > max / 3) cut + 1 else max
            val piece = rest.substring(0, at).trim()
            if (piece.isNotEmpty()) out.add(piece)
            rest = rest.substring(at).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    fun shutdown() {
        try { a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) } } catch (_: Exception) {}
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null; ready = false
    }
}
