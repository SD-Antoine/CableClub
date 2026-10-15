// Builds the static site of Cable Club into site/dist (Node 20+, no dependency).
//   node site/build.mjs            build (asks GitHub for the latest release; falls back to the releases page)
//   node site/build.mjs --offline  build without any network call
//   SITE_SIGNED=1 node site/build.mjs   the page of the downloads states that CableClub.exe is signed by SignPath
// The notes of the versions are the ones of CHANGELOG.md; the download links and hashes come from the latest release.
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const src = path.join(here, 'src');
const out = path.join(here, 'dist');
const offline = process.argv.includes('--offline');
const REPO = 'SD-Antoine/CableClub';
const RELEASES = `https://github.com/${REPO}/releases/latest`;

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

// ---------------------------------------------------------------- the latest release
const localVersion = /VERSION = "([^"]+)"/.exec(fs.readFileSync(path.join(root, 'src/slt/Version.java'), 'utf8'))?.[1] || '';
const info = {
  version: localVersion, date: '', winUrl: RELEASES, winSize: '', winSha: '(voir la page de la version)',
  apkUrl: RELEASES, apkSize: '', apkSha: '(voir la page de la version)',
};
const mo = (n) => `${(n / 1048576).toFixed(n < 10485760 ? 1 : 0).replace('.', ',')} Mo`;

async function getJson(url) {
  const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'cableclub-site-build' };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const r = await fetch(url, { headers });
  if (!r.ok) throw new Error(`${url}: ${r.status}`);
  return r.json();
}
async function getText(url) {
  const r = await fetch(url, { headers: { 'User-Agent': 'cableclub-site-build' }, redirect: 'follow' });
  if (!r.ok) throw new Error(`${url}: ${r.status}`);
  return r.text();
}

if (!offline) {
  try {
    const rel = await getJson(`https://api.github.com/repos/${REPO}/releases/latest`);
    info.version = String(rel.tag_name || '').replace(/^v/i, '') || info.version;
    info.date = String(rel.published_at || '').slice(0, 10);
    for (const a of rel.assets || []) {
      const win = /^CableClub-.*-windows-x64\.zip$/.test(a.name);
      const apk = /^CableClub-.*-android\.apk$/.test(a.name);
      if (!win && !apk) continue;
      const sha = (rel.assets || []).find((x) => x.name === `${a.name}.sha256`);
      let hash = '';
      if (sha) { try { hash = (await getText(sha.browser_download_url)).trim().split(/\s+/)[0]; } catch { /* shown without */ } }
      if (win) { info.winUrl = a.browser_download_url; info.winSize = mo(a.size); if (hash) info.winSha = hash; }
      if (apk) { info.apkUrl = a.browser_download_url; info.apkSize = mo(a.size); if (hash) info.apkSha = hash; }
    }
    console.log(`release ${info.version} (${info.date})`);
  } catch (e) {
    console.warn(`GitHub injoignable (${e.message}) : liens vers la page des versions`);
  }
}

// ---------------------------------------------------------------- the patch notes
function inline(s) {
  return esc(s).replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>').replace(/`([^`]+)`/g, '<code>$1</code>');
}
function changelogHtml() {
  const lines = fs.readFileSync(path.join(root, 'CHANGELOG.md'), 'utf8').split(/\r?\n/);
  const parts = [];
  let open = false;
  let skip = false;
  const close = () => { if (open) parts.push('</ul></article>'); open = false; };
  for (const line of lines) {
    const h = /^## (\S+)\s*(?:—|-)\s*(.*)$/.exec(line);
    if (h) {
      close();
      // a version still being prepared ("à venir") is not published
      skip = /à venir/i.test(h[2]);
      if (skip) continue;
      const date = /^\d{4}-\d{2}-\d{2}$/.test(h[2]) ? h[2].split('-').reverse().join('/') : h[2];
      parts.push(`<article class="release"><h2>Version ${esc(h[1])} <small>${esc(date)}</small></h2><ul>`);
      open = true;
      continue;
    }
    const li = /^- (.*)$/.exec(line);
    if (li && open && !skip) parts.push(`<li>${inline(li[1])}</li>`);
  }
  close();
  return parts.join('\n');
}

