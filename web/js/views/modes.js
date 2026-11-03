// The player's own game modes (point 2): compose a mode from the primitives of the engine and the rules of the
// catalog, check it with the server, export it as a .ccmode.json file, import one, and create a room with it; and
// the gallery of the server (2d): publish a mode, find the modes of the others, report one.
// A mode file is declarative: rules, values and the author's texts, never code (docs/modes.md).

import { h, mount, btn, field, seg, switchEl, chip, modal, toast, errorToast, confirmDialog, icon } from '../ui.js';
import { srv } from '../api.js';
import { me } from '../store.js';
import { officialModes, officialMode, familyOf, catalogRules, modeDefaults, applies } from '../data/modes.js';
import { ruleDef } from '../data/rules.js';
import { objectiveText } from './parts.js';
import { openReport } from './moderation.js';
import { openCreateRoom } from './create-room.js';
import { MODES } from './mode.js';
import { mobile } from '../platform.js';

// the modes are kept in this interface (each device its own list); the file is what travels
const KEY = 'cc.modes';
const LICENSES = [['CC0-1.0', 'CC0'], ['CC-BY-4.0', 'CC BY'], ['CC-BY-SA-4.0', 'CC BY-SA']];

function loadOwn() {
  try {
    const list = JSON.parse(localStorage.getItem(KEY) || '[]');
    return Array.isArray(list) ? list.filter((m) => m && typeof m === 'object' && m.id) : [];
  } catch { return []; }
}

function saveOwn(list) {
  try { localStorage.setItem(KEY, JSON.stringify(list)); } catch { toast('Les modes ne peuvent pas être gardés sur cet appareil : exportez-les.', 'warn'); }
}

/** The server checks the file (the same validator as for a room) and gives it back normalized. */
const check = async (spec) => (await srv.post('/modes/check', spec)).mode;

const slug = (text) => String(text || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
  .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 32).replace(/-+$/, '');

/** A new mode from an official one: its primitives and rules, a name and an id of its own. */
function fromOfficial(id) {
  const base = JSON.parse(JSON.stringify(officialMode(id) || officialModes()[0]));
  return { ...base, id: `${base.id}-perso`.slice(0, 32), name: `${base.name} perso`.slice(0, 40), author: me()?.name || 'Moi',
    license: 'CC-BY-4.0', description: base.description };
}

