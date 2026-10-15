# Publier Cable Club sur le Play Store

Tout ce qu'il faut pour la Play Console : ce que l'application fait déjà pour respecter les règles de
Google, les réponses aux formulaires, les textes de la fiche et les étapes dans l'ordre.

## Ce qui est déjà prêt dans Cable Club (0.9.0)

| Exigence de Google | Où c'est fait |
|---|---|
| Format AAB, cible Android 16 (API 36), 64 bits, pages mémoire de 16 Ko | `.\build-android.ps1 -Play` → `dist\CableClub-<version>-play.aab` |
| Pas de mise à jour hors du Play Store, pas de permission « installer des applications » | Version « play » (`android/app/src/play`) : la recherche de mises à jour sur GitHub et l'installateur n'y sont pas |
| Pas de code exécutable téléchargé | Les émulateurs (mGBA, melonDS DS) sont dans le bundle ; rien n'est téléchargé à part les données du jeu |
| Aucun jeu fourni | L'utilisateur ajoute ses propres ROMs |
| Politique de confidentialité | https://play.cableclub.app/confidentialite (servie par le serveur) |
| Supprimer son compte dans l'app et depuis le web | Mon profil › Supprimer mon compte ; https://play.cableclub.app/compte/suppression |
| Contenu créé par les utilisateurs (chat, profils) : conditions acceptées, signalement, blocage, modération | Case à cocher à l'inscription ; « Signaler » sur chaque message et chaque profil ; « Bloquer » ; Mon profil › Modération pour les modérateurs (admins.txt du serveur) |

Avant d'envoyer quoi que ce soit à Google, **le serveur doit être en version 0.9.0** (les pages de
confidentialité, de conditions et de suppression de compte, et les signalements, sont servis par lui).

## 1. Le compte développeur

1. https://play.google.com/console → créer un compte **personnel** (25 $ une fois), avec vérification
   d'identité (pièce d'identité) et d'un numéro de téléphone.
2. Nom du développeur affiché sur la fiche : par exemple « Cable Club » ou « SD-Antoine ».
3. Adresse e-mail de contact publique (obligatoire) : prévoir `contact@cableclub.app` (redirection
   d'e-mail gratuite dans l'espace OVH du domaine), la même que dans la politique de confidentialité.

## 2. Créer l'application

- Nom : **Cable Club** · Langue par défaut : français (France) · Application (pas jeu) · Gratuite.
- Catégorie conseillée : **Divertissement**.

## 3. La clé de signature (important)

Google signe les APK qu'il distribue (« Signature d'application par Google Play »). Deux choix :

