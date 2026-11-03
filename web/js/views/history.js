// Every game of a mode the player took part in, with its journal and summary, and the player's statistics in
// that mode (point 3: from the runs the server recorded).

import { h, mount, avatar, dateTime } from '../ui.js';
import { srv } from '../api.js';
import { gameName } from '../data/games.js';
import { stateChip } from './home.js';
import { familyOf } from '../data/modes.js';

/** The player's totals in this mode (finished runs; an abandoned one is counted apart). */
function statsCard(t) {
  if (!t || !(t.runs || t.abandons)) return null;
  const num = (value, label) => h('div', null, h('b', null, value), h('small', null, label));
  return h('div', { class: 'card mb' }, h('h3', null, 'Vos statistiques'),
    h('div', { class: 'stat-nums three' },
      num(t.runs, t.runs > 1 ? 'parties terminées' : 'partie terminée'),
      num(t.wins, t.wins > 1 ? 'victoires' : 'victoire'),
      num(t.survival == null ? '—' : `${t.survival} %`, 'de survie')),
    h('div', { class: 'stat-nums three mt-s' },
      num(t.captures, 'captures'), num(t.deaths, 'morts'), num(t.badgesBest, 'badges au plus')),
    t.abandons ? h('p', { class: 'tiny faint mt-s' }, `Et ${t.abandons} partie${t.abandons > 1 ? 's' : ''} arrêtée${t.abandons > 1 ? 's' : ''} avant la fin (non comptée${t.abandons > 1 ? 's' : ''}).`) : null,
    h('p', { class: 'tiny faint mt-s' }, 'Survie : Pokémon encore en vie à la fin, pour chaque Pokémon capturé.'));
}

/** Every game of a mode the player took part in; inside the tab of the mode. */
export function renderHistory(container, { mode = 'soullink' } = {}) {
  const page = h('div');
  container.appendChild(page);
  mount(page, h('div', { class: 'row muted' }, h('span', { class: 'spinner' }), 'Chargement…'));

  Promise.all([srv.get('/history'), srv.get('/me/stats').catch(() => null)]).then(([r, stats]) => {
    const rooms = r.rooms.filter((x) => familyOf(x) === mode);
    mount(page,
      statsCard(stats?.families?.[mode]),
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
      })) : h('div', { class: 'empty' }, h('b', null, 'Aucune partie pour l’instant'), 'Vos parties apparaîtront ici dès leur lancement.'));
  }).catch((e) => mount(page, h('div', { class: 'empty' }, e.message)));
  return () => {};
}
