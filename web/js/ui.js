// Small DOM toolkit: element builder, icons, modals, toasts, formatting.

export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v === null || v === undefined || v === false) continue;
      if (k === 'class') el.className = Array.isArray(v) ? v.filter(Boolean).join(' ') : v;
      else if (k === 'style' && typeof v === 'object') {
        for (const [sk, sv] of Object.entries(v)) { if (sk.startsWith('--')) el.style.setProperty(sk, sv); else el.style[sk] = sv; }
      }
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'value') el.value = v;
      else if (['checked', 'disabled', 'selected', 'readOnly', 'autofocus'].includes(k)) el[k] = !!v;
      else el.setAttribute(k, v === true ? '' : v);
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children) {
    if (c === null || c === undefined || c === false || c === true) continue;
    if (Array.isArray(c)) append(el, c);
    else if (c instanceof Node) el.appendChild(c);
    else el.appendChild(document.createTextNode(String(c)));
  }
}

/** Remembers the scroll position of the containers of {@code el}; the returned function puts it back (after a redraw). */
export function keepScroll(el) {
  const saved = [];
  for (let e = el.parentElement; e; e = e.parentElement) {
    if (/auto|scroll/.test(getComputedStyle(e).overflowY)) saved.push([e, e.scrollTop]);
  }
  if (document.scrollingElement) saved.push([document.scrollingElement, document.scrollingElement.scrollTop]);
  return () => saved.forEach(([e, top]) => { if (e.scrollTop !== top) e.scrollTop = top; });
}

export function mount(el, ...children) {
  while (el.firstChild) el.removeChild(el.firstChild);
  append(el, children);
  return el;
}

// ------------------------------------------------------------------- icons
const ICONS = {
  home: 'M3 11l9-7 9 7M5 10v10h5v-6h4v6h5V10',
  rooms: 'M4 5h16v6H4zM4 15h16v4H4zM8 8h.01M8 17h.01',
  friends: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8',
  history: 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3 3',
  rom: 'M6 2h9l5 5v15H6zM14 2v6h6M9 13h6M9 17h6',
  settings: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z',
  plus: 'M12 5v14M5 12h14',
  play: 'M7 4l13 8-13 8z',
  pause: 'M7 4h3v16H7zM14 4h3v16h-3z',
  ff: 'M3 5l8 7-8 7zM12 5l8 7-8 7z',
  volume: 'M11 5L6 9H2v6h4l5 4zM15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14',
  mute: 'M11 5L6 9H2v6h4l5 4zM23 9l-6 6M17 9l6 6',
  full: 'M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3',
  panel: 'M3 4h18v16H3zM15 4v16',
  back: 'M15 18l-6-6 6-6',
  x: 'M18 6L6 18M6 6l12 12',
  check: 'M20 6L9 17l-5-5',
  crown: 'M3 7l4 5 5-7 5 7 4-5-2 12H5z',
  link: 'M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7',
  copy: 'M9 9h11v11H9zM5 15H4V4h11v1',
  folder: 'M3 6a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z',
  file: 'M6 2h9l5 5v15H6zM14 2v6h6',
  download: 'M12 3v12M7 10l5 5 5-5M5 21h14',
  server: 'M3 4h18v6H3zM3 14h18v6H3zM7 7h.01M7 17h.01',
  user: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
  logout: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9',
  lock: 'M5 11h14v10H5zM8 11V7a4 4 0 0 1 8 0v4',
  unlock: 'M5 11h14v10H5zM8 11V7a4 4 0 0 1 7.8-1.2',
  trash: 'M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6',
  search: 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.3-4.3',
  info: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 16v-4M12 8h.01',
  alert: 'M12 9v4M12 17h.01M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z',
  dice: 'M4 4h16v16H4zM8.5 8.5h.01M15.5 8.5h.01M12 12h.01M8.5 15.5h.01M15.5 15.5h.01',
  edit: 'M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z',
  refresh: 'M21 12a9 9 0 1 1-2.6-6.4M21 3v6h-6',
  skull: 'M12 3a8 8 0 0 0-8 8c0 2.6 1.2 4.4 3 5.6V20a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1v-3.4c1.8-1.2 3-3 3-5.6a8 8 0 0 0-8-8zM9 12.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3zM15 12.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z',
  door: 'M14 3h5v18h-5M3 21h11V3L3 5zM10 12h.01',
  gamepad: 'M6 12h4M8 10v4M15 11h.01M18 13h.01M17.3 5H6.7a4 4 0 0 0-4 3.6L2 15a3 3 0 0 0 5.4 2L9 15h6l1.6 2A3 3 0 0 0 22 15l-.7-6.4A4 4 0 0 0 17.3 5z',
  save: 'M5 3h11l5 5v13H5zM8 3v5h8M8 21v-7h8v7',
  eye: 'M1 12s4-8 11-8 11 8 11 8-4 8-11 8S1 12 1 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  'screens-v': 'M7 2h10v9H7zM7 13h10v9H7z',
  'screens-h': 'M1 7h10v10H1zM13 7h10v10H13z',
  chat: 'M21 12a8 8 0 0 1-11.6 7.1L3 21l1.9-6.4A8 8 0 1 1 21 12z',
  send: 'M22 2L11 13M22 2l-7 20-4-9-9-4z',
  globe: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM2 12h20M12 2a15 15 0 0 1 0 20M12 2a15 15 0 0 0 0 20',
  block: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM4.9 4.9l14.2 14.2',
  image: 'M3 3h18v18H3zM8.5 10a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3zM21 15l-5-5L5 21',
  chevron: 'M6 9l6 6 6-6',
  swords: 'M14.5 17.5L3 6V3h3l11.5 11.5M13 19l6-6M16 16l4 4M19 21l2-2M9.5 6.5L13 3h3v3l-3.5 3.5M5 14l-2 2 3 3 2-2',
};

