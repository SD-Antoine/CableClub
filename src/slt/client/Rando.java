package slt.client;

import com.dabomstew.pkrandom.FileFunctions;
import slt.Http;
import slt.Json;
import slt.Log;
import com.dabomstew.pkrandom.MiscTweak;
import com.dabomstew.pkrandom.RandomSource;
import com.dabomstew.pkrandom.Randomizer;
import com.dabomstew.pkrandom.Settings;
import com.dabomstew.pkrandom.Version;
import com.dabomstew.pkrandom.pokemon.Encounter;
import com.dabomstew.pkrandom.pokemon.EncounterSet;
import com.dabomstew.pkrandom.pokemon.Evolution;
import com.dabomstew.pkrandom.pokemon.ExpCurve;
import com.dabomstew.pkrandom.pokemon.GenRestrictions;
import com.dabomstew.pkrandom.pokemon.Pokemon;
import com.dabomstew.pkrandom.pokemon.StaticEncounter;
import com.dabomstew.pkrandom.pokemon.Trainer;
import com.dabomstew.pkrandom.pokemon.TrainerPokemon;
import com.dabomstew.pkrandom.romhandlers.Gen1RomHandler;
import com.dabomstew.pkrandom.romhandlers.Gen2RomHandler;
import com.dabomstew.pkrandom.romhandlers.Gen3RomHandler;
import com.dabomstew.pkrandom.romhandlers.Gen4RomHandler;
import com.dabomstew.pkrandom.romhandlers.Gen5RomHandler;
import com.dabomstew.pkrandom.romhandlers.RomHandler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bridge to the Universal Pokemon Randomizer ZX engine (GPLv3), compiled into
 * SLT. Loads a ROM supplied by the player, applies the chosen options,
 * writes the randomized ROM and extracts the data the Soul Link tracker needs
 * (species types, wild areas, level caps).
 */
public final class Rando {

    private static final Object LOCK = new Object();
    private static ResourceBundle bundle;

    /** Settings fields that must not be set from the UI. */
    private static final Set<String> RESERVED = Set.of("customNames", "romName", "updatedFromOldVersion",
            "currentRestrictions", "currentMiscTweaks", "customStarters", "raceMode");

    private Rando() {
    }

    public static String uprVersion() {
        return Version.VERSION_STRING;
    }

    private static ResourceBundle bundle() {
        if (bundle == null) {
            bundle = ResourceBundle.getBundle("com/dabomstew/pkrandom/newgui/Bundle");
        }
        return bundle;
    }

    // ------------------------------------------------------------- loading

    private static RomHandler load(String path) {
        File f = new File(path);
        if (!f.isFile()) {
            throw new Http.Fail(400, "Fichier introuvable : " + path);
        }
        RomHandler.Factory[] factories = {
                new Gen1RomHandler.Factory(), new Gen2RomHandler.Factory(), new Gen3RomHandler.Factory(),
                new Gen4RomHandler.Factory(), new Gen5RomHandler.Factory()
        };
        for (RomHandler.Factory factory : factories) {
            boolean loadable;
            try {
                loadable = factory.isLoadable(f.getAbsolutePath());
            } catch (RuntimeException e) {
                loadable = false;
            }
            if (loadable) {
                RomHandler rh = factory.create(RandomSource.instance());
                boolean ok;
                try {
                    ok = rh.loadRom(f.getAbsolutePath());
                } catch (RuntimeException e) {
                    Log.error("Lecture de la ROM impossible : " + path, e);
                    ok = false;
                }
                if (!ok) {
                    throw new Http.Fail(400, "Cette ROM a été reconnue mais n'a pas pu être lue : fichier corrompu, "
                            + "modifié (hack, patch de traduction) ou version non prise en charge.");
                }
                return rh;
            }
        }
        throw new Http.Fail(400, "ROM non reconnue. Formats acceptés : Pokémon génération 1 à 5 "
                + "(.gb, .gbc, .gba, .nds), versions officielles non modifiées.");
    }

    /** Runs {@code f} on the ROM handler of a file (engine lock held: the engine is not thread-safe). */
    public static <T> T withHandler(String path, java.util.function.Function<RomHandler, T> f) {
        synchronized (LOCK) {
            return f.apply(load(path));
        }
    }

