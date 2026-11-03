## Ce que change cette contribution

<!-- En quelques lignes : quoi, pourquoi, et comment le vérifier (jeu, version, étapes). -->

## Vérifications

- [ ] **Aucune ressource tierce** : ni ROM, BIOS, firmware, sauvegarde, patch de ROM, ni sprite, image,
      son, musique, texte, carte ou capture d'écran tirés d'un jeu (Nintendo, Creatures, GAME FREAK ou
      autre ayant droit), ni lien pour en obtenir.
- [ ] Les images, sons et polices ajoutés sont propres au projet (ou sous licence compatible, notée dans
      `THIRD-PARTY.txt`) et inscrits dans `tests/assets-allowlist.txt` après vérification.
- [ ] Aucune fonction n'envoie une ROM ou une ressource d'un jeu par le réseau ; les règles s'appliquent
      par écriture en RAM.
- [ ] Toute nouvelle dépendance est compatible GPLv3 et ajoutée à `THIRD-PARTY.txt`.
- [ ] Module pour un autre jeu : catégorie `homebrew` ou `licensed` seulement, licence du jeu à vérifier
      par un mainteneur (`docs/fan-games.md`).
- [ ] Tests lancés : `node tests/assets-check.mjs`, `.\tests\golden.ps1`,
      `.\tests\security\security-tests.ps1` (et les tests node concernés).

Voir [CONTRIBUTING.md](../CONTRIBUTING.md).