export function icon(name, cls = '') {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('class', `icon ${cls}`.trim());
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', ICONS[name] || ICONS.info);
  svg.appendChild(path);
  return svg;
}

export function btn(label, onClick, { kind = '', iconName = null, title = null, disabled = false, type = 'button' } = {}) {
  return h('button', { class: `btn ${kind}`.trim(), type, title, disabled, onclick: onClick },
    iconName ? icon(iconName) : null, label);
}

export function iconBtn(name, onClick, title, kind = 'ghost') {
  return h('button', { type: 'button', class: `btn icon-only ${kind}`, title, 'aria-label': title, onclick: onClick }, icon(name));
}

export function switchEl(checked, onChange, { disabled = false } = {}) {
  const input = h('input', { type: 'checkbox', checked, disabled, onchange: (e) => onChange(e.target.checked) });
  return h('label', { class: ['switch', disabled && 'disabled'] }, input, h('span', { class: 'track' }));
}

export function seg(options, value, onChange, { disabled = false } = {}) {
  return h('div', { class: 'seg' }, options.map(([v, label]) =>
    h('button', { type: 'button', class: v === value ? 'active' : '', disabled, onclick: () => onChange(v) }, label)));
}

export function field(label, control, hint) {
  return h('label', { class: 'field' }, h('span', null, label), control, hint ? h('small', null, hint) : null);
}

export function chip(text, kind = '', dot = false) {
  return h('span', { class: `chip ${kind}`.trim() }, dot ? h('span', { class: 'dot' }) : null, text);
}

// ----------------------------------------------------------------- players
export const PLAYER_COLORS = ['#e0735d', '#7c9cff', '#5fbf8f', '#d7a94b'];

export function colorFor(slot) {
  return PLAYER_COLORS[(Math.max(1, Number(slot) || 1) - 1) % PLAYER_COLORS.length];
}

// opens a player's profile card (set by the application: views are not imported here)
let profileOpener = null;
export function setProfileOpener(fn) { profileOpener = fn; }

/**
 * A player's round picture: their profile picture when they have one ({@code id} + {@code pic}, its version),
 * else the first letter of their name on their colour. With an id, a click shows their profile.
 */
export function avatar(name, slot, { size = '', online = null, id = null, pic = 0 } = {}) {
  const letter = (name || '?').trim().charAt(0).toUpperCase() || '?';
  const open = id && profileOpener ? (e) => { e.preventDefault(); e.stopPropagation(); profileOpener(id); } : null;
  return h('span', { class: ['avatar', size, open && 'click'], style: { background: colorFor(slot) }, title: name, onclick: open },
    pic && id ? h('img', { src: `/api/avatar/${encodeURIComponent(id)}?v=${pic}`, alt: '', loading: 'lazy', onerror: (e) => e.target.remove() }) : null,
    pic && id ? null : letter,
    online === null ? null : h('span', { class: ['presence', online && 'on'] }));
}

