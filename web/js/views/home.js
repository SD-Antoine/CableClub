// Home: my rooms, invitations, create a room, join with a code.

import { h, mount, icon, btn, avatar, chip, modal, act, timeAgo, errorToast } from '../ui.js';
import { srv } from '../api.js';
import { store, on, refreshRooms, loadRoom } from '../store.js';
import { gameName } from '../data/games.js';
import { openCreateRoom } from './create-room.js';

export function stateChip(room) {
  if (room.state === 'lobby') return chip('Salon', '', true);
  if (room.state === 'running') return room.adventureOpen ? chip('Aventure ouverte', 'ok', true) : chip('Aventure fermée', 'warn', true);
  if (room.state === 'finished') {
    const o = room.ended?.outcome;
    return o === 'victory' ? chip('Terminée · victoire', 'ok') : chip('Terminée · défaite', 'danger');
  }
  return chip('Abandonnée');
}

/** My rooms of a game mode (lobby or running) and my invitations; inside the tab of the mode. */
export function renderHome(container, { mode = 'soullink' } = {}) {
  const page = h('div');
  container.appendChild(page);

  function draw() {
    const rooms = [...store.rooms.values()].filter((r) => (r.mode || 'soullink') === mode && (r.state === 'lobby' || r.state === 'running'));
    const invites = rooms.filter((r) => r.invited);
    const mine = rooms.filter((r) => !r.invited);
    mount(page,
      h('div', { class: 'row between mb' },
        h('p', { class: 'muted' }, 'Créez une salle de 1 à 4 joueurs, rejoignez celle d’un ami ou une salle publique.'),
        h('div', { class: 'row' },
          btn('Rejoindre avec un code', joinWithCode, { iconName: 'door' }),
          btn('Nouvelle salle', () => openCreateRoom(), { kind: 'primary', iconName: 'plus' }))),
      invites.length ? h('div', null,
        h('div', { class: 'section-title' }, h('h2', null, 'Invitations')),
        h('div', { class: 'stack' }, invites.map(inviteCard))) : null,
      invites.length ? h('div', { class: 'section-title' }, h('h2', null, 'Mes salles')) : null,
      mine.length ? h('div', { class: 'stack' }, mine.map(roomCard))
        : h('div', { class: 'empty' }, h('b', null, 'Aucune salle pour l’instant'),
          'Créez-en une pour lancer un Soul Link, puis invitez vos amis… ou rejoignez une salle publique.'));
  }

  function roomCard(room) {
    const members = room.members || [];
    return h('a', { class: 'card click room-card', href: `#/salle/${room.id}`, style: { color: 'inherit', textDecoration: 'none' } },
      h('div', { class: 'grow' },
        h('div', { class: 'row' }, h('h3', null, room.name), stateChip(room), room.visibility === 'public' ? chip('Publique', 'accent') : null),
        h('p', { class: 'muted small mt-s' }, `${gameName(room.game?.gameId)} · ${members.length}/${room.maxPlayers} joueur(s) · créée ${timeAgo(room.createdAt)}`)),
      h('div', { class: 'avatars' }, members.map((m) => avatar(m.name, m.slot, { size: 's', online: m.online, id: m.userId, pic: m.avatar }))),
      icon('back', 'flip'));
  }

  function inviteCard(room) {
    const join = btn('Rejoindre', (e) => act(e.currentTarget, async () => {
      await srv.post(`/rooms/${room.id}/join`);
      await loadRoom(room.id);
      location.hash = `#/salle/${room.id}`;
    }), { kind: 'primary small' });
    const decline = btn('Refuser', (e) => act(e.currentTarget, async () => {
      await srv.post(`/rooms/${room.id}/decline`);
      store.rooms.delete(room.id);
      draw();
    }), { kind: 'small ghost' });
    return h('div', { class: 'card room-card' },
      h('div', { class: 'grow' },
        h('h3', null, room.name),
        h('p', { class: 'muted small mt-s' }, `${room.leaderName || 'Un ami'} vous invite · ${gameName(room.game?.gameId)} · ${(room.members || []).length}/${room.maxPlayers} joueur(s)`)),
      decline, join);
  }

  function joinWithCode() {
    const input = h('input', { class: 'input code', maxlength: 6, placeholder: 'ABC123' });
    modal({
      title: 'Rejoindre avec un code',
      body: h('div', { class: 'stack' }, h('p', { class: 'muted' }, 'Le chef de salle trouve le code de 6 caractères en haut de sa salle.'), input),
      actions: [{ label: 'Rejoindre', kind: 'primary', onClick: async (close) => {
        try {
          const room = await srv.post('/rooms/join', { code: input.value.trim().toUpperCase() });
          store.rooms.set(room.id, room);
          close();
          location.hash = `#/salle/${room.id}`;
        } catch (e) { errorToast(e); }
      } }],
    });
  }

  draw();
  refreshRooms().catch(() => {});
  const offs = [on('rooms', draw), on('room', draw), on('presence', draw)];
  return () => offs.forEach((f) => f());
}
