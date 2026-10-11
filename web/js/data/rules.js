// Rules of a room (the server applies them; the leader may change them at any time).

export const RULES = [
  { key: 'autoEnd', type: 'bool', label: 'Fin automatique contre le Maître', highlight: true,
    desc: 'SLT détecte dans le jeu la victoire contre le Maître de la Ligue et termine le Soul Link (selon « Fin du Soul Link »). Désactivé : le chef de salle termine l’aventure à la main.' },
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
    desc: 'Les Pokémon morts quittent l’équipe pour la dernière boîte du PC, renommée « MORTS » (générations 3 à 5) ou pour la boîte actuelle du PC (génération 1, qui n’a pas de noms de boîtes). En génération 2, ils restent K.O. dans l’équipe. En génération 4 et 5, le dépôt se fait au prochain changement de lieu.' },
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
