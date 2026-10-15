// Safety of the players: reporting a message or a player, deleting one's account, and for the moderators the
// list of reports (what the stores ask of an app with a chat and accounts).

import { h, mount, btn, modal, act, toast, errorToast, field, confirmDialog, dateTime } from '../ui.js';
import { local, srv, openPage } from '../api.js';
import { store, refreshState } from '../store.js';

const REASONS = [
  ['harassment', 'Harcèlement ou insultes'],
  ['hate', 'Propos haineux ou discriminatoires'],
  ['sexual', 'Contenu sexuel ou choquant'],
  ['spam', 'Spam ou publicité'],
  ['cheat', 'Triche ou non-respect des règles d’une salle'],
  ['other', 'Autre'],
];

/** Reports a player, or one of their messages ({ ch, msg, text } given). */
export function openReport({ userId, name, ch = null, msg = null, text = null }) {
  const reason = h('select', { class: 'input' }, REASONS.map(([v, l]) => h('option', { value: v }, l)));
  const details = h('textarea', { class: 'input', rows: 3, maxlength: 250, placeholder: 'Facultatif : ce qui s’est passé.' });
  modal({
    title: msg ? 'Signaler un message' : `Signaler ${name}`,
    body: h('div', { class: 'stack' },
      text ? h('blockquote', { class: 'report-quote' }, h('b', null, name), ' : ', text) : null,
      field('Motif', reason),
      field('Précisions', details),
      h('p', { class: 'tiny faint' }, 'Les modérateurs du serveur reçoivent votre signalement avec ',
        msg ? 'le message' : 'le profil', '. La personne signalée ne sait pas qui l’a signalée. ',
        'Pour ne plus voir ses messages, vous pouvez aussi la bloquer depuis son profil.')),
    actions: [
      { label: 'Annuler', onClick: (close) => close() },
      { label: 'Signaler', kind: 'danger solid', onClick: async (close) => {
        const label = REASONS.find(([v]) => v === reason.value)?.[1] || '';
        try {
          await srv.post('/report', { userId, ch, msg, reason: details.value.trim() ? `${label} — ${details.value.trim()}` : label });
          close();
          toast('Merci : les modérateurs ont reçu votre signalement.', 'ok');
        } catch (e) { errorToast(e); }
      } },
    ],
  });
}

/** Deletes my account, after my password (the server checks it). */
export function openDeleteAccount() {
  const pw = h('input', { class: 'input', type: 'password', autocomplete: 'current-password' });
  modal({
    title: 'Supprimer mon compte',
    body: h('div', { class: 'stack' },
      h('p', null, 'Votre compte est supprimé définitivement du serveur, tout de suite :'),
      h('ul', { class: 'small muted' },
        h('li', null, 'votre profil, votre photo, vos amis et vos messages (privés et publics) ;'),
        h('li', null, 'vos salles pas encore lancées et vos sauvegardes envoyées au serveur ;'),
        h('li', null, 'dans les aventures déjà lancées avec d’autres joueurs, votre place reste pour eux, au nom de « Compte supprimé ».')),
      h('p', { class: 'small muted' }, 'Vos ROMs, vos parties et vos réglages sur cet appareil ne sont pas touchés. ',
        'Les copies de sauvegarde du serveur sont effacées au bout de 60 jours au plus.'),
      field('Mot de passe', pw, 'Pour confirmer que c’est bien vous.')),
    actions: [
      { label: 'Annuler', onClick: (close) => close() },
      { label: 'Supprimer définitivement', kind: 'danger solid', onClick: async (close) => {
        try {
          await local.post('/api/account/delete', { password: pw.value });
          close();
          toast('Votre compte a été supprimé.', 'ok');
          location.hash = '#/';
          setTimeout(() => location.reload(), 800);
        } catch (e) { errorToast(e); }
      } },
    ],
  });
}

