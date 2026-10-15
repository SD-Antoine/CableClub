// Shared state of the interface, kept up to date by the event stream of the
// local application (its own events + the SLT server's, relayed).

import { local, srv } from './api.js';

export const store = {
  state: null,          // /api/state of the application
  rooms: new Map(),     // id -> room (full view for members)
  friends: { friends: [], incoming: [], outgoing: [] },
  logs: new Map(),      // roomId -> journal lines
  connected: false,
  // chat: the channels of this player (general, rooms, friends) with their unread count;
  // open = channels shown right now (their new messages are read at once)
  chat: { channels: null, online: 0, admin: false, open: new Map() },
};

const listeners = new Map();

export function on(event, fn) {
  if (!listeners.has(event)) listeners.set(event, new Set());
  listeners.get(event).add(fn);
  return () => listeners.get(event).delete(fn);
}

export function emit(event, data) {
  for (const fn of listeners.get(event) || []) {
    try { fn(data); } catch (e) { console.error(e); }
  }
  for (const fn of listeners.get('*') || []) {
    try { fn(event, data); } catch (e) { console.error(e); }
  }
}

export const me = () => store.state?.link?.user || null;
export const loggedIn = () => !!store.state?.link?.loggedIn;

export async function refreshState() {
  store.state = await local.get('/api/state');
  emit('state', store.state);
  return store.state;
}

export async function refreshRooms() {
  if (!loggedIn()) return;
  const r = await srv.get('/rooms');
  const seen = new Set();
  for (const room of r.rooms) {
    seen.add(room.id);
    const known = store.rooms.get(room.id);
    // the list holds short views: keep a full view we already have
    if (!known || room.invited || !known.zones) store.rooms.set(room.id, room);
    else store.rooms.set(room.id, { ...known, ...room, zones: known.zones, rules: known.rules ?? room.rules });
  }
  for (const id of [...store.rooms.keys()]) {
    const x = store.rooms.get(id);
    if (!seen.has(id) && (x.state === 'lobby' || x.state === 'running')) store.rooms.delete(id);
  }
  emit('rooms', store.rooms);
}

export async function loadRoom(id) {
  const room = await srv.get(`/rooms/${id}`);
  store.rooms.set(id, room);
  emit('room', room);
  return room;
}

export async function refreshFriends() {
  if (!loggedIn()) return;
  store.friends = await srv.get('/friends');
  emit('friends', store.friends);
}

export async function loadLogs(roomId) {
  const r = await srv.get(`/rooms/${roomId}/logs?limit=2000`);
  store.logs.set(roomId, r.logs);
  emit('logs', { roomId, logs: r.logs });
  return r.logs;
}

export async function refreshChat() {
  if (!loggedIn()) return;
  const r = await srv.get('/chat');
  store.chat.channels = r.channels;
  store.chat.online = r.online;
  store.chat.admin = r.admin;
  // a conversation shown right now is read (the server may not know it yet)
  for (const c of r.channels) if (c.unread && store.chat.open.has(c.ch) && !document.hidden) markChatRead(c.ch);
  emit('chat-list', store.chat);
}

/** Unread messages of the rooms and of the friends (the general channel only shows a dot). */
export function chatUnread() {
  return (store.chat.channels || []).filter((c) => c.ch !== 'global').reduce((a, c) => a + (c.unread || 0), 0);
}

/** A channel is shown (count > 0) or no longer: its new messages count as read while it is. */
export function chatOpen(ch, on) {
  const n = (store.chat.open.get(ch) || 0) + (on ? 1 : -1);
  if (n > 0) store.chat.open.set(ch, n); else store.chat.open.delete(ch);
  if (on) markChatRead(ch);
}

const readTimers = new Map();
export function markChatRead(ch) {
  const c = (store.chat.channels || []).find((x) => x.ch === ch);
  const last = c?.last?.id;
  if (c && c.unread) { c.unread = 0; emit('chat-list', store.chat); }
  if (!last) return;
  clearTimeout(readTimers.get(ch));
  readTimers.set(ch, setTimeout(() => srv.post(`/chat/${encodeURIComponent(ch)}/read`, { id: last }).catch(() => {}), 400));
}

