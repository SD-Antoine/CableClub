// Public rooms of a game mode: every room still in its lobby that its leader
// made public; search by name, filter by size, join in one click.

import { h, mount, btn, avatar, chip, act, timeAgo, seg, switchEl } from '../ui.js';
import { srv } from '../api.js';
import { store, loadRoom } from '../store.js';
import { gameName } from '../data/games.js';
import { randoLines } from './parts.js';

export function renderPublic(container, { mode = 'soullink' } = {}) {
  const page = h('div');
  container.appendChild(page);
  const st = { q: '', size: 0, full: false, rooms: null, error: null, busy: false };
  let timer = 0;
  let refresh = 0;
  let alive = true;

  const search = h('input', { class: 'input', type: 'search', placeholder: 'Rechercher une salle (nom, jeu ou chef de salle)', autocomplete: 'off' });
  search.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(() => { st.q = search.value.trim(); load(); }, 250);
  });
  const filters = h('div');
  const results = h('div');
  mount(page,
    h('div', { class: 'public-tools card tight' },
      h('div', { class: 'grow' }, search), filters),
    results);

  function drawFilters() {
    mount(filters, h('div', { class: 'row wrap' },
      h('span', { class: 'small muted' }, 'Taille'),
      seg([[0, 'Toutes'], [1, '1'], [2, '2'], [3, '3'], [4, '4']], st.size, (v) => { st.size = v; drawFilters(); load(); }),
      h('label', { class: 'row small muted', style: { gap: '8px' } }, switchEl(st.full, (v) => { st.full = v; load(); }), 'Salles complètes')));
  }

  async function load() {
    st.busy = true;
    try {
      const r = await srv.get(`/rooms/public?mode=${encodeURIComponent(mode)}&q=${encodeURIComponent(st.q)}&size=${st.size}${st.full ? '&full=1' : ''}`);
      st.rooms = r.rooms;
      st.error = null;
    } catch (e) {
      st.error = e.message;
    }
    st.busy = false;
    if (alive) draw();
  }

  function card(room) {
    const members = room.members || [];
    const full = members.length >= room.maxPlayers;
    const lines = randoLines(room.rando || {}, Number(room.game?.gen || 3));
    const join = room.member
      ? btn('Ouvrir', () => { location.hash = `#/salle/${room.id}`; }, { kind: 'small' })
      : btn(full ? 'Complète' : 'Rejoindre', (e) => act(e.currentTarget, async () => {
        const joined = await srv.post(`/rooms/${room.id}/join`);
        store.rooms.set(joined.id, joined);
        await loadRoom(joined.id).catch(() => {});
        location.hash = `#/salle/${joined.id}`;
      }), { kind: 'small primary', disabled: full });
    return h('div', { class: 'card room-card public-room' },
      h('div', { class: 'grow' },
        h('div', { class: 'row' }, h('h3', { class: 'ellipsis' }, room.name), chip(`${members.length}/${room.maxPlayers}`, full ? 'warn' : 'ok', true)),
        h('p', { class: 'muted small mt-s' }, `${gameName(room.game?.gameId)} · chef : ${room.leaderName || '?'} · créée ${timeAgo(room.createdAt)}`),
        lines.length ? h('p', { class: 'tiny faint mt-s ellipsis', title: lines.join(' · ') }, lines.slice(0, 3).join(' · ')) : null),
      h('div', { class: 'avatars' }, members.map((m) => avatar(m.name, m.slot, { size: 's', online: m.online, id: m.userId, pic: m.avatar }))),
      join);
  }

  function draw() {
    if (st.error) { mount(results, h('div', { class: 'empty' }, st.error)); return; }
    if (!st.rooms) { mount(results, h('div', { class: 'row muted' }, h('span', { class: 'spinner' }), 'Chargement…')); return; }
    mount(results, st.rooms.length
      ? h('div', { class: 'stack' }, h('div', { class: 'tiny faint' }, `${st.rooms.length} salle(s)`), st.rooms.map(card))
      : h('div', { class: 'empty' }, h('b', null, st.q || st.size ? 'Aucune salle ne correspond' : 'Aucune salle publique pour l’instant'),
        'Créez une salle et cochez « Salle publique » pour que d’autres joueurs la trouvent ici.'));
  }

  drawFilters();
  draw();
  load();
  // the list follows the rooms being created and filled while it is shown
  refresh = setInterval(() => { if (!document.hidden && !st.busy) load(); }, 15000);
  return () => { alive = false; clearInterval(refresh); clearTimeout(timer); };
}
