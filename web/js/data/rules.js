// Rules of a room (the server applies them; the leader may change them at any time), by game mode: how each rule
// is shown. Which rules a mode offers, their default values and the number of players are in the mode's file
// (modes.js); what a rule needs from the game is in capabilities.json (both shared with the server).

import { missingForRule } from './capabilities.js';
import { specOf, familyOf, isOfficial, modeDefaults, locked, playersOf, missingForModeOf, applies } from './modes.js';

// Soul Link
export const RULES = [
  { key: 'autoEnd', type: 'bool', label: 'Fin automatique contre le Maître', highlight: true,
    desc: 'Cable Club détecte dans le jeu la victoire contre le Maître de la Ligue et termine le Soul Link (selon « Fin du Soul Link »). Désactivé : le chef de salle termine l’aventure à la main.' },
  { key: 'startWithBalls', type: 'bool', label: 'Départ aux premières Poké Balls',
    desc: 'Le Soul Link ne commence vraiment que quand chaque joueur a au moins une Poké Ball : avant, un Pokémon K.O. (le starter par exemple) ne meurt pas et les rencontres ne comptent pas.' },
  { key: 'shinyClause', type: 'bool', label: 'Clause chromatique',
    desc: 'Un Pokémon chromatique peut toujours être capturé, même dans une zone déjà utilisée (il n’est lié à personne).' },
  { key: 'dupesClause', type: 'bool', label: 'Clause des doublons',
    desc: 'Une lignée d’évolution déjà possédée dans la salle ne compte pas : la rencontre est ignorée tant que la zone n’est pas réglée.' },
  { key: 'legendaries', type: 'bool', label: 'Légendaires autorisés',
    desc: 'Désactivé : un légendaire capturé est aussitôt relâché.' },
  { key: 'giftPokemon', type: 'bool', label: 'Pokémon offerts ou échangés',
    desc: 'Pokémon donnés, échangés ou vendus par des personnages. Désactivé : ils sont relâchés à la réception.' },
  { key: 'naming', type: 'choice', label: 'Surnom des liens',
    options: [['leader', 'Choisi par le chef'], ['free', 'Libre']],
    desc: 'Quand tous les joueurs ont capturé le Pokémon d’une zone, le chef de salle lui donne son nom, appliqué dans chaque jeu.' },
  { key: 'pauseForNaming', type: 'bool', label: 'Pause pendant le nommage',
    desc: 'Les jeux attendent que le chef ait choisi le nom du lien.' },
  { key: 'deathBox', type: 'bool', label: 'Boîte des morts',
    desc: 'Les Pokémon morts quittent l’équipe pour la dernière boîte du PC, renommée « MORTS » (générations 3 à 5) ou pour la boîte actuelle du PC (générations 1 et 2, qui n’ont pas de noms de boîtes). En génération 4 et 5, le dépôt se fait au prochain changement de lieu. En génération 2, après le chargement d’une sauvegarde d’état, il attend le prochain lancement du jeu.' },
  { key: 'refundCancelled', type: 'bool', label: 'Rendre les objets d’une zone annulée',
    desc: 'Si un joueur met K.O. le premier Pokémon d’une zone, ceux qui l’avaient capturé récupèrent les objets utilisés.' },
  { key: 'wipeEndsRun', type: 'bool', label: 'Équipe entière K.O. = défaite',
    desc: 'Le Soul Link s’arrête si toute l’équipe d’un joueur tombe.' },
  { key: 'endWhen', type: 'choice', label: 'Fin du Soul Link',
    options: [['all', 'Quand tous ont battu le Maître'], ['first', 'Dès que l’un bat le Maître']] },
  { key: 'saveStates', type: 'bool', label: 'Sauvegardes d’état',
    desc: 'Autorise les sauvegardes instantanées de l’émulateur.' },
];


