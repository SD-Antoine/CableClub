# Schéma des événements

Le **vocabulaire commun** entre les deux couches basses de Cable Club : les **adaptateurs de jeux**
(`src/slt/player/track/*`, plus `TrackerHost`) lisent la mémoire de l'émulateur et **émettent** ces
événements ; le **serveur arbitre** (`src/slt/server/Room.java`) les **consomme** et en déduit le
journal et les directives. Un adaptateur ne connaît que ce vocabulaire ; il n'expose jamais d'adresse
mémoire. C'est le contrat que le point 1 du plan d'évolution stabilise.

- Un événement est un objet JSON avec un champ `type`. Pour `encounter` et `battle`, un champ `phase`
  (`start` ou `end`) précise la forme.
- **obligatoire** : présent et non nul. **optionnel** : peut être absent ou explicitement `null`.
- Unités : `level` 1–100 ; `species` = numéro National Dex ; `item`/`move` = identifiants **internes au
  jeu** (dépendent de la génération, voir `ItemRules`) ; `map` = identifiant de carte interne ; les
  identifiants `uid` sont des chaînes propres à la partie.
- Le validateur `slt.server.EventSchema` applique ce tableau (champs et types), et le harnais de
  non-régression (`tests/golden.ps1`) le passe sur chaque scénario. Le serveur, lui, **reste tolérant**
  (il lit les champs avec des valeurs par défaut et ignore un type inconnu) : ce schéma documente et
  teste le contrat, il ne fait pas rejeter un événement en production.

Origine : « GB » = `GbReader` (générations 1–2), « Gen3 » = `Gen3Reader`, « NDS » = `NdsReader`
(générations 4–5). Sauf mention, un événement existe dans toutes les générations prises en charge.

## Événements (émis par les adaptateurs, consommés par `Room`)

### `location` — changement de lieu
| champ | type | | 
|---|---|---|
| `zone` | chaîne | obligatoire — nom du lieu affiché |
| `map` | nombre | optionnel — identifiant de carte interne |

Consommé par `location()`. Sert à ouvrir/mettre à jour une zone. Origine : tous.

### `encounter` / `phase: "start"` — début d'un combat sauvage
| champ | type | |
|---|---|---|
| `uid` | chaîne | obligatoire — identifiant du Pokémon sauvage |
| `zone` | chaîne | obligatoire |
| `species` | nombre | obligatoire |
| `level` | nombre | obligatoire |
| `speciesName` | chaîne | optionnel |
| `shiny` | booléen | optionnel |
| `balls` | nombre | optionnel — Poké Balls au sac à cet instant |

Consommé par `encounterStart()` (classe la rencontre : zone, chromatique, doublon, légendaire…).

### `encounter` / `phase: "end"` — fin d'un combat sauvage (fuite ou K.O.)
| champ | type | |
|---|---|---|
| `uid` | chaîne | obligatoire |
| `outcome` | chaîne | optionnel — `failed`/`unknown` |
| `wildFainted` | booléen | optionnel — le sauvage est K.O. |
| `species`, `level`, `balls` | nombre | optionnel |
| `speciesName`, `zone` | chaîne | optionnel |
| `shiny` | booléen | optionnel |

Consommé par `encounterEnd()`. En mode individuel, une première rencontre épuisée (fuite ou K.O.)
consomme la zone ; en Soul Link, seul le K.O. du premier Pokémon annule la zone.

### `capture` — un Pokémon rejoint le joueur
| champ | type | |
|---|---|---|
| `uid` | chaîne | obligatoire |
| `species` | nombre | obligatoire |
| `level` | nombre | obligatoire |
| `origin` | chaîne | obligatoire — `new` (nouveau), `wild` (capturé), `trade` (offert/échangé), `existing` (déjà dans la sauvegarde) |
| `speciesName`, `nickname`, `zone` | chaîne | optionnel |
| `inParty` | booléen | optionnel — rejoint l'équipe (sinon le PC : « Notepad Clause ») |
| `fainted`, `shiny` | booléen | optionnel |

Consommé par `capture()` (lien, bonus, relâché, Pokémon de CS, départ…).

### `update` — un Pokémon change (niveau, évolution, surnom)
| champ | type | |
|---|---|---|
| `uid` | chaîne | obligatoire |
| `species`, `level` | nombre | optionnel |
| `speciesName`, `nickname` | chaîne | optionnel |
| `evolved` | booléen | optionnel — l'espèce a changé par évolution |

Consommé par `update()`. Un gain de niveau sur un Pokémon de CS le fait relâcher.

### `faint` — un Pokémon tombe K.O.
| champ | type | |
|---|---|---|
| `uid` | chaîne | obligatoire |
| `species`, `level` | nombre | optionnel |
| `speciesName`, `nickname`, `zone` | chaîne | optionnel |
| `doomed` | booléen | optionnel — déjà condamné (mort/relâché) |

