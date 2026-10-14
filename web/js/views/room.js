// A room: lobby (players, ROM, ready, launch), running adventure (play, links,
// players live, journal, rules) and the summary once the Soul Link is over.

import {
  h, mount, icon, btn, avatar, chip, modal, act, toast, errorToast, confirmDialog, clock, dateTime, seg, colorFor,
} from '../ui.js';
import { local, srv, pollJob } from '../api.js';
import { store, on, loadRoom, loadLogs, refreshFriends, me } from '../store.js';
import { gameName, LANG } from '../data/games.js';
import { rulesEditor, randoEditor, randoLines } from './parts.js';
import { stateChip } from './home.js';
import { tradeBox, TRADES } from './trades.js';
import { chatPanel } from './chat.js';
import { battlesPanel } from './battle.js';

const ZONE_STATUS = {
  open: ['Ouverte', ''], waiting: ['En attente', 'accent'], naming: ['Nommage', 'accent'], linked: ['Liée', 'ok'],
  dead: ['Lien perdu', 'danger'], cancelled: ['Annulée', 'warn'],
};

export async function renderRoom(container, roomId, { history = false } = {}) {
  let room = store.rooms.get(roomId);
  if (!room || !room.zones) room = await loadRoom(roomId);
  let localState = null;
  let building = null; // progress message while this PC prepares the ROM
  let autoPrepared = false;
  const ui = { tab: room.state === 'lobby' ? null : 'links', logFilter: 'all' };
  // the room's chat and battles: one panel each, kept across redraws (messages, what is being typed)
  let chat = null;
  let duels = null;
  const duelsBox = () => {
    if (!duels) duels = battlesPanel(room);
    return duels.el;
  };
  const chatBox = () => {
    if (!chat) chat = chatPanel(`room-${roomId}`, { compact: true, placeholder: 'Écrire aux joueurs de la salle…' });
    chat.setVisible(true);
    return chat.el;
  };
  const page = h('div', { class: 'page' });
  container.appendChild(page);

  const user = me();
  const mine = () => room.members?.find((m) => m.userId === user?.id);
  const isLeader = () => room.leaderId === user?.id;
  const gen = () => Number(room.game?.gen || 3);

  async function refreshLocal() {
    try { localState = await local.get(`/api/rooms/${roomId}/local`); } catch { localState = null; }
  }

  // ------------------------------------------------------------ actions
  async function prepare(button) {
    await act(button, async () => {
      building = 'Préparation de la ROM…';
      draw();
      const j = await local.post(`/api/rooms/${roomId}/prepare`);
      try {
        await pollJob(j.job, (s) => { building = s.message; drawRomOnly(); });
        toast('Votre ROM est prête.', 'ok');
      } finally {
        building = null;
        await refreshLocal();
        draw();
      }
    });
  }

  function maybeAutoPrepare() {
    const m = mine();
    if (!m || autoPrepared || building || room.state === 'finished' || room.state === 'abandoned') return;
    const st = m.rom?.status;
    const missingHere = localState && (!localState.rom || !localState.rom.present);
    if ((room.state === 'lobby' && (st === 'missing' || !st)) || (room.state === 'running' && missingHere && st === 'ready')) {
      autoPrepared = true;
      prepare(null);
    }
  }

  async function setRules(change) {
    try { await srv.post(`/rooms/${roomId}/settings`, { rules: change }); } catch (e) { errorToast(e); }
  }

  function editRando() {
    const draft = JSON.parse(JSON.stringify(room.rando || {}));
    modal({
      title: 'Randomisation',
      wide: true,
      body: randoEditor(draft, gen(), () => {}),
      actions: [{ label: 'Annuler', onClick: (c) => c() }, { label: 'Enregistrer', kind: 'primary', onClick: async (close) => {
        try {
          await srv.post(`/rooms/${roomId}/settings`, { rando: draft });
          close();
          toast('Randomisation modifiée : chaque joueur doit régénérer sa ROM.', 'info');
          autoPrepared = false;
        } catch (e) { errorToast(e); }
      } }],
    });
  }

  async function invite() {
    await refreshFriends().catch(() => {});
    const friends = store.friends.friends.filter((f) => !room.members.some((m) => m.userId === f.id));
    const list = h('div', { class: 'list' }, friends.length ? friends.map((f) => {
      const invited = (room.invites || []).includes(f.id);
      const b = btn(invited ? 'Invité' : 'Inviter', (e) => act(e.currentTarget, async () => {
        await srv.post(`/rooms/${roomId}/invite`, { userId: f.id });
        e.currentTarget.textContent = 'Invité';
      }), { kind: 'small', disabled: invited });
      return h('div', { class: 'list-item' }, avatar(f.name, 2, { size: 's', online: f.online, id: f.id, pic: f.avatar }),
        h('div', { class: 'grow' }, h('b', null, f.name), h('div', { class: 'tiny muted' }, f.online ? 'En ligne' : 'Hors ligne')), b);
    }) : h('p', { class: 'muted' }, 'Aucun ami à inviter. Ajoutez des amis depuis la page « Amis ».'));
    modal({
      title: 'Inviter des joueurs',
      body: h('div', { class: 'stack l' }, list,
        h('div', { class: 'card tight' }, h('div', { class: 'row' }, h('div', { class: 'grow' },
          h('b', null, 'Ou partagez le code'), h('div', { class: 'tiny muted' }, 'À saisir dans « Rejoindre avec un code ».')),
        h('span', { class: 'mono', style: { fontSize: '20px', letterSpacing: '.18em' } }, room.code),
        btn('', () => copy(room.code), { iconName: 'copy', kind: 'ghost icon-only', title: 'Copier' })))),
    });
  }

  function copy(text) {
    navigator.clipboard?.writeText(text).then(() => toast('Copié', 'ok', { ms: 1500 }), () => {});
  }

  async function endRun() {
    modal({
      title: 'Terminer l’aventure',
      body: h('p', { class: 'muted' }, 'Le Soul Link s’arrête pour tout le monde et rejoint l’historique avec son bilan. La fin est détectée automatiquement quand le Maître de la Ligue est battu ; utilisez ceci si besoin.'),
      actions: [
        { label: 'Annuler', onClick: (c) => c() },
        { label: 'Abandonner', kind: 'danger', onClick: (c) => finish('abandon', c) },
        { label: 'Défaite', kind: 'danger', onClick: (c) => finish('defeat', c) },
        { label: 'Victoire', kind: 'primary', onClick: (c) => finish('victory', c) },
      ],
    });
    async function finish(outcome, close) {
      try { await srv.post(`/rooms/${roomId}/end`, { outcome }); close(); } catch (e) { errorToast(e); }
    }
  }

  // --------------------------------------------------------------- views
  function header() {
    const m = mine();
    return h('div', { class: 'room-head' },
      h('div', null,
        h('h1', null, room.name, stateChip(room), room.visibility === 'public' ? chip('Publique', 'accent') : null),
        h('div', { class: 'meta' },
          h('span', null, gameName(room.game?.gameId)),
          h('span', null, '·'), h('span', null, `${room.members.length}/${room.maxPlayers} joueur(s)`),
          room.code && room.state === 'lobby' ? [h('span', null, '·'),
            h('button', { class: 'chip', title: 'Copier le code', onclick: () => copy(room.code) }, icon('copy'), h('span', { class: 'mono' }, room.code))] : null)),
      h('div', { class: 'row' },
        room.state === 'lobby' && isLeader() ? btn(room.visibility === 'public' ? 'Rendre privée' : 'Rendre publique', (e) => act(e.currentTarget,
          () => srv.post(`/rooms/${roomId}/settings`, { visibility: room.visibility === 'public' ? 'private' : 'public' })),
        { kind: 'ghost', iconName: room.visibility === 'public' ? 'lock' : 'globe',
          title: room.visibility === 'public' ? 'Seuls vos invités (ou le code) pourront entrer' : 'La salle apparaît dans « Salles publiques » : n’importe qui peut la rejoindre tant qu’elle n’est pas lancée' }) : null,
        room.state === 'lobby' && isLeader() ? btn('Inviter', invite, { iconName: 'plus' }) : null,
        room.state === 'running' && isLeader() ? btn(room.adventureOpen ? 'Fermer l’aventure' : 'Ouvrir l’aventure', (e) => act(e.currentTarget,
          () => srv.post(`/rooms/${roomId}/adventure`, { open: !room.adventureOpen })), { iconName: room.adventureOpen ? 'lock' : 'unlock' }) : null,
        room.state === 'running' && isLeader() ? btn('Terminer…', endRun, { kind: 'ghost' }) : null,
        room.state === 'lobby' && m && !isLeader() ? btn('Quitter', (e) => act(e.currentTarget, async () => {
          await srv.post(`/rooms/${roomId}/leave`); store.rooms.delete(roomId); location.hash = '#/';
        }), { kind: 'ghost' }) : null,
        room.state !== 'running' && isLeader() ? btn('Supprimer', async (e) => {
          const b = e.currentTarget;
          if (await confirmDialog('Supprimer la salle', 'La salle, son journal et les sauvegardes du serveur seront supprimés.', { okLabel: 'Supprimer', danger: true })) {
            act(b, async () => { await srv.post(`/rooms/${roomId}/delete`); store.rooms.delete(roomId); location.hash = '#/'; });
          }
        }, { kind: 'ghost danger' }) : null));
  }

  function romStatusLine(m) {
    const st = m.rom?.status;
    if (st === 'ready') return chip('ROM prête', 'ok', true);
    if (st === 'building') return chip('ROM en préparation…', 'accent', true);
    if (st === 'error') return chip('ROM : erreur', 'danger', true);
    return chip('ROM manquante', 'warn', true);
  }

  function slots() {
    const cells = [];
    for (const m of [...room.members].sort((a, b) => a.slot - b.slot)) {
      cells.push(h('div', { class: ['slot', m.userId === user?.id && 'me'] },
        h('div', { class: 'who' }, avatar(m.name, m.slot, { online: m.online, id: m.userId, pic: m.avatar }),
          h('div', { class: 'grow' }, h('b', null, m.name, m.userId === user?.id ? ' (vous)' : ''),
            h('div', { class: 'tiny muted' }, m.leader ? 'Chef de salle' : 'Joueur')),
          m.leader ? icon('crown') : null,
          isLeader() && !m.leader && room.state === 'lobby' ? h('button', { class: 'btn ghost small icon-only', title: 'Retirer de la salle',
            onclick: async () => { if (await confirmDialog('Retirer ce joueur', `${m.name} quittera la salle.`)) srv.post(`/rooms/${roomId}/kick`, { userId: m.userId }).catch(errorToast); } }, icon('x')) : null),
        h('div', { class: 'lines' }, romStatusLine(m),
          m.ready ? chip('Prêt', 'ok', true) : chip('Pas encore prêt', '', true))));
    }
    for (let i = room.members.length; i < room.maxPlayers; i++) {
      cells.push(h('div', { class: 'slot empty' }, isLeader() ? btn('Inviter un joueur', invite, { iconName: 'plus', kind: 'ghost' }) : h('span', null, 'Place libre')));
    }
    return h('div', { class: 'slots' }, cells);
  }

  const romBox = h('div');
  function drawRomOnly() { mount(romBox, romCard()); }

  function romCard() {
    const m = mine();
    if (!m) return null;
    const r = localState?.rom;
    const here = r && r.present;
    let status;
    if (building) status = h('div', { class: 'stack s' }, h('div', { class: 'row' }, h('span', { class: 'spinner' }), h('span', null, building)), h('div', { class: 'progress' }, h('i')));
    else if (here && m.rom?.status === 'ready') status = h('p', { class: 'muted small' }, `Générée sur ce PC à partir de votre copie (${LANG[r.lang] || r.lang || '?'}) · CRC `, h('span', { class: 'mono' }, r.crc), r.notes?.length ? ` · ${r.notes.join(' · ')}` : '');
    else if (m.rom?.status === 'error' || m.rom?.status === 'missing') status = h('p', { class: 'warn small' }, m.rom?.error || 'ROM non générée.');
    else status = h('p', { class: 'muted small' }, 'ROM non générée sur ce PC.');
    return h('div', { class: 'card' },
      h('h3', null, 'Ma ROM'),
      h('div', { class: 'mt-s' }, status),
      h('div', { class: 'row wrap mt-s' },
        here ? h('a', { class: 'btn small', href: `/api/rooms/${roomId}/rom/file`, title: 'Télécharger une copie de la ROM de la salle' }, icon('download'), 'Télécharger') : null,
        here ? btn('Dossier', () => local.post(`/api/rooms/${roomId}/rom/reveal`).catch(errorToast), { kind: 'small ghost', iconName: 'folder' }) : null,
        !building && room.state !== 'finished' ? btn(here ? 'Régénérer' : 'Générer ma ROM', (e) => prepare(e.currentTarget), { kind: here ? 'small ghost' : 'small primary', iconName: 'refresh' }) : null),
      room.state === 'running' && !here ? h('p', { class: 'tiny faint mt-s' }, 'Nouvel ordinateur ? Régénérez la ROM (identique grâce à la seed), puis récupérez votre sauvegarde depuis le serveur.') : null,
      room.state === 'running' ? h('div', { class: 'row mt-s' }, btn('Récupérer ma sauvegarde du serveur', async (e) => {
        const b = e.currentTarget;
        if (await confirmDialog('Restaurer la sauvegarde', 'La sauvegarde de ce PC sera remplacée par la dernière copie envoyée au serveur (une copie de l’actuelle est gardée).')) {
          act(b, async () => { await local.post(`/api/rooms/${roomId}/save/restore`); toast('Sauvegarde restaurée.', 'ok'); });
        }
      }, { kind: 'small ghost', iconName: 'save' })) : null);
  }

  function lobby() {
    const m = mine();
    const notReady = room.members.filter((x) => !x.ready).map((x) => x.name);
    const canLaunch = isLeader() && notReady.length === 0;
    return h('div', null,
      slots(),
      h('div', { class: 'grid2 mt-l' },
        h('div', { class: 'stack' }, (drawRomOnly(), romBox),
          h('div', { class: 'card' }, h('div', { class: 'row between' }, h('h3', null, 'Randomisation'),
            isLeader() ? btn('Modifier', editRando, { kind: 'small ghost', iconName: 'edit' }) : null),
          h('ul', { class: 'muted small', style: { margin: '10px 0 0', paddingLeft: '18px' } }, randoLines(room.rando, gen()).map((l) => h('li', null, l))),
          h('p', { class: 'tiny faint mt-s' }, 'Seed ', h('span', { class: 'mono' }, room.rando?.seed || '—')))),
        h('div', { class: 'card' }, h('h3', null, 'Règles'), h('p', { class: 'tiny faint mt-s' }, isLeader() ? 'Modifiables à tout moment, même en cours de partie.' : 'Fixées par le chef de salle.'),
          rulesEditor(room.rules, { editable: isLeader(), onChange: setRules }))),
      m ? h('div', { class: 'card mt chat-card' }, h('h3', { class: 'mb' }, 'Discussion de la salle'), chatBox()) : null,
      m ? h('div', { class: 'action-bar' },
        h('div', { class: 'grow' },
          h('b', null, m.ready ? 'Vous êtes prêt.' : 'Quand votre ROM est prête, indiquez que vous êtes prêt.'),
          h('div', { class: 'tiny muted' }, notReady.length ? `En attente : ${notReady.join(', ')}` : 'Tout le monde est prêt.')),
        btn(m.ready ? 'Je ne suis plus prêt' : 'Je suis prêt', (e) => act(e.currentTarget,
          () => srv.post(`/rooms/${roomId}/ready`, { ready: !m.ready })), { kind: m.ready ? '' : 'primary', disabled: !m.ready && m.rom?.status !== 'ready' }),
        isLeader() ? btn('Lancer l’aventure', (e) => act(e.currentTarget, () => srv.post(`/rooms/${roomId}/launch`)),
          { kind: 'primary', iconName: 'play', disabled: !canLaunch, title: canLaunch ? null : 'Tous les joueurs doivent être prêts' }) : null) : null);
  }

  /** A public room seen by someone who is not in it: what it is, and a button to join. */
  function visitorView() {
    const full = room.members.length >= room.maxPlayers;
    return h('div', null,
      h('div', { class: 'banner' }, icon('globe'), h('div', { class: 'grow' },
        h('b', null, 'Salle publique. '), `Chef de salle : ${room.leaderName || '?'}. `, full ? 'Elle est complète pour l’instant.' : 'Rejoignez-la pour préparer votre ROM avec les autres.'),
      btn('Rejoindre la salle', (e) => act(e.currentTarget, async () => {
        room = await srv.post(`/rooms/${roomId}/join`); store.rooms.set(roomId, room); draw(); maybeAutoPrepare();
      }), { kind: 'primary', disabled: full })),
      slots(),
      h('div', { class: 'grid2 mt-l' },
        h('div', { class: 'card' }, h('h3', null, 'Randomisation'),
          h('ul', { class: 'muted small', style: { margin: '10px 0 0', paddingLeft: '18px' } }, randoLines(room.rando || {}, gen()).map((l) => h('li', null, l)))),
        h('div', { class: 'card' }, h('h3', null, 'Règles'), rulesEditor(room.rules || {}, { editable: false, onChange: () => {} }))));
  }

  function invitedView() {
    return h('div', { class: 'banner' }, icon('info'), h('div', { class: 'grow' }, 'Vous êtes invité dans cette salle.'),
      btn('Refuser', (e) => act(e.currentTarget, async () => { await srv.post(`/rooms/${roomId}/decline`); location.hash = '#/'; }), { kind: 'small ghost' }),
      btn('Rejoindre', (e) => act(e.currentTarget, async () => { room = await srv.post(`/rooms/${roomId}/join`); store.rooms.set(roomId, room); draw(); }), { kind: 'small primary' }));
  }

  function playCard() {
    const m = mine();
    const here = localState?.rom?.present;
    const playing = store.state?.player?.roomId === roomId && store.state?.player?.state === 'running';
    const naming = room.naming;
    return h('div', { class: 'card' },
      naming ? h('div', { class: 'banner' }, icon('edit'), h('div', null, isLeader()
        ? `Tous les joueurs ont capturé leur Pokémon de ${naming.zoneName} : choisissez le nom du lien.`
        : `Le chef de salle choisit le nom du lien de ${naming.zoneName}. Les jeux reprennent ensuite.`)) : null,
      !room.adventureOpen ? h('div', { class: 'banner warn' }, icon('lock'), h('div', null, 'L’aventure est fermée par le chef de salle : les jeux sont en pause.')) : null,
      TRADES && (room.trades || []).some((t) => t.state === 'offered' && t.to === user?.id)
        ? h('div', { class: 'banner' }, icon('info'), h('div', { class: 'grow' }, 'Un partenaire vous propose un échange.'),
          btn('Voir', () => { ui.tab = 'players'; draw(); }, { kind: 'small' })) : null,
      !room.linkStarted ? h('div', { class: 'banner' }, icon('info'), h('div', null,
        'Le Soul Link commence vraiment quand chaque joueur a une Poké Ball : d’ici là, un Pokémon K.O. ne meurt pas. ',
        h('b', null, 'Attend : '), room.members.filter((m) => !m.hasBalls).map((m) => m.name).join(', ') || '…')) : null,
      h('div', { class: 'row' },
        h('div', { class: 'grow' }, h('h2', null, playing ? 'Votre partie est en cours' : 'Prêt à jouer ?'),
          h('p', { class: 'muted small mt-s' }, here ? 'Le jeu s’ouvre dans cette fenêtre. Vos partenaires vous voient en direct.' : 'Générez d’abord votre ROM (section « Ma ROM »).')),
        m ? btn(playing ? 'Reprendre' : 'Jouer', (e) => act(e.currentTarget, async () => {
          await local.post(`/api/rooms/${roomId}/play`);
          location.hash = `#/jouer/${roomId}`;
        }), { kind: 'primary big', iconName: 'play', disabled: !here }) : null));
  }

  function zonesTab() {
    const zones = room.zones || [];
    if (!zones.length) return h('div', { class: 'empty' }, h('b', null, 'Aucune zone pour l’instant'), 'Les zones apparaissent dès la première rencontre.');
    const members = [...room.members].sort((a, b) => a.slot - b.slot);
    return h('div', { class: 'card', style: { padding: '6px 8px', overflowX: 'auto' } }, h('table', { class: 'table zone-table' },
      h('thead', null, h('tr', null, h('th', null, 'Zone'), h('th', null, 'État'), members.map((m) => h('th', null, m.name)), h('th', null, 'Surnom'))),
      h('tbody', null, [...zones].reverse().map((z) => {
        const [label, kind] = ZONE_STATUS[z.status] || [z.status, ''];
        // a duplicate or a shiny caught there does not count: the zone waits for a real capture
        const missing = z.status === 'waiting' ? members.filter((m) => !z.entries[m.userId]).map((m) => m.name) : [];
        return h('tr', null, h('td', null, h('b', null, z.name)),
          h('td', null, chip(label, kind), missing.length ? h('div', { class: 'tiny faint mt-s' }, `Attend : ${missing.join(', ')}`) : null),
          members.map((m) => h('td', { class: 'mon' }, monCell(m, z.entries[m.userId]))),
          h('td', null, z.nickname ? h('b', null, z.nickname) : h('span', { class: 'faint' }, '—')));
      }))));
  }

  function monCell(m, e) {
    if (!e) return h('span', { class: 'faint' }, '—');
    if (e.result === 'killed') return h('div', { class: 'mon-cell released' }, h('b', null, e.speciesName || `#${e.species}`), h('small', null, 'mis K.O.'));
    const rec = (m.mons || []).find((x) => x.uid === e.uid);
    const st = rec?.status || 'alive';
    return h('div', { class: ['mon-cell', st] }, h('b', null, (e.speciesName || `#${e.species}`) + (e.shiny ? ' ✨' : '')),
      h('small', null, `N.${rec?.level || e.level}${st === 'dead' ? ' · mort' : st === 'released' ? ' · relâché' : ''}`));
  }

  function playersTab() {
    return h('div', null, tradeBox(room, user?.id), playersGrid());
  }

  function playersGrid() {
    return h('div', { class: 'stats-grid' }, [...room.members].sort((a, b) => a.slot - b.slot).map((m) => {
      const live = m.live || {};
      const team = live.team || [];
      const s = m.stats || {};
      return h('div', { class: 'card' },
        h('div', { class: 'row' }, avatar(m.name, m.slot, { online: m.online, id: m.userId, pic: m.avatar }),
          h('div', { class: 'grow' }, h('b', null, m.name), h('div', { class: 'tiny muted' },
            m.status === 'champion' ? '🏆 Maître vaincu' : live.zone ? `À ${live.zone}${live.battle ? ' · en combat' : ''}` : m.online ? 'En ligne' : 'Hors ligne'))),
        h('div', { class: 'stat-nums' },
          h('div', null, h('b', null, s.captures || 0), h('small', null, 'captures')),
          h('div', null, h('b', null, s.deaths || 0), h('small', null, 'morts')),
          h('div', null, h('b', null, s.badges || 0), h('small', null, 'badges')),
          h('div', null, h('b', null, s.shinies || 0), h('small', null, 'chromatiques'))),
        team.length ? h('div', { class: 'list' }, team.map((p) => h('div', { class: 'list-item', style: { padding: '6px 2px' } },
          h('div', { class: 'grow' }, h('b', { class: p.dead ? 'faint' : '' }, p.nickname || p.speciesName || `#${p.species}`),
            h('div', { class: 'tiny muted' }, `${p.speciesName || ''} N.${p.level}`)),
          h('span', { class: ['tiny', p.hp === 0 ? 'danger' : 'muted'] }, `${p.hp}/${p.maxHp} PV`))))
          : h('p', { class: 'tiny faint' }, 'Équipe visible quand le joueur est en jeu.'));
    }));
  }

  const logBox = h('div');
  const LOG_FILTERS = {
    all: () => true,
    captures: (l) => ['capture', 'encounter', 'link', 'name', 'release', 'existing'].includes(l.type),
    deaths: (l) => ['death', 'kill', 'cancel', 'whiteout'].includes(l.type),
    items: (l) => ['item', 'buy', 'sell', 'obtain', 'refund'].includes(l.type),
    battles: (l) => ['battle', 'win', 'loss', 'badge', 'champion'].includes(l.type),
  };
  function drawLogs() {
    const logs = store.logs.get(roomId);
    if (!logs) {
      mount(logBox, h('div', { class: 'row muted' }, h('span', { class: 'spinner' }), 'Chargement du journal…'));
      loadLogs(roomId).then(drawLogs).catch(errorToast);
      return;
    }
    const slotOf = (uid) => room.members.find((m) => m.userId === uid)?.slot;
    const list = logs.filter(LOG_FILTERS[ui.logFilter]).slice(-800).reverse();
    mount(logBox,
      h('div', { class: 'row between mb' }, seg([['all', 'Tout'], ['captures', 'Captures'], ['deaths', 'Morts'], ['items', 'Objets'], ['battles', 'Combats']],
        ui.logFilter, (v) => { ui.logFilter = v; drawLogs(); }), h('span', { class: 'tiny faint' }, `${logs.length} évènement(s)`)),
      list.length ? h('div', { class: 'card', style: { padding: '4px 14px' } }, h('div', { class: 'log' }, list.map((l) => h('div', { class: ['log-line', l.type] },
        h('time', { title: dateTime(l.at) }, clock(l.at)),
        h('span', { class: 'who-dot', style: { background: l.user ? colorFor(slotOf(l.user)) : 'var(--faint)' } }),
        h('span', null, l.text)))))
        : h('div', { class: 'empty' }, 'Rien pour l’instant.'));
  }

  function summary() {
    const ended = room.ended || {};
    const sum = ended.summary || [];
    const icons = { victory: '🏆', defeat: '☠', abandon: '⏹' };
    const titles = { victory: 'Soul Link réussi', defeat: 'Soul Link perdu', abandon: 'Aventure arrêtée' };
    return h('div', null,
      h('div', { class: ['outcome', ended.outcome] }, h('span', { class: 'big-icon' }, icons[ended.outcome] || '•'),
        h('div', null, h('h2', null, titles[ended.outcome] || 'Terminé'), h('p', { class: 'muted small' }, `${ended.reason || ''} ${ended.at ? '· ' + dateTime(ended.at) : ''}`))),
      h('div', { class: 'stats-grid' }, sum.map((s) => {
        const m = room.members.find((x) => x.userId === s.userId);
        const items = (obj) => {
          const entries = Object.entries(obj || {}).sort((a, b) => b[1] - a[1]);
          return entries.length ? h('div', { class: 'item-list' }, entries.map(([k, v]) => chip(`${v}× ${k}`))) : h('span', { class: 'tiny faint' }, 'Aucun');
        };
        return h('div', { class: 'card' },
          h('div', { class: 'row' }, avatar(s.name, m?.slot, { id: s.userId, pic: m?.avatar }), h('div', { class: 'grow' }, h('b', null, s.name),
            h('div', { class: 'tiny muted' }, s.status === 'champion' ? '🏆 Maître vaincu' : s.status === 'wiped' ? 'Équipe tombée' : `${s.alive || 0} Pokémon encore en vie`))),
          h('div', { class: 'stat-nums' },
            h('div', null, h('b', null, s.captures || 0), h('small', null, 'capturés')),
            h('div', null, h('b', null, s.deaths || 0), h('small', null, 'morts')),
            h('div', null, h('b', null, s.wildKills || 0), h('small', null, 'K.O. sauvages')),
            h('div', null, h('b', null, s.shinies || 0), h('small', null, 'chromatiques'))),
          h('div', { class: 'stack s' },
            h('div', { class: 'tiny muted' }, 'Objets utilisés en combat'), items(s.itemsBattle),
            h('div', { class: 'tiny muted mt-s' }, 'Objets utilisés hors combat'), items(s.itemsField),
            h('div', { class: 'tiny muted mt-s' }, 'Achats'), items(s.bought)));
      })));
  }

  function tabs(list) {
    if (!ui.tab || !list.some(([id]) => id === ui.tab)) ui.tab = list[0][0];
    if (ui.tab !== 'chat') chat?.setVisible(false);
    let content;
    switch (ui.tab) {
      case 'players': content = playersTab(); break;
      case 'journal': drawLogs(); content = logBox; break;
      case 'rules': content = h('div', { class: 'card' }, rulesEditor(room.rules, { editable: isLeader() && room.state === 'running', onChange: setRules })); break;
      case 'rom': content = (drawRomOnly(), romBox); break;
      case 'chat': content = h('div', { class: 'card chat-card' }, chatBox()); break;
      case 'battles': content = duelsBox(); break;
      default: content = zonesTab(); break;
    }
    return h('div', { class: 'mt-l' },
      h('div', { class: 'tabs' }, list.map(([id, label]) => h('button', { class: ui.tab === id ? 'active' : '', onclick: () => { ui.tab = id; draw(); } }, label))),
      content);
  }

  function draw() {
    const m = mine();
    let body;
    if (room.state === 'lobby') {
      body = m ? lobby() : room.visitor ? visitorView() : invitedView();
    } else if (room.state === 'running') {
      body = h('div', null, playCard(), tabs([['links', 'Liens'], ['players', 'Joueurs'], ['battles', 'Combats'], ['chat', 'Discussion'], ['journal', 'Journal'], ['rules', 'Règles'], ['rom', 'Ma ROM']]));
    } else {
      body = h('div', null, summary(), tabs([['links', 'Liens'], ['chat', 'Discussion'], ['journal', 'Journal'], ['rules', 'Règles']]));
    }
    mount(page, history ? h('a', { href: '#/historique', class: 'small muted' }, '← Historique') : null, header(), body);
  }

  await refreshLocal();
  draw();
  maybeAutoPrepare();

  const offs = [
    on('room', (r) => { if (r.id === roomId) { room = r; draw(); maybeAutoPrepare(); } }),
    on('presence', (d) => { if (d.roomId === roomId) draw(); }),
    on('live', (d) => { if (d.roomId === roomId && ui.tab === 'players') draw(); }),
    on('log', (l) => { if (l.roomId === roomId && ui.tab === 'journal') drawLogs(); }),
    on('player', () => { if (room.state === 'running') draw(); }),
    on('rom', (r) => { if (r.roomId === roomId && r.state === 'building' && r.message && !building) { building = r.message; drawRomOnly(); } }),
  ];
  return () => { offs.forEach((f) => f()); chat?.destroy(); duels?.destroy(); };
}

