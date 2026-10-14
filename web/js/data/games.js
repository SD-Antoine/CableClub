// Supported games (generations 1 to 5).

export const GAMES = {
  red: { gen: 1, name: 'Rouge' },
  blue: { gen: 1, name: 'Bleue' },
  yellow: { gen: 1, name: 'Jaune' },
  gold: { gen: 2, name: 'Or' },
  silver: { gen: 2, name: 'Argent' },
  crystal: { gen: 2, name: 'Cristal' },
  ruby: { gen: 3, name: 'Rubis' },
  sapphire: { gen: 3, name: 'Saphir' },
  emerald: { gen: 3, name: 'Émeraude' },
  firered: { gen: 3, name: 'Rouge Feu' },
  leafgreen: { gen: 3, name: 'Vert Feuille' },
  diamond: { gen: 4, name: 'Diamant' },
  pearl: { gen: 4, name: 'Perle' },
  platinum: { gen: 4, name: 'Platine' },
  heartgold: { gen: 4, name: 'Or HeartGold' },
  soulsilver: { gen: 4, name: 'Argent SoulSilver' },
  black: { gen: 5, name: 'Noire' },
  white: { gen: 5, name: 'Blanche' },
  black2: { gen: 5, name: 'Noire 2' },
  white2: { gen: 5, name: 'Blanche 2' },
};

export const GEN_LABEL = { 1: '1re génération', 2: '2e génération', 3: '3e génération', 4: '4e génération', 5: '5e génération' };
export const PLATFORM = { gb: 'Game Boy', gbc: 'Game Boy Color', gba: 'Game Boy Advance', nds: 'Nintendo DS' };
export const LANG = { fr: 'Français', en: 'Anglais', de: 'Allemand', es: 'Espagnol', it: 'Italien', ja: 'Japonais', ko: 'Coréen' };

export const gameName = (id) => (GAMES[id] ? `Pokémon ${GAMES[id].name}` : 'Jeu inconnu');

/** What Cable Club applies inside each generation's games (shown to the players). */
export const SUPPORT = {
  1: 'Partenaires visibles dans le jeu, suivi, morts liées, surnoms, morts rangés dans la boîte actuelle du PC, fin automatique',
  2: 'Partenaires visibles dans le jeu, suivi, morts liées, surnoms, morts rangés dans la boîte actuelle du PC, fin automatique',
  3: 'Tout : partenaires visibles dans le jeu, suivi, morts liées, surnoms, combats, objets, objets rendus, boîte MORTS, fin automatique',
  4: 'Partenaires visibles dans le jeu (3D), suivi, morts liées, surnoms, boîte MORTS (au changement de lieu), fin automatique',
  5: 'Partenaires visibles dans le jeu (3D), suivi, morts liées, surnoms, boîte MORTS (au changement de lieu), fin automatique',
};
