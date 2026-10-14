# Nouveautés de Cable Club (anciennement SLT)

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
