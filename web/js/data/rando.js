// Randomization options. Every "key" is a field of the Universal Pokemon
// Randomizer ZX Settings class: the backend copies the values as-is.
// Shops are not here: SLT randomizes the secondary clerks itself (see defaultRando).

const U = 'UNCHANGED';

/** Section list. Options can declare minGen/maxGen/gens and a "parent" (shown indented).
 *  desc: what the option is about; helps: what the selected choice does (shown under it). */
export const SECTIONS = [
  {
    id: 'pokemon', emoji: '🧬', label: 'Pokémon',
    groups: [
      {
        label: 'Statistiques de base', options: [
          { key: 'baseStatisticsMod', type: 'select', label: 'Statistiques des espèces', def: U,
            desc: 'Les 6 statistiques de chaque espèce : PV, Attaque, Défense, Attaque Spé., Défense Spé., Vitesse.',
            options: [[U, 'D’origine'], ['SHUFFLE', 'Mélangées'], ['RANDOM', 'Redistribuées au hasard']],
            helps: {
              [U]: 'Chaque espèce garde ses statistiques habituelles.',
              SHUFFLE: 'Chaque espèce garde ses 6 valeurs, mais dans un autre ordre (ex. : la grosse Attaque d’un Pokémon peut devenir sa Vitesse).',
              RANDOM: 'Le total de chaque espèce ne change pas, mais il est réparti au hasard entre les 6 statistiques.',
            } },
          { key: 'baseStatsFollowEvolutions', type: 'bool', label: 'Même profil pour toute la famille', def: false, parent: 'baseStatisticsMod',
            desc: 'Une évolution garde la même répartition que sa forme de base (si Salamèche devient rapide, Dracaufeu aussi).', when: (s) => s.baseStatisticsMod !== U },
          { key: 'assignEvoStatsRandomly', type: 'bool', label: 'Gains d’évolution au hasard', def: false, parent: 'baseStatisticsMod',
            desc: 'Les points gagnés en évoluant vont dans des statistiques tirées au hasard.',
            when: (s) => s.baseStatisticsMod !== U && s.baseStatsFollowEvolutions },
          { key: 'updateBaseStats', type: 'bool', label: 'Statistiques des jeux récents', def: false,
            desc: 'Certains Pokémon ont été renforcés dans les jeux suivants (ex. : Raichu, Papilusion) : leurs nouvelles valeurs sont appliquées.' },
          { key: 'updateBaseStatsToGeneration', type: 'select', label: 'Valeurs de quelle génération', def: 6, parent: 'updateBaseStats',
            options: [[6, '6e génération (X/Y)'], [7, '7e génération (Soleil/Lune)'], [8, '8e génération (Épée/Bouclier)'], [9, '9e génération (Écarlate/Violet)']], when: (s) => s.updateBaseStats },
          { key: 'standardizeEXPCurves', type: 'bool', label: 'Même vitesse de progression', def: false,
            desc: 'Normalement certains Pokémon montent de niveau bien plus lentement que d’autres. Ici, ils suivent tous la même courbe.' },
          { key: 'expCurveMod', type: 'select', label: 'Pokémon concernés', def: 'LEGENDARIES', parent: 'standardizeEXPCurves',
            options: [['LEGENDARIES', 'Tous sauf les légendaires'], ['STRONG_LEGENDARIES', 'Tous sauf les légendaires très puissants'], ['ALL', 'Tous, sans exception']],
            helps: {
              LEGENDARIES: 'Les légendaires gardent leur progression lente.',
              STRONG_LEGENDARIES: 'Seuls les légendaires les plus puissants gardent leur progression lente.',
              ALL: 'Même les légendaires progressent à la même vitesse que les autres.',
            },
            when: (s) => s.standardizeEXPCurves },
          { key: 'selectedEXPCurve', type: 'select', label: 'Vitesse choisie', def: 'MEDIUM_FAST', parent: 'standardizeEXPCurves',
            options: [['MEDIUM_FAST', 'Moyenne'], ['MEDIUM_SLOW', 'Moyenne-lente'], ['FAST', 'Rapide'],
              ['SLOW', 'Lente'], ['ERRATIC', 'Erratique', { minGen: 3 }], ['FLUCTUATING', 'Fluctuante', { minGen: 3 }]],
            helps: {
              MEDIUM_FAST: '1 000 000 points d’EXP pour atteindre le niveau 100 : le rythme le plus courant.',
              MEDIUM_SLOW: '1 059 860 EXP pour le niveau 100 : comme la plupart des starters.',
              FAST: '800 000 EXP pour le niveau 100 : on monte vite.',
              SLOW: '1 250 000 EXP pour le niveau 100 : comme les légendaires.',
              ERRATIC: '600 000 EXP pour le niveau 100, rapide au début puis irrégulier.',
              FLUCTUATING: '1 640 000 EXP pour le niveau 100 : très lent à haut niveau.',
            },
            when: (s) => s.standardizeEXPCurves },
        ],
      },
      {
        label: 'Types', options: [
          { key: 'typesMod', type: 'select', label: 'Types des Pokémon', def: U,
            options: [[U, 'D’origine'], ['RANDOM_FOLLOW_EVOLUTIONS', 'Au hasard, par famille'], ['COMPLETELY_RANDOM', 'Au hasard, par espèce']],
            helps: {
              [U]: 'Chaque Pokémon garde ses types habituels.',
              RANDOM_FOLLOW_EVOLUTIONS: 'Chaque famille reçoit de nouveaux types, que ses évolutions conservent.',
              COMPLETELY_RANDOM: 'Chaque espèce reçoit ses propres types : un Pokémon peut changer de type en évoluant.',
            } },
          { key: 'dualTypeOnly', type: 'bool', label: 'Toujours deux types', def: false, parent: 'typesMod',
            desc: 'Aucun Pokémon n’a un seul type.', when: (s) => s.typesMod !== U },
        ],
      },
      {
        label: 'Talents', minGen: 3, options: [
          { key: 'abilitiesMod', type: 'select', label: 'Talents', def: U, options: [[U, 'D’origine'], ['RANDOMIZE', 'Au hasard']],
            helps: { [U]: 'Chaque espèce garde ses talents habituels.', RANDOMIZE: 'Chaque espèce reçoit un ou deux talents tirés au hasard.' } },
          { key: 'abilitiesFollowEvolutions', type: 'bool', label: 'Mêmes talents pour toute la famille', def: false, parent: 'abilitiesMod',
            desc: 'Une évolution garde les talents de sa forme de base.', when: (s) => s.abilitiesMod !== U },
          { key: 'allowWonderGuard', type: 'bool', label: 'Autoriser Garde Mystik', def: true, parent: 'abilitiesMod',
            desc: 'Garde Mystik rend un Pokémon insensible à toutes les attaques sauf les super efficaces : souvent invincible ou au contraire très fragile.', when: (s) => s.abilitiesMod !== U },
          { key: 'ensureTwoAbilities', type: 'bool', label: 'Toujours deux talents possibles', def: false, parent: 'abilitiesMod',
            desc: 'Chaque espèce a deux talents différents ; chaque Pokémon capturé a l’un des deux.', when: (s) => s.abilitiesMod !== U },
          { key: 'weighDuplicateAbilitiesTogether', type: 'bool', label: 'Talents identiques tirés une seule fois', def: false, parent: 'abilitiesMod',
            desc: 'Certains talents font la même chose sous deux noms (ex. : Corps Sain et Écran Fumée) : ils ne comptent que pour un dans le tirage.', when: (s) => s.abilitiesMod !== U },
          { key: 'banTrappingAbilities', type: 'bool', label: 'Pas de talents qui empêchent de fuir', def: false, parent: 'abilitiesMod',
            desc: 'Marque Ombre, Piège Sable, Magnépiège : l’adversaire vous empêcherait de fuir ou de changer de Pokémon.', when: (s) => s.abilitiesMod !== U },
          { key: 'banNegativeAbilities', type: 'bool', label: 'Pas de talents qui handicapent', def: false, parent: 'abilitiesMod',
            desc: 'Absentéisme, Défaitiste, Début Calme… : ils affaiblissent le Pokémon qui les possède.', when: (s) => s.abilitiesMod !== U },
          { key: 'banBadAbilities', type: 'bool', label: 'Pas de talents inutiles en combat', def: false, parent: 'abilitiesMod',
            desc: 'Ramassage, Fuite, Lumiattirance… : ils ne servent qu’en dehors des combats.', when: (s) => s.abilitiesMod !== U },
        ],
      },
      {
        label: 'Évolutions', options: [
          { key: 'evolutionsMod', type: 'select', label: 'Évolutions', def: U,
            options: [[U, 'D’origine'], ['RANDOM', 'Au hasard'], ['RANDOM_EVERY_LEVEL', 'Une nouvelle à chaque niveau !', { minGen: 3 }]],
            helps: {
              [U]: 'Chaque Pokémon évolue comme d’habitude.',
              RANDOM: 'Chaque Pokémon évolue en une autre espèce tirée au hasard, au même moment qu’avant.',
              RANDOM_EVERY_LEVEL: 'À chaque niveau gagné, le Pokémon évolue en une nouvelle espèce au hasard.',
            } },
          { key: 'evosSimilarStrength', type: 'bool', label: 'Évolution de force comparable', def: false, parent: 'evolutionsMod',
            desc: 'L’évolution tirée a une puissance proche de celle d’origine.', when: (s) => s.evolutionsMod === 'RANDOM' },
          { key: 'evosSameTyping', type: 'bool', label: 'Garder un type en commun', def: false, parent: 'evolutionsMod',
            desc: 'L’évolution partage au moins un type avec le Pokémon qui évolue.', when: (s) => s.evolutionsMod !== U },
          { key: 'evosMaxThreeStages', type: 'bool', label: 'Trois stades au maximum', def: false, parent: 'evolutionsMod',
            desc: 'Pas de chaîne d’évolutions plus longue qu’une famille habituelle.', when: (s) => s.evolutionsMod === 'RANDOM' },
          { key: 'evosForceChange', type: 'bool', label: 'Toujours une évolution différente', def: false, parent: 'evolutionsMod',
            desc: 'Le tirage ne peut pas retomber sur l’évolution d’origine.', when: (s) => s.evolutionsMod !== U },
          { key: 'changeImpossibleEvolutions', type: 'bool', label: 'Remplacer les évolutions par échange', def: true,
            desc: 'Indispensable en Soul Link : les Pokémon qui évoluent normalement par échange (Kadabra, Machopeur, Gravalanch…) évoluent à la place en montant de niveau ou avec un objet.' },
          { key: 'makeEvolutionsEasier', type: 'bool', label: 'Évolutions plus tôt', def: false,
            desc: 'Les Pokémon qui évoluent très tard dans le jeu évoluent à un niveau plus bas.' },
          { key: 'removeTimeBasedEvolutions', type: 'bool', label: 'Évolutions à toute heure', def: false, minGen: 2,
            desc: 'Les évolutions possibles seulement le jour ou la nuit (ex. : Évoli en Mentali ou Noctali) deviennent possibles à tout moment.' },
        ],
      },
    ],
  },
  {
    id: 'starters', emoji: '🎁', label: 'Starters & fixes',
    groups: [
      {
        label: 'Pokémon de départ', options: [
          { key: 'startersMod', type: 'select', label: 'Pokémon proposés au départ', def: U,
            options: [[U, 'D’origine'], ['COMPLETELY_RANDOM', 'Complètement aléatoires'], ['RANDOM_WITH_TWO_EVOLUTIONS', 'Aléatoires, à deux évolutions']],
            helps: {
              [U]: 'Le professeur propose les trois starters habituels.',
              COMPLETELY_RANDOM: 'N’importe quel Pokémon peut être proposé, même un Pokémon déjà évolué ou sans évolution.',
              RANDOM_WITH_TWO_EVOLUTIONS: 'Uniquement des Pokémon de base qui évoluent deux fois, comme les starters habituels.',
            } },
          { key: 'randomizeStartersHeldItems', type: 'bool', label: 'Le starter tient un objet au hasard', def: false, gens: [2, 3, 4] },
          { key: 'banBadRandomStarterHeldItems', type: 'bool', label: 'Pas d’objet sans intérêt', def: false, gens: [2, 3, 4], parent: 'randomizeStartersHeldItems', when: (s) => s.randomizeStartersHeldItems },
        ],
      },
      {
        label: 'Pokémon fixes', note: 'Les Pokémon toujours au même endroit : légendaires, Pokémon offerts par des personnages, combats imposés (ex. : Ronflex sur la route, Motisma dans le Vieux Château).', options: [
          { key: 'staticPokemonMod', type: 'select', label: 'Pokémon fixes', def: U,
            options: [[U, 'D’origine'], ['RANDOM_MATCHING', 'Légendaire contre légendaire'], ['COMPLETELY_RANDOM', 'Complètement aléatoires'], ['SIMILAR_STRENGTH', 'De force comparable']],
            helps: {
              [U]: 'Chaque Pokémon fixe reste celui d’origine.',
              RANDOM_MATCHING: 'Un légendaire est remplacé par un autre légendaire, un Pokémon ordinaire par un autre Pokémon ordinaire.',
              COMPLETELY_RANDOM: 'N’importe quel Pokémon peut remplacer n’importe quel Pokémon fixe.',
              SIMILAR_STRENGTH: 'Chaque Pokémon fixe est remplacé par un Pokémon de puissance proche.',
            } },
          { key: 'limitMainGameLegendaries', type: 'bool', label: 'Légendaires de l’histoire raisonnables', def: false, minGen: 3, parent: 'staticPokemonMod',
            desc: 'Les légendaires que l’histoire vous oblige à affronter sont remplacés par des Pokémon adaptés, pour éviter un combat impossible.',
            when: (s) => s.staticPokemonMod !== U && s.staticPokemonMod !== 'COMPLETELY_RANDOM' },
          { key: 'limit600', type: 'bool', label: 'Les très puissants entre eux', def: false, parent: 'staticPokemonMod',
            desc: 'Les Pokémon fixes très puissants (600 ou plus de total de statistiques, comme Dracolosse) sont remplacés par d’autres Pokémon très puissants, légendaires ou non.',
            when: (s) => s.staticPokemonMod !== U },
          { key: 'correctStaticMusic', type: 'bool', label: 'Musique adaptée', def: false, minGen: 3, parent: 'staticPokemonMod',
            desc: 'La musique de combat spéciale d’un légendaire suit le Pokémon qui le remplace.', when: (s) => s.staticPokemonMod !== U },
          { key: 'staticLevelModified', type: 'bool', label: 'Changer leur niveau', def: false },
          { key: 'staticLevelModifier', type: 'int', label: 'Variation de niveau', unit: '%', def: 0, min: -50, max: 50, step: 5, parent: 'staticLevelModified',
            desc: 'Ex. : +20 % transforme un niveau 50 en niveau 60.', when: (s) => s.staticLevelModified },
        ],
      },
      {
        label: 'Échanges avec les personnages du jeu', options: [
          { key: 'inGameTradesMod', type: 'select', label: 'Échanges proposés', def: U,
            options: [[U, 'D’origine'], ['RANDOMIZE_GIVEN', 'Pokémon reçu au hasard'], ['RANDOMIZE_GIVEN_AND_REQUESTED', 'Pokémon demandé et reçu au hasard']],
            helps: {
              [U]: 'Les personnages proposent les échanges habituels.',
              RANDOMIZE_GIVEN: 'Le personnage demande le même Pokémon qu’avant, mais en donne un autre, tiré au hasard.',
              RANDOMIZE_GIVEN_AND_REQUESTED: 'Le Pokémon demandé et le Pokémon donné sont tous les deux tirés au hasard.',
            } },
          { key: 'randomizeInGameTradesNicknames', type: 'bool', label: 'Surnom du Pokémon reçu au hasard', def: false, parent: 'inGameTradesMod', when: (s) => s.inGameTradesMod !== U },
          { key: 'randomizeInGameTradesOTs', type: 'bool', label: 'Nom de son dresseur d’origine au hasard', def: false, minGen: 2, parent: 'inGameTradesMod', when: (s) => s.inGameTradesMod !== U },
          { key: 'randomizeInGameTradesIVs', type: 'bool', label: 'Potentiel (IV) du Pokémon reçu au hasard', def: false, minGen: 2, parent: 'inGameTradesMod', when: (s) => s.inGameTradesMod !== U },
          { key: 'randomizeInGameTradesItems', type: 'bool', label: 'Objet tenu par le Pokémon reçu au hasard', def: false, minGen: 2, parent: 'inGameTradesMod', when: (s) => s.inGameTradesMod !== U },
        ],
      },
    ],
  },
  {
    id: 'wild', emoji: '🌿', label: 'Pokémon sauvages',
    groups: [
      {
        label: 'Rencontres', options: [
          { key: 'wildPokemonMod', type: 'select', label: 'Pokémon sauvages', def: U,
            options: [[U, 'D’origine'], ['RANDOM', 'Au hasard'], ['AREA_MAPPING', 'Au hasard, zone par zone'], ['GLOBAL_MAPPING', 'Au hasard, pour tout le jeu']],
            helps: {
              [U]: 'Les Pokémon habituels de chaque zone.',
              RANDOM: 'Chaque emplacement de rencontre reçoit une espèce au hasard : une zone peut contenir beaucoup d’espèces différentes.',
              AREA_MAPPING: 'Dans chaque zone, chaque espèce d’origine est remplacée par une autre : la zone garde le même nombre d’espèces qu’avant.',
              GLOBAL_MAPPING: 'Chaque espèce est remplacée par la même autre espèce partout dans le jeu (ex. : tous les Rattata deviennent des Évoli).',
            } },
          { key: 'wildPokemonRestrictionMod', type: 'select', label: 'Contrainte', def: 'NONE', parent: 'wildPokemonMod',
            options: [['NONE', 'Aucune'], ['SIMILAR_STRENGTH', 'Force comparable'], ['CATCH_EM_ALL', 'Toutes les espèces'], ['TYPE_THEME_AREAS', 'Un type par zone']],
            helps: {
              NONE: 'Aucune contrainte sur le tirage.',
              SIMILAR_STRENGTH: 'Le remplaçant a une force proche de l’original : pas de Pokémon surpuissant dès la première route.',
              CATCH_EM_ALL: 'Le jeu essaie de placer chaque espèce du Pokédex quelque part avant d’en répéter une.',
              TYPE_THEME_AREAS: 'Tous les Pokémon d’une même zone partagent un type tiré au hasard.',
            },
            when: (s) => s.wildPokemonMod !== U },
          { key: 'useTimeBasedEncounters', type: 'bool', label: 'Tirage séparé selon l’heure et la saison', def: false, gens: [2, 4, 5],
            desc: 'Dans ces jeux, les rencontres changent selon le moment (jour, nuit, saison). Activé : chaque moment a son propre tirage. Désactivé : les mêmes Pokémon à toute heure.', parent: 'wildPokemonMod', when: (s) => s.wildPokemonMod !== U },
          { key: 'blockWildLegendaries', type: 'bool', label: 'Pas de légendaires sauvages', def: true, parent: 'wildPokemonMod', when: (s) => s.wildPokemonMod !== U },
          { key: 'allowWildAltFormes', type: 'bool', label: 'Formes alternatives possibles', def: false, gens: [5], parent: 'wildPokemonMod',
            desc: 'Ex. : les différentes formes de Motisma ou de Giratina peuvent apparaître.', when: (s) => s.wildPokemonMod !== U },
          { key: 'balanceShakingGrass', type: 'bool', label: 'Herbes qui bougent : force adaptée', def: false, gens: [5], parent: 'wildPokemonMod',
            desc: 'Les Pokémon rares des herbes qui bougent ont une force adaptée à leur niveau.', when: (s) => s.wildPokemonMod !== U },
          { key: 'wildLevelsModified', type: 'bool', label: 'Changer le niveau des sauvages', def: false },
          { key: 'wildLevelModifier', type: 'int', label: 'Variation de niveau', unit: '%', def: 0, min: -50, max: 50, step: 5, parent: 'wildLevelsModified',
            desc: 'Ex. : +20 % transforme un niveau 10 en niveau 12.', when: (s) => s.wildLevelsModified },
        ],
      },
      {
        label: 'Capture & objets', options: [
          { key: 'useMinimumCatchRate', type: 'bool', label: 'Captures plus faciles', def: false,
            desc: 'Garantit une chance de capture minimale à tous les Pokémon : précieux en Soul Link, où chaque rencontre compte.' },
          { key: 'minimumCatchRateLevel', type: 'select', label: 'Niveau de facilité', def: 1, parent: 'useMinimumCatchRate', when: (s) => s.useMinimumCatchRate,
            options: [[1, 'Normal'], [2, 'Renforcé'], [3, 'Super'], [4, 'Ultra'], [5, 'Capture garantie']],
            helps: {
              1: 'Au moins ~10 % de chances avec une Poké Ball sur un Pokémon en pleine santé (~5 % pour les légendaires).',
              2: 'Au moins ~17 % (~9 % pour les légendaires).',
              3: 'Au moins ~27 % (~14 % pour les légendaires).',
              4: 'Au moins ~34 % pour tous les Pokémon.',
              5: 'Chaque Poké Ball attrape le Pokémon à coup sûr.',
            } },
          { key: 'randomizeWildPokemonHeldItems', type: 'bool', label: 'Objets tenus par les sauvages au hasard', def: false, minGen: 2 },
          { key: 'banBadRandomWildPokemonHeldItems', type: 'bool', label: 'Pas d’objet sans intérêt', def: false, minGen: 2, parent: 'randomizeWildPokemonHeldItems', when: (s) => s.randomizeWildPokemonHeldItems },
        ],
      },
    ],
  },
  {
    id: 'trainers', emoji: '🥊', label: 'Dresseurs',
    groups: [
      {
        label: 'Équipes des dresseurs', options: [
          { key: 'trainersMod', type: 'select', label: 'Pokémon des dresseurs', def: U,
            options: [[U, 'D’origine'], ['RANDOM', 'Au hasard'], ['DISTRIBUTED', 'Au hasard, toutes espèces'],
              ['MAINPLAYTHROUGH', 'Au hasard, toutes espèces (histoire principale)', { gens: [5] }], ['TYPE_THEMED', 'Un type par dresseur'],
              ['TYPE_THEMED_ELITE4_GYMS', 'Un type par arène et par membre de la Ligue']],
            helps: {
              [U]: 'Les dresseurs ont leurs équipes habituelles.',
              RANDOM: 'Chaque Pokémon de dresseur est tiré au hasard.',
              DISTRIBUTED: 'Au hasard, en faisant apparaître toutes les espèces à peu près autant de fois chez les dresseurs.',
              MAINPLAYTHROUGH: 'Pareil, en ne comptant que les dresseurs de l’histoire principale.',
              TYPE_THEMED: 'Chaque dresseur reçoit un type au hasard : toute son équipe est de ce type.',
              TYPE_THEMED_ELITE4_GYMS: 'Seuls les Champions d’arène et la Ligue ont un type imposé (tiré au hasard) ; les autres dresseurs sont au hasard.',
            } },
          { key: 'rivalCarriesStarterThroughout', type: 'bool', label: 'Le rival garde son starter', def: false, parent: 'trainersMod',
            desc: 'Le rival utilise toujours le starter qu’il a choisi (et ses évolutions).', when: (s) => s.trainersMod !== U },
          { key: 'trainersUsePokemonOfSimilarStrength', type: 'bool', label: 'Force comparable à l’équipe d’origine', def: false, parent: 'trainersMod',
            desc: 'Chaque Pokémon est remplacé par un Pokémon de puissance proche.', when: (s) => s.trainersMod !== U },
          { key: 'trainersMatchTypingDistribution', type: 'bool', label: 'Types fréquents plus souvent choisis', def: false, parent: 'trainersMod',
            desc: 'Un type qui compte beaucoup de Pokémon (Eau, Normal…) est plus souvent tiré comme thème.', when: (s) => s.trainersMod === 'TYPE_THEMED' || s.trainersMod === 'TYPE_THEMED_ELITE4_GYMS' },
          { key: 'trainersBlockLegendaries', type: 'bool', label: 'Pas de légendaires chez les dresseurs', def: true, parent: 'trainersMod', when: (s) => s.trainersMod !== U },
          { key: 'trainersBlockEarlyWonderGuard', type: 'bool', label: 'Pas de Garde Mystik en début de jeu', def: true, minGen: 3, parent: 'trainersMod',
            desc: 'Un adversaire avec Garde Mystik peut être imbattable quand on n’a pas encore d’attaque super efficace.', when: (s) => s.trainersMod !== U },
          { key: 'allowTrainerAlternateFormes', type: 'bool', label: 'Formes alternatives possibles', def: false, minGen: 4, parent: 'trainersMod', when: (s) => s.trainersMod !== U },
          { key: 'trainersForceFullyEvolved', type: 'bool', label: 'Pokémon totalement évolués à haut niveau', def: false,
            desc: 'Au-delà d’un certain niveau, les Pokémon des dresseurs sont à leur dernière évolution.' },
          { key: 'trainersForceFullyEvolvedLevel', type: 'int', label: 'À partir du niveau', def: 30, min: 30, max: 65, step: 1, parent: 'trainersForceFullyEvolved', when: (s) => s.trainersForceFullyEvolved },
          { key: 'trainersLevelModified', type: 'bool', label: 'Changer le niveau des dresseurs', def: false },
          { key: 'trainersLevelModifier', type: 'int', label: 'Variation de niveau', unit: '%', def: 0, min: -50, max: 50, step: 5, parent: 'trainersLevelModified',
            desc: 'Ex. : +20 % transforme un niveau 30 en niveau 36.', when: (s) => s.trainersLevelModified },
          { key: 'eliteFourUniquePokemonNumber', type: 'int', label: 'Pokémon réservés à la Ligue', def: 0, min: 0, max: 2, step: 1, minGen: 3,
            desc: 'Nombre de Pokémon de chaque membre de la Ligue qu’aucun autre dresseur n’utilise : la surprise reste entière.' },
          { key: 'betterTrainerMovesets', type: 'bool', label: 'Attaques mieux choisies pour les dresseurs', def: false, minGen: 3,
            desc: 'Les dresseurs reçoivent des attaques plus stratégiques au lieu de celles apprises par niveau.' },
          { key: 'doubleBattleMode', type: 'bool', label: 'Tous les combats de dresseurs en duo', def: false, minGen: 3 },
        ],
      },
      {
        label: 'Pokémon et objets en plus', minGen: 3, options: [
          { key: 'additionalBossTrainerPokemon', type: 'int', label: 'Pokémon en plus : boss', desc: 'Ajouté aux Champions d’arène, à la Ligue et au rival final (6 Pokémon au maximum).', def: 0, min: 0, max: 5, step: 1 },
          { key: 'additionalImportantTrainerPokemon', type: 'int', label: 'Pokémon en plus : dresseurs importants', desc: 'Ajouté au rival et aux chefs de la Team.', def: 0, min: 0, max: 5, step: 1 },
          { key: 'additionalRegularTrainerPokemon', type: 'int', label: 'Pokémon en plus : autres dresseurs', def: 0, min: 0, max: 5, step: 1 },
          { key: 'randomizeHeldItemsForBossTrainerPokemon', type: 'bool', label: 'Objets tenus au hasard : boss', def: false },
          { key: 'randomizeHeldItemsForImportantTrainerPokemon', type: 'bool', label: 'Objets tenus au hasard : dresseurs importants', def: false },
          { key: 'randomizeHeldItemsForRegularTrainerPokemon', type: 'bool', label: 'Objets tenus au hasard : autres dresseurs', def: false },
          { key: 'consumableItemsOnlyForTrainerPokemon', type: 'bool', label: 'Seulement des objets à usage unique', def: false, desc: 'Surtout des baies, qui disparaissent une fois utilisées.' },
          { key: 'sensibleItemsOnlyForTrainerPokemon', type: 'bool', label: 'Objets adaptés au Pokémon', def: false, desc: 'Objets utiles avec ses attaques ou contre ses faiblesses.' },
          { key: 'highestLevelOnlyGetsItemsForTrainerPokemon', type: 'bool', label: 'Seul le Pokémon le plus fort tient un objet', def: false },
        ],
      },
      {
        label: 'Noms', options: [
          { key: 'randomizeTrainerNames', type: 'bool', label: 'Noms des dresseurs au hasard', def: false },
          { key: 'randomizeTrainerClassNames', type: 'bool', label: 'Noms des catégories de dresseurs au hasard', def: false, desc: 'Ex. : « Gamin », « Montagnard »…' },
        ],
      },
    ],
  },
  {
    id: 'moves', emoji: '⚡', label: 'Attaques',
    groups: [
      {
        label: 'Caractéristiques des attaques', options: [
          { key: 'randomizeMovePowers', type: 'bool', label: 'Puissance au hasard', def: false },
          { key: 'randomizeMoveAccuracies', type: 'bool', label: 'Précision au hasard', def: false },
          { key: 'randomizeMovePPs', type: 'bool', label: 'Nombre d’utilisations (PP) au hasard', def: false },
          { key: 'randomizeMoveTypes', type: 'bool', label: 'Type au hasard', def: false },
          { key: 'randomizeMoveCategory', type: 'bool', label: 'Physique ou spéciale au hasard', def: false, minGen: 4,
            desc: 'Décide si l’attaque utilise l’Attaque ou l’Attaque Spéciale.' },
          { key: 'updateMoves', type: 'bool', label: 'Attaques des jeux récents', def: false, desc: 'Applique les puissances, précisions et types modifiés dans les jeux suivants.' },
          { key: 'updateMovesToGeneration', type: 'select', label: 'Valeurs de quelle génération', def: 6, parent: 'updateMoves', when: (s) => s.updateMoves,
            options: [[2, '2e génération', { maxGen: 1 }], [3, '3e génération', { maxGen: 2 }], [4, '4e génération', { maxGen: 3 }], [5, '5e génération', { maxGen: 4 }],
              [6, '6e génération'], [7, '7e génération'], [8, '8e génération'], [9, '9e génération']] },
        ],
      },
      {
        label: 'Attaques apprises en montant de niveau', options: [
          { key: 'movesetsMod', type: 'select', label: 'Attaques apprises', def: U,
            options: [[U, 'D’origine'], ['RANDOM_PREFER_SAME_TYPE', 'Au hasard, plutôt du même type'], ['COMPLETELY_RANDOM', 'Complètement au hasard'], ['METRONOME_ONLY', 'Métronome uniquement !']],
            helps: {
              [U]: 'Chaque Pokémon apprend ses attaques habituelles.',
              RANDOM_PREFER_SAME_TYPE: 'Attaques tirées au hasard, souvent du même type que le Pokémon (plus efficaces pour lui).',
              COMPLETELY_RANDOM: 'N’importe quelle attaque, sans lien avec le Pokémon.',
              METRONOME_ONLY: 'Tous les Pokémon ne connaissent que Métronome, qui lance une attaque au hasard à chaque tour.',
            } },
          { key: 'startWithGuaranteedMoves', type: 'bool', label: 'Des attaques dès le niveau 1', def: false, parent: 'movesetsMod',
            desc: 'Chaque Pokémon connaît plusieurs attaques dès sa capture, même à bas niveau.',
            when: (s) => s.movesetsMod === 'RANDOM_PREFER_SAME_TYPE' || s.movesetsMod === 'COMPLETELY_RANDOM' },
          { key: 'guaranteedMoveCount', type: 'int', label: 'Nombre d’attaques au départ', def: 2, min: 2, max: 4, step: 1, parent: 'startWithGuaranteedMoves',
            when: (s) => s.startWithGuaranteedMoves && (s.movesetsMod === 'RANDOM_PREFER_SAME_TYPE' || s.movesetsMod === 'COMPLETELY_RANDOM') },
          { key: 'reorderDamagingMoves', type: 'bool', label: 'Attaques de plus en plus fortes', def: false, parent: 'movesetsMod',
            desc: 'Les attaques qui font des dégâts sont apprises de la plus faible à la plus puissante.',
            when: (s) => s.movesetsMod === 'RANDOM_PREFER_SAME_TYPE' || s.movesetsMod === 'COMPLETELY_RANDOM' },
          { key: 'movesetsForceGoodDamaging', type: 'bool', label: 'Assez d’attaques qui font des dégâts', def: false, parent: 'movesetsMod',
            desc: 'Évite un Pokémon qui n’aurait que des attaques de statut.',
            when: (s) => s.movesetsMod === 'RANDOM_PREFER_SAME_TYPE' || s.movesetsMod === 'COMPLETELY_RANDOM' },
          { key: 'movesetsGoodDamagingPercent', type: 'int', label: 'Part minimum d’attaques offensives', unit: '%', def: 0, min: 0, max: 100, step: 5, parent: 'movesetsForceGoodDamaging',
            when: (s) => s.movesetsForceGoodDamaging && (s.movesetsMod === 'RANDOM_PREFER_SAME_TYPE' || s.movesetsMod === 'COMPLETELY_RANDOM') },
          { key: 'blockBrokenMovesetMoves', type: 'bool', label: 'Pas d’attaques qui gâchent le jeu', def: false, parent: 'movesetsMod',
            desc: 'Guillotine, Abîme (K.O. en un coup), Sonicboom, Draco-Rage (dégâts fixes)…', when: (s) => s.movesetsMod !== U && s.movesetsMod !== 'METRONOME_ONLY' },
        ],
      },
    ],
  },
  {
    id: 'tms', emoji: '💿', label: 'CT, CS & tuteurs',
    groups: [
      {
        label: 'CT / CS', options: [
          { key: 'tmsMod', type: 'select', label: 'Attaque de chaque CT', def: U, options: [[U, 'D’origine'], ['RANDOM', 'Au hasard']],
            helps: { [U]: 'Chaque CT contient son attaque habituelle.', RANDOM: 'Chaque CT contient une attaque tirée au hasard (les CS ne changent jamais).' } },
          { key: 'keepFieldMoveTMs', type: 'bool', label: 'Garder les CT utiles hors combat', def: false, parent: 'tmsMod',
            desc: 'Les CT qui servent dans l’aventure (ex. : Flash, Éclate-Roc) restent identiques.', when: (s) => s.tmsMod !== U },
          { key: 'tmsForceGoodDamaging', type: 'bool', label: 'Assez de CT qui font des dégâts', def: false, parent: 'tmsMod', when: (s) => s.tmsMod !== U },
          { key: 'tmsGoodDamagingPercent', type: 'int', label: 'Part minimum de CT offensives', unit: '%', def: 0, min: 0, max: 100, step: 5, parent: 'tmsForceGoodDamaging', when: (s) => s.tmsMod !== U && s.tmsForceGoodDamaging },
          { key: 'blockBrokenTMMoves', type: 'bool', label: 'Pas d’attaques qui gâchent le jeu', def: false, parent: 'tmsMod',
            desc: 'Pas de K.O. en un coup ni de dégâts fixes dans les CT.', when: (s) => s.tmsMod !== U },
          { key: 'tmsHmsCompatibilityMod', type: 'select', label: 'Qui peut apprendre quoi', def: U,
            options: [[U, 'D’origine'], ['RANDOM_PREFER_TYPE', 'Au hasard, plutôt du même type'], ['COMPLETELY_RANDOM', 'Complètement au hasard'], ['FULL', 'Tout le monde apprend tout']],
            helps: {
              [U]: 'Chaque Pokémon peut apprendre les CT et CS habituelles.',
              RANDOM_PREFER_TYPE: 'Les CT apprenables sont tirées au hasard, plus souvent celles du type du Pokémon.',
              COMPLETELY_RANDOM: 'Les CT apprenables sont tirées complètement au hasard.',
              FULL: 'Tous les Pokémon peuvent apprendre toutes les CT et CS.',
            } },
          { key: 'tmLevelUpMoveSanity', type: 'bool', label: 'Cohérent avec les attaques par niveau', def: false, parent: 'tmsHmsCompatibilityMod',
            desc: 'Un Pokémon peut toujours apprendre par CT une attaque qu’il apprend aussi en montant de niveau.',
            when: (s) => s.tmsHmsCompatibilityMod !== U && s.tmsHmsCompatibilityMod !== 'FULL' },
          { key: 'tmsFollowEvolutions', type: 'bool', label: 'Même compatibilité pour toute la famille', def: false, parent: 'tmsHmsCompatibilityMod',
            when: (s) => s.tmsHmsCompatibilityMod !== U && s.tmsHmsCompatibilityMod !== 'FULL' },
          { key: 'fullHMCompat', type: 'bool', label: 'Toutes les CS pour tout le monde', def: false,
            desc: 'Tous les Pokémon peuvent apprendre Coupe, Surf, Force… : jamais bloqué faute du bon Pokémon.' },
        ],
      },
      {
        label: 'Tuteurs d’attaques', minGen: 2, note: 'Les personnages qui enseignent une attaque. Présents dans Cristal, Émeraude, Rouge Feu / Vert Feuille, Platine, HeartGold / SoulSilver et Noir 2 / Blanc 2.', options: [
          { key: 'moveTutorMovesMod', type: 'select', label: 'Attaques enseignées', def: U, options: [[U, 'D’origine'], ['RANDOM', 'Au hasard']],
            helps: { [U]: 'Les tuteurs enseignent leurs attaques habituelles.', RANDOM: 'Chaque tuteur enseigne une attaque tirée au hasard.' } },
          { key: 'keepFieldMoveTutors', type: 'bool', label: 'Garder les tuteurs utiles hors combat', def: false, parent: 'moveTutorMovesMod', when: (s) => s.moveTutorMovesMod !== U },
          { key: 'tutorsForceGoodDamaging', type: 'bool', label: 'Assez d’attaques qui font des dégâts', def: false, parent: 'moveTutorMovesMod', when: (s) => s.moveTutorMovesMod !== U },
          { key: 'tutorsGoodDamagingPercent', type: 'int', label: 'Part minimum d’attaques offensives', unit: '%', def: 0, min: 0, max: 100, step: 5, parent: 'tutorsForceGoodDamaging', when: (s) => s.moveTutorMovesMod !== U && s.tutorsForceGoodDamaging },
          { key: 'blockBrokenTutorMoves', type: 'bool', label: 'Pas d’attaques qui gâchent le jeu', def: false, parent: 'moveTutorMovesMod', when: (s) => s.moveTutorMovesMod !== U },
          { key: 'moveTutorsCompatibilityMod', type: 'select', label: 'Qui peut apprendre quoi', def: U,
            options: [[U, 'D’origine'], ['RANDOM_PREFER_TYPE', 'Au hasard, plutôt du même type'], ['COMPLETELY_RANDOM', 'Complètement au hasard'], ['FULL', 'Tout le monde apprend tout']],
            helps: {
              [U]: 'Chaque Pokémon peut apprendre les attaques habituelles des tuteurs.',
              RANDOM_PREFER_TYPE: 'Tirées au hasard, plus souvent celles du type du Pokémon.',
              COMPLETELY_RANDOM: 'Tirées complètement au hasard.',
              FULL: 'Tous les Pokémon peuvent apprendre toutes les attaques des tuteurs.',
            } },
          { key: 'tutorLevelUpMoveSanity', type: 'bool', label: 'Cohérent avec les attaques par niveau', def: false, parent: 'moveTutorsCompatibilityMod', when: (s) => s.moveTutorsCompatibilityMod !== U && s.moveTutorsCompatibilityMod !== 'FULL' },
          { key: 'tutorFollowEvolutions', type: 'bool', label: 'Même compatibilité pour toute la famille', def: false, parent: 'moveTutorsCompatibilityMod', when: (s) => s.moveTutorsCompatibilityMod !== U && s.moveTutorsCompatibilityMod !== 'FULL' },
        ],
      },
    ],
  },
  {
    id: 'items', emoji: '🎒', label: 'Objets',
    groups: [
      {
        label: 'Objets trouvés', options: [
          { key: 'fieldItemsMod', type: 'select', label: 'Objets ramassés par terre', def: U,
            options: [[U, 'D’origine'], ['SHUFFLE', 'Mélangés'], ['RANDOM', 'Au hasard'], ['RANDOM_EVEN', 'Au hasard, bien répartis']],
            helps: {
              [U]: 'Les objets habituels, à leur place habituelle.',
              SHUFFLE: 'Les mêmes objets qu’avant, mais placés à d’autres endroits.',
              RANDOM: 'Chaque objet est remplacé par un objet tiré au hasard.',
              RANDOM_EVEN: 'Au hasard, en évitant qu’un même objet revienne trop souvent.',
            } },
          { key: 'banBadRandomFieldItems', type: 'bool', label: 'Pas d’objets sans intérêt', def: false, parent: 'fieldItemsMod', when: (s) => s.fieldItemsMod === 'RANDOM' || s.fieldItemsMod === 'RANDOM_EVEN' },
        ],
      },
      {
        label: 'Talent Ramassage', minGen: 3, note: 'Les objets que rapporte un Pokémon qui a le talent Ramassage.', options: [
          { key: 'pickupItemsMod', type: 'select', label: 'Objets rapportés', def: U, options: [[U, 'D’origine'], ['RANDOM', 'Au hasard']] },
          { key: 'banBadRandomPickupItems', type: 'bool', label: 'Pas d’objets sans intérêt', def: false, parent: 'pickupItemsMod', when: (s) => s.pickupItemsMod !== U },
        ],
      },
    ],
  },
  {
    id: 'general', emoji: '🧩', label: 'Pool & ajustements',
    groups: [
      {
        label: 'Pokémon autorisés', special: 'limitGens', minGen: 2,
        note: 'Limite les Pokémon utilisés par le hasard à certaines générations (ex. : uniquement les 151 de Kanto dans Noir 2).',
        options: [
          { key: 'banIrregularAltFormes', type: 'bool', label: 'Pas de formes alternatives rares', def: false, minGen: 4,
            desc: 'Écarte les formes particulières (ex. : formes de combat, formes spéciales) du tirage.' },
        ],
      },
      { label: 'Ajustements du jeu', special: 'tweaks', options: [] },
    ],
  },
];

