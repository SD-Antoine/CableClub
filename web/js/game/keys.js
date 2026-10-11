// Buttons of the consoles (libretro joypad bits) and the default keyboard (AZERTY).

export const BUTTONS = [
  { id: 'UP', bit: 4, label: 'Haut' },
  { id: 'DOWN', bit: 5, label: 'Bas' },
  { id: 'LEFT', bit: 6, label: 'Gauche' },
  { id: 'RIGHT', bit: 7, label: 'Droite' },
  { id: 'A', bit: 8, label: 'A' },
  { id: 'B', bit: 0, label: 'B' },
  { id: 'X', bit: 9, label: 'X (DS)' },
  { id: 'Y', bit: 1, label: 'Y (DS)' },
  { id: 'L', bit: 10, label: 'L' },
  { id: 'R', bit: 11, label: 'R' },
  { id: 'START', bit: 3, label: 'Start' },
  { id: 'SELECT', bit: 2, label: 'Select' },
];

export const ACTIONS = [
  { id: 'FAST_HOLD', label: 'Avance rapide (maintenir)' },
  { id: 'FAST_TOGGLE', label: 'Avance rapide (marche / arrêt)' },
  { id: 'PAUSE', label: 'Pause' },
  { id: 'FULLSCREEN', label: 'Plein écran' },
];

export const DEFAULT_KEYS = {
  UP: 'arrowup', DOWN: 'arrowdown', LEFT: 'arrowleft', RIGHT: 'arrowright',
  A: 'x', B: 'w', X: 's', Y: 'q', L: 'a', R: 'z', START: 'enter', SELECT: 'backspace',
  FAST_HOLD: 'tab', FAST_TOGGLE: 't', PAUSE: 'p', FULLSCREEN: 'f11',
};

/** Normalized name of a keyboard key ("x", "enter", "arrowup"…). */
export function keyName(e) {
  return (e.key || '').length === 1 ? e.key.toLowerCase() : (e.key || '').toLowerCase();
}

export function keyLabel(k) {
  const names = {
    arrowup: '↑', arrowdown: '↓', arrowleft: '←', arrowright: '→', enter: 'Entrée', backspace: 'Retour',
    tab: 'Tab', ' ': 'Espace', escape: 'Échap', shift: 'Maj', control: 'Ctrl', alt: 'Alt',
  };
  if (!k) return '—';
  return names[k] || (k.length === 1 ? k.toUpperCase() : k.toUpperCase());
}

export function keymap(config) {
  return { ...DEFAULT_KEYS, ...(config?.keys || {}) };
}
