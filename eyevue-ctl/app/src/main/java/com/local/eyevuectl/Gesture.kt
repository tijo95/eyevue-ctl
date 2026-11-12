package com.local.eyevuectl

/**
 * Gestes entrants (cmd 0x45 ACTION_SYNC), GENERE depuis protocol.json.
 * Une app tierce peut donc declencher une action sur un signe de tete.
 *
 * Ce que le bytecode definit reellement : les noms de Command$Param
 * ci-dessous, tous de valeur 0x00/0x01 (un drapeau par famille, pas un
 * code de geste). Le champ 0x45 transporte donc plusieurs informations
 * dans un payload structure, pas un seul octet de code.
 * Les codes 0x07..0x11 (swipes, clic simple/double/triple) de la liste
 * de specs ne sont PAS dans le bytecode : seul le logcat les revelera.
 */
object Gesture {
    /** aucun geste */
    const val NoAction: Int = 0x00
    /** hochement de tete */
    const val HeadNod: Int = 0x01
    /** secouement de tete */
    const val HeadShake: Int = 0x01
    /** lunettes enfilees */
    const val WearOn: Int = 0x01
    /** lunettes retirees */
    const val WearOff: Int = 0x00
    /** declenche photo */
    const val StartPhoto: Int = 0x01
    /** photo annulee */
    const val NotStartPhoto: Int = 0x00
    /** declenche enregistrement */
    const val StartRecord: Int = 0x01
    /** enregistrement annule */
    const val NotStartRecord: Int = 0x00
    /** lecture musique */
    const val MusicPlay: Int = 0x01
    /** pause musique */
    const val MusicPause: Int = 0x00
    /** volume fort */
    const val VolumeBig: Int = 0x01
    /** volume faible */
    const val VolumeLittle: Int = 0x01

    /** Description lisible d'une trame 0x45 : payload brut + drapeaux. */
    fun describe(payload: ByteArray): String {
        val hex = payload.joinToString(" ") { "%02X".format(it) }
        val flags = ArrayList<String>()
        if (payload.isNotEmpty() && payload[0].toInt() and 0xFF == 0x00) flags.add("aucun geste")
        if (payload.isNotEmpty() && payload[0].toInt() and 0xFF == 0x01) flags.add("hochement de tete")
        if (payload.isNotEmpty() && payload[0].toInt() and 0xFF == 0x01) flags.add("secouement de tete")
        if (payload.isNotEmpty() && payload[0].toInt() and 0xFF == 0x01) flags.add("lunettes enfilees")
        if (payload.isNotEmpty() && payload[0].toInt() and 0xFF == 0x00) flags.add("lunettes retirees")
        if (payload.isNotEmpty() && payload[0].toInt() and 0xFF == 0x01) flags.add("declenche photo")
        if (payload.isNotEmpty() && payload[0].toInt() and 0xFF == 0x00) flags.add("photo annulee")
        if (payload.isNotEmpty() && payload[0].toInt() and 0xFF == 0x01) flags.add("declenche enregistrement")
        if (payload.isNotEmpty() && payload[0].toInt() and 0xFF == 0x00) flags.add("enregistrement annule")
        if (payload.isNotEmpty() && payload[0].toInt() and 0xFF == 0x01) flags.add("lecture musique")
        if (payload.isNotEmpty() && payload[0].toInt() and 0xFF == 0x00) flags.add("pause musique")
        if (payload.isNotEmpty() && payload[0].toInt() and 0xFF == 0x01) flags.add("volume fort")
        if (payload.isNotEmpty() && payload[0].toInt() and 0xFF == 0x01) flags.add("volume faible")
        return "payload[$hex]" + (if (flags.isEmpty()) " (structure a confirmer par capture)" else flags.joinToString(" / ", prefix = " -> "))
    }
}