// Code tweaks ("Misc. Tweaks" in UPR). value = bit flag, gens = where the engine supports it.
export const TWEAKS = [
  { value: 8, label: 'Texte instantané', desc: 'Les dialogues s’affichent d’un coup.', gens: [1, 2, 3, 4, 5] },
  { value: 256, label: 'Efficacité des types des jeux récents', desc: 'Ex. : Insecte n’est plus super efficace contre Poison, Spectre bat Psy.', gens: [1, 2, 3, 4, 5] },
  { value: 1, label: 'Expérience comme dans Noir/Blanc', desc: 'Battre un Pokémon de plus haut niveau rapporte plus d’expérience, un Pokémon de plus bas niveau en rapporte moins.', gens: [1, 2] },
  { value: 262144, label: 'Courir dès le début', desc: 'Pas besoin d’attendre les Chaussures de course.', gens: [3, 4, 5] },
  { value: 16, label: 'Courir dans les bâtiments', desc: 'Normalement interdit dans ces jeux.', gens: [3] },
  { value: 524288, label: 'Barres de PV et d’EXP plus rapides', gens: [4] },
  { value: 128, label: 'Pokédex National dès le début', gens: [3, 4, 5] },
  { value: 4096, label: 'Pas d’Œuf Chance au hasard', desc: 'L’Œuf Chance (+50 % d’expérience) ne peut pas sortir d’un tirage.', gens: [2, 3, 4, 5] },
  { value: 8192, label: 'Pas d’Œuf Chance offert', desc: 'Le personnage qui le donne d’habitude ne le donne plus.', gens: [5] },
  { value: 16384, label: 'Pas d’objets de revente au hasard', desc: 'Pépites, Perles… ne sortent pas des tirages : moins d’argent facile.', gens: [5] },
  { value: 65536, label: 'Niveau des Pokémon fixes adapté', desc: 'Certains Pokémon fixes reçoivent un niveau en rapport avec le moment du jeu.', gens: [3, 5] },
  { value: 2048, label: 'Démonstration de capture au hasard', desc: 'Le Pokémon attrapé par le personnage qui vous montre comment capturer est tiré au hasard (purement visuel).', gens: [1, 2, 3, 4] },
  { value: 32, label: 'Objet du PC de départ au hasard', desc: 'La Potion rangée dans votre PC au début est remplacée par un objet au hasard.', gens: [1, 3] },
  { value: 1024, label: 'Noms des Pokémon en minuscules', desc: 'Ex. : « Pikachu » au lieu de « PIKACHU ».', gens: [1, 2, 3, 4] },
  { value: 2, label: 'Précision + moins abusif', desc: 'En 1re génération, cet objet rend les attaques K.O. en un coup infaillibles : corrigé.', gens: [1] },
  { value: 4, label: 'Coups critiques corrigés', desc: 'En 1re génération, les Pokémon rapides font presque toujours des coups critiques : corrigé.', gens: [1] },
  { value: 64, label: 'Pikachu peut évoluer (Jaune)', gens: [1] },
  { value: 512, label: 'Mode Difficile forcé (Noir 2 / Blanc 2)', gens: [5] },
  { value: 1048576, label: 'Monde Distorsion plus court (Platine)', gens: [4] },
  { value: 2097152, label: 'Formes de Motisma : types des jeux récents', desc: 'Ex. : Motisma Chaleur devient Électrik / Feu.', gens: [4] },
  { value: 4194304, label: 'Pas d’alarme de PV faibles', desc: 'Coupe la musique d’alerte quand un Pokémon a peu de PV.', gens: [5] },
];

