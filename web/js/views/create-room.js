// New room: game, players and name → rules → randomization.

import { h, mount, keepScroll, modal, btn, field, seg, icon, errorToast, switchEl } from '../ui.js';
import { local, srv } from '../api.js';
import { store } from '../store.js';
import { GAMES, LANG, GEN_SHORT, support, gameName } from '../data/games.js';
import { ruleDefaults, playerChoices, MODE_PLAYERS, modeAvailable, modeName, MODE_MIN_GEN } from '../data/rules.js';
import { defaultRando } from '../data/rando.js';
import { rulesEditor, randoEditor } from './parts.js';

export async function openCreateRoom(mode = 'soullink') {
  let library;
  try {
    // the games this mode can be played with (the Shinylocke needs shinies: not the first generation)
    library = (await local.get('/api/library')).roms.filter((r) => r.present && modeAvailable(mode, r.gen));
  } catch (e) { errorToast(e); return; }

  const user = store.state?.link?.user;
  const st = {
    step: 0,
    romId: library[0]?.id || null,
    name: '',
    maxPlayers: MODE_PLAYERS[mode]?.min === 1 ? 1 : 2,
    visibility: 'private',
    rules: { ...ruleDefaults(mode) },
    rando: null,
  };
  const body = h('div');
  const foot = h('div', { class: 'row', style: { width: '100%' } });
  const m = modal({ title: `Nouvelle salle ${modeName(mode)}`, body: h('div', null, body), wide: true, actions: [foot] });

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
      rulesEditor(st.rules, { editable: true, mode, gen: gen(), onChange: (c) => { Object.assign(st.rules, c); draw(); } }));
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
      return h('div', { class: 'empty' }, h('b', null, MODE_MIN_GEN[mode] ? `Aucun jeu compatible dans votre bibliothèque` : 'Aucune ROM dans votre bibliothèque'),
        h('p', null, MODE_MIN_GEN[mode] ? `Le ${modeName(mode)} se joue avec les jeux de la ${MODE_MIN_GEN[mode]}e génération et plus : ajoutez-en un dans « Mes ROMs ».`
          : 'Ajoutez d’abord les ROMs de vos jeux (copies de vos cartouches).'),
        h('div', { class: 'mt' }, btn('Ouvrir « Mes ROMs »', () => { m.close(); location.hash = '#/roms'; }, { kind: 'primary' })));
    }
    // the generation first, then one line per game of it (the file name only tells two copies of a game apart)
    const gens = [...new Set(library.map((r) => Number(r.gen)))].sort();
    const order = Object.keys(GAMES);
    const roms = library.filter((r) => Number(r.gen) === gen())
      .sort((a, b) => order.indexOf(a.gameId) - order.indexOf(b.gameId) || String(a.lang).localeCompare(String(b.lang)));
    const twin = (r) => roms.some((x) => x !== r && x.gameId === r.gameId && x.lang === r.lang);
    // the end of the file name is what tells two copies apart (« …Enhanced)_apfix.nds »)
    const tail = (file) => (file.length > 32 ? `…${file.slice(-31)}` : file);
    const pick = (r) => { st.romId = r.id; st.rando = null; draw(); };
    const name = h('input', { class: 'input', placeholder: `${user?.name || 'Ma'} salle`, value: st.name, oninput: (e) => { st.name = e.target.value; } });
    return h('div', { class: 'stack l' },
      h('div', { class: 'stack' },
        gens.length > 1 ? field('Génération', seg(gens.map((g) => [g, GEN_SHORT[g]]), gen(),
          (g) => pick(library.find((r) => Number(r.gen) === g)))) : null,
        h('div', { class: 'pick-list' }, roms.map((r) => h('button', { type: 'button', class: ['pick', st.romId === r.id && 'selected'], onclick: () => pick(r) },
          h('span', { class: 'pick-dot' }),
          h('span', { class: 'grow' }, h('b', null, gameName(r.gameId)), twin(r) ? h('small', { title: r.file }, tail(r.file)) : null),
          h('span', { class: 'chip' }, LANG[r.lang] || r.lang))))),
      rom() ? h('p', { class: 'tiny faint' }, icon('info'), ' ', support(gen(), mode)) : null,
      h('div', { class: 'grid2' },
        field('Nom de la salle', name),
        field('Joueurs', seg(playerChoices(mode), st.maxPlayers, (v) => { st.maxPlayers = v; if (v < 2) st.visibility = 'private'; draw(); }))),
      h('div', { class: ['rule-row', st.maxPlayers < 2 && 'soon'] }, h('div', { class: 'grow' }, h('b', null, 'Salle publique'),
        h('small', null, st.maxPlayers < 2 ? 'Une salle solo ne peut pas être publique : personne ne pourrait la rejoindre.'
          : 'Elle apparaît dans « Salles publiques » : n’importe quel joueur du serveur peut la trouver et la rejoindre tant que l’aventure n’est pas lancée. Sinon, seuls vos invités (ou ceux qui ont le code) entrent.')),
      switchEl(st.visibility === 'public' && st.maxPlayers >= 2, (v) => { st.visibility = v ? 'public' : 'private'; }, { disabled: st.maxPlayers < 2 })));
  }

  async function create(button) {
    const r = rom();
    button.disabled = true;
    try {
      const room = await srv.post('/rooms', {
        name: st.name.trim() || `${user?.name || 'Ma'} salle`,
        game: { gameId: r.gameId, name: gameName(r.gameId), gen: Number(r.gen), platform: r.platform, romCode: r.romCode, lang: r.lang, crc: r.crc },
        maxPlayers: st.maxPlayers,
        visibility: st.visibility,
        mode,
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

