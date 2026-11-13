package com.local.eyevuectl

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Client OpenAI-compatible pour un serveur de vision LOCAL (llama.cpp par
 * exemple). Zéro dépendance tierce : HttpURLConnection + org.json, comme le
 * reste de l'app (app/build.gradle ne declare AUCUNE implementation).
 *
 * Ce que remplace cette classe : dans la version patchée de l'app du
 * constructeur, le role equivalent etait joue par
 *   com.watchfun.transcommon.net.PhotoOpenAiInterceptor (smali_classes6)
 * qui réécrivait les requêtes vers l'hote de notre choix (les URLs étant dans
 * la classe h30 : j = base REST, h = base wss, b/d/e = hosts Eyevue à NE PAS
 * rediriger, sous peine de basculer le DomainManager en HTTPS et de casser le
 * SSL). Ici il n'y a rien à intercepter : la requête est construite par nous.
 *
 * Deux regles reprises du patch d'origine, parce qu'elles ont coûté cher :
 *
 * 1. enable_thinking:false. Sur un modele qui "reflechit" avant de répondre,
 *    laisser le déflaut fait exploser la latence. Mesure sur le serveur :
 *    ~333 tokens de prompt, ~31 tokens générés en 0,5-0,7 s AVEC cette option.
 *    Elle est envoyée à chaque requete. llama.cpp l'accepte au niveau racine du
 *    body ; les serveurs qui ne la connaissent pas l'ignorent.
 *
 * 2. Lire UNIQUEMENT message.content, et tester s'il est VIDE. Dans le patch,
 *    la garde garde `if-eqz` sur isEmpty() du content a été MAINTENUE à la main
 *    (le remplacer par reasoning_content etait la faute : le modele met sa
 *    reflexion dans reasoning_content, et afficher ce champ fait sortir un
 *    monologue au lieu de la traduction). Un content vide n'est pas un succès.
 *
 * URL du serveur : JAMAIS codée en dur. Ce dépôt est public ; une adresse IP
 * locale, un nom de machine ou un numéro de série ne doivent pas s'y trouver.
 * Elle est saisie par l'utilisateur (Reglages > Serveur IA) et vit dans les
 * prefs. Le README documente un placeholder.
 *
 * Sécurité, a lire : un llama.cpp sur :8080 sans clé repond a n'importe qui sur
 * le même Wi-Fi (mesure : /health repond {"api_key":false}). Le champ api_key
 * des prefs envoie un en-tete Authorization: Bearer si le serveur en exige un.
 */