- **Recommandé : confier à Google la clé actuelle** (celle des APK de GitHub). Les versions Play et
  GitHub restent alors compatibles : un joueur peut passer de l'une à l'autre sans désinstaller. Dans la
  Play Console, à la création de la première version : *Utiliser une autre clé de signature* → *Exporter et
  importer une clé depuis un keystore Java* ; la console fournit l'outil `pepk.jar` et la commande exacte, à
  lancer avec `%USERPROFILE%\.cableclub\android-release.jks` et l'alias de `android-signing.properties`.
  Le bundle envoyé est signé avec cette même clé (clé d'importation).
- Laisser Google créer une nouvelle clé : plus simple, mais la version Play et l'APK de GitHub seront
  incompatibles (désinstaller pour passer de l'une à l'autre ; les sauvegardes envoyées au serveur restent).

## 4. Le test fermé obligatoire

Pour un nouveau compte personnel, Google exige un **test fermé avec au moins 12 testeurs pendant
14 jours d'affilée** avant d'ouvrir la publication à tous.

1. *Tests › Test fermé* : créer une liste de testeurs (adresses Gmail des amis), envoyer le bundle
   (`dist\CableClub-0.9.0-play.aab`, ou l'artefact *CableClub-play-aab* de l'exécution GitHub Actions).
2. Chaque testeur ouvre le lien d'inscription, installe l'app depuis le Play Store et la garde 14 jours.
3. Ensuite : *Demander l'accès à la production* (questionnaire sur le test).

## 5. Contenu de l'application (formulaires)

### Accès à l'application
L'app demande un compte. Fournir à Google un compte de test sur le serveur officiel (identifiant et mot de
passe créés pour l'occasion) et préciser : « L'application ne contient aucun jeu. Les fonctions sociales
(salles, amis, chat, profil) se testent sans jeu ; le jeu demande une ROM que l'utilisateur possède. »

### Publicité
Non, l'application ne contient pas de publicité.

### Classification du contenu (questionnaire IARC)
- Catégorie : « Réseaux sociaux, communication » ou « Autre » selon ce que propose le questionnaire.
- Violence, sexe, jurons, drogues, jeux d'argent : non (l'app n'a pas de contenu propre).
- Les utilisateurs peuvent-ils interagir / échanger ? **Oui** (chat, profils).
- Partage de la position : non. Achats : non.

### Public cible
13–15 ans, 16–17 ans, 18 ans et plus (pas les moins de 13 ans). L'app ne vise pas les enfants.

### Sécurité des données
- Données chiffrées en transit : **oui**. Suppression possible : **oui** (URL : https://play.cableclub.app/compte/suppression).
- Données partagées avec des tiers : **non** (l'hébergeur du serveur est un sous-traitant, ce n'est pas un partage).

| Type de données (catégories de Google) | Collectée | Obligatoire | Finalités |
|---|---|---|---|
| Informations personnelles › Nom (nom affiché) | Oui | Oui | Fonctionnement de l'app, gestion du compte |
| Informations personnelles › ID utilisateur (identifiant) | Oui | Oui | Fonctionnement de l'app, gestion du compte |
| Photos et vidéos › Photos (photo de profil) | Oui | Non | Fonctionnement de l'app |
| Messages › Autres messages dans l'application (chat) | Oui | Non | Fonctionnement de l'app |
| Activité dans l'app › Autre contenu généré (bio, signalements) | Oui | Non | Fonctionnement de l'app, sécurité |
| Activité dans l'app › Actions dans l'application (événements de jeu des salles) | Oui | Non | Fonctionnement de l'app |
| Fichiers et documents (sauvegardes de cartouche envoyées) | Oui | Non | Fonctionnement de l'app |

Non collectées : position, e-mail, contacts, identifiants de l'appareil, données de diagnostic,
historique de navigation, informations financières, santé.

### Autres déclarations
Application d'actualités : non. Application gouvernementale : non. Fonctionnalités financières : non.
Santé : non. Permissions : seulement Internet.

## 6. La fiche du Play Store

Visuels (dossier `docs/play-store`) : `icone-512.png` (512×512) et `banniere-1024x500.png`.
Captures : 2 à 8 captures du téléphone (format portrait). **Conseil prudent** : montrer l'interface
(salles, chat, profil, réglages) plutôt que des écrans de jeu, qui sont des images de Nintendo.

**Description courte** (80 caractères au plus) :

> Soul Link et Nuzlocke à plusieurs : vos jeux, vos amis, des règles respectées.

**Description complète** (version prudente, sans nom de marque dans le texte principal) :

> Cable Club réunit vos amis autour de vos défis préférés : Soul Link et Nuzlocke, à plusieurs, sur
> téléphone comme sur PC.
>
> • Des salles pour jouer ensemble : chacun lance sa propre partie, Cable Club suit les captures, les K.O.
> et les zones, et fait respecter les règles du défi pour tout le groupe.
> • Vos partenaires dans votre jeu : voyez où ils sont et ce qu'ils font, en direct.
> • Un journal de l'aventure, des statistiques et l'historique de vos parties.
> • Le chat : général, par salle et en privé avec vos amis.
> • Randomisation des parties, à la demande.
> • Boutons tactiles, vibrations, et manettes Bluetooth ou USB.
> • Le même compte sur PC et sur téléphone : commencez sur l'un, continuez sur l'autre.
>
> Cable Club ne contient aucun jeu : il utilise les copies de vos propres jeux.
>
> Logiciel libre et gratuit (GPLv3), sans publicité. Projet de fans, non affilié à Nintendo, Creatures,
> GAME FREAK ou The Pokémon Company, ni approuvé par eux.

Le mot « Pokémon » peut aider les joueurs à trouver l'app, mais c'est aussi ce qui attire les plaintes
pour marque déposée : la version ci-dessus ne le met que dans la mention de non-affiliation.

## 7. Ensuite, à chaque version

1. `.\build-android.ps1 -Play` (ou l'artefact *CableClub-play-aab* de GitHub Actions).
2. Play Console › *Production* (ou *Test fermé*) › *Créer une version* › envoyer le `.aab`, coller les
   nouveautés de la version (section du CHANGELOG, 500 caractères au plus).
3. Le numéro de version (versionCode) monte tout seul avec `src/slt/Version.java`.
