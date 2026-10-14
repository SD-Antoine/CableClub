// Profiles: a player's card (any name or picture opens it) and the page where
// the player edits theirs (picture, name, bio, favourite game and Pokémon).

import { h, mount, btn, avatar, modal, act, toast, errorToast, field, confirmDialog, dateTime, keepScroll } from '../ui.js';
import { local, srv } from '../api.js';
import { store, refreshState, refreshFriends, me } from '../store.js';
import { GAMES, gameName } from '../data/games.js';

const BIO_MAX = 160;

/** The profile card of a player, in a dialog. */
export async function openProfile(userId) {
  let p;
  try { p = await srv.get(`/users/${encodeURIComponent(userId)}`); } catch (e) { errorToast(e); return; }
  const self = me();
  const body = h('div');
  const dlg = modal({ title: 'Profil', body });

  function draw() {
    const sl = p.soullink || {};
    const fav = [p.favGame ? ['Jeu favori', GAMES[p.favGame] ? gameName(p.favGame) : p.favGame] : null,
      p.favPokemon ? ['Pokémon favori', p.favPokemon] : null].filter(Boolean);
    mount(body, h('div', { class: 'profile-card' },
      h('div', { class: 'profile-head' }, avatar(p.name, 2, { size: 'xl', id: null, pic: 0 }),
        h('div', { class: 'grow' }, h('h2', null, p.name), h('div', { class: 'muted small' }, `@${p.username}`),
          h('div', { class: 'tiny mt-s' }, h('span', { class: ['status-dot', p.online && 'on'] }), ' ', p.online ? 'En ligne' : 'Hors ligne',
            p.createdAt ? h('span', { class: 'faint' }, ` · membre depuis le ${new Date(p.createdAt).toLocaleDateString('fr-FR')}`) : null))),
      p.bio ? h('p', { class: 'profile-bio' }, p.bio) : h('p', { class: 'faint small' }, 'Pas encore de bio.'),
      fav.length ? h('div', { class: 'profile-favs' }, fav.map(([k, v]) => h('div', null, h('small', null, k), h('b', null, v)))) : null,
      h('div', { class: 'stat-nums three' },
        h('div', null, h('b', null, sl.played || 0), h('small', null, 'Soul Link joués')),
        h('div', null, h('b', null, sl.won || 0), h('small', null, 'réussis')),
        h('div', null, h('b', null, sl.running || 0), h('small', null, 'en cours'))),
      h('div', { class: 'row wrap' }, actions())));
    // the big picture is built here (the small helper opens profiles, not wanted inside one)
    const pic = body.querySelector('.avatar.xl');
    if (p.avatar && pic) mount(pic, h('img', { src: `/api/avatar/${encodeURIComponent(p.id)}?v=${p.avatar}`, alt: '' }));
  }

  function actions() {
    if (p.friend === 'me') return [btn('Modifier mon profil', () => { dlg.close(); location.hash = '#/profil'; }, { kind: 'primary', iconName: 'edit' })];
    const out = [];
    if (p.friend === 'friends') {
      out.push(btn('Message privé', () => { dlg.close(); location.hash = `#/chat/dm-${p.id}`; }, { kind: 'primary', iconName: 'chat' }));
      out.push(btn('Retirer des amis', (e) => act(e.currentTarget, async () => {
        await srv.post(`/friends/${p.id}/remove`); p.friend = 'none'; await refreshFriends(); draw();
      }), { kind: 'ghost' }));
    } else if (p.friend === 'received') {
      out.push(btn('Accepter la demande', (e) => act(e.currentTarget, async () => {
        await srv.post(`/friends/${p.id}/accept`); p.friend = 'friends'; await refreshFriends(); draw();
      }), { kind: 'primary' }));
    } else if (p.friend === 'sent') {
      out.push(btn('Demande envoyée', null, { disabled: true }));
    } else {
      out.push(btn('Ajouter en ami', (e) => act(e.currentTarget, async () => {
        const r = await srv.post('/friends/request', { username: p.username });
        p.friend = r.result === 'friends' ? 'friends' : 'sent';
        toast(r.result === 'friends' ? `${p.name} et vous êtes maintenant amis.` : `Demande envoyée à ${p.name}.`, 'ok');
        await refreshFriends(); draw();
      }), { kind: 'primary', iconName: 'plus' }));
    }
    out.push(h('span', { class: 'grow' }));
    out.push(btn(p.blocked ? 'Débloquer' : 'Bloquer', async (e) => {
      const b = e.currentTarget;
      if (!p.blocked && !await confirmDialog(`Bloquer ${p.name}`, 'Vous ne verrez plus ses messages dans le chat et il ne pourra plus vous écrire en privé.', { okLabel: 'Bloquer', danger: true })) return;
      act(b, async () => { await srv.post(`/users/${p.id}/block`, { on: !p.blocked }); p.blocked = !p.blocked; draw(); });
    }, { kind: 'ghost danger small', iconName: 'block' }));
    if (self?.admin) {
      out.push(btn('Rendre muet 24 h', (e) => act(e.currentTarget, async () => {
        await srv.post('/chat/mute', { userId: p.id, minutes: 1440 }); toast(`${p.name} ne peut plus écrire dans le chat général pendant 24 h.`, 'ok');
      }), { kind: 'ghost small' }));
      out.push(btn('Rendre la parole', (e) => act(e.currentTarget, async () => {
        await srv.post('/chat/mute', { userId: p.id, minutes: 0 }); toast(`${p.name} peut de nouveau écrire.`, 'ok');
      }), { kind: 'ghost small' }));
    }
    return out;
  }

  draw();
}

