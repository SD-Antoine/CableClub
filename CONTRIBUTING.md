# Contribuer à Cable Club

Merci de votre intérêt. Cable Club est un projet de fans non officiel, compatible avec les jeux
Pokémon, sans lien avec Nintendo, Creatures ou GAME FREAK. Quelques règles protègent le projet et
ceux qui y contribuent : une contribution qui ne les respecte pas est refusée, même utile.

## Ce qui n'entre jamais dans le dépôt

- **Aucun contenu de jeu** : ROM, BIOS, firmware, clé, sauvegarde, sprite, image, son, musique, texte,
  carte ou capture d'écran tirés d'un jeu (Nintendo, Creatures, GAME FREAK ou autre ayant droit). Ce que
  l'application affiche est lu à l'exécution dans le fichier du joueur.
- **Aucun lien ni aide** pour obtenir des jeux ou des fichiers de jeu.
- **Aucun moyen de contourner une protection** (BIOS ou clé propriétaire) : les émulateurs tournent sans.
- **Aucune fonction qui envoie une ROM** ou une ressource d'un jeu par le réseau.
- **Aucun secret** (clé de signature, mot de passe, jeton).

Les hooks du dépôt bloquent l'ajout des fichiers de jeu et de sauvegarde les plus courants ; ils ne
remplacent pas votre vigilance.

## Licences

- Le code de Cable Club est sous **GPLv3** (`LICENSE`) : une contribution est publiée sous cette licence.
- Toute dépendance nouvelle doit être compatible GPLv3 et être ajoutée à `THIRD-PARTY.txt` (auteur,
  licence, source exacte avec version ou commit, usage). En parler avant de l'ajouter.

## Modes de jeu

Un mode se partage comme un fichier `.ccmode.json` (format : `docs/modes.md`) : des règles, des
paramètres et vos textes, jamais de code, d'image, de son ni d'adresse. Vous en êtes l'auteur, ou vous
avez le droit de le partager, sous la licence que vous indiquez.

## Modules pour d'autres jeux (fan games, homebrew)

Le cadre est dans `docs/fan-games.md`. En résumé :

1. **Seulement les catégories 1 et 2** : un homebrew ou une œuvre originale dont l'auteur autorise
   l'usage, ou un jeu sous une licence qui le permet. Les fan games qui reprennent une franchise tierce
   (catégorie 3, la plupart des fan games Pokémon) ne sont **pas acceptés**, ni même mentionnés.
2. **Un manifeste `adapter.json`** vérifié par `AdapterManifest` : empreintes du fichier attendu (jamais
   le fichier), catégorie, licence du jeu avec son adresse, auteur et licence du module, structures de
   base, capacités.
3. **Vous déclarez la licence du jeu et en êtes responsable** ; un **mainteneur la vérifie** (licence
   lue à l'adresse donnée, ou autorisation écrite de l'auteur du jeu) et l'ajoute à `THIRD-PARTY.txt`
   **avant toute fusion**. Sans cette vérification, le module n'est pas fusionné.
4. **Le module ne lit que la mémoire du jeu** et applique les règles par écriture en RAM ; il ne décide
   rien (le serveur reste l'arbitre) et ne patche pas le code du jeu.
5. **Aucune promesse** : un module peut être refusé, ou retiré plus tard si le jeu change de licence ou
   si un ayant droit le demande.

## Façon de travailler

- Java 21 sans framework, interface en HTML/CSS/JS sans bundler ; pas de nouvelle bibliothèque sans
  accord.
- Commentaires du code en anglais, textes visibles en français ; suivre le style du fichier modifié.
- Petits commits atomiques, messages en français.
- Avant de proposer : `.\tests\golden.ps1` (arbitre), `.\tests\security\security-tests.ps1`,
  `node tests/capabilities.test.mjs`, `node tests/objectives.test.mjs`. Un changement de l'arbitre
  (`src/slt/server/Room.java` et ce qu'il appelle) passe par les scénarios golden.
- Ne jamais toucher à un comportement marqué « ✓ vérifié » (README, CHANGELOG) sans un test de
  non-régression qui le couvre.

## Sécurité

Une faille se signale **en privé** (voir `SECURITY.md`), jamais dans une issue publique. Les tests
d'intrusion ne visent qu'une instance de test locale (`tests/security/start-test-server.ps1`), jamais
le serveur officiel ni celui de quelqu'un d'autre.