// Nuzlocke: each player has their own, nothing is shared
export const NUZLOCKE_RULES = [
  { section: 'Les règles de base : un Pokémon K.O. est mort, et seul le premier Pokémon sauvage rencontré dans chaque zone peut être capturé (appliquées par Cable Club)' },
  { key: 'autoEnd', type: 'bool', label: 'Fin automatique contre le Maître', highlight: true,
    desc: 'Cable Club détecte dans le jeu la victoire contre le Maître de la Ligue (selon « Fin du Nuzlocke »). Désactivé : le chef de salle termine l’aventure à la main.' },
  { key: 'startWithBalls', type: 'bool', label: 'Départ à la première Poké Ball',
    desc: 'Les règles ne s’appliquent à un joueur qu’à partir de sa première Poké Ball : avant, un Pokémon K.O. ne meurt pas et les rencontres ne comptent pas (les premières routes se visitent avant d’avoir des Poké Balls).' },
  { key: 'deathBox', type: 'bool', label: 'Garder les morts dans le PC',
    desc: 'Les Pokémon morts quittent l’équipe pour la dernière boîte du PC, renommée « MORTS » (générations 3 à 5) ou pour la boîte actuelle du PC (générations 1 et 2). En génération 4 et 5, le dépôt se fait au prochain changement de lieu.' },
  { key: 'wipeEndsRun', type: 'bool', label: 'Équipe entière K.O. = partie perdue',
    desc: 'La partie d’un joueur est perdue si toute son équipe tombe. Désactivé : il peut continuer.' },
  { key: 'endWhen', type: 'choice', label: 'Fin du Nuzlocke',
    options: [['all', 'Quand tous ont fini'], ['first', 'Dès que l’un bat le Maître']] },
  { section: 'Règles optionnelles appliquées par Cable Club' },
  { key: 'dupesClause', type: 'bool', label: 'Clause des doublons',
    desc: 'Si le premier Pokémon rencontré est d’une lignée d’évolution que vous possédez déjà, la rencontre est ignorée : vous pouvez en rencontrer un autre dans la zone.' },
  { key: 'shinyClause', type: 'bool', label: 'Clause chromatique',
    desc: 'Un Pokémon chromatique peut toujours être capturé, même dans une zone déjà utilisée.' },
  { key: 'legendaries', type: 'bool', label: 'Légendaires autorisés',
    desc: 'Désactivé : un légendaire capturé est aussitôt relâché.' },
  { key: 'giftPokemon', type: 'bool', label: 'Pokémon offerts ou échangés',
    desc: 'Les Pokémon donnés par des personnages ne comptent pas pour la zone. Désactivé : ils sont relâchés à la réception.' },
  { key: 'saveStates', type: 'bool', label: 'Sauvegardes d’état',
    desc: 'Autorise les sauvegardes instantanées de l’émulateur.' },
  { section: 'Règles classiques du Nuzlocke, appliquées par Cable Club dans le jeu : un objet interdit quitte le sac dès que le joueur reprend la main sur le terrain' },
  { key: 'honorNoHealItems', type: 'bool', label: 'Pas d’objets de soin',
    desc: 'Soins uniquement au Centre Pokémon : potions, soins de statut, Rappels, PP, boissons, herbes et baies qui soignent sont retirés du sac.' },
  { key: 'honorNoHeldItems', type: 'bool', label: 'Pas d’objet tenu',
    desc: 'L’objet tenu par un Pokémon de l’équipe est remis dans le sac (les lettres restent).' },
  { key: 'honorNoMultiExp', type: 'bool', label: 'Pas de Multi Exp', desc: 'Retiré du sac (et des Pokémon qui le tiennent).' },
  { key: 'honorNoMasterBall', type: 'bool', label: 'Pas de Master Ball', desc: 'Retirée du sac.' },
  { key: 'honorNoEvolution', type: 'bool', label: 'Pas d’évolution', rom: true,
    desc: 'Les évolutions sont retirées de la ROM de la salle : aucun Pokémon n’évolue, les pierres sont sans effet. Se choisit avant le lancement de l’aventure.' },
  { key: 'honorNoFly', type: 'bool', label: 'Pas de Vol', desc: 'La CS Vol est retirée du sac, et les Pokémon de l’équipe oublient l’attaque Vol.' },
  { key: 'honorNoRepel', type: 'bool', label: 'Pas de Repousse', desc: 'Repousse, Superepousse et Max Repousse sont retirées du sac.' },
  { key: 'honorSetMode', type: 'bool', label: 'Mode Défini',
    desc: 'Le style de combat « Défini » est imposé dans les options du jeu : pas de changement de Pokémon proposé après avoir mis K.O. celui d’un dresseur.' },
  { key: 'honorCsException', type: 'bool', label: 'Exception pour les CS',
    desc: 'Avant de lancer la Poké Ball, touchez « Pokémon de CS » dans la barre du jeu : la capture est gardée même si la zone est déjà utilisée. Ce Pokémon ne doit jamais gagner d’expérience (combat, Multi Exp, Super Bonbon) : sinon il est relâché.' },
  { key: 'honorNotepad', type: 'bool', label: 'Pas de capture équipe pleine',
    desc: 'Un Pokémon capturé alors que l’équipe est pleine (il part au PC) est relâché, et la chance de la zone est utilisée (« Notepad Clause »).' },
  { section: 'Nuzlocke Hardcore : appliqué par Cable Club' },
  { key: 'hardcore', type: 'bool', label: 'Nuzlocke Hardcore',
    desc: 'Plafond de niveau : aucun Pokémon de l’équipe au-dessus du Pokémon le plus fort du prochain champion d’arène (puis de la Ligue après 8 badges), lu dans la ROM de la salle ; au-dessus, il part au PC jusqu’au badge suivant. Pas d’objets du sac en combat (les Poké Balls et les objets tenus restent permis). Style de combat Défini imposé dans le jeu. Équipe entière K.O. = partie perdue.' },
  { key: 'hardcoreItems', type: 'choice', label: 'Objet utilisé en combat', when: (r) => r.hardcore,
    options: [['report', 'Signalé à la salle'], ['lose', 'Partie perdue']],
    desc: 'Cable Club voit les objets du sac utilisés pendant un combat (sur DS, d’après l’écran du jeu).' },
];


