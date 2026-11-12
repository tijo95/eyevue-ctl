# Avis, propriété, limites

## Ce que ce dépôt contient

- Le **code source de notre propre application** (`com.local.eyevuectl`), écrite par nous.
- La **documentation du protocole** de communication avec les lunettes, relevée par nous.
- Des **outils** (Python, batch) d'analyse et de capture de trames.

Ce dépôt ne contient **aucun binaire de l'éditeur** : aucun APK Eyevue, aucun `.so`, aucune
capture de son interface, aucun de ses secrets. Il ne contient pas non plus d'APK de notre app
(le build se fait sur place : voir README).

## Marque

**Eyevue** est une marque d'un tiers. Ce projet **n'est ni affilié, ni approuvé, ni soutenu**
par l'éditeur des lunettes. Les noms `Eyevue`, `E09-3AA1`, `HUNTI` et les identifiants de
service sont cités à seule fin de **décrire l'interopérabilité**, ce que la marque autorise
usuellement (mention nécessaire pour dire à quoi s'adresse un produit).

## Sur la façon dont le protocole a été établi

Les tables de commandes, les UUID GATT et les constantes réseau ont été **obtenues par lecture
du bytecode d'un APK légalement acquis** et par **mesure sur notre propre matériel** (logcat
des trames que l'app émet elle-même). Il n'y a **ni chiffrement, ni clé, ni signature** dans
la couche BLE de ce produit : vérifier soi-même — aucune occurrence de `Cipher`,
`SecretKeySpec` ou `MessageDigest` dans le module Bluetooth.

La documentation publiée ici est **notre rédaction** : tableaux, explications, mesures, pièges.
Elle ne recopie **aucun code de l'éditeur**. Ce dépôt **ne contient délibérément aucun script
de patch de l'APK de l'éditeur** ni fragment de son bytecode : ces éléments (scripts de
re-patch, littéraux `smali`) relèvent d'un usage strictement privé et **ne doivent pas être
redistribués publiquement**.

## Ce que tu fais de ce dépôt

- **Réécrire une app compatible** avec ces lunettes à partir de la spécification : c'est
  l'objet du dépôt, et c'est légal dans la plupart des juridictions (interopérabilité).
- **Redistribuer le binaire de l'éditeur**, ses ressources, ou des fragments de son bytecode :
  à ne pas faire.
- **Contourner des mesures techniques de protection** d'un produit peut contrevenir à la
  législation locale (ex. DMCA §1201, Directive 2001/29/CE art. 6) et aux conditions générales
  du fabricant. Ce dépôt **ne fournit aucun moyen** de le faire.
- **Utiliser les commandes radio** (photo, réseau, mise à jour) : les règles applicables au
  Wi-Fi, au Bluetooth et aux mises à jour **téléchargées depuis les serveurs du fabricant**
  t'appartiennent.

## Suppression de fichiers de la galerie

La commande de suppression est **irréversible** côté lunettes : ce qui est effacé de l'appareil
n'est pas récupérable. `WifiImport.deleteOnAp()` écrit cette mise en garde dans son
documentation de méthode ; l'app exige une sélection explicite. **Ne transforme pas ce chemin
en suppression massive** sans conscience du risque : il n'existe d'ailleurs **aucune** commande
« tout supprimer » dans le protocole.

## Licence du code de ce dépôt

Le code que nous avons écrit (app Kotlin, outils, documentation) est fourni **en l'état**, sans
garantie, pour usage personnel et étude. Tu peux l'étudier, le modifier, le réutiliser.
Tu n'obtiens **aucun droit** sur les marques, les logiciels, les firmwares ou les serveurs de
l'éditeur des lunettes.

*Si tu estimes qu'un contenu de ce dépôt dépasse ce qui est acceptable, ouvre une issue :
il sera retiré.*
