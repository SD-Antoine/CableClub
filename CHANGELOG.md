# Nouveautés de SLT

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
