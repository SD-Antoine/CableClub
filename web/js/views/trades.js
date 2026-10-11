// Trades between the players of a room: offer one of your Pokémon, answer an
// offer with one of yours, follow the transfer (it happens in both games).

import { h, mount, act } from '../ui.js';
import { srv } from '../api.js';

const STEP = {
  offered: 'en attente de réponse', extract: 'transfert en cours (restez hors combat)…',
  swap: 'transfert en cours (restez hors combat)…', done: 'terminé', cancelled: 'annulé',
};

/**
 * The trade box of a running room for one player.
 * @param compact smaller version (side panel of the game)
 */
export function tradeBox(room, userId, { compact = false } = {}) {
  if (room.state !== 'running' || room.members.length < 2) return null;
  const memberName = (id) => room.members.find((x) => x.userId === id)?.name || '?';
  const recOf = (id, uid) => room.members.find((x) => x.userId === id)?.mons?.find((r) => r.uid === uid);
  const label = (r) => (r ? (r.nickname || r.speciesName || `#${r.species}`) + (r.shiny ? ' ✨' : '') : 'Pokémon');
  const me = room.members.find((x) => x.userId === userId);

  /** My Pokémon that may be traded now: alive, in the party, shiny until both players beat the League. */
  const tradables = (partnerId) => {
    const partner = room.members.find((x) => x.userId === partnerId);
    const league = me?.status === 'champion' && partner?.status === 'champion';
    const inParty = new Set((me?.live?.team || []).map((p) => p.uid));
    return (me?.mons || []).filter((r) => r.status === 'alive' && r.origin !== 'existing' && inParty.has(r.uid) && (league || r.shiny));
  };
  const action = (body, button) => act(button, () => srv.post(`/rooms/${room.id}/trades`, body));
  const button = (text, body, kind = 'small') => {
    const b = h('button', { type: 'button', class: `btn ${kind}` }, text);
    b.onclick = () => action(body, b);
    return b;
  };

  const trades = room.trades || [];
  const active = trades.find((t) => !['done', 'cancelled'].includes(t.state) && (t.from === userId || t.to === userId));
  let body;
  if (active && active.state === 'offered' && active.to === userId) {
    const choices = tradables(active.from);
    body = h('div', { class: 'stack s' },
      h('div', { class: 'small' }, h('b', null, memberName(active.from)), ' vous propose ', h('b', null, label(recOf(active.from, active.fromUid))),
        '. Contre lequel de vos Pokémon ?'),
      choices.length ? h('div', { class: 'row wrap', style: { gap: '6px' } },
        choices.map((r) => button(label(r), { action: 'answer', trade: active.id, accept: true, uid: r.uid })))
        : h('small', { class: 'warn' }, 'Aucun de vos Pokémon d’équipe ne peut être échangé maintenant.'),
      h('div', null, button('Refuser', { action: 'answer', trade: active.id, accept: false }, 'small ghost')));
  } else if (active) {
    const other = active.from === userId ? active.to : active.from;
    body = h('div', { class: 'stack s' },
      h('div', { class: 'small' }, 'Échange avec ', h('b', null, memberName(other)), ' : ', label(recOf(active.from, active.fromUid)),
        active.toUid ? [' ⇄ ', label(recOf(active.to, active.toUid))] : null),
      h('div', { class: 'tiny muted' }, STEP[active.state] || active.state),
      ['offered', 'extract'].includes(active.state) ? h('div', null, button('Annuler', { action: 'cancel', trade: active.id }, 'small ghost')) : null);
  } else {
    const partners = room.members.filter((x) => x.userId !== userId);
    const partner = h('select', { class: 'input', style: { width: compact ? '100%' : '160px' } }, partners.map((x) => h('option', { value: x.userId }, x.name)));
    const pick = h('select', { class: 'input', style: { width: compact ? '100%' : '200px' } });
    const fill = () => {
      const list = tradables(partner.value);
      mount(pick, list.length ? list.map((r) => h('option', { value: r.uid }, label(r))) : h('option', { value: '' }, 'Aucun Pokémon échangeable'));
    };
    partner.onchange = fill;
    fill();
    const go = h('button', { type: 'button', class: 'btn small primary' }, 'Proposer');
    go.onclick = () => { if (pick.value) action({ action: 'offer', to: partner.value, uid: pick.value }, go); };
    body = compact ? h('div', { class: 'stack s' }, partner, pick, h('div', null, go))
      : h('div', { class: 'row wrap', style: { gap: '8px' } }, h('span', { class: 'small' }, 'Proposer à'), partner, pick, go);
  }
  const recent = trades.filter((t) => ['done', 'cancelled'].includes(t.state)).slice(-2);
  return h('div', { class: compact ? 'trade-box' : 'card mb' }, h(compact ? 'b' : 'h3', { class: compact ? '' : 'mb' }, '🔁 Échanges'), body,
    !compact && recent.length ? h('div', { class: 'tiny muted mt-s' }, recent.map((t) => h('div', null, `${memberName(t.from)} ⇄ ${memberName(t.to)} : ${STEP[t.state]}`))) : null,
    compact ? null : h('p', { class: 'tiny faint mt-s' }, 'Les Pokémon échangés quittent leur lien. Tant que les deux joueurs n’ont pas battu le Maître, seuls les chromatiques s’échangent. L’échange se fait dans les deux jeux en quelques secondes : restez hors combat.'));
}
