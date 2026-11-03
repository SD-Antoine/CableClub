# Jeux autres que les versions officielles (fan games, homebrew)

Cable Club est fait pour les jeux Pokémon officiels des générations 1 à 5. Ce document fixe le cadre
dans lequel un **module d'adaptation** pour un autre jeu peut être proposé. Ce cadre n'est **pas une
promesse** : un module n'est accepté que s'il respecte tout ce qui suit, et rien n'oblige à prendre en
charge un jeu qui est soumis.

## Les trois catégories

| Catégorie | Exemples | Ce que fait Cable Club |
|---|---|---|
| **1. Homebrew et œuvres originales** dont l'auteur autorise l'usage | un jeu original sous licence libre, ou avec l'accord écrit de son auteur | peut être pris en charge par un module (`"category": "homebrew"`) |
| **2. Jeux et moteurs sous une licence qui permet l'intégration** | un moteur libre et les jeux faits avec, si leur licence le permet | peut être pris en charge par un module (`"category": "licensed"`) |
| **3. Fan games qui reprennent une franchise tierce** (personnages, créatures, noms, cartes, sprites, musiques, textes d'un jeu existant) | la grande majorité des fan games et ROM hacks Pokémon | **refusés** : ni distribués, ni hébergés, ni listés, ni mis en avant, ni pris en charge |

Pour la catégorie 3, même un simple module serait un référencement : son manifeste nommerait le jeu et
donnerait son empreinte. Le validateur (`src/slt/server/AdapterManifest.java`) refuse donc toute autre
catégorie que `homebrew` et `licensed`, ainsi qu'un nom de jeu qui reprend une franchise tierce
(« Pokémon », « Pocket Monsters »…), quelle que soit la catégorie déclarée. La catégorie déclarée par
l'auteur du module est **vérifiée par un mainteneur** avant toute fusion (voir `CONTRIBUTING.md`).

Statut juridique : catégories 1 et 2 ✅ quand la licence est vérifiée et notée dans `THIRD-PARTY.txt` ;
catégorie 3 ❌, donc exclue.

## Exigences techniques

Un jeu ne peut être pris en charge que s'il remplit toutes ces conditions :

1. **Il tourne dans un émulateur que Cable Club intègre déjà** : mGBA (Game Boy, Game Boy Color, Game
   Boy Advance), DeSmuME sur PC et melonDS DS sur Android (Nintendo DS). Un jeu sur moteur PC (RPG Maker,
   Pokémon Essentials…) ne tourne pas dans un émulateur : hors de ce cadre (point 4c du plan, pas
   commencé).
2. **Ses structures en mémoire sont celles d'un jeu officiel** que les adaptateurs savent lire (`base`
   dans le manifeste : `emerald`, `firered`, `platinum`…), ou documentées par l'auteur du module au même
   niveau de détail. Un module ne change jamais la façon dont l'arbitre décide : il ne connaît que la
   mémoire du jeu (règle d'architecture de `CLAUDE.md`).
3. **Il fournit les événements communs** de `docs/events.md` (zones, rencontres, captures, K.O., équipe,
   badges…) et applique les directives par **écriture en RAM** ; aucun patch du code du jeu (garde-fou
   « la RAM avant la ROM »).
4. **Il déclare ce qu'il sait faire** (`capabilities`, mêmes noms que `web/js/data/capabilities.json`) :
   un mode ou une règle qui a besoin d'une capacité absente n'est pas proposé pour ce jeu.
5. **Le joueur apporte son propre fichier.** Le module le reconnaît à ses empreintes (CRC32 et SHA-1) ;
   le fichier n'est jamais inclus, téléchargé ni transmis par le réseau.

## Le manifeste `adapter.json`

Chaque module a un manifeste, vérifié par `AdapterManifest` (8 Ko au plus, champs fermés : tout champ
inconnu est refusé, aucune donnée binaire). Exemple, pour un jeu **fictif** :
[`docs/adapter.example.json`](adapter.example.json).

| Champ | Contenu |
|---|---|
| `schemaVersion` | `1` |
| `id` | identifiant du module : minuscules, chiffres, tirets (3 à 40) |
| `game.name`, `game.version` | nom et version du jeu ciblé (sans nom de franchise tierce) |
| `game.platform` | `gb`, `gbc`, `gba` ou `nds`, cohérent avec `base` |
| `game.crc32`, `game.sha1` | empreintes du fichier attendu (8 et 40 chiffres hexadécimaux) — **jamais le fichier** |
| `category` | `homebrew` ou `licensed` |
| `gameLicense.name`, `gameLicense.url` | licence du jeu, et adresse `https://` où la lire (ou l'autorisation de l'auteur) |
| `module.author`, `module.license` | auteur du module et sa licence (compatible GPLv3) |
| `base` | jeu officiel dont le module reprend les structures en mémoire |
| `capabilities` | ce que le module sait faire : `true` / `false` par capacité |

Le 4a fixe ce contrat ; l'application ne charge encore aucun module. Le point 4b (profils d'adresses
pour des jeux GBA proches d'Émeraude ou de Rouge Feu) s'appuiera dessus.

## Ce qu'un module ne contient jamais

Aucune ROM, aucun BIOS ni firmware, aucun sprite, son, texte ou carte extrait d'un jeu, aucun lien pour
en obtenir. Ce qui est affiché est lu à l'exécution dans le fichier du joueur, comme pour les jeux
officiels. Les hooks du dépôt bloquent déjà l'ajout de fichiers de jeu et de sauvegardes.
