# Protocole de communication lunettes (reverse interop)

> **Cadre légal.** Ce document est produit dans le seul but d'assurer l'**interopérabilité** d'un logiciel indépendant avec le matériel, ce que l'**article 6 de la Directive 2009/24/CE** autorise explicitement (et la jurisprudence *SEGA v. Accolade* / *Sony v. Connectix* hors UE).
> Les tables ci-dessous ont été **relevées depuis le bytecode** (`com.eyevue.glassapp.bluetooth.protocol.*`) d'un APK que nous possédons. Aucune donnée n'a été obtenue par effraction : il n'y a **ni chiffrement, ni clé, ni signature** dans la couche BLE (vérifié : aucune occurrence de `Cipher`, `SecretKeySpec`, `MessageDigest` dans le module).
> **Ne pas** réutiliser le nom ni le logo de la marque dans une app dérivée.

## 1. Transports

| Canal | Service | Caractéristiques | Usage |
|---|---|---|---|
| **BLE** | `fe010000-1234-5678-abcd-00805f9b34fb` | écriture + notification | commandes / statut |
| UART Nordic (standard) | — | `AA12` service · `AA13` **write** · `AA14` **notify** · `AA15` MTU | très probablement le profil réel utilisé |
| **SPP** (Bluetooth Classic) | UUID `00001101` (SPP standard) | socket RFCOMM | **gros volumes** : photos, audio, vidéo, OTA |
| Profils audio standard | HFP `111e` · AVRCP `110b` | — | les lunettes sont aussi un casque BT |

> Le **BLE est lent** (~20 ko/s max avec MTU 517). Tout ce qui est multimédia passe donc par le **SPP**. C'est prévisible : le code contient `SppCore`, `SendSppDataThread`, `ReceiveSppDataThread`, `ConnectionSppThread` *en plus* de `BleCore`.

## 2. Format de trame

Relevé dans `Datagram.convertLeHeaderToBytes()` / `convertSppHeaderToBytes()`.

```
BLE  (app -> lunettes) : AB 55 | len_hi len_lo | cmd | payload ... | crc
BLE  (lunettes -> app) : AC 55 | len_hi len_lo | cmd | payload ... | crc
SPP                    : 52 58 | len_hi len_lo | cmd | payload ... | crc   (SOF 'RX')
                         fin de fichier SPP : 58 52                         (EOF 'XR')
```

- `len` = **16 bits big-endian**, et `len = len(payload) + 2` (démontré dans `LeHeader.setPayload` / `SppHeader.setPayload`).
- `cmd` = **1 octet**, table §3.
- `crc` = **somme arithmétique simple des octets, masquée `& 0xFF`** (`Datagram.getBytesCrc()` : boucle `add-int`, puis `and-int/lit16 0xff`). **Pas** de CRC-16.
  - **Périmètre exact** : `getBytesCrc()` appelle `convertHeaderToCrcBytes()`, qui dans le bytecode ne lit que `commandId`. Mais la version compilée ajoute manifestement le payload (sinon les trames loggées seraient invalides). Le script `decode.py` **calibre le périmètre automatiquement** sur les trames capturées. Attendu : `somme(cmd + payload)`.

### Trame de contrôle (debug interne)
`LeHeader.toString()` produit `LeHeader{\nlen=..\n, commandId=0x..\n, payload=[..]\n, crc=0x..\n}`. Ces lignes sont une **mine d'or** : le nom du champ est loggé à côté de la valeur, ce qui permet de valider notre décodeur sans supposition.

## 3. Table des commandes (`protocol/Command.smali`)

### Commandes applicables — les plus utiles pour une app tierce

