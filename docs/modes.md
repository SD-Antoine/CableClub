# Fichiers de mode (`.ccmode.json`)

Un mode de jeu Cable Club est un **fichier déclaratif** : il compose les primitives du moteur
(`src/slt/server/Mode.java`) avec les règles d'un catalogue fermé. Il ne contient **jamais** de code,
de binaire, d'image, de son ni d'adresse : tout ce qui sort du format est refusé, avec un message
clair, par le validateur `src/slt/server/ModeSpec.java`. Les modes officiels sont eux-mêmes des
fichiers de ce format (`web/js/data/modes/`), lus et vérifiés comme les autres.

## Exemple

```json
{
  "schemaVersion": 1,
  "id": "shiny-sololocke",
  "name": "Shiny Sololocke",
  "description": "Un seul Pokémon dans l’équipe, et seuls les chromatiques se capturent.",
  "author": "Pseudo de l’auteur",
  "license": "CC-BY-4.0",
  "players": { "min": 1, "max": 4 },
  "needs": ["shinies"],
  "engine": { "links": "none", "encounters": "shiniesOnly", "team": "one" },
  "rules": {
    "autoEnd": true, "endWhen": "all", "soloParty": "auto", "soloLoss": "next",
    "oneShinyPerZone": { "default": true, "locked": true }
  },
  "win": ["champion"],
  "lose": ["soloLost"],
  "objectives": [{ "type": "badges", "count": 8 }]
}
```

## Champs

| Champ | Contenu |
|---|---|
| `schemaVersion` | `1` |
| `id` | 3 à 32 caractères : minuscules, chiffres, tirets |
| `name`, `author`, `license` | texte de 1 à 40 caractères (`license` : la licence du texte du mode, ex. `CC0-1.0`) |
| `description` | texte de 600 caractères au plus |
| `players` | `min` et `max`, de 1 à 10 (au moins 2 quand les joueurs sont liés) |
| `needs` | capacités du jeu nécessaires (`web/js/data/capabilities.json`) ; `shinies` obligatoire si seuls les chromatiques comptent |
| `engine` | les trois primitives (ci-dessous) |
| `rules` | règles du catalogue proposées par le mode : valeur par défaut, ou `{ "default": …, "locked": true }` pour la fixer |
| `win` | `champion` : battre le Maître de la Ligue termine (règles `autoEnd`, `endWhen`) |
| `lose` | `teamWiped` (équipe entière K.O., règle `wipeEndsRun`, équipe libre) ou `soloLost` (le Pokémon seul meurt, règle `soloLoss`, équipe d'un seul) |
| `objectives` | buts affichés (point 3), sans effet sur l'arbitrage : `champion`, `badges` (`count` 1–8), `captures` (`count` 1–500) |

Textes : pas de caractères de contrôle, pas de `<` ni `>`, **aucune adresse ni nom de domaine** (pas de
lien vers une ROM, une image ou un site). Fichier de 32 Ko au plus.

## Primitives (`engine`)

| Primitive | Valeurs |
|---|---|
| `links` | `shared` : les Pokémon d'une même zone sont liés entre les joueurs (une mort tue les âmes liées) ; `none` : chacun sa partie |
| `encounters` | `firstPerZone` : la première rencontre de chaque zone compte ; `shiniesOnly` : seuls les chromatiques se capturent (un combat n'épuise jamais une zone) |
| `team` | `free` ; `one` : un seul Pokémon vivant dans l'équipe, un autre du PC prend la relève quand il meurt |

Les 8 combinaisons tournent sur le même moteur. Les modes historiques en sont quatre : Soul Link
(`shared`, `firstPerZone`, `free`), Nuzlocke (`none`, `firstPerZone`, `free`), Sololocke (`none`,
`firstPerZone`, `one`), Shinylocke (`none`, `shiniesOnly`, `free`). Les quatre autres sont couvertes
par les scénarios `tests/golden/combo-*.json`.

## Règles du catalogue

Une règle qui n'a pas de sens avec les primitives choisies ne peut garder que sa valeur neutre (entre
parenthèses).

| Règle | Valeurs | A du sens avec |
|---|---|---|
| `shinyClause` | vrai / faux | `encounters: firstPerZone` (faux) |
| `oneShinyPerZone` | vrai / faux | `encounters: shiniesOnly` (faux) |
| `dupesClause`, `giftPokemon`, `legendaries`, `deathBox`, `startWithBalls`, `saveStates` | vrai / faux | toujours |
| `naming` | `leader` / `free` | `links: shared` (`free`) |
| `pauseForNaming`, `refundCancelled` | vrai / faux | `links: shared` (faux) |
| `wipeEndsRun` | vrai / faux | `team: free` (faux) |
| `autoEnd` | vrai / faux | `win: champion` (faux) |
| `endWhen` | `all` / `first` | `win: champion` (`all`) |
| règles classiques `honor…`, `hardcore` | vrai / faux | `links: none` (faux) |
| `hardcoreItems` | `report` / `lose` | `links: none` (`report`) |
| `soloParty` | `auto` / `honor` | `team: one` (`auto`) |
| `soloLoss` | `next` / `end` | `team: one` (`next`) |

`honorNoEvolution` (pas d'évolution) modifie la ROM de la salle : **réservée aux modes officiels**
(garde-fou « la RAM avant la ROM »). Toutes les autres règles s'appliquent en écrivant dans la RAM.

Dans une salle, une règle fixée par le mode (`locked`) ne change pas ; une valeur hors des valeurs
permises, ou différente de la neutre pour une règle sans objet, est refusée.

## Importer un mode

- `POST /api/modes/check` (avec une session) : le corps est le fichier ; la réponse est sa forme
  normalisée (`{ "mode": … }`), ou une erreur 400 qui dit ce qui ne va pas.
- `POST /api/rooms` avec `modeFile` (le fichier, en objet ou en texte) à la place de `mode` : le fichier
  est vérifié de la même façon, puis **copié dans la salle** (sa sauvegarde le garde ; il est relu et
  revérifié au redémarrage du serveur). Le jeu de la salle doit avoir les capacités de `needs`, et le
  nombre de joueurs reste dans `players`.
- Un mode importé ne peut pas reprendre l'`id` d'un mode officiel.
