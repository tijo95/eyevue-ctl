package com.local.eyevuectl

/**
 * Parametres des commandes, GENERES depuis protocol.json - ne pas editer ici.
 *
 * Un meme octet porte plusieurs sens selon la commande : chaque commande a son
 * propre objet P<Nom>. Les valeurs sont celles relevees dans le bytecode.
 *
 * Volume : les deux chemins n'utilisent PAS les memes valeurs, ils sont donc
 * separe (PAppSetVolume = 0x30/0x31, PAiSetVolume = 0x00/0x01) ; la capture
 * logcat dira lequel la vraie app utilise.
 */

object PAiSetVolume {
    const val Down: Int = 0x00
    const val Up: Int = 0x01
}

object PAppGetDeviceStatus {
    const val Status: Int = 0x00
}

object PAppSetVolume {
    const val Low: Int = 0x30
    const val High: Int = 0x31
}

object PBrightness {
    const val Low: Int = 0x30
    const val Mid: Int = 0x31
    const val High: Int = 0x32
}

object PControlCall {
    const val Hangup: Int = 0x00
    const val Answer: Int = 0x01
}

object PControlMusic {
    const val Pause: Int = 0x00
    const val Play: Int = 0x01
}

// RETIRE de l'app : aucun reglage de teinte par BLE sur E09-3AA1 (le masque 0x95
// ne declare pas l'electrochrome). Le 0x37 du SDK est un alias de
// APP_PULL_THUMBNAIL_STATUS ; exposer des valeurs fonce/clair induirait en erreur.

object PMicSetting {
    const val Value: Int = 0x00
}

object POfflineLanguage {
    const val Zh: Int = 0x00
    const val En: Int = 0x01
}

object PRecordAudio {
    const val End: Int = 0x00
    const val Start: Int = 0x01
}

object PRecordTime {
    const val M1: Int = 0x3C
    const val M3: Int = 0xB4
    const val M5: Int = 0x12C
    const val M7: Int = 0x1A4
    const val M10: Int = 0x258
    /** secondes -> valeur RECORD_TIME (payload 2 octets) */
    fun minutes(m: Int): Int = m * 60
}

object PSetCaptureDirection {
    const val Vertical: Int = 0x30
    const val Horizontal: Int = 0x31
}

object PSetCaptureMode {
    const val Standard: Int = 0x30
    const val Classic: Int = 0x31
}

object PSetCaptureStyle {
    const val Ratio16_9: Int = 0x30
    const val Ratio4_3: Int = 0x31
    const val Res2K: Int = 0x30
    const val Res1080P: Int = 0x31
}

object PSetEq {
    const val Standard: Int = 0x00
    const val Rock: Int = 0x01
    const val Pop: Int = 0x02
    const val Classical: Int = 0x03
    const val Jazz: Int = 0x04
    const val Custom: Int = 0xFF
}

object PSwitchMusic {
    const val Prev: Int = 0x00
    const val Next: Int = 0x01
}

object PTakePhoto {
    const val Thumbnail: Int = 0x30
    const val HighQuality: Int = 0x31
}

object PWearDetect {
    const val Off: Int = 0x30
    const val On: Int = 0x31
}