| `cmd` | Nom | Param (`Param`) |
|---|---|---|
| `0x22` | **TAKE_PHOTO** | `30` vignette · `31` haute qualité |
| `0x36` | **APP_PULL_PHOTO** (récupérer la photo) | `0` échec · `1` succès |
| `0x97 / 0x98 / 0x99` | PHOTO_START / PHOTO_TRANS / PHOTO_END | — |
| `0x37` | PULL_THUMBNAIL_STATUS *(aussi `ELECTROCHROMIC`)* | `0` noir · `1` transparent |
| `0x40 / 0x42` | PULL_THUMBNAIL_COUNT / RECEIVE_THUMBNAIL_COUNT | — |
| `0x70` | **APP_SET_VOLUME** | `30` bas · `31` haut |
| `0x32` | AI_SET_VOLUME | — |
| `0x01` | **BRIGHTNESS** | `30` bas · `31` moyen · `32` haut |
| `0x73` | SET_EQ | `0` normal · `1` rock · `2` pop · `3` classique · `4` jazz · `FF` custom |
| `0x30` | SWITCH_MUSIC | `0` préc. · `1` suiv. |
| `0x31` | CONTROL_MUSIC | `0` pause · `1` lecture |
| `0x33` | CONTROL_CALL | `0` raccrocher · `1` découcher |
| `0x34` | RECORD_AUDIO | `0` stop · `1` start |
| `0x23 / 0x24` | RECORD_VIDEO / STOP_RECORD | — |
| `0x50` | SET_CAPTURE_MODE | `30` standard · `31` classique |
| `0x35` | SET_CAPTURE_STYLE | `30` 16:9/2K · `31` 4:3/1080p |
| `0x61` | SET_CAPTURE_DIRECTION | `30` vertical · `31` horizontal |
| `0x57 / 0x56 / 0x49` | START / STOP / CANCEL VOICE_RECOGNITION | — |
| `0x51` | CANCEL_AI_VOICE | `1` |
| `0x72` | SET_VOICE_ASSISTANT_STATUS | on/off |
| `0x48` | GET_DEVICE_STATUS | `0` |
| `0x17 / 0x16` | GET_BATTERY / GET_CAPACITY | — |
| `0x95` | GET_SUPPORT_FUNCTION | — |
| `0x9A / 0x71` | GET_EQ / GET_VOICE_ASSISTANT_STATUS | — |
| `0x04` | WEAR_DETECT | `30` off · `31` on |
| `0x06` | VOICE_COMMAND | `30` off · `31` on |
| `0x03` | MIC_SETTING | `0` |
| `0x62` | OFFLINE_LANGUAGE | `0` zh · `1` en |
| `0x59` | TIME (horloge) | — |
| `0x02` | RECORD_TIME | `3C` 1min · `B4` 3 · `12C` 5 · `1A4` 7 · `258` 10 |
| `0x25 / 0x39 / 0x67` | WIFI_INFO / OPEN_WIFI / LIVE | `30` AP · `31` P2P |
| `0x63 / 0x96` | WIFI_OTA / WIFI_OTA_RESULT | `0` échec · `1` succès |
| `0x14` | RECOVER_FACTORY ⚠️ | `0` — **destructif** |
| `0x15` | CLEAR_DEVICE_CONNECTION_RECORD ⚠️ | `0` |

### Rapports envoyés par les lunettes (à écouter)

| `cmd` | Nom |
|---|---|
| `0x53` | RECEIVE_BATTERY |
| `0x55` | RECEIVE_DEVICE_INFO |
| `0x45` | **ACTION_SYNC** — événements gestuels |
| `0x07 / 0x08 / 0x09` | gestes : swipe avant / arrière / clic |
| `0x10 / 0x11` | double-clic / triple-clic |
| `0x43` | GESTURE_RECOVER |
| `0x46 / 0x97 / 0x99` | RECEIVE_VOICE_DATA (+start/end) |
| `0x54` | ISP_WORK_STATE (`0` photo · `1` vidéo · `2` audio · `3` import) |
| `0x69` | RECEIVE_SOUND (`0` système · `1` média · `2` appel) |
| `0x65` | SEND_ISP_VERSION |
| `0x9B` | **APP_BIND_CONFIRM** ← voir §4 |

### Codes `ACTION_SYNC` (`0x45`) — événements entrants

```
0x01 start photo / nod / head-shake / play / wear-on / volume+
0x00 no action / pause / wear-off / not-started
```

