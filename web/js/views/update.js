// Updates of SLT (GitHub releases): what is new, then download, install and
// restart in one click. The new SLT opens its own window; when SLT does not own
// this window, the page waits for the new version and reloads itself.

import { h, mount, modal, btn, icon, act, toast } from '../ui.js';
import { local, pollJob } from '../api.js';
import { store } from '../store.js';

/** Release notes (Markdown from GitHub) as simple paragraphs and lists. */
function notes(text) {
  const out = [];
  let list = null;
  for (const raw of String(text || '').split(/\r?\n/)) {
    const line = raw.trim();
    if (/^-{3,}$/.test(line)) break; // what follows is for the GitHub page (download instructions)
    if (!line) { list = null; continue; }
    const item = line.match(/^[-*]\s+(.*)$/);
    const clean = (s) => s.replace(/\*\*(.+?)\*\*/g, '$1').replace(/`(.+?)`/g, '$1').replace(/^#+\s*/, '');
    if (item) {
      if (!list) { list = h('ul', { class: 'update-list' }); out.push(list); }
      list.appendChild(h('li', null, clean(item[1])));
    } else {
      list = null;
      out.push(h(line.startsWith('#') ? 'b' : 'p', { class: line.startsWith('#') ? 'update-title' : 'small' }, clean(line)));
    }
  }
  return out.length ? h('div', { class: 'update-notes' }, out) : null;
}

/** Waits for the new version to answer, then reloads the interface. */
async function waitForRestart(version, setText) {
  const started = Date.now();
  for (;;) {
    await new Promise((r) => setTimeout(r, 1500));
    try {
      const s = await local.get('/api/state');
      if (s.version === version) { location.reload(); return; }
      if (s.version && Date.now() - started > 60000) {
        setText(`Cable Club a redémarré sans changer de version : voyez le fichier mise-a-jour.log du dossier data.`);
        return;
      }
    } catch { /* SLT is restarting */ }
    if (Date.now() - started > 180000) {
      setText('Cable Club ne répond pas encore : relancez-le si besoin.');
      return;
    }
  }
}

export function openUpdate() {
  const u = store.state?.update;
  if (!u || u.state !== 'available') return;
  const sizeMb = u.size ? ` (${Math.round(u.size / 1048576)} Mo)` : '';
  const status = h('div', { class: 'small muted' });
  const hosting = store.state?.host?.running;
  const body = h('div', { class: 'stack' },
    h('p', null, `Vous avez la version ${u.current}. La nouvelle version ${u.version} est prête${sizeMb}.`),
    notes(u.notes),
    u.installable
      ? h('p', { class: 'tiny faint' }, 'Cable Club télécharge la nouvelle version, la met à la place de l’ancienne et redémarre (environ une minute). ',
        'Vos données (salles, sauvegardes, ROMs, réglages) ne sont pas touchées.',
        hosting ? ' Votre serveur sera coupé quelques secondes : vos amis se reconnecteront tout seuls.' : '')
      : h('p', { class: 'small warn' }, u.reason || 'Cette copie de Cable Club ne peut pas se mettre à jour seule.'),
    status);
  let installBtn;
  const m = modal({
    title: `Cable Club ${u.version} est disponible`,
    body,
    actions: [
      { label: 'Plus tard', onClick: (close) => close() },
      { label: 'Voir sur GitHub', onClick: () => local.post('/api/update/page', {}).catch(() => {}) },
      u.installable ? (installBtn = btn('Mettre à jour et redémarrer', () => act(installBtn, async () => {
        const res = await local.post('/api/update/install', {});
        await pollJob(res.job, (s) => { status.textContent = s.message || ''; });
        mount(body, h('div', { class: 'empty' }, icon('refresh'), h('b', null, `Installation de Cable Club ${u.version}…`),
          h('p', { class: 'small muted' }, 'Cable Club se ferme et se rouvre tout seul sur la nouvelle version.')));
        const text = body.querySelector('p');
        await waitForRestart(u.version, (t) => { text.textContent = t; });
      }), { kind: 'primary', iconName: 'download' })) : null,
    ].filter(Boolean),
  });
  return m;
}

/** "Mise à jour" entry of the side rail when a new version is out. */
export function updateLink() {
  const u = store.state?.update;
  if (!u || u.state !== 'available') return null;
  return h('button', { type: 'button', class: 'nav-item update-item', title: `Cable Club ${u.version} est disponible`, onclick: () => openUpdate() },
    icon('download'), h('span', { class: 'label' }, `Mise à jour ${u.version}`));
}

/** Settings section: installed version, check now, automatic check. */
export function updateSettings(switchEl, save) {
  const u = store.state?.update || {};
  const cfg = store.state?.config || {};
  const when = u.checkedAt ? new Date(u.checkedAt).toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' }) : null;
  const line = {
    available: `Nouvelle version ${u.version} disponible.`,
    uptodate: 'Cable Club est à jour.',
    error: u.error || 'Vérification impossible.',
  }[u.state] || 'Pas encore vérifié.';
  const check = btn('Rechercher une mise à jour', (e) => act(e.currentTarget, async () => {
    const s = await local.post('/api/update/check', {});
    store.state.update = s;
    if (s.state === 'available') openUpdate();
    else toast(s.state === 'uptodate' ? `Cable Club ${s.current} est à jour.` : (s.error || 'Vérification impossible.'), s.state === 'uptodate' ? 'ok' : 'warn');
  }), { iconName: 'refresh' });
  return h('div', { class: 'stack' },
    h('div', { class: 'row' },
      h('div', { class: 'grow' }, h('b', null, `Version installée : ${store.state?.version || '?'}`),
        h('div', { class: 'small muted' }, line, when ? ` (vérifié le ${when})` : '')),
      u.state === 'available' ? btn('Voir la mise à jour', () => openUpdate(), { kind: 'primary', iconName: 'download' }) : check),
    h('div', { class: 'rule-row' },
      h('div', { class: 'grow' }, h('b', null, 'Vérifier automatiquement'),
        h('small', null, 'Au démarrage puis toutes les 6 heures, Cable Club regarde s’il existe une nouvelle version sur GitHub. Rien n’est installé sans votre accord.')),
      switchEl(cfg.updateCheck !== false, (v) => save({ updateCheck: v }))));
}