/** A moderator deletes an account (serious or repeated breaches of the terms of use). */
export async function adminDeleteAccount(p, onDone) {
  if (!await confirmDialog(`Supprimer le compte de ${p.name}`,
    `Le compte @${p.username} est supprimé définitivement (profil, amis, messages). À réserver aux manquements graves ou répétés aux conditions d’utilisation.`,
    { okLabel: 'Supprimer le compte', danger: true })) return;
  try {
    await srv.post(`/admin/users/${p.id}/delete`);
    toast(`Compte de ${p.name} supprimé.`, 'ok');
    if (onDone) onDone();
  } catch (e) { errorToast(e); }
}

/** The legal links (terms, privacy, deleting the account from the web). */
export function legalLinks() {
  const link = (label, page) => h('a', { href: '#', onclick: (e) => { e.preventDefault(); openPage(page).catch(errorToast); } }, label);
  return h('p', { class: 'tiny faint' }, link('Conditions d’utilisation', 'conditions'), ' · ',
    link('Politique de confidentialité', 'confidentialite'), ' · ', link('Code source', 'source'));
}

/** For the moderators: the reports, newest first, with what can be done. */
export function reportsCard() {
  const box = h('div', { class: 'card stack' }, h('p', { class: 'muted small' }, 'Chargement des signalements…'));
  let showDone = false;

  async function load() {
    let list;
    try { list = (await srv.get('/admin/reports')).reports || []; } catch (e) { mount(box, h('p', { class: 'muted small' }, e.message)); return; }
    const shown = list.filter((r) => showDone || !r.done);
    mount(box,
      h('div', { class: 'row' }, h('b', { class: 'grow' }, `${list.filter((r) => !r.done).length} signalement(s) à traiter`),
        btn(showDone ? 'Masquer les traités' : 'Voir aussi les traités', () => { showDone = !showDone; load(); }, { kind: 'ghost small' })),
      shown.length ? shown.map((r) => h('div', { class: ['report-row', r.done && 'done'] },
        h('div', { class: 'small' }, h('b', null, r.userName), h('span', { class: 'faint' }, ` @${r.username} · signalé par ${r.byName} · ${dateTime(r.at)}`)),
        r.text ? h('blockquote', { class: 'report-quote' }, r.text) : h('div', { class: 'tiny faint' }, 'Profil signalé'),
        r.reason ? h('div', { class: 'tiny' }, r.reason) : null,
        h('div', { class: 'row wrap' },
          btn('Profil', async () => { const { openProfile } = await import('./profile.js'); openProfile(r.userId); }, { kind: 'ghost small', iconName: 'user' }),
          // moderators delete messages of the general chat (rooms and private messages are not theirs to open)
          r.ch === 'global' && r.msgId ? btn('Supprimer le message', (e) => act(e.currentTarget, async () => {
            await srv.post(`/chat/global/${r.msgId}/delete`);
            toast('Message supprimé.', 'ok');
          }), { kind: 'ghost small danger' }) : null,
          btn('Rendre muet 24 h', (e) => act(e.currentTarget, async () => {
            await srv.post('/chat/mute', { userId: r.userId, minutes: 1440 }); toast(`${r.userName} est muet 24 h dans le chat général.`, 'ok');
          }), { kind: 'ghost small' }),
          h('span', { class: 'grow' }),
          btn(r.done ? 'Rouvrir' : 'Traité', (e) => act(e.currentTarget, async () => {
            await srv.post(`/admin/reports/${r.id}/done`, { done: !r.done }); await refreshState(); load();
          }), { kind: r.done ? 'ghost small' : 'small', iconName: r.done ? null : 'check' }))))
        : h('p', { class: 'muted small' }, 'Rien à traiter.'));
  }

  load();
  return box;
}

/** The number of open reports, for the moderators (0 otherwise). */
export const openReports = () => (store.state?.link?.user?.admin ? store.state.link.user.reports || 0 : 0);