function chatArrived(d) {
  const list = store.chat.channels;
  const c = list?.find((x) => x.ch === d.ch);
  const mine = d.msg?.from === me()?.id;
  if (c) {
    c.last = d.msg;
    if (!mine) {
      if (store.chat.open.has(d.ch) && !document.hidden) markChatRead(d.ch);
      else c.unread = (c.unread || 0) + 1;
    }
  } else if (list) {
    // a channel this page did not know yet (new friend, new room): the list is read again
    refreshChat().catch(() => {});
  }
  emit('chat', d);
  emit('chat-list', store.chat);
}

export function pendingInvites() {
  return [...store.rooms.values()].filter((r) => r.invited);
}

let source = null;
// this page: its stream is forgotten at once when the window closes
export const PAGE_ID = Math.random().toString(36).slice(2) + Date.now().toString(36);

export function connectStream() {
  if (source) source.close();
  source = new EventSource(`/api/stream?page=${PAGE_ID}`);
  const handle = (name, fn) => source.addEventListener(name, (e) => {
    let data = null;
    try { data = JSON.parse(e.data); } catch { data = e.data; }
    fn(data);
  });
  handle('hello', (s) => {
    if (!s || !s.link) return; // only the application's own state, never a relayed server greeting
    store.state = s; store.connected = true; emit('state', s); emit('hello', s);
  });
  handle('link', (l) => {
    if (store.state) store.state.link = l;
    emit('state', store.state);
    if (l.connected) { refreshRooms().catch(() => {}); refreshFriends().catch(() => {}); }
  });
  handle('session', (l) => { if (store.state) store.state.link = l; emit('state', store.state); emit('session', l); });
  handle('host', (hst) => { if (store.state) store.state.host = hst; emit('host', hst); emit('state', store.state); });
  handle('update', (u) => { if (store.state) store.state.update = u; emit('update', u); emit('state', store.state); });
  handle('player', (p) => { if (store.state) store.state.player = p; emit('player', p); });
  handle('rom', (r) => emit('rom', r));
  // the phone: ROM files picked in Android's file picker, being copied and added (a job to follow)
  handle('rom-import', (d) => emit('rom-import', d));
  handle('room', (room) => {
    const known = store.rooms.get(room.id);
    store.rooms.set(room.id, known && !room.zones && known.zones ? { ...known, ...room } : room);
    emit('room', store.rooms.get(room.id));
  });
  handle('room-deleted', (d) => { store.rooms.delete(d.roomId); emit('room-deleted', d); emit('rooms', store.rooms); });
  handle('log', (line) => {
    const list = store.logs.get(line.roomId);
    if (list && !list.some((x) => x.seq === line.seq)) list.push(line);
    emit('log', line);
  });
  handle('live', (d) => {
    const room = store.rooms.get(d.roomId);
    const m = room?.members?.find((x) => x.userId === d.userId);
    if (m) m.live = d.live;
    emit('live', d);
  });
  handle('presence', (d) => {
    const room = store.rooms.get(d.roomId);
    const m = room?.members?.find((x) => x.userId === d.userId);
    if (m) m.online = d.online;
    emit('presence', d);
  });
  handle('notice', (n) => emit('notice', n));
  handle('chat', (d) => chatArrived(d));
  handle('chat-delete', (d) => emit('chat-delete', d));
  handle('battle', (b) => emit('battle', b));
  handle('ghost', (g) => emit('ghost', g));
  handle('friends', (f) => { refreshFriends().catch(() => {}); emit('friends-event', f); });
  handle('invite', (i) => { refreshRooms().catch(() => {}); emit('invite', i); });
  // moderators: a report arrived or was handled (count in the profile)
  handle('report', (d) => {
    const u = store.state?.link?.user;
    if (u) u.reports = d.open;
    if (d.report) emit('notice', { level: 'warn', text: `Signalement : ${d.report.userName}, par ${d.report.byName}. À voir dans Mon profil › Modération.` });
    emit('report', d);
  });
  handle('kicked', (k) => { store.rooms.delete(k.roomId); emit('kicked', k); emit('rooms', store.rooms); });
  source.onerror = () => {
    if (store.connected) { store.connected = false; emit('disconnected'); }
  };
  source.onopen = () => { store.connected = true; };
}
