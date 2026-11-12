# Atelier de capture du protocole

> Note : ce dossier contient l'**analyse** du protocole (spécification, capture de trames depuis
> logcat, génération des tables). Il ne contient **aucun script de patch de l'APK de l'éditeur**
> ni fragment de son bytecode : ces éléments relèvent d'un usage privé et ne sont pas publiés
> ici (voir NOTICE.md).

But : **récupérer les trames réelles** que l'app envoie aux lunettes, pour calibrer le décodeur et écrire une app indépendante sans deviner.

On n'a pas besoin d'un sniffer BLE (nRF Connect, Ubertooth) : **l'app logge elle-même ses trames en hexadécimal** dans logcat. Cf. [PROTOCOL.md](PROTOCOL.md) §5.

## Fichiers

| Fichier | Rôle |
|---|---|
| `PROTOCOL.md` | **La spec** : framing, 66 commandes, params, transports, appairage. Le vrai livrable. |
| `decode.py` | Parse le logcat, décode les trames, écrit CSV + timeline Markdown. |
| `capture.bat` | Vide le buffer logcat puis capture en continu jusqu'à Ctrl+C. |
| `captures/` | Dépose tes `.log` ici (ignorés par git). |

## Déroulé (~15 min)

```bat
cd protocol
capture.bat
```

Pendant que la capture tourne, sur le téléphone (l'app de l'éditeur, qui logge ses propres trames) :

1. connecter les lunettes ;
2. rester sur la page ~10 s → handshake, statut, batterie ;
3. prendre **une** photo → `TAKE_PHOTO` ;
4. déclencher la **traduction** → la séquence `0x97 / 0x98 / 0x99` ;
5. changer **volume** et **luminosité** → `0x70`, `0x01` ;
6. **hocher la tête** → `ACTION_SYNC` ;
7. déconnecter les lunettes.

Retour dans la console → **Ctrl+C**, puis :

```bash
python decode.py captures/capture_XXXX.log
```

## Interpréter le résultat

| Ce que montre `decode.py` | Conclusion |
|---|---|
| Des trames `BLE TX AB 55 …` décodées avec des noms connus | ✅ protocole confirmé, on peut coder une app tierce |
| `CRC calibre : sum(cmd+payload)` | ✅ le périmètre CRC était la dernière inconnue |
| Trames avec `commande=inconnu_0x..` | le payload est multi-octets : on le décompose ensuite |
| `crc : A_RECALIBRER` | périmètre CRC différent → ajustable dans `crc_candidates()` |
| **Aucune** trame, mais `onConnectionStateChange` | le module BLE de l'app ne s'est pas activé → vérifier que les lunettes étaient connectées |
| Trames **seulement** en SPP (`52 58`) | le BLE ne porte que le pilotage ; tout le média passe par le socket RFCOMM → l'app tierce devra faire du Bluetooth Classic |
| Une séquence avant `onConnectionStateChange` « réussi » | c'est le handshake `0x9B` → **déterminer s'il est local ou réseau** |

## Questions que cette capture doit trancher

1. **L'appairage exige-t-il le serveur Eyevue ?** Si `0x9B` est échangé en local → on peut s'en passer. **Résultat n°1.**
2. Le volume/luminosité passent-ils par BLE ou SPP ?
3. La récupération de photo : commande BLE puis transfert SPP ? À quel débit ?
4. Le format audio de la reconnaissance vocale (celui qu'on doit produire pour l'IA locale).

## Auto-test du décodeur

```bash
python decode.py --selftest
```

Vérifie sur des trames synthétiques : framing, sens TX/RX, décodage `cmd`→nom, param, CRC.

## Sécurité

Le logcat peut contenir des **adresses MAC** et le nom de tes appareils. Les `.log` restent en local (`.gitignore` couvre `captures/`) ; à ne pas publier.