    // ------------------------------------------------------------- inspect

    public static Map<String, Object> inspect(String path) {
        synchronized (LOCK) {
            RomHandler rh = load(path);
            return describe(rh);
        }
    }

    private static Map<String, Object> describe(RomHandler rh) {
        String romName = rh.getROMName();
        String entry = romName.startsWith("Pokemon ") ? romName.substring(8) : romName;
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("abilities", rh.abilitiesPerPokemon() > 0);
        caps.put("moveTutors", rh.hasMoveTutors());
        caps.put("timeBasedEncounters", rh.hasTimeBasedEncounters());
        caps.put("staticPokemon", rh.canChangeStaticPokemon());
        caps.put("shops", rh.hasShopRandomization());
        caps.put("physicalSpecialSplit", rh.hasPhysicalSpecialSplit());
        caps.put("starterHeldItems", rh.supportsStarterHeldItems());
        caps.put("fourStartingMoves", rh.supportsFourStartingMoves());
        caps.put("mainGameLegendaries", rh.hasMainGameLegendaries());
        caps.put("staticMusicFix", rh.hasStaticMusicFix());
        caps.put("wildAltFormes", rh.hasWildAltFormes());
        caps.put("starterAltFormes", rh.hasStarterAltFormes());
        caps.put("staticAltFormes", rh.hasStaticAltFormes());
        caps.put("functionalFormes", rh.hasFunctionalFormes());
        caps.put("isYellow", rh.isYellow());
        caps.put("miscTweaks", (long) rh.miscTweaksAvailable());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("romName", romName);
        out.put("romCode", rh.getROMCode());
        out.put("gen", (long) rh.generationOfPokemon());
        out.put("gameId", gameIdFor(entry));
        out.put("lang", langFor(entry));
        out.put("supportLevel", rh.getSupportLevel());
        out.put("extension", rh.getDefaultExtension());
        out.put("platform", platformFor(rh));
        out.put("valid", rh.isRomValid());
        out.put("capabilities", caps);
        return out;
    }

    static String platformFor(RomHandler rh) {
        switch (rh.generationOfPokemon()) {
            case 1:
                return "gb";
            case 2:
                return "gbc";
            case 3:
                return "gba";
            default:
                return "nds";
        }
    }

    static String gameIdFor(String entry) {
        String e = entry.toLowerCase();
        String[][] table = {
                {"fire red", "firered"}, {"leaf green", "leafgreen"}, {"heartgold", "heartgold"},
                {"soulsilver", "soulsilver"}, {"black 2", "black2"}, {"white 2", "white2"},
                {"red", "red"}, {"blue", "blue"}, {"green", "blue"}, {"yellow", "yellow"},
                {"gold", "gold"}, {"silver", "silver"}, {"crystal", "crystal"},
                {"ruby", "ruby"}, {"sapphire", "sapphire"}, {"emerald", "emerald"},
                {"diamond", "diamond"}, {"pearl", "pearl"}, {"platinum", "platinum"},
                {"black", "black"}, {"white", "white"}
        };
        for (String[] row : table) {
            if (e.startsWith(row[0])) {
                return row[1];
            }
        }
        return null;
    }

    static String langFor(String entry) {
        Matcher m = Pattern.compile("\\(([A-Z])[^)]*\\)").matcher(entry);
        if (!m.find()) {
            return "?";
        }
        switch (m.group(1)) {
            case "F": return "fr";
            case "G": return "de";
            case "S": return "es";
            case "I": return "it";
            case "J": return "ja";
            case "K": return "ko";
            default: return "en";
        }
    }

    // ------------------------------------------------------------ analyze

    public static Map<String, Object> analyze(String path, Consumer<String> progress) {
        synchronized (LOCK) {
            progress.accept("Chargement de la ROM…");
            RomHandler rh = load(path);
            progress.accept("Lecture des données du jeu…");
            List<String> warnings = new ArrayList<>();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rom", describe(rh));
            out.put("romData", extract(rh, warnings));
            out.put("warnings", warnings);
            return out;
        }
    }

    // ------------------------------------------------------------ tracker

    /** Bumped when the content of the tracker info changes (invalidates the cache). */
    private static final String TRACKER_INFO_VERSION = "slt-2";

