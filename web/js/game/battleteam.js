// The teams of a battle between players: Showdown sets from the Pokémon read in the games (GameReader.battleSet),
// for every generation. Kept apart from the battle screen so it can be checked without a browser.

/** "POING-KARATE" (generations 1-3 write in capitals) -> "Poing-Karate". */
export function pretty(name) {
  if (!name || typeof name !== 'string') return null;
  const n = name.replace(/[\u0000-\u001f]/g, '').trim();
  if (!n || n === '-' || /^-+$/.test(n)) return null;
  if (n !== n.toUpperCase()) return n;
  return n.toLowerCase().replace(/(^|[\s'-])([\p{L}])/gu, (m, a, b) => a + b.toUpperCase());
}

export const NATURES = ['Hardy', 'Lonely', 'Brave', 'Adamant', 'Naughty', 'Bold', 'Docile', 'Relaxed', 'Impish', 'Lax',
  'Timid', 'Hasty', 'Serious', 'Jolly', 'Naive', 'Modest', 'Mild', 'Quiet', 'Bashful', 'Rash', 'Calm', 'Gentle',
  'Sassy', 'Careful', 'Quirky'];

/**
 * Generation 2: the held items that matter in a battle, by the game's own id (item list of the cartridge: Gold,
 * Silver and Crystal share it) -> Showdown's id.
 */
const GEN2_ITEMS = {
  3: 'brightpowder', 30: 'luckypunch', 35: 'metalpowder', 57: 'expshare', 73: 'quickclaw', 74: 'psncureberry',
  76: 'softsand', 77: 'sharpbeak', 78: 'przcureberry', 79: 'burntberry', 80: 'iceberry', 81: 'poisonbarb',
  82: 'kingsrock', 83: 'bitterberry', 84: 'mintberry', 88: 'silverpowder', 91: 'amuletcoin', 94: 'cleansetag',
  95: 'mysticwater', 96: 'twistedspoon', 98: 'blackbelt', 102: 'blackglasses', 103: 'slowpoketail', 104: 'pinkbow',
  105: 'stick', 106: 'smokeball', 107: 'nevermeltice', 108: 'magnet', 109: 'miracleberry', 112: 'everstone',
  113: 'spelltag', 117: 'miracleseed', 118: 'thickclub', 119: 'focusband', 125: 'hardstone', 126: 'luckyegg',
  138: 'charcoal', 139: 'berryjuice', 140: 'scopelens', 143: 'metalcoat', 144: 'dragonfang', 146: 'leftovers',
  150: 'mysteryberry', 151: 'dragonscale', 152: 'berserkgene', 163: 'lightball', 170: 'polkadotbow', 173: 'berry',
  174: 'goldberry',
};

/**
 * Generation 3 item id -> the number Showdown gives the item (the generation 4 list): the berries (133-175) moved by
 * 16, the held items (179-225) by 34, the Berry Juice from 44 to 43.
 */
function gen3ItemNum(id) {
  if (id >= 133 && id <= 175) return id + 16;
  if (id >= 179 && id <= 225) return id + 34;
  return id === 44 ? 43 : 0;
}

/** The game's own id of a Showdown item (the room's item names are in that order), or null. */
export function gameItemId(itemName, gen, dex) {
  const it = dex.items.get(itemName);
  if (!it?.exists) return null;
  if (gen === 2) {
    const e = Object.entries(GEN2_ITEMS).find(([, id]) => id === it.id);
    return e ? Number(e[0]) : null;
  }
  if (gen === 3) {
    if (it.num >= 149 && it.num <= 191) return it.num - 16;
    if (it.num >= 213 && it.num <= 259) return it.num - 34;
    return it.num === 43 ? 44 : null;
  }
  return it.num;
}

/** Showdown sets from the players' Pokémon. {@code levelMode}: 'real' (their own levels) or a level for everybody. */
export function buildTeam(sets, dex, names, levelMode) {
  const byNum = (list) => { const m = new Map(); for (const x of list) if (!m.has(x.num)) m.set(x.num, x); return m; };
  const species = byNum(dex.species.all().filter((s) => !s.forme || s.baseSpecies === s.name));
  const moves = byNum(dex.moves.all());
  const abilities = byNum(dex.abilities.all());
  const items = byNum(dex.items.all().filter((i) => i.num > 0));
  const itemOf = (s, gen) => {
    let it = null;
    if (gen === 2 && Number(s.item2) > 0) it = dex.items.get(GEN2_ITEMS[Number(s.item2)] || '');
    else if (gen === 3 && Number(s.item3) > 0) it = items.get(gen3ItemNum(Number(s.item3)));
    else if (gen >= 4 && Number(s.item) > 0) it = items.get(Number(s.item));
    return it?.exists && (!it.gen || it.gen <= gen) ? it.name : '';
  };
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
      item: gen >= 2 ? itemOf(s, gen) : '',
      ability: '',
      gender: '',
    };
    if (!set.moves.length) { set.moves = ['struggle']; set.movePPUps = [0]; }
    if (gen >= 3) set.nature = NATURES[Number(s.nature) % 25] || 'Hardy';
    if (gen >= 4) {
      set.ability = abilities.get(Number(s.ability))?.name || Object.values(sp.abilities)[0] || '';
      set.gender = s.gender === 'N' ? '' : s.gender || '';
    } else if (gen === 3) {
      set.ability = sp.abilities[Number(s.abilitySlot) || 0] || sp.abilities[0] || '';
    }
    // gender: the species decides, else the game's own rule (generation 3-4 personality, generation 2 Attack DV)
    if (gen >= 2) {
      if (sp.gender) set.gender = sp.gender === 'N' ? '' : sp.gender;
      else if (!set.gender && s.genderValue !== undefined && sp.genderRatio) {
        const f = sp.genderRatio.F;
        set.gender = gen === 2 ? (Number(s.genderValue) < f * 16 ? 'F' : 'M') : (Number(s.genderValue) < f * 256 ? 'F' : 'M');
      }
    }
    if (gen <= 2) delete set.nature;
    return set;
  });
}
