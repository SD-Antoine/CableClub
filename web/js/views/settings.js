// Settings: appearance, account, server (join / host), controls, game, data.

import { h, mount, btn, field, act, toast, seg, switchEl, confirmDialog } from '../ui.js';
import { local } from '../api.js';
import { store, refreshState, on } from '../store.js';
import { BUTTONS, ACTIONS, DEFAULT_KEYS, keyLabel, keyName, keymap } from '../game/keys.js';
import { hostAddresses } from './parts.js';
import { updateSettings } from './update.js';

const THEMES = [
  ['onyx', 'Onyx', ['#161616', '#262627', '#8aa4ff']],
  ['blanc', 'Blanc', ['#f3f3f1', '#ffffff', '#3b5bd6']],
  ['nuit', 'Bleu nuit', ['#0e1626', '#1a2840', '#82a8ff']],
];

export function renderSettings(container, { applyTheme }) {
  const page = h('div', { class: 'page narrow' });
  container.appendChild(page);
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
    const user = link.user || {};
    const keys = keymap(cfg);

    const name = h('input', { class: 'input', value: user.name || '', maxlength: 24 });
    const oldPw = h('input', { class: 'input', type: 'password', autocomplete: 'current-password' });
    const newPw = h('input', { class: 'input', type: 'password', autocomplete: 'new-password' });
    const address = h('input', { class: 'input', value: link.server || '', placeholder: 'Code d’invitation (SLT1-…) ou adresse' });

    mount(page,
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Réglages'))),

      section('Apparence', h('div', { class: 'theme-swatches' }, THEMES.map(([id, label, colors]) =>
        h('button', { class: ['swatch', (cfg.theme || 'onyx') === id && 'active'], onclick: async () => { applyTheme(id); await save({ theme: id }); draw(); } },
          h('div', { class: 'preview', style: { background: colors[0] } }, h('i', { style: { background: colors[1] } }), h('i', { style: { background: colors[2], flex: '.4' } })),
          h('span', null, label))))),

      section('Compte', h('div', { class: 'stack' },
        h('p', { class: 'muted small' }, `Connecté en tant que @${user.username || '?'}.`),
        h('div', { class: 'row top' }, h('div', { class: 'grow' }, field('Nom affiché', name)),
          h('div', { style: { paddingTop: '26px' } }, btn('Enregistrer', (e) => act(e.currentTarget, async () => {
            await local.post('/api/me', { name: name.value.trim() });
            await refreshState();
            toast('Nom enregistré.', 'ok');
          })))),
        h('div', { class: 'grid2' }, field('Mot de passe actuel', oldPw), field('Nouveau mot de passe', newPw)),
        h('div', { class: 'row' }, btn('Changer le mot de passe', (e) => act(e.currentTarget, async () => {
          await local.post('/api/me/password', { old: oldPw.value, new: newPw.value });
          oldPw.value = ''; newPw.value = '';
          toast('Mot de passe changé. Vos autres appareils devront se reconnecter.', 'ok');
        })), h('span', { class: 'grow' }),
        btn('Se déconnecter', async (e) => {
          const b = e.currentTarget;
          if (await confirmDialog('Se déconnecter', 'Le jeu en cours sera fermé.')) act(b, async () => { await local.post('/api/auth/logout'); location.hash = '#/'; location.reload(); });
        }, { kind: 'ghost danger', iconName: 'logout' })))),

      section('Serveur', h('div', { class: 'stack' },
        h('div', { class: 'row' }, h('span', { class: ['status-dot', link.connected && 'on'] }),
          h('span', null, link.connected ? 'Connecté à ' : 'Non connecté à '), h('span', { class: 'mono' }, link.server || '—')),
        h('div', { class: 'row top' }, h('div', { class: 'grow' }, field('Code d’invitation ou adresse du serveur', address, 'Changer de serveur vous déconnecte : vos comptes et salles sont propres à chaque serveur.')),
          h('div', { style: { paddingTop: '26px' } }, btn('Utiliser', (e) => act(e.currentTarget, async () => {
            await local.post('/api/server/use', { address: address.value });
            await refreshState();
            location.hash = '#/';
            location.reload();
          })))),
        h('div', { class: 'rule-row' },
          h('div', { class: 'grow' }, h('b', null, 'Héberger le serveur sur ce PC'),
            host.running ? hostAddresses(host, { onInternet: async (v) => {
              try { store.state = await local.post('/api/host/internet', { on: v }); draw(); } catch (err) { toast(err.message, 'danger'); }
            }, onFirewall: async () => {
              store.state = await local.post('/api/host/firewall', {});
              toast('SLT est autorisé dans le pare-feu Windows.', 'ok');
              draw();
            } })
              : h('small', null, 'Ce PC devient le serveur de votre groupe (comptes, salles, journaux). Il doit rester allumé pendant les parties.')),
          switchEl(!!host.running, async (v) => {
            try {
              store.state = await local.post(v ? '/api/host/start' : '/api/host/stop', v ? { port: 47330, use: false } : {});
              draw();
            } catch (err) { toast(err.message, 'danger'); draw(); }
          })))),

      section('Contrôles', h('div', { class: 'stack' },
        h('p', { class: 'muted small' }, 'Cliquez sur une touche puis appuyez sur la nouvelle. Les manettes Xbox (et compatibles) sont reconnues automatiquement.'),
        h('div', { class: 'keymap' }, [...BUTTONS, ...ACTIONS].map((b) => h('div', { class: 'key-row' },
          h('span', { class: 'small' }, b.label),
          h('button', { class: ['kbd', listening === b.id && 'listening'], onclick: () => { listening = b.id; draw(); } },
            listening === b.id ? 'Appuyez…' : keyLabel(keys[b.id]))))),
        h('div', { class: 'row' }, btn('Touches par défaut', async () => { await save({ keys: { ...DEFAULT_KEYS } }); draw(); }, { kind: 'small ghost' }),
          h('span', { class: 'grow' }), h('span', { class: 'small muted' }, 'Manette n°'),
          seg([[0, '1'], [1, '2'], [2, '3'], [3, '4']], Number(cfg.padIndex ?? 0), async (v) => { await save({ padIndex: v }); draw(); })))),

      section('Jeu', h('div', { class: 'list' },
        h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Volume'), h('small', null, `${cfg.volume ?? 80} %`)),
          h('input', { type: 'range', min: 0, max: 100, value: cfg.volume ?? 80, onchange: async (e) => { await save({ volume: Number(e.target.value) }); draw(); } })),
        h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Direct des partenaires'),
          h('small', null, 'Images par seconde envoyées et reçues pour voir les autres joueurs (moins = moins de connexion utilisée).')),
          seg([[0, 'Off'], [2, '2'], [4, '4'], [8, '8']], Number(cfg.liveFps ?? 4), async (v) => { await save({ liveFps: v }); draw(); })),
        h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Image lissée'), h('small', null, 'Désactivé : pixels nets (recommandé).')),
          switchEl(!!cfg.smooth, async (v) => { await save({ smooth: v }); draw(); })),
        h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Pause quand la fenêtre est réduite'),
          h('small', null, 'Le jeu s’arrête tant que SLT est réduit ou caché.')),
          switchEl(cfg.pauseHidden !== false, async (v) => { await save({ pauseHidden: v }); draw(); })))),

      section('Mises à jour', updateSettings(switchEl, async (patch) => { await save(patch); draw(); })),

      section('Données', h('div', { class: 'row' }, h('span', { class: 'muted small grow mono ellipsis' }, store.state?.dataDir || ''),
        btn('Ouvrir le dossier', () => local.post('/api/data/reveal').catch((e) => toast(e.message, 'danger')), { kind: 'small', iconName: 'folder' }))),
      h('p', { class: 'tiny faint center mt-l' }, `SLT ${store.state?.version || ''} · Projet de fans non officiel · Pokémon © Nintendo, Creatures, GAME FREAK`));
  }

  function section(title, body) {
    return h('div', null, h('div', { class: 'section-title' }, h('h2', null, title)), h('div', { class: 'card' }, body));
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
  const off = on('state', () => { if (!listening) draw(); });
  return () => { window.removeEventListener('keydown', onKey, true); off(); };
}