    /**
     * Data the in-game tracker needs (map and location names, species names,
     * text table), written once per ROM file in {@code cacheDir}.
     */
    public static Path trackerInfo(String romPath, Path cacheDir) throws IOException {
        File f = new File(romPath);
        String key = TRACKER_INFO_VERSION + "|" + f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        Path out = cacheDir.resolve("suivi-" + Integer.toHexString(key.hashCode()) + ".json");
        if (Files.isRegularFile(out)) {
            return out;
        }
        Map<String, Object> info = new LinkedHashMap<>();
        synchronized (LOCK) {
            RomHandler rh = load(romPath);
            Map<String, Object> d = describe(rh);
            info.put("gameId", d.get("gameId"));
            info.put("gen", d.get("gen"));
            info.put("lang", d.get("lang"));
            info.put("maps", MapTables.export(rh));
            info.put("locations", MapTables.locations(rh));
            info.putAll(MapTables.tracker(rh));
            // names of the moves and abilities in the game's language (battles between players)
            List<Object> moves = new ArrayList<>();
            for (com.dabomstew.pkrandom.pokemon.Move mv : rh.getMoves()) {
                moves.add(mv == null ? null : mv.name);
            }
            info.put("moves", moves);
            List<Object> abilities = new ArrayList<>();
            if (rh.abilitiesPerPokemon() > 0) {
                for (int i = 0; i < 400; i++) {
                    String n;
                    try {
                        n = rh.abilityName(i);
                    } catch (RuntimeException e) {
                        break;
                    }
                    if (i > 0 && (n == null || n.isBlank())) {
                        break;
                    }
                    abilities.add(n);
                }
            }
            info.put("abilities", abilities);
        }
        Files.createDirectories(cacheDir);
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.writeString(tmp, Json.stringify(info));
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    // ---------------------------------------------------------- randomize

    public static Map<String, Object> randomize(String sourcePath, String outputPath, Map<String, Object> options,
                                                long seed, Consumer<String> progress) throws IOException {
        synchronized (LOCK) {
            List<String> warnings = new ArrayList<>();
            progress.accept("Chargement de la ROM…");
            RomHandler rh = load(sourcePath);

            progress.accept("Préparation des options…");
            Settings settings = new Settings();
            Map<String, Object> fields = Json.obj(options.get("settings"));
            if (fields != null) {
                applyFields(settings, fields, warnings);
            }
            settings.setCustomNames(FileFunctions.getCustomNames());
            // the engine writes the settings string (which embeds the ROM name) to its log
            settings.setRomName(rh.getROMName());

            List<Object> gens = Json.arr(options.get("limitGens"));
            if (gens != null && !gens.isEmpty()) {
                GenRestrictions gr = new GenRestrictions();
                for (Object g : gens) {
                    switch (((Number) g).intValue()) {
                        case 1: gr.allow_gen1 = true; break;
                        case 2: gr.allow_gen2 = true; break;
                        case 3: gr.allow_gen3 = true; break;
                        case 4: gr.allow_gen4 = true; break;
                        case 5: gr.allow_gen5 = true; break;
                        default: break;
                    }
                }
                gr.allow_evolutionary_relatives = Json.bool(options, "limitEvoRelatives", false);
                gr.limitToGen(rh.generationOfPokemon());
                if (!gr.nothingSelected()) {
                    settings.setCurrentRestrictions(gr);
                    settings.setLimitPokemon(true);
                }
            }

            int tweaks = 0;
            List<Object> tweakList = Json.arr(options.get("miscTweaks"));
            if (tweakList != null) {
                for (Object t : tweakList) {
                    if (t instanceof Number) {
                        tweaks |= ((Number) t).intValue();
                    }
                }
            }
            int available = rh.miscTweaksAvailable();
            if ((tweaks & ~available) != 0) {
                warnings.add("Certains ajustements ne sont pas disponibles pour ce jeu et ont été ignorés.");
            }
            settings.setCurrentMiscTweaks(tweaks & available);

            guardCapabilities(settings, rh, warnings);
            Settings.TweakForROMFeedback fb = settings.tweakForRom(rh);
            if (fb.isRemovedCodeTweaks()) {
                warnings.add("Des ajustements incompatibles avec cette ROM ont été retirés.");
            }

            // outputPath has no extension: the engine knows the right one (.gbc/.gba/.nds)
            File out = new File(outputPath + "." + rh.getDefaultExtension());
            if (out.getAbsoluteFile().equals(new File(sourcePath).getAbsoluteFile())) {
                throw new Http.Fail(400, "La ROM générée ne peut pas écraser la ROM d'origine.");
            }
            Files.createDirectories(out.getAbsoluteFile().getParentFile().toPath());

            progress.accept("Randomisation en cours (seed " + seed + ")…");
            ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
            PrintStream log = new PrintStream(logBytes, false, "UTF-8");
            Randomizer randomizer = new Randomizer(settings, rh, bundle(), false);
            try {
                randomizer.randomize(out.getAbsolutePath(), log, seed);
            } catch (RuntimeException e) {
                Log.error("Échec de randomisation", e);
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                throw new Http.Fail(500, "Le randomizer a échoué : " + msg
                        + ". Essayez d'autres options ou une autre seed.");
            }
            log.flush();

            Path logPath = Path.of(out.getAbsolutePath() + ".log");
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] body = logBytes.toByteArray();
            byte[] all = new byte[bom.length + body.length];
            System.arraycopy(bom, 0, all, 0, bom.length);
            System.arraycopy(body, 0, all, bom.length, body.length);
            Files.write(logPath, all);

            progress.accept("Lecture des données du jeu randomisé…");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("outputPath", out.getAbsolutePath());
            result.put("logPath", logPath.toString());
            result.put("seed", String.valueOf(seed));
            result.put("settingsString", Version.VERSION + settings.toString());
            result.put("uprVersion", Version.VERSION_STRING);
            result.put("rom", describe(rh));
            result.put("romData", extract(rh, warnings));
            result.put("warnings", warnings);
            return result;
        }
    }