// ------------------------------------------------------------------ modals
export function modal({ title, body, actions = [], wide = false, onClose = null, dismissable = true }) {
  const root = document.getElementById('modals');
  let closed = false;
  const close = () => {
    if (closed) return;
    closed = true;
    back.remove();
    document.removeEventListener('keydown', onKey);
    if (onClose) onClose();
  };
  const onKey = (e) => { if (e.key === 'Escape' && dismissable) close(); };
  const box = h('div', { class: ['modal', wide && 'wide'], role: 'dialog', 'aria-modal': 'true' },
    h('div', { class: 'modal-head' }, h('h2', null, title), dismissable ? iconBtn('x', close, 'Fermer') : null),
    h('div', { class: 'modal-body' }, body),
    actions.length ? h('div', { class: 'modal-foot' }, actions.map((a) => (a instanceof Node ? a : btn(a.label, () => a.onClick(close), { kind: a.kind || '' })))) : null);
  const back = h('div', { class: 'modal-back', onmousedown: (e) => { if (e.target === back && dismissable) close(); } }, box);
  root.appendChild(back);
  document.addEventListener('keydown', onKey);
  setTimeout(() => { const f = box.querySelector('input, select, textarea'); if (f) f.focus(); }, 30);
  return { close, box };
}

export function confirmDialog(title, text, { okLabel = 'Confirmer', danger = false } = {}) {
  return new Promise((resolve) => {
    let answered = false;
    modal({
      title,
      body: h('p', { class: 'muted' }, text),
      onClose: () => { if (!answered) resolve(false); },
      actions: [
        { label: 'Annuler', onClick: (close) => { answered = true; resolve(false); close(); } },
        { label: okLabel, kind: danger ? 'danger solid' : 'primary', onClick: (close) => { answered = true; resolve(true); close(); } },
      ],
    });
  });
}

// ------------------------------------------------------------------ toasts
export function toast(text, kind = 'info', { title = null, action = null, ms = 5000 } = {}) {
  const root = document.getElementById('toasts');
  const el = h('div', { class: `toast ${kind}` },
    h('div', { class: 'grow' }, title ? h('b', null, title) : null, h('span', null, text),
      action ? h('div', null, btn(action.label, () => { action.onClick(); el.remove(); }, { kind: 'small primary' })) : null),
    h('button', { type: 'button', class: 'btn ghost small icon-only', onclick: () => el.remove(), title: 'Fermer' }, icon('x')));
  root.appendChild(el);
  while (root.children.length > 5) root.firstChild.remove();
  if (ms) setTimeout(() => el.remove(), ms);
  return el;
}

export function errorToast(e) {
  const text = e && e.message ? e.message : String(e);
  toast(text, 'danger', { ms: Math.max(7000, text.length * 70) });
}

/** Runs an async action from a button, showing errors. */
export async function act(button, fn) {
  if (button) button.disabled = true;
  try {
    return await fn();
  } catch (e) {
    errorToast(e);
    return undefined;
  } finally {
    if (button && button.isConnected) button.disabled = false;
  }
}

// -------------------------------------------------------------- formatting
export function timeAgo(ms) {
  if (!ms) return '';
  const s = Math.round((Date.now() - ms) / 1000);
  if (s < 60) return 'à l’instant';
  const m = Math.round(s / 60);
  if (m < 60) return `il y a ${m} min`;
  const hr = Math.round(m / 60);
  if (hr < 24) return `il y a ${hr} h`;
  const d = Math.round(hr / 24);
  if (d < 30) return `il y a ${d} j`;
  return new Date(ms).toLocaleDateString('fr-FR');
}

export function clock(ms) {
  return new Date(ms).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
}

export function dateTime(ms) {
  return new Date(ms).toLocaleString('fr-FR', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

export function plural(n, one, many) {
  return `${n} ${n > 1 ? many : one}`;
}
