#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen_kotlin.py - genere le code Kotlin du protocole depuis protocol.json.

protocol.json est la source unique : decode.py / commands.py (outillage de
verification) et le code Android partagent exactement les memes tables.

Usage :  python gen_kotlin.py        (ecrit dans ../eyevue-ctl/)

Les noms de parametres ci-dessous sont ceux de protocol.json, releves dans le
module protocol de l'APK. Une commande possede plusieurs familles de parametres
(16:9 ET 2K par ex.) : chaque nom devient une constante PascalCase.
"""
import json
import os

SPEC = json.load(open("protocol.json", encoding="utf-8"))
CMDS = SPEC["commands"]
PARAMS = SPEC["params"]

APP = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir,
                   "eyevue-ctl", "app", "src", "main", "java", "com", "local", "eyevuectl")

# commande -> [(constante Kotlin, nom du parametre dans protocol.json)]
# Les noms de parametres sont ceux releves dans Command$Param.smali.
SPEC_PARAMS = {
    "BRIGHTNESS":          [("Low", "LOW_BRIGHTNESS"), ("Mid", "MID_BRIGHTNESS"),
                            ("High", "HIGH_BRIGHTNESS")],
    "ELECTROCHROMIC":      [("Off", "ELECTROCHROMIC_OFF"), ("On", "ELECTROCHROMIC_ON")],
    "WEAR_DETECT":         [("Off", "WEAR_DETECT_OFF"), ("On", "WEAR_DETECT_ON")],
    "TAKE_PHOTO":          [("Thumbnail", "TAKE_PHOTO_THUMBNAIL"),
                            ("HighQuality", "TAKE_PHOTO_HIGH_QUALITY")],
    "RECORD_TIME":         [("M1", "RECORD_TIME_1MIN"), ("M3", "RECORD_TIME_3MIN"),
                            ("M5", "RECORD_TIME_5MIN"), ("M7", "RECORD_TIME_7MIN"),
                            ("M10", "RECORD_TIME_10MIN")],
    "RECORD_AUDIO":        [("End", "RECORD_AUDIO_END"), ("Start", "RECORD_AUDIO_START")],
    # les 2 chemins de volume, valeurs differentes
    "APP_SET_VOLUME":      [("Low", "VOLUME_SMALL"), ("High", "VOLUME_BIG")],
    "AI_SET_VOLUME":       [("Down", "VOLUME_DOWN"), ("Up", "VOLUME_UP")],
    "CONTROL_MUSIC":       [("Pause", "CONTROL_MUSIC_PAUSE"), ("Play", "CONTROL_MUSIC_PLAY")],
    "SWITCH_MUSIC":        [("Prev", "SWITCH_MUSIC_PREV"), ("Next", "SWITCH_MUSIC_NEXT")],
    "CONTROL_CALL":        [("Hangup", "CONTROL_CALL_HANGUP"), ("Answer", "CONTROL_CALL_ANSWER")],
    "SET_EQ":              [("Standard", "EQ_STANDARD"), ("Rock", "EQ_ROCK"), ("Pop", "EQ_POP"),
                            ("Classical", "EQ_CLASSICAL"), ("Jazz", "EQ_JAZZ"), ("Custom", "EQ_CUSTOM")],
    "SET_CAPTURE_MODE":    [("Standard", "CAPTURE_MODE_STANDARD"), ("Classic", "CAPTURE_MODE_CLASSIC")],
    "SET_CAPTURE_STYLE":   [("Ratio16_9", "CAPTURE_STYLE_16_9"), ("Ratio4_3", "CAPTURE_STYLE_4_3"),
                            ("Res2K", "CAPTURE_STYLE_2K"), ("Res1080P", "CAPTURE_STYLE_1080P")],
    "SET_CAPTURE_DIRECTION": [("Vertical", "CAMERA_VERTICAL"), ("Horizontal", "CAMERA_HORIZONTAL")],
    "APP_GET_DEVICE_STATUS": [("Status", "GET_DEVICE_STATUS")],
    "OFFLINE_LANGUAGE":    [("Zh", "OFFLINE_LANGUAGE_ZH"), ("En", "OFFLINE_LANGUAGE_EN")],
    # MIC_SETTING : le bytecode ne definit qu'UNE valeur (0x0). Aucune variante
    # "haute" documentee -> on ne l'invente pas, la capture tranchera.
    "MIC_SETTING":         [("Value", "MIC_SETTING")],
}
HELPERS = {
    "RECORD_TIME": ['    /** secondes -> valeur RECORD_TIME (payload 2 octets) */',
                    '    fun minutes(m: Int): Int = m * 60'],
}

# Gestes entrants (cmd 0x45 ACTION_SYNC) : payload -> texte affiche a l'ecran.
# Le bytecode ne definit pas les codes 0x07..0x11 ; la capture logcat les
# revelera. Les noms ci-dessous sont ceux de Command$Param.
GESTURE = [
    ("NoAction", "ACTION_SYNC_NO_ACTION", "aucun geste"),
    ("HeadNod", "ACTION_SYNC_HEAD_NOD", "hochement de tete"),
    ("HeadShake", "ACTION_SYNC_ACTION", "secouement de tete"),
    ("WearOn", "ACTION_SYNC_WEAR_ON", "lunettes enfilees"),
    ("WearOff", "ACTION_SYNC_WEAR_OFF", "lunettes retirees"),
    ("StartPhoto", "ACTION_SYNC_START_PHOTO", "declenche photo"),
    ("NotStartPhoto", "ACTION_SYNC_NOT_START_PHOTO", "photo annulee"),
    ("StartRecord", "ACTION_SYNC_START_RECORD", "declenche enregistrement"),
    ("NotStartRecord", "ACTION_SYNC_NOT_START_RECORD", "enregistrement annule"),
    ("MusicPlay", "ACTION_SYNC_MUSIC_PLAY", "lecture musique"),
    ("MusicPause", "ACTION_SYNC_MUSIC_PAUSE", "pause musique"),
    ("VolumeBig", "ACTION_SYNC_VOLUME_BIG", "volume fort"),
    ("VolumeLittle", "ACTION_SYNC_VOLUME_LITTLE", "volume faible"),
]


def gen_gestures():
    L = ["package com.local.eyevuectl", "",
         "/**",
         " * Gestes entrants (cmd 0x45 ACTION_SYNC), GENERE depuis protocol.json.",
         " * Une app tierce peut donc declencher une action sur un signe de tete.",
         " *",
         " * Ce que le bytecode definit reellement : les noms de Command$Param",
         " * ci-dessous, tous de valeur 0x00/0x01 (un drapeau par famille, pas un",
         " * code de geste). Le champ 0x45 transporte donc plusieurs informations",
         " * dans un payload structure, pas un seul octet de code.",
         " * Les codes 0x07..0x11 (swipes, clic simple/double/triple) de la liste",
         " * de specs ne sont PAS dans le bytecode : seul le logcat les revelera.",
         " */"]
    L.append("object Gesture {")
    for const, pname, txt in GESTURE:
        L.append("    /** %s */" % txt)
        L.append("    const val %s: Int = %s" % (const, hexv(PARAMS[pname])))
    L.append("")
    L.append("    /**")
    L.append("     * Description d'une trame 0x45 recue : hex brut, sans interpretation.")
    L.append("     *")
    L.append("     * On n'interpretete PAS le payload : les noms ci-dessus sont des")
    L.append("     * drapeaux booleens (tous 0x00/0x01) par famille, pas des codes de")
    L.append("     * geste ; les comparer a payload[0] produirait un afiche faux. La")
    L.append("     * structure reelle sera etablie par la capture logcat (decode.py).")
    L.append("     */")
    L.append("    fun describe(payload: ByteArray): String =")
    L.append("        \"payload[\" + payload.joinToString(\" \") { \"%02X\".format(it) } +")
    L.append("            \"] (structure a etablir par capture logcat)\"")
    L.append("}")
    return "\n".join(L) + "\n"


def ival(v):
    return int(v, 16) if isinstance(v, str) else v


def pascal(snake):
    """APP_SET_VOLUME -> AppSetVolume (nom d'objet Kotlin)."""
    return "".join(w.capitalize() for w in snake.split("_"))


def hexv(v):
    return "0x%02X" % ival(v)





def cmd_parts(code):
    return CMDS[code].split("/")


def params_block(name):
    pairs = SPEC_PARAMS.get(name)
    if not pairs:
        return []
    out = []
    for const, p in pairs:
        if p not in PARAMS:
            print("  !! parametre '%s' (commande %s) absent de protocol.json" % (p, name))
            continue
        if not (const and const[0].isalpha()):
            raise SystemExit("constante Kotlin invalide : %r" % const)
        out.append((const, PARAMS[p]))
    return out


def gen_cmd():
    L = ["""package com.local.eyevuectl

/**
 * Commandes du protocole, GENEREES depuis protocol.json - ne pas editer ici.
 * Reverse-engineering pour usage d'interop (voir protocol/PROTOCOL.md).
 */
object Cmd {"""]
    for code in sorted(CMDS, key=lambda c: int(c, 16)):
        parts = cmd_parts(code)
        L.append("    // %s = %s" % (code, " / ".join(parts)))
        for part in parts:
            L.append("    const val %s: Int = %s" % (part, hexv(code)))
    L.append("")
    L.append("    /** opcode -> noms de commandes (plusieurs si opcode partage) */")
    L.append("    val NAMES = mapOf(")
    for code in sorted(CMDS, key=lambda c: int(c, 16)):
        L.append('        "%s" to "%s",' % (hexv(code), CMDS[code]))
    L.append("    )")
    L.append("")
    L.append("    fun nameOf(cmd: Int): String =")
    L.append('        NAMES["0x%02X".format(cmd)] ?: "inconnu_0x%02X".format(cmd)')
    L.append("")
    L.append('    /** nom de commande -> opcode ; KeyError si nom inconnu. */')
    L.append("    fun code(name: String): Int =")
    L.append("        NAMES.entries.first { name in it.value.split('/') }.key.toInt(16)")
    L.append("}")
    return "\n".join(L) + "\n"


def gen_params():
    L = ["""package com.local.eyevuectl

/**
 * Parametres des commandes, GENERES depuis protocol.json - ne pas editer ici.
 *
 * Un meme octet porte plusieurs sens selon la commande : chaque commande a son
 * propre objet P<Nom>. Les valeurs sont celles relevees dans le bytecode.
 *
 * Volume : les deux chemins n'utilisent PAS les memes valeurs, ils sont donc
 * separe (PAppSetVolume = 0x30/0x31, PAiSetVolume = 0x00/0x01) ; la capture
 * logcat dira lequel la vraie app utilise.
 */"""]
    for name in sorted(set(p for x in CMDS.values() for p in x.split("/"))):
        blk = params_block(name)
        if not blk:
            continue
        L.append("")
        L.append("object P%s {" % pascal(name))
        for const, v in blk:
            L.append("    const val %s: Int = %s" % (const, hexv(v)))
        for h in HELPERS.get(name, []):
            L.append(h)
        L.append("}")
    L.append("")
    return "\n".join(L)


def main():
    os.makedirs(APP, exist_ok=True)
    for fn, txt in [("Cmd.kt", gen_cmd()), ("Params.kt", gen_params()),
                    ("Gesture.kt", gen_gestures())]:
        p = os.path.normpath(os.path.join(APP, fn))
        open(p, "w", encoding="utf-8").write(txt)
        print("ecrit %-9s %3d lignes" % (fn, txt.count("\n")))


if __name__ == "__main__":
    main()
