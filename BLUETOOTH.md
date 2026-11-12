# Couche Bluetooth des lunettes E09-3AA1 — référence pour modifier le projet

Ce fichier condense **tout ce qui a été trouvé côté Bluetooth**, avec pour chaque point la
**preuve** : bytecode lu, ou mesure logcat. Il est destiné à quelqu'un qui veut modifier le
projet, ou écrire sa propre app, sans repartir de zéro.

La spec complète (framing détaillé, table des commandes, params) est dans
[`protocol/PROTOCOL.md`](protocol/PROTOCOL.md). L'implémentation est dans
`eyevue-ctl/app/src/main/java/com/local/eyevuectl/` :

| Fichier | Rôle |
|---|---|
| `BleCtl.kt` | **GATT** : scan, connexion, écriture sur `aa13`, abonnements `aa14`, MTU, file d'envoi |
| `BleCtlShared.kt` | GATT **partagé** entre écrans (l'album et le visualiseur ne se volent pas la connexion) |
| `SppCtl.kt` | **SPP / Bluetooth Classic** : canal gros volume (photos, vidéo, audio, OTA) |
| `Frame.kt` | Construction et parse de la trame, CRC |
| `Cmd.kt`, `Params.kt` | **Tables générées** depuis le bytecode (68 constantes de commande, 17 objets de paramètres) par `protocol/gen_kotlin.py` |
| `Eyevue.kt` | API de haut niveau (`takePhoto`, `wifiConnect`, `setVolume`, …) |
| `GlassState.kt` | Décodage des rapports d'état |
| `Gesture.kt` | Décodage des trames tactiles |
| `MainActivity.kt` | Écran de pilotage + journal des trames |

---

## 1. Deux transports, et pourquoi deux

| Transport | Usage | Rôle réel |
|---|---|---|
| **BLE GATT** | commandes, état, événements, **vignettes** | pilotage |
| **SPP (Classic)** | photos pleines, vidéo, audio, fond d'écran, OTA | gros volume |
| **Wi-Fi AP** | **import et suppression des fichiers** | transfert de masse (PROTOCOL.md §7) |

Le BLE ne transfère **pas** les fichiers ; le Wi-Fi ne **pilote** pas l'appareil. Confondre
les deux est l'erreur la plus fréquente quand on part de zéro.

## 2. GATT : les UUID qui marchent, et le piège

```kotlin
serviceUuid = UUID.fromString("0000aa12-0000-1000-8000-00805F9B34FB")  // UUID_SERVICE
writeUuid   = UUID.fromString("0000aa13-0000-1000-8000-00805f9b34fb")  // UUID_CMD_WRITE
notifyUuid  = UUID.fromString("0000aa14-0000-1000-8000-00805f9b34fb")  // UUID_CMD_NOTIFY
cccdUuid    = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")  // CCCD
```

> **Le piège qui a coûté le plus cher.** Le bytecode contient aussi `fe010000-…`, qui
> *ressemble* à un service. Ce **n'est pas** `UUID_SERVICE` : écrire sur `fe010000` fait
> partir les commandes **dans le vide**, sans erreur et sans ack. La découverte de service ne
> renvoie rien d'exploitable et on conclut à tort que « le BLE ne répond pas ». La correction
> en `aa12` est ce qui a fait répondre les lunettes : découverte réussie, ack `crc=ok`,
> rapports.

`aa15` (4 caractéristique) : **rôle inconnu**, la lecture du bytecode s'arrête avant son
`sput-object`. Non résolu.

## 3. Framing

```
52 58 <cmd> <len…> <payload…> <crc…> 58 52
```

SOF/EOF = `52 58` / `58 52` (ASCII « RX »). CRC vérifié à l'émission et à la réception :
**un ack `crc=ok` dans le journal est la seule preuve que la commande a été comprise**.
`Frame.kt` construit et parse ; le journal de `MainActivity` nomme chaque commande
(`Cmd.nameOf`).

## 4. Appairage : ce que le BLE ne fait pas tout seul

- **BLE** : GATT standard, pas de pairing forcé pour les commandes.
- **SPP**, ordre observé dans le bytecode :
  1. appareil non appairé → `createBond()` ;
  2. déjà appairé → `fetchUuidsWithSdp()`, puis **la 1re UUID de la liste** ;
  3. côté serveur : `listenUsingRfcommWithServiceRecord(uuid)`.

`SppCtl.kt` implémente cet ordre. L'étape SDP est la plus sensible : changer l'ordre casse la
connexion Classic sans message d'erreur parlant.

## 5. Séquence de validation terrain (le test à refaire après toute modification)

Séquence **mesurée** qui prouve que la chaîne complète fonctionne :

```
0x22 TAKE_PHOTO
  -> ack crc=ok
  -> 0x45 ACTION_SYNC, payload[0] = 01      (la photo est declenchee)
  -> 0x42 = 1 vignette
```

Et la **rafale d'état à la connexion**, envoyée d'elles-mêmes par les lunettes, que le
décodeur doit encaisser sans exploser :
`0x16, 0x01, 0x02, 0x04, 0x06, 0x07-0x11, 0x61, 0x65, 0x17` — tous `crc=ok`.

Si tu touches à `Frame.kt`, `Cmd.kt` ou `BleCtl.kt`, **cette séquence est le test** :
bouton `Prendre une photo` → tu dois voir l'ack, puis `0x45`, puis la vignette.

## 6. Masque de capacités `0x95` : le garde-fou contre les faux boutons

`GET_SUPPORT_FUNCTION 0x95` renvoie **4 octets gros endien**, bits 0-14. L'app lit le masque,
l'affiche dans les réglages, et s'en sert pour distinguer :

> **« bouton cassé »** de **« fonction absente sur ce modèle »**.

Masque mesuré sur E09-3AA1 : `0x0000040A`. **Les bits électrochromes ne sont pas présents**,
et 30 s de gestes sur la branche n'ont produit **aucune trame BLE** : l'électrochrome de ce
modèle n'est **pas pilotable par BLE**. C'est pour cela que la carte « Opacité des verres »
(fonce / clair / nuance) a été **retirée de l'app**, plutôt que laissée à afficher du faux.

Leçon pour un contributeur : **avant d'ajouter un bouton, lis `0x95`**. Un bouton dont la
capacité est absente n'échouera pas bruyamment — il n'enverra rien.

## 7. Batterie : deux rapports qui sont des NIVEAUX ASCII, pas des volts

`0x53` et `0x17` sont **tous deux des niveaux en ASCII** : `[chiffre][chiffre][drapeau charge]`.

| `0x17` lu | Décodage | `0x53` |
|---|---|---|
| `[39 38 00]` | `'9' '8'` → **98** | 97 % |
| `[39 36 00]` | `'9' '6'` → **96** | 96 % |
| `[39 39 01]` | `'9' '9'` + charge → **99** | 100 % |
| `[38 31 00]` | `'8' '1'` → **81** | — |
| `[36 38]` | `'6' '8'` → **68** | — |

> Il n'existe **aucune tension** dans `0x17`. Une version précédente de ce projet y décodait
> une « tension » : elle sortait **14 393 mV** et **13 881 mV** à 97 % et 96 % de charge,
> physiquement impossibles. Le décodage a été **supprimé** (`voltageMv`, `parseVoltage`,
> `voltageToPct`, courbe 3S). L'app lit `0x17` comme un niveau ASCII (`parseLevel0x17`),
> affiche `0x53` en priorité et `0x17` en repli **étiqueté `(0x17)`**, et n'affiche **aucun
> volt, aucune valeur « estimée »**.
>
> Un payload **non ASCII** comme `[3A 30 01]` (ASCII « :0 ») est journalisé
> **« non décodé »** au lieu de recevoir une valeur inventée.

Ajouter une estimation d'autonomie est légitime ; la **présenter comme une mesure** ne l'est pas.

## 8. Événements entrants

- `0x45 ACTION_SYNC` : événements, dont `payload[0]=01` = photo déclenchée.
- `0x42` : **vignette** (petite image sur BLE) → `Etat vignette` / `Nb vignettes` à l'écran.
- Trames tactiles : décodées dans `Gesture.kt`, affichées en clair dans le journal.

## 9. Générer les tables, ne pas les taper à la main

`Cmd.kt` et `Params.kt` sont **générés** par `protocol/gen_kotlin.py` depuis
`protocol/protocol.json`. Ne pas les éditer à la main : modifier `protocol.json` et régénérer.

Contrôle bas niveau fourni :

```bash
cd eyevue-ctl
python tools/check_refs.py     # exit 0 = toute Cmd.X / PObjet.x citée existe bien
```

Il lit les fichiers générés, ignore commentaires et chaînes, vérifie la résolution des
symboles. Sa sortie normale est
`objets params : 17 | constantes Cmd : 68 | attendus JSON : 68 | references verifiees : 84`.
Il existe parce qu'avec 68 commandes, une faute de frappe dans un `cmd` se traduit par un
**silence radio**, pas par une erreur.

## 10. Journal : l'outil de diagnostic principal

```bash
adb logcat -s EyevueCtl:V EyevueImport:V EyevueMedia:V
```

`protocol/capture.bat` et `protocol/decode.py` captent et décodent les trames de l'app du
constructeur (CSV + timeline) : c'est ainsi que les tables ont été remplies, **sans
sniffeur BLE** — l'app logge elle-même ses trames en hexadécimal (PROTOCOL.md §5).

## 11. Ce qui n'est PAS résolu (à ne pas présenter comme acquis)

- `aa15` : rôle inconnu.
- Base réseau **gagnante pour la suppression** : déduite du succès du téléchargement, **non mesurée** pour `cmd=4003`.
- Forme d'URL de suppression réellement acceptée : inconnue (les variantes sont testées en cascade, le journal dit laquelle agit).
- Électrochrome : non pilotable par BLE sur ce modèle, mécanisme réel non identifié.
- Échantillon `(0x17, 0x53)` à niveau **intermédiaire** : manquant pour confirmer la linéarité sur toute la plage.