// ---------------------------------------------------------------- code signing text (SignPath asks for it)
const SIGNED = process.env.SITE_SIGNED === '1';
const signBlock = SIGNED ? `
<p><b>Free code signing provided by <a href="https://about.signpath.io/">SignPath.io</a>, certificate by <a href="https://signpath.org/">SignPath Foundation</a>.</b></p>
<p><code>CableClub.exe</code> (version Windows) est signé à chaque version publiée, à partir du code public de ce projet, compilé par GitHub Actions. Rien d’autre n’est signé avec ce certificat, et chaque signature est approuvée à la main.</p>
<ul>
  <li><b>Auteurs et relecteurs</b> : les membres du <a href="https://github.com/${REPO}">dépôt GitHub</a>, aujourd’hui <a href="https://github.com/SD-Antoine">SD-Antoine</a>. Toute contribution extérieure passe par une demande de fusion (pull request) relue.</li>
  <li><b>Approbateur</b> : <a href="https://github.com/SD-Antoine">SD-Antoine</a>.</li>
</ul>
<p><b>Confidentialité</b> : Cable Club ne transmet d’informations qu’aux serveurs que vous choisissez (le serveur Cable Club ou celui d’un ami) et à GitHub pour chercher les mises à jour. Voir la <a href="https://play.cableclub.app/confidentialite">politique de confidentialité</a>.</p>` : `
<p>Pour que Windows reconnaisse Cable Club, nous demandons la signature de code gratuite de la <a href="https://signpath.org/">SignPath Foundation</a> pour <code>CableClub.exe</code>. En attendant qu’elle soit active, le programme n’est pas signé : c’est ce qui explique l’avertissement de SmartScreen décrit plus haut.</p>
<p>Le code de Cable Club est public et la version Windows est compilée par GitHub : le fichier publié est celui qui sort du code que vous pouvez lire.</p>`;

// ---------------------------------------------------------------- pages
fs.mkdirSync(out, { recursive: true });
// the folder itself stays (a preview server may be serving it): only its content is renewed
for (const e of fs.readdirSync(out)) fs.rmSync(path.join(out, e), { recursive: true, force: true });
fs.cpSync(path.join(src, 'assets'), path.join(out, 'assets'), { recursive: true });
for (const f of ['.htaccess', 'robots.txt']) fs.copyFileSync(path.join(src, f), path.join(out, f));

const assetVersion = createHash('sha1').update(fs.readFileSync(path.join(src, 'assets/site.css'))).update(fs.readFileSync(path.join(src, 'assets/site.js'))).digest('hex').slice(0, 8);
const layout = fs.readFileSync(path.join(src, 'layout.html'), 'utf8');
const dateText = info.date ? `, publiée le ${info.date.split('-').reverse().join('/')}` : '';
const vars = {
  VERSION: esc(info.version), RELEASE_DATE_TEXT: dateText,
  WIN_URL: esc(info.winUrl), WIN_SIZE_TEXT: info.winSize ? ` · ${info.winSize}` : '', WIN_SHA: esc(info.winSha),
  APK_URL: esc(info.apkUrl), APK_SIZE_TEXT: info.apkSize ? ` · ${info.apkSize}` : '', APK_SHA: esc(info.apkSha),
  SIGN_BLOCK: signBlock, CHANGELOG: changelogHtml(),
};
const fill = (text, v) => text.replace(/\{\{([A-Za-z_]+)\}\}/g, (m, k) => (k in v ? v[k] : m));

const pages = fs.readdirSync(src).filter((f) => f.endsWith('.html') && f !== 'layout.html');
const urls = [];
for (const f of pages) {
  let body = fs.readFileSync(path.join(src, f), 'utf8');
  const m = /^<!--(\{[\s\S]*?\})-->\s*/.exec(body);
  if (!m) throw new Error(`${f}: métadonnées manquantes`);
  const meta = JSON.parse(m[1]);
  body = fill(body.slice(m[0].length), vars);
  const nav = Object.fromEntries(['home', 'guide', 'nouveautes'].map((k) => [`NAV_${k}`, meta.page === k ? ' aria-current="page"' : '']));
  const html = fill(layout, { ...vars, ...nav, TITLE: esc(meta.title), DESCRIPTION: esc(meta.description), PATH: meta.path, ASSET_VERSION: assetVersion, CONTENT: body });
  const left = html.match(/\{\{[A-Za-z_]+\}\}/g);
  if (left) throw new Error(`${f}: variables non remplacées ${[...new Set(left)].join(', ')}`);
  fs.writeFileSync(path.join(out, f), html);
  if (f !== '404.html') urls.push(meta.path);
}
fs.writeFileSync(path.join(out, 'sitemap.xml'), `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.map((u) => `  <url><loc>https://cableclub.app/${u}</loc></url>`).join('\n')}\n</urlset>\n`);
console.log(`site construit dans ${path.relative(root, out)} : ${pages.length} pages, signature ${SIGNED ? 'oui' : 'non'}`);
