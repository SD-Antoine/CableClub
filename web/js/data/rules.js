// Rules of a room (the server applies them; the leader may change them at any time), by game mode.

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
  { key: 'shinyClause', type: 'bool', label: 'Clause chromatique',
    desc: 'Un Pokémon chromatique peut toujours être capturé, même dans une zone déjà utilisée.' },
  { key: 'legendaries', type: 'bool', label: 'Légendaires autorisés',
    desc: 'Désactivé : un légendaire capturé est aussitôt relâché.' },
  { key: 'giftPokemon', type: 'bool', label: 'Pokémon offerts ou échangés',
    desc: 'Les Pokémon donnés par des personnages ne comptent pas pour la zone. Désactivé : ils sont relâchés à la réception.' },
  { key: 'saveStates', type: 'bool', label: 'Sauvegardes d’état',
    desc: 'Autorise les sauvegardes instantanées de l’émulateur.' },
  { section: 'Règles d’honneur : Cable Club ne peut pas les vérifier, chacun s’y tient' },
  { key: 'honorNoHealItems', type: 'bool', label: 'Pas d’objets de soin', desc: 'Soigner uniquement dans les Centres Pokémon.' },
  { key: 'honorNoHeldItems', type: 'bool', label: 'Pas d’objet tenu' },
  { key: 'honorNoMultiExp', type: 'bool', label: 'Pas de Multi Exp' },
  { key: 'honorNoMasterBall', type: 'bool', label: 'Pas de Master Ball' },
  { key: 'honorNoEvolution', type: 'bool', label: 'Pas d’évolution' },
  { key: 'honorNoFly', type: 'bool', label: 'Pas de Vol' },
  { key: 'honorNoRepel', type: 'bool', label: 'Pas de Repousse' },
  { key: 'honorSetMode', type: 'bool', label: 'Mode Défini', desc: 'Pas de changement de Pokémon après avoir mis K.O. le Pokémon d’un dresseur.' },
  { key: 'honorCsException', type: 'bool', label: 'Exception pour les CS',
    desc: 'Si une CS est indispensable, capturer exceptionnellement un Pokémon compatible pour débloquer le passage, puis le remettre au PC pour toujours.' },
  { key: 'honorNotepad', type: 'bool', label: 'Aucun Pokémon dans le PC', desc: 'Interdit de capturer tant que l’équipe est pleine (« Notepad Clause »).' },
  { section: 'Bientôt' },
  { key: 'hardcore', type: 'bool', label: 'Nuzlocke Hardcore', soon: true,
    desc: 'Pas d’objets en combat, plafond de niveau selon le prochain champion d’arène, hors-jeu = défaite et partie à recommencer, mode Défini.' },
];

export const NUZLOCKE_DEFAULTS = {
  shinyClause: false, dupesClause: false, legendaries: true, giftPokemon: true, naming: 'free',
  pauseForNaming: false, deathBox: true, refundCancelled: false, wipeEndsRun: true, endWhen: 'all', saveStates: false,
  autoEnd: true, startWithBalls: true,
  honorNoHealItems: false, honorNoHeldItems: false, honorNoMultiExp: false, honorNoMasterBall: false, honorNoEvolution: false,
  honorNoFly: false, honorNoRepel: false, honorSetMode: false, honorCsException: false, honorNotepad: false,
};

export const rulesOf = (mode) => (mode === 'nuzlocke' ? NUZLOCKE_RULES : RULES);
export const ruleDefaults = (mode) => (mode === 'nuzlocke' ? NUZLOCKE_DEFAULTS : RULE_DEFAULTS);

// players a room of each mode may have
export const MODE_PLAYERS = { soullink: { min: 2, max: 5 }, nuzlocke: { min: 1, max: 10 } };

/** Choices of the number of players of a room: [value, label] (1 is "Solo"). */
export function playerChoices(mode) {
  const { min, max } = MODE_PLAYERS[mode] || MODE_PLAYERS.soullink;
  const out = [];
  for (let n = min; n <= max; n++) out.push([n, n === 1 ? 'Solo' : String(n)]);
  return out;
}
