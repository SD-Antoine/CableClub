// Battles between the players of a room, simulated right here with Pokémon
// Showdown's engine (web/js/vendor/pkmn-sim.js, loaded the first time). The
// server only orders the choices: each screen replays them on the same teams
// with the same seed, so everybody sees the same battle. Nothing changes in
// the games (the teams are copies of the players' parties).

import { h, mount, btn, icon, modal, toast, errorToast, seg, confirmDialog } from '../ui.js';
import { local, srv } from '../api.js';
import { on, me } from '../store.js';

let sim = null;
async function loadSim() {
  if (!sim) sim = await import('../vendor/pkmn-sim.js');
  return sim;
}

// names of the room's game in its language, read from its ROM (cached per room)
const namesCache = new Map();
async function roomNames(roomId) {
  if (!namesCache.has(roomId)) {
    namesCache.set(roomId, local.get(`/api/rooms/${roomId}/names`).catch(() => ({})));
  }
  return namesCache.get(roomId);
}

/** "POING-KARATE" (generations 1-3 write in capitals) -> "Poing-Karate". */
function pretty(name) {
  if (!name || typeof name !== 'string') return null;
  const n = name.replace(/[\u0000-\u001f]/g, '').trim();
  if (!n || n === '-' || /^-+$/.test(n)) return null;
  if (n !== n.toUpperCase()) return n;
  return n.toLowerCase().replace(/(^|[\s'-])([\p{L}])/gu, (m, a, b) => a + b.toUpperCase());
}

const NATURES = ['Hardy', 'Lonely', 'Brave', 'Adamant', 'Naughty', 'Bold', 'Docile', 'Relaxed', 'Impish', 'Lax',
  'Timid', 'Hasty', 'Serious', 'Jolly', 'Naive', 'Modest', 'Mild', 'Quiet', 'Bashful', 'Rash', 'Calm', 'Gentle',
  'Sassy', 'Careful', 'Quirky'];
const TYPES = {
  Normal: 'Normal', Fire: 'Feu', Water: 'Eau', Electric: 'Électrik', Grass: 'Plante', Ice: 'Glace', Fighting: 'Combat',
  Poison: 'Poison', Ground: 'Sol', Flying: 'Vol', Psychic: 'Psy', Bug: 'Insecte', Rock: 'Roche', Ghost: 'Spectre',
  Dragon: 'Dragon', Dark: 'Ténèbres', Steel: 'Acier', Fairy: 'Fée', '???': '???',
};
const STATUS = { par: ['PAR', 'paralysé'], brn: ['BRU', 'brûlé'], slp: ['SOM', 'endormi'], frz: ['GEL', 'gelé'],
  psn: ['PSN', 'empoisonné'], tox: ['PSN', 'gravement empoisonné'] };
const STATS = { atk: 'l’Attaque', def: 'la Défense', spa: 'l’Attaque Spéciale', spd: 'la Défense Spéciale', spe: 'la Vitesse',
  accuracy: 'la Précision', evasion: 'l’Esquive', spc: 'le Spécial' };

/** Showdown sets from the players' Pokémon (see GameReader.battleSet). */
function buildTeam(sets, dex, names, levelMode) {
  const byNum = (list) => { const m = new Map(); for (const x of list) if (!m.has(x.num)) m.set(x.num, x); return m; };
  const species = byNum(dex.species.all().filter((s) => !s.forme || s.baseSpecies === s.name));
  const moves = byNum(dex.moves.all());
  const abilities = byNum(dex.abilities.all());
  const items = byNum(dex.items.all());
  const used = new Set();
  return sets.map((s) => {
    const sp = species.get(Number(s.species)) || dex.species.get('Pikachu');
    const gen = Number(s.gen);
    let name = (s.nickname || pretty(names.species?.[s.species]) || sp.name).replace(/[|,\]]/g, '').slice(0, 18) || sp.name;
    // the names identify the Pokémon in the battle: two the same get a number
    for (let i = 2; used.has(name); i++) name = `${name.slice(0, 15)} ${i}`;
    used.add(name);
    const known = (s.moves || []).map((id, i) => [moves.get(Number(id))?.id, Number(s.ppUps?.[i]) || 0]).filter(([id]) => id);
    const set = {
      name, species: sp.name,
      level: levelMode === 'real' ? Math.max(1, Math.min(100, Number(s.level) || 50)) : Number(levelMode),
      moves: known.map(([id]) => id),
      // the games' own PP Ups (Showdown would give 3 to every move)
      movePPUps: known.map(([, up]) => Math.max(0, Math.min(3, up))),
      ivs: s.ivs || {}, evs: s.evs || {},
      happiness: s.happiness ?? 255,
      shiny: !!s.shiny,
      item: '',
      ability: '',
      gender: '',
    };
    if (!set.moves.length) { set.moves = ['struggle']; set.movePPUps = [0]; }
    if (gen >= 3) set.nature = NATURES[Number(s.nature) % 25] || 'Hardy';
    if (gen >= 4) {
      set.ability = abilities.get(Number(s.ability))?.name || Object.values(sp.abilities)[0] || '';
      if (Number(s.item) > 0) set.item = items.get(Number(s.item))?.name || '';
      set.gender = s.gender === 'N' ? '' : s.gender || '';
    } else if (gen === 3) {
      set.ability = sp.abilities[Number(s.abilitySlot) || 0] || sp.abilities[0] || '';
    }
    // gender: the species decides, else the game's own rule (generation 3-4 personality, generation 2 Attack DV)
    if (sp.gender) set.gender = sp.gender === 'N' ? '' : sp.gender;
    else if (!set.gender && s.genderValue !== undefined && sp.genderRatio) {
      const f = sp.genderRatio.F;
      set.gender = gen === 2 ? (Number(s.genderValue) < f * 16 ? 'F' : 'M') : (Number(s.genderValue) < f * 256 ? 'F' : 'M');
    }
    if (gen <= 2) delete set.nature;
    return set;
  });
}

// --------------------------------------------------------------- the battle

let current = null; // the battle shown (one at a time)

/** Opens (or brings back) the screen of a battle. */
export async function openBattle(roomId, battleId) {
  if (current?.id === battleId) return;
  current?.close();
  const view = { id: battleId, close: () => {} };
  current = view;

  const box = h('div', { class: 'battle' }, h('div', { class: 'row muted', style: { padding: '24px' } }, h('span', { class: 'spinner' }), 'Préparation du combat…'));
  const back = h('div', { class: 'modal-back battle-back' }, box);
  document.getElementById('modals').appendChild(back);

  let info;
  let names;
  let S;
  try {
    [info, names, S] = await Promise.all([srv.get(`/rooms/${roomId}/battles/${battleId}`), roomNames(roomId), loadSim()]);
  } catch (e) {
    back.remove();
    current = null;
    errorToast(e);
    return;
  }
  const user = me();
  const mySide = info.p1 === user?.id ? 'p1' : info.p2 === user?.id ? 'p2' : null;
  const bottom = mySide || 'p1';
  const top = bottom === 'p1' ? 'p2' : 'p1';
  const gen = Math.max(1, Math.min(5, Number(info.gen) || 3));
  const dex = S.Dex.forGen(gen);
  const moveNum = (idOrName) => dex.moves.get(idOrName)?.num;
  const moveName = (idOrName) => pretty(names.moves?.[moveNum(idOrName)]) || dex.moves.get(idOrName)?.name || idOrName;
  const abilityName = (n) => pretty(names.abilities?.[dex.abilities.get(n)?.num]) || n;
  const itemName = (n) => pretty(names.items?.[dex.items.get(n)?.num]) || n;
  const playerName = { p1: info.p1Name || 'Joueur 1', p2: info.p2Name === info.p1Name ? `${info.p2Name} (2)` : info.p2Name || 'Joueur 2' };

  // a custom game, as in the cartridges: no team preview (generation 5 has one by default), the first Pokémon leads
  const battle = new S.Battle({ formatid: gen >= 5 ? 'gen5customgame@@@!teampreview' : `gen${gen}customgame`,
    seed: (info.seed || [1, 2, 3, 4]).map(Number) });
  battle.setPlayer('p1', { name: playerName.p1, team: buildTeam(info.teams?.p1 || [], dex, names, info.level) });
  battle.setPlayer('p2', { name: playerName.p2, team: buildTeam(info.teams?.p2 || [], dex, names, info.level) });
  // the games' PP Ups (the engine gives every move 3): set before the first turn, the same on every screen
  for (const side of battle.sides) {
    for (const p of side.pokemon) {
      const ups = p.set?.movePPUps || [];
      p.baseMoveSlots.forEach((slot, i) => {
        const up = ups[i] ?? 3;
        slot.pp = slot.maxpp = battle.calculatePP(dex.moves.get(slot.id), up);
        if (p.ppUps) p.ppUps[i] = up;
      });
      p.moveSlots.forEach((slot, i) => { const base = p.baseMoveSlots[i]; if (base) { slot.pp = base.pp; slot.maxpp = base.maxpp; } });
    }
  }

  const st = { applied: 0, logAt: 0, lines: [], pending: false, panel: 'moves', reported: false, closed: false, server: info };
  const sideOf = (id) => battle.sides[id === 'p1' ? 0 : 1];

  function apply(choices) {
    for (; st.applied < choices.length; st.applied++) {
      const c = choices[st.applied];
      if (battle.ended) continue;
      if (c.choice === 'forfeit') battle.lose(c.side);
      else battle.choose(c.side, c.choice);
    }
    readLog();
  }

  // ---------------------------------------------------------------- the log, in French
  const who = (ident) => (ident || '').replace(/^p\da?:\s*/, '');
  function line(text, kind = '') { if (text) st.lines.push({ text, kind }); }
  function readLog() {
    const log = battle.log;
    for (; st.logAt < log.length; st.logAt++) {
      const raw = log[st.logAt];
      if (raw.startsWith('|split|')) {
        // secret (exact HP) for this side's player, public (percentages) for the others
        const side = raw.slice(7);
        const pick = side === mySide ? log[st.logAt + 1] : log[st.logAt + 2];
        st.logAt += 2;
        describe(pick || '');
      } else {
        describe(raw);
      }
    }
  }
  function describe(raw) {
    const p = raw.split('|').slice(1);
    const tag = p[0];
    const from = p.find((x) => x?.startsWith('[from]'))?.slice(7).replace(/^(item|ability|move): /, '');
    switch (tag) {
      case 'start': line('Le combat commence !', 'sep'); break;
      case 'turn': line(`Tour ${p[1]}`, 'sep'); break;
      case 'switch': case 'drag': {
        const side = p[1].slice(0, 2);
        line(tag === 'drag' ? `${who(p[1])} est traîné au combat !` : `${playerName[side]} envoie ${who(p[1])} !`);
        break;
      }
      case 'move': line(`${who(p[1])} utilise ${moveName(p[2])} !`, 'move'); break;
      case 'faint': line(`${who(p[1])} est K.O. !`, 'bad'); break;
      case '-supereffective': line('C’est super efficace !'); break;
      case '-resisted': line('Ce n’est pas très efficace…'); break;
      case '-immune': line(`Ça n’affecte pas ${who(p[1])}…`); break;
      case '-crit': line('Coup critique !'); break;
      case '-miss': line(p[2] ? `${who(p[2])} évite l’attaque !` : `L’attaque de ${who(p[1])} échoue !`); break;
      case '-fail': line('Mais cela échoue !'); break;
      case '-ohko': line('K.O. en un coup !'); break;
      case '-hitcount': line(`Touché ${p[2]} fois !`); break;
      case '-status': line(`${who(p[1])} est ${STATUS[p[2]]?.[1] || p[2]} !`); break;
      case '-curestatus':
        line(p[2] === 'slp' ? `${who(p[1])} se réveille !` : p[2] === 'frz' ? `${who(p[1])} dégèle !` : `${who(p[1])} n’est plus ${STATUS[p[2]]?.[1] || 'malade'}.`);
        break;
      case 'cant': {
        const r = { par: 'est paralysé ! Il ne peut pas attaquer !', slp: 'dort profondément.', frz: 'est gelé !',
          flinch: 'a peur et ne peut pas attaquer !', recharge: 'doit se reposer !' }[p[2]] || 'ne peut pas attaquer !';
        line(`${who(p[1])} ${r}`);
        break;
      }
      case '-boost': case '-unboost': {
        const n = Number(p[3]);
        const what = tag === '-boost' ? (n >= 2 ? 'augmente beaucoup' : 'augmente') : (n >= 2 ? 'baisse beaucoup' : 'baisse');
        line(`${(STATS[p[2]] || p[2]).replace(/^l[ae’]\s?/, (m) => m.charAt(0).toUpperCase() + m.slice(1))} de ${who(p[1])} ${what} !`);
        break;
      }
      case '-damage':
        if (from === 'psn' || from === 'tox') line(`${who(p[1])} souffre du poison !`);
        else if (from === 'brn') line(`${who(p[1])} souffre de sa brûlure !`);
        else if (from === 'Sandstorm') line(`${who(p[1])} est blessé par la tempête de sable !`);
        else if (from === 'Hail') line(`${who(p[1])} est blessé par la grêle !`);
        else if (from === 'confusion') line(`${who(p[1])} se blesse dans sa confusion !`);
        else if (from === 'Recoil' || from === 'recoil') line(`${who(p[1])} subit le contrecoup !`);
        else if (from === 'Leech Seed') line(`Vampigraine draine l’énergie de ${who(p[1])} !`);
        else if (from) line(`${who(p[1])} est blessé (${itemName(from)}).`);
        break;
      case '-heal':
        line(from ? `${who(p[1])} récupère des PV (${itemName(abilityName(from))}).` : `${who(p[1])} récupère des PV.`);
        break;
      case '-start':
        if (p[2] === 'confusion' || p[2]?.endsWith('confusion')) line(`${who(p[1])} est confus !`);
        else if (/Substitute/.test(p[2] || '')) line(`${who(p[1])} crée un clone !`);
        else if (/Leech Seed/.test(p[2] || '')) line(`${who(p[1])} est infecté !`);
        break;
      case '-end':
        if (p[2] === 'confusion') line(`${who(p[1])} n’est plus confus.`);
        else if (/Substitute/.test(p[2] || '')) line(`Le clone de ${who(p[1])} disparaît !`);
        break;
      case '-weather': {
        if (p[2] === '[upkeep]') break;
        const w = { RainDance: 'Il commence à pleuvoir !', SunnyDay: 'Le soleil brille !', Sandstorm: 'Une tempête de sable se lève !',
          Hail: 'Il commence à grêler !', none: 'Le temps redevient normal.' }[p[1]];
        line(w || null);
        break;
      }
      case '-ability': line(`${who(p[1])} : talent ${abilityName(p[2])} !`); break;
      case '-enditem': if ((p[3] || '').includes('[eat]')) line(`${who(p[1])} mange : ${itemName(p[2])} !`); break;
      case '-sidestart': line(`${moveName(p[2].replace(/^move: /, ''))} protège l’équipe de ${playerName[p[1].slice(0, 2)]} !`); break;
      case '-sideend': line(`${moveName(p[2].replace(/^move: /, ''))} prend fin chez ${playerName[p[1].slice(0, 2)]}.`); break;
      case 'win': line(`${p[1]} gagne le combat !`, 'end'); break;
      case 'tie': line('Égalité !', 'end'); break;
      default: break;
    }
  }

  // ---------------------------------------------------------------- choices
  async function choose(choice) {
    if (st.pending || !mySide) return;
    st.pending = true;
    draw();
    try {
      const r = await srv.post(`/rooms/${roomId}/battles`, { action: 'choose', battle: battleId, choice, index: st.applied });
      update(r);
    } catch (e) {
      errorToast(e);
    } finally {
      st.pending = false;
      draw();
    }
  }

  function update(b) {
    if (!b || b.id !== battleId) return;
    st.server = b;
    if (Array.isArray(b.choices)) apply(b.choices);
    draw();
    if (battle.ended && mySide && !st.reported && b.state === 'running') {
      st.reported = true;
      const winner = battle.winner ? (battle.winner === playerName.p1 ? 'p1' : battle.winner === playerName.p2 ? 'p2' : 'tie') : 'tie';
      srv.post(`/rooms/${roomId}/battles`, { action: 'result', battle: battleId, winner }).catch(() => {});
    }
  }

  // ---------------------------------------------------------------- drawing
  const logBox = h('div', { class: 'battle-log' });
  function hpColor(f) { return f > 0.5 ? 'var(--ok)' : f > 0.2 ? 'var(--warn)' : 'var(--danger)'; }

  function monCard(sideId, exact) {
    const side = sideOf(sideId);
    const p = side.active[0];
    const team = side.pokemon;
    const balls = h('div', { class: 'battle-balls', title: 'Équipe' }, team.map((m) => h('i', { class: m.fainted ? 'out' : m.status ? 'sick' : '' })));
    if (!p) return h('div', { class: ['battle-mon', sideId === bottom ? 'mine' : 'theirs'] }, h('b', null, playerName[sideId]), balls);
    const f = p.maxhp ? p.hp / p.maxhp : 0;
    const types = p.getTypes ? p.getTypes() : p.species.types;
    return h('div', { class: ['battle-mon', sideId === bottom ? 'mine' : 'theirs', p.fainted && 'fainted'] },
      h('div', { class: 'row between' },
        h('div', null, h('b', null, p.name), h('span', { class: 'muted small' }, `  N.${p.level}`),
          p.gender === 'M' ? h('span', { class: 'g-m' }, ' ♂') : p.gender === 'F' ? h('span', { class: 'g-f' }, ' ♀') : null),
        p.status ? h('span', { class: `chip st-${p.status}` }, STATUS[p.status]?.[0] || p.status.toUpperCase()) : null),
      h('div', { class: 'tiny muted' }, `${pretty(names.species?.[p.species.num]) || p.species.name} · ${types.map((t) => TYPES[t] || t).join(' / ')}`),
      h('div', { class: 'battle-hp' }, h('i', { style: { width: `${Math.max(0, Math.round(f * 100))}%`, background: hpColor(f) } })),
      h('div', { class: 'row between tiny muted' }, h('span', null, exact ? `${p.hp} / ${p.maxhp} PV` : `${Math.round(f * 100)} %`), balls),
      h('div', { class: 'tiny faint' }, playerName[sideId]));
  }

  function controls() {
    if (battle.ended) {
      const w = battle.winner;
      return h('div', { class: 'battle-controls end' },
        h('b', null, !w ? 'Égalité.' : w === playerName[mySide] ? 'Victoire !' : mySide ? 'Défaite…' : `${w} gagne !`),
        h('span', { class: 'muted small' }, 'Rien ne change dans vos jeux : c’était pour le plaisir.'),
        btn('Fermer', () => view.close(), { kind: 'primary' }));
    }
    if (!mySide) return h('div', { class: 'battle-controls muted small' }, 'Vous regardez ce combat.');
    const side = sideOf(mySide);
    const req = side.activeRequest;
    if (!req || req.wait || side.isChoiceDone?.() || st.pending) {
      return h('div', { class: 'battle-controls muted small' }, h('span', { class: 'spinner' }), ' En attente de l’adversaire…',
        h('span', { class: 'grow' }), btn('Abandonner', forfeit, { kind: 'ghost small danger' }));
    }
    const forced = req.forceSwitch?.[0];
    const team = req.side.pokemon;
    const switchList = h('div', { class: 'battle-switch' }, team.map((m, i) => {
      const [hp, status] = (m.condition || '').split(' ');
      const dead = hp === '0' || status === 'fnt' || m.condition?.includes('fnt');
      const name = m.ident.replace(/^p\d: /, '');
      return btn(`${name} · ${dead ? 'K.O.' : hp}`, () => choose(`switch ${i + 1}`), { kind: 'small', disabled: m.active || dead });
    }));
    if (forced) {
      return h('div', { class: 'battle-controls' }, h('b', null, 'Choisissez le Pokémon suivant :'), switchList);
    }
    const act = req.active?.[0];
    const slots = side.active[0]?.moveSlots || [];
    const moves = h('div', { class: 'battle-moves' }, (act?.moves || []).map((m, i) => {
      const mv = dex.moves.get(m.id);
      // PP from the Pokémon itself (the first request was made before the games' PP Ups were set)
      if (slots[i]?.id === m.id) { m = { ...m, pp: slots[i].pp, maxpp: slots[i].maxpp }; }
      return h('button', { class: `battle-move t-${(mv?.type || 'Normal').toLowerCase()}`, disabled: m.disabled || m.pp === 0,
        onclick: () => choose(`move ${i + 1}`) },
      h('b', null, m.id === 'struggle' ? 'Lutte' : moveName(m.id)),
      h('small', null, `${TYPES[mv?.type] || ''}${m.maxpp ? ` · ${m.pp}/${m.maxpp} PP` : ''}`));
    }));
    const trapped = act?.trapped;
    return h('div', { class: 'battle-controls' },
      seg([['moves', 'Attaques'], ['switch', 'Changer']], st.panel, (v) => { st.panel = v; draw(); }, { disabled: trapped }),
      st.panel === 'switch' && !trapped ? switchList : moves,
      h('div', { class: 'row' }, h('span', { class: 'grow' }), btn('Abandonner', forfeit, { kind: 'ghost small danger' })));
  }

  async function forfeit() {
    if (await confirmDialog('Abandonner le combat', 'Votre adversaire gagne le combat.', { okLabel: 'Abandonner', danger: true })) choose('forfeit');
  }

  function draw() {
    if (st.closed) return;
    const stick = logBox.scrollHeight - logBox.scrollTop - logBox.clientHeight < 30;
    mount(logBox, st.lines.slice(-300).map((l) => h('div', { class: ['bl', l.kind] }, l.text)));
    if (stick) logBox.scrollTop = logBox.scrollHeight;
    mount(box,
      h('div', { class: 'battle-head' },
        h('b', null, `${playerName.p1} contre ${playerName.p2}`),
        h('span', { class: 'chip' }, info.level === 'real' ? 'Niveaux des jeux' : `Niveau ${info.level}`),
        h('span', { class: 'grow' }),
        h('button', { class: 'btn ghost icon-only', title: 'Fermer (le combat continue)', onclick: () => view.close() }, icon('x'))),
      h('div', { class: 'battle-body' },
        h('div', { class: 'battle-field' }, monCard(top, !mySide), monCard(bottom, !!mySide)),
        logBox),
      controls());
  }

  view.close = () => {
    st.closed = true;
    off();
    back.remove();
    if (current === view) current = null;
  };
  const off = on('battle', (b) => update(b));
  apply(info.choices || []);
  st.server = info;
  draw();
  logBox.scrollTop = logBox.scrollHeight;
  update(info);
}

// --------------------------------------------------------------- the room's tab

/** Battles of a room: challenge a partner, answer a challenge, watch, results. */
export function battlesPanel(room) {
  const el = h('div');
  const st = { battles: null, level: 'real', to: '' };
  const user = me();

  async function load() {
    try { st.battles = (await srv.get(`/rooms/${room.id}/battles`)).battles; } catch (e) { st.battles = []; st.error = e.message; }
    draw();
  }

  function act(body, button) {
    if (button) button.disabled = true;
    return srv.post(`/rooms/${room.id}/battles`, body).then((b) => { merge(b); return b; }).catch(errorToast)
      .finally(() => { if (button && button.isConnected) button.disabled = false; });
  }

  function merge(b) {
    if (!b || b.roomId !== room.id) return;
    const list = st.battles || [];
    const i = list.findIndex((x) => x.id === b.id);
    if (i >= 0) list[i] = b; else list.unshift(b);
    st.battles = list;
    draw();
  }

  function draw() {
    const partners = room.members.filter((m) => m.userId !== user?.id);
    if (!st.to && partners.length) st.to = partners[0].userId;
    const list = st.battles || [];
    const open = list.filter((b) => b.state === 'challenge' || b.state === 'running');
    const done = list.filter((b) => b.state === 'done').slice(0, 8);
    const select = h('select', { class: 'input', style: { width: '200px' }, onchange: (e) => { st.to = e.target.value; } },
      partners.map((m) => h('option', { value: m.userId, selected: st.to === m.userId }, m.name)));
    mount(el,
      h('div', { class: 'card' },
        h('h3', null, 'Défier un partenaire'),
        h('p', { class: 'muted small mt-s' }, 'Un combat avec vos équipes actuelles (lues dans vos jeux), pour le plaisir : rien ne change dans vos parties, aucun Pokémon ne meurt. Les Pokémon morts du Soul Link ne combattent pas.'),
        partners.length ? h('div', { class: 'row wrap mt' }, select,
          seg([['real', 'Niveaux des jeux'], ['50', 'Niveau 50'], ['100', 'Niveau 100']], st.level, (v) => { st.level = v; draw(); }),
          btn('Défier', (e) => act({ action: 'challenge', to: st.to, level: st.level }, e.currentTarget), { kind: 'primary', iconName: 'swords' }))
          : h('p', { class: 'faint small mt' }, 'Il faut au moins un partenaire dans la salle.')),
      open.length ? h('div', { class: 'stack mt' }, open.map((b) => {
        const mine = b.p1 === user?.id || b.p2 === user?.id;
        const asked = b.state === 'challenge' && b.p2 === user?.id;
        return h('div', { class: 'card tight row' },
          icon('swords'),
          h('div', { class: 'grow' }, h('b', null, `${b.p1Name} contre ${b.p2Name}`),
            h('div', { class: 'tiny muted' }, b.state === 'challenge' ? (asked ? 'Vous êtes défié !' : 'En attente de réponse…') : 'Combat en cours')),
          asked ? btn('Refuser', (e) => act({ action: 'decline', battle: b.id }, e.currentTarget), { kind: 'small ghost' }) : null,
          asked ? btn('Accepter', async (e) => { const r = await act({ action: 'accept', battle: b.id }, e.currentTarget); if (r?.state === 'running') openBattle(room.id, b.id); }, { kind: 'small primary' }) : null,
          b.state === 'challenge' && b.p1 === user?.id ? btn('Annuler', (e) => act({ action: 'cancel', battle: b.id }, e.currentTarget), { kind: 'small ghost' }) : null,
          b.state === 'running' ? btn(mine ? 'Reprendre' : 'Regarder', () => openBattle(room.id, b.id), { kind: 'small' }) : null);
      })) : null,
      done.length ? h('div', { class: 'mt' }, h('div', { class: 'section-title' }, h('h2', null, 'Derniers combats')),
        h('div', { class: 'card tight' }, h('div', { class: 'list' }, done.map((b) => h('div', { class: 'list-item' },
          h('div', { class: 'grow' }, b.winner === 'tie' ? `${b.p1Name} et ${b.p2Name} : égalité`
            : `${b.winner === 'p1' ? b.p1Name : b.p2Name} bat ${b.winner === 'p1' ? b.p2Name : b.p1Name}`),
          h('span', { class: 'tiny faint' }, new Date(b.at).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }))))))) : null);
  }

  const off = on('battle', (b) => merge(b));
  draw();
  load();
  return { el, destroy: off };
}