    // ------------------------------------------------------------ settings

    private static void applyFields(Settings s, Map<String, Object> fields, List<String> warnings) {
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String name = e.getKey();
            if (RESERVED.contains(name)) {
                continue;
            }
            Field f;
            try {
                f = Settings.class.getDeclaredField(name);
            } catch (NoSuchFieldException ex) {
                warnings.add("Option inconnue ignorée : " + name);
                continue;
            }
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                setField(s, f, e.getValue());
            } catch (Exception ex) {
                warnings.add("Valeur invalide pour " + name + " : " + e.getValue());
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setField(Object target, Field f, Object value) throws IllegalAccessException {
        f.setAccessible(true);
        Class<?> t = f.getType();
        if (t == boolean.class) {
            f.setBoolean(target, Boolean.TRUE.equals(value));
        } else if (t == int.class) {
            f.setInt(target, value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(String.valueOf(value)));
        } else if (t.isEnum()) {
            f.set(target, Enum.valueOf((Class<Enum>) t, String.valueOf(value)));
        } else {
            throw new IllegalArgumentException("type non pris en charge");
        }
    }

    private static void setEnum(Settings s, String field, Object value) {
        try {
            Field f = Settings.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(s, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Disables options the loaded game cannot support (mirrors the UPR GUI). */
    private static void guardCapabilities(Settings s, RomHandler rh, List<String> warnings) {
        int gen = rh.generationOfPokemon();
        if (gen < 3) {
            if (s.getEvolutionsMod() == Settings.EvolutionsMod.RANDOM_EVERY_LEVEL) {
                setEnum(s, "evolutionsMod", Settings.EvolutionsMod.RANDOM);
                warnings.add("« Évolution aléatoire à chaque niveau » n'existe qu'à partir de la 3e génération : remplacé par « Aléatoire ».");
            }
            s.setDoubleBattleMode(false);
            s.setAdditionalBossTrainerPokemon(0);
            s.setAdditionalImportantTrainerPokemon(0);
            s.setAdditionalRegularTrainerPokemon(0);
            s.setRandomizeHeldItemsForBossTrainerPokemon(false);
            s.setRandomizeHeldItemsForImportantTrainerPokemon(false);
            s.setRandomizeHeldItemsForRegularTrainerPokemon(false);
            s.setConsumableItemsOnlyForTrainers(false);
            s.setSensibleItemsOnlyForTrainers(false);
            s.setHighestLevelGetsItemsForTrainers(false);
            s.setEliteFourUniquePokemonNumber(0);
            s.setBetterTrainerMovesets(false);
            if (s.getSelectedEXPCurve() == ExpCurve.ERRATIC || s.getSelectedEXPCurve() == ExpCurve.FLUCTUATING) {
                s.setSelectedEXPCurve(ExpCurve.MEDIUM_FAST);
            }
        }
        if (s.getSelectedEXPCurve() == null) {
            s.setSelectedEXPCurve(ExpCurve.MEDIUM_FAST);
        }
        if (gen < 4) {
            s.setBanIrregularAltFormes(false);
        }
        if (gen != 5) {
            s.setBalanceShakingGrass(false);
            if (s.getTrainersMod() == Settings.TrainersMod.MAINPLAYTHROUGH) {
                setEnum(s, "trainersMod", Settings.TrainersMod.DISTRIBUTED);
            }
        }
        if (gen < 7) {
            s.setEvolutionMovesForAll(false);
            s.setEvosAllowAltFormes(false);
            s.setShinyChance(false);
            s.setSwapTrainerMegaEvos(false);
            s.setSwapStaticMegaEvos(false);
        }
        if (!rh.hasStaticMusicFix()) {
            s.setCorrectStaticMusic(false);
        }
        if (!rh.hasMainGameLegendaries()) {
            s.setLimitMainGameLegendaries(false);
        }
        if (!rh.hasStarterAltFormes()) {
            s.setAllowStarterAltFormes(false);
        }
        if (!rh.hasStaticAltFormes()) {
            s.setAllowStaticAltFormes(false);
        }
        if (!rh.hasWildAltFormes()) {
            s.setAllowWildAltFormes(false);
        }
        if (!rh.hasFunctionalFormes()) {
            s.setAllowTrainerAlternateFormes(false);
        }
        if (rh.abilitiesPerPokemon() == 0) {
            setEnum(s, "pickupItemsMod", Settings.PickupItemsMod.UNCHANGED);
            s.setBanBadRandomPickupItems(false);
        }
        if (s.isUpdateMoves()) {
            int g = Math.max(gen + 1, Math.min(9, s.getUpdateMovesToGeneration()));
            s.setUpdateMovesToGeneration(g);
        }
        if (s.isUpdateBaseStats()) {
            int g = Math.max(Math.max(6, gen + 1), Math.min(9, s.getUpdateBaseStatsToGeneration()));
            s.setUpdateBaseStatsToGeneration(g);
        }
    }

    // ------------------------------------------------------------- extract

    private static Map<String, Object> extract(RomHandler rh, List<String> warnings) {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> rom = describe(rh);
        data.put("romName", rom.get("romName"));
        data.put("gameId", rom.get("gameId"));
        data.put("gen", rom.get("gen"));
        data.put("lang", rom.get("lang"));

        try {
            List<Object> mons = new ArrayList<>();
            for (Pokemon p : rh.getPokemon()) {
                if (p == null) {
                    continue;
                }
                List<Object> types = new ArrayList<>();
                if (p.primaryType != null) {
                    types.add(p.primaryType.name());
                }
                if (p.secondaryType != null && p.secondaryType != p.primaryType) {
                    types.add(p.secondaryType.name());
                }
                List<Object> evos = new ArrayList<>();
                for (Evolution ev : p.evolutionsFrom) {
                    if (ev.to != null && !evos.contains((long) ev.to.number)) {
                        evos.add((long) ev.to.number);
                    }
                }
                int bst = gen1Bst(rh, p);
                mons.add(Json.map("n", (long) p.number, "name", p.name, "t", types, "evo", evos, "bst", (long) bst));
            }
            data.put("pokemon", mons);
        } catch (Exception e) {
            warnings.add("Liste des Pokémon indisponible : " + e.getMessage());
            Log.error("extract pokemon", e);
        }

        try {
            List<Object> starters = new ArrayList<>();
            for (Pokemon p : rh.getStarters()) {
                if (p != null) {
                    starters.add((long) p.number);
                }
            }
            data.put("starters", starters);
        } catch (Exception e) {
            Log.error("extract starters", e);
        }

        try {
            List<Object> areas = new ArrayList<>();
            for (EncounterSet es : rh.getEncounters(true)) {
                String[] zm = zoneOf(es.displayName);
                Map<Integer, long[]> byMon = new TreeMap<>();
                List<Integer> order = new ArrayList<>();
                for (Encounter enc : es.encounters) {
                    if (enc.pokemon == null) {
                        continue;
                    }
                    int n = enc.pokemon.number;
                    int min = enc.level;
                    int max = Math.max(enc.level, enc.maxLevel);
                    long[] range = byMon.get(n);
                    if (range == null) {
                        byMon.put(n, new long[]{min, max});
                        order.add(n);
                    } else {
                        range[0] = Math.min(range[0], min);
                        range[1] = Math.max(range[1], max);
                    }
                }
                List<Object> list = new ArrayList<>();
                for (int n : order) {
                    long[] r = byMon.get(n);
                    list.add(Json.map("n", (long) n, "min", r[0], "max", r[1]));
                }
                areas.add(Json.map("name", es.displayName, "zone", zm[0], "method", zm[1], "mons", list));
            }
            data.put("areas", areas);
        } catch (Exception e) {
            warnings.add("Zones de rencontre indisponibles : " + e.getMessage());
            Log.error("extract areas", e);
        }

        // map id -> zone name, used by the automatic tracker to know where the player is
        data.put("maps", MapTables.export(rh));

        try {
            data.put("caps", levelCaps(rh.getTrainers(), rh.generationOfPokemon()));
        } catch (Exception e) {
            warnings.add("Niveaux des dresseurs indisponibles : " + e.getMessage());
            Log.error("extract caps", e);
        }

        try {
            List<Object> statics = new ArrayList<>();
            if (rh.canChangeStaticPokemon()) {
                for (StaticEncounter se : rh.getStaticPokemon()) {
                    if (se.pkmn != null) {
                        statics.add(Json.map("n", (long) se.pkmn.number, "level", (long) se.level));
                    }
                }
            }
            data.put("statics", statics);
        } catch (Exception e) {
            Log.error("extract statics", e);
        }
        return data;
    }

    private static int gen1Bst(RomHandler rh, Pokemon p) {
        if (rh.generationOfPokemon() == 1) {
            return p.hp + p.attack + p.defense + p.special + p.speed;
        }
        return p.hp + p.attack + p.defense + p.spatk + p.spdef + p.speed;
    }

    private static final Pattern GEN1_AREA = Pattern.compile(
            "^(Grass/Cave|Surfing|Super Rod Fishing|Old Rod Fishing|Good Rod Fishing) on (.+)$");
    private static final Pattern TRAILING_PAREN = Pattern.compile("\\s*\\((Morning|Day|Night|Spring|Summer|Autumn|Winter)\\)$");
    private static final String[] SUFFIXES = {
            " Night Fishing Replacement", " Hoenn/Sinnoh Radio", " Swarm/Radar/GBA", " Morning Grass/Cave",
            " Day Grass/Cave", " Night Grass/Cave", " Doubles Grass", " Shaking Spots", " Surfing Spots",
            " Fishing Spots", " Fishing Swarm", " Grass/Cave", " Rock Smash", " Super Rod", " Good Rod",
            " Old Rod", " Surfing", " Fishing", " Headbutt", " Swarms", " Filler"
    };

    /** Splits a UPR area name into {zone, method}. */
    static String[] zoneOf(String displayName) {
        String s = displayName == null ? "" : displayName.trim();
        Matcher m = GEN1_AREA.matcher(s);
        if (m.matches()) {
            return new String[]{m.group(2).trim(), m.group(1)};
        }
        String base = TRAILING_PAREN.matcher(s).replaceAll("");
        for (String suffix : SUFFIXES) {
            if (base.endsWith(suffix) && base.length() > suffix.length()) {
                return new String[]{base.substring(0, base.length() - suffix.length()).trim(), suffix.trim()};
            }
        }
        return new String[]{base, "Spécial"};
    }

    private static final Pattern GYM_TAG = Pattern.compile("^GYM(\\d+)(-LEADER)?$");

    private record Battle(int level, String trainer) {
    }

    /**
     * Level cap of each gym / league battle, in story order: the highest level
     * of the leader's team the FIRST time you face them. The engine also tags
     * rematches and Challenge Mode teams as leaders, hence the minimum over
     * the leader's battles. Games without leader tags (gens 1-2) tag the whole
     * gym: the leader is then the strongest trainer.
     */
    static List<Object> levelCaps(List<Trainer> trainers, int gen) {
        Map<Integer, List<Battle>> leaders = new TreeMap<>();
        Map<Integer, Battle> wholeGym = new TreeMap<>();
        Map<String, List<Battle>> league = new LinkedHashMap<>();
        for (String k : List.of("ELITE1", "ELITE2", "ELITE3", "ELITE4", "CHAMPION")) {
            league.put(k, new ArrayList<>());
        }
        for (Trainer t : trainers) {
            if (t == null || t.tag == null) {
                continue;
            }
            int max = 0;
            for (TrainerPokemon tp : t.pokemon) {
                max = Math.max(max, tp.level);
            }
            if (max <= 0) {
                continue;
            }
            Battle b = new Battle(max, t.fullDisplayName);
            Matcher m = GYM_TAG.matcher(t.tag);
            if (m.matches()) {
                int n = Integer.parseInt(m.group(1));
                // Black/White 2 tag optional or alternative leaders as gyms 9+ (Striaton trio)
                if (gen == 5 && n > 8) {
                    continue;
                }
                if (m.group(2) != null) {
                    leaders.computeIfAbsent(n, k -> new ArrayList<>()).add(b);
                } else {
                    // the whole gym is tagged: the leader is the strongest trainer (name only if unambiguous)
                    Battle cur = wholeGym.get(n);
                    if (cur == null || b.level() > cur.level()) {
                        wholeGym.put(n, b);
                    } else if (b.level() == cur.level() && cur.trainer() != null && !cur.trainer().equals(b.trainer())) {
                        wholeGym.put(n, new Battle(cur.level(), null));
                    }
                }
                continue;
            }
            String tag = t.tag.replaceAll("-.*$", "");
            if (league.containsKey(tag)) {
                league.get(tag).add(b);
            }
        }
        java.util.TreeSet<Integer> gyms = new java.util.TreeSet<>(leaders.keySet());
        gyms.addAll(wholeGym.keySet());
        List<Object> caps = new ArrayList<>();
        int index = 0;
        for (int n : gyms) {
            Battle b = leaders.containsKey(n) ? first(leaders.get(n)) : wholeGym.get(n);
            index++;
            caps.add(Json.map("key", "GYM" + n, "label", "Arène " + index, "trainer", b.trainer(), "level", (long) b.level()));
        }
        String[] labels = {"Conseil 4 — 1", "Conseil 4 — 2", "Conseil 4 — 3", "Conseil 4 — 4", "Maître"};
        int strongestElite = 0;
        for (String k : List.of("ELITE1", "ELITE2", "ELITE3", "ELITE4")) {
            if (!league.get(k).isEmpty()) {
                strongestElite = Math.max(strongestElite, first(league.get(k)).level());
            }
        }
        int i = 0;
        for (Map.Entry<String, List<Battle>> e : league.entrySet()) {
            if (!e.getValue().isEmpty()) {
                Battle b = first(e.getValue());
                // a "champion" far above the Elite Four is a post-game battle (Alder in Black/White)
                if (e.getKey().equals("CHAMPION") && strongestElite > 0 && b.level() > strongestElite + 15) {
                    i++;
                    continue;
                }
                caps.add(Json.map("key", e.getKey(), "label", labels[i], "trainer", b.trainer(), "level", (long) b.level()));
            }
            i++;
        }
        return caps;
    }

    /** The easiest of several battles against the same trainer: the first encounter. */
    private static Battle first(List<Battle> battles) {
        Battle best = battles.get(0);
        for (Battle b : battles) {
            if (b.level() < best.level()) {
                best = b;
            }
        }
        return best;
    }

    /** Exposes MiscTweak bits so the UI and the engine stay consistent. */
    public static List<Object> miscTweaks() {
        List<Object> out = new ArrayList<>();
        for (MiscTweak t : MiscTweak.allTweaks) {
            out.add(Json.map("value", (long) t.getValue(), "name", t.getTweakName()));
        }
        return out;
    }
}