// ------------------------------------------------------------------ helpers

export function allOptions() {
  const out = [];
  for (const s of SECTIONS) for (const g of s.groups) for (const o of g.options) out.push({ ...o, section: s.id, group: g });
  return out;
}

const inGen = (o, gen) => {
  if (!gen) return true;
  if (o.gens && !o.gens.includes(gen)) return false;
  if (o.minGen && gen < o.minGen) return false;
  if (o.maxGen && gen > o.maxGen) return false;
  return true;
};

export function optionAvailable(opt, gen) {
  return inGen(opt, gen) && (!opt.group || inGen(opt.group, gen));
}

export function choiceAvailable(choice, gen) {
  const meta = choice[2];
  return !meta || inGen(meta, gen);
}

export function defaultSettings() {
  const s = {};
  for (const o of allOptions()) s[o.key] = o.def;
  return s;
}

/** Keeps only values valid for the generation (falls back to defaults). */
export function sanitizeSettings(settings, gen) {
  const s = { ...defaultSettings(), ...(settings || {}) };
  for (const o of allOptions()) {
    if (!optionAvailable(o, gen)) { s[o.key] = o.def; continue; }
    if (o.type === 'select' && !o.options.some((c) => c[0] === s[o.key] && choiceAvailable(c, gen))) {
      const first = o.options.find((c) => choiceAvailable(c, gen));
      s[o.key] = o.options.some((c) => c[0] === o.def && choiceAvailable(c, gen)) ? o.def : first[0];
    }
    if (o.type === 'int') s[o.key] = Math.max(o.min, Math.min(o.max, Number(s[o.key]) || 0));
  }
  return s;
}

