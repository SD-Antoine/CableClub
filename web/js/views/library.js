// My ROMs: the player's own game files, never modified.

import { h, mount, btn, act, toast, modal, icon, confirmDialog } from '../ui.js';
import { local, pollJob } from '../api.js';
import { gameName, LANG, GEN_LABEL } from '../data/games.js';

export function renderLibrary(container) {
  const page = h('div', { class: 'page' });
  container.appendChild(page);
  let roms = [];
  let busy = null;

  async function load() {
    roms = (await local.get('/api/library')).roms;
    draw();
  }

  async function addFolder(button) {
    const dir = await pickPath({ folder: true });
    if (!dir) return;
    await act(button, async () => {
      busy = 'Recherche des ROMs…';
      draw();
      try {
        const j = await local.post('/api/library/scan', { path: dir });
        const r = await pollJob(j.job, (s) => { busy = s.message; draw(); });
        toast(`${r.added} ROM(s) ajoutée(s) sur ${r.found} trouvée(s).${r.skipped.length ? ` ${r.skipped.length} fichier(s) ignoré(s).` : ''}`, r.added ? 'ok' : 'warn');
      } finally {
        busy = null;
        await load();
      }
    });
  }

  async function addFile(button) {
    const f = await pickPath({ folder: false });
    if (!f) return;
    await act(button, async () => {
      const r = await local.post('/api/library/add', { path: f });
      toast(`${gameName(r.gameId)} ajouté.`, 'ok');
      await load();
    });
  }

  function draw() {
    const byGen = {};
    for (const r of roms) (byGen[r.gen] ||= []).push(r);
    mount(page,
      h('div', { class: 'page-head' },
        h('div', null, h('h1', null, 'Mes ROMs'), h('p', null, 'Les copies de vos jeux. SLT ne les modifie jamais : chaque salle génère sa propre ROM à partir d’elles.')),
        h('div', { class: 'row' }, btn('Ajouter un fichier', (e) => addFile(e.currentTarget), { iconName: 'file' }),
          btn('Ajouter un dossier', (e) => addFolder(e.currentTarget), { kind: 'primary', iconName: 'folder' }))),
      busy ? h('div', { class: 'card mb' }, h('div', { class: 'row' }, h('span', { class: 'spinner' }), h('span', null, busy))) : null,
      roms.length ? Object.keys(byGen).sort().map((g) => h('div', null,
        h('div', { class: 'section-title' }, h('h2', null, GEN_LABEL[g])),
        h('div', { class: 'card', style: { padding: '4px 16px' } }, h('div', { class: 'list' }, byGen[g].map((r) => h('div', { class: 'list-item' },
          icon('rom'),
          h('div', { class: 'grow' }, h('b', null, gameName(r.gameId)), h('div', { class: 'tiny muted ellipsis' }, `${LANG[r.lang] || r.lang} · ${r.file}`)),
          r.present ? null : h('span', { class: 'chip warn' }, 'Fichier introuvable'),
          h('span', { class: 'tiny faint mono' }, r.crc),
          btn('', async (e) => {
            const b = e.currentTarget;
            if (await confirmDialog('Retirer cette ROM', 'Le fichier n’est pas supprimé, il quitte seulement la bibliothèque de SLT.', { okLabel: 'Retirer' })) {
              act(b, async () => { await local.post(`/api/library/${r.id}/remove`); await load(); });
            }
          }, { kind: 'ghost small icon-only', iconName: 'trash', title: 'Retirer' })))))))
        : h('div', { class: 'empty' }, h('b', null, 'Bibliothèque vide'), 'Ajoutez le dossier qui contient les ROMs de vos jeux (versions officielles, génération 1 à 5).'));
  }

  load().catch((e) => mount(page, h('div', { class: 'empty' }, e.message)));
  return () => {};
}

/** File / folder chooser drawn inside SLT (a native dialog could open behind the window). */
export function pickPath({ folder }) {
  return new Promise((resolve) => {
    let current = null;
    let result = null;
    const list = h('div', { class: 'fs-list' });
    const pathLine = h('div', { class: 'tiny muted mono ellipsis' });
    const places = h('div', { class: 'row wrap' });
    const m = modal({
      title: folder ? 'Choisir un dossier' : 'Choisir une ROM',
      wide: true,
      body: h('div', { class: 'stack' }, places, pathLine, list),
      actions: folder ? [{ label: 'Annuler', onClick: (c) => c() }, { label: 'Choisir ce dossier', kind: 'primary', onClick: (c) => { result = current; c(); } }]
        : [{ label: 'Annuler', onClick: (c) => c() }],
      onClose: () => resolve(result),
    });
    async function open(path) {
      try {
        const r = await local.get(`/api/fs/list?roms=1&path=${encodeURIComponent(path)}`);
        current = r.path;
        mount(pathLine, r.path);
        mount(list,
          r.parent ? h('div', { class: 'fs-entry', onclick: () => open(r.parent) }, icon('back'), '..') : null,
          r.entries.map((e) => h('div', { class: 'fs-entry', onclick: () => {
            if (e.dir) open(e.path);
            else if (!folder) { result = e.path; m.close(); }
          } }, icon(e.dir ? 'folder' : 'rom'), h('span', { class: 'grow ellipsis' }, e.name))));
      } catch (e) { toast(e.message, 'danger'); }
    }
    local.get('/api/fs/places').then((r) => {
      mount(places, r.places.map((p) => btn(p.label, () => open(p.path), { kind: 'small' })));
      if (r.places.length) open(r.places.find((p) => p.kind === 'folder')?.path || r.places[0].path);
    });
  });
}
