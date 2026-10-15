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

## Publication automatique (GitHub Actions → o2switch)

Le workflow [`site.yml`](../.github/workflows/site.yml) construit le site puis l'envoie par FTPS sur l'hébergement,
à chaque changement de `site/` ou du CHANGELOG sur `main`, après chaque version publiée, et à la demande
(onglet Actions › Site › Run workflow).

### Mise en place (une seule fois)

1. **Ajouter le domaine dans cPanel** (o2switch) : *Domaines* › *Créer un domaine* (domaine additionnel) `cableclub.app`.
   Notez le **dossier racine** proposé (par exemple `cableclub.app`).
2. **Faire pointer le domaine vers o2switch** dans la zone DNS chez OVH (Web Cloud › Noms de domaine › cableclub.app › Zone DNS) :
   - enregistrement **A** de `cableclub.app` (sans sous-domaine) vers l'adresse IP du serveur o2switch (indiquée dans cPanel,
     colonne de droite : « Adresse IP partagée ») ;
   - enregistrement **CNAME** de `www` vers `cableclub.app.` ;
   - **ne touchez pas** aux enregistrements `play` et `srv1` (le serveur de jeu), ni à ceux de la messagerie (MX, SPF, DKIM, SRV).
3. **Certificat HTTPS** : cPanel (AutoSSL) l'installe tout seul, quelques minutes à quelques heures après le changement DNS.
4. **Compte FTP dédié** : cPanel › *Comptes FTP* › créer par exemple `deploy@cableclub.app`, avec comme **répertoire** le dossier
   racine du domaine (étape 1) et un mot de passe fort. Relevez dans « Configurer le client FTP » le **nom d'hôte du serveur**
   (de la forme `xxxx.o2switch.net`) : c'est lui, et pas `cableclub.app`, qui présente un certificat valide pour le FTPS.
5. **Secrets GitHub** (dépôt › Settings › Secrets and variables › Actions › *New repository secret*) :
   `FTP_SERVER` (le nom d'hôte de l'étape 4), `FTP_USERNAME`, `FTP_PASSWORD`.
   Variables facultatives (onglet *Variables*) : `FTP_DIR` si le dossier du compte FTP n'est pas la racine du site (défaut `./`),
   `FTP_SECURITY=loose` si le certificat du serveur FTP est refusé.
6. Lancer le workflow (*Actions › Site › Run workflow*) et ouvrir https://cableclub.app.

### Plus tard

- **SignPath accepté** : créer la variable `SITE_SIGNED` = `1`. La page de téléchargement affiche alors la « Code signing policy »
  exigée par SignPath (« Free code signing provided by SignPath.io… »).
- **Contact** : l'adresse `contact@cableclub.app` est celle de la boîte Zimbra.
- **Mentions légales** : `src/mentions-legales.html` (éditeur, hébergeurs) ; à vérifier si l'hébergeur ou l'adresse change.

Le workflow n'utilise pas l'outil « Git Version Control » de cPanel : le site demande une étape de construction (versions
publiées, notes de version), qu'il vaut mieux faire dans GitHub Actions.
