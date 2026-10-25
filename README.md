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

Prérequis : téléphone ARM64 sous Android 8.0 ou plus récent, connexion Internet.

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
