// Rules of a room (the server applies them; the leader may change them at any time), by game mode.

// Soul Link
export const RULES = [
  { key: 'autoEnd', type: 'bool', label: 'Fin automatique contre le Maître', highlight: true,
    desc: 'Cable Club détecte dans le jeu la victoire contre le Maître de la Ligue et termine le Soul Link (selon « Fin du Soul Link »). Désactivé : le chef de salle termine l’aventure à la main.' },
  { key: 'startWithBalls', type: 'bool', label: 'Départ aux premières Poké Balls',
    desc: 'Le Soul Link ne commence vraiment que quand chaque joueur a au moins une Poké Ball : avant, un Pokémon K.O. (le starter par exemple) ne meurt pas et les rencontres ne comptent pas.' },
  { key: 'shinyClause', type: 'bool', label: 'Clause chromatique', minGen: 2,
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

export const RULE_DEFAULTS = {
  shinyClause: true, dupesClause: true, legendaries: true, giftPokemon: false, naming: 'leader',
  pauseForNaming: true, deathBox: true, refundCancelled: true, wipeEndsRun: true, endWhen: 'all', saveStates: false,
  autoEnd: true, startWithBalls: true,
};

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
  { key: 'shinyClause', type: 'bool', label: 'Clause chromatique', minGen: 2,
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
  { key: 'honorNoHeldItems', type: 'bool', label: 'Pas d’objet tenu', minGen: 2,
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

export const NUZLOCKE_DEFAULTS = {
  shinyClause: false, dupesClause: false, legendaries: true, giftPokemon: true, naming: 'free',
  pauseForNaming: false, deathBox: true, refundCancelled: false, wipeEndsRun: true, endWhen: 'all', saveStates: false,
  autoEnd: true, startWithBalls: true,
  honorNoHealItems: false, honorNoHeldItems: false, honorNoMultiExp: false, honorNoMasterBall: false, honorNoEvolution: false,
  honorNoFly: false, honorNoRepel: false, honorSetMode: false, honorCsException: false, honorNotepad: false,
  hardcore: false, hardcoreItems: 'report',
};

// the classic rules and the Hardcore, shared by the modes where each player has their own run
const sectionAt = (start) => NUZLOCKE_RULES.findIndex((d) => d.section && d.section.startsWith(start));
const CLASSIC = NUZLOCKE_RULES.slice(sectionAt('Règles classiques'), sectionAt('Nuzlocke Hardcore'));
const HARDCORE = NUZLOCKE_RULES.slice(sectionAt('Nuzlocke Hardcore'));
const classicDefaults = Object.fromEntries(CLASSIC.filter((d) => d.key).map((d) => [d.key, false]));
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

export const SOLOLOCKE_DEFAULTS = {
  ...NUZLOCKE_DEFAULTS, soloParty: 'auto', soloLoss: 'next',
};

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

export const SHINYLOCKE_DEFAULTS = {
  ...NUZLOCKE_DEFAULTS, shinyClause: false, giftPokemon: false, oneShinyPerZone: false, ...classicDefaults,
};

const BY_MODE = {
  soullink: [RULES, RULE_DEFAULTS],
  nuzlocke: [NUZLOCKE_RULES, NUZLOCKE_DEFAULTS],
  sololocke: [SOLOLOCKE_RULES, SOLOLOCKE_DEFAULTS],
  shinylocke: [SHINYLOCKE_RULES, SHINYLOCKE_DEFAULTS],
};

/** The rules of a mode, without those impossible in the room's generation (shinies in generation 1, held items…). */
export const rulesOf = (mode, gen) => (BY_MODE[mode] || BY_MODE.soullink)[0].filter((d) => !d.minGen || !gen || gen >= d.minGen);
export const ruleDefaults = (mode) => (BY_MODE[mode] || BY_MODE.soullink)[1];

/** Names of the game modes. */
export const MODE_NAMES = { soullink: 'Soul Link', nuzlocke: 'Nuzlocke', sololocke: 'Sololocke', shinylocke: 'Shinylocke' };
export const modeName = (mode) => MODE_NAMES[mode] || MODE_NAMES.soullink;

// players a room of each mode may have
export const MODE_PLAYERS = { soullink: { min: 2, max: 5 }, nuzlocke: { min: 1, max: 10 }, sololocke: { min: 1, max: 10 }, shinylocke: { min: 1, max: 10 } };

/** The first generation a mode can be played in (the Shinylocke needs shinies: generation 2 and later). */
export const MODE_MIN_GEN = { shinylocke: 2 };
export const modeAvailable = (mode, gen) => !MODE_MIN_GEN[mode] || Number(gen) >= MODE_MIN_GEN[mode];

/** Choices of the number of players of a room: [value, label] (1 is "Solo"). */
export function playerChoices(mode) {
  const { min, max } = MODE_PLAYERS[mode] || MODE_PLAYERS.soullink;
  const out = [];
  for (let n = min; n <= max; n++) out.push([n, n === 1 ? 'Solo' : String(n)]);
  return out;
}