Le **hochement de tête** (`ACTION_HEAD_SHAKE`) est donc une commande native : une app tierce peut y brancher « accepter la traduction », ce que l'app d'origine n'exploite pas.

## 4. Appairage — point crucial

Un message loggé dans le code révèle la séquence :

> `等待设备0x9B data 0x01确认，暂不通知连接成功、不创建配对`
> *« en attente de la confirmation appareil `0x9B data 0x01` ; ne pas signaler la connexion comme réussie, ne pas créer le pairing »* (**BleCore**)

Autrement dit, le couplage BLE effectif est conditionné par l'échange `APP_BIND_CONFIRM (0x9B) / payload 0x01` **entre l'app et les lunettes, en local** — le nom de la classe qui pilote cela (`BleCore`) est dans le module *applicatif*, pas réseau. La capture de logcat §6 doit confirmer si ce handshake est indépendant du serveur constructeur. **C'est LE résultat à valider en priorité** : s'il est local, une app tierce peut piloter les lunettes sans jamais toucher à leur cloud.

## 5. Logcat : où sont les trames

`BleCore` logge **lui-même** chaque envoi et chaque réception, en hexadécimal (`bytesToHex` = `"%02X "`, majuscules, séparateur espace unique) :

| Émission | Format de ligne |
|---|---|
| envoi BLE | `<TAG>: sendData: AB 55 00 03 70 31 <crc>` |
| réception BLE | `<TAG>: data= ...` |
| état connexion | `<TAG>: onConnectionStateChange ...` |
| MTU négocié | `<TAG>: onMtuChanged: mtu=517` |

**Le `<TAG>` est le nom de classe complet** (il provient de `Class.toString()`) :

```
class com.eyevue.glassapp.bluetooth.manager.BleCore
```

Comme il contient des espaces, le filtrage `logcat TAG:V` **ne fonctionne pas** : il faut capturer globalement et filtrer côté script (`decode.py` le fait déjà).

## 6. Atelier de capture

Voir [README.md](README.md). En résumé :

```bat
capture.bat        :: vide le buffer, lance logcat, a toi de piloter l'app
                     :: puis Ctrl+C pour arreter
python decode.py captures\capture_XXXX.log
```

Sorties : `*_frames.csv` (toutes les trames décodées) + `*_timeline.md` (chronologie lisible).


---

## Ajout vérifié sur le terrain — capacités, electrochrome, tactile, batterie

### Masque de capacités : réponse à `0x95 GET_SUPPORT_FUNCTION`

La commande est envoyée par `configSendCommand(0x95, 0x00)`. La réponse est un **écho
du même code `0x95`** avec un **payload de 4 octets, GROS ENDIEN** (`DeviceCapabilityParser.parseCapabilities`
lit `b0<<24 | b1<<16 | b2<<8 | b3`, et exige exactement 4 octets — sinon
`IllegalArgumentException`).

Relevé sur la paire `E09-3AA1` (MAC `C2:01:06:00:3A:A1`) :

```
> GET_SUPPORT_FUNCTION param=0x00  trame = AB 55 00 03 95 00 95
< cmd=0x95 payload=[00 00 04 0A] crc=ok      ->  masque 0x0000040A  = bits 1, 3, 10
```

Table des 15 bits (`DeviceCapability`, bitPosition 0..14), avec l'état de cette paire :

| bit | fonction | E09-3AA1 |
|----:|---|---|
| 0 | diffusion en direct (live) | — |
| **1** | réglage rapide du volume | **✓** |
| 2 | filigrane photo | — |
| **3** | détection de port (dynamique) | **✓** |
| 4 | détection de port | — |
| 5 | stabilisation (dynamique) | — |
| 6 | stabilisation | — |
| 7 | mot d'éveil IA | — |
| 8 | voix hors-ligne locale | — |
| 9 | changement de langue dynamique | — |
| **10** | orientation d'écran (dynamique) | **✓** |
| 11 | orientation d'écran | — |
| 12 | bouton de réinitialisation d'usine distinct | — |
| 13 | confirmation d'appairage | — |
| 14 | réglage EQ par l'app | — |

