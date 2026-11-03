# Parties vérifiées (point 3e)

Les classements ne comptent que des **parties vérifiées**. Ce document décrit la mécanique, publique ;
les règles de détection du serveur officiel ne sont **pas** dans ce dépôt (elles le resteraient lisibles
par ceux qu'elles visent).

## Ce qui fait qu'une partie est vérifiée

Une partie terminée (bilan de fin de la salle) entre dans un classement si :
- aucun **contrôle** du serveur ne l'a signalée, ou un modérateur l'a examinée et déclarée « vérifiée » ;
- aucun modérateur ne l'a **retirée des classements** (après un signalement de joueur, ou de lui-même).

Une partie signalée par un contrôle et pas encore examinée apparaît au joueur « en cours de
vérification », sans dire pourquoi. Les statistiques personnelles (onglet Historique) comptent toutes ses
parties.

## Les contrôles

`src/slt/server/RunCheck.java` : un contrôle reçoit, dans l'ordre, chaque événement que le jeu d'un joueur
envoie (`docs/events.md`), avec la salle, le mode, le jeu, les règles et une mémoire propre à cette
partie (gardée avec la salle). Il peut **lever un signalement** (un code et un texte pour les
modérateurs) ; il ne change jamais ni la partie ni la salle. Un contrôle qui échoue (exception) est
ignoré pour cet événement : la salle continue.

Les contrôles sont chargés au démarrage par `ServiceLoader` depuis le classpath du serveur
(`META-INF/services/slt.server.RunCheck` dans un jar). **Le serveur publié avec Cable Club n'en a
aucun** : sur un serveur auto-hébergé, toute partie terminée est vérifiée. Le serveur officiel ajoute le
sien à son classpath ; ce module n'est pas distribué (s'il l'était, la GPL s'appliquerait à lui).

## Signalements et modération

- Un joueur signale les résultats d'un autre depuis un classement (drapeau sur sa ligne) :
  `POST /api/report` avec `leaderboard` (le mode) et `userId`.
- Les modérateurs voient dans Mon profil › Modération les signalements, et sous « Parties à vérifier »
  les parties signalées par les contrôles (`GET /api/admin/runs`, ou `?userId=…&mode=…` pour les parties
  d'un joueur), avec ce que les contrôles ont relevé.
- Décision : « Vérifiée » ou « Retirer des classements » (`POST /api/admin/runs/review`), gardée dans
  `parties-verifiees.json` ; un compte supprimé emporte les décisions qui le concernent.
