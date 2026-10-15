// Written chat: the general channel, one per room, private messages between
// friends. chatPanel() is one conversation (page, room, side panel of the
// game); renderChat() the page with every channel of the player.

import { h, mount, btn, avatar, icon, act, errorToast, clock, confirmDialog } from '../ui.js';
import { srv } from '../api.js';
import { store, on, me, refreshChat, chatOpen, markChatRead } from '../store.js';
import { mobile } from '../platform.js';
import { openReport } from './moderation.js';

const MAX = 500;
// messages of one author closer than this are shown as one block
const GROUP_MS = 5 * 60 * 1000;

function dayLabel(ms) {
  const d = new Date(ms);
  const today = new Date();
  const yesterday = new Date(Date.now() - 86400000);
  if (d.toDateString() === today.toDateString()) return 'Aujourd’hui';
  if (d.toDateString() === yesterday.toDateString()) return 'Hier';
  return d.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
}

/**
 * One conversation: its messages (older ones on demand) and the field to write.
 * @param ch       "global", "room-ID" or "dm-USERID"
 * @param compact  narrow version (side panel of the game, room page)
 * @return { el, destroy }
 */
export function chatPanel(ch, { compact = false, placeholder = null, readOnly = false } = {}) {
  const st = { msgs: [], more: false, loading: true, error: null, admin: false };
  const list = h('div', { class: 'chat-msgs' });
  const input = h('textarea', { class: 'input chat-input', rows: 1, maxlength: MAX, placeholder: placeholder || 'Écrire un message…' });
  const send = btn('', () => submit(), { kind: 'primary icon-only', iconName: 'send', title: 'Envoyer (Entrée)' });
  const form = readOnly ? h('div', { class: 'chat-closed tiny faint' }, 'Discussion en lecture seule.')
    : h('div', { class: 'chat-form' }, input, send);
  const el = h('div', { class: ['chat-panel', compact && 'compact'] }, list, form);
  const me_ = me();

  input.addEventListener('keydown', (e) => {
    // the game keys must not reach the game while typing (screen.js ignores fields); Enter sends
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); }
    if (e.key === 'Escape') input.blur(); // back to the game
  });
  input.addEventListener('input', () => {
    input.style.height = 'auto';
    input.style.height = `${Math.min(120, input.scrollHeight)}px`;
  });

  async function submit() {
    const text = input.value.trim();
    if (!text) return;
    send.disabled = true;
    try {
      const m = await srv.post(`/chat/${encodeURIComponent(ch)}`, { text });
      input.value = '';
      input.style.height = 'auto';
      add(m, true);
    } catch (e) { errorToast(e); } finally { send.disabled = false; input.focus(); }
  }

  function atBottom() {
    return list.scrollHeight - list.scrollTop - list.clientHeight < 40;
  }

  function add(m, scroll) {
    if (!m || st.msgs.some((x) => x.id === m.id)) return;
    const stick = scroll || atBottom();
    st.msgs.push(m);
    st.msgs.sort((a, b) => a.id - b.id);
    if (st.msgs.length > 400) st.msgs.splice(0, st.msgs.length - 400);
    draw();
    if (stick) list.scrollTop = list.scrollHeight;
  }

  async function load(older) {
    const before = older && st.msgs.length ? st.msgs[0].id : 0;
    try {
      const r = await srv.get(`/chat/${encodeURIComponent(ch)}?limit=60${before ? `&before=${before}` : ''}`);
      st.admin = r.admin;
      st.more = r.more;
      const prevHeight = list.scrollHeight;
      const prevTop = list.scrollTop;
      const known = new Set(st.msgs.map((m) => m.id));
      st.msgs = [...r.messages.filter((m) => !known.has(m.id)), ...st.msgs].sort((a, b) => a.id - b.id);
      st.loading = false;
      draw();
      if (older) list.scrollTop = prevTop + (list.scrollHeight - prevHeight);
      else list.scrollTop = list.scrollHeight;
    } catch (e) {
      st.loading = false;
      st.error = e.message;
      draw();
    }
  }

  async function remove(m) {
    if (!await confirmDialog('Supprimer le message', 'Il disparaît pour tout le monde.', { okLabel: 'Supprimer', danger: true })) return;
    try { await srv.post(`/chat/${encodeURIComponent(ch)}/${m.id}/delete`); } catch (e) { errorToast(e); }
  }

  function draw() {
    const items = [];
    if (st.more) items.push(h('div', { class: 'chat-more' }, btn('Messages plus anciens', (e) => act(e.currentTarget, () => load(true)), { kind: 'small ghost' })));
    if (st.loading) items.push(h('div', { class: 'row muted small chat-empty' }, h('span', { class: 'spinner' }), 'Chargement…'));
    else if (st.error) items.push(h('div', { class: 'chat-empty small warn' }, st.error));
    else if (!st.msgs.length) items.push(h('div', { class: 'chat-empty small faint' }, 'Aucun message pour l’instant. Lancez la discussion !'));
    let prev = null;
    for (const m of st.msgs) {
      const day = dayLabel(m.at);
      if (!prev || dayLabel(prev.at) !== day) items.push(h('div', { class: 'chat-day' }, h('span', null, day)));
      const grouped = prev && prev.from === m.from && m.at - prev.at < GROUP_MS && dayLabel(prev.at) === day;
      const mine = m.from === me_?.id;
      const canDelete = mine || st.admin;
      items.push(h('div', { class: ['chat-msg', grouped && 'grouped', mine && 'mine'] },
        grouped ? h('time', { class: 'chat-time-side' }, clock(m.at))
          : avatar(m.name, 2, { size: compact ? 's' : '', id: m.from, pic: m.avatar }),
        h('div', { class: 'chat-body' },
          grouped ? null : h('div', { class: 'chat-meta' }, h('b', null, m.name), h('time', null, clock(m.at))),
          h('div', { class: 'chat-text' }, m.text)),
        canDelete ? h('button', { class: 'chat-del', title: 'Supprimer', onclick: () => remove(m) }, icon('trash')) : null,
        mine ? null : h('button', { class: ['chat-del', 'chat-report', canDelete && 'second'], title: 'Signaler ce message',
          onclick: () => openReport({ userId: m.from, name: m.name, ch, msg: m.id, text: m.text }) }, icon('flag'))));
      prev = m;
    }
    mount(list, items);
  }

  const offs = [
    on('chat', (d) => { if (d.ch === ch) add(d.msg, false); }),
    on('chat-delete', (d) => { if (d.ch === ch) { st.msgs = st.msgs.filter((m) => m.id !== d.id); draw(); } }),
  ];
  // new messages of this channel are read while it is shown (and the window visible)
  let shown = false;
  const setVisible = (v) => {
    if (v === shown) return;
    shown = v;
    chatOpen(ch, v);
    if (v) requestAnimationFrame(() => { list.scrollTop = list.scrollHeight; });
  };
  const onVisible = () => { if (!document.hidden && shown) markChatRead(ch); };
  document.addEventListener('visibilitychange', onVisible);
  setVisible(true);
  draw();
  load(false);
  return {
    el,
    focus: () => input.focus(),
    /** The panel is (no longer) on screen: its messages count as read only while it is. */
    setVisible,
    destroy: () => { offs.forEach((f) => f()); document.removeEventListener('visibilitychange', onVisible); setVisible(false); },
  };
}

