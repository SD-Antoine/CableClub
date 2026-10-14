// The tab of a game mode: its rooms, its public rooms and its history. Each
// mode of Cable Club has its own tab (Soul Link and Nuzlocke; others to come, greyed).

import { h, mount } from '../ui.js';
import { renderHome } from './home.js';
import { renderPublic } from './public.js';
import { renderHistory } from './history.js';

export const MODES = {
  soullink: {
    name: 'Soul Link',
    logo: 'img/soullink.svg',
    hash: '#/soullink',
    blurb: 'De 2 à 5 joueurs, chacun dans sa partie : les Pokémon capturés dans une même zone sont liés, et si l’un meurt, son partenaire aussi.',
  },
  nuzlocke: {
    name: 'Nuzlocke',
    logo: 'img/nuzlocke.svg',
    hash: '#/nuzlocke',
    blurb: 'De 1 à 10 joueurs, chacun son Nuzlocke : un Pokémon K.O. est mort, et seul le premier Pokémon sauvage rencontré dans chaque zone peut être capturé.',
  },
  // to come
  sololocke: { name: 'Sololocke', logo: 'img/sololocke.svg', soon: true },
  shinylocke: { name: 'Shinylocke', logo: 'img/shinylocke.svg', soon: true },
};

const TABS = [['salles', 'Mes salles'], ['publiques', 'Salles publiques'], ['historique', 'Historique']];

export function renderMode(container, mode, tab) {
  const m = MODES[mode]?.soon ? MODES.soullink : MODES[mode] || MODES.soullink;
  const current = TABS.some(([id]) => id === tab) ? tab : 'salles';
  const page = h('div', { class: 'page' });
  const body = h('div');
  mount(page,
    h('div', { class: 'mode-head' }, h('img', { src: m.logo, alt: '' }),
      h('div', null, h('h1', null, m.name), h('p', { class: 'muted' }, m.blurb))),
    h('div', { class: 'tabs' }, TABS.map(([id, label]) => h('button', { class: current === id ? 'active' : '',
      onclick: () => { location.hash = id === 'salles' ? m.hash : `${m.hash}/${id}`; } }, label))),
    body);
  container.appendChild(page);
  const opts = { mode: MODES[mode]?.soon ? 'soullink' : MODES[mode] ? mode : 'soullink' };
  if (current === 'publiques') return renderPublic(body, opts);
  if (current === 'historique') return renderHistory(body, opts);
  return renderHome(body, opts);
}