// the classic rules and the Hardcore, shared by the modes where each player has their own run
const sectionAt = (start) => NUZLOCKE_RULES.findIndex((d) => d.section && d.section.startsWith(start));
const CLASSIC = NUZLOCKE_RULES.slice(sectionAt('Règles classiques'), sectionAt('Nuzlocke Hardcore'));
const HARDCORE = NUZLOCKE_RULES.slice(sectionAt('Nuzlocke Hardcore'));
const rule = (key) => NUZLOCKE_RULES.find((d) => d.key === key);

// Sololocke: one Pokémon in the team at a time, the Nuzlocke's rules around it
export const SOLOLOCKE_RULES = [
  { section: 'Les règles de base : un seul Pokémon dans l’équipe, un Pokémon K.O. est mort, et seul le premier Pokémon sauvage rencontré dans chaque zone peut être capturé (appliquées par Cable Club)' },
  { ...rule('autoEnd'), desc: 'Cable Club détecte dans le jeu la victoire contre le Maître de la Ligue (selon « Fin du Sololocke »). Désactivé : le chef de salle termine l’aventure à la main.' },
  rule('startWithBalls'),
  { key: 'soloParty', type: 'choice', label: 'Un seul Pokémon dans l’équipe',
    options: [['auto', 'Les autres vont au PC'], ['honor', 'Sur l’honneur']],
    desc: '« Les autres vont au PC » : dès qu’un deuxième Pokémon vivant rejoint l’équipe, Cable Club l’envoie dans le PC. « Sur l’honneur » : l’équipe peut garder des Pokémon pour les CS, mais un seul combat.' },
  { key: 'soloLoss', type: 'choice', label: 'Quand votre Pokémon meurt',
    options: [['next', 'Un autre prend la relève'], ['end', 'La partie est perdue']],
    desc: '« Un autre prend la relève » : allez chercher un Pokémon vivant dans le PC ; la partie n’est perdue que s’il n’en reste aucun.' },
  rule('deathBox'),
  { ...rule('endWhen'), label: 'Fin du Sololocke' },
  { section: 'Règles optionnelles appliquées par Cable Club' },
  rule('dupesClause'), rule('shinyClause'), rule('legendaries'), rule('giftPokemon'), rule('saveStates'),
  ...CLASSIC.filter((d) => d.key !== 'honorNoMultiExp' && d.key !== 'honorNotepad'),
  ...HARDCORE,
];