export function countChanged(settings, sectionId, gen) {
  const def = defaultSettings();
  let n = 0;
  for (const o of allOptions()) {
    if (o.section !== sectionId || !optionAvailable(o, gen)) continue;
    if (settings[o.key] !== def[o.key]) n++;
  }
  return n;
}

export function defaultRando(gen) {
  return {
    enabled: true,
    preset: 'classic',
    settings: sanitizeSettings(PRESETS.find((p) => p.id === 'classic').build(), gen),
    // instant text, levels of the fixed Pokémon adapted, no Lucky Egg given (each kept where the game supports it)
    miscTweaks: [8, 65536, 8192],
    limitGens: [],
    limitEvoRelatives: false,
    seed: randomSeed(),
    // SLT: one ROM for everybody, or one ROM (one seed) per player
    perPlayer: false,
    // SLT: secondary shop clerks randomized (never a Master Ball nor an item sold for more than its price)
    shops: gen >= 3 ? 'secondary' : 'off',
  };
}

export function randomSeed() {
  return String(Math.floor(100000000 + Math.random() * 899999999));
}

// ------------------------------------------------------------------ presets

const P = (o) => ({ ...defaultSettings(), ...o });

const SOULLINK_BASE = {
  changeImpossibleEvolutions: true,
  fullHMCompat: true,
  keepFieldMoveTMs: true,
  keepFieldMoveTutors: true,
};

