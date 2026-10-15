// Settings: one page for each entry of the « Réglages » menu of the side rail (game, controls, personalisation,
// server, update, data). The account (password, logout) is on the profile page. On the phone the pages are
// reached from « Moi », and say what differs there (touch buttons, no hosting, no file explorer).

import { h, mount, btn, field, act, toast, seg, switchEl, keepScroll, icon } from '../ui.js';
import { local } from '../api.js';
import { store, refreshState, on } from '../store.js';
import { BUTTONS, ACTIONS, DEFAULT_KEYS, keyLabel, keyName, keymap } from '../game/keys.js';
import { hostAddresses } from './parts.js';
import { updateSettings } from './update.js';
import { mobile } from '../platform.js';

const THEMES = [
  ['onyx', 'Onyx', ['#161616', '#262627', '#8aa4ff']],
  ['blanc', 'Blanc', ['#f3f3f1', '#ffffff', '#3b5bd6']],
  ['nuit', 'Bleu nuit', ['#0e1626', '#1a2840', '#82a8ff']],
];

/** The menu of the side rail: a page each, « Autres » only groups two of them. */
export const SETTINGS_MENU = [
  { id: 'jeu', label: 'Jeu', about: 'Le son, l’image et le comportement du jeu pendant la partie.' },
  { id: 'controles', label: 'Contrôles', about: mobile ? 'Les boutons à l’écran, la manette et le clavier.' : 'Les touches du clavier et la manette.' },
  { id: 'personnalisation', label: 'Personnalisation', about: 'L’arrière-plan et les couleurs de l’interface.' },
  { id: 'serveur', label: 'Serveur', about: mobile ? 'Le serveur auquel vous êtes connecté.' : 'Le serveur auquel vous êtes connecté, ou celui que ce PC héberge.' },
  { id: 'autres', label: 'Autres', children: [
    { id: 'mise-a-jour', label: 'Mise à jour', about: 'La version de Cable Club et sa recherche de mises à jour.' },
    { id: 'donnees', label: 'Données', about: 'Où Cable Club garde vos salles, vos sauvegardes et vos ROMs.' },
  ] },
];

const PAGES = SETTINGS_MENU.flatMap((m) => m.children || [m]);

/** The page shown for an address of the menu (« Autres » alone, or an unknown one: the first page of the group, or the first). */
export function settingsPage(id) {
  return PAGES.find((p) => p.id === id) || (id === 'autres' ? PAGES.find((p) => p.id === 'mise-a-jour') : PAGES[0]);
}

