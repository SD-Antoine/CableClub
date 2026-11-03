// The goals of a mode and each player's progress (point 3): node tests/objectives.test.mjs
// Checks: the goals of the mode file, or the badges then the Champion for a mode that declares none; the progress
// from the counts the server sends (badges, captures, status), bounded by the target.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

globalThis.fetch = async (url) => ({ ok: true, json: async () => JSON.parse(readFileSync(fileURLToPath(url), 'utf8')) });

const caps = await import('../web/js/data/capabilities.js');
await caps.loadCapabilities();
const modes = await import('../web/js/data/modes.js');
await modes.loadModes();
const { objectivesOf, progressOf } = await import('../web/js/data/objectives.js');

let failed = 0;
const check = (ok, what) => { if (!ok) { failed++; console.log(`  ${what}`); } };
const types = (list) => list.map((g) => g.type + (g.count ? g.count : '')).join(',');

// official modes: none declared → the road of the game; the Nuzlocke Hardcore declares the Champion
for (const id of ['soullink', 'nuzlocke', 'sololocke', 'shinylocke']) {
  check(types(objectivesOf(id)) === 'badges8,champion', `${id} : 8 badges puis le Maître`);
}
check(types(objectivesOf('nuzlocke-hardcore')) === 'champion', 'Nuzlocke Hardcore : ses objectifs déclarés');
// an imported mode (a room keeps its file)
const room = { mode: 'mon-mode', modeFile: { schemaVersion: 1, id: 'mon-mode', win: [], objectives: [{ type: 'captures', count: 30 }, { type: 'badges', count: 4 }] } };
check(types(objectivesOf(room)) === 'captures30,badges4', 'mode importé : ses objectifs');
check(objectivesOf({ mode: 'x', modeFile: { schemaVersion: 1, id: 'x', win: [], objectives: [] } }).length === 0, 'sans objectif ni fin au Maître : rien');

// progress
const member = { status: 'playing', stats: { badges: 5, captures: 42 } };
let p = progressOf({ type: 'badges', count: 8 }, member);
check(p.value === 5 && p.target === 8 && !p.done, 'badges 5/8');
p = progressOf({ type: 'captures', count: 30 }, member);
check(p.value === 30 && p.done, 'captures plafonnées à la cible, objectif atteint');
check(!progressOf({ type: 'champion' }, member).done && progressOf({ type: 'champion' }, { status: 'champion' }).done, 'Maître vaincu');
check(progressOf({ type: 'badges', count: 8 }, {}).value === 0, 'joueur sans statistiques : 0');

console.log(failed === 0 ? 'ok     objectifs des modes' : `ÉCHEC  objectifs des modes : ${failed} cas`);
process.exit(failed === 0 ? 0 : 1);
