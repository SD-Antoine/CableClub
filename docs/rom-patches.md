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
- « Jeu original » (randomisation désactivée) ne modifie la ROM que pour la règle « Pas d'évolution » ;
  sans elle, la ROM du joueur est jouée telle quelle (les salles DS anciennes gardent aussi leur taux de
  chromatiques dans la ROM, voir ci-dessous).

## Inventaire

| Modification | Où | Ce qui change | Statut | Alternative en RAM | Décision |
|---|---|---|---|---|---|
| Randomisation (Pokémon sauvages, de départ, fixes, dresseurs, talents, attaques, CT, objets) | `client/Rando.java` (Universal Pokemon Randomizer ZX) | les **données** de la ROM, et du code pour quelques options (tweaks : texte instantané, course, Pokédex National…) | ⚠️ par nature | aucune : la randomisation *est* une ROM modifiée | reste (choix du joueur, désactivable : « Jeu original ») |
| Vendeurs secondaires | `SltPatches.secondaryShops` (4e et 5e gén., par le randomiseur), `SltPatches.gen3Shops` (3e gén., scripts de la ROM) | les listes d'objets des vendeurs | ⚠️ (partie de la randomisation) | aucune utile : la liste est lue dans la ROM quand le vendeur parle | reste (option de randomisation) |
| Lignées d'évolution (clause des doublons) | `SltPatches.families` | **rien** : lecture seule | ✅ | — | — |
| « Pas d'évolution » (règle classique, modes sans liens) | `SltPatches.apply`, `noEvolution` | les **tables d'évolution** sont vidées (niveau, pierres, échange, bonheur…) | ⚠️ | voir ci-dessous : **aucune aussi fiable** | **reste** |
| Taux de chromatiques, 2e à 5e gén. | `player/ShinyBoost.java` | **rien dans la ROM** : écriture en RAM du Pokémon sauvage pendant la transition du combat | ✅ | (c'est la RAM) | — |
| Taux de chromatiques, salles DS enregistrées avant le passage à la RAM | `SltPatches.dsShinyRate` | le **code** : le test « valeur chromatique < 8 » de la fonction unique du jeu devient « < N » | ⚠️ (code) | voir ci-dessous | **gardé pour ces seules salles** : tous leurs joueurs doivent générer la même ROM (contrôle du CRC) |

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

## Taux de chromatiques DS : passé en RAM

**Comment** : quand le terrain disparaît (transition du combat), avec la chance voulue, le Pokémon sauvage
reçoit un **nouveau PID chromatique**, écrit dans toutes ses copies en RAM : mêmes nature, sexe et talent
(4e gén. : PID % 25, octet bas ; 5e gén. : octet bas, bit 16), blocs remis dans l'ordre du nouveau PID, partie
combat rechiffrée. Les copies que le combat fait ensuite reprennent ce PID, et le Pokémon est un vrai
chromatique, partout.

**Le moment d'écriture, mesuré** (ROM françaises, sans fenêtre, taux 8192/8192) :

| Famille testée | Terrain parti → copies du combat → PID en clair (images) | Vérifié |
|---|---|---|
| Platine | 656 → 747 → 772 | combat |
| SoulSilver | 161 → 281 → 304 | combat, étincelles |
| Blanche 2 | 187 → 341 → 379 | combat, capture |
| Blanche | 117 → 216 → 252 | combat, capture |

Le suivi passe toutes les 30 images : il a au moins 3 occasions. Si une copie en clair existe déjà, il est trop
tard et le Pokémon reste tel quel. Diamant, Perle, Noire et Noire 2 sont supposés suivre leur famille.

**Protections** : un Pokémon de l'équipe, des boîtes ou dont une copie est dans la sauvegarde (pension) n'est
jamais touché, même si son écran de résumé ou le PC en fait une copie qui ressemble à un Pokémon sauvage.

**Différences avec le patch, acceptées** : le taux ne vaut que pour les Pokémon **sauvages** (comme en 2e et 3e
génération), plus pour ceux des dresseurs ni les œufs ; en échange, les chromatiques sont de vrais chromatiques.
Non mesuré : un Pokémon reçu en cadeau pendant qu'un écran du jeu cache le terrain pourrait aussi être concerné.

**Le patch reste pour les salles anciennes** : le serveur marque `shinyRam` toute randomisation enregistrée
depuis ce changement. Une salle sans cette marque garde son patch et son seuil, et ne reçoit pas le taux en RAM
(sinon il compterait deux fois) : ses joueurs doivent pouvoir régénérer la même ROM, contrôlée par son CRC.

## Statut juridique

| Règle | Aujourd'hui | Avec l'alternative en RAM |
|---|---|---|
| Taux de chromatiques, 2e à 5e gén. | ✅ (RAM) | — |
| Taux de chromatiques, salles DS anciennes | ⚠️ (patch du code) | gardé pour que leur ROM reste identique |
| « Pas d'évolution » | ⚠️ (patch des données) | pas d'alternative fiable : reste ⚠️ |
| Randomisation, vendeurs secondaires | ⚠️ (par nature) | reste ⚠️ |

Toute nouvelle règle s'applique d'abord en RAM ; un patch de ROM n'est ajouté que s'il n'existe pas
d'autre voie, et il est inscrit dans ce document avec la raison.
