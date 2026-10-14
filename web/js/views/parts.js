// Pieces shared by several screens: rules list, randomization settings.

import { h, mount, switchEl, seg, icon, act, keepScroll } from '../ui.js';
import { rulesOf, ruleDefaults } from '../data/rules.js';
import {
  SECTIONS, TWEAKS, PRESETS, optionAvailable, choiceAvailable, sanitizeSettings, presetById, randomSeed, summarize,
} from '../data/rando.js';

/** The room's rules: switches (editable by the leader, read-only for the others). */
export function rulesEditor(rules, { editable, onChange, mode }) {
  const r = { ...ruleDefaults(mode), ...(rules || {}) };
  return h('div', { class: 'list' }, rulesOf(mode).map((def) => {
    if (def.section) return h('div', { class: 'rule-section' }, def.section);
    let control;
    if (def.type === 'bool') {
      control = switchEl(!!r[def.key] && !def.soon, (v) => onChange({ [def.key]: v }), { disabled: !editable || def.soon });
    } else {
      control = seg(def.options, r[def.key], (v) => onChange({ [def.key]: v }), { disabled: !editable });
    }
    return h('div', { class: ['rule-row', def.highlight && 'rule-key', def.soon && 'soon'] },
      h('div', { class: 'grow' }, h('b', null, def.highlight ? '★ ' : '', def.label, def.soon ? h('span', { class: 'chip', style: { marginLeft: '8px' } }, 'Bientôt') : null),
        def.desc ? h('small', null, def.desc) : null), control);
  }));
}

/** Short description of the randomization of a room. */
export function randoLines(rando, gen) {
  const lines = summarize(rando, gen);
  if (rando?.enabled !== false) {
    if (rando?.perPlayer) lines.push('Une ROM différente par joueur');
    if (rando?.shops === 'secondary') lines.push('Vendeurs secondaires aléatoires (sans Master Ball ni objet de revente)');
  }
  if (Number(rando?.shinyRate) > 0 && gen >= 2) lines.push(`Chromatiques : 1 sur ${rando.shinyRate}`);
  return lines;
}

// shiny rates a room may choose (1 in N); the games' own is 1 in 8192
export const SHINY_RATES = [[0, 'Celui du jeu (1 sur 8192)'], [4096, '1 sur 4096'], [2048, '1 sur 2048'], [1024, '1 sur 1024'],
  [512, '1 sur 512'], [256, '1 sur 256']];

/** The shiny rate of a room (part of the randomization: the DS games get it in their ROM). */
function shinyCard(rando, gen, changed) {
  if (gen < 2) return null;
  const how = gen >= 4
    ? 'Écrit dans la ROM de la salle : vaut pour tous les Pokémon du jeu, sauvages comme ceux des dresseurs.'
    : 'Appliqué pendant la partie : chaque Pokémon sauvage a cette chance d’apparaître chromatique (étincelles, couleurs, et chromatique une fois capturé).';
  return h('div', { class: 'card tight' }, h('div', { class: 'rule-row' },
    h('div', { class: 'grow' }, h('b', null, 'Pokémon chromatiques'), h('small', null, how)),
    h('select', { class: 'input', style: { width: '210px' }, onchange: (e) => { rando.shinyRate = Number(e.target.value); changed(); } },
      SHINY_RATES.map(([v, label]) => h('option', { value: v, selected: Number(rando.shinyRate || 0) === v }, label)))));
}

/**
 * Randomization settings of a new room (mutates {@code rando}). Presets first;
 * "Options avancées" opens every option of the randomizer for this generation.
 */
