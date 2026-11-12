#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
commands.py - constructeurs de trames Eyevue (BLE/SPP).

Construit les octets a envoyer, conformes a Datagram.convertLeHeaderToBytes() :
    AB 55 | len_hi len_lo | cmd | payload | crc      (len = len(payload)+2)
CRC = somme(cmd+payload) & 0xFF.  Reverse interop - voir PROTOCOL.md.

Exemple :
    from commands import frame, build
    sock.sendall(build("TAKE_PHOTO", "THUMBNAIL"))   # trame complete
"""
from decode import COMMANDS, PARAMS

SOF_BLE_TX = bytes([0xAB, 0x55])
SOF_BLE_RX = bytes([0xAC, 0x55])
SOF_SPP = bytes([0x52, 0x58])

# --- commandes de pilotage (nom -> opcode) ---
CMD = {k: v for k, v in COMMANDS.items() if "/" not in v}
CMD_BY_NAME = {v: k for k, v in CMD.items()}
CMD_BY_NAME["PULL_THUMBNAIL_STATUS"] = 0x37

# --- alias pour les opcodes partages par deux semantiques ---
# 0x37 : ELECTROCHROMIC (verre qui fonce) et APP_PULL_THUMBNAIL_STATUS
# 0x97/0x98/0x99 : seq photo ET seq audio (selon le contexte de session)
ALIAS = {
    "ELECTROCHROMIC": 0x37,
    "GLASS_TINT": 0x37,
    "PHOTO_START": 0x97,
    "VOICE_START": 0x97,
    "PHOTO_TRANS": 0x98,
    "THUMBNAIL_START": 0x98,
    "PHOTO_END": 0x99,
    "VOICE_END": 0x99,
    "THUMBNAIL_COUNT": 0x42,
    "PULL_THUMBNAIL_COUNT": 0x40,
    "DEVICE_INFO": 0x55,
    "BATTERY": 0x53,
}


def crc(body: bytes) -> int:
    return sum(body) & 0xFF


def frame(cmd: int, payload=b"", sof=SOF_BLE_TX) -> bytes:
    """Assemble une trame complete."""
    payload = bytes(payload)
    ln = len(payload) + 2
    body = bytes([ln >> 8, ln & 0xFF, cmd & 0xFF]) + payload
    return sof + body + bytes([crc(body)])


def find_param(name: str, cmd_name: str = "") -> int:
    """Resolve un nom de parametre toleramment, en contexte de la commande.

    'THUMBNAIL' avec TAKE_PHOTO -> TAKE_PHOTO_THUMBNAIL (0x30)
    'HIGH'      avec BRIGHTNESS -> HIGH_BRIGHTNESS      (0x32)
    Un nom complet ('VOLUME_UP') est accepte tel quel. Erreur si ambigu.
    """
    n = name.strip().upper()
    if n in PARAMS:
        return PARAMS[n]
    cands = [k for k in PARAMS if k.endswith("_" + n) or k.startswith(n + "_")]
    if not cands:
        raise KeyError("parametre '%s' inconnu" % name)
    ctok = set(cmd_name.upper().replace("_", " ").split())
    ranked = sorted(cands, key=lambda k: -len(ctok & set(k.split("_"))))
    top = ranked[0]
    if len(ranked) > 1 and len(ctok & set(top.split("_"))) == len(ctok & set(ranked[1].split("_"))):
        raise KeyError("parametre '%s' ambigu pour %s : %s" % (name, cmd_name or "?", ", ".join(ranked[:4])))
    return PARAMS[top]


def encode_param(v: int) -> bytes:
    """Encode un parametre. RECORD_TIME_* depasse 255 (5MIN=0x12C, 10MIN=0x258)
    -> payload sur 2 octets big-endian. Les autres commandes observees ont un
    payload d'un octet ; la capture logcat confirme la taille reelle."""
    if 0 <= v <= 255:
        return bytes([v])
    return bytes([v >> 8, v & 0xFF])


def build(name, param=None, channel="ble", sof=None) -> bytes:
    """build('TAKE_PHOTO', 'THUMBNAIL') -> octets prets a envoyer."""
    cmd = ALIAS.get(name, CMD_BY_NAME.get(name))
    if cmd is None:
        raise KeyError("commande '%s' inconnue (%d disponibles)" % (name, len(CMD_BY_NAME) + len(ALIAS)))
    if param is None:
        payload = b""
    elif isinstance(param, (bytes, bytearray, list, tuple)):
        payload = bytes(param)
    elif isinstance(param, int):
        payload = encode_param(param)
    else:
        payload = encode_param(find_param(param, name))
    sof = sof or (SOF_SPP if channel == "spp" else SOF_BLE_TX)
    return frame(cmd, payload, sof)
