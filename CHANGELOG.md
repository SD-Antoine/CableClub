# Nouveautés de Cable Club (anciennement SLT)

## 0.11.1 — 2026-10-26

- **1re génération, versions françaises (Rouge, Bleu, Jaune)** : les autres joueurs sont visibles dans le jeu dès le début de la partie. Avant, ils n'apparaissaient qu'une fois le premier Pokémon reçu. Ce premier Pokémon compte maintenant bien comme Pokémon de départ : il était pris pour un Pokémon déjà présent dans la sauvegarde, et sa mort ne comptait pas.
- **Inviter un joueur** dans une salle (ou depuis la page Amis) n'affiche plus d'erreur : l'invitation partait pourtant bien.

## 0.11.0 — 2026-10-25

- **Les règles d'honneur sont appliquées par Cable Club** (modes Nuzlocke, Sololocke et Shinylocke, générations 1 à 5) : ce sont maintenant les « Règles classiques du Nuzlocke ». Un objet interdit quitte le sac dès que le joueur reprend la main sur le terrain (hors menus, dialogues et combats), et chaque changement est noté dans le journal de la salle.
- **Pas d'objets de soin** : potions, soins de statut, Rappels, PP, boissons, herbes et baies qui soignent sont retirés du sac (et des Pokémon qui les tiennent). **Pas de Master Ball**, **Pas de Repousse**, **Pas de Multi Exp** (le Multi Exp de la 1re génération aussi) : retirés du sac.
- **Pas d'objet tenu** (générations 2 à 5) : l'objet tenu par un Pokémon de l'équipe est remis dans le sac ; les lettres restent.
- **Pas de Vol** : la CS Vol est retirée du sac, et les Pokémon de l'équipe oublient l'attaque Vol.
- **Mode Défini** : le style de combat « Défini » est imposé dans les options du jeu, comme dans le Nuzlocke Hardcore.
- **Pas d'évolution** : les évolutions sont retirées de la ROM de la salle (aucun Pokémon n'évolue, les pierres sont sans effet). La règle se choisit avant le lancement de l'aventure ; la changer fait régénérer les ROMs.
- **Pas de capture équipe pleine** (« Notepad Clause ») : un Pokémon capturé alors que l'équipe est pleine (il part au PC) est relâché, et la chance de la zone est utilisée.
- **Exception pour les CS** : le bouton « Pokémon de CS » de la barre du jeu fait garder la prochaine capture interdite (zone déjà utilisée, doublon, pas chromatique, équipe pleine) pour les CS. Ce Pokémon ne doit jamais gagner d'expérience (combat, Multi Exp, Super Bonbon) : sinon, il est relâché. En Sololocke, il reste dans l'équipe sans compter comme le Pokémon seul.

## 0.10.0 — 2026-10-25

