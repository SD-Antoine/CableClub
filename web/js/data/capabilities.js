// What Cable Club can do in each game: capabilities.json next to this file, the same file the server reads
// (point 1d). Loaded once when the interface starts (loadCapabilities in app.js).

let DATA = { capabilities: {}, generations: {}, games: {}, modes: {}, rules: {}, deathBox: {} };

export async function loadCapabilities() {
  try {
    const res = await fetch(new URL('./capabilities.json', import.meta.url));
    if (res.ok) DATA = await res.json();
  } catch {
    // unreadable: nothing is hidden (the server still refuses what a game cannot do)
  }
}

/** Whether the game can do this: its own value, else its generation's. */
export function has(gameId, gen, cap) {
  const own = DATA.games?.[gameId]?.[cap];
  if (typeof own === 'boolean') return own;
  return DATA.generations?.[String(gen)]?.[cap] === true;
}

/** Why the game cannot do what a mode or a rule needs (the text of the first capability missing), or null. */
function missing(table, key, gameId, gen) {
  const needs = DATA[table]?.[key];
  if (!needs || !Number(gen)) return null; // nothing needed, or the game is not known yet
  for (const cap of needs) {
    if (!has(gameId, gen, cap)) return DATA.capabilities?.[cap]?.missing || cap;
  }
  return null;
}

export const missingForMode = (mode, gameId, gen) => missing('modes', mode, gameId, gen);
export const missingForRule = (rule, gameId, gen) => missing('rules', rule, gameId, gen);

/** Where the dead Pokémon go in this game (shown to the players). */
export function deathBoxText(gameId, gen) {
  const kind = DATA.games?.[gameId]?.deathBox || DATA.generations?.[String(gen)]?.deathBox;
  return DATA.deathBox?.[kind] || null;
}
