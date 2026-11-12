package com.local.eyevuectl

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Modele de l'Album (photos / videos / enregistrements stockes dans les lunettes).
 *
 * Sources : bytecode (FileListParser/FileBean, ModBleResponse.getSWifiPhotoUrl,
 * callbacks d'import) + logs systeme de la session d'import reel du 11/11 20:41.
 * Ce qui n'a pas ete observe sur notre paire est marque en commentaire.
 */
data class MediaFile(
    /** chemin sur l'AP, ex. "/EVENT/20261111204018731.jpg" (12 URLs lues dans le log) */
    val path: String,
    val name: String,
    val size: Long,
    val epochSec: Long?
) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    var checked: Boolean = false

    /** Cle canonique (voir MediaFile.keyOf) : fait le pont listing AP <-> carte
     *  <-> MediaStore. Renseigne par les fabriques, jamais suppose. */
    var key: String = keyOf(name)

    /** Classement par extension, mecanisme des callbacks d'import de l'app d'origine. */
    val tab: Int get() = when (ext) {
        "jpg", "jpeg", "png" -> TAB_PHOTO
        "aac", "wav", "mp3" -> TAB_REC     // .aac/.wav = constantes lues dans le bytecode
        "mp4", "3gp", "mov", "avi" -> TAB_VIDEO
        else -> TAB_ALL
    }

    /** Date lisible du groupe ("2026-11-11"), comme les separateurs de l'app d'origine. */
    fun dayLabel(): String {
        val ms = epochSec?.times(1000L) ?: epochFromName()
        return if (ms == null) "sans date" else DAY_FMT.format(ms)
    }

    /**
     * Les fichiers de l'AP sous /EVENT/ portent l'horodatage complet
     * "YYYYMMDDHHMMSSmmm" (URLs lues dans le log PhotoViewModel).
     */
    fun epochFromName(): Long? {
        val m = TS_IN_NAME.find(name) ?: return null
        return try {
            val ms = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT)
                .parse(m.groupValues[1])?.time ?: return null
            ms / 1000L
        } catch (_: Exception) { null }
    }

    /** Nom local dans la galerie, calque du log reel "DH-261111-204018731-...". */
    fun localName(): String {
        val ms = epochFromName()
        val stamp = if (ms != null) NAME_STAMP.format(ms * 1000L) else "import"
        return "DH-" + stamp + "-" + name.substringBeforeLast('.') + "." + ext
    }

    companion object {
        const val TAB_ALL = 0
        const val TAB_PHOTO = 1
        const val TAB_VIDEO = 2
        const val TAB_REC = 3

        val TAB_LABELS = arrayOf("Tout", "Photo", "Vidéo", "Enregistrement")

        private val TS_IN_NAME = Regex("(\\d{14})")
        private val DAY_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE)
        private val NAME_STAMP = SimpleDateFormat("yyMMdd-HHmmssSSS", Locale.FRANCE)

        /**
         * Listing de l'AP. DEUX FORMATS SELON LE FIRMWARE.
         *
         * JSON, mesure du 11-12 14:07:05 sur http://192.168.169.1/app/getfilelist
         * (reponse 200, 635 octets), corps journalise tel quel :
         *   {"result":0,"info":[{"folder":"EVENT","files":[
         *        {"name":"EVENT/20261111233442737.jpg","size":560,
         *         "createtimestr":"20261111233442","type":1}, ...],"count":5},
         *      {"folder":"AAC","files":[],"count":0},
         *      {"folder":"LOOP","files":[],"count":0}, ...]}
         * name est DONC "EVENT/<14chiffres+3>.jpg", sans DCIM ni 100HUNTI, et la
         * date est createtimestr (AAAAMMDDHHMMSS), pas un epoch.
         *
         * XML, formats FileListParser/FileBean du bytecode (champs
         * File|FPATH|TIME|SIZE|NAME|ATTR|TIMECODE) : conserve en secours, les
         * anciens logs constructeur en decrivent un.
         *
         * L'appelant journalise le corps brut quand la liste sort vide, d'ou le
         * "listing sans <File>" qui a livre le JSON ci-dessus.
         */
        fun parseFileList(s: String): List<MediaFile> {
            val t = s.trim()
            if (t.startsWith("{")) {
                val json = parseFileListJson(t)
                if (json.isNotEmpty()) return json
            }
            val out = ArrayList<MediaFile>()
            val blocks = ArrayList<String>()
            Regex("<item\b[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
                .findAll(s).forEach { blocks.add(it.groupValues[1]) }
            if (blocks.isEmpty())
                Regex("<File\b[^>]*>(.*?)</File>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(s).forEach { blocks.add(it.groupValues[1]) }
            for (b in blocks) {
                val path = field(b, "FPATH") ?: continue
                val name = field(b, "NAME") ?: path.substringAfterLast('/')
                val size = field(b, "SIZE")?.toLongOrNull() ?: 0L
                val time = field(b, "TIME")?.toLongOrNull()
                out.add(MediaFile(path, name, size, time).also { it.key = keyOf(name) })
            }
            return out
        }

        /**
         * Parseur JSON de ce listing, sans dependance : la reponse est plate
         * (info[] -> files[] -> 4 scalaires), un scan de caracteres suffit et
         * n'ajoute aucune librairie a l'APK.
         */
        private fun parseFileListJson(s: String): List<MediaFile> {
            val out = ArrayList<MediaFile>()
            // Decoupe en objets de niveau 1 ({...}) ; les objets "files" sont
            // trouves par leurs cles, ce qui rend le parseur insensible a la
            // profondeur exacte du JSON du firmware.
            Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").findAll(s).forEach { mn ->
                val rel = mn.groupValues[1]
                if (rel.isEmpty()) return@forEach
                val from = mn.range.first
                val to = (s.indexOf('}', from)).takeIf { it > 0 } ?: s.length
                val obj = s.substring(from, to)
                val size = Regex("\"size\"\\s*:\\s*(\\d+)").find(obj)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val stamp = Regex("\"createtimestr\"\\s*:\\s*\"?(\\d{14})").find(obj)?.groupValues?.get(1)
                val epoch = stamp?.let {
                    try {
                        SimpleDateFormat("yyyyMMddHHmmss", Locale.FRANCE).parse(it)?.time?.div(1000L)
                    } catch (e: Exception) { null }
                }
                out.add(MediaFile("/" + rel, rel.substringAfterLast('/'), size, epoch)
                    .also { it.key = keyOf(rel) })
            }
            return out
        }

        private fun field(block: String, key: String): String? {
            val m = Regex("<" + key + ">(.*?)</" + key + ">", RegexOption.DOT_MATCHES_ALL)
                .find(block) ?: return null
            return m.groupValues[1].trim().ifEmpty { null }
        }

        /** Liste etablie AVANT le premier GET HTTP (12 noms en memoire avant la
         *  1re connection dans le log) : elle vient du BLE. Si le champ BLE du nom
         *  n'est pas identifie, on repasse par parseFileList (secours). */
        fun fromBleNames(names: List<String>): List<MediaFile> =
            names.map { MediaFile("/EVENT/" + it, it, 0L, null)
                .also { m -> m.key = keyOf(m.name) } }

        /**
         * Cle canonique d'un media, la SEULE qui marche entre les trois mondes
         * observes sur cette paire (mesure du 11-12 14:32, DCIM/100HUNTI) :
         *   listing AP    "20261112010334735.jpg"
         *   fichier carte "DH-261112-010334000-20261112010334735.jpg"
         *   MediaStore    _display_name = idem fichier carte
         * Le nom local est "DH-<stamp>-<stampMs>-<stampMs+000>.jpg" : le nom du
         * listing y est passe ENTIER. La cle est donc la plus longue suite de
         * chiffres du nom (17 chiffres ici ; les fragments "261112" (6) et
         * "010334000" (9) sont plus courts et ne servent pas).
         */
        fun keyOf(name: String): String {
            var best = ""
            Regex("[0-9]{10,}").findAll(name).forEach { m ->
                if (m.value.length > best.length) best = m.value
            }
            return if (best.isNotEmpty()) best else name.substringBeforeLast('.')
        }
    }
}