- **Mode Sololocke**, avec son onglet : des salles de 1 (solo) à 10 joueurs, chacun le sien, avec les règles du Nuzlocke autour d'**un seul Pokémon dans l'équipe**. Dès qu'un deuxième Pokémon vivant rejoint l'équipe (capture, cadeau, retrait du PC), Cable Club l'envoie dans le PC ; ou « Sur l'honneur » : l'équipe peut garder des Pokémon pour les CS, mais un seul combat. Quand votre Pokémon meurt : « Un autre prend la relève » (un Pokémon vivant du PC ; la partie n'est perdue que s'il n'en reste aucun) ou « La partie est perdue ». Jeux des générations 1 à 5.
- **Mode Shinylocke**, avec son onglet : des salles de 1 à 10 joueurs, chacun le sien. **Seuls les Pokémon chromatiques peuvent être capturés** (les autres sont relâchés aussitôt), et un Pokémon K.O. est mort ; le Pokémon de départ est autorisé. Options : un seul chromatique par zone, clause des doublons, légendaires, Pokémon offerts (gardés même non chromatiques, ou seulement s'ils le sont). Jeux des générations 2 à 5 : la 1re génération n'a pas de Pokémon chromatiques, ses jeux ne sont donc pas proposés pour ce mode.
- **Nuzlocke Hardcore**, une option des modes Nuzlocke, Sololocke et Shinylocke, appliquée par Cable Club dans les jeux des générations 1 à 5. **Plafond de niveau** : aucun Pokémon de l'équipe au-dessus du Pokémon le plus fort du prochain champion d'arène, puis de la Ligue après le 8e badge ; les niveaux sont lus dans la ROM de la salle (une ROM randomisée donne les siens), et un Pokémon au-dessus part au PC jusqu'au badge suivant (l'équipe en garde toujours un). Le plafond de chaque joueur est affiché dans la salle et dans le panneau du jeu. **Pas d'objets du sac en combat** (les Poké Balls et les objets tenus restent permis) : au choix, l'objet est signalé à la salle ou la partie est perdue. **Style de combat Défini imposé** dans les options du jeu. **Équipe entière K.O. = partie perdue**.
- **Taux de chromatiques au choix** (étape Randomisation, générations 2 à 5) : « Celui du jeu (1/8192) » ou « Au choix », avec un champ pour le numérateur : 3 donne 3/8192 (environ 1 sur 2731), et on peut aller jusqu'à 8192/8192 (tous les Pokémon sauvages chromatiques), aussi dans les jeux DS. Avant, seuls quelques taux fixes étaient proposés (de 1 sur 4096 à 1 sur 256).
- **Objets rendus dans toutes les générations** : avec « Rendre les objets d'une zone annulée », les objets utilisés sur une zone annulée reviennent dans le sac en générations 1, 2, 4 et 5 aussi (avant : 3e génération seulement). Le sac des jeux de 2e génération est maintenant lu par Cable Club.
- **Combats entre joueurs** : les objets tenus comptent aussi en 2e et 3e génération (Restes, Baie, Vive Griffe…).
- **Options selon la génération** : une règle impossible dans un jeu n'est plus proposée pour lui (pas de clause chromatique ni de taux de chromatiques en 1re génération, pas d'objet tenu en 1re génération), et un mode n'affiche que les jeux avec lesquels il peut se jouer.
- La présélection de randomisation « Soul Link randomisé » s'appelle maintenant « Randomisé classique » (elle sert à tous les modes).

## 0.9.0 — 2026-10-15

- **Supprimer son compte** : Mon profil › Supprimer mon compte (avec votre mot de passe), ou sans l'application sur la page « Supprimer un compte » du serveur. Le compte, le profil, la photo, les amis, les messages et les sauvegardes envoyées au serveur sont effacés tout de suite ; dans une aventure déjà lancée avec d'autres joueurs, la place reste pour eux sous le nom « Compte supprimé ».
- **Signaler** un message (petit drapeau sur chaque message des autres) ou un joueur (sur son profil) : les modérateurs du serveur le reçoivent aussitôt et le traitent dans Mon profil › Modération (supprimer le message, rendre muet, supprimer le compte en cas de manquement grave).
- **Conditions d'utilisation et politique de confidentialité** du serveur officiel, à accepter en créant un compte ; on les retrouve dans Réglages › Autres › À propos, avec la licence et la mention de non-affiliation.
- **Nouveaux logos** Nuzlocke, Sololocke et Shinylocke (sans Poké Ball).
- **Version Play Store** de l'application Android (bundle `.aab`, mises à jour par Google Play) à côté de l'APK de GitHub ; guide de publication dans `docs/play-store`.
- La version Windows pourra être signée par SignPath (signature de code gratuite pour les logiciels libres) : l'avertissement « éditeur inconnu » de Windows disparaîtra au fil des téléchargements.

## 0.8.0 — 2026-10-15

