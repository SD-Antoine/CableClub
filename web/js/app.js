// Cable Club interface: boot, layout, router, global notifications.

import { h, mount, icon, avatar, toast, modal, field, errorToast, setProfileOpener } from './ui.js';
import {
  store, on, refreshState, refreshRooms, refreshFriends, refreshChat, connectStream, loggedIn, me, pendingInvites, chatUnread, PAGE_ID,
} from './store.js';
import { srv, local } from './api.js';
import { renderWelcome } from './views/welcome.js';
import { renderMode, MODES } from './views/mode.js';
import { renderRoom } from './views/room.js';
import { renderPlay } from './views/play.js';
import { renderFriends } from './views/friends.js';
import { renderChat } from './views/chat.js';
import { renderProfile, openProfile } from './views/profile.js';
import { renderLibrary } from './views/library.js';
import { renderSettings, settingsPage, SETTINGS_MENU } from './views/settings.js';
import { openUpdate, updateLink } from './views/update.js';
import { watchBattles } from './views/battle.js';
import { renderMe } from './views/me.js';
import { mobile } from './platform.js';

const root = document.getElementById('root');
let cleanup = null;
let currentRoute = null;

// what is not part of a game mode: friends, chat, ROMs, settings
const NAV = [
  { id: 'friends', hash: '#/amis', label: 'Amis', icon: 'friends' },
  { id: 'chat', hash: '#/chat', label: 'Chat', icon: 'chat' },
  { id: 'library', hash: '#/roms', label: 'Mes ROMs', icon: 'rom' },
];

// the « Réglages » dropdown of the rail (and « Autres » in it): open while the player is on a page of the settings,
// then as they left it (the rail is drawn again at each news)
let settingsOpen = false;
let othersOpen = false;

export function applyTheme(theme) {
  const t = ['onyx', 'blanc', 'nuit'].includes(theme) ? theme : 'onyx';
  document.documentElement.dataset.theme = t;
  try { localStorage.setItem('slt.theme', t); } catch { /* storage unavailable */ }
}

export function go(hash) {
  if (location.hash === hash) route();
  else location.hash = hash;
}