Le parseur imprime `发现未定义的功能位: <bit>` (« bit de fonction non défini ») pour tout bit
situé au-delà de 14. **Rien de tel ne s'est imprimé** : la table est complète, aucun bit caché
au-delà du bit 14.

Ce masque explique aussi deux fonctions proposées par le SDK et absentes sur ce modèle :
`supportEqAdjust` (bit 14) et `supportLive` (bit 0) valent 0.

### L'électrochrome n'est pas pilotable par BLE sur ce modèle — il est tactile

Conclusion à trois preuves convergentes :

1. **Aucun bit** de la table `DeviceCapability` ne déclare le verre électrochrome, et le masque
   réel de la paire n'a aucun bit au-delà de 14 : la fonction n'est pas négociable, elle ne peut
   pas être masquée par un bit « réservé ».
2. La trame envoyée est **byte-identique** à celle de l'app d'origine : `setElectrochromicOn/Off`
   passe par `configSendCommand(0x37, 0x01|0x00)`, `CommonBean.convertBytes()` produit
   **1 octet**, et `LeHeader` ne porte que `commandId`/`len`/`crc`. Le framing n'est donc pas en cause.
3. **Observation de l'utilisateur** : sur ces lunettes, le changement de teinte se fait **au
   tactile de la branche**, donc localement dans le firmware. Consistance vérifiée par le test
   terrain : pendant une fenêtre de 30 s de gestes sur la branche, **aucune trame BLE entrante**
   (journal `EyevueCtl` vide de `cmd=`) alors que la liaison était `CONNECTED` — le geste agit
   dans le firmware et ne transite pas par le téléphone.

Les commandes `0x07`/`0x08`/`0x09`/`0x10`/`0x11` (`GESTURE_SWIPE_FRONT`, `GESTURE_SWIPE_BACK`,
`GESTURE_CLICK`, `GESTURE_DOUBLE_CLICK`, `GESTURE_TRIPLE_CLICK`) **ne sont jamais envoyées par
l'app** : zéro occurrence dans `SendCommandViaBle`. Elles sont donc **device → app** (notifications
de gestes), ce qui confirme le sens des noms et explique leur présence dans la rafale d'état.

`0x37` garde sa double appellation confirmée par le bytecode : `ELECTROCHROMIC` **et**
`APP_PULL_THUMBNAIL_STATUS`, même octet.

### Batterie : `0x53` et `0x17` sont tous deux des NIVEAUX en ASCII — la « tension » de `0x17` n'existe pas

Résolu par les trames réelles, qui contredisaient l'ordre d'octets supposé au départ :

- `0x53 APP_RECEIVE_BATTERY` = `[drapeaux][niveau]` : `[00 64]` → 100 %, pas en charge ;
  `[01 64]` → 100 %, **en charge** (bit 0 des drapeaux). Lu dans le mauvais ordre, le premier
  octet (0 ou 1) donnait l'affichage erroné « 1 % ».
- `0x17 GET_BATTERY` = 3 octets, **payload ASCII** `[chiffre][chiffre][drapeau de charge]`.

**La tension a été retirée : elle était fabriquée.** La lecture « 16 bits petit boutien en
millivolts » de `0x17` a été implantée, **affichée à l'écran** (« 13,88 V », « 12,35 V »,
« 7,12 V ») et justifiée par une courbe « Li-ion 3S » (12,60 V pleine → 10,80 V vide) servant
d'estimation de pourcentage. La preuve de l'erreur est une paire `(0x17, 0x53)` de la même
session, `0x53` étant le niveau mesuré par les lunettes elles-mêmes :

| heure | `0x17` | ASCII | `0x53` (mesure) | « volts » petit boutien |
|---|---|---|---|---|
| 14:39 | `[39 38 00]` | « 98 » | — | 14 393 mV |
| 14:47 | — | — | `[00 61]` → **97 %** | — |
| 14:48 | — | — | `[00 60]` → **96 %** | — |
| 14:53 | `[39 36 00]` | « 96 » | — | 13 881 mV |

