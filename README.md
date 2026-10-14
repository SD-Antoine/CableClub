# Cable Club (anciennement SLT — Soul Link Together)

Application Windows pour jouer en **Soul Link à plusieurs (1 à 4 joueurs)** sur
les jeux Pokémon de la **1re à la 5e génération**, chacun dans sa propre
partie, **en se voyant en direct**, avec les règles du Soul Link **appliquées
automatiquement dans les jeux**.

**[⬇ Télécharger la dernière version](https://github.com/SD-Antoine/CableClub/releases/latest)**
(`CableClub-x.y.z-windows-x64.zip` : décompressez-le et lancez `CableClub.exe`, rien à
installer). Les nouveautés de chaque version sont dans [CHANGELOG.md](CHANGELOG.md).

- **Comptes et amis** : chaque joueur a un compte sur le serveur SLT de son
  groupe ; on ajoute ses amis pour les inviter en un clic (présence en ligne).
- **Salles de 1 à 4 joueurs** : le chef crée la salle (jeu, règles,
  randomisation), invite ses amis ou partage un code. Chacun génère sa ROM,
  se déclare **Prêt**, et le chef appuie sur **Lancer**. Il peut ensuite
  **ouvrir / fermer l'aventure** (pause pour tout le monde) et **ajuster les
  règles à tout moment**.
- **Le jeu s'affiche dans la fenêtre principale** de SLT (émulateurs mGBA et
  DeSmuME intégrés, son, manette Xbox, clavier configurable, écran tactile DS à
  la souris, avance rapide, plein écran).
- **Les joueurs se voient dans le jeu** (générations 1 à 3 et 5) : chaque
  partenaire apparaît dans votre partie, à sa vraie position sur la carte, avec
  son vrai sprite (direction, marche, vélo), **son pseudo au-dessus de la tête**
  et une icône d'état : combat (deux Poké Balls et un choc), dialogue (trois
  points qui sautent), échange (deux Poké Balls et deux flèches), déconnecté
  (Wi-Fi barré). Ses déplacements sont rejoués image par image, comme dans son
  jeu. Les partenaires ne s'affichent que quand vous avez la main (jamais
  par-dessus un combat, un dialogue, un menu ou une transition) ; un partenaire
  en combat reste visible là où il a quitté la carte, avec son icône. En 5e
  génération, le décor est en 3D : les partenaires sont placés avec la caméra
  de votre jeu (même quand elle tourne ou zoome), plus petits au loin, et leur
  sprite est lu dans la ROM.
- **Échanges entre partenaires** depuis la salle ou le panneau du jeu : le
  Pokémon passe d'un jeu à l'autre en quelques secondes (chromatiques
  uniquement tant que les deux joueurs n'ont pas battu le Maître).
- **Écrans en direct** pour tous les jeux : à côté de son jeu, chacun voit
  l'écran de ses partenaires (cliquable pour l'agrandir), leur zone, leur équipe
  et leurs PV, et le journal de la salle.
- **Journal de chaque action** : entrée dans une zone, rencontre, capture
  (espèce, niveau, zone), combat de dresseur lancé / gagné / perdu, Pokémon mort,
  objet utilisé (en combat ou non), acheté, vendu ou obtenu, badge, évolution,
  surnom, Maître de la Ligue battu…
- **Historique** : toutes les parties (en cours, terminées, abandonnées) avec
  leur journal complet et leur bilan.
- **Bilan de fin** : par joueur, Pokémon capturés, morts, mis K.O., chromatiques,
  objets utilisés en combat et hors combat (lesquels et combien), achats.
- **Mises à jour automatiques** : SLT signale chaque nouvelle version publiée
  sur GitHub ; un clic la télécharge, l'installe à la place de l'ancienne et
  redémarre SLT, sans toucher aux données (salles, sauvegardes, ROMs, réglages).
- Interface sobre : thème **Onyx** (noir délavé) par défaut, **Blanc** ou
  **Bleu nuit** au choix.

> SLT ne contient **aucun jeu ni aucune ROM**. Chaque joueur utilise la copie de
> son propre jeu. Projet de fans non officiel, sous licence GPLv3.

## Règles du Soul Link appliquées par SLT

