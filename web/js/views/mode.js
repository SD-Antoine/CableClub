// The tab of a game mode: its rooms, its public rooms and its history. Each
// mode of Cable Club has its own tab (only Soul Link for now).

import { h, mount } from '../ui.js';
import { renderHome } from './home.js';
import { renderPublic } from './public.js';
import { renderHistory } from './history.js';

export const MODES = {
  soullink: {
    name: 'Soul Link',
    logo: 'img/soullink.svg',
    hash: '#/soullink',
    blurb: 'Jusqu’à 4 joueurs, chacun dans sa partie : les Pokémon capturés dans une même zone sont liés, et si l’un meurt, son partenaire aussi.',
  },
};

const TABS = [['salles', 'Mes salles'], ['publiques', 'Salles publiques'], ['historique', 'Historique']];

export function renderMode(container, mode, tab) {
  const m = MODES[mode] || MODES.soullink;
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
  const opts = { mode };
  if (current === 'publiques') return renderPublic(body, opts);
  if (current === 'historique') return renderHistory(body, opts);
  return renderHome(body, opts);
}