/** Makes any image a square PNG of at most 256 pixels (sent in base64; the server makes it 128). */
function shrink(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      const side = Math.min(img.naturalWidth, img.naturalHeight);
      const out = Math.min(256, side);
      const c = document.createElement('canvas');
      c.width = out;
      c.height = out;
      const ctx = c.getContext('2d');
      // small pixel art (sprites) stays sharp
      ctx.imageSmoothingEnabled = side > 128;
      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(img, (img.naturalWidth - side) / 2, (img.naturalHeight - side) / 2, side, side, 0, 0, out, out);
      URL.revokeObjectURL(url);
      resolve(c.toDataURL('image/png'));
    };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('Image illisible (PNG, JPEG, GIF, WebP…).')); };
    img.src = url;
  });
}

/** My profile, editable. */
export function renderProfile(container) {
  const page = h('div', { class: 'page narrow' });
  container.appendChild(page);

  function draw() {
    const u = me() || {};
    const name = h('input', { class: 'input', value: u.name || '', maxlength: 24 });
    const counter = h('small', null, `${(u.bio || '').length} / ${BIO_MAX}`);
    const bio = h('textarea', { class: 'input', rows: 3, maxlength: BIO_MAX, placeholder: 'Quelques mots sur vous : vos jeux, vos défis préférés…',
      oninput: (e) => { counter.textContent = `${e.target.value.length} / ${BIO_MAX}`; } });
    bio.value = u.bio || '';
    const games = h('select', { class: 'input' }, h('option', { value: '' }, '—'),
      Object.keys(GAMES).map((id) => h('option', { value: id, selected: u.favGame === id }, gameName(id))));
    const mon = h('input', { class: 'input', value: u.favPokemon || '', maxlength: 24, placeholder: 'ex. Évoli' });
    const oldPw = h('input', { class: 'input', type: 'password', autocomplete: 'current-password' });
    const newPw = h('input', { class: 'input', type: 'password', autocomplete: 'new-password' });
    const file = h('input', { type: 'file', accept: 'image/*', style: { display: 'none' }, onchange: async (e) => {
      const f = e.target.files?.[0];
      e.target.value = '';
      if (!f) return;
      try {
        const data = await shrink(f);
        await local.post('/api/me/avatar', { data });
        await refreshState();
        toast('Photo de profil changée.', 'ok');
        draw();
      } catch (err) { errorToast(err); }
    } });

    const restore = keepScroll(page);
    mount(page,
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Mon profil'), h('p', null, 'Ce que les autres joueurs voient en cliquant sur votre nom ou votre photo.'))),
      h('div', { class: 'card' }, h('div', { class: 'row' },
        avatar(u.name, 1, { size: 'xl', id: u.id, pic: u.avatar }),
        h('div', { class: 'grow stack s' }, h('b', null, 'Photo de profil'),
          h('small', { class: 'muted' }, 'Une image carrée de préférence ; elle est recadrée et réduite. Visible par tous les joueurs du serveur.'),
          h('div', { class: 'row' }, file,
            btn(u.avatar ? 'Changer' : 'Choisir une image', () => file.click(), { kind: 'small', iconName: 'image' }),
            u.avatar ? btn('Retirer', (e) => act(e.currentTarget, async () => {
              await local.post('/api/me/avatar/remove', {}); await refreshState(); draw();
            }), { kind: 'small ghost danger' }) : null)))),
      h('div', { class: 'card mt stack' },
        field('Nom affiché', name, 'Votre pseudo dans les salles, le chat et au-dessus de votre personnage en jeu. Votre identifiant de connexion (@' + (u.username || '?') + ') ne change pas.'),
        h('label', { class: 'field' }, h('span', null, 'Bio'), bio, counter),
        h('div', { class: 'grid2' }, field('Jeu favori', games), field('Pokémon favori', mon)),
        h('div', { class: 'row' }, h('span', { class: 'grow' }),
          btn('Voir ma fiche', () => openProfile(u.id), { kind: 'ghost' }),
          btn('Enregistrer', (e) => act(e.currentTarget, async () => {
            await local.post('/api/me/profile', { name: name.value.trim(), bio: bio.value.trim(), favGame: games.value, favPokemon: mon.value.trim() });
            await refreshState();
            toast('Profil enregistré.', 'ok');
            draw();
          }), { kind: 'primary' }))),
      // the account: not shown to the other players
      h('div', { class: 'section-title' }, h('h2', null, 'Compte')),
      h('div', { class: 'card stack' },
        h('p', { class: 'muted small' }, `Connecté en tant que @${u.username || '?'} sur ${store.state?.link?.server || 'le serveur'}.`),
        h('div', { class: 'grid2' }, field('Mot de passe actuel', oldPw), field('Nouveau mot de passe', newPw, '8 caractères minimum.')),
        h('div', { class: 'row' }, btn('Changer le mot de passe', (e) => act(e.currentTarget, async () => {
          await local.post('/api/me/password', { old: oldPw.value, new: newPw.value });
          oldPw.value = ''; newPw.value = '';
          toast('Mot de passe changé. Vos autres appareils devront se reconnecter.', 'ok');
        })), h('span', { class: 'grow' }),
        btn('Se déconnecter', async (e) => {
          const b = e.currentTarget;
          if (await confirmDialog('Se déconnecter', 'Le jeu en cours sera fermé.')) act(b, async () => { await local.post('/api/auth/logout'); location.hash = '#/'; location.reload(); });
        }, { kind: 'ghost danger', iconName: 'logout' }))),
      u.createdAt ? h('p', { class: 'tiny faint center mt' }, `Compte créé le ${dateTime(u.createdAt)}`) : null);
    restore();
  }

  draw();
  return () => {};
}

export const profileOf = () => store.state?.link?.user;