Le **serveur SLT est l'arbitre** : chaque jeu lui envoie ce qui s'y passe (lu
dans la mémoire de l'émulateur), il applique les règles de la salle et renvoie à
chaque jeu ce qu'il doit appliquer.

| Règle | Ce que fait SLT |
|---|---|
| Un seul Pokémon par zone | La première rencontre d'une zone compte. Une fois un Pokémon **capturé ou mis K.O.** dans une zone, une nouvelle capture y est refusée : le Pokémon est **relâché, c'est-à-dire supprimé du jeu** (de l'équipe ou du PC). |
| Pokémon liés par zone | Quand chaque joueur a capturé le Pokémon d'une zone, ils sont liés. |
| Surnom choisi par le chef | Dès que tout le monde a capturé, **les jeux se mettent en pause** et le chef choisit le surnom ; il est **écrit dans chaque jeu**, puis les parties reprennent. |
| Mort partagée | Si un Pokémon lié tombe K.O., ses âmes liées meurent aussi : **mises K.O. dans les jeux, impossibles à soigner** (même au Centre Pokémon) et **déposées dans la boîte « MORTS » du PC**. |
| Zone annulée | Si un joueur met K.O. le premier Pokémon d'une zone, la zone est annulée : les Pokémon capturés là sont relâchés (supprimés du jeu) et **les objets utilisés pour les capturer sont rendus dans le sac**. |
| Clause des doublons | Une lignée d'évolution déjà possédée dans la salle ne compte pas : la rencontre est ignorée et une capture est relâchée. Elle ne compte pas non plus pour la zone : **tant que ce joueur n'a pas capturé un vrai Pokémon de la zone, le lien n'est pas complet et le chef ne peut pas le nommer** (la salle affiche qui manque). Le partenaire lié par la même zone ne compte pas comme doublon (sinon une zone à espèce unique serait bloquée). |
| Clause chromatique | Un chromatique peut toujours être capturé (sans lien). |
| Légendaires / Pokémon offerts | Légendaires autorisés par défaut, Pokémon offerts interdits par défaut : un Pokémon interdit, capturé ou reçu, est relâché aussitôt. |
| Départ aux Poké Balls | Le Soul Link ne commence vraiment que quand **chaque joueur a au moins une Poké Ball** : avant, un Pokémon K.O. (le starter par exemple) ne meurt pas et les rencontres ne comptent pas. La salle affiche qui attend encore. |
| Morts dans les statistiques | Seul le joueur dont le Pokémon est tombé en premier compte la mort ; les âmes liées qui meurent avec lui ne comptent pas chez les partenaires. |
| Échanges | Un Pokémon échangé quitte son lien et appartient au receveur (sans lien). Tant que les deux joueurs n'ont pas battu le Maître, seuls les chromatiques s'échangent. |
| Fin | **Fin automatique contre le Maître** (règle activée par défaut) : SLT détecte la victoire dans le jeu et termine le Soul Link (quand tous l'ont battu, ou dès le premier, au choix). Sinon, défaite si une équipe entière tombe, ou fin déclarée par le chef. |

## Randomisation

Moteur : Universal Pokemon Randomizer ZX 4.6.1 (préréglages + toutes les options
avancées), plus les ajouts de SLT :

- **Même ROM pour tout le monde** : même jeu + mêmes options + même seed ⇒ ROM
  identique pour chaque joueur, vérifiée par son CRC avant le lancement.
- **Une ROM différente par joueur** (option) : chaque joueur a sa propre seed.
- **Vendeurs secondaires** randomisés — le second vendeur des boutiques et
  Centres Pokémon, celui des Balls inhabituelles et des CT, les étages de grand
  magasin, les marchés, les herboristes… Le vendeur principal (Poké Balls,
  Potions, stock qui évolue avec l'aventure) ne change pas. Jamais de Master Ball
  ni d'objet qui se revend plus cher qu'il ne s'achète ; un vendeur de CT vend
  d'autres CT (jamais de CS). En 3e génération, SLT trouve ces vendeurs
  directement dans les scripts de la ROM (toutes versions et langues ; Émeraude
  FR : 11 vendeurs) ; en 4e et 5e génération, il s'appuie sur le randomiseur.
- **Objets au sol** et **CT** aléatoires (jamais les CS).

Les ROMs ne transitent jamais par le réseau : chaque joueur génère la sienne à
partir de sa propre copie du jeu. « Télécharger » donne une copie de la ROM de la
salle ; sur un nouveau PC, on la **régénère à l'identique** (même seed) et on
**récupère sa sauvegarde depuis le serveur** (SLT y copie la sauvegarde du jeu).

## État d’avancement (v0.4.1)

| | 1re gén. | 2e gén. | 3e gén. | 4e gén. | 5e gén. |
|---|---|---|---|---|---|
| Suivi (captures, zones, K.O., évolutions, badges, rencontres) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Joueurs visibles dans le jeu (sprite, pseudo, icône d'état) | **✓ vérifié** (Rouge) | **✓ vérifié** (Cristal) | **✓ vérifié** (Rubis, Émeraude, Rouge Feu) | écrans | **✓ vérifié** (Blanche 2, à pied)⁵ |
| Mort liée en jeu (K.O., impossible à soigner) | ✓ | ✓ | ✓ | ✓ | ✓ |
| Surnom du lien écrit dans le jeu | **✓ vérifié** | ✓ | **✓ vérifié** | ✓² | **✓ vérifié** (Blanc 2) |
| Pokémon morts rangés dans le PC | **✓ vérifié** (boîte actuelle) | — (reste K.O.)³ | **✓ vérifié** (boîte MORTS) | ✓² (boîte 18 MORTS) | **✓ vérifié** (boîte 24 MORTS) |
| Relâché = supprimé du jeu | **✓ vérifié** | équipe | ✓ | ✓ | ✓ |
| Départ aux premières Poké Balls | ✓ | ✓ | ✓ | ✓ | ✓ |
| Échanges entre partenaires | ✓ | ✓ | **✓ vérifié** | ✓ | ✓ |
| Maître de la Ligue battu (fin automatique) | **✓ vérifié**⁴ | ✓ (Panthéon) | ✓ | ✓ (salle du Panthéon) | chef |
| Vendeurs secondaires randomisés | — | — | **✓ vérifié** | ✓ (UPR) | ✓ (UPR) |
| Objets utilisés / achetés / obtenus | ✓ | — | **✓ vérifié** | ✓ | ✓ |
| Objets rendus (zone annulée) | — | — | **✓ vérifié** | — | — |
| Combats de dresseurs (lancé / gagné / perdu) | ✓ | Cristal | **✓ vérifié** | — | — |

**✓ vérifié** : testé dans le jeu (versions françaises). ✓ : implémenté et
testé hors du jeu ou sur une autre version de la même famille. « chef » : le chef
de salle déclare la fin depuis la salle. « écrans » : les partenaires se voient
sur le côté (aperçu en direct).

² Structure de sauvegarde vérifiée sur Diamant, Platine et HeartGold FR (boîtes,
noms de boîtes, alphabet des surnoms) ; en 4e et 5e génération, l'équipe n'est
modifiée qu'au **prochain changement de lieu** (pendant un combat, le jeu
travaille sur une copie de l'équipe et l'écraserait).
³ En 2e génération, la boîte courante est dans la mémoire de sauvegarde de la
cartouche, que l'émulateur ne montre qu'en copie : SLT ne peut pas y écrire.
⁴ Simulé (compteur du Panthéon).
⁵ À vélo ou en surf, le partenaire s'affiche debout (sprite de marche) ; il
passe toujours devant le décor (jamais caché par un arbre ou un bâtiment).

Pas encore fait :

- **Joueurs visibles dans le jeu en 4e génération** : écrans côte à côte pour
  l'instant (la méthode de la 5e génération devrait s'y adapter).
- **Combats entre partenaires** : un vrai combat en lien entre deux jeux
  demande un câble link / une connexion sans fil que les émulateurs intégrés ne
  savent pas faire à travers Internet.
- **Fin automatique en 5e génération** (salle du Panthéon non identifiée).
- **Patch des scripts des ROMs** (légendaires, PNJ qui donnent un Pokémon) :
  SLT applique ces règles **à l'exécution** (Pokémon relâché à la réception).
- **Objets donnés par les PNJ** randomisés (hors objets clés).

## Utilisation

1. Lancer `CableClub.exe` (Java et les émulateurs sont inclus).
2. Premier lancement : **Serveur Cable Club (recommandé)** (play.cableclub.app),
   ou le serveur d'un ami (code d'invitation), ou héberger le serveur sur ce PC
   (options avancées), puis créer son compte.
3. **Mes ROMs** → ajouter le dossier de ses ROMs.
4. **Salles** → **Nouvelle salle** (jeu, nombre de joueurs, règles,
   randomisation) → **Inviter** ses amis.
5. Chacun : ROM générée automatiquement → **Je suis prêt**. Le chef : **Lancer**.
6. **Jouer** : le jeu s'ouvre dans la fenêtre ; tout le reste est automatique.

Contrôles par défaut (AZERTY) : flèches, X = A, W = B, S / Q = X / Y (DS),
A / Z = L / R, Entrée = Start, Retour arrière = Select, Tab (maintenir) ou T =
avance rapide, P = pause, F11 = plein écran. Modifiables dans **Réglages → Contrôles**.

### Le serveur SLT

Les comptes, amis, salles, journaux et copies des sauvegardes vivent sur un
serveur SLT :

- **Dans l'application** : Réglages → Serveur → Avancé → « Héberger un serveur sur ce PC »
  (port TCP 47330). Windows demande d'autoriser SLT dans le pare-feu.
- **Seul**, sur un PC ou un serveur allumé en permanence :
  `CableClub.exe --server [--port=47330] [--data=dossier] [--name="Mon serveur"]`
  (le code d'invitation s'affiche dans son journal).

**Jouer avec des amis à distance, sans VPN** (ce que Hamachi ou Tailscale
feraient, intégré à SLT) :

- **« Accessible depuis Internet »** : SLT demande à la box (UPnP) de rediriger
  le port vers ce PC, le renouvelle tant que SLT tourne et le referme quand
  l'hébergement s'arrête. Si la box refuse, SLT l'indique (activer « UPnP »
  dans la box, ou ouvrir le port à la main).
- **Code d'invitation** (`SLT1-…`, bouton « Copier » dans Réglages → Serveur) :
  il contient toutes les adresses du serveur (adresse publique de la box,
  adresses IPv6 publiques, adresse locale) et l'empreinte de son certificat.
  L'ami le colle à la place d'une adresse : SLT essaie toutes les adresses à
  la fois et garde la première qui répond avec le bon certificat, et repasse
  sur une autre si elle cesse de répondre.
- **Connexion chiffrée** (HTTPS) : le serveur crée son propre certificat au
  premier lancement ; les amis le reconnaissent par son empreinte (dans le code,
  ou mémorisée au premier contact avec une adresse tapée à la main). Option
  `--http` du serveur seul pour revenir en clair (derrière un reverse proxy).

- **Diagnostic** : si aucune adresse ne répond, SLT dit pourquoi (code copié
  avant l'ouverture du port, adresse Internet sans réponse = pare-feu ou box,
  connexion refusée = SLT fermé chez l'hôte, autre certificat = code périmé) ;
  chaque essai est noté dans `slt.log`. Côté hôte, Réglages > Serveur signale
  une box derrière une autre box, une adresse IPv4 partagée par l'opérateur, ou
  un pare-feu Windows qui bloque SLT (bouton « Autoriser SLT dans le pare-feu »,
  qui demande l'accord de Windows).

Contrairement à un VPN, il n'y a pas de serveur relais au milieu : si la box
de l'hôte refuse toute ouverture (UPnP désactivé, adresse IPv4 partagée) et que
l'ami n'a pas l'IPv6, un VPN (Tailscale…) reste nécessaire.

Les mots de passe sont stockés hachés (PBKDF2-SHA256), les jetons de session
seulement par leur empreinte.

## Architecture

```
src/slt/
  Main                  CableClub.exe : application, --server ou --player
  server/               serveur SLT (JDK HttpServer, JSON sur disque, flux SSE)
    Accounts            comptes, sessions, amis
    Rooms, Room         salles, arbitre du Soul Link, journal, aperçus, sauvegardes
    Hub                 présence et diffusion temps réel
  client/               application d'un joueur (API locale 127.0.0.1)
    ServerLink          connexion au serveur (requêtes + flux relayé à l'interface)
    Library, RoomRoms   ROMs du joueur, génération de la ROM de chaque salle
    Rando, SltPatches   pont vers UPR ZX, ajouts SLT (boutiques, lignées)
    Players             lancement du lecteur, relais des événements / directives
    Hosting             serveur SLT intégré
    Updates             mises à jour depuis les releases GitHub
  player/               lecteur sans fenêtre : libretro via l'API FFM de Java 21
    Session, WsServer   boucle d'émulation, images vers la fenêtre en WebSocket
    TrackerHost         lecture mémoire + application des règles en jeu
    GbPresence, Gen3Presence, Gen5Presence   joueurs visibles en jeu (Gen5Sprites : sprites lus dans la ROM)
    track/              lecteurs mémoire (GbReader, Gen3Reader, NdsReader), TrackerEngine
web/                    interface (HTML / CSS / JS modules, sans framework)
build.ps1               compilation + dist\CableClub\CableClub.exe (jpackage)
```

## Compiler

Prérequis : JDK 21 et git.

```powershell
.\build.ps1              # dist\CableClub\CableClub.exe et dist\CableClub-<version>-windows-x64.zip
.\build.ps1 -NoPackage   # compile seulement
.\build.ps1 -Out dossier # paquet dans un autre dossier que dist (tests)
.\run-dev.ps1            # application en mode développement (interface servie depuis web\)
.\run-dev.ps1 -Data data2 -Port 47341   # 2e joueur sur le même PC (tests)
.\run-dev.ps1 -Server    # serveur seul
```

## Publier une version

1. Mettre le numéro dans `src/slt/Version.java` et décrire les nouveautés dans
   une section `## x.y.z` de [CHANGELOG.md](CHANGELOG.md).
2. Commit, puis `git tag vx.y.z` et `git push --follow-tags`.
3. GitHub Actions ([release.yml](.github/workflows/release.yml)) compile SLT sous
   Windows et publie la release (zip + empreinte SHA-256, notes tirées du
   CHANGELOG). Les SLT installés la proposent alors à leurs joueurs.

## Licence

GPLv3 — voir [LICENSE](LICENSE) et [THIRD-PARTY.txt](THIRD-PARTY.txt).
Pokémon © Nintendo, Creatures Inc., GAME FREAK inc.
