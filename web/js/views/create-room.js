// New room: game, players and name → rules → randomization.

import { h, mount, keepScroll, modal, btn, field, seg, icon, errorToast } from '../ui.js';
import { local, srv } from '../api.js';
import { store } from '../store.js';
import { LANG, GEN_LABEL, SUPPORT, gameName } from '../data/games.js';
import { RULE_DEFAULTS } from '../data/rules.js';
import { defaultRando } from '../data/rando.js';
import { rulesEditor, randoEditor } from './parts.js';

export async function openCreateRoom() {
  let library;
  try {
    library = (await local.get('/api/library')).roms.filter((r) => r.present);
  } catch (e) { errorToast(e); return; }

  const user = store.state?.link?.user;
  const st = {
    step: 0,
    romId: library[0]?.id || null,
    name: '',
    maxPlayers: 2,
    rules: { ...RULE_DEFAULTS },
    rando: null,
  };
  const body = h('div');
  const foot = h('div', { class: 'row', style: { width: '100%' } });
  const m = modal({ title: 'Nouvelle salle', body: h('div', null, body), wide: true, actions: [foot] });

  const rom = () => library.find((r) => r.id === st.romId);
  const gen = () => Number(rom()?.gen || 3);

  let drawnStep = -1;
  function draw() {
    // a redraw within the same step keeps the scroll position (switches, options)
    const restore = drawnStep === st.step ? keepScroll(body) : () => {};
    drawnStep = st.step;
    const steps = ['Jeu', 'Règles', 'Randomisation'];
    const head = h('div', { class: 'row mb' }, steps.map((s, i) => h('span', { class: ['chip', i === st.step && 'accent'] }, `${i + 1}. ${s}`)));
    let content;
    if (st.step === 0) content = gameStep();
    else if (st.step === 1) content = h('div', null, h('p', { class: 'muted mb' }, 'Vous pourrez les ajuster à tout moment, même en cours de partie.'),
      rulesEditor(st.rules, { editable: true, onChange: (c) => { Object.assign(st.rules, c); draw(); } }));
    else {
      if (!st.rando) st.rando = defaultRando(gen());
      content = randoEditor(st.rando, gen(), () => {});
    }
    mount(body, head, content);
    restore();
    requestAnimationFrame(restore);
    mount(foot,
      st.step > 0 ? btn('Retour', () => { st.step--; draw(); }) : h('span'),
      h('span', { class: 'grow' }),
      st.step < 2 ? btn('Continuer', () => { if (st.step === 0 && !rom()) return; st.step++; draw(); }, { kind: 'primary', disabled: !rom() })
        : btn('Créer la salle', (e) => create(e.currentTarget), { kind: 'primary', iconName: 'check' }));
  }

  function gameStep() {
    if (!library.length) {
      return h('div', { class: 'empty' }, h('b', null, 'Aucune ROM dans votre bibliothèque'),
        h('p', null, 'Ajoutez d’abord les ROMs de vos jeux (copies de vos cartouches).'),
        h('div', { class: 'mt' }, btn('Ouvrir « Mes ROMs »', () => { m.close(); location.hash = '#/roms'; }, { kind: 'primary' })));
    }
    const byGen = {};
    for (const r of library) (byGen[r.gen] ||= []).push(r);
    const name = h('input', { class: 'input', placeholder: `${user?.name || 'Ma'} salle`, value: st.name, oninput: (e) => { st.name = e.target.value; } });
    return h('div', { class: 'stack l' },
      h('div', { class: 'stack' }, Object.keys(byGen).sort().map((g) => h('div', null,
        h('div', { class: 'tiny faint mb', style: { marginBottom: '6px' } }, GEN_LABEL[g]),
        h('div', { class: 'grid3' }, byGen[g].map((r) => h('button', { type: 'button', class: ['choice', st.romId === r.id && 'selected'],
          onclick: () => { st.romId = r.id; st.rando = null; draw(); } },
        h('div', { class: 'grow' }, h('b', null, gameName(r.gameId)), h('small', null, `${LANG[r.lang] || r.lang} · ${r.file}`)))))))),
      rom() ? h('p', { class: 'tiny faint' }, icon('info'), ' ', SUPPORT[gen()]) : null,
      h('div', { class: 'grid2' },
        field('Nom de la salle', name),
        field('Joueurs', seg([[1, '1'], [2, '2'], [3, '3'], [4, '4']], st.maxPlayers, (v) => { st.maxPlayers = v; draw(); }))));
  }

  async function create(button) {
    const r = rom();
    button.disabled = true;
    try {
      const room = await srv.post('/rooms', {
        name: st.name.trim() || `${user?.name || 'Ma'} salle`,
        game: { gameId: r.gameId, name: gameName(r.gameId), gen: Number(r.gen), platform: r.platform, romCode: r.romCode, lang: r.lang, crc: r.crc },
        maxPlayers: st.maxPlayers,
        rules: st.rules,
        rando: { ...st.rando, seed: st.rando.seed || undefined },
      });
      store.rooms.set(room.id, room);
      m.close();
      location.hash = `#/salle/${room.id}`; // the room page prepares the leader's ROM right away
    } catch (e) {
      errorToast(e);
      button.disabled = false;
    }
  }

  draw();
}

