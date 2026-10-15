// First launch: choose the SLT server (join one or host it here), then log in or create an account.

import { h, mount, icon, field, seg, act, errorToast } from '../ui.js';
import { local, openPage } from '../api.js';
import { store, refreshState, on } from '../store.js';
import { hostAddresses } from './parts.js';
import { mobile } from '../platform.js';

// the official Cable Club server: a real (Let's Encrypt) certificate, nothing to remember or paste
const OFFICIAL = 'https://play.cableclub.app:443';

export function renderWelcome(container, onDone) {
  const ui = { step: store.state?.link?.server ? 'account' : 'server', mode: 'official', tab: 'login', hosted: false, error: null };
  // the hosted server's banner follows the box live, without touching what is being typed
  let fillBanner = null;
  const off = on('host', () => { if (fillBanner) fillBanner(); });

  function draw() {
    mount(container, h('div', { class: 'welcome' }, h('div', { class: 'welcome-box' },
      h('div', { class: 'logo' }, h('img', { src: 'img/logo.svg', alt: '' }),
        h('div', null, h('b', null, 'Cable Club'), h('small', null, 'Pokémon à plusieurs — générations 1 à 5'))),
      ui.step === 'server' ? serverStep() : accountStep())));
  }

  function serverStep() {
    const address = h('input', { class: 'input big', placeholder: 'Code d’invitation (SLT1-…) ou adresse', value: ui.code || '' });
    const internet = h('input', { type: 'checkbox', checked: ui.internet !== false, onchange: (e) => { ui.internet = e.target.checked; } });
    const go = h('button', { class: 'btn primary big block', type: 'submit' }, 'Continuer');
    const form = h('form', { class: 'stack l', onsubmit: (e) => {
      e.preventDefault();
      act(go, async () => {
        ui.error = null;
        if (ui.mode === 'host') {
          const s = await local.post('/api/host/start', { port: 47330, use: true, internet: ui.internet !== false });
          store.state = s;
          ui.hosted = true;
        } else {
          ui.code = ui.mode === 'official' ? '' : address.value;
          try {
            const r = await local.post('/api/server/use', { address: ui.mode === 'official' ? OFFICIAL : address.value });
            store.state = r.state;
          } catch (err) {
            // the diagnosis is long: shown under the field rather than in a passing notification
            // (the one about hosts and invitation codes does not apply to the official server)
            ui.error = ui.mode === 'official'
              ? 'Le serveur Cable Club (play.cableclub.app) ne répond pas. Vérifiez votre connexion à Internet, puis réessayez dans un instant.'
              : err.message;
            draw();
            return;
          }
        }
        ui.step = 'account';
        draw();
      });
    } },
      h('div', null, h('h1', null, 'Bienvenue'), h('p', { class: 'muted mt-s' },
        'Les comptes, les amis et les salles vivent sur un serveur. Le serveur Cable Club convient à presque tout le monde : un seul compte pour retrouver tous vos amis.')),
      h('div', { class: 'stack' },
        h('button', { type: 'button', class: ['choice', ui.mode === 'official' && 'selected'], onclick: () => { ui.mode = 'official'; draw(); } },
          icon('server'), h('div', null, h('b', null, 'Serveur Cable Club (recommandé)'), h('small', null, 'Le serveur officiel : un seul compte, vos amis et vos salles où que vous soyez.'))),
        h('button', { type: 'button', class: ['choice', ui.mode === 'join' && 'selected'], onclick: () => { ui.mode = 'join'; draw(); } },
          icon('server'), h('div', null, h('b', null, 'Rejoindre un autre serveur'), h('small', null, 'Un ami héberge son propre serveur : collez le code d’invitation qu’il vous a envoyé.'))),
        // hosting a server on this PC: an advanced choice, hidden until asked for (a phone does not host)
        mobile ? null : ui.advanced || ui.mode === 'host'
          ? h('button', { type: 'button', class: ['choice', ui.mode === 'host' && 'selected'], onclick: () => { ui.mode = 'host'; draw(); } },
            icon('home'), h('div', null, h('b', null, 'Héberger un serveur sur ce PC'), h('small', null, 'Avancé : ce PC devient le serveur de votre groupe. Il doit rester allumé, Cable Club ouvert, pendant les parties.')))
          : h('a', { href: '#', class: 'small muted', onclick: (e) => { e.preventDefault(); ui.advanced = true; draw(); } }, 'Options avancées')),
      ui.mode === 'join' ? field('Code d’invitation ou adresse du serveur', address, 'L’hôte trouve son code dans Réglages > Serveur.') : null,
      (ui.mode === 'join' || ui.mode === 'official') && ui.error ? h('div', { class: 'banner warn' }, icon('alert'), h('div', { class: 'small' }, ui.error)) : null,
      ui.mode === 'host' ? h('label', { class: 'switch' }, internet, h('span', { class: 'track' }),
        h('span', { class: 'small' }, 'Accessible depuis Internet — pour des amis qui jouent de chez eux (Cable Club ouvre le port de votre box)')) : null,
      go);
    return form;
  }

  function accountStep() {
    const link = store.state?.link || {};
    // what is typed survives a switch between « J'ai un compte » and « Nouveau compte »
    const user = h('input', { class: 'input', autocomplete: 'username', placeholder: 'ex. sacha', value: ui.user || '', oninput: (e) => { ui.user = e.target.value; } });
    const pass = h('input', { class: 'input', type: 'password', autocomplete: ui.tab === 'login' ? 'current-password' : 'new-password',
      value: ui.pass || '', oninput: (e) => { ui.pass = e.target.value; } });
    const name = h('input', { class: 'input', placeholder: 'Nom visible par vos amis (facultatif)', value: ui.shownName || '', oninput: (e) => { ui.shownName = e.target.value; } });
    const submit = h('button', { class: 'btn primary big block', type: 'submit' }, ui.tab === 'login' ? 'Se connecter' : 'Créer mon compte');
    // the terms of use and the privacy policy of the server (its pages /conditions and /confidentialite)
    const terms = h('input', { type: 'checkbox', checked: !!ui.terms, onchange: (e) => { ui.terms = e.target.checked; } });
    const page = (label, which) => h('a', { href: '#', onclick: (e) => { e.preventDefault(); openPage(which).catch(errorToast); } }, label);
    let hosted = null;
    if (ui.hosted) {
      hosted = h('div');
      fillBanner = () => {
        const host = store.state?.host || {};
        mount(hosted, host.running ? h('div', { class: 'banner' }, icon('info'), h('div', null, h('b', null, 'Serveur démarré. '),
          'Créez votre compte ici. Vos amis, eux, colleront ce code d’invitation dans Cable Club :', hostAddresses(host, {
            onFirewall: async () => { store.state = await local.post('/api/host/firewall', {}); fillBanner(); },
          }))) : null);
      };
      fillBanner();
    }
    return h('form', { class: 'stack l', onsubmit: (e) => {
      e.preventDefault();
      act(submit, async () => {
        const path = ui.tab === 'login' ? '/api/auth/login' : '/api/auth/register';
        if (ui.tab === 'register' && !terms.checked) throw new Error('Pour créer un compte, acceptez les conditions d’utilisation.');
        await local.post(path, { username: user.value.trim(), password: pass.value, name: name.value.trim() || undefined,
          terms: ui.tab === 'register' ? true : undefined });
        await refreshState();
        onDone();
      });
    } },
      h('div', null, h('h1', null, ui.tab === 'login' ? 'Connexion' : 'Créer un compte'),
        h('p', { class: 'muted mt-s small' }, 'Serveur : ', h('span', { class: 'mono' }, link.server || '?'), ' · ',
          h('a', { href: '#', onclick: (e) => { e.preventDefault(); ui.step = 'server'; draw(); } }, 'changer'))),
      hosted,
      seg([['login', 'J’ai un compte'], ['register', 'Nouveau compte']], ui.tab, (v) => { ui.tab = v; draw(); }),
      field('Identifiant', user, ui.tab === 'register' ? '3 à 20 caractères : lettres, chiffres, « . », « - », « _ ».' : null),
      field('Mot de passe', pass, ui.tab === 'register' ? '8 caractères minimum.' : null),
      ui.tab === 'register' ? field('Nom affiché', name) : null,
      ui.tab === 'register' ? h('label', { class: 'check-line small' }, terms,
        h('span', null, 'J’ai 13 ans ou plus et j’accepte les ', page('conditions d’utilisation', 'conditions'),
          ' du serveur. Ma vie privée : ', page('politique de confidentialité', 'confidentialite'), '.')) : null,
      submit);
  }

  draw();
  return () => off();
}