// Shinylocke: only shiny Pokémon may be caught (generations 2 to 5: the first one has no shinies)
export const SHINYLOCKE_RULES = [
  { section: 'Les règles de base : seuls les Pokémon chromatiques peuvent être capturés, et un Pokémon K.O. est mort (appliquées par Cable Club). Le Pokémon de départ est autorisé. Le taux de chromatiques se règle à l’étape « Randomisation ».' },
  { ...rule('autoEnd'), desc: 'Cable Club détecte dans le jeu la victoire contre le Maître de la Ligue (selon « Fin du Shinylocke »). Désactivé : le chef de salle termine l’aventure à la main.' },
  rule('startWithBalls'), rule('deathBox'), rule('wipeEndsRun'),
  { ...rule('endWhen'), label: 'Fin du Shinylocke' },
  { section: 'Règles optionnelles appliquées par Cable Club' },
  { key: 'oneShinyPerZone', type: 'bool', label: 'Un seul chromatique par zone',
    desc: 'Après une capture dans une zone, un autre chromatique rencontré là ne peut plus être capturé.' },
  rule('dupesClause'), rule('legendaries'),
  { key: 'giftPokemon', type: 'bool', label: 'Pokémon offerts ou échangés',
    desc: 'Gardés même s’ils ne sont pas chromatiques. Désactivé : seuls les Pokémon offerts chromatiques sont gardés, les autres sont relâchés.' },
  rule('saveStates'),
  ...CLASSIC,
  ...HARDCORE,
];


// how the rules of each family of modes are shown, in their order and sections
const BY_FAMILY = { soullink: RULES, nuzlocke: NUZLOCKE_RULES, sololocke: SOLOLOCKE_RULES, shinylocke: SHINYLOCKE_RULES };

// a rule of an imported mode that its family does not show: its first definition in the lists above
const ANY = new Map();
for (const list of Object.values(BY_FAMILY)) for (const d of list) if (d.key && !ANY.has(d.key)) ANY.set(d.key, d);

/** How a rule is shown (the mode editor): as in the family of the mode when it has it, else its first definition. */
export const ruleDef = (key, mode) => (mode && (BY_FAMILY[familyOf(mode)] || []).find((d) => d.key === key)) || ANY.get(key) || null;

/**
 * The rules of a mode (an id, a room or a mode file) as they are shown: those the mode offers, in the order of its
 * family, without those the room's game cannot apply (shinies or held items in generation 1…). A rule the mode
 * fixes is marked « locked ». An imported mode also shows, after them, the rules its family does not have.
 */
export function rulesOf(mode, gen, gameId) {
  const spec = specOf(mode);
  const offered = spec.rules || {};
  const official = isOfficial(spec);
  const shows = (key) => official || applies(spec, key);
  const own = BY_FAMILY[familyOf(spec)] || RULES;
  const shown = own.filter((d) => !d.key || (d.key in offered && shows(d.key)));
  const extra = official ? [] : Object.keys(offered).filter((k) => ANY.has(k) && !own.some((d) => d.key === k) && shows(k));
  const all = extra.length ? [...shown, { section: 'Autres règles du mode' }, ...extra.map((k) => ANY.get(k))] : shown;
  const usable = all.filter((d) => !d.key || !missingForRule(d.key, gameId, gen))
    .map((d) => (d.key && locked(spec, d.key) ? { ...d, locked: true } : d));
  // a section without any rule left under it is not shown
  return usable.filter((d, i) => !d.section || (usable[i + 1] && !usable[i + 1].section));
}

export const ruleDefaults = (mode) => modeDefaults(mode);

/** Whether a game can play a mode (the Shinylocke needs shinies: not the first generation). */
export const modeAvailable = (mode, gen, gameId) => !missingForModeOf(mode, gameId, gen);

/** The generations a mode can be played in. */
export const modeGenerations = (mode) => [1, 2, 3, 4, 5].filter((g) => modeAvailable(mode, g));

/** Choices of the number of players of a room: [value, label] (1 is "Solo"). */
export function playerChoices(mode) {
  const { min, max } = playersOf(mode);
  const out = [];
  for (let n = min; n <= max; n++) out.push([n, n === 1 ? 'Solo' : String(n)]);
  return out;
}