`0x17` annonce 98 % et 96 % ; `0x53` mesure 97 % et 96 % sur la même session. La clé ASCII
fonctionne aussi sur les échantillons plus anciens : `[38 31]` = « 81 », `[36 38]` = « 68 ».
Décodés en millivolts, ces mêmes octets donnaient 14 393 mV et 13 881 mV — à **97 % et 96 %**
de charge, un pack ne peut pas être à mi-course (4S Li-ion : ~16,4 V à 97 %). La tension, la
courbe 3S et les « volts » affichés sont donc des artefacts d'un décodage erroné, et non une
échelle mal calibrée.

Le point méthodologique compte ici : `0x17` **acquittait** (`crc=ok`) et les valeurs décodées
étaient plausibles à l'œil ; les deux avaient déjà été présentées comme une confirmation. Seul
le **rapprochement avec une source indépendante** (`0x53` de la même session) a départagé les
hypothèses. Un payload est une hypothèse de décodage tant qu'une deuxième source ne le recoupe
pas — et un nombre plausible affiché à l'écran ne vaut pas une mesure.

`[3A 30 01]` reste **non expliqué** : ses deux premiers octets sont ASCII « :0 », pas deux
chiffres. Ce payload-là n'est pas décodé (journal explicite), et ses « 12 346 mV » n'ont
aucune valeur.

En pratique : `0x53` n'est qu'un *push* et n'arrive que dans une session sur quelques-unes, si
bien que `0x17` est souvent la seule source disponible. L'app affiche le niveau de `0x53` en
priorité, celui de `0x17` en repli **étiqueté « (0x17) »**, et aucune valeur n'est calculée par
l'app. Le point bas manquant reste utile : un échantillon `(0x17, 0x53)` à niveau intermédiaire
et à plat confirmerait la linéarité sur toute la plage.

### Divers

- La 4ᵉ caractéristique `aa15` reste sans rôle lu (le désassemblage s'arrête avant son
  `sput-object`).
- `GET_SUPPORT_FUNCTION 0x95` est utilisable pour **détecter les fonctions d'un modèle** avant
  d'afficher un réglage : c'est ce qui distingue « bouton cassé » de « fonction absente ».
- Le test `capture.bat` (`protocol/`) donne maintenant des trames BLE réelles : il produit la
  charge de `0x53`, et sert à collecter les paires `(0x17, 0x53)` manquantes ci-dessus.

---

# 7. Transport Wi‑Fi / AP des lunettes (import + suppression des médias)

