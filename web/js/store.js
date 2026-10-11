// Shared state of the interface, kept up to date by the event stream of the
// local application (its own events + the SLT server's, relayed).

import { local, srv } from './api.js';

export const store = {
  state: null,          // /api/state of the application
  rooms: new Map(),     // id -> room (full view for members)
  friends: { friends: [], incoming: [], outgoing: [] },
  logs: new Map(),      // roomId -> journal lines
  connected: false,
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

export function pendingInvites() {
  return [...store.rooms.values()].filter((r) => r.invited);
}

let source = null;

export function connectStream() {
  if (source) source.close();
  source = new EventSource('/api/stream');
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
  handle('ghost', (g) => emit('ghost', g));
  handle('friends', (f) => { refreshFriends().catch(() => {}); emit('friends-event', f); });
  handle('invite', (i) => { refreshRooms().catch(() => {}); emit('invite', i); });
  handle('kicked', (k) => { store.rooms.delete(k.roomId); emit('kicked', k); emit('rooms', store.rooms); });
  source.onerror = () => {
    if (store.connected) { store.connected = false; emit('disconnected'); }
  };
  source.onopen = () => { store.connected = true; };
}
