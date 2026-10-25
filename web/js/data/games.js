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

/** What Cable Club applies inside a generation's games for a game mode (shown to the players). */
export function support(gen, mode = 'soullink') {
  const linked = mode === 'soullink';
  const parts = [
    linked && (gen >= 4 ? 'partenaires visibles dans le jeu (3D)' : 'partenaires visibles dans le jeu'),
    'suivi',
    linked && 'morts liées',
    linked && 'surnoms',
    mode === 'sololocke' && 'un seul Pokémon dans l’équipe (les autres vont au PC)',
    gen <= 2 ? 'morts rangés dans la boîte actuelle du PC' : gen === 3 ? 'boîte MORTS' : 'boîte MORTS (au changement de lieu)',
    'objets rendus',
    gen >= 2 && 'taux de chromatiques',
    gen >= 2 ? 'combats entre joueurs (avec les objets tenus)' : 'combats entre joueurs',
    !linked && `règles classiques du Nuzlocke (objets interdits retirés du sac${gen >= 2 ? ', objets tenus' : ''}, Vol, évolutions, mode Défini…)`,
    !linked && 'option Hardcore (plafond de niveau, objets en combat, mode Défini)',
    'fin automatique',
  ].filter(Boolean).join(', ');
  return parts[0].toUpperCase() + parts.slice(1) + (gen === 1 ? ' (pas de Pokémon chromatiques dans cette génération)' : '');
}