function parse() {
  const parts = (location.hash || '#/').replace(/^#\/?/, '').split('/').filter(Boolean).map(decodeURIComponent);
  switch (parts[0]) {
    case undefined: return { id: 'mode', mode: 'soullink' };
    case 'salle': return { id: 'room', roomId: parts[1] };
    case 'jouer': return { id: 'play', roomId: parts[1] };
    case 'amis': return { id: 'friends' };
    // the phone shows the list of the conversations first, then the one opened
    case 'chat': return { id: 'chat', ch: parts[1] || (mobile ? null : 'global') };
    case 'profil': return { id: 'profile' };
    case 'moi': return { id: 'me' };
    // older links of the history (before the tabs of the modes)
    case 'historique': return parts[1] ? { id: 'room', roomId: parts[1], history: true } : { id: 'mode', mode: 'soullink', tab: 'historique' };
    case 'roms': return { id: 'library' };
    case 'reglages': return { id: 'settings', tab: parts[1] };
    default: return MODES[parts[0]] && !MODES[parts[0]].soon ? { id: 'mode', mode: parts[0], tab: parts[1] } : { id: 'mode', mode: 'soullink' };
  }
}

function rail(active, route) {
  const user = me();
  const link = store.state?.link || {};
  const friendsCount = store.friends.incoming.length;
  const unread = chatUnread();
  const globalNew = (store.chat.channels || []).some((c) => c.ch === 'global' && c.unread);
  return h('nav', { class: 'rail' },
    h('div', { class: 'brand' }, h('img', { src: 'img/logo.svg', alt: '' }),
      h('div', { class: 'brand-text' }, h('b', null, 'Cable Club'), h('small', null, 'Pokémon à plusieurs'))),
    h('div', { class: 'rail-group' }, 'Modes de jeu'),
    Object.entries(MODES).map(([id, m]) => (m.soon
      ? h('span', { class: ['nav-item', 'mode-item', 'soon'], title: `${m.name} : bientôt` },
        h('img', { src: m.logo, alt: '' }), h('span', { class: 'label' }, m.name), h('span', { class: 'chip' }, 'Bientôt'))
      : h('a', { class: ['nav-item', 'mode-item', active === `mode:${id}` && 'active'], href: m.hash, title: m.name },
        h('img', { src: m.logo, alt: '' }), h('span', { class: 'label' }, m.name),
        pendingInvites().some((i) => (i.mode || 'soullink') === id) ? h('span', { class: 'count', title: 'Invitations' }, pendingInvites().filter((i) => (i.mode || 'soullink') === id).length) : null))),
    h('div', { class: 'rail-sep' }),
    NAV.map((n) => h('a', { class: ['nav-item', active === n.id && 'active'], href: n.hash, title: n.label },
      icon(n.icon), h('span', { class: 'label' }, n.label),
      n.id === 'friends' && friendsCount ? h('span', { class: 'count' }, friendsCount) : null,
      n.id === 'chat' && unread ? h('span', { class: 'count' }, unread > 99 ? '99+' : unread) : null,
      n.id === 'chat' && !unread && globalNew ? h('span', { class: 'dot-new', title: 'Nouveaux messages dans le chat général' }) : null)),
    settingsMenu(route),
    h('div', { class: 'spacer' }),
    updateLink(),
    h('a', { class: ['me', active === 'profile' && 'active'], href: '#/profil', title: `Mon profil · ${link.server || ''}` },
      avatar(user?.name, 1, { size: 's', online: !!link.connected, id: user?.avatar ? user.id : null, pic: user?.avatar || 0 }),
      h('div', { class: 'me-text' }, h('b', null, user?.name || '—'),
        h('small', null, link.connected ? 'Connecté' : 'Hors ligne'))));
}

/** « Réglages »: a dropdown with a page for each entry (« Autres » is a dropdown too). */
function settingsMenu(route) {
  const here = route?.id === 'settings' ? settingsPage(route.tab).id : null;
  const toggle = (open, set) => () => { set(!open); refreshRail(); };
  const entry = (m, depth) => (m.children
    ? [h('button', { type: 'button', class: ['nav-item', 'nav-sub-item', 'nav-group', `depth-${depth}`, m.children.some((c) => c.id === here) && !othersOpen && 'active'],
      'aria-expanded': String(othersOpen), onclick: toggle(othersOpen, (v) => { othersOpen = v; }) },
    h('span', { class: 'label' }, m.label), icon('chevron', ['chevron', othersOpen && 'open'].filter(Boolean).join(' '))),
    othersOpen ? h('div', { class: 'nav-sub' }, m.children.map((c) => entry(c, depth + 1))) : null]
    : h('a', { class: ['nav-item', 'nav-sub-item', `depth-${depth}`, here === m.id && 'active'], href: `#/reglages/${m.id}` }, h('span', { class: 'label' }, m.label)));
  return h('div', { class: 'nav-dropdown' },
    h('button', { type: 'button', class: ['nav-item', 'nav-group', route?.id === 'settings' && !settingsOpen && 'active'], title: 'Réglages', 'aria-expanded': String(settingsOpen),
      onclick: () => {
        if (settingsOpen) { settingsOpen = false; refreshRail(); return; }
        settingsOpen = true;
        if (currentRoute?.id === 'settings') refreshRail(); else go('#/reglages/jeu');
      } },
    icon('settings'), h('span', { class: 'label' }, 'Réglages'), icon('chevron', ['chevron', settingsOpen && 'open'].filter(Boolean).join(' '))),
    settingsOpen ? h('div', { class: 'nav-sub' }, SETTINGS_MENU.map((m) => entry(m, 1))) : null);
}

/**
 * The phone's bottom bar (on the side when the phone is held sideways): the rooms of the game modes, the friends,
 * the chat, the ROMs and « Moi » (profile and settings).
 */
function tabBar(active) {
  const user = me();
  const friendsCount = store.friends.incoming.length;
  const unread = chatUnread();
  const invites = pendingInvites().length;
  const upd = store.state?.update?.state === 'available';
  const tab = (id, hash, label, iconName, count) => h('a', { class: ['tab-item', active === id && 'active'], href: hash },
    iconName === 'me' ? avatar(user?.name, 1, { size: 's', id: user?.avatar ? user.id : null, pic: user?.avatar || 0 }) : icon(iconName),
    h('span', null, label), count ? h('span', { class: 'count' }, count > 99 ? '99+' : count) : null);
  return h('nav', { class: 'tabbar' },
    tab('rooms', '#/', 'Salles', 'rooms', invites),
    tab('friends', '#/amis', 'Amis', 'friends', friendsCount),
    tab('chat', '#/chat', 'Chat', 'chat', unread),
    tab('library', '#/roms', 'ROMs', 'rom', 0),
    tab('me', '#/moi', 'Moi', 'me', upd ? '1' : 0));
}

function activeTab(r) {
  if (r.id === 'room' || r.id === 'play' || r.id === 'mode') return 'rooms';
  if (r.id === 'profile' || r.id === 'settings' || r.id === 'me') return 'me';
  return r.id;
}

function activeNav(r) {
  if (r.id === 'room' || r.id === 'play') return `mode:${store.rooms.get(r.roomId)?.mode || 'soullink'}`;
  if (r.id === 'mode') return `mode:${r.mode}`;
  return r.id;
}

function layout(r) {
  const main = h('main', { class: 'main', id: 'main' });
  if (mobile) {
    // the game takes the whole screen of the phone
    mount(root, h('div', { class: ['app', 'phone', r.id === 'play' && 'playing'] }, main, r.id === 'play' ? null : tabBar(activeTab(r))));
    return main;
  }
  mount(root, h('div', { class: ['app', r.id === 'play' && 'compact'] }, rail(activeNav(r), r), main));
  return main;
}

async function route() {
  if (cleanup) { try { cleanup(); } catch (e) { console.error(e); } cleanup = null; }
  const r = parse();
  currentRoute = r;
  if (r.id === 'settings') {
    settingsOpen = true;
    if (settingsPage(r.tab).id === 'mise-a-jour' || settingsPage(r.tab).id === 'donnees') othersOpen = true;
  }
  if (!loggedIn()) {
    mount(root, h('div', { id: 'main', class: 'main' }));
    cleanup = renderWelcome(document.getElementById('main'), () => { refreshAll(); go('#/'); });
    return;
  }
  const main = layout(r);
  try {
    switch (r.id) {
      case 'room': cleanup = await renderRoom(main, r.roomId, { history: !!r.history }); break;
      case 'play': cleanup = await renderPlay(main, r.roomId); break;
      case 'friends': cleanup = renderFriends(main); break;
      case 'chat': cleanup = renderChat(main, r.ch); break;
      case 'profile': cleanup = renderProfile(main); break;
      case 'me': cleanup = renderMe(main); break;
      case 'library': cleanup = renderLibrary(main); break;
      case 'settings': cleanup = renderSettings(main, { applyTheme, tab: r.tab }); break;
      default: cleanup = renderMode(main, r.mode, r.tab); break;
    }
  } catch (e) {
    errorToast(e);
    mount(main, h('div', { class: 'page' }, h('div', { class: 'empty' }, h('b', null, 'Impossible d’afficher cette page'), e.message)));
  }
}

/** Re-draws the rail (badges, connection) without touching the page. */
function refreshRail() {
  if (!loggedIn() || !currentRoute) return;
  if (mobile) {
    const bar = root.querySelector('.app > .tabbar');
    if (bar) bar.replaceWith(tabBar(activeTab(currentRoute)));
    return;
  }
  const old = root.querySelector('.app > .rail');
  if (old) old.replaceWith(rail(activeNav(currentRoute), currentRoute));
}

// ------------------------------------------------------------- naming
// When every player caught the Pokémon of a zone, the leader names the link
// (wherever they are in the interface, even in game).
let namingOpen = null;

function checkNaming(room) {
  const user = me();
  if (!room || !room.naming || !user || room.leaderId !== user.id || room.state !== 'running') {
    if (namingOpen && room && namingOpen.roomId === room.id && !room.naming) { namingOpen.close(); namingOpen = null; }
    return;
  }
  if (namingOpen && namingOpen.zone === room.naming.zone) return;
  if (namingOpen) namingOpen.close();
  const zone = (room.zones || []).find((z) => z.key === room.naming.zone);
  const mons = zone ? Object.entries(zone.entries).map(([uid, e]) => {
    const m = room.members.find((x) => x.userId === uid);
    return h('div', { class: 'list-item' }, avatar(m?.name, m?.slot, { size: 's' }),
      h('div', { class: 'grow' }, h('b', null, e.speciesName || `#${e.species}`), h('div', { class: 'tiny muted' }, `${m?.name || '?'} · N.${e.level}`)));
  }) : [];
  const input = h('input', { class: 'input big', maxlength: 10, placeholder: 'Surnom (10 lettres max.)' });
  const submit = async (close) => {
    const name = input.value.trim();
    if (!name) { input.focus(); return; }
    try {
      await srv.post(`/rooms/${room.id}/name`, { zone: room.naming.zone, name });
      close();
    } catch (e) { errorToast(e); }
  };
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') submit(namingOpen.close); });
  const m = modal({
    title: `Nommer le lien de ${room.naming.zoneName}`,
    dismissable: false,
    body: h('div', { class: 'stack' },
      h('p', { class: 'muted' }, 'Tous les joueurs ont capturé leur Pokémon de cette zone. Leur surnom commun sera écrit dans chaque jeu ; les parties reprennent dès qu’il est choisi.'),
      h('div', { class: 'list' }, mons), field('Surnom', input)),
    actions: [{ label: 'Valider le nom', kind: 'primary', onClick: (close) => submit(close) }],
    onClose: () => { namingOpen = null; },
  });
  namingOpen = { ...m, roomId: room.id, zone: room.naming.zone };
}

// ------------------------------------------------------------- boot
async function refreshAll() {
  await Promise.allSettled([refreshRooms(), refreshFriends(), refreshChat()]);
  refreshRail();
  for (const r of store.rooms.values()) checkNaming(r);
}

async function boot() {
  document.documentElement.classList.toggle('mobile', mobile);
  try {
    await refreshState();
  } catch (e) {
    mount(root, h('div', { class: 'boot' }, e.message));
    return;
  }
  applyTheme(store.state.config?.theme);
  connectStream();
  on('state', () => refreshRail());
  on('friends', () => refreshRail());
  on('rooms', () => refreshRail());
  on('chat-list', () => refreshRail());
  setProfileOpener((id) => openProfile(id));
  watchBattles();
  // a private message (or a message of a room) while its conversation is not shown
  on('chat', (d) => {
    const m = d.msg;
    if (!m || m.from === me()?.id || d.ch === 'global' || store.chat.open.has(d.ch) || currentRoute?.id === 'play') return;
    const where = d.ch.startsWith('dm-') ? 'Message privé' : `Salle « ${(store.chat.channels || []).find((c) => c.ch === d.ch)?.title || '…'} »`;
    toast(m.text.length > 140 ? `${m.text.slice(0, 140)}…` : m.text, 'info', {
      title: `${m.name} · ${where}`, ms: 8000, action: { label: 'Répondre', onClick: () => go(`#/chat/${d.ch}`) } });
  });
  on('session', (l) => { if (!l.loggedIn) { toast('Votre session a expiré : reconnectez-vous.', 'warn'); route(); } });
  on('room', (room) => { refreshRail(); checkNaming(room); });
  on('invite', (i) => toast(`${i.from} vous invite dans « ${i.roomName} » (${i.game}).`, 'info', {
    title: 'Invitation', ms: 12000, action: { label: 'Voir la salle', onClick: () => go(`#/salle/${i.roomId}`) } }));
  on('kicked', (k) => { toast(`Vous avez été retiré de la salle « ${k.roomName} ».`, 'warn'); if (currentRoute?.roomId === k.roomId) go('#/'); });
  on('room-deleted', (d) => { if (currentRoute?.roomId === d.roomId && currentRoute.id !== 'play') { toast('Cette salle a été supprimée.', 'warn'); go('#/'); } });
  on('friends-event', (f) => {
    if (f.reason === 'request' && f.from) toast(`${f.from} vous demande en ami.`, 'info', { action: { label: 'Voir', onClick: () => go('#/amis') } });
    if (f.reason === 'accepted' && f.from) toast(`${f.from} a accepté votre demande d’ami.`, 'ok');
  });
  on('notice', (n) => {
    if (currentRoute?.id === 'play') return; // shown over the game
    toast(n.text, n.level === 'danger' ? 'danger' : n.level === 'warn' ? 'warn' : n.level === 'ok' ? 'ok' : 'info');
  });
  // a new version on GitHub: said once per version, then the rail keeps a link
  let toldUpdate = null;
  const tellUpdate = (u) => {
    if (!u || u.state !== 'available' || toldUpdate === u.version || currentRoute?.id === 'play') return;
    toldUpdate = u.version;
    toast(`La version ${u.version} de Cable Club est disponible.`, 'info', {
      title: 'Mise à jour', ms: 15000, action: { label: 'Voir', onClick: () => openUpdate() } });
  };
  on('update', tellUpdate);
  window.addEventListener('hashchange', route);
  // the window closes (or reloads): the application closes too unless a window comes back
  // (on the phone, the app's own window never closes without the app)
  let saidBye = mobile;
  const bye = () => {
    if (saidBye) return;
    saidBye = true;
    try {
      navigator.sendBeacon('/api/ui/bye', new Blob([JSON.stringify({ t: store.state?.uiToken, page: PAGE_ID })], { type: 'text/plain' }));
    } catch { /* closing anyway */ }
  };
  window.addEventListener('beforeunload', bye);
  window.addEventListener('pagehide', bye);
  if (loggedIn()) await refreshAll();
  tellUpdate(store.state?.update);
  route();
}

export const app = { go, applyTheme, local };
boot();
