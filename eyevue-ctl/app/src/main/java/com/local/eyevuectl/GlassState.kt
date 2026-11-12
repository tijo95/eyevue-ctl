package com.local.eyevuectl

import kotlin.math.roundToInt
import java.util.Calendar

/**
 * Etat agregre des lunettes, construit depuis les trames recues.
 *
 * ⚠ La CHARGE n'a AUCUN opcode dedie dans le protocole (le module protocol
 * ne definit que GET_BATTERY 0x17 / APP_RECEIVE_BATTERY 0x53). L'indicateur
 * "⚡" de l'app d'origine vient donc du payload de 0x53. Les formats ci-dessous
 * sont des hypotheses, a trancher par la capture logcat ; [parseBattery] les
 * teste dans l'ordre et renvoie celle qui a un sens.
 */
class GlassState {
    var connected = false
    var battery: Int? = null
    var charging: Boolean? = null
    var storageUsed: Int? = null
    var storageTotal: Int? = null
    var brightness: Int? = null
    var volume: Int? = null
    var firmware: String? = null
    /** Niveau (0-100) annonce par la trame 0x17, quand son payload est en ASCII. */
    var levelFrom0x17: Int? = null
    /** Masque de capacites (trame 0x95, 4 octets GROS endien), null = pas recu. */
    var capabilities: Int? = null
    var deviceName: String? = null
    var mac: String? = null
    var lastRx: Frame.Rx? = null

