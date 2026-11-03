// The game modes: the mode files of modes/ (the same files the server reads, point 2), and the mode of a room
// (an official one, or the copy of an imported mode the room keeps). Loaded once when the interface starts.

import { has, capabilityMissing } from './capabilities.js';

let OFFICIAL = new Map();
let CATALOG = {};

export async function loadModes() {
  try {
    // the rules a mode may use (the server reads the same catalog)
    CATALOG = (await (await fetch(new URL('./modes/catalog.json', import.meta.url))).json()).rules || {};
    const index = await (await fetch(new URL('./modes/index.json', import.meta.url))).json();
    const files = await Promise.all(index.map(async (id) => (await fetch(new URL(`./modes/${id}.ccmode.json`, import.meta.url))).json()));
    OFFICIAL = new Map(files.map((f) => [f.id, f]));
  } catch {
    // unreadable: the rooms still show (the server sends each room's mode name)
  }
}

/** Every official mode, in the order of modes/index.json. */
export const officialModes = () => [...OFFICIAL.values()];
export const officialMode = (id) => OFFICIAL.get(id) || null;
export const isOfficial = (spec) => !!spec && OFFICIAL.get(spec.id) === spec;

/** A mode nobody can read: the Soul Link's shape, so that a screen still draws. */
const UNKNOWN = { id: 'soullink', name: 'Soul Link', players: { min: 2, max: 5 }, needs: [],
  engine: { links: 'shared', encounters: 'firstPerZone', team: 'free' }, rules: {}, win: ['champion'], lose: ['teamWiped'], objectives: [] };

/** The mode file of a mode id, of a room (its imported mode, else its official one) or a mode file itself. */
export function specOf(x) {
  if (x && typeof x === 'object' && x.schemaVersion) return x;
  if (x && typeof x === 'object') return x.modeFile || OFFICIAL.get(x.mode || 'soullink') || OFFICIAL.get('soullink') || UNKNOWN;
  return OFFICIAL.get(x || 'soullink') || OFFICIAL.get('soullink') || UNKNOWN;
}

/**
 * The historical mode a mode is closest to, whose tab shows its rooms (as ModeSpec.family on the server): the links
 * first (Soul Link), then the shinies (Shinylocke), then the team of one (Sololocke), else the Nuzlocke.
 */
export function familyOf(x) {
  if (x && typeof x === 'object' && !x.schemaVersion && x.family) return x.family; // a room: the server says
  const e = specOf(x).engine || {};
  return e.links === 'shared' ? 'soullink' : e.encounters === 'shiniesOnly' ? 'shinylocke' : e.team === 'one' ? 'sololocke' : 'nuzlocke';
}

/** The official modes shown in a tab (the Nuzlocke and the Nuzlocke Hardcore…). */
export const modesOfFamily = (family) => officialModes().filter((s) => familyOf(s) === family);

/** The name of a mode (a room: the name the server gives, which is also the one of an imported mode). */
export function modeName(x) {
  if (x && typeof x === 'object' && !x.schemaVersion && x.modeName) return x.modeName;
  return specOf(x).name;
}

export const linked = (x) => specOf(x).engine?.links === 'shared';
export const soloTeam = (x) => specOf(x).engine?.team === 'one';

/** The default value of each rule the mode offers. */
export function modeDefaults(x) {
  const out = {};
  for (const [k, v] of Object.entries(specOf(x).rules || {})) out[k] = v && typeof v === 'object' ? v.default : v;
  return out;
}

/** Whether the mode fixes this rule (it cannot change in its rooms). */
export function locked(x, rule) {
  const v = specOf(x).rules?.[rule];
  return !!(v && typeof v === 'object' && v.locked);
}

export const playersOf = (x) => specOf(x).players || UNKNOWN.players;

/** Why the game cannot play the mode (what is missing), or null. */
export function missingForModeOf(x, gameId, gen) {
  if (!Number(gen)) return null; // the game is not known yet
  for (const cap of specOf(x).needs || []) {
    if (!has(gameId, gen, cap)) return capabilityMissing(cap);
  }
  return null;
}

/** The rules of the catalog, in its order: { key, options (null: a switch), when, neutral, officialOnly }. */
export const catalogRules = () => Object.entries(CATALOG).map(([key, d]) => ({
  key, options: d.options || null, when: d.when || null, neutral: d.options ? d.neutral : false, officialOnly: !!d.officialOnly,
}));

/** Whether a rule makes sense with the mode's primitives ("links:shared"…, as ModeSpec on the server). */
export function applies(x, rule) {
  const d = CATALOG[rule];
  if (!d) return false;
  if (!d.when) return true;
  const spec = specOf(x);
  const [axis, value] = d.when.split(':');
  return axis === 'win' ? (spec.win || []).includes(value) : spec.engine?.[axis] === value;
}
