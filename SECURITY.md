# Sécurité

Merci de signaler **en privé** toute faille de sécurité de Cable Club : ne l'ouvrez pas
en *issue* publique et ne publiez pas de détail exploitable tant qu'elle n'est pas corrigée.

## Signaler une faille

- De préférence, par le signalement privé de GitHub : onglet **Security** du dépôt
  [SD-Antoine/CableClub](https://github.com/SD-Antoine/CableClub) → **Report a vulnerability**
  (*Privately report a security vulnerability*). La discussion reste privée jusqu'au correctif.

Indiquez, si possible : la version concernée (voir « À propos » dans l'application), le composant
(serveur, application, lecteur de jeu), les étapes pour reproduire, et l'effet observé.

## Délai

Réponse visée sous **7 jours**. Une faille confirmée est corrigée par ordre de gravité, puis publiée
dans une nouvelle version ; le `CHANGELOG.md` mentionne la correction (sans détail exploitable avant
que tout le monde ait pu se mettre à jour). Projet personnel et bénévole : les délais peuvent varier.

## Portée

Entrent dans le périmètre : le serveur (`src/slt/server/`), l'application d'un joueur et son API locale
(`src/slt/client/`), le lecteur de jeu (`src/slt/player/`), et les mises à jour automatiques.

**Hors périmètre** : les tests d'intrusion contre le serveur public `play.cableclub.app` ne sont pas
autorisés (données réelles des joueurs, service partagé). Montez une instance de test locale
(`tests/security/start-test-server.ps1`) et testez uniquement celle-ci.

## Ce que Cable Club ne manipule jamais

Cable Club ne contient, ne télécharge et ne transmet aucune ROM, BIOS, firmware ni sauvegarde d'un
jeu : les ROMs restent sur le PC du joueur et ne transitent pas par le réseau. Un signalement qui
demanderait d'en envoyer une est sans objet.
