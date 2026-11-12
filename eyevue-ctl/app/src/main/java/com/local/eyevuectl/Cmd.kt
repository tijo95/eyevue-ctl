package com.local.eyevuectl

/**
 * Commandes du protocole, GENEREES depuis protocol.json - ne pas editer ici.
 * Reverse-engineering pour usage d'interop (voir protocol/PROTOCOL.md).
 */
object Cmd {
    // 0x01 = BRIGHTNESS
    const val BRIGHTNESS: Int = 0x01
    // 0x02 = RECORD_TIME
    const val RECORD_TIME: Int = 0x02
    // 0x03 = MIC_SETTING
    const val MIC_SETTING: Int = 0x03
    // 0x04 = WEAR_DETECT
    const val WEAR_DETECT: Int = 0x04
    // 0x06 = VOICE_COMMAND
    const val VOICE_COMMAND: Int = 0x06
    // 0x07 = GESTURE_SWIPE_FRONT
    const val GESTURE_SWIPE_FRONT: Int = 0x07
    // 0x08 = GESTURE_SWIPE_BACK
    const val GESTURE_SWIPE_BACK: Int = 0x08
    // 0x09 = GESTURE_CLICK
    const val GESTURE_CLICK: Int = 0x09
    // 0x0A = DEVICE_PHOTO_RECOGNITION
    const val DEVICE_PHOTO_RECOGNITION: Int = 0x0A
    // 0x10 = GESTURE_DOUBLE_CLICK
    const val GESTURE_DOUBLE_CLICK: Int = 0x10
    // 0x11 = GESTURE_TRIPLE_CLICK
    const val GESTURE_TRIPLE_CLICK: Int = 0x11
    // 0x14 = RECOVER_FACTORY
    const val RECOVER_FACTORY: Int = 0x14
    // 0x15 = CLEAR_DEVICE_CONNECTION_RECORD
    const val CLEAR_DEVICE_CONNECTION_RECORD: Int = 0x15
    // 0x16 = GET_CAPACITY
    const val GET_CAPACITY: Int = 0x16
    // 0x17 = GET_BATTERY
    const val GET_BATTERY: Int = 0x17
    // 0x22 = TAKE_PHOTO
    const val TAKE_PHOTO: Int = 0x22
    // 0x23 = RECORD_VIDEO
    const val RECORD_VIDEO: Int = 0x23
    // 0x24 = STOP_RECORD
    const val STOP_RECORD: Int = 0x24
    // 0x25 = APP_RECEIVE_WIFI_INFO
    const val APP_RECEIVE_WIFI_INFO: Int = 0x25
    // 0x30 = SWITCH_MUSIC
    const val SWITCH_MUSIC: Int = 0x30
    // 0x31 = CONTROL_MUSIC
    const val CONTROL_MUSIC: Int = 0x31
    // 0x32 = AI_SET_VOLUME
    const val AI_SET_VOLUME: Int = 0x32
    // 0x33 = CONTROL_CALL
    const val CONTROL_CALL: Int = 0x33
    // 0x34 = RECORD_AUDIO
    const val RECORD_AUDIO: Int = 0x34
    // 0x35 = SET_CAPTURE_STYLE
    const val SET_CAPTURE_STYLE: Int = 0x35
    // 0x36 = APP_PULL_PHOTO
    const val APP_PULL_PHOTO: Int = 0x36
    // 0x37 = PULL_THUMBNAIL_STATUS / ELECTROCHROMIC
    const val PULL_THUMBNAIL_STATUS: Int = 0x37
    const val ELECTROCHROMIC: Int = 0x37
    // 0x39 = OPEN_WIFI
    const val OPEN_WIFI: Int = 0x39
    // 0x40 = APP_PULL_THUMBNAIL_COUNT
    const val APP_PULL_THUMBNAIL_COUNT: Int = 0x40
    // 0x42 = APP_RECEIVE_THUMBNAIL_COUNT
    const val APP_RECEIVE_THUMBNAIL_COUNT: Int = 0x42
    // 0x43 = GESTURE_RECOVER
    const val GESTURE_RECOVER: Int = 0x43
    // 0x44 = FILE_DOWNLOAD_FINISH
    const val FILE_DOWNLOAD_FINISH: Int = 0x44
    // 0x45 = ACTION_SYNC
    const val ACTION_SYNC: Int = 0x45
    // 0x46 = APP_RECEIVE_VOICE_DATA
    const val APP_RECEIVE_VOICE_DATA: Int = 0x46
    // 0x48 = APP_GET_DEVICE_STATUS
    const val APP_GET_DEVICE_STATUS: Int = 0x48
    // 0x49 = APP_CANCEL_VOICE_RECOGNITION
    const val APP_CANCEL_VOICE_RECOGNITION: Int = 0x49
    // 0x50 = SET_CAPTURE_MODE
    const val SET_CAPTURE_MODE: Int = 0x50
    // 0x51 = APP_CANCEL_AI_VOICE
    const val APP_CANCEL_AI_VOICE: Int = 0x51
    // 0x53 = APP_RECEIVE_BATTERY
    const val APP_RECEIVE_BATTERY: Int = 0x53
    // 0x54 = ISP_WORK_STATE
    const val ISP_WORK_STATE: Int = 0x54
    // 0x55 = APP_RECEIVE_DEVICE_INFO
    const val APP_RECEIVE_DEVICE_INFO: Int = 0x55
    // 0x56 = APP_STOP_VOICE_RECOGNITION
    const val APP_STOP_VOICE_RECOGNITION: Int = 0x56
    // 0x57 = APP_START_VOICE_RECOGNITION
    const val APP_START_VOICE_RECOGNITION: Int = 0x57
    // 0x59 = TIME
    const val TIME: Int = 0x59
    // 0x61 = SET_CAPTURE_DIRECTION
    const val SET_CAPTURE_DIRECTION: Int = 0x61
    // 0x62 = OFFLINE_LANGUAGE
    const val OFFLINE_LANGUAGE: Int = 0x62
    // 0x63 = WIFI_OTA
    const val WIFI_OTA: Int = 0x63
    // 0x64 = CUSTOMER_PID_VID
    const val CUSTOMER_PID_VID: Int = 0x64
    // 0x65 = SEND_ISP_VERSION
    const val SEND_ISP_VERSION: Int = 0x65
    // 0x66 = GET_SUPPORT_LIVE
    const val GET_SUPPORT_LIVE: Int = 0x66
    // 0x67 = APP_LIVE
    const val APP_LIVE: Int = 0x67
    // 0x68 = DEVICE_CONNECT_A2DP
    const val DEVICE_CONNECT_A2DP: Int = 0x68
    // 0x69 = APP_RECEIVE_SOUND
    const val APP_RECEIVE_SOUND: Int = 0x69
    // 0x70 = APP_SET_VOLUME
    const val APP_SET_VOLUME: Int = 0x70
    // 0x71 = GET_VOICE_ASSISTANT_STATUS
    const val GET_VOICE_ASSISTANT_STATUS: Int = 0x71
    // 0x72 = SET_VOICE_ASSISTANT_STATUS
    const val SET_VOICE_ASSISTANT_STATUS: Int = 0x72
    // 0x73 = SET_EQ
    const val SET_EQ: Int = 0x73
    // 0x95 = GET_SUPPORT_FUNCTION
    const val GET_SUPPORT_FUNCTION: Int = 0x95
    // 0x96 = WIFI_OTA_RESULT
    const val WIFI_OTA_RESULT: Int = 0x96
    // 0x97 = PHOTO_START / VOICE_START
    const val PHOTO_START: Int = 0x97
    const val VOICE_START: Int = 0x97
    // 0x98 = PHOTO_TRANS / THUMB_START
    const val PHOTO_TRANS: Int = 0x98
    const val THUMB_START: Int = 0x98
    // 0x99 = PHOTO_END / VOICE_END
    const val PHOTO_END: Int = 0x99
    const val VOICE_END: Int = 0x99
    // 0x9A = GET_EQ
    const val GET_EQ: Int = 0x9A
    // 0x9B = APP_BIND_CONFIRM
    const val APP_BIND_CONFIRM: Int = 0x9B