Tout ce qui suit a été **mesuré sur le terrain** (téléphone + lunettes E09‑3AA1, journal
logcat de l'app tierce `com.local.eyevuectl`). C'est la couche que le BLE ne sait pas faire :
le BLE pilote l'appareil, **le Wi‑Fi transfère les fichiers**.

## 7.1 Les trois hôtes relevés dans le bytecode

Le bytecode du constructeur a trois constantes d'URL de base, choisies selon le mode réseau :

| Constante (lue) | URL | Condition |
|---|---|---|
| `j0` | `http://192.168.169.1/` | `re7.s` seul → **AP des lunettes, mode classique** |
| `i0` | `http://192.168.1.254/` | `re7.s && re7.t` → mode « TWiFi » |
| `h0` | `http://192.168.49.207/` | sinon → Wi‑Fi Direct / P2P |

**Mesuré** : `http://192.168.169.1/?custom=1&cmd=3015` répond, et c'est sur
`192.168.169.1/EVENT/<nom>.jpg` que le **téléchargement a réussi**. L'AP ne sert **que du
HTTP, jamais de HTTPS** : Android bloque le clair par défaut, il faut une
`network_security_config.xml` qui autorise explicitement `192.168.169.1`
(erreur mesurée : `Cleartext HTTP traffic to 192.168.169.1 not permitted`).

## 7.2 SSID de l'AP

L'AP des lunettes est en `DH‑TwI‑XXXXXXXX` (préfixe `DH‑` relevé dans le bytecode). L'app
mémorise le dernier SSID d'AP vu (`last_ap_ssid`), se connecte dessus, **puis relâche la
connexion** (`release()` dans un `finally`) : garder l'AP attachée coupe Internet pendant
toute la durée de l'opération.

## 7.3 Listing des médias

```
GET http://192.168.169.1/app/getfilelist
```

Réponse JSON (mesurée) avec les fichiers de `A:\DCIM\100HUNTI\`. Le nom côté AP est de la
forme `20261111204012731.jpg` (17 chiffres) ; le nom **local** après import est
`DH-261111-204012731-20261111204012731.jpg`. **Les deux partagent les 17 chiffres** : c'est
la clé canonique, `MediaFile.keyOf()`, le **seul** critère de rapprochement fiable.

> ⚠️ Comparer les noms bruts entre listing AP et galerie locale **ne marche jamais** : les
> chaînes ne sont pas les mêmes. Un contrôle du type
> `after.none { it.name == nomLocal }` est **toujours vrai**, donc une commande **rejetée**
> par l'AP y est annoncée « supprimée ». C'est un faux succès réel, rencontré dans ce projet.

## 7.4 Télécharger un média

```
GET http://192.168.169.1/EVENT/<YYYYMMDDHHMMSSmmm>.jpg
```

Chemin canonique alternatif dans le bytecode : `/DCIM/100HUNTI/`. On télécharge `f.path`
tel que renvoyé par le listing, sans le réécrire.

## 7.5 Supprimer un média (le point le moins documenté)

Deux formes **toutes deux présentes dans le bytecode**, testées l'une après l'autre :

```
GET http://<base>/?custom=1&cmd=4003&str=A:\DCIM\100HUNTI\<nom>
GET http://<base>/app/deletefile?file=<nom>
```

Trois précautions qui ont coûté cher, à ne pas perdre si tu modifies le projet :

1. **Le nom envoyé doit être celui du LISTING AP**, pas le nom local. Le constructeur
   concatène `str=` + le nom du listing. Envoyer `DH-…` fait rejeter la commande.
2. **Le serveur embarqué répond à TOUTE URL**, y compris inconnue : un `200` ne prouve **rien**.
   Le seul verdict qui vaille est **la disparition du fichier du listing**, contrôlée par
   `GET /app/getfilelist` **après** la commande (et un listing **avant**, pour ne rien
   annoncer sur un fichier déjà absent). Un `4xx/5xx` = endpoint rejeté → passer à la forme
   suivante sans relister (1 à 2 s par listing).
3. **`HttpUrl` percent‑encode le `\` dans une query.** Le constructeur passe sa chaîne par le
   `@Url` de Retrofit → okhttp normalise en `A:%5CDCIM%5C100HUNTI%5C`. L'URL que l'AP reçoit
   réellement du constructeur **n'est donc pas identique octet pour octet** à la constante du
   bytecode. Les deux formes sont testées, et le journal dit laquelle a fait agir la commande.

**Il n'existe aucune commande « tout supprimer »** dans le bytecode : `DeleteAllCommand()` est
un `toString()` de classe gson, pas une trame (`hb4.smali`). La suppression totale = une
requête par fichier.

**Ordre imposé local → lunettes.** Effacer d'abord sur les lunettes laisserait sur le
téléphone les clones du dédoublonnage MediaStore (`…jpg` + `(1)(2)(3)` pour **un** fichier AP,
mesuré le 12‑11) sans original à récupérer.

## 7.6 Ce qui reste non mesuré

- La **base gagnante pour la suppression** est **déduite** du succès du téléchargement sur
  `192.168.169.1/EVENT/…`, pas mesurée pour `cmd=4003`.
- La **forme acceptée** (avec/sans extension, `\` nus vs `%5C`) est inconnue.
- Le **texte de succès** du constructeur n'est pas lu.
- La 4ᵉ caractéristique GATT `aa15` reste sans rôle lu.

Si tu fais ces mesures, **écris‑les ici** avec l'horodatage et la ligne de log : c'est la
section la plus utile du dépôt, et la plus fragile.