export function renderModes(container) {
  const page = h('div', { class: 'page narrow' });
  container.appendChild(page);
  let own = loadOwn();
  let tab = 'mine';
  let published = new Map(); // my modes in the gallery, by id (those taken out by the moderation too)
  let gallery = null; // the modes of the gallery shown, or the error that came instead
  let query = '';
  let timer = 0;

  async function refreshPublished() {
    try {
      published = new Map(((await srv.get('/modes/public?mine=1')).modes || []).map((e) => [e.id, e]));
    } catch { published = new Map(); }
    if (tab === 'mine') draw();
  }

  async function loadGallery() {
    try {
      gallery = (await srv.get(`/modes/public?q=${encodeURIComponent(query)}`)).modes || [];
    } catch (e) { gallery = e; }
    if (tab === 'public') drawGallery();
  }

  const galleryBox = h('div');
  function draw() {
    mount(page,
      h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Mes modes'),
        h('p', null, 'Composez votre mode avec les règles que Cable Club applique dans les jeux, puis créez une salle avec. Un mode est un fichier « .ccmode.json » : des règles et vos textes, jamais de code. Pour le partager, exportez-le, ou publiez-le dans la galerie du serveur.'))),
      h('div', { class: 'tabs' }, [['mine', 'Mes modes'], ['public', 'Modes publics']].map(([id, label]) =>
        h('button', { class: tab === id ? 'active' : '', onclick: () => { tab = id; draw(); if (id === 'public') loadGallery(); } }, label))),
      tab === 'public' ? galleryView() : [
        h('div', { class: 'row wrap mb' },
          btn('Nouveau mode', () => edit(fromOfficial('nuzlocke'), true), { kind: 'primary', iconName: 'plus' }),
          importButton(),
          btn('Coller un mode', pasteMode, { iconName: 'copy' })),
        own.length ? h('div', { class: 'stack' }, own.map(card))
          : h('div', { class: 'empty' }, h('b', null, 'Aucun mode pour l’instant'), 'Partez d’un mode officiel et changez ce que vous voulez, importez le fichier d’un ami, ou prenez un mode de la galerie (« Modes publics »).')]);
  }

  /** What a mode is, in a card: name, tab, author, players, text, goals; then its buttons. */
  function modeCard(spec, { by = null, extra = null } = {}, ...buttons) {
    const family = MODES[familyOf(spec)];
    return h('div', { class: 'card' },
      h('div', { class: 'row' }, h('h3', { class: 'grow' }, spec.name), extra, family ? chip(`Onglet ${family.name}`) : null),
      h('p', { class: 'small muted mt-s' }, `Par ${spec.author}${by ? ` · publié par ${by}` : ''} · ${spec.license} · ${spec.players.min === spec.players.max ? spec.players.min : `${spec.players.min} à ${spec.players.max}`} joueur(s)`),
      spec.description ? h('p', { class: 'small mt-s' }, spec.description) : null,
      spec.objectives?.length ? h('p', { class: 'tiny muted mt-s' }, `Objectifs : ${spec.objectives.map(objectiveText).join(', ')}`) : null,
      h('div', { class: 'row wrap mt' }, buttons));
  }

  // my mode in the gallery: the same file (its rooms may be public), another version, or taken out
  const publication = (spec) => {
    const e = published.get(spec.id);
    if (!e) return null;
    return { entry: e, hidden: !!e.hidden, same: JSON.stringify(e.mode) === JSON.stringify(spec) };
  };

  function card(spec) {
    const p = publication(spec);
    const state = !p ? null : p.hidden ? chip('Retiré par la modération', 'danger') : p.same ? chip('Publié', 'ok') : chip('Publié (autre version)', 'warn');
    return modeCard(spec, { extra: state },
      btn('Créer une salle', () => createRoom(spec, !!p && p.same && !p.hidden), { kind: 'small primary', iconName: 'play' }),
      btn('Modifier', () => edit(JSON.parse(JSON.stringify(spec)), false), { kind: 'small', iconName: 'edit' }),
      mobile ? null : btn('Exporter', () => download(spec), { kind: 'small', iconName: 'download' }),
      btn('Copier', () => copy(spec), { kind: 'small', iconName: 'copy', title: 'Copier le fichier du mode (à coller chez un ami)' }),
      !p ? btn('Publier', () => publish(spec), { kind: 'small', iconName: 'globe', title: 'Le montrer à tous les joueurs du serveur' })
        : !p.hidden && !p.same ? btn('Mettre à jour la publication', () => publish(spec), { kind: 'small', iconName: 'globe' }) : null,
      p && !p.hidden ? btn('Retirer de la galerie', () => unpublish(spec.id), { kind: 'small ghost' }) : null,
      h('span', { class: 'grow' }),
      btn('', () => remove(spec), { kind: 'small ghost icon-only', iconName: 'trash', title: 'Supprimer ce mode' }));
  }

  async function createRoom(spec, isPublished) {
    try {
      // checked again: the validator of the server is the one that counts
      const ok = await check(spec);
      openCreateRoom(familyOf(ok), ok, { published: isPublished });
    } catch (e) { errorToast(e); }
  }

  async function publish(spec) {
    if (!await confirmDialog(`Publier « ${spec.name} »`,
      `Le mode apparaît dans « Modes publics » pour tous les joueurs du serveur, avec votre pseudo. Ils pourront le prendre, créer des salles avec (publiques ou non) et le signaler. Son texte reste sous la licence ${spec.license}. Pas de lien, pas d’aide pour obtenir des jeux, rien d’offensant : la modération retire les modes qui ne respectent pas les conditions d’utilisation.`,
      { okLabel: 'Publier' })) return;
    try {
      await srv.post('/modes/publish', spec);
      toast(`« ${spec.name} » est publié.`, 'ok');
      await refreshPublished();
    } catch (e) { errorToast(e); }
  }

  async function unpublish(id) {
    if (!await confirmDialog('Retirer de la galerie', 'Le mode n’est plus proposé aux autres joueurs. Il reste dans vos modes, et les salles déjà créées avec gardent leur copie.', { okLabel: 'Retirer' })) return;
    try {
      await srv.post(`/modes/public/${encodeURIComponent(id)}/unpublish`);
      await refreshPublished();
      if (tab === 'public') loadGallery();
    } catch (e) { errorToast(e); }
  }

  // ------------------------------------------------------------ gallery
  function galleryView() {
    const search = h('input', { class: 'input', type: 'search', placeholder: 'Rechercher un mode (nom, texte, auteur)', value: query, autocomplete: 'off',
      oninput: (e) => { clearTimeout(timer); timer = setTimeout(() => { query = e.target.value.trim(); loadGallery(); }, 250); } });
    drawGallery();
    return h('div', null, h('div', { class: 'card tight mb' }, search), galleryBox);
  }

  function drawGallery() {
    if (gallery === null) { mount(galleryBox, h('p', { class: 'muted small' }, 'Chargement…')); return; }
    if (gallery instanceof Error) { mount(galleryBox, h('div', { class: 'empty' }, h('b', null, 'Galerie indisponible'), gallery.message)); return; }
    mount(galleryBox, gallery.length ? h('div', { class: 'stack' }, gallery.map(publicCard))
      : h('div', { class: 'empty' }, h('b', null, query ? 'Aucun mode trouvé' : 'Aucun mode publié pour l’instant'), 'Publiez le vôtre depuis « Mes modes ».'));
  }

  function publicCard(e) {
    const spec = e.mode;
    const uses = Number(e.uses) || 0;
    return modeCard(spec, { by: e.publisher, extra: uses ? chip(`${uses} salle${uses > 1 ? 's' : ''}`) : null },
      btn('Créer une salle', () => openCreateRoom(familyOf(spec), spec, { published: true }), { kind: 'small primary', iconName: 'play' }),
      btn('Ajouter à mes modes', () => take(spec), { kind: 'small', iconName: 'plus' }),
      h('span', { class: 'grow' }),
      e.mine ? btn('Retirer de la galerie', () => unpublish(e.id), { kind: 'small ghost' })
        : btn('Signaler', () => openReport({ modeId: e.id, name: spec.name, text: spec.description || '(sans description)' }), { kind: 'small ghost', iconName: 'flag' }));
  }

  async function take(spec) {
    const mine = own.find((m) => m.id === spec.id);
    if (mine && JSON.stringify(mine) === JSON.stringify(spec)) { toast('Ce mode est déjà dans vos modes.', 'info'); return; }
    if (mine && !await confirmDialog('Remplacer votre mode', `Vous avez déjà un mode « ${spec.id} » : il est remplacé par celui de la galerie.`, { okLabel: 'Remplacer' })) return;
    keep(JSON.parse(JSON.stringify(spec)));
    toast(`« ${spec.name} » ajouté à vos modes.`, 'ok');
  }

  function keep(spec, replacedId = spec.id) {
    const i = own.findIndex((m) => m.id === replacedId);
    if (i >= 0) own[i] = spec; else own.unshift(spec);
    own = own.filter((m, j) => m.id !== spec.id || j === own.indexOf(spec));
    saveOwn(own);
    draw();
  }

  async function remove(spec) {
    if (!await confirmDialog('Supprimer ce mode', `« ${spec.name} » disparaît de cet appareil. Les salles déjà créées avec gardent leur copie du mode.`, { okLabel: 'Supprimer', danger: true })) return;
    own = own.filter((m) => m.id !== spec.id);
    saveOwn(own);
    draw();
  }

  const fileText = (spec) => `${JSON.stringify(spec, null, 2)}\n`;

  function download(spec) {
    const url = URL.createObjectURL(new Blob([fileText(spec)], { type: 'application/json' }));
    const a = h('a', { href: url, download: `${spec.id}.ccmode.json` });
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function copy(spec) {
    navigator.clipboard?.writeText(fileText(spec)).then(() => toast('Fichier du mode copié', 'ok', { ms: 1500 }),
      () => toast('Copie impossible sur cet appareil.', 'warn'));
  }

  /** A mode brought from elsewhere: checked by the server before it joins the list. */
  async function bring(text) {
    let spec;
    try { spec = JSON.parse(text); } catch { toast('Ce n’est pas un fichier de mode lisible (JSON).', 'warn'); return false; }
    try {
      const ok = await check(spec);
      const known = own.some((m) => m.id === ok.id);
      keep(ok);
      toast(known ? `« ${ok.name} » remplacé par le fichier importé.` : `« ${ok.name} » ajouté à vos modes.`, 'ok');
      return true;
    } catch (e) { errorToast(e); return false; }
  }

  function importButton() {
    const input = h('input', { type: 'file', accept: '.json,.ccmode.json,application/json', style: { display: 'none' },
      onchange: async (e) => {
        const file = e.target.files?.[0];
        e.target.value = '';
        if (!file) return;
        if (file.size > 32 * 1024) { toast('Fichier trop gros pour un mode (32 Ko au plus).', 'warn'); return; }
        await bring(await file.text());
      } });
    return h('span', null, input, btn('Importer un fichier', () => input.click(), { iconName: 'folder' }));
  }

  function pasteMode() {
    const area = h('textarea', { class: 'input mono', rows: 10, placeholder: '{ "schemaVersion": 1, … }' });
    modal({ title: 'Coller un mode', body: h('div', { class: 'stack' }, h('p', { class: 'muted small' }, 'Collez le fichier d’un mode (le texte que « Copier » met dans le presse-papiers).'), area),
      actions: [{ label: 'Annuler', onClick: (c) => c() }, { label: 'Ajouter', kind: 'primary', onClick: async (c) => { if (await bring(area.value)) c(); } }] });
  }

  // ------------------------------------------------------------- editor
  function edit(draft, isNew) {
    const originalId = draft.id;
    const st = { error: null, checked: null, idEdited: !isNew, timer: 0 };
    const body = h('div');
    const status = h('div');
    const m = modal({ title: isNew ? 'Nouveau mode' : `Modifier « ${draft.name} »`, body: h('div', null, body), wide: true, actions: [
      { label: 'Annuler', onClick: (c) => c() },
      h('span', { class: 'grow' }),
      status,
      btn('Enregistrer', save, { kind: 'primary', iconName: 'check' }),
    ] });

    function set(change) {
      const before = { engine: draft.engine, win: draft.win };
      Object.assign(draft, change);
      conform(before);
      draw();
      schedule();
    }

    // the primitives decide what the mode must have: the end conditions with their rule, the shinies, two players;
    // a rule that starts to make sense is proposed with its value in the first official mode that uses it
    function conform(before = null) {
      draft.needs = draft.engine.encounters === 'shiniesOnly' ? ['shinies'] : [];
      draft.lose = draft.engine.team === 'one' ? ['soloLost'] : ['teamWiped'];
      if (draft.engine.links === 'shared') {
        draft.players = { min: Math.max(2, draft.players.min), max: Math.max(2, draft.players.max) };
      }
      const usedBy = (key) => officialModes().find((o) => key in (o.rules || {}) && applies(o, key));
      const rules = {};
      for (const r of catalogRules()) {
        if (r.officialOnly || !appliesNow(r)) continue;
        if (r.key in draft.rules) rules[r.key] = draft.rules[r.key];
        else if (before && !appliesWith(before, r) && usedBy(r.key)) rules[r.key] = modeDefaults(usedBy(r.key))[r.key];
        else if (required(r.key)) rules[r.key] = r.options ? r.neutral : false;
      }
      for (const k of ['autoEnd', 'wipeEndsRun', 'soloLoss']) {
        if (k in rules && !(k in draft.rules)) rules[k] = k === 'soloLoss' ? 'next' : true;
      }
      draft.rules = rules;
    }

    const appliesWith = ({ engine, win }, r) => {
      if (!r.when) return true;
      const [axis, value] = r.when.split(':');
      return axis === 'win' ? win.includes(value) : engine[axis] === value;
    };
    const appliesNow = (r) => appliesWith(draft, r);
    // rules an end condition needs (the validator asks for them)
    const required = (key) => (draft.win.includes('champion') && (key === 'autoEnd' || key === 'endWhen'))
      || (draft.lose.includes('teamWiped') && key === 'wipeEndsRun') || (draft.lose.includes('soloLost') && key === 'soloLoss');

    function schedule() {
      clearTimeout(st.timer);
      st.checked = null;
      drawStatus('Vérification…');
      st.timer = setTimeout(async () => {
        try {
          st.checked = await check(draft);
          st.error = null;
        } catch (e) {
          st.checked = null;
          st.error = e.message;
        }
        drawStatus();
      }, 400);
    }

    function drawStatus(pending) {
      mount(status, pending ? h('span', { class: 'small muted' }, pending)
        : st.checked ? h('span', { class: 'small', style: { color: 'var(--ok)' } }, icon('check'), ' Mode valide')
          : h('span', { class: 'small', style: { color: 'var(--danger)' }, title: st.error || '' }, icon('alert'), ' À corriger'));
      if (errorBox) mount(errorBox, st.error && !pending ? h('div', { class: 'banner warn' }, icon('alert'), h('div', { class: 'small' }, st.error)) : null);
    }

    function save() {
      if (!st.checked) { toast(st.error || 'Le mode est en cours de vérification.', 'warn'); return; }
      if (own.some((x) => x.id === st.checked.id) && st.checked.id !== originalId) {
        toast(`Vous avez déjà un mode « ${st.checked.id} » : changez l’identifiant.`, 'warn');
        return;
      }
      keep(st.checked, originalId);
      m.close();
    }

    let errorBox = null;
    function draw() {
      const text = (key, max, { area = false, onInput } = {}) => h(area ? 'textarea' : 'input', { class: 'input', maxlength: max, rows: area ? 3 : null,
        value: draft[key] || '', oninput: (e) => { draft[key] = e.target.value; if (onInput) onInput(e.target.value); schedule(); } });
      const idInput = h('input', { class: 'input mono', maxlength: 32, value: draft.id,
        oninput: (e) => { st.idEdited = true; draft.id = e.target.value.trim(); schedule(); } });
      const nameInput = text('name', 40, { onInput: (v) => { if (!st.idEdited) { draft.id = slug(v); idInput.value = draft.id; } } });
      const range = (lo) => Array.from({ length: 10 - lo + 1 }, (_, i) => [lo + i, String(lo + i)]);
      const minP = draft.engine.links === 'shared' ? 2 : 1;
      errorBox = h('div');
      mount(body, h('div', { class: 'stack l' },
        isNew ? field('Partir de', seg(officialModes().map((s) => [s.id, s.name]), null, (id) => {
          const base = fromOfficial(id);
          Object.assign(draft, { engine: base.engine, rules: base.rules, players: base.players, win: base.win, lose: base.lose, objectives: base.objectives, needs: base.needs });
          conform(); draw(); schedule();
        }), 'Reprend les primitives et les règles d’un mode officiel (vos textes restent).') : null,
        h('div', { class: 'grid2' }, field('Nom', nameInput), field('Identifiant', idInput, 'Minuscules, chiffres et tirets.')),
        field('Description', text('description', 600, { area: true })),
        h('div', { class: 'grid2' }, field('Auteur', text('author', 40)),
          field('Licence du texte', seg(LICENSES, draft.license, (v) => set({ license: v })))),
        h('div', { class: 'grid2' },
          field('Joueurs au moins', seg(range(minP).slice(0, 5), draft.players.min, (v) => set({ players: { min: v, max: Math.max(v, draft.players.max) } }))),
          field('Joueurs au plus', h('select', { class: 'input', onchange: (e) => set({ players: { min: Math.min(draft.players.min, Number(e.target.value)), max: Number(e.target.value) } }) },
            range(minP).map(([v, l]) => h('option', { value: v, selected: v === draft.players.max }, l))))),
        h('div', { class: 'section-title' }, h('h2', null, 'Primitives')),
        field('Joueurs', seg([['shared', 'Liés (Soul Link)'], ['none', 'Chacun sa partie']], draft.engine.links, (v) => set({ engine: { ...draft.engine, links: v } })),
          'Liés : les Pokémon d’une même zone sont liés entre les joueurs, et une mort tue les âmes liées.'),
        field('Rencontres', seg([['firstPerZone', 'Première de la zone'], ['shiniesOnly', 'Chromatiques seulement']], draft.engine.encounters, (v) => set({ engine: { ...draft.engine, encounters: v } })),
          draft.engine.encounters === 'shiniesOnly' ? 'Seuls les chromatiques se capturent (jeux de la 2e génération et plus).' : 'Seul le premier Pokémon sauvage rencontré dans chaque zone compte.'),
        field('Équipe', seg([['free', 'Libre'], ['one', 'Un seul Pokémon']], draft.engine.team, (v) => set({ engine: { ...draft.engine, team: v } })),
          draft.engine.team === 'one' ? 'Un seul Pokémon vivant dans l’équipe ; quand il meurt, un autre du PC prend la relève (selon la règle).' : null),
        h('div', { class: 'rule-row' }, h('div', { class: 'grow' }, h('b', null, 'Battre le Maître de la Ligue termine la partie'),
          h('small', null, 'Avec les règles « Fin automatique » et « Fin ».')),
        switchEl(draft.win.includes('champion'), (v) => set({ win: v ? ['champion'] : [] }))),
        h('div', { class: 'section-title' }, h('h2', null, 'Règles')),
        h('p', { class: 'tiny muted' }, 'Proposée : la règle apparaît dans les salles du mode, avec sa valeur de départ. Fixée : elle ne change pas dans les salles.'),
        h('div', { class: 'list' }, catalogRules().filter((r) => !r.officialOnly && appliesNow(r)).map(ruleRow)),
        h('div', { class: 'section-title' }, h('h2', null, 'Objectifs')),
        h('p', { class: 'tiny muted' }, 'Affichés aux joueurs : ils ne décident pas de la fin de la partie.'),
        objectives(),
        errorBox));
      drawStatus(st.checked === null && !st.error ? 'Vérification…' : null);
    }

    function ruleRow(r) {
      const def = ruleDef(r.key, draft);
      const on = r.key in draft.rules;
      const v = draft.rules[r.key];
      const value = v && typeof v === 'object' ? v.default : v;
      const isLocked = !!(v && typeof v === 'object' && v.locked);
      const put = (val, lockedNow) => { draft.rules = { ...draft.rules, [r.key]: lockedNow ? { default: val, locked: true } : val }; set({}); };
      const labels = Object.fromEntries(def?.options || []);
      const control = !on ? null : r.options
        ? seg(r.options.map((o) => [o, labels[o] || o]), value, (val) => put(val, isLocked))
        : switchEl(!!value, (val) => put(val, isLocked));
      return h('div', { class: 'rule-row' },
        h('div', { class: 'grow' }, h('b', null, def?.label || r.key), def?.desc ? h('small', null, def.desc) : null,
          on ? h('label', { class: 'row tiny muted mt-s', style: { gap: '6px' } },
            switchEl(isLocked, (val) => put(value, val)), 'Fixée par le mode') : null),
        control,
        h('label', { class: 'row tiny muted', style: { gap: '6px' }, title: required(r.key) ? 'Nécessaire avec les conditions de fin choisies' : null },
          switchEl(on, (val) => {
            const rules = { ...draft.rules };
            if (val) rules[r.key] = r.options ? r.neutral : false; else delete rules[r.key];
            draft.rules = rules;
            set({});
          }, { disabled: required(r.key) }), 'Proposée'));
    }

    function objectives() {
      const list = draft.objectives || [];
      const update = (next) => set({ objectives: next });
      const count = (o, i, max) => h('input', { class: 'input', type: 'number', min: 1, max, value: o.count, style: { width: '90px' },
        onchange: (e) => update(list.map((x, j) => (j === i ? { ...x, count: Math.max(1, Math.min(max, Number(e.target.value) || 1)) } : x))) });
      return h('div', { class: 'stack' },
        list.map((o, i) => h('div', { class: 'row' }, h('span', { class: 'grow small' }, objectiveText(o)),
          o.type === 'badges' ? count(o, i, 8) : o.type === 'captures' ? count(o, i, 500) : null,
          btn('', () => update(list.filter((_, j) => j !== i)), { kind: 'small ghost icon-only', iconName: 'x', title: 'Retirer' }))),
        list.length < 10 ? h('div', { class: 'row wrap' },
          list.some((o) => o.type === 'champion') ? null : btn('Battre le Maître', () => update([...list, { type: 'champion' }]), { kind: 'small', iconName: 'plus' }),
          btn('Badges', () => update([...list, { type: 'badges', count: 8 }]), { kind: 'small', iconName: 'plus' }),
          btn('Captures', () => update([...list, { type: 'captures', count: 30 }]), { kind: 'small', iconName: 'plus' })) : null);
    }

    conform();
    draw();
    schedule();
  }

  draw();
  refreshPublished();
  return () => clearTimeout(timer);
}
