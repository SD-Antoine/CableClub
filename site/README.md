# Site de Cable Club (cableclub.app)

Site statique (HTML, CSS, un peu de JavaScript, aucun cookie ni service externe).

- `src/` : les pages (`index`, `telecharger`, `guide`, `nouveautes`, `mentions-legales`, `404`), le gabarit commun
  (`layout.html`), les styles et les images (`assets/`), `.htaccess` et `robots.txt`.
- `build.mjs` : assemble le site dans `dist/` (Node 20 ou plus, aucune dépendance). Il lit **la dernière version
  publiée sur GitHub** (liens de téléchargement, tailles, empreintes SHA-256) et **CHANGELOG.md** (page « Nouveautés »).
  Les versions marquées « à venir » dans le CHANGELOG ne sont pas publiées.

```bash
node site/build.mjs            # construit site/dist (interroge GitHub)
node site/build.mjs --offline  # sans réseau (liens vers la page des versions)
cd site/dist && python -m http.server 8080   # voir le résultat sur http://localhost:8080
```

## Mise en ligne (GitHub → cPanel o2switch)

GitHub Actions ([`site.yml`](../.github/workflows/site.yml)) construit le site et le range dans la branche **`site`** du dépôt,
à chaque changement de `site/` ou du CHANGELOG sur `main` et après chaque version publiée. cPanel clone cette branche
(Git Version Control) et son `.cpanel.yml` copie les fichiers dans le dossier du domaine (`~/cableclub.app` ; changer
`SITE_DIR` dans `src/.cpanel.yml` si c'est un autre dossier).

### Mise en place (une seule fois)

1. Domaine `cableclub.app` ajouté dans cPanel, A de `cableclub.app` et de `www` vers l'IP partagée du cPanel (fait).
2. **Clé de déploiement** : cPanel › *Accès SSH* › *Gérer les clés SSH* › générer une clé **sans phrase secrète**, l'autoriser ;
   copier la clé publique dans GitHub › dépôt › *Settings* › *Deploy keys* › *Add deploy key* (lecture seule).
   (Le dépôt étant public, l'adresse HTTPS `https://github.com/SD-Antoine/CableClub.git` marche aussi sans clé.)
3. cPanel › *Git Version Control* › *Créer* : cloner `git@github.com:SD-Antoine/CableClub.git`, chemin par exemple
   `repositories/cableclub-site` (hors du dossier public). Puis *Gérer* › *Informations de base* › branche extraite : **`site`**.
4. *Gérer* › *Extraire ou déployer* : **Mettre à jour à partir du distant**, puis **Déployer le commit HEAD**.
5. Ouvrir https://cableclub.app (le certificat HTTPS est posé par AutoSSL quelques minutes après le changement DNS).

À chaque mise à jour : les deux boutons de l'étape 4. Pour que ce soit automatique, une tâche cron cPanel (toutes les 15 min) :

```
cd ~/repositories/cableclub-site && git pull -q && uapi VersionControlDeployment create repository_root=$HOME/repositories/cableclub-site >/dev/null
```

### Plus tard

- **SignPath accepté** : variable GitHub `SITE_SIGNED` = `1` (Settings › Secrets and variables › Actions › Variables). La page de
  téléchargement affiche alors la « Code signing policy » exigée par SignPath.
- **Contact** : `contact@cableclub.app` est la boîte Zimbra.
- **Mentions légales** : `src/mentions-legales.html` (éditeur, hébergeurs).
