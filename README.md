# NodeDroid — Paramo1d pour Android

Application Android simple qui embarque le client de vérification
[paramo1d](https://github.com/ignotusnemo/paramo1d) : votre téléphone se connecte
directement aux pairs du réseau principal Parano1d, vérifie la chaîne d'en-têtes
depuis le bloc genèse, puis la preuve récursive de l'État et les certificats de fork.
Aucun serveur de confiance n'est nécessaire.

## Utilisation

1. Ouvrez la page **Releases** de ce dépôt et téléchargez le dernier `Paramo1d-N.apk`
   depuis votre téléphone.
2. Ouvrez le fichier et autorisez l'installation d'applications inconnues si Android le demande.
3. Lancez **Paramo1d** et appuyez sur **Démarrer la vérification**.

Au premier lancement, l'application installe les paramètres de vérification embarqués
(quelques instants). Ensuite elle trouve des pairs, télécharge et vérifie la chaîne,
puis affiche le dernier bloc vérifié, la racine d'État, la durée et la mémoire utilisée.
Avec « Continuer à suivre les nouveaux blocs », elle reste active en arrière-plan
(notification avec bouton **Arrêter**) et vérifie chaque nouveau bloc.

## Réglages selon l'appareil

L'app détecte le téléphone (modèle, puce, nombre de cœurs et cœurs performants,
RAM) et propose quatre profils :

| Profil | Cœurs utilisés (ex. realme GT Neo2, 8 cœurs dont 4 performants) |
| --- | --- |
| Économie | moitié des cœurs performants (2) |
| Équilibré | cœurs performants (4) |
| Performance | tous les cœurs (8) |
| Automatique | Performance en charge, Équilibré sur batterie, Économie si batterie < 40 % ou si le téléphone tiède |

Le nombre de cœurs est plafonné sur les téléphones à 3–4 Go de RAM. Pendant la
vérification, l'app surveille la batterie, la température et le réseau toutes
les 10 secondes : elle se met en pause si le téléphone chauffe (batterie ≥ 45 °C
ou alerte thermique d'Android), si la batterie passe sous 20 % hors charge, et
en option hors charge ou hors Wi-Fi, puis reprend seule. Un changement de nombre
de cœurs est appliqué entre deux blocs.

Prérequis : téléphone ARM64 sous Android 8.0 ou plus récent, connexion Internet.

## Minage avec un ou plusieurs téléphones

L'onglet **Miner** fait du téléphone un chercheur de nonce (`parano1d-miner`).
Dans Parano1d, un bloc doit d'abord être prouvé par un nœud complet qui détient
l'État ; ce travail de preuve est trop lourd pour un téléphone (≈ 17 s sur 12 cœurs,
plusieurs Go de RAM). Il faut donc **un nœud en mode extminer** (PC ou serveur)
ou un pool, auquel autant de téléphones que voulu se connectent :

```sh
umask 077
openssl rand -hex 32 > ~/.parano1d/mining.key
parano1d --mode extminer --rpc-listen 0.0.0.0:9601 \
  --mining-key-file ~/.parano1d/mining.key
# facultatif : laisser chaque téléphone choisir son adresse de paiement
#   --allow-custom-coinbase
```

Dans l'app, renseignez l'adresse du nœud (`http://IP-du-PC:9601`), le contenu
de `mining.key` et, si le nœud l'autorise, une adresse de paiement `o1…`.
Le jeton circule en clair en HTTP : restez sur votre réseau local, un VPN
(WireGuard, Tailscale) ou un proxy TLS. Les mêmes profils de puissance et pauses
(chaleur, batterie, charge, Wi-Fi) s'appliquent au minage.

## Construction

L'APK est construit par GitHub Actions (`.github/workflows/build-apk.yml`) :

1. compilation de paramo1d pour `aarch64-linux-android` avec le NDK ;
2. téléchargement des paquets `history-step-pack-*` de la release Parano1d épinglée,
   puis `paramo1d prepare` qui contrôle leurs empreintes SHA-256 et produit les paramètres ;
3. test de synchronisation réelle sur le réseau principal avec la version PC ;
4. assemblage et signature de l'APK, publié en release.

L'interface (Java, sans dépendance) lance le vérificateur natif comme processus
dans un service au premier plan et analyse sa sortie.

Licence de paramo1d : Apache-2.0.