export const PRESETS = [
  {
    id: 'vanilla', emoji: '🎮', label: 'Jeu original',
    desc: 'Aucun hasard : le jeu tel qu’il est sorti.',
    disables: true, build: () => P({}),
  },
  {
    id: 'light', emoji: '🌱', label: 'Légère',
    desc: 'Seuls les Pokémon sauvages, les starters et les Pokémon fixes changent. Dresseurs, attaques et objets restent d’origine.',
    build: () => P({
      ...SOULLINK_BASE,
      wildPokemonMod: 'AREA_MAPPING', wildPokemonRestrictionMod: 'SIMILAR_STRENGTH', blockWildLegendaries: true,
      startersMod: 'COMPLETELY_RANDOM', staticPokemonMod: 'RANDOM_MATCHING', limitMainGameLegendaries: true,
      inGameTradesMod: 'RANDOMIZE_GIVEN',
    }),
  },
  {
    id: 'classic', emoji: '🔗', label: 'Randomisé classique',
    desc: 'Le format le plus joué : sauvages, starters, dresseurs, talents, attaques et objets au hasard, avec des sécurités pour ne jamais être bloqué.',
    build: () => P({
      ...SOULLINK_BASE,
      makeEvolutionsEasier: true,
      abilitiesMod: 'RANDOMIZE', abilitiesFollowEvolutions: true, banTrappingAbilities: true, banNegativeAbilities: false, banBadAbilities: true,
      allowWonderGuard: false,
      startersMod: 'COMPLETELY_RANDOM', staticPokemonMod: 'COMPLETELY_RANDOM',
      inGameTradesMod: 'RANDOMIZE_GIVEN',
      wildPokemonMod: 'AREA_MAPPING', wildPokemonRestrictionMod: 'NONE', blockWildLegendaries: false,
      useMinimumCatchRate: false,
      trainersMod: 'RANDOM', trainersUsePokemonOfSimilarStrength: false, rivalCarriesStarterThroughout: true, trainersBlockLegendaries: false,
      trainersForceFullyEvolved: true, trainersForceFullyEvolvedLevel: 50, betterTrainerMovesets: true,
      movesetsMod: 'RANDOM_PREFER_SAME_TYPE', startWithGuaranteedMoves: true, guaranteedMoveCount: 4,
      movesetsForceGoodDamaging: true, movesetsGoodDamagingPercent: 25, blockBrokenMovesetMoves: false,
      tmsMod: 'RANDOM', blockBrokenTMMoves: false, tmsHmsCompatibilityMod: 'COMPLETELY_RANDOM', tmLevelUpMoveSanity: true, tmsFollowEvolutions: true,
      moveTutorMovesMod: 'RANDOM', blockBrokenTutorMoves: true, tutorsForceGoodDamaging: true, tutorsGoodDamagingPercent: 25,
      moveTutorsCompatibilityMod: 'COMPLETELY_RANDOM', tutorLevelUpMoveSanity: true, tutorFollowEvolutions: true,
      fieldItemsMod: 'RANDOM', banBadRandomFieldItems: true,
      pickupItemsMod: 'RANDOM', banBadRandomPickupItems: true,
    }),
  },
  {
    id: 'wild', emoji: '🌪️', label: 'Sauvage',
    desc: 'Comme « Randomisé classique », avec en plus les types, les évolutions et les statistiques mélangés : chaque Pokémon est à redécouvrir.',
    build: () => ({
      ...PRESETS.find((p) => p.id === 'classic').build(),
      baseStatisticsMod: 'SHUFFLE', baseStatsFollowEvolutions: true,
      typesMod: 'RANDOM_FOLLOW_EVOLUTIONS',
      evolutionsMod: 'RANDOM', evosSimilarStrength: true, evosMaxThreeStages: true, evosForceChange: true,
      trainersMod: 'DISTRIBUTED', additionalBossTrainerPokemon: 1,
      fieldItemsMod: 'RANDOM_EVEN',
      randomizeWildPokemonHeldItems: true, banBadRandomWildPokemonHeldItems: true,
    }),
  },
  {
    id: 'chaos', emoji: '🔥', label: 'Chaos total',
    desc: 'Absolument tout est au hasard, sans garde-fou de force : très imprévisible, parfois déséquilibré.',
    build: () => P({
      ...SOULLINK_BASE,
      baseStatisticsMod: 'RANDOM', typesMod: 'COMPLETELY_RANDOM', abilitiesMod: 'RANDOMIZE',
      evolutionsMod: 'RANDOM', evosForceChange: true,
      startersMod: 'COMPLETELY_RANDOM', staticPokemonMod: 'COMPLETELY_RANDOM', limit600: true,
      inGameTradesMod: 'RANDOMIZE_GIVEN_AND_REQUESTED', randomizeInGameTradesNicknames: true, randomizeInGameTradesOTs: true,
      randomizeInGameTradesIVs: true, randomizeInGameTradesItems: true,
      wildPokemonMod: 'RANDOM', blockWildLegendaries: false, useTimeBasedEncounters: true,
      randomizeWildPokemonHeldItems: true,
      trainersMod: 'RANDOM', trainersBlockLegendaries: false, randomizeTrainerNames: true, randomizeTrainerClassNames: true,
      randomizeHeldItemsForBossTrainerPokemon: true, randomizeHeldItemsForImportantTrainerPokemon: true, randomizeHeldItemsForRegularTrainerPokemon: true,
      randomizeMovePowers: true, randomizeMoveAccuracies: true, randomizeMovePPs: true, randomizeMoveTypes: true, randomizeMoveCategory: true,
      movesetsMod: 'COMPLETELY_RANDOM', startWithGuaranteedMoves: true, guaranteedMoveCount: 4,
      tmsMod: 'RANDOM', tmsHmsCompatibilityMod: 'COMPLETELY_RANDOM',
      moveTutorMovesMod: 'RANDOM', moveTutorsCompatibilityMod: 'COMPLETELY_RANDOM',
      fieldItemsMod: 'RANDOM', pickupItemsMod: 'RANDOM',
    }),
  },
  {
    id: 'surprise', emoji: '🎲', label: 'Surprise',
    desc: 'Les options elles-mêmes sont tirées au sort (en gardant les sécurités pour ne pas être bloqué).',
    build: (gen) => surpriseSettings(gen),
  },
];