/**
 * The chat page: the player's channels on the left, the open one on the right. On the phone, one at a time: the
 * list (no channel given), or the conversation with a way back to the list.
 */
export function renderChat(container, current) {
  const page = h('div', { class: ['chat-page', mobile && (current ? 'phone-open' : 'phone-list')] });
  container.appendChild(page);
  const side = h('aside', { class: 'chat-side' });
  const main = h('section', { class: 'chat-main' });
  mount(page, side, main);
  let panel = null;
  let shown = null;
  const ch = current || 'global';

  function title(c) {
    if (!c) return ch.startsWith('dm-') ? 'Message privé' : 'Discussion';
    return c.ch === 'global' ? 'Général' : c.title;
  }

  function drawSide() {
    const list = store.chat.channels;
    if (!list) { mount(side, h('div', { class: 'row muted small', style: { padding: '14px' } }, h('span', { class: 'spinner' }), 'Chargement…')); return; }
    const entry = (c, lead, sub) => h('a', { class: ['chat-chan', c.ch === ch && 'active', c.unread && 'unread'], href: `#/chat/${c.ch}` },
      lead, h('div', { class: 'grow' }, h('b', { class: 'ellipsis' }, title(c)),
        h('small', { class: 'ellipsis' }, c.last ? `${c.last.from === me()?.id ? 'Vous' : c.last.name} : ${c.last.text}` : sub)),
      c.unread ? h('span', { class: 'count' }, c.unread > 99 ? '99+' : c.unread) : null);
    const global = list.find((c) => c.ch === 'global');
    const rooms = list.filter((c) => c.ch.startsWith('room-'));
    const dms = list.filter((c) => c.ch.startsWith('dm-'))
      .sort((a, b) => (b.last?.at || 0) - (a.last?.at || 0) || Number(b.online) - Number(a.online) || a.title.localeCompare(b.title));
    mount(side,
      h('div', { class: 'chat-side-title' }, h('h2', null, 'Chat')),
      global ? entry(global, h('span', { class: 'chat-chan-icon' }, icon('globe')), `${store.chat.online} joueur(s) en ligne`) : null,
      rooms.length ? h('div', { class: 'chat-group' }, 'Mes salles') : null,
      rooms.map((c) => entry(c, h('span', { class: 'chat-chan-icon' }, icon('rooms')), 'Discussion de la salle')),
      h('div', { class: 'chat-group' }, 'Amis'),
      dms.length ? dms.map((c) => entry(c, avatar(c.user?.name, 2, { size: 's', online: !!c.online, id: null, pic: 0 }), c.online ? 'En ligne' : 'Hors ligne'))
        : h('p', { class: 'tiny faint', style: { padding: '4px 12px' } }, 'Ajoutez des amis pour leur écrire en privé.'));
    // the small pictures of friends (built here: the helper would open their profile instead of the conversation)
    side.querySelectorAll('.chat-chan').forEach((a, i) => {
      const c = [global, ...rooms, ...dms].filter(Boolean)[i];
      const av = a.querySelector('.avatar');
      if (c?.user?.avatar && av && !av.querySelector('img')) {
        mount(av, h('img', { src: `/api/avatar/${encodeURIComponent(c.user.id)}?v=${c.user.avatar}`, alt: '' }),
          h('span', { class: ['presence', c.online && 'on'] }));
      }
    });
  }

  function drawMain() {
    const c = (store.chat.channels || []).find((x) => x.ch === ch);
    if (shown !== ch) {
      panel?.destroy();
      panel = chatPanel(ch, { placeholder: ch === 'global' ? 'Écrire à tout le monde…' : ch.startsWith('dm-') ? `Écrire à ${c?.title || '…'}…` : 'Écrire à la salle…' });
      shown = ch;
    }
    const head = h('div', { class: 'chat-head' },
      mobile ? h('a', { class: 'btn ghost icon-only', href: '#/chat', title: 'Conversations' }, icon('back')) : null,
      ch === 'global' ? h('span', { class: 'chat-chan-icon big' }, icon('globe'))
        : ch.startsWith('dm-') ? avatar(c?.title, 2, { id: ch.slice(3), pic: c?.user?.avatar || 0, online: c ? !!c.online : null })
          : h('span', { class: 'chat-chan-icon big' }, icon('rooms')),
      h('div', { class: 'grow' }, h('h2', null, title(c)), h('small', { class: 'muted' },
        ch === 'global' ? `Tout le serveur · ${store.chat.online} joueur(s) en ligne · restez courtois`
          : ch.startsWith('room-') ? 'Les joueurs de la salle' : c?.online ? 'En ligne' : 'Hors ligne')),
      ch.startsWith('room-') ? btn('Voir la salle', () => { location.hash = `#/salle/${ch.slice(5)}`; }, { kind: 'small ghost' }) : null);
    mount(main, head, panel.el);
  }

  drawSide();
  if (mobile && !current) {
    refreshChat().then(() => drawSide()).catch(() => {});
    const offList = on('chat-list', () => drawSide());
    return () => offList();
  }
  drawMain();
  refreshChat().then(() => { drawSide(); drawMain(); }).catch(() => {});
  // the phone opens its keyboard only when the player touches the field
  if (!mobile) setTimeout(() => panel?.focus(), 50);
  const off = on('chat-list', () => drawSide());
  return () => { off(); panel?.destroy(); };
}
