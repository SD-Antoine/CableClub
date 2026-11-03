// What the interface offers for each game, from web/js/data/capabilities.json (point 1d): node tests/capabilities.test.mjs
// Checks: the same rules are hidden as with the former "minGen" fields, the modes are offered in the same generations
// (their needs read in the mode files), the Nuzlocke Hardcore and an imported mode get the right rules,
// and the support line only gains what the data adds (trainer battles read or not) and the right death box.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// the interface fetches the file next to capabilities.js: read it from the disk here
globalThis.fetch = async (url) => ({ ok: true, json: async () => JSON.parse(readFileSync(fileURLToPath(url), 'utf8')) });

const caps = await import('../web/js/data/capabilities.js');
await caps.loadCapabilities();
const modes = await import('../web/js/data/modes.js');
await modes.loadModes();
const { rulesOf, modeAvailable, modeGenerations } = await import('../web/js/data/rules.js');
// the four historical modes (the Nuzlocke Hardcore is checked below)
const MODE_IDS = ['soullink', 'nuzlocke', 'sololocke', 'shinylocke'];
const { GAMES, support } = await import('../web/js/data/games.js');

let failed = 0;
const check = (ok, what) => { if (!ok) { failed++; console.log(`  ${what}`); } };

// the former rule: shinyClause and honorNoHeldItems from generation 2
const FORMER_MIN_GEN = { shinyClause: 2, honorNoHeldItems: 2 };
const keys = (list) => list.filter((d) => d.key).map((d) => d.key).join(',');
for (const mode of MODE_IDS) {
  for (const [gameId, game] of Object.entries(GAMES)) {
    const former = rulesOf(mode, 0).filter((d) => !d.key || !FORMER_MIN_GEN[d.key] || game.gen >= FORMER_MIN_GEN[d.key]);
    check(keys(rulesOf(mode, game.gen, gameId)) === keys(former), `${mode} / ${gameId} : mêmes règles qu'avant`);
  }
  check(keys(rulesOf(mode, 0)) === keys(rulesOf(mode, undefined)), `${mode} : jeu inconnu, toutes les règles`);
}

// modes: the Shinylocke from generation 2 (former MODE_MIN_GEN), the others everywhere
check(!modeAvailable('shinylocke', 1, 'red') && modeAvailable('shinylocke', 2, 'gold'), 'Shinylocke à partir de la 2e génération');
check(modeGenerations('shinylocke').join() === '2,3,4,5', 'générations du Shinylocke');
for (const mode of ['soullink', 'nuzlocke', 'sololocke']) check(modeGenerations(mode).join() === '1,2,3,4,5', `${mode} : toutes les générations`);

// the support line: the former text, plus the trainer battles where the game reads them
function former(gen, mode) {
  const linked = mode === 'soullink';
  const parts = [
    linked && (gen >= 4 ? 'partenaires visibles dans le jeu (3D)' : 'partenaires visibles dans le jeu'),
    'suivi', linked && 'morts liées', linked && 'surnoms',
    mode === 'sololocke' && 'un seul Pokémon dans l’équipe (les autres vont au PC)',
    gen <= 2 ? 'morts rangés dans la boîte actuelle du PC' : gen === 3 ? 'boîte MORTS' : 'boîte MORTS (au changement de lieu)',
    'objets rendus', gen >= 2 && 'taux de chromatiques',
    gen >= 2 ? 'combats entre joueurs (avec les objets tenus)' : 'combats entre joueurs',
    !linked && `règles classiques du Nuzlocke (objets interdits retirés du sac${gen >= 2 ? ', objets tenus' : ''}, Vol, évolutions, mode Défini…)`,
    !linked && 'option Hardcore (plafond de niveau, objets en combat, mode Défini)', 'fin automatique',
  ].filter(Boolean).join(', ');
  return parts[0].toUpperCase() + parts.slice(1) + (gen === 1 ? ' (pas de Pokémon chromatiques dans cette génération)' : '');
}
const READS_TRAINER_BATTLES = ['red', 'blue', 'yellow', 'crystal', 'ruby', 'sapphire', 'emerald', 'firered', 'leafgreen'];
for (const mode of MODE_IDS) {
  for (const [gameId, game] of Object.entries(GAMES)) {
    const text = support(game.gen, mode, gameId);
    const battles = text.includes(', combats de dresseurs');
    check(battles === READS_TRAINER_BATTLES.includes(gameId), `${mode} / ${gameId} : combats de dresseurs ${battles ? 'annoncés' : 'absents'}`);
    check(text.replace(', combats de dresseurs', '') === former(game.gen, mode), `${mode} / ${gameId} : reste du texte inchangé`);
  }
}

// the mode files (point 2): the interface reads the same modes as the server, in the same tabs
check(modes.officialModes().map((s) => s.id).join() === 'soullink,nuzlocke,sololocke,shinylocke,nuzlocke-hardcore', 'les 5 modes officiels sont lus');
check(modes.familyOf('nuzlocke-hardcore') === 'nuzlocke' && MODE_IDS.every((m) => modes.familyOf(m) === m), 'onglet de chaque mode');
const hardcore = rulesOf('nuzlocke-hardcore', 3, 'emerald');
check(hardcore.filter((d) => d.locked).map((d) => d.key).join() === 'hardcore', 'Nuzlocke Hardcore : seul le Hardcore est fixé');
check(keys(hardcore) === keys(rulesOf('nuzlocke', 3, 'emerald')), 'Nuzlocke Hardcore : les règles du Nuzlocke');
// an imported mode: the rules of its family, then those its family does not show; nothing without sense for it
const shinySolo = { schemaVersion: 1, id: 'shiny-solo', name: 'Shiny Sololocke', players: { min: 1, max: 4 }, needs: ['shinies'],
  engine: { links: 'none', encounters: 'shiniesOnly', team: 'one' }, win: ['champion'], lose: ['soloLost'],
  rules: { autoEnd: true, endWhen: 'all', soloParty: 'auto', soloLoss: 'next', oneShinyPerZone: { default: true, locked: true }, naming: 'free' } };
check(keys(rulesOf(shinySolo, 3, 'emerald')) === 'autoEnd,endWhen,oneShinyPerZone,soloParty,soloLoss', 'mode importé : ses règles, sans « naming » (sans objet)');
check(!modeAvailable(shinySolo, 1, 'red') && modeAvailable(shinySolo, 2, 'gold'), 'mode importé : ses besoins (chromatiques)');
check(modes.applies(shinySolo, 'soloLoss') && !modes.applies(shinySolo, 'wipeEndsRun') && !modes.applies(shinySolo, 'shinyClause'), 'règles qui ont du sens');

console.log(failed === 0 ? 'ok     capacités des jeux dans l’interface' : `ÉCHEC  capacités des jeux dans l’interface : ${failed} cas`);
process.exit(failed === 0 ? 0 : 1);
