#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
decode.py - decodeur des trames Eyevue extraites de logcat.

Source des formats : reverse-engineering (usage interop, Art. 6 Dir. 2009/24/CE)
du module com.eyevue.glassapp.bluetooth.{protocol,manager} d'un APK possede.

Framing BLE  (Datagram.convertLeHeaderToBytes) :
    AB 55 | len_hi len_lo | cmd | payload... | crc      (app -> lunettes)
    AC 55 | ... idem                                    (lunettes -> app)
    len = len(payload) + 2
Framing SPP  (SOF 52 58 "RX", EOF 58 52 "XR", meme layout) :
    52 58 | len_hi len_lo | cmd | payload... | crc
CRC : somme simple & 0xFF. Perimetre exact calibre automatiquement.

Usage :
    python decode.py capture_XXXX.log
    python decode.py --selftest
Sorties : *_frames.csv + *_timeline.md a cote du .log, resume sur stdout.
"""
import csv
import os
import re
import sys

COMMANDS = {
    0x01: "BRIGHTNESS", 0x02: "RECORD_TIME", 0x03: "MIC_SETTING", 0x04: "WEAR_DETECT",
    0x06: "VOICE_COMMAND", 0x07: "GESTURE_SWIPE_FRONT", 0x08: "GESTURE_SWIPE_BACK",
    0x09: "GESTURE_CLICK", 0x0A: "DEVICE_PHOTO_RECOGNITION", 0x10: "GESTURE_DOUBLE_CLICK",
    0x11: "GESTURE_TRIPLE_CLICK", 0x14: "RECOVER_FACTORY", 0x15: "CLEAR_DEVICE_CONNECTION_RECORD",
    0x16: "GET_CAPACITY", 0x17: "GET_BATTERY", 0x22: "TAKE_PHOTO", 0x23: "RECORD_VIDEO",
    0x24: "STOP_RECORD", 0x25: "APP_RECEIVE_WIFI_INFO", 0x30: "SWITCH_MUSIC",
    0x31: "CONTROL_MUSIC", 0x32: "AI_SET_VOLUME", 0x33: "CONTROL_CALL", 0x34: "RECORD_AUDIO",
    0x35: "SET_CAPTURE_STYLE", 0x36: "APP_PULL_PHOTO", 0x37: "PULL_THUMBNAIL_STATUS/ELECTROCHROMIC",
    0x39: "OPEN_WIFI", 0x40: "APP_PULL_THUMBNAIL_COUNT", 0x42: "APP_RECEIVE_THUMBNAIL_COUNT",
    0x43: "GESTURE_RECOVER", 0x44: "FILE_DOWNLOAD_FINISH", 0x45: "ACTION_SYNC",
    0x46: "APP_RECEIVE_VOICE_DATA", 0x48: "APP_GET_DEVICE_STATUS",
    0x49: "APP_CANCEL_VOICE_RECOGNITION", 0x50: "SET_CAPTURE_MODE", 0x51: "APP_CANCEL_AI_VOICE",
    0x53: "APP_RECEIVE_BATTERY", 0x54: "ISP_WORK_STATE", 0x55: "APP_RECEIVE_DEVICE_INFO",
    0x56: "APP_STOP_VOICE_RECOGNITION", 0x57: "APP_START_VOICE_RECOGNITION",
    0x59: "TIME", 0x61: "SET_CAPTURE_DIRECTION", 0x62: "OFFLINE_LANGUAGE",
    0x63: "WIFI_OTA", 0x64: "CUSTOMER_PID_VID", 0x65: "SEND_ISP_VERSION",
    0x66: "GET_SUPPORT_LIVE", 0x67: "APP_LIVE", 0x68: "DEVICE_CONNECT_A2DP",
    0x69: "APP_RECEIVE_SOUND", 0x70: "APP_SET_VOLUME", 0x71: "GET_VOICE_ASSISTANT_STATUS",
    0x72: "SET_VOICE_ASSISTANT_STATUS", 0x73: "SET_EQ", 0x95: "GET_SUPPORT_FUNCTION",
    0x96: "WIFI_OTA_RESULT", 0x97: "PHOTO_START/VOICE_START", 0x98: "PHOTO_TRANS/THUMB_START",
    0x99: "PHOTO_END/VOICE_END", 0x9A: "GET_EQ", 0x9B: "APP_BIND_CONFIRM",
}

PARAMS = {
    "ACTION_HEAD_SHAKE": 1, "ACTION_SYNC_ACTION": 1, "ACTION_SYNC_HEAD_NOD": 1,
    "ACTION_SYNC_MUSIC_PAUSE": 0, "ACTION_SYNC_MUSIC_PLAY": 1, "ACTION_SYNC_NOT_START_PHOTO": 0,
    "ACTION_SYNC_NOT_START_RECORD": 0, "ACTION_SYNC_NO_ACTION": 0, "ACTION_SYNC_START_PHOTO": 1,
    "ACTION_SYNC_START_RECORD": 1, "ACTION_SYNC_VOLUME_BIG": 1, "ACTION_SYNC_VOLUME_LITTLE": 1,
    "ACTION_SYNC_WEAR_OFF": 0, "ACTION_SYNC_WEAR_ON": 1, "AI_VOICE_CANCEL": 1,
    "BATTERY_CAPACITY": 0, "CAMERA_HORIZONTAL": 0x31, "CAMERA_VERTICAL": 0x30,
    "CAPTURE_MODE_CLASSIC": 0x31, "CAPTURE_MODE_STANDARD": 0x30, "CAPTURE_STYLE_1080P": 0x31,
    "CAPTURE_STYLE_16_9": 0x30, "CAPTURE_STYLE_2K": 0x30, "CAPTURE_STYLE_4_3": 0x31,
    "CLEAR_DEVICE_CONNECTION_RECORD": 0, "CONTROL_CALL_ANSWER": 1, "CONTROL_CALL_HANGUP": 0,
    "CONTROL_MUSIC_PAUSE": 0, "CONTROL_MUSIC_PLAY": 1, "DEVICE_CAPACITY": 0,
    "ELECTROCHROMIC_OFF": 0, "ELECTROCHROMIC_ON": 1, "EQ_CLASSICAL": 3, "EQ_CUSTOM": 0xFF,
    "EQ_JAZZ": 4, "EQ_POP": 2, "EQ_ROCK": 1, "EQ_STANDARD": 0, "FILE_DOWNLOAD_FINISH": 0,
    "GET_DEVICE_STATUS": 0, "GET_WIFI_AP": 0x30, "GET_WIFI_P2P": 0x31, "HIGH_BRIGHTNESS": 0x32,
    "ISP_IMPORTING": 3, "ISP_TAKE_AUDIO": 2, "ISP_TAKE_PHOTO": 0, "ISP_TAKE_VIDEO": 1,
    "LIVE_TYPE_AP": 0x30, "LIVE_TYPE_P2P": 0x31, "LOW_BRIGHTNESS": 0x30, "MIC_SETTING": 0,
    "MID_BRIGHTNESS": 0x31, "MUSIC_NEXT": 0x34, "MUSIC_PREV": 0x33, "OFFLINE_LANGUAGE_EN": 1,
    "OFFLINE_LANGUAGE_ZH": 0, "PHOTO_HIGH_QUALITY": 1, "PHOTO_THUMBNAIL": 0,
    "PULL_PHOTO_FAIL": 0, "PULL_PHOTO_SUCCESS": 1, "RECORD_AUDIO_END": 0, "RECORD_AUDIO_START": 1,
    "RECORD_TIME_10MIN": 0x258, "RECORD_TIME_1MIN": 0x3C, "RECORD_TIME_3MIN": 0xB4,
    "RECORD_TIME_5MIN": 0x12C, "RECORD_TIME_7MIN": 0x1A4, "RECORD_VIDEO": 0,
    "RECOVERY_FACTORY": 0, "SOUND_CALL": 2, "SOUND_MEDIA": 1, "SOUND_SYSTEM": 0,
    "SWITCH_MUSIC_NEXT": 1, "SWITCH_MUSIC_PREV": 0, "TAKE_PHOTO_HIGH_QUALITY": 0x31,
    "TAKE_PHOTO_THUMBNAIL": 0x30, "THUMBNAIL_NUMBS": 0, "VOICE_COMMAND_OFF": 0x30,
    "VOICE_COMMAND_ON": 0x31, "VOLUME_BIG": 0x31, "VOLUME_DOWN": 0,
    "VOLUME_PAUSE_PLAY_ANSWER_HANGUP": 0x32, "VOLUME_SMALL": 0x30, "VOLUME_UP": 1,
    "WEAR_DETECT_OFF": 0x30, "WEAR_DETECT_ON": 0x31, "WIFI_FILE_DOWNLOAD_CLEAR_THUMBNAIL": 0,
    "WIFI_FILE_DOWNLOAD_FINISH": 0x30, "WIFI_FILE_DOWNLOAD_FINISH_NUN": 0x31,
    "WIFI_FILE_DOWNLOAD_NOT_CLEAR": 1, "WIFI_OTA_AP": 0x30, "WIFI_OTA_FAIL": 0,
    "WIFI_OTA_P2P": 0x31, "WIFI_OTA_SUCCESS": 1,
}

PARAM_REV = {}
for _k, _v in PARAMS.items():
    PARAM_REV.setdefault(_v & 0xFFFF, []).append(_k)

SOF_BLE_TX = (0xAB, 0x55)
SOF_BLE_RX = (0xAC, 0x55)
SOF_SPP = (0x52, 0x58)

LOGCAT_TS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)")
HEX_RUN = re.compile(r"(?<![0-9A-Fa-f])((?:[0-9A-F]{2} ){2,}[0-9A-F]{2})(?![0-9A-Fa-f])")
DEC_ARR = re.compile(r"\[(-?\d{1,3}(?:,\s*-?\d{1,3})+)\]")
INTEREST = re.compile(
    r"(sendData: |data=|onCharacteristic|onConnectionStateChange|onMtuChanged"
    r"|ReceiveSppDataThread|SendSppDataThread|SppCore|BleCore|LeHeader)",
    re.IGNORECASE)


def runs_of(line):
    out = []
    for m in HEX_RUN.finditer(line):
        toks = m.group(1).split()
        if len(toks) >= 3:
            out.append([int(t, 16) for t in toks])
    for m in DEC_ARR.finditer(line):
        vals = [int(t) & 0xFF for t in m.group(1).split(",")]
        if len(vals) >= 3:
            out.append(vals)
    return out


def crc_candidates(frame, crc_idx, pay_hi):
    body = frame[4:pay_hi]
    body_l = frame[2:pay_hi]
    def s(seq): return sum(seq) & 0xFF
    def x(seq):
        r = 0
        for b in seq:
            r ^= b
        return r
    return {
        "sum(cmd+payload)": s(body), "sum(len+cmd+payload)": s(body_l),
        "xor(cmd+payload)": x(body), "xor(len+cmd+payload)": x(body_l),
        "sum(cmd)": s(frame[4:5]),
    }


def pick_param(val, cmd_name):
    """Nom(s) de parametre pour un octet. Un meme octet a souvent plusieurs
    sens (0x31 = VOLUME_BIG, CAMERA_HORIZONTAL, HIGH_BRIGHTNESS...). On fait
    remonter en premier ceux qui partagent un mot avec le nom de la commande."""
    cands = PARAM_REV.get(val & 0xFF, [])
    if not cands:
        return ""
    ctok = set(cmd_name.upper().replace("/", " ").replace("_", " ").split())
    ranked = sorted(cands, key=lambda n: -len(ctok & set(n.split("_"))))
    shown = ranked[:4]
    more = "+%d" % (len(ranked) - len(shown)) if len(ranked) > len(shown) else ""
    return "|".join(shown) + more


def parse_frame(b):
    if len(b) < 6:
        return None
    head = tuple(b[:2])
    if head == SOF_BLE_TX:
        ch, d = "BLE", "TX"
    elif head == SOF_BLE_RX:
        ch, d = "BLE", "RX"
    elif head == SOF_SPP:
        ch, d = "SPP", "??"
    else:
        return None
    ln = (b[2] << 8) | b[3]
    cmd = b[4]
    partial = ln < 2 or len(b) < ln + 4
    pay = b[5:5 + max(0, ln - 2)] if not partial else b[5:]
    crc = None if partial else b[ln + 3]
    crcok, scope = ("partiel" if partial else "incomplet"), None
    if not partial:
        cands = crc_candidates(b, ln + 3, 5 + len(pay))
        for k, v in cands.items():
            if v == crc:
                crcok, scope = "ok", k
                break
        else:
            crcok = "A_RECALIBRER"
    pname = pick_param(pay[0], COMMANDS.get(cmd, "")) if len(pay) == 1 else ""
    return {"ch": ch, "dir": d, "len": ln, "cmd": cmd,
            "name": COMMANDS.get(cmd, "inconnu_0x%02X" % cmd),
            "payload": " ".join("%02X" % x for x in pay), "param": pname,
            "crc": crcok, "crc_scope": scope,
            "raw": " ".join("%02X" % x for x in b), "partial": partial}


def decode_file(path):
    frames, events, ts = [], [], ""
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if not INTEREST.search(line):
                continue
            m = LOGCAT_TS.match(line)
            if m:
                ts = m.group(1)
            for run in runs_of(line):
                fr = parse_frame(run)
                if fr:
                    fr["ts"] = ts
                    frames.append(fr)
            low = line.lower()
            if "onconnectionstatechange" in low or "onmtuchanged" in low:
                events.append((ts, line.strip()[:200]))
    return frames, events


def report(path, frames, events):
    base = os.path.splitext(path)[0]
    with open(base + "_frames.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["ts", "canal", "dir", "cmd_hex", "commande", "payload_hex",
                    "param", "crc", "brut"])
        for fr in frames:
            w.writerow([fr["ts"], fr["ch"], fr["dir"], "0x%02X" % fr["cmd"], fr["name"],
                        fr["payload"], fr["param"], fr["crc"], fr["raw"]])
    with open(base + "_timeline.md", "w", encoding="utf-8") as f:
        f.write("# Timeline BLE/SPP\n\n## Connexions / MTU\n")
        for ts, e in events:
            f.write("- `%s` %s\n" % (ts, e))
        f.write("\n## Trames (%d)\n\n| ts | canal | dir | cmd | commande | payload | param | crc |\n"
                "|---|---|---|---|---|---|---|---|\n" % len(frames))
        for fr in frames:
            f.write("| %s | %s | %s | 0x%02X | **%s** | `%s` | %s | %s |\n" % (
                fr["ts"], fr["ch"], fr["dir"], fr["cmd"], fr["name"],
                fr["payload"], fr["param"], fr["crc"]))
    print("trames decodees : %d" % len(frames))
    scopes = {}
    for fr in frames:
        if fr["crc_scope"]:
            scopes[fr["crc_scope"]] = scopes.get(fr["crc_scope"], 0) + 1
    if scopes:
        print("CRC calibre  : %s" % max(scopes, key=scopes.get))
    seen = {}
    for fr in frames:
        seen[fr["name"]] = seen.get(fr["name"], 0) + 1
    for n, c in sorted(seen.items(), key=lambda kv: -kv[1]):
        print("  %-42s x%d" % (n, c))
    print("\n-> %s_frames.csv\n-> %s_timeline.md" % (base, base))


SELFTEST = """11-11 10:00:01.100  1234 1240 D class com.eyevue.glassapp.bluetooth.manager.BleCore(1234): sendData: AB 55 00 03 70 31 A1
11-11 10:00:02.200  1234 1240 I class com.eyevue.glassapp.bluetooth.manager.BleCore(1234): data= AC 55 00 03 53 64 B7
11-11 10:00:03.300  1234 1240 D class com.eyevue.glassapp.bluetooth.manager.BleCore(1234): sendData: AB 55 00 02 22 22
11-11 10:00:04.000  1234 1240 I class com.eyevue.glassapp.bluetooth.manager.BleCore(1234): onMtuChanged: mtu=517
11-11 10:00:05.000  1234 1240 D class com.eyevue.glassapp.bluetooth.manager.BleCore(1234): sendData: AB 55 00 03 73 00 73
"""


def selftest():
    p = "selftest_capture.log"
    open(p, "w", encoding="utf-8").write(SELFTEST)
    frames, events = decode_file(p)
    assert len(frames) == 4, len(frames)
    assert frames[0]["name"] == "APP_SET_VOLUME" and "VOLUME" in frames[0]["param"], frames[0]
    assert frames[0]["crc"] == "ok", frames[0]
    assert frames[1]["name"] == "APP_RECEIVE_BATTERY" and frames[1]["dir"] == "RX", frames[1]
    assert frames[2]["name"] == "TAKE_PHOTO", frames[2]
    assert frames[3]["name"] == "SET_EQ", frames[3]
    report(p, frames, events)
    print("SELFTEST OK")


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--selftest":
        selftest()
    elif len(sys.argv) >= 2:
        fr, ev = decode_file(sys.argv[1])
        report(sys.argv[1], fr, ev)
    else:
        print(__doc__)