/** Picks a random value for every option, keeping the anti-softlock safeties. */
export function surpriseSettings(gen) {
  const s = defaultSettings();
  const r = Math.random;
  for (const o of allOptions()) {
    if (!optionAvailable(o, gen)) continue;
    if (o.type === 'bool') s[o.key] = r() < 0.4;
    else if (o.type === 'select') {
      const choices = o.options.filter((c) => choiceAvailable(c, gen));
      s[o.key] = choices[Math.floor(r() * choices.length)][0];
    } else if (o.type === 'int') {
      const steps = Math.round((o.max - o.min) / o.step);
      let k = Math.floor(r() * (steps + 1));
      if (o.unit === '%' && /Level/.test(o.key)) {
        // level modifiers stay within -15 % .. +15 %
        k = Math.round(steps / 2) + Math.floor(r() * 7) - 3;
      }
      s[o.key] = o.min + k * o.step;
    }
  }
  Object.assign(s, SOULLINK_BASE);
  if (s.movesetsMod === 'METRONOME_ONLY' && r() < 0.8) s.movesetsMod = 'RANDOM_PREFER_SAME_TYPE';
  if (s.minimumCatchRateLevel === 5 && r() < 0.7) s.minimumCatchRateLevel = 2;
  return s;
}

export function presetById(id) {
  return PRESETS.find((p) => p.id === id);
}

/** Short human summary of what a settings object changes. */
export function summarize(rando, gen) {
  if (!rando || !rando.enabled) return ['Aucune randomisation (jeu original)'];
  const s = rando.settings || {};
  const out = [];
  const label = (key) => {
    const o = allOptions().find((x) => x.key === key);
    const c = o && o.options ? o.options.find((x) => x[0] === s[key]) : null;
    return c ? c[1] : s[key];
  };
  const add = (key, prefix) => { if (s[key] && s[key] !== 'UNCHANGED' && s[key] !== 'NONE') out.push(`${prefix} : ${label(key)}`); };
  add('wildPokemonMod', 'Sauvages');
  add('startersMod', 'Starters');
  add('trainersMod', 'Dresseurs');
  add('staticPokemonMod', 'Pokémon fixes');
  add('typesMod', 'Types');
  if (gen >= 3) add('abilitiesMod', 'Talents');
  add('evolutionsMod', 'Évolutions');
  add('baseStatisticsMod', 'Stats');
  add('movesetsMod', 'Attaques');
  add('tmsMod', 'CT');
  add('fieldItemsMod', 'Objets');
  if (!out.length) out.push('Options personnalisées (aucun changement majeur)');
  return out;
}