export function randoEditor(rando, gen, onChange) {
  const root = h('div', { class: 'stack l' });
  const ui = { advanced: rando.preset === 'custom', section: SECTIONS[0].id };

  const changed = () => { onChange(rando); draw(); };

  function applyPreset(id) {
    const p = presetById(id);
    rando.preset = id;
    rando.enabled = !p.disables;
    if (!p.disables) rando.settings = sanitizeSettings(p.build(gen), gen);
    changed();
  }

  function draw() {
    const presets = h('div', { class: 'grid3' }, PRESETS.filter((p) => p.id !== 'surprise').map((p) =>
      h('button', { type: 'button', class: ['choice', (rando.preset === p.id || (!rando.enabled && p.disables)) && 'selected'],
        onclick: () => applyPreset(p.id) },
      h('div', null, h('b', null, p.label), h('small', null, p.desc)))));
    const slt = rando.enabled ? h('div', { class: 'card tight' },
      h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Une ROM différente pour chaque joueur'),
        h('small', null, 'Chaque joueur reçoit sa propre randomisation (sa propre seed) au lieu d’une ROM identique pour tous.')),
      switchEl(!!rando.perPlayer, (v) => { rando.perPlayer = v; changed(); })),
      gen >= 3 ? h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Vendeurs secondaires aléatoires'),
        h('small', null, 'Le vendeur principal ne change pas. Jamais de Master Ball ni d’objet qui se revend plus cher qu’il ne s’achète.')),
      switchEl(rando.shops === 'secondary', (v) => { rando.shops = v ? 'secondary' : 'off'; changed(); })) : null,
      h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Seed'),
        h('small', null, 'Le code du tirage : même seed + même jeu = exactement la même ROM. Changez-la pour un autre tirage.')),
      h('input', { class: 'input mono', style: { width: '180px' }, value: rando.seed, oninput: (e) => { rando.seed = e.target.value.replace(/\D/g, ''); onChange(rando); } }),
      h('button', { type: 'button', class: 'btn icon-only ghost', title: 'Nouvelle seed', onclick: () => { rando.seed = randomSeed(); changed(); } }, icon('dice'))),
    ) : null;
    const adv = rando.enabled ? h('div', null,
      h('button', { type: 'button', class: 'btn ghost', onclick: () => { ui.advanced = !ui.advanced; draw(); } },
        icon(ui.advanced ? 'back' : 'settings'), ui.advanced ? 'Masquer les options avancées' : 'Options avancées…'),
      ui.advanced ? advanced() : null) : null;
    const restore = keepScroll(root);
    mount(root, presets, shinyCard(rando, gen, changed), slt, adv);
    restore();
    requestAnimationFrame(restore);
  }

  function advanced() {
    const tabs = h('div', { class: 'tabs' }, SECTIONS.map((s) =>
      h('button', { type: 'button', class: ui.section === s.id ? 'active' : '', onclick: () => { ui.section = s.id; draw(); } }, s.label)));
    const section = SECTIONS.find((s) => s.id === ui.section);
    const body = h('div', { class: 'stack' }, section.groups.map((g) => {
      if (g.minGen && gen < g.minGen) return null;
      const opts = g.options.filter((o) => optionAvailable({ ...o, group: g }, gen) && (!o.when || o.when(rando.settings)));
      const extra = g.special === 'tweaks' ? tweaks() : null;
      if (!opts.length && !extra) return null;
      return h('div', { class: 'card tight' }, h('h3', { class: 'mb' }, g.label),
        g.note ? h('p', { class: 'tiny faint mb' }, g.note) : null,
        h('div', { class: 'list' }, opts.map(optionRow)), extra);
    }));
    return h('div', { class: 'mt' }, tabs, body);
  }

  function optionRow(o) {
    const s = rando.settings;
    const set = (v) => { s[o.key] = v; rando.preset = 'custom'; changed(); };
    let control;
    if (o.type === 'bool') control = switchEl(!!s[o.key], set);
    else if (o.type === 'select') {
      control = h('select', { class: 'input', style: { width: '260px' }, onchange: (e) => {
        const raw = e.target.value;
        set(o.options.some((c) => typeof c[0] === 'number') ? Number(raw) : raw);
      } }, o.options.filter((c) => choiceAvailable(c, gen)).map((c) => h('option', { value: c[0], selected: s[o.key] === c[0] }, c[1])));
    } else {
      control = h('input', { class: 'input', type: 'number', min: o.min, max: o.max, step: o.step, value: s[o.key], style: { width: '110px' },
        onchange: (e) => set(Math.max(o.min, Math.min(o.max, Number(e.target.value) || 0))) });
    }
    // what the option is about, then what the selected choice does
    const help = o.helps ? o.helps[s[o.key]] : null;
    return h('div', { class: 'rule-row', style: o.parent ? { paddingLeft: '18px' } : null },
      h('div', { class: 'grow' }, h('b', null, o.label + (o.unit ? ` (${o.unit})` : '')),
        o.desc ? h('small', null, o.desc) : null,
        help ? h('small', { class: 'choice-help' }, '→ ', help) : null), control);
  }

  function tweaks() {
    const list = TWEAKS.filter((t) => t.gens.includes(gen));
    const set = new Set(rando.miscTweaks || []);
    return h('div', { class: 'list' }, list.map((t) => h('div', { class: 'rule-row' },
      h('div', { class: 'grow' }, h('b', null, t.label), t.desc ? h('small', null, t.desc) : null),
      switchEl(set.has(t.value), (v) => {
        if (v) set.add(t.value); else set.delete(t.value);
        rando.miscTweaks = [...set];
        rando.preset = 'custom';
        changed();
      }))));
  }

  draw();
  return root;
}


