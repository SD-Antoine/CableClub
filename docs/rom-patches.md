# Modifications de la ROM de salle : inventaire et alternatives en RAM (point 8)

Cable Club préfère appliquer une règle **en écrivant dans la RAM** du jeu (variables de la partie)
plutôt qu'en modifiant la ROM (programme et données du jeu) : c'est le garde-fou « la RAM avant la
ROM » de `CLAUDE.md`, d'après l'arrêt de la CJUE *Sony c. Datel* (C-159/23, 17 octobre 2024), pour
qui modifier des variables en mémoire ne touche pas au programme protégé. Une modification de la ROM
reste un acte d'adaptation du programme : statut ⚠️.

Ce document recense ce que Cable Club change dans la ROM, et pour chaque changement s'il existe une
alternative en RAM aussi fiable. **Un patch n'est remplacé que si l'alternative est aussi fiable ;
sinon il reste, et la raison est notée ici.**

Rappels qui valent pour tout ce qui suit :
- la ROM de salle est **générée sur l'appareil de chaque joueur**, à partir de sa propre copie
  (`client/RoomRoms.java`), avec la même seed pour tous ; elle ne passe jamais par le réseau ;
- « Jeu original » (randomisation désactivée) ne modifie la ROM que pour les deux règles ci-dessous
  (« Pas d'évolution », taux de chromatiques DS) ; sans elles, la ROM du joueur est jouée telle quelle.

## Inventaire

| Modification | Où | Ce qui change | Statut | Alternative en RAM | Décision |
|---|---|---|---|---|---|
| Randomisation (Pokémon sauvages, de départ, fixes, dresseurs, talents, attaques, CT, objets) | `client/Rando.java` (Universal Pokemon Randomizer ZX) | les **données** de la ROM, et du code pour quelques options (tweaks : texte instantané, course, Pokédex National…) | ⚠️ par nature | aucune : la randomisation *est* une ROM modifiée | reste (choix du joueur, désactivable : « Jeu original ») |
| Vendeurs secondaires | `SltPatches.secondaryShops` (4e et 5e gén., par le randomiseur), `SltPatches.gen3Shops` (3e gén., scripts de la ROM) | les listes d'objets des vendeurs | ⚠️ (partie de la randomisation) | aucune utile : la liste est lue dans la ROM quand le vendeur parle | reste (option de randomisation) |
| Lignées d'évolution (clause des doublons) | `SltPatches.families` | **rien** : lecture seule | ✅ | — | — |
| « Pas d'évolution » (règle classique, modes sans liens) | `SltPatches.apply`, `noEvolution` | les **tables d'évolution** sont vidées (niveau, pierres, échange, bonheur…) | ⚠️ | voir ci-dessous : **aucune aussi fiable** | **reste** |
| Taux de chromatiques, jeux DS (4e et 5e gén.) | `SltPatches.dsShinyRate` | le **code** : le test « valeur chromatique < 8 » de la fonction unique du jeu devient « < N » | ⚠️ (code) | voir ci-dessous : **plausible**, à prouver jeu par jeu | reste pour l'instant ; prototype possible (8b) |
| Taux de chromatiques, 2e et 3e gén. | `player/ShinyBoost.java` | **rien dans la ROM** : écriture en RAM du Pokémon sauvage pendant la transition du combat | ✅ | (c'est déjà la RAM) | — |

## « Pas d'évolution » : pourquoi le patch reste

Le patch vide les tables d'évolution de la ROM de salle : **aucune** évolution n'a lieu, quel que soit
le déclencheur, sans scène d'évolution ni état incohérent. Les alternatives étudiées :

1. **Pierre Stase tenue** (2e à 5e gén.) : elle bloque les évolutions par niveau, échange et bonheur,
   **pas celles par pierre** ; elle occupe l'objet tenu (en conflit avec la règle « Pas d'objet tenu »,
   et avec les objets du joueur) ; elle n'existe pas en 1re génération. → incomplet.
2. **Annuler la scène d'évolution** (l'équivalent d'appuyer sur B pendant l'animation) : il faudrait
   reconnaître la scène dans la RAM de chacun des 20 jeux (et de leurs langues) ; une évolution par
   pierre **ne s'annule pas** ; le joueur voit la scène commencer. → incomplet et fragile.
