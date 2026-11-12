# EyevueCtl — app libre pour lunettes Eyevue E09-3AA1

Application Android **écrite de zéro** (Kotlin, `com.local.eyevuectl`) qui pilote les lunettes
Eyevue **E09-3AA1** en BLE, **sans l'app du constructeur et sans serveur distant**.

Le dépôt contient aussi la **documentation complète du protocole** relevée pendant le projet :
c'est le livrable le plus utile pour quelqu'un qui veut modifier le projet ou écrire sa propre
app. Tout est **mesuré ou lu dans le bytecode**, jamais deviné — et ce qui reste non mesuré est
écrit comme tel.

- 📘 **[BLUETOOTH.md](BLUETOOTH.md)** — la couche Bluetooth en référence : UUID qui marchent,
  framing, appairage SPP, masque de capacités, batterie, événements, **et les pièges** qui ont
  coûté cher. À lire en premier.
- 📗 **[protocol/PROTOCOL.md](protocol/PROTOCOL.md)** — la spec : format de trame, table des
  commandes, params, appairage, **et la couche Wi-Fi/AP** (import, listing, téléchargement,
  suppression).
- 📕 **[protocol/](protocol/)** — l'atelier de capture : récupérer les trames réelles depuis
  logcat, les décoder, **régénérer les tables** de l'app.

## Ce que fait l'app

| Écran | Ce qui est réellement implémenté |
|---|---|
| **Pilotage** (`MainActivity`) | photo, vidéo, micro, volume + presets EQ, luminosité des lumières, ratio 4:3 / 16:9, orientation, langue, mode, port ON/OFF, horloge, stockage, firmware, capacités `0x95`, batterie, **journal des trames** (chaque commande nommée, avec ack et CRC) |
| **Album** (`AlbumActivity`) | vignettes **téléphonées depuis les lunettes** via l'AP Wi-Fi, sélection multiple, tout sélectionner, **télécharger** vers `Téléchargements/Eyevue`, **supprimer** (téléphone **puis** lunettes) |
| **Visualiseur** (`ViewerActivity`) | média en grand, nom complet, télécharger, supprimer ; retour à l'album qui se recharge |

Commandes disponibles : **68 constantes** dans la table générée (`Cmd.kt`, 215 lignes),
**17 objets de paramètres** (`Params.kt`), **84 références vérifiées** — le compte rendu par
`python tools/check_refs.py` (sortie attendue : `OK : toutes les references Cmd.* / P*.* resolvent`).
Les commandes les plus utiles sont listées dans PROTOCOL.md §3.

## Ce que l'app ne fait PAS (délibérément)

- **Aucune IA, aucun serveur.** Cette app est hors ligne. Elle ne contient **aucun** code de
  serveur LLM, aucune URL, aucune clé. (Le branchement sur un serveur IA local a été un autre
  volet du projet, sur un autre binaire ; il n'est pas dans ce dépôt.)
- **Aucun bouton pour une fonction absente du modèle.** La carte « Opacité des verres »
  (électrochrome) a été **retirée** : le masque `0x95` (`0x0000040A`) n'a pas les bits
  électrochromes, et 30 s de gestes sur la branche n'ont produit **aucune trame BLE**.
- **Aucune valeur inventée.** Pas de volts, pas de « tension » déduite, pas de pourcentage
  « estimé ». Un payload non décodé est écrit **« non décodé »**.
- **Aucun faux succès d'interface.** La suppression côté lunettes est validée par un
  **re-listing** de l'AP : le fichier doit avoir **disparu du listing**. Un code HTTP `200` ne
  compte pas — le serveur embarqué répond à toute URL, y compris inconnue. Les fichiers
  encore sur les lunettes sont **écartés** du téléchargement/suppression local et le compte
  l'affiche (`3 ignore(s) (sur les lunettes)`).
- **Aucun faux aperçu.** Vidéo et enregistrement sont annoncés comme tels.

## Construire

Prérequis : JDK 17, Android SDK (platform 34), Gradle (8.9 utilisé), un appareil Android
min **23** / cible **34**.

```bash
cd eyevue-ctl
gradle assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Vérification sans compilateur Kotlin :

```bash
cd eyevue-ctl && python tools/check_refs.py      # exit 0 = tables cohérentes avec le code
```

> `local.properties` (chemin du SDK) n'est pas dans le dépôt, il est propre à chaque machine.
> Aucun APK n'est versionné : les binaires font grossir le dépôt et ne sont pas relisibles.

L'APK est en **debug** : il est signé par la clé de debug Android, donc installable directement
pour tester. Ce n'est pas une publication.

## Comment utiliser la doc du protocole

1. **Tu veux piloter les lunettes depuis ta propre app** → BLUETOOTH.md §2 (UUID), §3
   (framing), §5 (la séquence de test à reproduire).
2. **Tu veux récupérer les trames** → PROTOCOL.md §5 (l'app du constructeur logge elle-même
   ses trames en hexadécimal, aucun sniffeur BLE n'est nécessaire) puis `protocol/capture.bat`
   + `protocol/decode.py`.
3. **Tu veux les fichiers (photos/vidéos) sur les lunettes** → PROTOCOL.md **§7** : ce n'est
   pas du BLE, c'est l'AP Wi-Fi `192.168.169.1` en HTTP, avec listing, téléchargement et
   suppression.
4. **Tu veux ajouter un bouton** → BLUETOOTH.md §6 : lis d'abord le masque `0x95`.

## Ce qui reste ouvert

Le projet documente aussi ce qu'il **ne sait pas** (BLUETOOTH.md §11, PROTOCOL.md §7.6) :
`aa15` sans rôle lu, base réseau gagnante pour la suppression non mesurée, forme d'URL
exacte acceptée inconnue, mécanisme électrochrome non identifié. Les apports qui **mesurent**
ces points sont les bienvenus — écris la mesure, la date et la ligne de log.

## Licence & limites

Eyevue est une **marque d'un tiers**. Ce projet n'est ni affilié, ni approuvé, ni soutenu par
l'éditeur. Voir [NOTICE.md](NOTICE.md).
