# CableClub — consignes pour Claude Code

CableClub (anciennement SLT, Soul Link Together) coordonne des parties Pokémon jouées chacune dans
**son propre émulateur** (cœurs libretro : mGBA pour GB/GBC/GBA, DeSmuME sur PC et melonDS DS sur
Android pour la DS). L'état du jeu est **lu dans la RAM**, le **serveur arbitre**, puis renvoie des
**directives** que le lecteur applique en **écrivant dans la RAM**. CableClub reste une **surcouche**
autour des jeux émulés : ne jamais remplacer les menus, combats ou écrans des jeux par des recréations,
ni viser un moteur autonome.

Modes actuels : Soul Link, Nuzlocke, Sololocke, Shinylocke, option Nuzlocke Hardcore, règles
classiques. Générations 1 à 5. Version dans `src/slt/Version.java`, historique dans `CHANGELOG.md`.

## Architecture en trois couches

1. **Moteur de modes** (serveur, l'arbitre) — `src/slt/server/`
   - `Room.java` : l'état de la salle et les mécanismes communs (zones, liens, journal, directives) ;
     `event()` passe chaque événement au mode de la salle, puis `dispatch()` (le `switch` sur les
     types) pour ceux qui n'ont pas encore de règle propre. Sa vue et son fichier : `RoomStore.java` ;
     les échanges entre joueurs (désactivés, `RoomTrades.ENABLED`) : `RoomTrades.java`. Ce qui est enregistré
     se lit par des vues typées qui gardent la map lue et la réécrivent telle quelle (`RoomGame`, `RoomEnd`,
     `MemberRom`, `ModeGallery.Entry`, `Reports.Report`, `RunReviews.Verdict`) : un fichier enregistré ne change
     jamais à cause d'elles (`StoredFilesTest`) ; ne pas les remplacer par des `record` qui recréeraient la map.
   - `Mode.java` : le moteur des modes — rencontres (`EncounterLogic`), captures (`CaptureLogic`),
     morts, équipe, Hardcore, démarrage et fin (`RunLogic`) — composé de trois primitives (joueurs
     liés ou non, première rencontre ou chromatiques seuls, équipe libre ou d'un seul Pokémon) ; les
     8 combinaisons tournent sur le même `Engine`. Aucun handler de `Room` ne teste le mode.
   - `ModeSpec.java` : un mode est un fichier déclaratif `.ccmode.json` (format : `docs/modes.md`) —
     catalogue fermé des règles (`web/js/data/modes/catalog.json`, lu aussi par l'interface),
     validateur strict (pas de code, de binaire, d'image ni d'adresse ; `honorNoEvolution`, écrite
     dans la ROM, réservée aux modes officiels), registre des modes officiels (`web/js/data/modes/`),
     `family()` (l'onglet d'un mode). `ModeSpecTest` vérifie que les fichiers officiels redonnent
     exactement les règles d'avant ; chaque nouvelle combinaison a son scénario golden.
   - Mode importé : `POST /api/rooms` avec `modeFile` (copié dans la salle, relu au redémarrage par
     `ModeSpec.stored`), `POST /api/modes/check` (vérification). `ModeGallery.java` : galerie des modes
     publics (publication rattachée au compte, signalement via `/api/report`, retrait par un modérateur) ;
     une salle d'un mode importé n'est publique que si ce mode est publié et non retiré.
   - `Rooms.java` : salles (autorisations, salle d'attente, parties terminées gardées en résumé) ; à côté,
     `RoomJournal.java` (journal), `LiveScreens.java` (écrans en direct, JPEG relayés à la demande),
     `RoomGhosts.java` (positions en jeu), `CloudSaves.java` (copies cloud des sauvegardes).
   - `Records.java` : les parties terminées (bilan de fin de chaque salle) → statistiques d'un joueur
     (`/api/me/stats`) et classements (`/api/leaderboard`, mode officiel, jeu, nombre de joueurs ; seuls
     les comptes `ranked`, option du profil désactivée par défaut). Objectifs affichés :
     `web/js/data/objectives.js`. Seules les **parties vérifiées** comptent (`docs/run-checks.md`) :
     `RunCheck` (contrôles chargés par `ServiceLoader`, aucun dans le serveur publié ; ceux du serveur
     officiel sont dans un dépôt privé, **jamais dans ce dépôt**), `RunReviews` (décisions des
     modérateurs). Ne jamais écrire de règle de détection dans le dépôt public.
   - `ServerApi.java`, `Accounts.java`, `Hub.java` (présence, flux SSE).
   - Côté interface : `web/js/data/modes.js` (les mêmes fichiers de mode, `specOf`, `familyOf`),
     `web/js/data/rules.js` (affichage des règles : libellé, texte, sections), l'éditeur
     `web/js/views/modes.js` (« Mes modes », gardés dans le navigateur, exportés en fichier).
   - Capacités des jeux : `web/js/data/capabilities.json` (par génération, exceptions par jeu, besoins
     des règles ; ceux d'un mode sont dans son fichier, `needs`), lu par `Capabilities.java` (refus côté serveur) et par
     `web/js/data/capabilities.js` (ce que l'interface propose). Une capacité se relève dans les
     adaptateurs avant d'être écrite là ; `tests/capabilities.test.mjs` et `CapabilitiesTest` la vérifient.
2. **Adaptateurs de jeux** (seuls à connaître la mémoire d'un jeu) — `src/slt/player/`
   - `track/GameReader.java` : interface d'adaptateur (lecture `Snapshot`, écritures `Patch`/`Write`),
     implémentée par `GbReader` (gén. 1-2), `Gen3Reader`, `NdsReader` (gén. 4-5, avec `NdsPkm`).
   - `track/TrackerEngine.java` produit les événements ; `TrackerHost.java` applique les directives.
     Le vocabulaire d'événements (contrat adaptateurs ↔ arbitre) est documenté dans `docs/events.md` et
     validé par `src/slt/server/EventSchema.java` (le harnais golden le passe sur chaque scénario).
   - Présence en jeu : `GbPresence`, `Gen3Presence`, `Gen4Presence`, `Gen5Presence` (`Gen5Sprites`
     lit les sprites dans la ROM du joueur à l'exécution), `MapChange`.
   - `ShinyBoost.java` : taux de chromatiques par écriture en RAM (gén. 2-5).
   - `ScriptRunner.java` : lecteur sans fenêtre piloté par script (tests hors interface).
   - Autres jeux (point 4) : cadre dans `docs/fan-games.md` ; manifeste d'adaptateur `adapter.json`
     vérifié par `src/slt/server/AdapterManifest.java` (catégories `homebrew` et `licensed` seulement :
     un fan game reprenant une franchise tierce est refusé ; empreintes du fichier, jamais le fichier).
     Pas encore chargé par l'application (4b). Licence d'un module vérifiée par un mainteneur avant
     fusion (`CONTRIBUTING.md`).
3. **Interface** — `web/` (HTML/CSS/JS modules, sans framework ni bundler ; seul `web/js/vendor/`
   contient du code tiers) et l'application locale `src/slt/client/` (`LocalApi` sur 127.0.0.1,
   `ServerLink`, `Library`, `RoomRoms`, `Players`, `Updates`).

Règles d'architecture :
- **Un mode ne connaît jamais la mémoire d'un jeu** : il ne manipule que des événements communs et
  des actions abstraites. Les adresses et structures restent dans les adaptateurs.
- **Le serveur reste l'arbitre** et **les directives sont le seul canal** vers les adaptateurs.
- Chaque jeu déclare ce qu'il sait faire ; un mode refuse proprement de démarrer si une règle dont il
  a besoin n'est pas prise en charge.

Cœurs d'émulation : `cores.lock.json` (source connue de chaque binaire livré) — mGBA et DeSmuME du
buildbot libretro, commit lu dans le binaire par `build.ps1` / `android/app/build.gradle.kts` (la
construction échoue sinon) ; melonDS DS : release officielle v1.4.0 épinglée par SHA-256, arm64-v8a
seulement. Les constructions écrivent `coeurs.txt` / `coeurs.json` ; la release joint les sources exactes
(`.github/cores-sources.ps1`). Changer un cœur = changer `cores.lock.json` et revérifier en jeu.

ROM de salle : `client/RoomRoms.java` la génère **localement** depuis la copie du joueur, via
`client/Rando.java` (Universal Pokemon Randomizer ZX 4.6.1, GPLv3, compilé par `build.ps1`) puis
`client/SltPatches.java` (vendeurs, retrait des évolutions ; patch du test chromatique des jeux DS gardé pour
les seules salles enregistrées avant `shinyRam`, afin que leur ROM reste identique pour chaque joueur).

## Conventions

- Java 21 sans framework : JDK `HttpServer`, JSON maison `slt.Json`, pas de nouvelle bibliothèque
  sans accord. Les sources `src/` et `web/` servent aussi à Android (`android/app/build.gradle.kts`) :
  n'utiliser que des API Java disponibles sur les anciens Android (par exemple pas de
  `InputStream.readNBytes(int)`).
- Commentaires du code **en anglais** (comme le reste du dépôt), textes visibles **en français**.
- Suivre le style du fichier modifié (nommage, densité de commentaires, idiomes).
- Fichiers écrits depuis PowerShell : **UTF-8 sans BOM** (un BOM dans `Version.java` casse javac).
- Petits commits atomiques, messages en français, format des commits existants.
- Toute nouvelle dépendance : compatible GPLv3 et ajoutée à `THIRD-PARTY.txt` (auteur, licence,
  source exacte avec version ou commit, usage).

## Commandes

```powershell
.\build.ps1                  # compile + dist\CableClub\CableClub.exe et le .zip
.\build.ps1 -NoPackage       # compile seulement (build\app-classes, build\lib)
.\build.ps1 -Out <dossier>   # paquet ailleurs que dist (tests : utiliser un dossier temporaire)
.\run-dev.ps1                # application en développement, interface servie depuis web\
.\run-dev.ps1 -Data data2 -Port 47341   # 2e joueur sur le même PC
.\run-dev.ps1 -Server        # serveur seul (données dans .\serveur)
.\build-android.ps1 -Debug   # APK de test (signé avec la clé de débogage)
.\build-android.ps1          # APK des releases GitHub (clé de signature hors dépôt)
.\build-android.ps1 -Play    # bundle Play Store
.\tests\golden.ps1           # non-régression de l'arbitre : scénarios tests\golden\*.json rejoués sur Room
.\tests\golden.ps1 -Update   # réécrit les références .txt (seulement pour un changement de comportement décidé)
node tests/battleteam.test.mjs   # équipes des combats dans le simulateur
node tests/capabilities.test.mjs # ce que l'interface propose pour chaque jeu (capacités)
node tests/objectives.test.mjs   # objectifs des modes et progression des joueurs
node tests/assets-check.mjs      # aucune ressource tierce dans le dépôt (--package <dossier> : un paquet)
.\tests\security\security-tests.ps1      # tests de sécurité et fuzzers (contrôle Host, JSON, sauvegardes DS, modes, galerie)
.\tests\security\start-test-server.ps1   # instance de test locale (127.0.0.1:47399), seule cible permise des scans
.\tests\load\load.ps1                    # banc de charge du serveur (500 joueurs, 2 cœurs, serveur jetable sur 127.0.0.1)
```

La CI (`checks.yml`, à chaque push et pull request) lance les ressources tierces, `golden.ps1`, les tests
`node` de l'interface et `security-tests.ps1` ; `release.yml` les relance avant de publier. `android.yml`
(pull requests et `main`, seulement si `android/`, `src/` hors serveur, `web/`, les cœurs ou les scripts de
construction changent) construit l'APK de test, cœurs et moteur de randomisation en cache.

Toute modification de `src/slt/server/Room.java` (ou de ce qu'il appelle) passe par `tests\golden.ps1`.
Une référence réécrite avec `-Update` se relit dans le diff et se justifie dans le message de commit.

Publier une version : numéro dans `Version.java`, section `## x.y.z` dans `CHANGELOG.md`, commit,
`git tag vx.y.z`, `git push --follow-tags` (`.github/workflows/release.yml` publie la release).

## Garde-fous juridiques

Ils réduisent le risque, ils ne rendent pas le projet « légal » par décret.

1. **Aucun contenu Nintendo / Game Freak / Creatures** dans le dépôt ni dans les releases : pas de ROM,
   BIOS, firmware, clé, sprite, musique, texte de jeu, capture d'écran de jeu. Ce qui est affiché est
   **lu à l'exécution dans la ROM du joueur** (comme `Gen5Sprites`), jamais extrait et versionné.
2. **Les ROMs ne transitent jamais par le réseau.** Aucune fonction ne doit permettre d'envoyer,
   partager ou héberger une ROM, un BIOS ou une ressource extraite d'un jeu, même « de salle ».
3. **La RAM avant la ROM** : appliquer une règle par écriture de variables en RAM (comme `ShinyBoost`),
   pas par patch du code du jeu (CJUE, *Sony c. Datel*, C-159/23, 17 octobre 2024). Les patches de
   ROM (`SltPatches`) ont un statut ⚠️ ; leur inventaire, les alternatives en RAM étudiées et la raison
   de chaque patch restant sont dans `docs/rom-patches.md` (à tenir à jour pour tout nouveau patch).
4. **Aucun contournement de mesure technique** : ne jamais intégrer ni télécharger de BIOS, firmware
   ou clé propriétaire (les cœurs tournent en HLE / FreeBIOS : à conserver).
5. **Modes communautaires déclaratifs uniquement** : ni code exécutable, ni binaire, ni ressource
   graphique ou sonore ; seulement des règles, paramètres et textes de l'auteur. Pas de langage de
   script pour les modes.
6. **Marque « Pokémon »** : usage descriptif seulement (« compatible avec les jeux Pokémon »), avec la
   mention de non-affiliation ; pas de logo, de typographie officielle ni de nom imitant une marque.
7. **Licences tierces** : voir Conventions (`THIRD-PARTY.txt`, compatibilité GPLv3).

Si une modification change un statut juridique (✅ → ⚠️ ou ❌), le signaler **avant** de l'implémenter.

Hooks du projet (`.claude/settings.json`, script `.claude/hooks/guard.mjs`) :
- **bloquent** l'écriture dans le dépôt, le `git add` et le commit de `.gb`, `.gbc`, `.gba`, `.nds`,
  `.sav`, `.dsv`, `.sgm`, `.srm`, `bios*.bin`, `firmware*.bin`, `gba_bios*` ;
- **avertissent** après une modification de `SltPatches.java` ou `Rando.java` (ROM, préférer la RAM),
  de `RoomRoms.java`, `ServerLink.java` ou `ServerApi.java` (aucun envoi réseau de ROM), et à l'ajout
  d'une image, d'un son ou d'une vidéo dans `web/`, `site/` ou `docs/` (pas de ressource d'un jeu).
Ils ne voient que les outils de Claude Code : un commit fait ailleurs (GitHub Desktop…) n'est pas
contrôlé par eux. La CI le fait (point 6) : `tests/assets-check.mjs` (workflow `checks.yml` à chaque push
et pull request, et deux étapes de `release.yml`, dépôt puis paquet) refuse ROM, sauvegarde, BIOS,
firmware, patch de ROM, état d'émulateur, tout fichier dont l'en-tête est celui d'une cartouche (quel que
soit son nom), et toute image, son, police ou archive absente de `tests/assets-allowlist.txt` (chemin +
SHA-256 : un fichier ajouté ou modifié se regarde avant d'y être inscrit, `--list` donne les lignes).

## Règles de travail

- **Ne jamais toucher à un comportement marqué ✓ vérifié** (README, « État d'avancement », et
  CHANGELOG) **sans test de non-régression** qui le couvre.
- Un point à la fois : lire les fichiers concernés, proposer un plan court, **attendre l'accord**,
  implémenter, lancer les tests, relire, mettre à jour `CHANGELOG.md` (format existant) et le README,
  puis lister ce qui doit être vérifié en jeu (jeu, version française, étapes).
- Si une tâche s'avère nettement plus lourde que prévu, s'arrêter et le dire avant de continuer.
- Commits, tags, releases et déploiements du serveur : **seulement après un accord explicite**.
- `deploy-vps.ps1` et `sauvegarde-vps.ps1` sont des scripts locaux : ne jamais les versionner.
- Ne jamais modifier les données réelles de l'utilisateur (`dist\CableClub\data`, `data\`) : travailler
  sur des copies dans un dossier temporaire. Tests de jeu avec le **son coupé**.
- Sécurité : tests d'intrusion ou scans **uniquement contre une instance de test locale** (jamais
  play.cableclub.app, le serveur d'un ami, l'hébergeur ou GitHub) ; aucun secret ni clé dans le dépôt
  (la clé de signature Android reste dans `%USERPROFILE%\.cableclub\`) ; ne pas publier le détail
  d'une faille non corrigée.
- Ne pas installer de plugin, de skill ou de serveur MCP sans le demander.
