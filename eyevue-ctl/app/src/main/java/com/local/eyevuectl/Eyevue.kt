package com.local.eyevuectl

/**
 * Façade de pilotage : les gestes de l'ecran -> trames BLE.
 * Noms et valeurs = ceux releves dans le module protocol (voir Cmd.kt/Params.kt,
 * generes depuis protocol.json).
 */
class Eyevue(val ble: BleCtl) {

    // ---- statut ----
    fun requestStatus() {                                   // batterie + stockage + infos
        ble.send(Cmd.GET_BATTERY)
        ble.send(Cmd.GET_CAPACITY)
        ble.send(Cmd.APP_GET_DEVICE_STATUS, PAppGetDeviceStatus.Status)
    }

    // ---- photo / video / micro ----
    fun takePhoto(hq: Boolean = false) =
        ble.send(Cmd.TAKE_PHOTO, if (hq) PTakePhoto.HighQuality else PTakePhoto.Thumbnail)

    fun startVideo() = ble.send(Cmd.RECORD_VIDEO)
    fun stopRecord() = ble.send(Cmd.STOP_RECORD)
    fun setRecordDuration(minutes: Int) = ble.send(Cmd.RECORD_TIME, PRecordTime.minutes(minutes))

    fun startMic() = ble.send(Cmd.RECORD_AUDIO, PRecordAudio.Start)
    fun stopMic() = ble.send(Cmd.RECORD_AUDIO, PRecordAudio.End)

    // ---- affichage / lunettes ----
    fun setBrightness(level: Int) = ble.send(Cmd.BRIGHTNESS, level)   // PBrightness.*
    // PAS de reglage de teinte des verres : le masque 0x95 d'E09-3AA1 ne declare
    // aucun bit electrochrome et les gestes sur la branche ne produisent aucune
    // trame BLE. La teinte est locale au firmware, reglee au tactile de la branche.
    fun setWearDetect(on: Boolean) = ble.send(Cmd.WEAR_DETECT, if (on) PWearDetect.On else PWearDetect.Off)

    // ---- volume (2 chemins, valeurs DIFFERENTES) ----
    fun setVolumeLow() = ble.send(Cmd.APP_SET_VOLUME, PAppSetVolume.Low)
    fun setVolumeHigh() = ble.send(Cmd.APP_SET_VOLUME, PAppSetVolume.High)
    fun setVolumeDelta(up: Boolean) =
        ble.send(Cmd.AI_SET_VOLUME, if (up) PAiSetVolume.Up else PAiSetVolume.Down)

    // ---- audio (les lunettes sont aussi un casque BT) ----
    fun playPause() = ble.send(Cmd.CONTROL_MUSIC, PControlMusic.Pause)
    fun play() = ble.send(Cmd.CONTROL_MUSIC, PControlMusic.Play)
    fun trackPrev() = ble.send(Cmd.SWITCH_MUSIC, PSwitchMusic.Prev)
    fun trackNext() = ble.send(Cmd.SWITCH_MUSIC, PSwitchMusic.Next)
    fun callAnswer() = ble.send(Cmd.CONTROL_CALL, PControlCall.Answer)
    fun callHangup() = ble.send(Cmd.CONTROL_CALL, PControlCall.Hangup)
    fun setEq(style: Int) = ble.send(Cmd.SET_EQ, style)               // PSetEq.*
    fun getEq() = ble.send(Cmd.GET_EQ)

    // ---- photo : mode & qualite ----
    fun setCaptureMode(standard: Boolean) =
        ble.send(Cmd.SET_CAPTURE_MODE, if (standard) PSetCaptureMode.Standard else PSetCaptureMode.Classic)
    fun setCaptureStyle(wide: Boolean) =
        ble.send(Cmd.SET_CAPTURE_STYLE, if (wide) PSetCaptureStyle.Ratio16_9 else PSetCaptureStyle.Ratio4_3)
    fun setCaptureOrientation(vertical: Boolean) =
        ble.send(Cmd.SET_CAPTURE_DIRECTION,
            if (vertical) PSetCaptureDirection.Vertical else PSetCaptureDirection.Horizontal)

    // ---- wifi / AP des lunettes (import) ----
    /**
     * Ouvre l'AP des lunettes. Payload [01] : c'est CE QUI A ETE OBSERVE sur notre
     * paire E09-3AA1 (trame 0x39 payload=[01] relevee dans nos journaux), donc
     * c'est celui-la qui part. La doc bytecode documente 0x30=AP / 0x31=P2P pour
     * d'autres modeles : ces valeurs n'ont jamais ete observees chez nous, elles
     * restent dans Params.kt en commentaire, pas dans le chemin d'execution.
     */
    fun openWifiAp() = ble.send(Cmd.OPEN_WIFI, byteArrayOf(0x01))

    // ---- recuperation des medias (photos/vignettes) ----
    fun pullPhoto() = ble.send(Cmd.APP_PULL_PHOTO)
    fun pullThumbnailCount() = ble.send(Cmd.APP_PULL_THUMBNAIL_COUNT)
    fun pullThumbnailStatus() = ble.send(Cmd.PULL_THUMBNAIL_STATUS)

    // ---- infos / capacites ----
    fun getFirmware() = ble.send(Cmd.SEND_ISP_VERSION)
    fun getSupport() = ble.send(Cmd.GET_SUPPORT_FUNCTION)

    // ---- horloge / wifi ----
    fun syncTime(nowMs: Long = System.currentTimeMillis()) =
        ble.send(Cmd.TIME, GlassState.timePayload(nowMs))
    fun wifiConnect(ssid: String, password: String, epochSec: Long) =
        ble.send(Cmd.OPEN_WIFI, buildWifiPayload(ssid, password, epochSec))

    fun setMic(value: Int = PMicSetting.Value) = ble.send(Cmd.MIC_SETTING, value)
    fun setOfflineLanguage(english: Boolean) =
        ble.send(Cmd.OFFLINE_LANGUAGE, if (english) POfflineLanguage.En else POfflineLanguage.Zh)

    companion object {
        /**
         * Payload de OPEN_WIFI (0x39) : date, duree, ssid, mot de passe.
         * Le bytecode met epoch puis len=15 (0xF) ; les champs texte suivent.
         * ⚠ Agencement exact (tailles de champs) a confirmer par la capture.
         */
        fun buildWifiPayload(ssid: String, password: String, epochSec: Long, durationSec: Int = 0xF): ByteArray {
            val out = ArrayList<Byte>()
            out.add((epochSec shr 24 and 0xFF).toByte()); out.add((epochSec shr 16 and 0xFF).toByte())
            out.add((epochSec shr 8 and 0xFF).toByte()); out.add((epochSec and 0xFF).toByte())
            out.add((durationSec and 0xFF).toByte())
            ssid.forEach { out.add((it.code and 0xFF).toByte()) }
            password.forEach { out.add((it.code and 0xFF).toByte()) }
            return ByteArray(out.size) { out[it] }
        }
    }
}
