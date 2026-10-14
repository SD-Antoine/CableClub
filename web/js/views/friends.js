// Friends: add by identifier, requests, friend list with presence, invite to a room.

import { h, mount, btn, avatar, act, toast, modal } from '../ui.js';
import { srv } from '../api.js';
import { store, on, refreshFriends } from '../store.js';

export function renderFriends(container) {
  const page = h('div', { class: 'page narrow' });
  container.appendChild(page);
  const input = h('input', { class: 'input', placeholder: 'Identifiant de votre ami', autocomplete: 'off' });
  const suggestions = h('div', { class: 'list' });
  let timer = 0;

  input.addEventListener('input', () => {
    clearTimeout(timer);
    const q = input.value.trim();
    if (q.length < 2) { mount(suggestions); return; }
    timer = setTimeout(async () => {
      try {
        const r = await srv.get(`/users/search?q=${encodeURIComponent(q)}`);
        const known = new Set([...store.friends.friends, ...store.friends.outgoing].map((f) => f.id));
        mount(suggestions, r.users.filter((u) => !known.has(u.id)).map((u) => h('div', { class: 'list-item' },
          avatar(u.name, 2, { size: 's', id: u.id, pic: u.avatar }), h('div', { class: 'grow' }, h('b', null, u.name), h('div', { class: 'tiny muted' }, `@${u.username}`)),
          btn('Ajouter', (e) => send(e.currentTarget, u.username), { kind: 'small' }))));
      } catch { mount(suggestions); }
    }, 250);
  });

  async function send(button, username) {
    await act(button, async () => {
      const r = await srv.post('/friends/request', { username });
      toast(r.result === 'friends' ? `${r.name} et vous êtes maintenant amis.` : `Demande envoyée à ${r.name}.`, 'ok');
      input.value = '';
      mount(suggestions);
      await refreshFriends();
    });
  }

  function inviteTo(friend) {
    const rooms = [...store.rooms.values()].filter((r) => r.state === 'lobby' && !r.invited && r.leaderId === store.state?.link?.user?.id);
    modal({
      title: `Inviter ${friend.name}`,
      body: rooms.length ? h('div', { class: 'list' }, rooms.map((r) => h('div', { class: 'list-item' },
        h('div', { class: 'grow' }, h('b', null, r.name), h('div', { class: 'tiny muted' }, `${(r.members || []).length}/${r.maxPlayers} joueur(s)`)),
        btn('Inviter', (e) => act(e.currentTarget, async () => {
          await srv.post(`/rooms/${r.id}/invite`, { userId: friend.id });
          e.currentTarget.textContent = 'Invité';
          e.currentTarget.disabled = true;
        }), { kind: 'small primary' }))))
        : h('p', { class: 'muted' }, 'Vous n’avez pas de salle en préparation. Créez-en une depuis « Salles ».'),
    });
  }

  function draw() {
    const f = store.friends;
    mount(page,
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Amis'), h('p', null, 'Ajoutez vos amis pour les inviter en un clic.'))),
      h('form', { class: 'card', onsubmit: (e) => { e.preventDefault(); if (input.value.trim()) send(e.submitter, input.value.trim()); } },
        h('div', { class: 'row' }, h('div', { class: 'grow' }, input), btn('Envoyer une demande', null, { kind: 'primary', type: 'submit' })),
        suggestions),
      f.incoming.length ? [h('div', { class: 'section-title' }, h('h2', null, 'Demandes reçues')),
        h('div', { class: 'card', style: { padding: '4px 16px' } }, h('div', { class: 'list' }, f.incoming.map((u) => h('div', { class: 'list-item' },
          avatar(u.name, 2, { size: 's', id: u.id, pic: u.avatar }), h('div', { class: 'grow' }, h('b', null, u.name), h('div', { class: 'tiny muted' }, `@${u.username}`)),
          btn('Refuser', (e) => act(e.currentTarget, async () => { await srv.post(`/friends/${u.id}/remove`); await refreshFriends(); }), { kind: 'small ghost' }),
          btn('Accepter', (e) => act(e.currentTarget, async () => { await srv.post(`/friends/${u.id}/accept`); await refreshFriends(); }), { kind: 'small primary' })))))] : null,
      h('div', { class: 'section-title' }, h('h2', null, `Mes amis (${f.friends.length})`)),
      f.friends.length ? h('div', { class: 'card', style: { padding: '4px 16px' } }, h('div', { class: 'list' }, f.friends.map((u) => h('div', { class: 'list-item' },
        avatar(u.name, 2, { size: 's', online: u.online, id: u.id, pic: u.avatar }),
        h('div', { class: 'grow' }, h('b', null, u.name), h('div', { class: 'tiny muted' }, u.online ? 'En ligne' : 'Hors ligne')),
        btn('', () => { location.hash = `#/chat/dm-${u.id}`; }, { kind: 'small ghost icon-only', iconName: 'chat', title: 'Message privé' }),
        btn('Inviter', () => inviteTo(u), { kind: 'small' }),
        btn('', (e) => act(e.currentTarget, async () => { await srv.post(`/friends/${u.id}/remove`); await refreshFriends(); }),
          { kind: 'small ghost icon-only', iconName: 'x', title: 'Retirer de mes amis' })))))
        : h('div', { class: 'empty' }, h('b', null, 'Pas encore d’amis'), 'Demandez l’identifiant de vos amis et ajoutez-les ci-dessus.'),
      f.outgoing.length ? [h('div', { class: 'section-title' }, h('h2', null, 'Demandes envoyées')),
        h('div', { class: 'card', style: { padding: '4px 16px' } }, h('div', { class: 'list' }, f.outgoing.map((u) => h('div', { class: 'list-item' },
          avatar(u.name, 2, { size: 's', id: u.id, pic: u.avatar }), h('div', { class: 'grow' }, h('b', null, u.name), h('div', { class: 'tiny muted' }, 'En attente')),
          btn('Annuler', (e) => act(e.currentTarget, async () => { await srv.post(`/friends/${u.id}/remove`); await refreshFriends(); }), { kind: 'small ghost' })))))] : null);
  }

  draw();
  refreshFriends().catch(() => {});
  const off = on('friends', draw);
  return () => off();
}
