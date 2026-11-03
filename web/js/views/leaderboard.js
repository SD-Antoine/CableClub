// The leaderboards of a mode (point 3): the players of this server who chose to appear in them, by wins, then fewest
// deaths a run, then the best survival; for one official mode, every game or one, every size of room or one. Only
// the runs the server recorded count. Nothing from the games is shown here (no screen, no sprite).

import { h, mount, avatar, seg, chip, btn, act, toast } from '../ui.js';
import { srv, local } from '../api.js';
import { me, refreshState } from '../store.js';
import { GAMES, gameName } from '../data/games.js';
import { modesOfFamily, playersOf } from '../data/modes.js';
import { modeAvailable } from '../data/rules.js';
import { mobile } from '../platform.js';
import { openReport } from './moderation.js';

export function renderLeaderboard(container, { mode = 'soullink' } = {}) {
  const page = h('div');
  container.appendChild(page);
  const modes = modesOfFamily(mode);
  const st = { mode: modes.find((s) => s.id === mode)?.id || modes[0]?.id || mode, game: '', players: 0, rows: null, error: null, ranked: !!me()?.ranked, pending: 0 };
  const filters = h('div');
  const results = h('div');
  mount(page, filters, results);
  let alive = true;

  function drawFilters() {
    const { min, max } = playersOf(st.mode);
    const sizes = [[0, 'Tous']];
    for (let n = min; n <= max; n++) sizes.push([n, n === 1 ? 'Solo' : String(n)]);
    const games = Object.entries(GAMES).filter(([id, g]) => modeAvailable(st.mode, g.gen, id));
    mount(filters, h('div', { class: 'card tight mb stack' },
      modes.length > 1 ? seg(modes.map((s) => [s.id, s.name]), st.mode, (v) => { st.mode = v; st.players = 0; drawFilters(); load(); }) : null,
      h('div', { class: 'row wrap' },
        h('select', { class: 'input', style: { width: mobile ? '100%' : '220px' }, onchange: (e) => { st.game = e.target.value; load(); } },
          h('option', { value: '', selected: !st.game }, 'Tous les jeux'),
          games.map(([id]) => h('option', { value: id, selected: st.game === id }, gameName(id)))),
        h('span', { class: 'small muted' }, 'Joueurs'),
        seg(sizes, st.players, (v) => { st.players = v; drawFilters(); load(); }))));
  }

  async function load() {
    try {
      const r = await srv.get(`/leaderboard?mode=${encodeURIComponent(st.mode)}&game=${encodeURIComponent(st.game)}&players=${st.players}`);
      st.rows = r.rows || [];
      st.ranked = !!r.ranked;
      st.pending = Number(r.pending) || 0;
      st.error = null;
    } catch (e) { st.error = e.message; }
    if (alive) draw();
  }

  function draw() {
    const optIn = st.ranked ? null : h('div', { class: 'banner mb' }, h('div', { class: 'grow small' },
      h('b', null, 'Vous n’apparaissez pas dans les classements. '),
      'Personne n’y figure sans l’avoir demandé : votre pseudo et vos résultats deviendraient visibles par les joueurs de ce serveur.'),
    btn('Y apparaître', (e) => act(e.currentTarget, async () => {
      await local.post('/api/me/profile', { ranked: true });
      await refreshState();
      toast('Vous apparaissez dans les classements (retrait possible dans Mon profil).', 'ok');
      load();
    }), { kind: 'small primary' }));
    if (st.error) { mount(results, optIn, h('div', { class: 'empty' }, st.error)); return; }
    if (!st.rows) { mount(results, h('p', { class: 'muted small' }, 'Chargement…')); return; }
    // the player's runs the checks flagged: waiting for a moderator (nothing said of why)
    const pending = st.pending ? h('div', { class: 'banner mb' }, h('div', { class: 'grow small' },
      h('b', null, `${st.pending} de vos parties ${st.pending > 1 ? 'sont' : 'est'} en cours de vérification. `),
      'Elle' + (st.pending > 1 ? 's apparaîtront' : ' apparaîtra') + ' dans ce classement une fois vérifiée' + (st.pending > 1 ? 's.' : '.'))) : null;
    mount(results, optIn, pending,
      st.rows.length ? h('div', { class: 'card', style: { padding: '4px 14px' } }, h('div', { class: 'list' }, st.rows.map(row)))
        : h('div', { class: 'empty' }, h('b', null, 'Personne pour l’instant'), 'Les parties terminées des joueurs qui ont choisi d’apparaître ici seront classées.'),
      h('p', { class: 'tiny faint mt' }, 'Classés par victoires, puis le moins de morts par partie, puis la meilleure survie (Pokémon encore en vie à la fin, pour chaque capture). Seules les parties terminées et vérifiées sur ce serveur comptent ; une partie arrêtée avant la fin ne compte pas.'));
  }

  function row(r) {
    const u = r.user || {};
    const perRun = r.runs ? (r.deaths / r.runs).toFixed(1).replace('.', ',') : '0';
    return h('div', { class: ['list-item', 'rank-row', r.me && 'me'] },
      h('span', { class: 'rank' }, r.rank <= 3 ? ['🥇', '🥈', '🥉'][r.rank - 1] : `${r.rank}.`),
      avatar(u.name, 1, { size: 's', id: u.id, pic: u.avatar }),
      h('div', { class: 'grow' }, h('b', null, u.name, r.me ? ' (vous)' : ''),
        h('div', { class: 'tiny muted' }, `${r.runs} partie${r.runs > 1 ? 's' : ''} · ${perRun} mort${r.deaths / Math.max(1, r.runs) >= 2 ? 's' : ''} par partie${r.survival == null ? '' : ` · ${r.survival} % de survie`}`)),
      chip(`${r.wins} victoire${r.wins > 1 ? 's' : ''}`, r.wins ? 'ok' : ''),
      r.me ? null : btn('', () => openReport({ userId: u.id, name: u.name, leaderboard: st.mode }), { kind: 'small ghost icon-only', iconName: 'flag', title: 'Signaler ces résultats' }));
  }

  drawFilters();
  draw();
  load();
  return () => { alive = false; };
}
