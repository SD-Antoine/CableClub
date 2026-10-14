// The game, inside the SLT window: emulator image in the centre, the other
// players live on the side, the room's pauses over the game.

import { h, mount, btn, avatar, confirmDialog, clock, colorFor, toast } from '../ui.js';
import { local, srv } from '../api.js';
import { store, on, loadRoom, loadLogs, me } from '../store.js';
import { GameScreen } from '../game/screen.js';
import { GhostLayer } from '../game/ghosts.js';
import { keymap } from '../game/keys.js';
import { tradeBox } from './trades.js';
import { chatPanel } from './chat.js';

export async function renderPlay(container, roomId) {
  let room = store.rooms.get(roomId) || await loadRoom(roomId);
  if (!room.zones) room = await loadRoom(roomId);
  const user = me();
  const cfg = store.state?.config || {};
  const ui = {
    info: null, side: cfg.sidePanel !== false, sideTab: 'partners', closed: false, connecting: true,
    big: null, autoPaused: false, userPaused: false,
    // whose game the side panel shows: '' = every partner
    stream: (() => { try { return sessionStorage.getItem(`slt.stream.${roomId}`) || ''; } catch { return ''; } })(),
  };
  const ds = room.game?.platform === 'nds';

  const canvas = h('canvas', { width: 240, height: 160, class: cfg.smooth ? 'smooth' : '' });
  const osd = h('div', { class: 'osd' });
  const veil = h('div');
  const ghostLayer = h('div', { class: 'pg-layer' });
  const screenBox = h('div', { class: 'screen-box' }, canvas, ghostLayer);
  const stage = h('div', { class: 'stage', tabindex: 0 }, screenBox, osd, veil);
  const bar = h('div', { class: 'play-bar' });
  const side = h('aside', { class: ['side', !ui.side && 'hidden'] });
  const root = h('div', { class: 'play' }, bar, h('div', { class: 'play-body' }, stage, side));
  container.appendChild(root);

  // ------------------------------------------------------------ screen
  const screen = new GameScreen(canvas, keymap(cfg), {
    onOpen: () => {
      ui.connecting = false;
      ui.closed = false;
      drawVeil();
      // paused by SLT when the player left this screen: resume
      try {
        if (sessionStorage.getItem(`slt.autopause.${roomId}`)) {
          sessionStorage.removeItem(`slt.autopause.${roomId}`);
          screen.cmd('pause', { on: false });
        }
      } catch { /* storage unavailable */ }
    },
    onInfo: (info) => { ui.info = info; ui.userPaused = info.paused; drawBar(); drawVeil(); fit(); },
    onOsd: (text) => showOsd(text),
    onClosed: (onPurpose) => { ui.closed = true; ui.connecting = false; ui.onPurpose = onPurpose; drawVeil(); drawBar(); },
    onResize: () => fit(),
    onFrame: (me) => ghosts.setMe(me),
    onSelf: (m) => ghosts.setSelf(m),
    onAction: (a) => { if (a === 'pause') togglePause(); if (a === 'fullscreen') fullscreen(); },
  });
  screen.setLayout(cfg.dsLayout);

  /** DS: the two screens one above the other, or side by side. */
  function toggleLayout() {
    const next = screen.layout === 'h' ? 'v' : 'h';
    screen.setLayout(next);
    local.post('/api/config', { dsLayout: next }).catch(() => {});
    drawBar();
    stage.focus();
  }

  function fit() {
    const w = canvas.width;
    const hgt = canvas.height;
    const r = stage.getBoundingClientRect();
    if (!w || !hgt || !r.width) return;
    let scale = Math.min((r.width - 24) / w, (r.height - 24) / hgt);
    if (scale >= 2) scale = Math.floor(scale); // whole multiples keep the pixels sharp
    canvas.style.width = `${Math.max(1, Math.floor(w * scale))}px`;
    canvas.style.height = `${Math.max(1, Math.floor(hgt * scale))}px`;
    ghostLayer.style.width = `${w}px`;
    ghostLayer.style.height = `${hgt}px`;
    ghosts.setSize(w, hgt);
    ghostLayer.style.transform = `scale(${Math.floor(w * scale) / w}, ${Math.floor(hgt * scale) / hgt})`;
  }

  // the other players inside the game (every generation): drawn where they stand on this player's map
  const ghosts = new GhostLayer(ghostLayer, (userId) => {
    const m = room.members.find((x) => x.userId === userId);
    const trading = (room.trades || []).some((t) => ['extract', 'swap'].includes(t.state) && (t.from === userId || t.to === userId));
    return m && m.userId !== user?.id ? { name: m.name, color: colorFor(m.slot), online: m.online, trading } : null;
  });
  ghosts.start();
  srv.get(`/rooms/${roomId}/ghosts`).then((r) => r.ghosts.forEach((g) => ghosts.update(g))).catch(() => {});
  const resizeObserver = new ResizeObserver(() => fit());
  resizeObserver.observe(stage);

  function showOsd(text, kind = '') {
    const el = h('div', { class: kind }, text);
    osd.appendChild(el);
    while (osd.children.length > 4) osd.firstChild.remove();
    setTimeout(() => el.remove(), 4500);
  }

  async function connect() {
    ui.connecting = true;
    ui.closed = false;
    drawVeil();
    try {
      let st = await local.post(`/api/rooms/${roomId}/play`);
      for (let i = 0; i < 60 && !(st.state === 'running' && st.wsPort); i++) {
        await new Promise((r) => setTimeout(r, 250));
        st = store.state?.player?.roomId === roomId ? store.state.player : st;
        if (st.state === 'stopped') throw new Error('Le jeu s’est fermé au démarrage (voir data\\lecteur.log).');
      }
      if (!(st.state === 'running' && st.wsPort)) throw new Error('Le jeu ne répond pas.');
      screen.connect(st.wsPort, st.wsToken);
      stage.focus();
    } catch (e) {
      ui.connecting = false;
      ui.closed = true;
      ui.error = e.message;
      drawVeil();
    }
  }

  // ------------------------------------------------------------ actions
  function togglePause() {
    screen.cmd('pause', { on: !ui.userPaused });
  }

  function fullscreen() {
    if (document.fullscreenElement) document.exitFullscreen();
    else root.requestFullscreen().catch(() => {});
  }

  async function quitGame() {
    if (!await confirmDialog('Quitter le jeu', 'Pensez à sauvegarder dans le jeu avant de quitter. La sauvegarde de la cartouche est enregistrée et copiée sur le serveur.', { okLabel: 'Quitter le jeu' })) return;
    screen.cmd('quit');
    setTimeout(() => { location.hash = `#/salle/${roomId}`; }, 300);
  }

  // ------------------------------------------------------------ toolbar
  // volume slider: kept across redraws, never redrawn while it is dragged
  const volume = h('input', { type: 'range', class: 'vol-range', min: 0, max: 100, step: 1, title: 'Volume' });
  let volTimer = null;
  const volEnd = () => {
    volume.blur(); // the game keys are ignored while a field has the focus
    if (ui.dragVol) { ui.dragVol = false; if (ui.barStale) { ui.barStale = false; drawBar(); } }
  };
  volume.addEventListener('pointerdown', () => { ui.dragVol = true; });
  volume.addEventListener('pointerup', volEnd);
  volume.addEventListener('input', () => {
    volume.style.setProperty('--fill', `${volume.value}%`);
    volume.classList.remove('muted');
    screen.cmd('volume', { v: Number(volume.value), muted: false });
    clearTimeout(volTimer);
    volTimer = setTimeout(() => local.post('/api/config', { volume: Number(volume.value) }).catch(() => {}), 400);
  });
  volume.addEventListener('change', volEnd);

  function drawBar() {
    if (ui.dragVol) { ui.barStale = true; return; }
    const i = ui.info || {};
    volume.value = i.volume ?? 80;
    volume.style.setProperty('--fill', `${i.muted ? 0 : (i.volume ?? 80)}%`);
    volume.classList.toggle('muted', !!i.muted);
    const myLive = room.members.find((m) => m.userId === user?.id)?.live || {};
    mount(bar,
      btn('', () => { location.hash = `#/salle/${roomId}`; }, { kind: 'ghost icon-only', iconName: 'back', title: 'Retour à la salle' }),
      h('div', { class: 'title' }, h('b', { class: 'ellipsis' }, room.name),
        myLive.zone ? h('span', { class: 'chip' }, myLive.zone) : null,
        i.fast ? h('span', { class: 'chip accent' }, `▶▶ ${i.speed === 0 ? 'sans limite' : '×' + i.speed}`) : null),
      btn('', togglePause, { kind: ['ghost icon-only', i.paused && 'on'].filter(Boolean).join(' '), iconName: i.paused ? 'play' : 'pause', title: i.paused ? 'Reprendre (P)' : 'Pause (P)' }),
      btn('', () => screen.cmd('ff-toggle'), { kind: ['ghost icon-only', i.fast && 'on'].filter(Boolean).join(' '), iconName: 'ff', title: 'Avance rapide (T, ou maintenir Tab)' }),
      h('select', { class: 'input', style: { width: '92px', height: '32px' }, title: 'Vitesse de l’avance rapide',
        onchange: (e) => { e.target.blur(); screen.cmd('speed', { v: Number(e.target.value) }); local.post('/api/config', { fastSpeed: Number(e.target.value) }).catch(() => {}); } },
      [2, 3, 4, 8, 0].map((v) => h('option', { value: v, selected: (i.speed ?? 4) === v }, v === 0 ? 'Max' : `×${v}`))),
      btn('', () => screen.cmd('volume', { muted: !i.muted }), { kind: 'ghost icon-only', iconName: i.muted || i.volume === 0 ? 'mute' : 'volume', title: i.muted ? 'Remettre le son' : 'Couper le son' }),
      volume,
      i.states ? btn('', () => screen.cmd('save-state', { slot: 1 }), { kind: 'ghost icon-only', iconName: 'save', title: 'Sauvegarde d’état' }) : null,
      i.states ? btn('', () => screen.cmd('load-state', { slot: 1 }), { kind: 'ghost icon-only', iconName: 'refresh', title: 'Charger l’état' }) : null,
      ds ? btn('', toggleLayout, { kind: ['ghost icon-only', screen.layout === 'h' && 'on'].filter(Boolean).join(' '),
        iconName: screen.layout === 'h' ? 'screens-h' : 'screens-v', title: screen.layout === 'h' ? 'Écrans l’un au-dessus de l’autre' : 'Écrans côte à côte' }) : null,
      btn('', fullscreen, { kind: 'ghost icon-only', iconName: 'full', title: 'Plein écran (F11)' }),
      btn('', () => { ui.side = !ui.side; side.classList.toggle('hidden', !ui.side); chat?.setVisible(ui.side && ui.sideTab === 'chat'); local.post('/api/config', { sidePanel: ui.side }).catch(() => {}); setTimeout(fit, 0); },
        { kind: ['ghost icon-only', ui.side && 'on'].filter(Boolean).join(' '), iconName: 'panel', title: 'Joueurs et journal' }),
      btn('Quitter le jeu', quitGame, { kind: 'ghost small' }));
  }

  // ------------------------------------------------------------ veils
  function drawVeil() {
    const d = room.directives || {};
    const pause = d.pause;
    let box = null;
    if (ui.connecting) {
      box = h('div', { class: 'veil-box' }, h('div', { class: 'row', style: { justifyContent: 'center' } }, h('span', { class: 'spinner' }), h('b', null, 'Lancement du jeu…')));
    } else if (ui.closed) {
      box = h('div', { class: 'veil-box' }, h('h2', null, ui.error ? 'Le jeu n’a pas pu démarrer' : 'Le jeu est fermé'),
        ui.error ? h('p', null, ui.error) : h('p', null, 'Votre sauvegarde est enregistrée.'),
        h('div', { class: 'row mt', style: { justifyContent: 'center' } },
          btn('Retour à la salle', () => { location.hash = `#/salle/${roomId}`; }),
          btn('Relancer', () => connect(), { kind: 'primary', iconName: 'play' })));
    } else if (pause) {
      const leaderNaming = pause.reason === 'naming' && room.leaderId === user?.id;
      box = h('div', { class: 'veil-box' },
        h('h2', null, pause.reason === 'naming' ? 'Nommage du lien' : pause.reason === 'closed' ? 'Aventure en pause' : 'Soul Link terminé'),
        h('p', null, leaderNaming ? `Choisissez le nom du lien de ${pause.zoneName}.` : pause.text),
        pause.reason === 'ended' ? h('div', { class: 'row mt', style: { justifyContent: 'center' } },
          btn('Voir le bilan', () => { location.hash = `#/salle/${roomId}`; }, { kind: 'primary' })) : null);
    } else if (ui.info?.paused) {
      box = h('div', { class: 'veil-box' }, h('h2', null, 'Pause'), h('p', null, 'Appuyez sur P ou sur le bouton pour reprendre.'),
        h('div', { class: 'row mt', style: { justifyContent: 'center' } }, btn('Reprendre', togglePause, { kind: 'primary', iconName: 'play' })));
    }
    mount(veil, box ? h('div', { class: 'veil' }, box) : null);
  }

  // ------------------------------------------------------------ side panel
  const images = new Map(); // userId -> { seq, url, timer }
  // the room's chat, kept while the game is shown (what is being typed survives the redraws)
  let chat = null;
  function drawSide() {
    const partners = [...room.members].filter((m) => m.userId !== user?.id).sort((a, b) => a.slot - b.slot);
    const unread = (store.chat.channels || []).find((c) => c.ch === `room-${roomId}`)?.unread || 0;
    const tabs = h('div', { class: 'tabs' },
      h('button', { class: ui.sideTab === 'partners' ? 'active' : '', onclick: () => { ui.sideTab = 'partners'; drawSide(); } }, partners.length ? 'Partenaires' : 'Équipe'),
      h('button', { class: ui.sideTab === 'chat' ? 'active' : '', onclick: () => { ui.sideTab = 'chat'; drawSide(); setTimeout(() => chat?.focus(), 0); } },
        'Chat', unread && ui.sideTab !== 'chat' ? h('span', { class: 'tab-count' }, unread) : null),
      h('button', { class: ui.sideTab === 'journal' ? 'active' : '', onclick: () => { ui.sideTab = 'journal'; drawSide(); } }, 'Journal'));
    let body;
    if (ui.sideTab === 'chat') {
      if (!chat) chat = chatPanel(`room-${roomId}`, { compact: true, placeholder: 'Écrire à la salle (Entrée)…' });
      chat.setVisible(ui.side);
      mount(side, tabs, h('div', { class: 'side-body chat-side-body' }, chat.el));
      return;
    }
    chat?.setVisible(false);
    if (ui.sideTab === 'journal') {
      const logs = store.logs.get(roomId);
      if (!logs) loadLogs(roomId).then(drawSide).catch(() => {});
      const slotOf = (uid) => room.members.find((m) => m.userId === uid)?.slot;
      body = h('div', { class: 'log' }, (logs || []).slice(-120).reverse().map((l) => h('div', { class: ['log-line', l.type] },
        h('time', null, clock(l.at)), h('span', { class: 'who-dot', style: { background: l.user ? colorFor(slotOf(l.user)) : 'var(--faint)' } }), h('span', null, l.text))));
    } else {
      const list = partners.length ? partners : room.members.filter((m) => m.userId === user?.id);
      if (ui.stream && !partners.some((m) => m.userId === ui.stream)) ui.stream = '';
      // with several partners: a list to watch one game, or all of them
      const picker = partners.length > 1 ? h('select', { class: 'input stream-pick', title: 'Direct affiché',
        onchange: (e) => {
          ui.stream = e.target.value;
          try { sessionStorage.setItem(`slt.stream.${roomId}`, ui.stream); } catch { /* storage unavailable */ }
          e.target.blur();
          drawSide();
        } },
      h('option', { value: '', selected: !ui.stream }, 'Tous les directs'),
      partners.map((m) => h('option', { value: m.userId, selected: ui.stream === m.userId }, `Direct de ${m.name}`))) : null;
      // the chosen game first, the others without their screen
      const ordered = ui.stream ? [...list.filter((m) => m.userId === ui.stream), ...list.filter((m) => m.userId !== ui.stream)] : list;
      body = h('div', null, picker, tradeBox(room, user?.id, { compact: true }),
        ordered.map((m) => partnerBlock(m, partners.length > 0 && showsScreen(m.userId))));
    }
    mount(side, tabs, h('div', { class: 'side-body' }, body));
  }

  /** The side panel shows this partner's game (every partner, or only the one picked in the list). */
  function showsScreen(userId) {
    return !ui.stream || ui.stream === userId;
  }

  function partnerBlock(m, withScreen) {
    const live = m.live || {};
    const img = images.get(m.userId);
    const screenEl = withScreen ? h('div', { class: ['screen', ds && 'ds'], title: 'Agrandir', onclick: () => { ui.big = m.userId; drawBig(); } },
      img?.url ? h('img', { src: img.url, alt: '' }) : h('span', null, m.online ? 'En attente de l’image…' : 'Hors ligne')) : null;
    return h('div', { class: 'partner', dataset: { partner: m.userId } }, screenEl,
      h('div', { class: 'info' }, avatar(m.name, m.slot, { size: 's', online: m.online, id: m.userId, pic: m.avatar }),
        h('div', { class: 'grow' }, h('b', null, m.name), h('div', { class: 'zone' },
          m.status === 'champion' ? '🏆 Maître vaincu' : live.zone ? `${live.zone}${live.battle ? ' · en combat' : ''}` : '—'))),
      live.team?.length ? h('div', { class: 'team-mini' }, live.team.map((p) => h('span', { class: p.dead || p.hp === 0 ? 'dead' : '', title: `${p.speciesName || ''} ${p.hp}/${p.maxHp} PV` },
        `${p.nickname || p.speciesName || '#' + p.species} ${p.level}`, h('i', { style: { width: `${p.maxHp ? Math.round(100 * p.hp / p.maxHp) : 0}%` } })))) : null);
  }

  const bigBox = h('div');
  stage.appendChild(bigBox);
  function drawBig() {
    const img = ui.big ? images.get(ui.big) : null;
    mount(bigBox, ui.big ? h('div', { class: 'big-view', onclick: () => { ui.big = null; drawBig(); } },
      img?.url ? h('img', { src: img.url, alt: '' }) : h('span', { class: 'muted' }, 'En attente de l’image…')) : null);
  }

  const fps = Math.max(1, Math.min(10, Number(cfg.liveFps) || 4));
  let polling = true;
  async function pollPartner(m) {
    while (polling) {
      const st = images.get(m.userId) || { seq: 0, url: null };
      const visible = ui.side && ui.sideTab === 'partners' && showsScreen(m.userId) || ui.big === m.userId;
      if (visible && m.online !== false) {
        try {
          const res = await fetch(`/api/live/${roomId}/${m.userId}?since=${st.seq}`, { headers: { 'X-SLT': '1' } });
          if (res.status === 200) {
            const blob = await res.blob();
            if (st.url) URL.revokeObjectURL(st.url);
            st.url = URL.createObjectURL(blob);
            st.seq = Number(res.headers.get('X-Seq')) || st.seq + 1;
            images.set(m.userId, st);
            // the picture of the game only: the photo of the player is an image too
            const el = side.querySelector(`[data-partner="${m.userId}"] .screen img`);
            if (el) el.src = st.url; else drawSide();
            if (ui.big === m.userId) drawBig();
          }
        } catch { /* network hiccup: next round */ }
      }
      await new Promise((r) => setTimeout(r, ui.big === m.userId ? 1000 / Math.max(fps, 5) : 1000 / fps));
    }
  }

  // ------------------------------------------------------------ wiring
  drawBar();
  drawVeil();
  drawSide();
  connect();
  for (const m of room.members) if (m.userId !== user?.id) pollPartner(m);

  const offs = [
    on('room', (r) => {
      if (r.id !== roomId) return;
      room = r;
      drawVeil();
      drawSide();
      drawBar();
    }),
    on('live', (d) => { if (d.roomId === roomId && ui.sideTab === 'partners') drawSide(); }),
    on('ghost', (g) => { if (g.roomId === roomId) ghosts.update(g); }),
    on('presence', (d) => { if (d.roomId === roomId) drawSide(); }),
    on('log', (l) => { if (l.roomId === roomId && ui.sideTab === 'journal') drawSide(); }),
    on('chat-list', () => { if (ui.sideTab !== 'chat') drawSide(); }),
    on('notice', (n) => {
      if (n.roomId && n.roomId !== roomId) return;
      if (n.local) return; // already shown by the game itself
      showOsd(n.text, n.level === 'danger' ? 'danger' : n.level === 'warn' ? 'warn' : n.level === 'ok' ? 'ok' : '');
    }),
    on('player', (p) => {
      if (p.roomId === roomId && p.state === 'stopped' && !ui.closed) { ui.closed = true; drawVeil(); }
    }),
  ];
  // the game pauses while the player looks at another screen of SLT
  const onVisibility = () => {
    if (store.state?.config?.pauseHidden === false) return;
    if (document.hidden && !ui.userPaused && screen.connected) { ui.autoPaused = true; screen.cmd('pause', { on: true }); }
    else if (!document.hidden && ui.autoPaused) { ui.autoPaused = false; screen.cmd('pause', { on: false }); }
  };
  document.addEventListener('visibilitychange', onVisibility);

  return () => {
    polling = false;
    chat?.destroy();
    ghosts.clear();
    offs.forEach((f) => f());
    document.removeEventListener('visibilitychange', onVisibility);
    resizeObserver.disconnect();
    if (screen.connected && !ui.userPaused) {
      // leaving the game screen: the game waits (resumed when coming back)
      screen.cmd('pause', { on: true });
      try { sessionStorage.setItem(`slt.autopause.${roomId}`, '1'); } catch { /* storage unavailable */ }
      toast('Jeu en pause. « Reprendre » dans la salle pour continuer.', 'info', { ms: 3500 });
    }
    screen.close();
    for (const st of images.values()) if (st.url) URL.revokeObjectURL(st.url);
  };
}