- **Cable Club sur Android** : une application pour téléphone (`CableClub-0.8.0-android.apk`, Android 11 ou plus récent, avec un moteur web à jour : Android System WebView 111 ou plus récent, que le Play Store tient à jour), avec le même compte, les mêmes amis et les mêmes salles que sur PC. On y joue avec les joueurs sur PC, dans les mêmes règles, et les partenaires apparaissent dans le jeu comme sur PC (générations 1 à 5, Pokémon suiveur compris). L'interface est la même, allégée pour un petit écran : une barre en bas (Salles, Amis, Chat, ROMs, Moi), les réglages dans « Moi ».
- **Le jeu sur téléphone** : boutons à l'écran (croix, A B, L R, et X Y pour la DS, Start, Select), petite vibration au toucher, manettes Bluetooth ou USB reconnues (les boutons à l'écran se cachent alors), écran tactile de la DS au doigt. Téléphone en hauteur : le jeu en haut, les boutons dessous et les onglets Joueurs, Chat et Journal en bas (un onglet ouvre le panneau). Téléphone en largeur : les boutons de chaque côté du jeu, le panneau à droite. L'écran reste allumé pendant la partie, et le jeu se met en pause quand on change d'application.
- Sur téléphone, **pas d'écrans des partenaires en direct** (économie de données et de batterie) : on voit leur position dans le jeu, leur zone, leur état, leur équipe, le chat et le journal. Votre propre écran peut être partagé avec les joueurs sur PC (Réglages › Jeu).
- **ROMs sur téléphone** : « Ajouter des ROMs » ouvre le sélecteur de fichiers d'Android ; Cable Club garde sa propre copie des fichiers choisis.
- **DS sur téléphone avec melonDS DS** (bien plus rapide que DeSmuME sur un processeur de téléphone) ; Game Boy, Color et Advance avec mGBA, comme sur PC. Les sauvegardes d'état ne passent pas d'un émulateur à l'autre (PC ↔ téléphone pour la DS), les sauvegardes du jeu si.
- **Sauvegardes DS d'un appareil à l'autre** : la sauvegarde d'un jeu DS est maintenant copiée sur le serveur quand on quitte le jeu, sur PC comme sur téléphone, et « Récupérer ma sauvegarde du serveur » la remet au format de l'émulateur de l'appareil. On peut ainsi commencer une partie DS sur PC et la continuer sur téléphone, ou l'inverse.
- **Mises à jour sur téléphone** : Cable Club propose la nouvelle version comme sur PC, la télécharge, puis Android demande de confirmer l'installation (les données restent).
- Un téléphone **n'héberge pas de serveur** (il se met en veille, et les réseaux mobiles n'acceptent pas les connexions entrantes) : on y utilise le serveur Cable Club ou celui d'un ami sur PC.
- **Écran tactile DS** : une touche très brève (un clic rapide de souris) n'était parfois pas vue par le jeu ; elle compte maintenant toujours, comme un appui de touche bref.

## 0.7.1 — 2026-10-15