    companion object {
        /**
         * Payload de APP_RECEIVE_BATTERY (0x53) : [drapeaux][niveau].
         * Ordre RETABLI par les trames relevees sur le terrain, qui contredisaient
         * l'ordre [niveau][drapeaux] suppose au depart :
         *   [00 64] -> niveau 0x64=100, drapeaux 0x00 -> 100 %, pas en charge
         *   [01 64] -> niveau 0x64=100, drapeaux 0x01 -> 100 %, EN CHARGE
         * Le premier octet valait 0 ou 1 : lu comme le niveau il donnait "0 %" ou
         * "1 %", ce qui est l'affichage errone constate a l'ecran.
         * L'octet de drapeaux porte le bit 0 pour la charge ; les autres bits sont
         * restent non observes.
         */
        fun parseBattery(p: ByteArray): Pair<Int, Boolean?> {
            if (p.isEmpty()) return 0 to null
            val b0 = p[0].toInt() and 0xFF
            val b1 = p[1].toInt() and 0xFF
            return when (p.size) {
                1 -> b0.coerceAtMost(100) to null
                else -> {
                    // cas reel : [drapeaux][niveau]
                    if (b0 <= 1 && b1 in 0..100) b1 to (b0 and 1 == 1)
                    // repli : [niveau][drapeaux], si le premier octet est un niveau plausible
                    else if (b0 in 0..100) b0 to (b1 and 1 == 1)
                    else b1.coerceAtMost(100) to (b0 and 1 == 1)
                }
            }
        }

        /**
         * Masque de capacites (reponse a GET_SUPPORT_FUNCTION 0x95) : 4 octets,
         * GROS ENDIEN (lecture de DeviceCapabilityParser : b0<<24|b1<<16|b2<<8|b3),
         * 32 bits ; les bits 0..14 sont definis par l'enum DeviceCapability.
         * Reel sur la paire E09-3AA1 testee : [00 00 04 0A] = 0x0000040A
         * -> bits 1, 3, 10 -> reglage rapide du volume, detection de port (dyn),
         * orientation d'ecran (dyn).
         *
         * IMPORTANT : AUCUN bit ne declare le verre electrochrome. La table
         * DeviceCapability s'arrete au bit 14 (reglage EQ) et le parseur imprime
         * "bit de fonction non defini" pour tout bit inconnu : masquer un bit
         * electrochrome est donc impossible, la fonction n'est pas negociable.
         * Sur ces modeles la teinte est reglee LOCALEMENT par le tactile de la
         * branche (confirme par l'utilisateur) ; le 0x37 du SDK ne le fait pas.
         */
        fun parseCapabilities(p: ByteArray): Int? {
            if (p.size < 4) return null
            return ((p[0].toInt() and 0xFF) shl 24) or ((p[1].toInt() and 0xFF) shl 16) or
                ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        }

        /** Bits definis par l'enum DeviceCapability, dans l'ordre de leur position. */
        val CAPABILITY_NAMES = arrayOf(
            "diffusion en direct (live)",
            "reglage rapide du volume",
            "filigrane photo",
            "detection de port (dynamique)",
            "detection de port",
            "stabilisation (dynamique)",
            "stabilisation",
            "mot d'eveil IA",
            "voix hors-ligne locale",
            "changement de langue dynamique",
            "orientation d'ecran (dynamique)",
            "orientation d'ecran",
            "bouton de reinitialisation d'usine distinct",
            "confirmation d'appairage",
            "reglage EQ par l'app")

        /** Noms des capacites annoncees ; bits >14 signales comme non definis. */
        fun capabilityNames(mask: Int): List<String> {
            val out = ArrayList<String>()
            for (bit in 0..14) if (mask and (1 shl bit) != 0) out.add(CAPABILITY_NAMES[bit])
            if (mask and (0xFFFFFFF0.toInt()) != 0) {
                val extra = ArrayList<String>()
                for (bit in 15..31) if (mask and (1 shl bit) != 0) extra.add("bit $bit")
                out.add("NON DEFINIS: " + extra.joinToString(", "))
            }
            return out
        }

        /**
         * PAS DE TENSION. La lecture "16 bits petit boutien en millivolts" de 0x17
         * a ete utilisee, affichee a l'ecran ("13,88 V", "12,35 V", "7,12 V") et
         * justifiee par une courbe "Li-ion 3S" : TOUT cela est FAUX. Preuve decisive,
         * deux paires (0x17, 0x53) de la MEME session, ou 0x53 donne le niveau
         * mesure par les lunettes elles-memes :
         *   14:39  0x17 [39 38 00]   14:47  0x53 [00 61] -> 97 %
         *   14:48  0x53 [00 60] -> 96 %   14:53  0x17 [39 36 00]
         * Les deux premiers octets de 0x17 sont de l'ASCII : "98" et "96", soit les
         * 97 % et 96 % des lunettes. La meme clef marche sur les autres echantillons
         * ([38 31] = "81", [36 38] = "68"). Decode en millivolts, ces memes octets
         * donnaient 14 393 mV et 13 881 mV : a 97 % et 96 % de charge, un pack ne
         * peut pas etre a mi-course (4S Li-ion : ~16,4 V a 97 %). La tension etait
         * donc un nombre FABRIQUE, et c'est lui qui etait affiche.
         *
         * 0x17 est donc lu comme un NIVEAU ASCII, source secondaire : le 3e octet
         * sert de drapeau de charge (0 = decharge, 1 = charge), et [3A 30 01]
         * (ASCII ":0") reste NON EXPLIQUE : ce payload-la n'est pas deux chiffres.
         * Le niveau de reference reste 0x53 APP_RECEIVE_BATTERY, mesure des lunettes.
         * Un niveau issu de 0x17 est toujours signale comme tel a l'ecran.
         */
        fun parseLevel0x17(p: ByteArray): Pair<Int?, Boolean?> {
            val charging = if (p.size >= 3) (p[2].toInt() and 1) == 1 else null
            if (p.size < 2) return null to charging
            val c0 = p[0].toInt() and 0xFF
            val c1 = p[1].toInt() and 0xFF
            // payload ASCII attendu : "97", "96", "81", "68"...
            if (c0 in '0'.code..'9'.code && c1 in '0'.code..'9'.code) {
                val n = (c0 - '0'.code) * 10 + (c1 - '0'.code)
                return n.coerceAtMost(100) to charging
            }
            return null to charging   // ex. [3A 30 01] (":0") : non decode
        }

        /** Payload de TIME (0x59) : date systeme encodee sur 4 octets. */
        fun timePayload(nowMs: Long = System.currentTimeMillis()): ByteArray {
            val c = Calendar.getInstance()
            c.timeInMillis = nowMs
            val y = c.get(Calendar.YEAR) - 2000
            val daysSince2000 = ((nowMs / 1000L) / 86400L).toInt()
            val secsOfDay = ((nowMs / 1000L) % 86400L).toInt()
            val v = (y shl 25) or (daysSince2000 shl 9) or (secsOfDay shr 5)
            return byteArrayOf(
                (v shr 24 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte(),
                (v shr 8 and 0xFF).toByte(), (v and 0xFF).toByte())
        }

        /** Payload de GET_CAPACITY (0x16) -> (utilisé, total). */
        fun parseCapacity(p: ByteArray): Pair<Int?, Int?> {
            fun u(i: Int) = if (p.size > i) p[i].toInt() and 0xFF else null
            return when (p.size) {
                2 -> u(0) to u(1)
                4 -> (((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)) to
                       (((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF))
                else -> u(0) to u(1)
            }
        }

        /** Payload de APP_RECEIVE_DEVICE_INFO (0x55) : champs ASCII separés. */
        fun parseDeviceInfo(p: ByteArray): Map<String, String> {
            val s = String(p, Charsets.ISO_8859_1).trim('\u0000')
            val parts = s.split(Regex("[,;|\\x00]")).map { it.trim() }.filter { it.isNotEmpty() }
            val out = LinkedHashMap<String, String>()
            parts.forEachIndexed { i, v ->
                val key = when (i) {
                    0 -> "name"; 1 -> "mac"; 2 -> "firmware"; 3 -> "sn"; else -> "f$i"
                }
                out[key] = v
            }
            return out
        }
    }

    /** Applique une trame recue ; renvoie le champ modifie (pour rafraichir l'UI). */
    fun apply(rx: Frame.Rx): String {
        lastRx = rx
        when (rx.cmd) {
            0x53 -> { val (b, c) = parseBattery(rx.payload); battery = b; charging = c; return "batterie" }
            0x17 -> {
                val (n, ch) = parseLevel0x17(rx.payload)
                levelFrom0x17 = n
                if (n == null && rx.payload.isNotEmpty())
                    android.util.Log.i("EyevueCtl",
                        "0x17 non decode (payload non ASCII) : " +
                            rx.payload.joinToString(" ") { "%02X".format(it) })
                if (charging == null && ch != null) charging = ch
                return if (n == null) "niveau 0x17 non decode" else "niveau 0x17 = $n%"
            }
            0x16 -> { val (u, t) = parseCapacity(rx.payload); storageUsed = u; storageTotal = t; return "stockage" }
            0x55 -> {
                val m = parseDeviceInfo(rx.payload)
                deviceName = m["name"] ?: deviceName
                mac = m["mac"] ?: mac
                firmware = m["firmware"] ?: firmware
                return "infos"
            }
            0x37 -> return "statut vignettes"
            0x95 -> {
                val m = parseCapabilities(rx.payload)
                capabilities = m
                return if (m == null) "capacites (payload < 4 octets)" else "capacites"
            }
            0x65 -> { firmware = String(rx.payload, Charsets.ISO_8859_1).trim('\u0000'); return "firmware" }
        }
        return "0x%02X".format(rx.cmd)
    }

    /**
     * Pourcentage affiche. Priorite a 0x53 APP_RECEIVE_BATTERY, niveau mesure par
     * les lunettes. A defaut, le niveau ASCII de 0x17, signale par
     * batteryFrom0x17. Les deux sont des donnees des lunettes : aucune valeur
     * n'est calculee par l'app.
     */
    val batteryPct: Int?
        get() = battery?.coerceIn(0, 100) ?: levelFrom0x17?.coerceIn(0, 100)

    /** Vrai quand le pourcentage affiche vient de 0x17 et non de 0x53. */
    val batteryFrom0x17: Boolean get() = battery == null && levelFrom0x17 != null
}