/** Everywhere in the interface: a challenge received opens a question; a battle accepted opens its screen. */
export function watchBattles() {
  const asked = new Set();
  on('battle', (b) => {
    const user = me();
    if (!b || !user) return;
    if (b.state === 'challenge' && b.p2 === user.id && !asked.has(b.id)) {
      asked.add(b.id);
      modal({
        title: '⚔ Défi',
        body: h('p', null, `${b.p1Name} vous défie en combat (${b.level === 'real' ? 'niveaux des jeux' : `niveau ${b.level}`}). Vos équipes actuelles s’affrontent, sans rien changer dans vos jeux.`),
        actions: [
          { label: 'Refuser', onClick: (close) => { srv.post(`/rooms/${b.roomId}/battles`, { action: 'decline', battle: b.id }).catch(() => {}); close(); } },
          { label: 'Accepter', kind: 'primary', onClick: async (close) => {
            close();
            try {
              const r = await srv.post(`/rooms/${b.roomId}/battles`, { action: 'accept', battle: b.id });
              if (r.state === 'running') openBattle(b.roomId, b.id);
            } catch (e) { errorToast(e); }
          } },
        ],
      });
    }
    // the challenger sees the battle start
    if (b.state === 'running' && b.p1 === user.id && !asked.has(`run-${b.id}`) && (b.choiceCount || 0) === 0) {
      asked.add(`run-${b.id}`);
      openBattle(b.roomId, b.id);
    }
    if (b.state === 'declined' && b.p1 === user.id && !asked.has(`no-${b.id}`)) {
      asked.add(`no-${b.id}`);
      toast(`${b.p2Name} a refusé votre défi.`, 'info');
    }
  });
}