class Vision(private val ctx: Context, private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "Vision"
        const val PREF_URL = "ai_url"
        const val PREF_KEY = "ai_key"
        const val PREF_MODEL = "ai_model"
        const val PREF_LANG = "ai_lang"
        const val PREF_PROMPT = "ai_prompt"
        const val PREF_AUTO = "ai_speak"

        /**
         * Consigne envoyée au modele. Elle est MODIFIABLE (Reglages) parce que
         * la qualité OCR dépend beaucoup du prompt ; la recopie de celle qui a
         * servi de référence est donnée ici.
         *
         * Le mot EMPTY est un CONTRAT, pas une convention : le code le teste
         * littéralement. Un modele qui n'a rien trouvé doit le dire avec ce mot,
         * sinon la voix lirait une hallucination. Les phrases du type "I cannot
         * see any text" sont la sortie la plus fréquente des modeles vision, et
         * sans sentinelle elles sont lues à voix haute.
         */
        const val DEFAULT_PROMPT =
            "Transcris le texte visible dans cette image, fidelement, sans le commenter. " +
                "S'il n'y a aucun texte lisible, reponds exactement EMPTY. " +
                "S'il y a du texte, reponds UNIQUEMENT par sa traduction en {LANG}, " +
                "sans guillemets, sans preambule, sans explication."

        /** Reconnait le signal "pas de texte", quelle que soit la formulation. */
        fun looksEmpty(s: String): Boolean {
            val t = s.trim().lowercase().replace("\"", "").replace(".", "").trim()
            if (t.isEmpty()) return true
            if (t == "empty") return true
            if (t.startsWith("empty")) return true
            val markers = listOf(
                "no text", "aucun texte", "pas de texte", "il n'y a aucun texte",
                "il n'y a pas de texte", "no visible text", "no readable text",
                "cannot see any text", "i can not see", "i cannot see", "i can't see",
                "there is no text", "aucun texte lisible", "n/a", "none"
            )
            return markers.any { t.contains(it) }
        }

        /**
         * Normalise l'URL saisie : le client attend un host:port, pas un chemin.
         * Les variantes collees (/v1, /v1/, http://, slashes finaux) sont la
         * cause n°1 des "la traduction ne marche pas".
         */
        fun normalizeBase(raw: String): String {
            var u = raw.trim()
            if (u.isEmpty()) return ""
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u
            u = u.trimEnd('/')
            if (u.endsWith("/v1")) u = u.substring(0, u.length - 3).trimEnd('/')
            return u
        }
    }

    class Res(val text: String?, val error: String?, val raw: String = "") {
        val ok: Boolean get() = error == null && text != null
        val empty: Boolean get() = text != null && looksEmpty(text)
    }

    private fun base(): String = normalizeBase(prefs.getString(PREF_URL, "") ?: "")

    /** sonde de configuration, pour le diagnostic affiche a l'ecran. */
    fun probe(l: (String) -> Unit) {
        val b = base()
        if (b.isEmpty()) { l("serveur IA non configure (Reglages > Serveur IA)"); return }
        l("sonde $b/health")
        try {
            val c = URL(b + "/health").openConnection() as HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 4000
            val code = c.responseCode
            val body = try {
                c.inputStream.bufferedReader().readText()
            } catch (e: Exception) { "(corps illisible)" }
            c.disconnect()
            l("health http $code : ${body.take(200)}")
        } catch (e: Exception) {
            l("health injoignable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Liste les modeles charges, pour le menu deroulant. llama.cpp repond
     * {"data":[{"id":"...","meta":{"n_ctx":...}}]} (mesure : {"id":"swift-1.5-iq3_s",
     * "ctx":131072}). Le nom du modele change selon ce qui est charge sur le
     * serveur, donc il n'est jamais suppose : on le lit, et l'utilisateur choisit.
     */
    fun listModels(l: (List<String>) -> Unit) {
        val b = base()
        if (b.isEmpty()) { l(emptyList()); return }
        Thread {
            val out = ArrayList<String>()
            try {
                val c = URL(b + "/v1/models").openConnection() as HttpURLConnection
                auth(c)
                c.connectTimeout = 5000; c.readTimeout = 8000
                if (c.responseCode == 200) {
                    val o = JSONObject(c.inputStream.bufferedReader().readText())
                    val arr = o.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val id = m.optString("id")
                        if (id.isNotEmpty()) out.add(id)
                    }
                } else l(emptyList()).let { c.disconnect(); return@Thread }
                c.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "listModels: $e")
            }
            l(out)
        }.start()
    }

    private fun auth(c: HttpURLConnection) {
        val k = prefs.getString(PREF_KEY, "") ?: ""
        if (k.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $k")
    }

    /**
     * Traduit une image. Appeler sur un fil secondaire (ce methodes est bloquant).
     *
     * @param bytes le JPEG PLEINE RESOLUTION (3200x2400, ~378 Ko mesures). La
     *   vignette BLE (720x300, 5,7 Ko) est inutilisable pour du texte : 53 fois
     *   moins de pixels. C'est ce choix qui impose le chemin Wi-Fi AP.
     */
    fun translateImage(bytes: ByteArray, langTag: String, promptTpl: String, l: (String) -> Unit): Res {
        val b = base()
        if (b.isEmpty()) return Res(null, "serveur IA non configure (Reglages > Serveur IA)")
        val model = prefs.getString(PREF_MODEL, "") ?: ""
        if (model.isEmpty()) return Res(null, "aucun modele choisi (Reglages > Serveur IA)")

        // L'image part en data URL base64 : 378 Ko de JPEG deviennent ~505 Ko de
        // base64. Cela passe sur le Wi-Fi de la maison, PAS sur l'AP des lunettes
        // (l'AP ne donne pas acces au serveur : voir Translate.kt, le pivot).
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        l("image ${bytes.size} octets -> ${b64.length} caracteres base64")

        val prompt = promptTpl.replace("{LANG}", langTag)
        val content = JSONArray()
        content.put(JSONObject().apply {
            put("type", "text"); put("text", prompt)
        })
        content.put(JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + b64))
        })
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user"); put("content", content)
            }))
            put("stream", false)
            put("max_tokens", 900)
            put("temperature", 0.2)
            // Voir commentaire de classe, regle 1.
            put("enable_thinking", false)
        }.toString()

        return try {
            val c = URL(b + "/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            auth(c)
            c.setRequestProperty("Content-Type", "application/json")
            c.connectTimeout = 8000
            // 120 s : un modele vision local, avec 505 Ko d'image en entree, met
            // plusieurs secondes quand il est chaud, et bien plus quand il doit
            // recharger. Un readTimeout de 15 s (le reglage de l'import photo)
            // ferait echouer des appels parfaitement valides.
            c.readTimeout = 120000
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val sb = StringBuilder()
            val rd = try { BufferedReader(InputStreamReader(stream, Charset.forName("UTF-8"))) }
                catch (e: Exception) { null }
            if (rd == null) return Res(null, "corps de reponse illisible", "")
            rd.use { r -> var line = r.readLine(); while (line != null) { sb.append(line); line = r.readLine() } }
            c.disconnect()
            val raw = sb.toString()
            if (code !in 200..299) {
                Log.w(TAG, "http $code: ${raw.take(400)}")
                return Res(null, "serveur http $code : ${raw.take(240)}", raw)
            }
            parse(raw).also { r ->
                if (r.ok) l("reponse ${r.text!!.length} caracteres")
                else l("reponse sans contenu exploitable")
            }
        } catch (e: Exception) {
            // Le detail de l'exception est LA sortie utile ici : "Connection
            // refused" = serveur eteint ; "ENETUNREACH"/"timed out" = on est
            // encore associe a l'AP des lunettes, donc sans acces au reseau local.
            val m = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "translateImage: $m")
            Res(null, "$m " + when {
                m.contains("ECONNREFUSED", true) || m.contains("Connection refused", true) ->
                    "(le serveur IA est eteint, ou n'ecoute pas cette adresse)"
                m.contains("timeout", true) || m.contains("timed out", true) ->
                    "(modele trop lent, ou rechargement en cours)"
                m.contains("ENETUNREACH", true) || m.contains("EHOSTUNREACH", true) ||
                    m.contains("Unable to resolve host", true) ->
                    """(pas d'acces reseau : on est sans doute encore associe a l'AP des " +
                        "lunettes, qui ne donne PAS acces au serveur)"""
                else -> ""
            })
        }
    }

    /**
     * Extraction du texte de la reponse. Regle 2 du commentaire de classe :
     * on lit message.content et on teste s'il est vide ; reasoning_content n'est
     * JAMAIS lu comme reponse.
     */
    private fun parse(raw: String): Res {
        val o = try { JSONObject(raw) } catch (e: Exception) {
            return Res(null, "reponse pas JSON : ${raw.take(200)}", raw)
        }
        o.optJSONArray("errors")?.let { arr ->
            if (arr.length() > 0) return Res(null, "erreur serveur: ${arr.optString(0)}", raw)
        }
        o.optString("error").takeIf { it.isNotEmpty() }?.let { return Res(null, "erreur serveur: $it", raw) }
        val choices = o.optJSONArray("choices")
        if (choices == null || choices.length() == 0) return Res(null, "pas de choices", raw)
        val msg = choices.optJSONObject(0)?.optJSONObject("message")
            ?: return Res(null, "pas de message dans choices[0]", raw)
        // content, et LUI seul. Voir regle 2.
        val content = msg.optString("content")
        if (content.isEmpty()) {
            val reason = msg.optString("reasoning_content")
            return Res(null, "content vide" +
                (if (reason.isNotEmpty()) " (le modele a repondu dans reasoning_content : " +
                    "desactive le raisonnement, option enable_thinking=false)" else ""), raw)
        }
        val finish = choices.optJSONObject(0)?.optString("finish_reason") ?: ""
        var text = content.trim()
        // Certains modeles entourent la reponse de guillemets ; la consigne dit
        // "sans guillemets", on les retire quand ils encadrent tout.
        if (text.length > 1 && text.startsWith("\"") && text.endsWith("\""))
            text = text.substring(1, text.length - 1).trim()
        if (finish == "length") Log.w(TAG, "reponse TRONQUEE (finish_reason=length) : " +
            "max_tokens=900 insuffisant pour cette page -> augmenter max_tokens")
        return Res(text, null, raw)
    }
}