    /** opcode -> noms de commandes (plusieurs si opcode partage) */
    val NAMES = mapOf(
        "0x01" to "BRIGHTNESS",
        "0x02" to "RECORD_TIME",
        "0x03" to "MIC_SETTING",
        "0x04" to "WEAR_DETECT",
        "0x06" to "VOICE_COMMAND",
        "0x07" to "GESTURE_SWIPE_FRONT",
        "0x08" to "GESTURE_SWIPE_BACK",
        "0x09" to "GESTURE_CLICK",
        "0x0A" to "DEVICE_PHOTO_RECOGNITION",
        "0x10" to "GESTURE_DOUBLE_CLICK",
        "0x11" to "GESTURE_TRIPLE_CLICK",
        "0x14" to "RECOVER_FACTORY",
        "0x15" to "CLEAR_DEVICE_CONNECTION_RECORD",
        "0x16" to "GET_CAPACITY",
        "0x17" to "GET_BATTERY",
        "0x22" to "TAKE_PHOTO",
        "0x23" to "RECORD_VIDEO",
        "0x24" to "STOP_RECORD",
        "0x25" to "APP_RECEIVE_WIFI_INFO",
        "0x30" to "SWITCH_MUSIC",
        "0x31" to "CONTROL_MUSIC",
        "0x32" to "AI_SET_VOLUME",
        "0x33" to "CONTROL_CALL",
        "0x34" to "RECORD_AUDIO",
        "0x35" to "SET_CAPTURE_STYLE",
        "0x36" to "APP_PULL_PHOTO",
        "0x37" to "PULL_THUMBNAIL_STATUS/ELECTROCHROMIC",
        "0x39" to "OPEN_WIFI",
        "0x40" to "APP_PULL_THUMBNAIL_COUNT",
        "0x42" to "APP_RECEIVE_THUMBNAIL_COUNT",
        "0x43" to "GESTURE_RECOVER",
        "0x44" to "FILE_DOWNLOAD_FINISH",
        "0x45" to "ACTION_SYNC",
        "0x46" to "APP_RECEIVE_VOICE_DATA",
        "0x48" to "APP_GET_DEVICE_STATUS",
        "0x49" to "APP_CANCEL_VOICE_RECOGNITION",
        "0x50" to "SET_CAPTURE_MODE",
        "0x51" to "APP_CANCEL_AI_VOICE",
        "0x53" to "APP_RECEIVE_BATTERY",
        "0x54" to "ISP_WORK_STATE",
        "0x55" to "APP_RECEIVE_DEVICE_INFO",
        "0x56" to "APP_STOP_VOICE_RECOGNITION",
        "0x57" to "APP_START_VOICE_RECOGNITION",
        "0x59" to "TIME",
        "0x61" to "SET_CAPTURE_DIRECTION",
        "0x62" to "OFFLINE_LANGUAGE",
        "0x63" to "WIFI_OTA",
        "0x64" to "CUSTOMER_PID_VID",
        "0x65" to "SEND_ISP_VERSION",
        "0x66" to "GET_SUPPORT_LIVE",
        "0x67" to "APP_LIVE",
        "0x68" to "DEVICE_CONNECT_A2DP",
        "0x69" to "APP_RECEIVE_SOUND",
        "0x70" to "APP_SET_VOLUME",
        "0x71" to "GET_VOICE_ASSISTANT_STATUS",
        "0x72" to "SET_VOICE_ASSISTANT_STATUS",
        "0x73" to "SET_EQ",
        "0x95" to "GET_SUPPORT_FUNCTION",
        "0x96" to "WIFI_OTA_RESULT",
        "0x97" to "PHOTO_START/VOICE_START",
        "0x98" to "PHOTO_TRANS/THUMB_START",
        "0x99" to "PHOTO_END/VOICE_END",
        "0x9A" to "GET_EQ",
        "0x9B" to "APP_BIND_CONFIRM",
    )

    fun nameOf(cmd: Int): String =
        NAMES["0x%02X".format(cmd)] ?: "inconnu_0x%02X".format(cmd)

    /** nom de commande -> opcode ; KeyError si nom inconnu. */
    fun code(name: String): Int =
        NAMES.entries.first { name in it.value.split('/') }.key.toInt(16)
}