- **Le Pokémon des partenaires apparaît pour tous les Pokémon, dans HeartGold et SoulSilver** : la 0.7.0 ne reconnaissait que le tiers des Pokémon suiveurs (ceux dont la fiche du jeu a certaines valeurs) ; ceux qui volent ou qui bougent autrement, comme Papilusion, restaient invisibles chez les autres joueurs. Ils apparaissent maintenant tous.
- **Partenaire derrière un gros Pokémon** : un partenaire qui passe derrière un gros Pokémon suiveur (Onix, Steelix, Léviator…) est masqué par lui, comme derrière un personnage.
- **Écran d'un partenaire** : en quittant l'écran de jeu puis en y revenant, le direct d'un partenaire ne se glissait plus dans le cadre de sa photo de profil quand il en avait une.
- **« En combat » juste** : la liste des joueurs n'affiche plus « en combat » pour un joueur qui n'est pas en combat. Elle suit maintenant l'écran du jeu de chacun (aussi pour les combats de dresseurs dans les jeux de la 4e et 5e génération, que Cable Club ne voyait pas). Dans ces jeux, Cable Club ne prend plus pour une rencontre sauvage un Pokémon que le jeu garde en mémoire sans combat (les deux Pokémon de départ non choisis chez le professeur, par exemple), ce qui pouvait compter à tort une rencontre dans la zone.
- **Réglages en menu déroulant** : « Réglages », dans la colonne de gauche, se déplie en Jeu, Contrôles, Personnalisation (l'arrière-plan), Serveur et Autres (Mise à jour, Données), une page chacun. Le compte (changement de mot de passe, déconnexion) passe sur la page de votre profil, en bas à gauche.
- **Création de compte** : la touche Entrée valide le formulaire (elle revenait à « J'ai un compte »), et ce que vous aviez tapé reste en passant de « J'ai un compte » à « Nouveau compte ».
- **Contrôles** : la page ne remonte plus tout en haut à chaque touche attribuée.
- **Fermer ferme tout** : la croix rouge de la fenêtre, comme « Fermer Cable Club » dans l'icône de la zone de notification (icônes cachées), ferment le jeu, le serveur hébergé, l'icône et la fenêtre, ainsi que tout navigateur qui serait resté en arrière-plan avec la fenêtre de Cable Club. Si Cable Club est arrêté de force, le jeu en cours s'arrête aussi.
- **Nouveaux logos** : un nouveau logo Cable Club, toujours un câble en forme de C, avec ses deux prises ; et de vrais logos pour le Nuzlocke (une pierre tombale et une Poké Ball), le Sololocke (six places d'équipe, un seul Pokémon) et le Shinylocke (une Poké Ball dorée et ses étincelles).
- **Notes de version dans Réglages › Autres › Mise à jour** : les nouveautés de la version installée, et les versions précédentes repliées dessous.
- **Symbole de combat à la sortie d'un bâtiment** (4e et 5e génération) : le symbole des deux Poké Balls n'apparaît plus au-dessus d'un partenaire qui franchit une porte ou un escalier. Un partenaire n'est dit « en combat » que si l'écran du jeu reste d'un autre genre pendant plus d'une demi-seconde.

## 0.7.0 — 2026-10-14

- **Mode Nuzlocke**, avec son onglet : des salles de **1 (solo) à 10 joueurs**, chacun jouant son propre Nuzlocke (rien n'est lié entre les joueurs). Cable Club applique dans les jeux les deux règles de base : un Pokémon K.O. est mort (boîte des morts), et seul le premier Pokémon sauvage rencontré dans chaque zone peut être capturé (fuir ou le battre épuise la zone). Règles optionnelles appliquées : clause des doublons (lignées que vous possédez), clause chromatique, légendaires interdits ou non, Pokémon offerts, équipe entière K.O. = partie perdue. Règles d'honneur affichées aux joueurs (non vérifiables) : pas d'objets de soin, d'objet tenu, de Multi Exp, de Master Ball, d'évolution, de Vol, de Repousse, mode Défini, exception pour les CS, aucun Pokémon dans le PC. L'option Hardcore du Nuzlocke et les modes Sololocke et Shinylocke sont annoncés (grisés).
- **Salles solo** : le choix « 1 » devient « Solo ». Une salle solo ne peut pas être publique.
- **Soul Link de 2 à 5 joueurs** (au lieu de 1 à 4).
- **Le nombre de joueurs d'une salle se change** tant que l'aventure n'est pas lancée (liste « Joueurs » dans la salle).
- Le taux de chromatiques reste réglable dans tous les modes.

- **Le Pokémon des partenaires, visible en jeu dans HeartGold et SoulSilver** : le Pokémon qui suit chaque joueur apparaît dans le jeu des autres, à sa place, avec la même orientation et la même animation de marche que dans le jeu de son dresseur (son vrai sprite, lu dans la ROM). Les formes spéciales qui n'ont pas de sprite de suiveur ne sont pas affichées.
- **Fin du « marcher sur la tête »** (4e et 5e génération) : un partenaire qui passe derrière un personnage du jeu (PNJ, Pokémon suiveur) est maintenant masqué par lui, au lieu d'être dessiné par-dessus. Dans les jeux de 4e génération, votre propre personnage, redessiné par-dessus un partenaire qui passe derrière vous, est enfin exactement superposé au vrai (il était décalé de quelques pixels), et les partenaires sont posés exactement sur leur case.
- **Déplacements fluides en 4e génération, et un peu en 5e** : les jeux DS ne changent d'image qu'une fois sur deux, et ce que Cable Club lisait dans la mémoire (caméra, positions) ne correspondait pas toujours à l'image affichée : à chaque pas, les partenaires faisaient un petit saut de côté, 30 fois par seconde, par rapport au décor (le « déchirement »). Ils restent maintenant collés au décor.

## 0.6.2 — 2026-10-14

- **`SLT.exe` disparaît** : le programme s'appelle seulement `CableClub.exe`, et l'archive `CableClub-x.y.z-windows-x64.zip`. Depuis la 0.6.0 ou la 0.6.1, la mise à jour automatique s'en occupe : l'ancien `SLT.exe` est supprimé au premier lancement (un raccourci vers lui est à refaire vers `CableClub.exe`).
- Depuis SLT 0.5.0 ou avant, la mise à jour automatique ne peut pas installer cette version : téléchargez-la, décompressez-la, puis copiez le dossier `data` de l'ancienne version à côté de `CableClub.exe` (réglages, ROMs et salles suivent).
- Le fichier LISEZMOI fourni avec le programme présente maintenant Cable Club et le serveur officiel.

## 0.6.1 — 2026-10-14

- **Correction importante** : « Serveur Cable Club (recommandé) » ne trouvait pas le serveur officiel (« Aucun serveur Cable Club ne répond à https://play.cableclub.app »), donc impossible de s'y connecter ou d'y créer un compte. Une adresse commençant par `https://` sans port visait le port des serveurs hébergés sur un PC au lieu du port HTTPS habituel. Une adresse tapée sans `https://` ni port (« play.cableclub.app ») essaie maintenant les deux.

## 0.6.0 — 2026-10-14

SLT devient **Cable Club** : un seul endroit pour jouer à Pokémon à plusieurs, avec un onglet par mode de jeu (le Soul Link pour commencer).

- **Nouveau nom, nouveau logo** : un câble link en forme de C. Le Soul Link garde son logo dans son onglet. Le programme s'appelle maintenant `CableClub.exe` (`SLT.exe` reste là pour cette version, le temps que les mises à jour passent).
- **Onglets des modes de jeu** : à gauche, « Soul Link » regroupe vos salles, les salles publiques et votre historique. Amis, chat, ROMs et réglages restent communs à tous les modes.
- **Salles publiques** : en créant une salle (ou depuis la salle, tant que l'aventure n'est pas lancée), le chef peut la rendre publique. Elle apparaît alors dans « Salles publiques », où n'importe quel joueur du serveur peut la trouver et la rejoindre. Recherche par nom (ou par jeu, ou par chef de salle) et filtre par taille de salle (1 à 4 joueurs).
- **Chat** : un chat général pour tout le serveur, une discussion par salle (aussi dans le panneau de droite en jeu, onglet « Chat ») et des messages privés entre amis. Messages non lus signalés, anti-spam, blocage d'un joueur, suppression de ses propres messages ; les modérateurs du serveur peuvent supprimer un message ou rendre un joueur muet dans le chat général.
- **Profil** : photo de profil (n'importe quelle image, recadrée et réduite), nom affiché, bio, jeu et Pokémon favoris. Cliquer sur le nom ou la photo d'un joueur, partout dans l'application, ouvre sa fiche (ajouter en ami, message privé, bloquer, Soul Link joués et réussis).
- **Combats dans les salles** : défiez un partenaire de votre salle (onglet « Combats ») avec vos équipes actuelles, lues dans vos jeux, aux niveaux des jeux, au niveau 50 ou au niveau 100. Le combat se joue dans l'application avec le moteur de Pokémon Showdown, selon les règles de la génération de la salle : attaques, statistiques, natures, talents et objets de vos vrais Pokémon. Rien ne change dans vos parties et personne ne meurt ; les Pokémon morts du Soul Link ne combattent pas. Les autres joueurs de la salle peuvent regarder, et le résultat s'inscrit dans le journal.
- **Partenaires visibles en jeu en 4e génération** (Diamant, Perle, Platine, Or HeartGold, Argent SoulSilver) : comme en 5e génération, chaque partenaire apparaît à sa place sur la carte en 3D, avec son vrai sprite (marche et course) lu dans la ROM, dehors comme à l'intérieur des bâtiments. Vérifié sur Platine, Diamant et Or HeartGold. Toutes les générations ont maintenant les partenaires dans le jeu.
- **Morts rangés dans le PC en 2e génération** (Or, Argent, Cristal) : un Pokémon mort quitte l'équipe pour la boîte actuelle du PC, comme en 1re génération (vérifié sur Cristal, sauvegarde comprise). Après le chargement d'une sauvegarde d'état, le rangement attend le prochain lancement du jeu.
- **Taux de chromatiques** : dans la randomisation de la salle, choisissez la chance de croiser un Pokémon chromatique (du taux du jeu, 1 sur 8192, jusqu'à 1 sur 256). En 2e et 3e génération, chaque Pokémon sauvage a cette chance d'apparaître chromatique (vérifié sur Cristal et Émeraude) ; en 4e et 5e génération, le taux est écrit dans la ROM de la salle.
- **Écrans de la DS côte à côte** : un bouton de la barre du jeu place l'écran tactile à droite de l'écran du haut (retenu pour les parties suivantes).
- **Directs des partenaires** : avec plusieurs partenaires, une liste dans le panneau de droite permet de ne regarder qu'un seul jeu (les autres restent en résumé, sans image).
- **Fermer la fenêtre ferme tout** : quitter Cable Club ferme aussi le jeu et le serveur hébergé, même quand la fenêtre était réduite (avant, l'application pouvait rester ouverte en arrière-plan).
- **Héberger un serveur sur son PC** devient une option avancée, désactivée par défaut (le serveur Cable Club suffit à presque tout le monde).
- **Échanges retirés pour le moment** (le temps de leur trouver leur place dans les modes de jeu).
- « Mes ROMs » signale les ROMs introuvables (déplacées) : ajouter le dossier où elles sont maintenant les retrouve, sans rien perdre.
- **Serveur Cable Club officiel** : au premier lancement, un seul clic sur « Serveur Cable Club (recommandé) » (play.cableclub.app, certificat reconnu) : un seul compte, vos amis et vos salles où que vous soyez. Un serveur protégé par un vrai certificat (Let's Encrypt…) n'est plus « mémorisé » par empreinte : les renouvellements ne cassent plus la connexion. Si vous étiez déjà connecté à play.cableclub.app avant son certificat Let's Encrypt, Cable Club s'y reconnecte tout seul.
- **Fin automatique en 5e génération** (Noire, Blanche, Noire 2, Blanche 2) : battre le Maître de la Ligue (arrivée dans la Salle de la Gloire avec 8 badges) termine le Soul Link, comme dans les autres générations.
- Partenaires en 5e génération : un partenaire qui passe derrière vous (une case plus haut sur la carte) passe maintenant derrière votre personnage au lieu de lui marcher sur la tête.
- Partenaires en 5e génération : ils restent affichés pendant les dialogues classiques (derrière la boîte de texte), mais toujours pas pendant un combat, un fondu ou un flash blanc.

## 0.5.0 — 2026-10-11

- **Partenaires visibles en jeu en 5e génération** (Noire, Blanche, Noire 2, Blanche 2) : chaque partenaire apparaît dans votre partie à sa vraie place sur la carte en 3D, placé avec la caméra de votre jeu (même quand elle tourne ou zoome), plus petit au loin, avec son vrai sprite (lu dans la ROM), son pseudo et ses icônes (combat, dialogue…). Vérifié sur Blanche 2. À vélo ou en surf, le partenaire s'affiche pour l'instant debout.
- **Partenaires fluides** (toutes générations) : leurs déplacements sont rejoués image par image, comme dans leur jeu, au lieu d'avancer par à-coups.
- Correction : les autres joueurs ne tremblent plus quand vous marchez (leur position sautait d'une case au début de chacun de vos pas, et pouvait avoir une image de retard sur la carte).
- Retiré : « Un Pokémon unique par zone » (toutes les rencontres d'une zone étaient le même Pokémon tant qu'il n'était ni capturé ni mis K.O.). « Une ROM différente par joueur » donne maintenant simplement une seed à chacun. Une salle pas encore lancée peut régénérer sa ROM (bouton « Régénérer » dans la salle) ; les parties en cours gardent la leur.
- Pour que les déplacements soient fluides, l'hôte du serveur et les joueurs doivent tous passer à cette version.

## 0.4.4 — 2026-10-11

- **Correction importante** pour ceux qui rejoignent le serveur d'un ami : après une coupure de la connexion (ou juste après s'être connecté), chaque clic dans le menu ramenait à l'écran de connexion, voire au code d'invitation. La session n'était pas perdue : l'interface confondait un message du serveur avec son propre état.

## 0.4.3 — 2026-10-11

- **Correction importante** : les versions 0.4.1 et 0.4.2 téléchargées depuis GitHub restaient bloquées sur « Chargement de SLT… » (des fichiers de l'interface manquaient dans le zip). Si vous êtes dans ce cas, téléchargez cette version à la main et décompressez-la par-dessus l'ancienne : vos données sont conservées.
- La construction vérifie désormais que l'interface est complète avant de publier une version.

## 0.4.2 — 2026-10-11

- **Volume** : un curseur de volume dans la barre du jeu (à côté du bouton son), retenu pour les parties suivantes.
- Correction : changer le volume dans les Réglages s'applique aussi au jeu déjà lancé (avant, seulement au lancement suivant).
- Correction : après avoir choisi la vitesse de l'avance rapide, les touches du jeu répondent tout de suite.
- Correction : déplacer ou renommer le dossier de SLT ne fait plus « perdre » les ROMs des salles.

## 0.4.1 — 2026-10-11

- **Mises à jour automatiques** : SLT regarde sur GitHub s'il existe une nouvelle version et la propose ; un clic la télécharge, l'installe et redémarre SLT. Vos données (salles, sauvegardes, ROMs, réglages) sont conservées. Réglages › Mises à jour : version installée, recherche manuelle, vérification automatique.
- **Nouveaux réglages par défaut d'un Soul Link** : Pokémon fixes complètement aléatoires et au niveau adapté, pas d'Œuf Chance offert, sauvages sans contrainte de force ni captures facilitées, dresseurs sans contrainte de force, compatibilités CT et tuteurs complètement au hasard, tuteurs avec assez d'attaques offensives, pas de talents inutiles en combat. Les légendaires sont autorisés par défaut (Règles).
- Correction : la liste des options avancées de randomisation (et l'étape Règles de la création de salle) ne remonte plus tout en haut quand on change une option ou d'onglet.

## 0.4.0 — 2026-10-11

- Joueurs visibles dans le jeu en 1re et 2e génération (en plus de la 3e) ; ils ne s'affichent plus par-dessus un combat, un dialogue, un menu ou une transition ; un partenaire en combat reste visible avec son icône.
- Corrections 3e génération : avatar invisible en combat (Rubis / Saphir) et avatar remplacé par une attaque (Feu Sacré).
- Surnom du lien écrit dans le jeu en générations 1, 2, 4 et 5 ; Pokémon morts rangés dans le PC en générations 1, 4 et 5.
- Fin automatique contre le Maître de la Ligue (règle activée par défaut).
- Le Soul Link commence quand chaque joueur a une Poké Ball : avant, un K.O. ne compte pas.
- Échanges entre partenaires (chromatiques seulement tant que la Ligue n'est pas vaincue).
- Statistiques : seule la première mort d'un lien compte.
- Starters complètement aléatoires par défaut ; explications des options de randomisation réécrites.

## 0.3.1

- Explication claire quand un ami n'arrive pas à rejoindre un serveur (code copié trop tôt, box fermée, pare-feu, double NAT…).
- Vérification du pare-feu Windows et bouton pour autoriser SLT.

## 0.3.0

- Jeu à distance sans VPN : connexion chiffrée, ouverture automatique du port de la box (UPnP), code d'invitation.
