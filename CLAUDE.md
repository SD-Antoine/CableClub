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
     types) pour ceux qui n'ont pas encore de règle propre.
   - `Mode.java` : le moteur des modes — rencontres (`EncounterLogic`), captures (`CaptureLogic`),
     morts, équipe, Hardcore, démarrage et fin (`RunLogic`) — composé de trois primitives (joueurs
     liés ou non, première rencontre ou chromatiques seuls, équipe libre ou d'un seul Pokémon) ; les
     8 combinaisons tournent sur le même `Engine`. Aucun handler de `Room` ne teste le mode.
   - `ModeSpec.java` : un mode est un fichier déclaratif `.ccmode.json` (format : `docs/modes.md`) —
     catalogue fermé des règles, validateur strict (pas de code, de binaire, d'image ni d'adresse ;
     `honorNoEvolution`, écrite dans la ROM, réservée aux modes officiels), registre des modes
     officiels (`web/js/data/modes/`). `ModeSpecTest` vérifie que les fichiers officiels redonnent
     exactement les règles d'avant ; chaque nouvelle combinaison a son scénario golden.
   - `Rooms.java` : salles, journal, écrans en direct (JPEG relayés), copies cloud des sauvegardes.
   - `ServerApi.java`, `Accounts.java`, `Hub.java` (présence, flux SSE).
   - Définitions des règles côté interface : `web/js/data/rules.js` (clé, type, libellé).
   - Capacités des jeux : `web/js/data/capabilities.json` (par génération, exceptions par jeu, besoins
     des modes et des règles), lu par `Capabilities.java` (refus côté serveur) et par
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
   - `ShinyBoost.java` : taux de chromatiques par écriture en RAM (gén. 2-3).
   - `ScriptRunner.java` : lecteur sans fenêtre piloté par script (tests hors interface).
3. **Interface** — `web/` (HTML/CSS/JS modules, sans framework ni bundler ; seul `web/js/vendor/`
   contient du code tiers) et l'application locale `src/slt/client/` (`LocalApi` sur 127.0.0.1,
   `ServerLink`, `Library`, `RoomRoms`, `Players`, `Updates`).

Règles d'architecture :
- **Un mode ne connaît jamais la mémoire d'un jeu** : il ne manipule que des événements communs et
  des actions abstraites. Les adresses et structures restent dans les adaptateurs.
- **Le serveur reste l'arbitre** et **les directives sont le seul canal** vers les adaptateurs.
- Chaque jeu déclare ce qu'il sait faire ; un mode refuse proprement de démarrer si une règle dont il
  a besoin n'est pas prise en charge.

ROM de salle : `client/RoomRoms.java` la génère **localement** depuis la copie du joueur, via
`client/Rando.java` (Universal Pokemon Randomizer ZX 4.6.1, GPLv3, compilé par `build.ps1`) puis
`client/SltPatches.java` (vendeurs, retrait des évolutions, patch du test chromatique des jeux DS).

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
```

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
   ROM (`SltPatches`) ont un statut ⚠️.
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
contrôlé par eux.

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