export function renderSettings(container, { applyTheme, tab }) {
  const page = h('div', { class: 'page narrow' });
  container.appendChild(page);
  const current = settingsPage(tab);
  let listening = null;

  const save = async (patch) => {
    try {
      store.state = await local.post('/api/config', patch);
    } catch (e) { toast(e.message, 'danger'); }
  };

  function draw() {
    const cfg = store.state?.config || {};
    const link = store.state?.link || {};
    const host = store.state?.host || {};
    const keys = keymap(cfg);
    const address = h('input', { class: 'input', value: link.server || '', placeholder: 'Code d’invitation (SLT1-…) ou adresse' });

    const pages = {
      jeu: () => section('Jeu', h('div', { class: 'list' },
        h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Volume'), h('small', { class: 'vol-label' }, `${cfg.volume ?? 80} %`)),
          h('input', { type: 'range', class: 'vol-range', min: 0, max: 100, value: cfg.volume ?? 80, style: { width: '160px', '--fill': `${cfg.volume ?? 80}%` },
            oninput: (e) => { e.target.style.setProperty('--fill', `${e.target.value}%`); e.target.closest('.rule-row').querySelector('.vol-label').textContent = `${e.target.value} %`; },
            onchange: async (e) => { await save({ volume: Number(e.target.value) }); draw(); } })),
        mobile ? h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Partager mon jeu'),
          h('small', null, 'Images par seconde envoyées pour que les joueurs sur PC voient votre partie (moins = moins de données mobiles). Le téléphone n’affiche pas les jeux des autres.')),
        seg([[0, 'Off'], [2, '2'], [4, '4']], Number(cfg.liveFps ?? 4), async (v) => { await save({ liveFps: v }); draw(); }))
          : h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Direct des partenaires'),
            h('small', null, 'Images par seconde envoyées et reçues pour voir les autres joueurs (moins = moins de connexion utilisée).')),
          seg([[0, 'Off'], [2, '2'], [4, '4'], [8, '8']], Number(cfg.liveFps ?? 4), async (v) => { await save({ liveFps: v }); draw(); })),
        h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Image lissée'), h('small', null, 'Désactivé : pixels nets (recommandé).')),
          switchEl(!!cfg.smooth, async (v) => { await save({ smooth: v }); draw(); })),
        h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, mobile ? 'Pause quand Cable Club passe en arrière-plan' : 'Pause quand la fenêtre est réduite'),
          h('small', null, mobile ? 'Le jeu s’arrête quand vous changez d’application ou éteignez l’écran.' : 'Le jeu s’arrête tant que Cable Club est réduit ou caché.')),
          switchEl(cfg.pauseHidden !== false, async (v) => { await save({ pauseHidden: v }); draw(); })))),

      controles: () => (mobile ? phoneControls(cfg, keys) : section('Contrôles', h('div', { class: 'stack' },
        h('p', { class: 'muted small' }, 'Cliquez sur une touche puis appuyez sur la nouvelle. Les manettes Xbox (et compatibles) sont reconnues automatiquement.'),
        h('div', { class: 'keymap' }, [...BUTTONS, ...ACTIONS].map((b) => h('div', { class: 'key-row' },
          h('span', { class: 'small' }, b.label),
          h('button', { type: 'button', class: ['kbd', listening === b.id && 'listening'], onclick: () => { listening = b.id; draw(); } },
            listening === b.id ? 'Appuyez…' : keyLabel(keys[b.id]))))),
        h('div', { class: 'row' }, btn('Touches par défaut', async () => { await save({ keys: { ...DEFAULT_KEYS } }); draw(); }, { kind: 'small ghost' }),
          h('span', { class: 'grow' }), h('span', { class: 'small muted' }, 'Manette n°'),
          seg([[0, '1'], [1, '2'], [2, '3'], [3, '4']], Number(cfg.padIndex ?? 0), async (v) => { await save({ padIndex: v }); draw(); }))))),

      personnalisation: () => section('Arrière-plan', h('div', { class: 'theme-swatches' }, THEMES.map(([id, label, colors]) =>
        h('button', { type: 'button', class: ['swatch', (cfg.theme || 'onyx') === id && 'active'], onclick: async () => { applyTheme(id); await save({ theme: id }); draw(); } },
          h('div', { class: 'preview', style: { background: colors[0] } }, h('i', { style: { background: colors[1] } }), h('i', { style: { background: colors[2], flex: '.4' } })),
          h('span', null, label))))),

      serveur: () => section('Serveur', h('div', { class: 'stack' },
        h('div', { class: 'row' }, h('span', { class: ['status-dot', link.connected && 'on'] }),
          h('span', null, link.connected ? 'Connecté à ' : 'Non connecté à '), h('span', { class: 'mono' }, link.server || '—')),
        h('div', { class: 'row top' }, h('div', { class: 'grow' }, field('Code d’invitation ou adresse du serveur', address, 'Changer de serveur vous déconnecte : vos comptes et salles sont propres à chaque serveur.')),
          h('div', { style: { paddingTop: '26px' } }, btn('Utiliser', (e) => act(e.currentTarget, async () => {
            await local.post('/api/server/use', { address: address.value });
            await refreshState();
            location.hash = '#/';
            location.reload();
          })))),
        // advanced: off unless the player turns it on (the Cable Club server needs nothing on this PC); never on a phone
        mobile ? h('p', { class: 'small muted' }, 'Un téléphone ne peut pas héberger de serveur : il se met en veille, Android ferme les applications en arrière-plan et les réseaux mobiles n’acceptent pas les connexions entrantes. Utilisez le serveur Cable Club, ou celui d’un ami sur PC.') : h('details', { class: 'advanced', open: !!host.running }, h('summary', { class: 'small muted' }, 'Avancé'),
          h('div', { class: 'rule-row' },
            h('div', { class: 'grow' }, h('b', null, 'Héberger un serveur sur ce PC'),
              host.running ? hostAddresses(host, { onInternet: async (v) => {
                try { store.state = await local.post('/api/host/internet', { on: v }); draw(); } catch (err) { toast(err.message, 'danger'); }
              }, onFirewall: async () => {
                store.state = await local.post('/api/host/firewall', {});
                toast('Cable Club est autorisé dans le pare-feu Windows.', 'ok');
                draw();
              } })
                : h('small', null, 'Désactivé par défaut. Ce PC devient le serveur de votre groupe (comptes, salles, journaux) : il doit rester allumé, Cable Club ouvert, pendant les parties.')),
            switchEl(!!host.running, async (v) => {
              try {
                store.state = await local.post(v ? '/api/host/start' : '/api/host/stop', v ? { port: 47330, use: false } : {});
                draw();
              } catch (err) { toast(err.message, 'danger'); draw(); }
            }))))),

      'mise-a-jour': () => section('Mise à jour', updateSettings(switchEl, async (patch) => { await save(patch); draw(); })),

      donnees: () => section('Données', mobile ? h('div', { class: 'stack' }, h('span', { class: 'muted small mono', style: { overflowWrap: 'anywhere' } }, store.state?.dataDir || ''),
        h('p', { class: 'small muted' }, 'Ce dossier est visible depuis un ordinateur branché en USB (Android > data > app.cableclub). Il est effacé si vous désinstallez Cable Club : vos sauvegardes de cartouche restent copiées sur le serveur.'))
        : h('div', { class: 'row' }, h('span', { class: 'muted small grow mono ellipsis' }, store.state?.dataDir || ''),
          btn('Ouvrir le dossier', () => local.post('/api/data/reveal').catch((e) => toast(e.message, 'danger')), { kind: 'small', iconName: 'folder' }))),
    };

    // the page is drawn again after each change (a key given, a switch…): the scrolling stays where it was
    const restore = keepScroll(page);
    mount(page,
      h('div', { class: 'page-head' }, h('div', { class: mobile ? 'row top' : null },
        mobile ? h('a', { class: 'btn ghost icon-only', href: '#/moi', title: 'Retour' }, icon('back')) : null,
        h('div', null, h('h1', null, current.label), h('p', null, current.about)))),
      pages[current.id](),
      h('p', { class: 'tiny faint center mt-l' }, `Cable Club ${store.state?.version || ''} · Projet de fans non officiel · Pokémon © Nintendo, Creatures, GAME FREAK`));
    restore();
  }

  /** The phone: the touch buttons, the vibrations, the gamepad, and the keyboard folded away. */
  function phoneControls(cfg, keys) {
    return section('Contrôles', h('div', { class: 'list' },
      h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Boutons à l’écran'),
        h('small', null, 'Auto : cachés tant qu’une manette est connectée.')),
      seg([['auto', 'Auto'], ['always', 'Toujours'], ['never', 'Jamais']], cfg.touchControls || 'auto', async (v) => { await save({ touchControls: v }); draw(); })),
      h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Vibrations'), h('small', null, 'Une petite vibration à chaque bouton touché.')),
        switchEl(cfg.haptics !== false, async (v) => { await save({ haptics: v }); draw(); })),
      h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Manette'),
        h('small', null, 'Les manettes Bluetooth ou USB sont reconnues en jeu : boutons à la place de ceux de la console (celui du bas est B), gâchette droite = avance rapide.')),
      seg([[0, '1'], [1, '2'], [2, '3'], [3, '4']], Number(cfg.padIndex ?? 0), async (v) => { await save({ padIndex: v }); draw(); })),
      h('details', { class: 'advanced', open: !!listening }, h('summary', { class: 'small muted' }, 'Clavier'),
        h('p', { class: 'muted small mb' }, 'Pour un clavier branché au téléphone : touchez une touche ci-dessous puis appuyez sur la nouvelle.'),
        h('div', { class: 'keymap' }, BUTTONS.map((b) => h('div', { class: 'key-row' },
          h('span', { class: 'small' }, b.label),
          h('button', { type: 'button', class: ['kbd', listening === b.id && 'listening'], onclick: () => { listening = b.id; draw(); } },
            listening === b.id ? 'Appuyez…' : keyLabel(keys[b.id]))))))));
  }

  // a section titled like the page (the heading above says it already) has no title of its own
  function section(title, body) {
    return h('div', null, title === current.label ? null : h('div', { class: 'section-title' }, h('h2', null, title)), h('div', { class: 'card' }, body));
  }

  const onKey = async (e) => {
    if (!listening) return;
    e.preventDefault();
    e.stopPropagation();
    const id = listening;
    listening = null;
    if (e.key === 'Escape') { draw(); return; }
    const keys = keymap(store.state?.config);
    const k = keyName(e);
    for (const other of Object.keys(keys)) if (keys[other] === k) keys[other] = '';
    keys[id] = k;
    await save({ keys });
    draw();
  };
  window.addEventListener('keydown', onKey, true);
  draw();
  // news of the application (connection, update…) redraws the page, unless a key is awaited or something is being typed
  const off = on('state', () => {
    const typing = page.contains(document.activeElement) && /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName);
    if (!listening && !typing) draw();
  });
  return () => { window.removeEventListener('keydown', onKey, true); off(); };
}
