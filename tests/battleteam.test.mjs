// Battles of every generation in the simulator, with teams like the games give (node tests/battleteam.test.mjs).
// Checks: the sets are accepted, the held items of generations 2 and 3 are found, every battle goes to its end.
import * as S from '../web/js/vendor/pkmn-sim.js';
import { buildTeam, gameItemId } from '../web/js/game/battleteam.js';

const MAX_SPECIES = { 1: 151, 2: 251, 3: 386, 4: 493, 5: 649 };
let seed = 12345;
const rnd = (n) => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed % n; };
let failures = 0;
const check = (ok, what) => { console.log(`${ok ? 'OK   ' : 'ÉCHEC'} ${what}`); if (!ok) failures++; };

function randomSet(gen, dex) {
  const moves = dex.moves.all().filter((m) => m.num > 0 && m.gen <= gen && !m.isNonstandard && m.id !== 'struggle');
  const s = {
    gen, species: 1 + rnd(MAX_SPECIES[gen]), level: 5 + rnd(95), nickname: null,
    moves: [0, 1, 2, 3].map(() => moves[rnd(moves.length)].num), ppUps: [rnd(4), rnd(4), rnd(4), rnd(4)], shiny: rnd(10) === 0,
  };
  if (gen <= 2) {
    const dv = () => rnd(16);
    const [atk, def, spe, spc] = [dv(), dv(), dv(), dv()];
    const hp = ((atk & 1) << 3) | ((def & 1) << 2) | ((spe & 1) << 1) | (spc & 1);
    s.ivs = { hp: 2 * hp, atk: 2 * atk, def: 2 * def, spa: 2 * spc, spd: 2 * spc, spe: 2 * spe };
    s.evs = { hp: rnd(256), atk: rnd(256), def: rnd(256), spa: rnd(256), spd: rnd(256), spe: rnd(256) };
    if (gen === 2) { s.happiness = rnd(256); s.genderValue = atk; s.item2 = [0, 146, 73, 173, 109, 119, 82, 140][rnd(8)]; }
  } else {
    const iv = () => rnd(32);
    s.ivs = { hp: iv(), atk: iv(), def: iv(), spa: iv(), spd: iv(), spe: iv() };
    s.evs = { hp: rnd(85), atk: rnd(85), def: rnd(85), spa: rnd(85), spd: rnd(85), spe: rnd(85) };
    s.nature = rnd(25);
    s.genderValue = rnd(256);
    s.happiness = rnd(256);
    if (gen === 3) { s.abilitySlot = rnd(2); s.item3 = [0, 200, 142, 186, 183, 44, 175][rnd(7)]; }
    else {
      const abilities = dex.abilities.all().filter((a) => a.num > 0 && a.gen <= gen);
      s.ability = abilities[rnd(abilities.length)].num;
      const items = dex.items.all().filter((i) => i.num > 0 && i.gen <= gen && !i.isNonstandard);
      s.item = rnd(3) ? items[rnd(items.length)].num : 0;
      s.gender = ['M', 'F', 'N'][rnd(3)];
    }
  }
  return s;
}

for (const gen of [1, 2, 3, 4, 5]) {
  const dex = S.Dex.forGen(gen);
  // held items read from the games
  if (gen === 2) {
    const t = buildTeam([{ gen, species: 25, level: 50, moves: [85], item2: 146 }, { gen, species: 1, level: 50, moves: [22], item2: 173 }], dex, {}, 50);
    check(t[0].item === 'Leftovers' && t[1].item === 'Berry', `génération 2 : objets tenus lus (${t[0].item}, ${t[1].item})`);
    check(gameItemId('Leftovers', 2, dex) === 146, 'génération 2 : nom de l’objet retrouvé dans la ROM (Restes = 146)');
  }
  if (gen === 3) {
    const t = buildTeam([{ gen, species: 25, level: 50, moves: [85], item3: 200 }, { gen, species: 1, level: 50, moves: [22], item3: 142 }], dex, {}, 50);
    check(t[0].item === 'Leftovers' && t[1].item === 'Sitrus Berry', `génération 3 : objets tenus lus (${t[0].item}, ${t[1].item})`);
    check(gameItemId('Sitrus Berry', 3, dex) === 142, 'génération 3 : nom de l’objet retrouvé dans la ROM (Baie Sitrus = 142)');
  }
  let ended = 0; let errors = 0; let turns = 0; let lastError = '';
  for (let n = 0; n < 40; n++) {
    try {
      const size = 1 + rnd(6);
      const teams = [0, 1].map(() => Array.from({ length: size }, () => randomSet(gen, dex)));
      const level = n % 2 ? 'real' : 50;
      const battle = new S.Battle({ formatid: gen >= 5 ? 'gen5customgame@@@!teampreview' : `gen${gen}customgame`, seed: [n + 1, 2, 3, 4] });
      battle.setPlayer('p1', { name: 'Léa', team: buildTeam(teams[0], dex, {}, level) });
      battle.setPlayer('p2', { name: 'Mathis', team: buildTeam(teams[1], dex, {}, level) });
      for (let i = 0; i < 1000 && !battle.ended; i++) battle.makeChoices('default', 'default');
      if (battle.ended) ended++;
      turns += battle.turn;
    } catch (e) {
      errors++;
      lastError = e.message;
    }
  }
  check(errors === 0 && ended === 40, `génération ${gen} : 40 combats menés à leur fin (${turns} tours en tout)${errors ? `, ${errors} erreur(s) : ${lastError}` : ''}`);
}
console.log(failures ? `${failures} ÉCHEC(S)` : 'TOUT EST BON');
process.exit(failures ? 1 : 0);