3. **Revenir à l'espèce d'avant** après l'évolution (le suivi la détecte déjà) : la scène se joue,
   les statistiques, le talent, les attaques apprises et le Pokédex doivent être remis en état, dans des
   données chiffrées et contrôlées par une somme (3e à 5e gén.). → fragile, visible, risque de
   corrompre un Pokémon.
4. **Modifier la copie des tables d'évolution que le jeu charge en RAM** (jeux DS seulement : ils lisent
   leurs données dans des fichiers de la cartouche, copiés en mémoire) : il faudrait trouver, jeu par
   jeu, quand cette copie est faite et la réécrire à temps ; en 1re à 3e génération, les tables sont lues
   directement dans la ROM (pas de copie en RAM). → seulement pour la DS, à prouver, aucune garantie.

Aucune n'est aussi fiable que le patch : **il reste**, avec son statut ⚠️. La règle se choisit avant le
lancement de l'aventure (la ROM de salle est régénérée), et elle n'est disponible que dans les modes
officiels (un mode importé ne peut pas la demander : `ModeSpec`, règle réservée).

## Taux de chromatiques DS : une alternative plausible, à prouver

**Ce que fait le patch** : les jeux DS n'ont qu'une fonction qui décide si un Pokémon est chromatique
(`PID` et identifiants du dresseur combinés, « < 8 » sur 65 536). Le patch change ce seuil. Effets :
- le taux vaut pour **tous** les Pokémon du jeu (sauvages, dresseurs, œufs, Pokémon fixes) ;
- un Pokémon dont la valeur est entre 8 et N **paraît** chromatique dans cette ROM seulement : envoyé
  dans un autre jeu, il ne l'est plus.

**L'alternative** (celle de `ShinyBoost` en 3e génération) : quand un combat sauvage commence, avec la
chance voulue, donner au Pokémon sauvage un **nouveau PID chromatique** qui garde sa nature, son sexe et
son talent, puis rechiffrer ses données (ordre des blocs et clé tirés du PID, somme de contrôle). Le
Pokémon est alors un vrai chromatique, partout. Ce que cela demande :
- les structures des 4e et 5e générations (chiffrement des blocs et de la partie combat, somme de
  contrôle ; en 5e génération, nature stockée à part et talent caché) : en partie déjà lues par
  `track/NdsPkm.java` ;
- **le bon moment d'écriture**, dans chaque famille de jeux (Diamant/Perle/Platine, HeartGold/
  SoulSilver, Noir/Blanc, Noir 2/Blanc 2) : après la création du Pokémon sauvage, avant que le combat en
  fasse sa copie et choisisse l'animation (étincelles) ; c'est ce que `ShinyBoost` règle en 3e
  génération pendant la transition ;
- des essais sur des copies des ROM du joueur, dans un dossier temporaire, avec le lecteur sans fenêtre
  (`player/ScriptRunner.java`), puis des vérifications en jeu sur PC (DeSmuME) et sur téléphone
  (melonDS DS), son coupé.

**Différences de comportement** à accepter ou non : le taux ne vaudrait plus que pour les Pokémon
**sauvages** (comme en 2e et 3e génération), plus pour ceux des dresseurs ni les œufs ; en échange, les
chromatiques seraient de vrais chromatiques. Le texte de l'étape « Randomisation », qui annonce
aujourd'hui « écrit dans la ROM de la salle : vaut pour tous les Pokémon du jeu », changerait.

**Décision** : le patch reste tant qu'un prototype n'a pas prouvé, sur chaque famille de jeux, un
résultat aussi fiable (point 8b, à lancer sur accord, en commençant par Platine). Si une famille
résiste, elle garde le patch et la raison est ajoutée ici.

## Statut juridique

| Règle | Aujourd'hui | Avec l'alternative en RAM |
|---|---|---|
| Taux de chromatiques, 2e et 3e gén. | ✅ (RAM) | — |
| Taux de chromatiques, 4e et 5e gén. | ⚠️ (patch du code) | ✅ si le prototype aboutit |
| « Pas d'évolution » | ⚠️ (patch des données) | pas d'alternative fiable : reste ⚠️ |
| Randomisation, vendeurs secondaires | ⚠️ (par nature) | reste ⚠️ |

Toute nouvelle règle s'applique d'abord en RAM ; un patch de ROM n'est ajouté que s'il n'existe pas
d'autre voie, et il est inscrit dans ce document avec la raison.