Consommé par `faint()` (mort, âme liée, Sololocke).

### `party` — composition de l'équipe (identifiants)
| champ | type | |
|---|---|---|
| `uids` | tableau de chaînes | obligatoire |

Consommé par `live()` → `parkCheck()` (Sololocke, plafond Hardcore).

### `team` — équipe détaillée (vue en direct)
| champ | type | |
|---|---|---|
| `mons` | tableau d'objets | obligatoire — chacun : `uid`, `species`, `speciesName`, `nickname`, `level`, `hp`, `maxHp`, `shiny`, `dead`, `set` (dont `exp`) |

Consommé par `live()` → `hmCheck()` (l'expérience d'un Pokémon de CS).

### `badges` — nombre de badges
| champ | type | |
|---|---|---|
| `count` | nombre | obligatoire |

Consommé par `badges()` (Hardcore : relève le plafond de niveau).

### `whiteout` — toute l'équipe est K.O.
Aucun champ. Consommé par `whiteout()` (Hardcore / `wipeEndsRun` : partie perdue).

### `battle` / `phase: "start"` — début d'un combat de dresseur
| champ | type | |
|---|---|---|
| `kind` | chaîne | obligatoire — `trainer` (les combats sauvages passent par `encounter`) |
| `size` | nombre | optionnel — taille de l'équipe adverse |
| `trainer` | chaîne | optionnel — nom du dresseur |

### `battle` / `phase: "end"` — fin d'un combat de dresseur
| champ | type | |
|---|---|---|
| `kind` | chaîne | obligatoire — `trainer` |
| `outcome` | chaîne | optionnel — `won` / `lost` |
| `trainer` | chaîne | optionnel |

Consommé par `battle()`. Non lu en 4e/5e génération (le jeu DS ne le signale pas).

### `items` — le sac change
| champ | type | |
|---|---|---|
| `changes` | tableau d'objets | obligatoire — chacun : `item` (nombre), `name` (chaîne), `delta` (nombre, < 0 = utilisé/vendu, > 0 = obtenu/acheté) |
| `money` | nombre | optionnel — variation d'argent (achat/vente) |
| `battle` | booléen | optionnel — pendant un combat |
| `fight` | booléen | optionnel — le jeu l'affirme (générations 1–3) ; absent sur DS |

Consommé par `items()` (statistiques, remboursements, Hardcore : objet en combat).

### `champion` — le Maître de la Ligue est battu
Aucun champ. Consommé par `champion()` (fin automatique selon `endWhen`/`autoEnd`).

### `balls` — le sac contient (ou non) des Poké Balls
| champ | type | |
|---|---|---|
| `has` | booléen | obligatoire |

Consommé par `balls()` → `checkStart()` (le run démarre à la première Poké Ball).

### `caps` — niveaux des champions dans la ROM du joueur (Hardcore)
| champ | type | |
|---|---|---|
| `gyms` | tableau de 8 nombres | obligatoire — Pokémon le plus fort de chaque champion d'arène |
| `league` | nombre | optionnel — plafond de la Ligue |

Consommé par `caps()` (plafond de niveau Hardcore).

### `removed` — un objet interdit quitte le sac (règles classiques)
| champ | type | |
|---|---|---|
| `item` | nombre | obligatoire |
| `name` | chaîne | optionnel |
| `qty` | nombre | optionnel |
| `rule` | chaîne | optionnel — catégorie (`heal`, `repel`, `expShare`, `masterBall`…) |

Émis par `TrackerHost`, consommé par `ruled()` (journal). Origine : tous.

### `held` — un objet tenu est retiré ou remis au sac
| champ | type | |
|---|---|---|
| `item` | nombre | obligatoire |
| `uid`, `nickname` | chaîne | optionnel — le Pokémon porteur |
| `name` | chaîne | optionnel |
| `rule` | chaîne | optionnel — `held` (remis, pas d'objet tenu) ou une catégorie interdite |

Émis par `TrackerHost`, consommé par `ruled()`. Générations 2–5.

### `forgot` — un Pokémon oublie une attaque (pas de Vol)
| champ | type | |
|---|---|---|
| `move` | nombre | obligatoire |
| `uid`, `nickname` | chaîne | optionnel |

Émis par `TrackerHost`, consommé par `ruled()`.

## Actions de l'interface (pour mémoire — ce ne sont pas des événements de jeu)

Elles viennent de l'interface via l'API du serveur, pas des adaptateurs : `launch` (lancer
l'aventure), `adventure` (ouvrir/fermer), `end` (déclarer la fin), `name` (nommer un lien), `hm`
(exception pour les CS), `ack` (le jeu a rendu les objets remboursés), `settings` (changer les règles).
Elles ne passent pas par `EventSchema`.
