// Every Soul Link the player took part in, with its journal and summary.

import { h, mount, avatar, dateTime } from '../ui.js';
import { srv } from '../api.js';
import { gameName } from '../data/games.js';
import { stateChip } from './home.js';

/** Every game of a mode the player took part in; inside the tab of the mode. */
export function renderHistory(container, { mode = 'soullink' } = {}) {
  const page = h('div');
  container.appendChild(page);
  mount(page, h('div', { class: 'row muted' }, h('span', { class: 'spinner' }), 'Chargement…'));

  srv.get('/history').then((r) => {
    const rooms = r.rooms.filter((x) => (x.mode || 'soullink') === mode);
    mount(page,
      h('p', { class: 'muted mb' }, 'Vos parties, en cours et terminées, avec leur journal complet.'),
      rooms.length ? h('div', { class: 'stack' }, rooms.map((room) => {
        const sum = room.summary || [];
        const caught = sum.reduce((a, s) => a + (s.captures || 0), 0);
        const dead = sum.reduce((a, s) => a + (s.deaths || 0), 0);
        return h('a', { class: 'card click room-card', href: `#/historique/${room.id}`, style: { color: 'inherit', textDecoration: 'none' } },
          h('div', { class: 'grow' },
            h('div', { class: 'row' }, h('h3', null, room.name), stateChip(room)),
            h('p', { class: 'muted small mt-s' }, `${gameName(room.game?.gameId)} · ${room.launchedAt ? 'lancée le ' + dateTime(room.launchedAt) : ''}`
              + ` · ${caught} capture(s) · ${dead} mort(s)`)),
          h('div', { class: 'avatars' }, (room.members || []).map((m) => avatar(m.name, m.slot, { size: 's', id: m.userId, pic: m.avatar }))));
      })) : h('div', { class: 'empty' }, h('b', null, 'Aucune partie pour l’instant'), 'Vos Soul Link apparaîtront ici dès leur lancement.'));
  }).catch((e) => mount(page, h('div', { class: 'empty' }, e.message)));
  return () => {};
}