/**
 * The server hosted on this PC, as friends join it: the invitation code to
 * send (every address + the certificate), whether the box lets the Internet
 * in, and the addresses themselves.
 */
export function hostAddresses(host, { onInternet = null, onFirewall = null } = {}) {
  const net = host.internet || {};
  const fw = host.firewall || {};
  const nets = host.networks || [];
  const labels = { vpn: 'Par VPN (Tailscale…)', public: 'Par Internet', local: 'Même box / Wi-Fi' };
  const code = host.invitation;
  const copy = (btnEl) => {
    navigator.clipboard?.writeText(code).then(() => { btnEl.textContent = 'Copié ✓'; setTimeout(() => { btnEl.textContent = 'Copier'; }, 1500); });
  };
  const state = {
    open: ['ok', `Accessible depuis Internet : ${net.message || 'port ouvert sur la box'}.`],
    opening: ['', net.message || 'Ouverture du port sur la box…'],
    error: ['warn', net.message || 'La box n’a pas ouvert le port.'],
    off: ['', 'Accessible seulement depuis votre réseau (même box / Wi-Fi).'],
  }[net.state || 'off'];
  // while the box answers, the code would miss the Internet address: friends would get a code that cannot work
  const waiting = net.state === 'opening';
  const fwButton = onFirewall ? h('button', { type: 'button', class: 'btn small primary' }, 'Autoriser Cable Club dans le pare-feu') : null;
  if (fwButton) fwButton.onclick = () => act(fwButton, onFirewall);
  return h('div', { class: 'stack s', style: { marginTop: '6px' } },
    onInternet ? h('label', { class: 'row', style: { gap: '10px' } }, switchEl(!!net.on, onInternet),
      h('span', { class: 'small' }, 'Accessible depuis Internet (Cable Club ouvre le port de votre box tout seul)')) : null,
    h('small', { class: state[0] }, waiting ? '⏳ ' : '', state[1]),
    fw.message ? h('div', { class: 'banner warn', style: { marginTop: '4px' } }, icon('alert'), h('div', { class: 'stack s' },
      h('small', null, fw.message),
      fwButton ? h('div', null, fwButton) : h('small', { class: 'faint' }, 'Réglages > Serveur propose de l’autoriser.'),
      h('small', { class: 'faint' }, 'Windows vous demandera la permission (Oui). Un antivirus avec son propre pare-feu doit aussi laisser passer Cable Club.'))) : null,
    waiting ? h('small', { class: 'faint' }, 'Le code d’invitation apparaît dès que la box a répondu.') : null,
    code && !waiting ? h('div', { class: 'row', style: { gap: '8px', marginTop: '4px' } },
      h('input', { class: 'input mono', readOnly: true, value: code, style: { height: '32px', fontSize: '12px' }, onfocus: (e) => e.target.select() }),
      h('button', { type: 'button', class: 'btn small primary', onclick: (e) => copy(e.currentTarget) }, 'Copier')) : null,
    code && !waiting ? h('small', { class: 'faint' }, net.state === 'open'
      ? 'Envoyez ce code d’invitation à vos amis : ils le collent dans Cable Club à la place d’une adresse. '
        + 'La connexion est chiffrée et Cable Club vérifie que c’est bien votre serveur.'
      : 'Tant que la box n’a pas ouvert le port, ce code ne fonctionne que sur votre réseau (même box), ou en IPv6 si les deux connexions le permettent.') : null,
    nets.length ? h('details', null, h('summary', { class: 'tiny faint', style: { cursor: 'pointer' } }, 'Adresses du serveur'),
      nets.map((n) => h('div', { class: 'tiny faint' }, `${labels[n.kind] || labels.local} : `, h('span', { class: 'mono' }, n.address)))) : null);
}
