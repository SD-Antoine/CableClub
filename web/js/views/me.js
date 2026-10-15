// « Moi » on the phone: what the PC keeps in its side rail (the profile, the settings, the new version), as one
// page reached from the bottom bar.

import { h, mount, avatar, icon, btn } from '../ui.js';
import { store, on, me } from '../store.js';
import { SETTINGS_MENU } from './settings.js';
import { openUpdate } from './update.js';

export function renderMe(container) {
  const page = h('div', { class: 'page narrow' });
  container.appendChild(page);

  function draw() {
    const u = me() || {};
    const link = store.state?.link || {};
    const upd = store.state?.update;
    const entries = SETTINGS_MENU.flatMap((m) => m.children || [m]);
    mount(page,
      h('a', { class: 'card click me-card', href: '#/profil' },
        avatar(u.name, 1, { size: 'l', id: u.avatar ? u.id : null, pic: u.avatar || 0 }),
        h('div', { class: 'grow' }, h('b', null, u.name || '—'),
          h('div', { class: 'small muted' }, h('span', { class: ['status-dot', link.connected && 'on'] }), ' ',
            link.connected ? 'Connecté' : 'Hors ligne', ` · @${u.username || '?'}`)),
        h('span', { class: 'small muted' }, 'Mon profil'), icon('back', 'flip')),
      upd?.state === 'available' ? h('div', { class: 'banner mt' }, icon('download'),
        h('div', { class: 'grow' }, h('b', null, `Cable Club ${upd.version} est disponible.`)),
        btn('Voir', () => openUpdate(), { kind: 'small primary' })) : null,
      h('div', { class: 'section-title' }, h('h2', null, 'Réglages')),
      h('div', { class: 'card list-card' }, entries.map((e) => h('a', { class: 'list-link', href: `#/reglages/${e.id}` },
        h('div', { class: 'grow' }, h('b', null, e.label), h('small', null, e.about)), icon('back', 'flip')))),
      h('p', { class: 'tiny faint center mt-l' }, `Cable Club ${store.state?.version || ''} · Projet de fans non officiel · Pokémon © Nintendo, Creatures, GAME FREAK`));
  }

  draw();
  const off = on('state', draw);
  return () => off();
}
