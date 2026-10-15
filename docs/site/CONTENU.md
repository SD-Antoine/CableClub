# Le site de Cable Club : contenu (première réflexion)

Adresse : **https://cableclub.app** (le domaine OVH déjà à vous ; `play.cableclub.app` reste le serveur de jeu).

## Ce que le site doit faire

1. **Donner envie en 10 secondes** à quelqu'un qui découvre : « jouer à un Soul Link / Nuzlocke avec ses
   amis, chacun sur sa partie, et se voir dans le jeu ».
2. **Faire télécharger sans hésitation** : le bon fichier pour PC ou téléphone, rassurer sur l'avertissement
   de Windows et l'antivirus, expliquer les ROMs.
3. **Répondre aux questions** avant qu'on les pose (FAQ), pour moins de messages d'aide.
4. **Porter les pages obligatoires** : confidentialité, conditions, suppression de compte, politique de
   signature du code (SignPath la demande sur la page d'accueil et de téléchargement), mentions légales.

Public : joueurs de 13 à 30 ans qui connaissent les défis Nuzlocke (vidéos YouTube et Twitch), souvent
pas techniques. Ton : simple, chaleureux, tutoiement ou vouvoiement à choisir (l'application vouvoie : garder
le vouvoiement pour la cohérence).

## Les pages

### 1. Accueil

- **Bandeau** : logo, titre, phrase d'accroche, deux boutons (Télécharger pour Windows / pour Android ;
  le site choisit le bon selon l'appareil), lien discret « Code source sur GitHub ».
  - Accroche, au choix :
    - « Vos défis Nuzlocke, enfin à plusieurs. »
    - « Un Soul Link avec vos amis, chacun sur sa partie, tous dans le même monde. »
  - Sous-titre : « Cable Club relie vos parties : il suit vos captures et vos K.O., fait respecter les règles
    et montre vos partenaires directement dans votre jeu. Gratuit, sur PC et sur téléphone. »
- **Une image animée** (le cœur de l'argument) : deux joueurs qui se croisent dans la même ville, chacun dans
  sa partie, avec leurs pseudos au-dessus de la tête. Voir « Visuels » pour la prudence sur les images de jeu.
- **Trois piliers** (icône + titre + deux lignes) :
  1. *Vos amis dans votre jeu* — leur personnage, leur Pokémon suiveur, leur état (combat, dialogue) en direct.
  2. *Les règles, sans tricher* — zones, captures, morts : l'arbitre de la salle s'en occupe pour tout le monde.
  3. *Partout* — le même compte sur PC et sur téléphone, la partie continue de l'un à l'autre.
- **Comment ça marche, en 3 étapes** : 1. Créez un compte et invitez vos amis. 2. Ouvrez une salle, choisissez
  le jeu et les règles (randomisation comprise). 3. Chacun lance sa partie : l'aventure commence.
- **Les modes** : Soul Link (2 à 5 joueurs, Pokémon liés par zone), Nuzlocke (1 à 10 joueurs, chacun sa course),
  Sololocke et Shinylocke « bientôt ». Une carte par mode avec son nouveau logo.
- **Jeux pris en charge** : générations 1 à 5 (Game Boy à Nintendo DS), en listant les versions françaises
  testées. (Les noms des jeux sont des marques : les citer en texte, sans leurs logos ni jaquettes.)
- **Bas de page** : Télécharger, Guide, Nouveautés, Code source, Confidentialité, Conditions, Mentions légales,
  Code signing policy, mention de non-affiliation.

### 2. Télécharger

- **Windows** : bouton du zip de la dernière version (lien direct vers GitHub : `releases/latest/download/…`),
  taille, version, date. « Décompressez et lancez CableClub.exe, rien à installer. »
  - Encadré **« Windows affiche un avertissement ? »** : pourquoi (programme récent, peu téléchargé), comment
    passer (Informations complémentaires › Exécuter quand même), et que la signature SignPath le fera
    disparaître avec le temps. Lien vers la vérification de l'empreinte SHA-256.
  - Encadré **« Mon antivirus le bloque ? »** : faux positif connu sur les programmes récents ; comment
    l'autoriser (Norton, Avast, Defender) ; lien pour nous signaler le nom de la détection.
- **Android** : bouton Play Store (badge officiel, quand l'app y sera) et lien APK direct (« pour les téléphones
  sans Play Store »), Android 11 ou plus récent, moteur web à jour.
- **Politique de signature du code** (section « Code signing policy ») : texte d'attribution SignPath,
  rôles, lien confidentialité — repris du README.
- Configuration conseillée : PC Windows 10/11 64 bits ; téléphone récent pour la DS.

### 3. Guide (premiers pas + FAQ)

- **Premiers pas** illustrés : installer, créer un compte, ajouter ses ROMs, créer une salle, inviter, jouer,
  manettes et boutons tactiles.
- **FAQ** :
  - *Où trouver les jeux ?* — Cable Club n'en fournit pas ; utilisez des copies de vos propres cartouches
    (avec un lecteur de cartouches). Aucun lien de téléchargement de ROM, nulle part sur le site.
  - *C'est quoi un Soul Link ? un Nuzlocke ?* — règles expliquées simplement, avec les options.
  - *Faut-il jouer en même temps ?* — non, chacun avance à son rythme ; les règles restent liées.
  - *Mes sauvegardes ?* — sur l'appareil, et une copie sur le serveur pour passer du PC au téléphone.
  - *Je peux héberger mon propre serveur ?* — oui sur PC (Réglages › Serveur › Avancé), pas sur téléphone.
  - *Combien ça coûte ?* — rien, pas de publicité, logiciel libre.
  - *Mes données ?* — résumé de la politique de confidentialité, lien.
  - *Supprimer mon compte ?* — dans l'app ou en ligne.
  - *Un bug, une idée ?* — GitHub Issues (et un espace communautaire si vous en ouvrez un : Discord ?).

### 4. Nouveautés

Les notes de version, reprises du CHANGELOG (la même source que l'application). À générer automatiquement à
chaque publication pour ne rien écrire deux fois.

### 5. Pages légales

- **Confidentialité**, **Conditions d'utilisation**, **Supprimer un compte** : déjà écrites (servies par le
  serveur : `play.cableclub.app/confidentialite`, `/conditions`, `/compte/suppression`). Le site peut les
  reprendre ou simplement y renvoyer.
- **Mentions légales** (obligatoires en France, loi LCEN) : l'éditeur (une personne physique peut ne donner que
  son pseudonyme sur le site si elle a donné son identité à l'hébergeur), un contact, et l'hébergeur du site
  (nom, adresse, téléphone ; par exemple GitHub, Inc. si le site est sur GitHub Pages).
- **Code signing policy** (voir Télécharger).

## Visuels

- Identité : fond sombre (#16171b), dégradé bleu-violet du logo (#8aa4ff → #b38cff), coins arrondis, la police
  système de l'application. Thème clair automatique possible.
- Captures et vidéos : l'interface de Cable Club (salles, chat, profil, réglages, la barre des joueurs) ne pose
  pas de problème. **Les images des jeux appartiennent à Nintendo / Game Freak** : le plus prudent est de les
  éviter en gros plan sur la page d'accueil, ou d'en montrer peu (une courte séquence où l'on voit surtout les
  pseudos et l'interface de Cable Club). Aucun sprite, logo ou jaquette officiels en image fixe.
- Pas de « Pokémon » dans le logo ni dans le titre du site ; le mot peut apparaître dans les textes pour décrire
  (« les jeux Pokémon de la Game Boy à la DS »), toujours avec la mention de non-affiliation en bas de page.

## Technique (proposition)

- **Site statique** (HTML/CSS, un peu de JavaScript), sans traceur ni cookie : donc sans bandeau cookies, et
  cohérent avec la politique de confidentialité.
- **Hébergement** : GitHub Pages (gratuit, HTTPS automatique, un dossier `site/` du dépôt ou un dépôt à part,
  publié à chaque modification) avec le domaine `cableclub.app` pointé chez OVH ; ou le VPS (déjà en place,
  mais un serveur web à ajouter devant le serveur de jeu). GitHub Pages est plus simple et ne charge pas le VPS.
- Liens de téléchargement « toujours la dernière version » :
  `https://github.com/SD-Antoine/CableClub/releases/latest/download/…` (les noms de fichiers contiennent la
  version : le site lit la dernière version via l'API GitHub, ou on ajoute des copies sans numéro).
- Français d'abord ; anglais plus tard (la communauté Nuzlocke est surtout anglophone).
- Référencement : titre et description clairs (« Soul Link et Nuzlocke à plusieurs »), une page par sujet de la
  FAQ, aperçu pour les réseaux sociaux (image 1200×630 dérivée de la bannière).

## À décider par vous

1. Tutoiement ou vouvoiement sur le site.
2. L'accroche (une des deux, ou une autre).
3. Hébergement : GitHub Pages (conseillé) ou le VPS.
4. L'adresse de contact publique (`contact@cableclub.app` ?) et ce qui figure dans les mentions légales.
5. Un espace communautaire (Discord, GitHub Discussions) à mettre en avant, ou pas encore.
6. Jusqu'où montrer les images des jeux (aucune / quelques secondes / plus).
