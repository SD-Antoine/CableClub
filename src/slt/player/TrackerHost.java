package slt.player;

import slt.Json;
import slt.Log;
import slt.player.track.GameInfo;
import slt.player.track.GameReader;
import slt.player.track.Mem;
import slt.player.track.Mon;
import slt.player.track.Readers;
import slt.player.track.TrackerEngine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Connects the automatic tracker to the running game: every 30 frames the
 * memory areas the reader needs are copied (a few microseconds to one
 * millisecond) and decoded on a separate thread, so the game never waits.
 * The room's decisions are applied here, between two frames, on the live
 * memory: shared deaths (kept at 0 HP), nicknames of the links, items given
 * back, dead Pokémon moved to their PC box.
 */
final class TrackerHost implements Session.Tracker {

    private final TrackerEngine engine;
    private final GameReader reader;
    private final String platform;
    private final boolean sync;
    private final ExecutorService worker;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final long[][] areas;
    private final Path refundsFile;
    private final Set<String> appliedRefunds = new LinkedHashSet<>();
    private volatile Consumer<String> toast = s -> { };
    private volatile Consumer<List<Object>> ack = ids -> { };
    private volatile Map<String, Object> trade;
    private final Set<String> appliedTrades = new HashSet<>();
    private String tradeSent;
    private long tradeSentAt;
    private Consumer<Map<String, Object>> tradeSink = x -> { };
    private volatile Map<String, Object> nicknames = Map.of();
    private volatile List<Object> refunds = List.of();
    private volatile boolean deathBox;
    private volatile Set<Long> release = Set.of();
    /** Living Pokémon of the team to send to the PC (Sololocke: one Pokémon in the team; Hardcore: level cap). */
    private volatile Set<Long> park = Set.of();
    /** Why each of them goes: "solo" or "cap". */
    private volatile Map<String, Object> parkWhy = Map.of();
    /** Hardcore, or the rule "Mode Défini": the game's battle style kept on "Set". */
    private volatile boolean setMode;
    /** Items the room's rules take out of the bag, by category ({@link slt.player.track.ItemRules}): heal, repel… */
    private volatile Set<String> ban = Set.of();
    /** No held items: they go back to the bag (mail excepted). */
    private volatile boolean noHeld;
    /** No Fly: the team forgets the move. */
    private volatile boolean noFly;
    /** The presence in game tells when the player has the controls on the field: for how many images (0: not now). */
    private volatile boolean presenceKnown;
    private volatile int freeFrames;
    /** Held items left on a Pokémon because the bag had no room for them (told once). */
    private final Set<Long> heldNoRoom = new HashSet<>();
    private long lastWrites;
    private final Set<Long> depositFailures = new HashSet<>();
    /** The room's shiny rate, applied in the game (generations 2 and 3), or null. */
    private final ShinyBoost shiny;
    private final ShinyBoost.Live live;
    private CoreHost liveCore;

    private TrackerHost(TrackerEngine engine, GameReader reader, String platform, boolean sync, Path saves, ShinyBoost shiny) {
        this.engine = engine;
        this.reader = reader;
        this.shiny = shiny;
        this.live = new ShinyBoost.Live() {
            @Override
            public byte[] read(long address, int length) {
                ByteBuffer seg = segment(liveCore, address, length);
                return seg == null ? null : Ram.bytes(seg);
            }

            @Override
            public boolean write(long address, byte[] bytes) {
                return TrackerHost.this.write(liveCore, List.of(new GameReader.Write(address, bytes)));
            }
        };
        this.platform = platform;
        this.sync = sync;
        this.areas = reader.areas();
        this.refundsFile = saves.resolve("remboursements.json");
        this.worker = sync ? null : Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "slt-suivi");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        loadRefunds();
    }

    /**
     * Tracker for this game, or null when the game is not supported or
     * unknown ({@code --game}).
     *
     * @param sync decode on the emulation thread (scripts: deterministic)
     */
    static TrackerHost create(Map<String, String> a, Path rom, Path saves, Consumer<Map<String, Object>> sink, boolean sync) {
        String game = a.get("--game");
        GameInfo info = null;
        String infoFile = a.get("--info");
        if (infoFile != null && Files.isRegularFile(Path.of(infoFile))) {
            try {
                info = GameInfo.load(Path.of(infoFile));
                if (game == null) {
                    game = info.game;
                }
            } catch (Exception e) {
                Log.warn("Suivi : données du jeu illisibles : " + e.getMessage());
            }
        }
        if (game == null) {
            Log.info("Suivi automatique indisponible : jeu non identifié.");
            return null;
        }
        if (info == null) {
            info = GameInfo.bare(game, 0);
        }
        GameReader reader = Readers.forGame(game, info);
        if (reader == null) {
            Log.info("Suivi automatique indisponible pour " + game);
            return null;
        }
        String name = rom.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        String platform = name.endsWith(".nds") ? "nds" : name.endsWith(".gba") ? "gba" : "gb";
        TrackerEngine engine = new TrackerEngine(reader, info, saves.resolve("suivi.json"), sink);
        Log.info("Suivi automatique : " + game + " (" + reader.getClass().getSimpleName() + ")");
        ShinyBoost shiny = ShinyBoost.create(reader, rom);
        TrackerHost host = new TrackerHost(engine, reader, platform, sync, saves, shiny);
        if (shiny != null && a.containsKey("--shiny-rate")) {
            shiny.setRate(Integer.parseInt(a.get("--shiny-rate"))); // tests (scripts)
        }
        return host;
    }

    TrackerEngine engine() {
        return engine;
    }

    void setToast(Consumer<String> t) {
        toast = t == null ? s -> { } : t;
    }

    void setAck(Consumer<List<Object>> a) {
        ack = a == null ? ids -> { } : a;
    }

    /** Where trade data and results go (the room, through the application). */
    void setTradeSink(Consumer<Map<String, Object>> t) {
        tradeSink = t == null ? x -> { } : t;
    }

    void toast(String text) {
        toast.accept(text);
    }

    /** Takes the room's directives into account (called by the directive poller). */
    void directives(Map<String, Object> d) {
        List<Object> doomedList = Json.arr(d.get("doomed"));
        Set<Long> doomed = new HashSet<>();
        if (doomedList != null) {
            for (Object o : doomedList) {
                try {
                    doomed.add(Long.parseLong(String.valueOf(o)));
                } catch (NumberFormatException ignored) {
                    // not a tracker id
                }
            }
        }
        engine.setDoomed(doomed);
        Map<String, Object> n = Json.obj(d.get("nicknames"));
        nicknames = n == null ? Map.of() : n;
        List<Object> r = Json.arr(d.get("refunds"));
        refunds = r == null ? List.of() : r;
        deathBox = Json.bool(d, "deathBox", false);
        // the room's shiny rate: k out of 8192 (servers before 0.10 only send "1 in N")
        int shinyNum = slt.ShinyOdds.numerator(d);
        if (shiny != null) {
            shiny.setNumerator(shinyNum);
        }
        if ("nds".equals(platform)) {
            slt.player.track.NdsPkm.shinyNumerator(shinyNum); // the ROM's own test was changed (SltPatches)
        }
        Set<Long> rel = new HashSet<>();
        List<Object> relList = Json.arr(d.get("release"));
        if (relList != null) {
            for (Object o : relList) {
                try {
                    rel.add(Long.parseLong(String.valueOf(o)));
                } catch (NumberFormatException ignored) {
                    // not a tracker id
                }
            }
        }
        release = rel;
        Set<Long> pk = new HashSet<>();
        List<Object> parkList = Json.arr(d.get("park"));
        if (parkList != null) {
            for (Object o : parkList) {
                try {
                    pk.add(Long.parseLong(String.valueOf(o)));
                } catch (NumberFormatException ignored) {
                    // not a tracker id
                }
            }
        }
        park = pk;
        Map<String, Object> why = Json.obj(d.get("parkWhy"));
        parkWhy = why == null ? Map.of() : why;
        setMode = Json.bool(d, "setMode", false);
        Set<String> b = new HashSet<>();
        List<Object> banList = Json.arr(d.get("ban"));
        if (banList != null) {
            for (Object o : banList) {
                b.add(String.valueOf(o));
            }
        }
        ban = b;
        noHeld = Json.bool(d, "noHeld", false);
        noFly = Json.bool(d, "noFly", false);
        trade = Json.obj(d.get("trade"));
    }

    /**
     * What the presence in game sees on this image: the player has the controls on the field (no menu, dialogue or
     * battle), or null when it does not know yet (the player was never seen on the field). The bag and the team are
     * changed by the rules only while the player has the controls, the game holding no copy of them.
     */
    void fieldFree(Boolean free) {
        presenceKnown = free != null;
        freeFrames = Boolean.TRUE.equals(free) ? Math.min(freeFrames + 1, 1 << 20) : 0;
    }

    /** Generation of the game (the ids of its items). */
    private int gen() {
        if (reader instanceof slt.player.track.GbReader gb) {
            return gb.gen();
        }
        if (reader instanceof slt.player.track.NdsReader nds) {
            return nds.gen5() ? 5 : 4;
        }
        return 3;
    }

    // ----------------------------------------------------------------- tick

    @Override
    public void tick(CoreHost core, long frame) {
        applyDeaths(core);
        if (shiny != null) {
            liveCore = core;
            shiny.tick(live);
        }
        if (!busy.compareAndSet(false, true)) {
            return; // previous reading still being decoded
        }
        Mem m = capture(core);
        if (m == null) {
            busy.set(false);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastWrites > 1000 || sync) {
            lastWrites = now;
            try {
                applyWrites(core, m);
            } catch (RuntimeException e) {
                Log.error("Application des règles en jeu", e);
            }
        }
        if (sync) {
            try {
                engine.update(m);
            } finally {
                busy.set(false);
            }
            return;
        }
        worker.execute(() -> {
            try {
                engine.update(m);
            } catch (RuntimeException e) {
                Log.error("Suivi automatique", e);
            } finally {
                busy.set(false);
            }
        });
    }

    /** Copy of the memory the reader needs (fresh arrays: the previous copy may still be decoded). */
    private Mem capture(CoreHost core) {
        Mem m = new Mem();
        for (long[] area : areas) {
            ByteBuffer seg = segment(core, area[0], (int) area[1]);
            if (seg == null) {
                continue;
            }
            int len = (int) Math.min(area[1], seg.capacity());
            byte[] buf = new byte[len];
            Ram.copy(seg, 0, buf, 0, len);
            m.add(area[0], buf);
        }
        return m.blocks().isEmpty() ? null : m;
    }

    /** Live memory at a console address, or null if not available. */
    private ByteBuffer segment(CoreHost core, long addr, int len) {
        ByteBuffer base;
        long start;
        switch (platform) {
            case "nds" -> {
                base = core.systemRam();
                start = 0x02000000L;
            }
            case "gb" -> {
                if (addr >= 0x100000L) {
                    base = core.saveRam();
                    start = 0x100000L;
                } else {
                    base = core.systemRam();
                    start = 0xC000L;
                }
            }
            default -> {
                return core.bus(addr, len);
            }
        }
        if (base == null || addr < start || addr - start + len > base.capacity()) {
            return null;
        }
        return Ram.slice(base, addr - start, len);
    }

    /** Writes everything or nothing. */
    private boolean write(CoreHost core, List<GameReader.Write> writes) {
        for (GameReader.Write w : writes) {
            if (segment(core, w.address(), w.bytes().length) == null) {
                return false;
            }
        }
        for (GameReader.Write w : writes) {
            Ram.put(segment(core, w.address(), w.bytes().length), 0, w.bytes(), 0, w.bytes().length);
        }
        return true;
    }

    /** Keeps the Pokémon whose Soul Link partner died at 0 HP (also after a Pokémon Center). */
    private void applyDeaths(CoreHost core) {
        Set<Long> doomed = engine.doomed();
        if (doomed.isEmpty()) {
            return;
        }
        for (Mon mon : engine.party()) {
            if (!doomed.contains(mon.uid())) {
                continue;
            }
            ByteBuffer seg = segment(core, mon.address(), reader.structSize());
            if (seg == null) {
                continue;
            }
            byte[] live = Ram.bytes(seg);
            GameReader.Patch p = reader.zeroHp(live, mon);
            if (p == null) {
                continue;
            }
            Ram.put(seg, p.offset(), p.bytes(), 0, p.bytes().length);
            Log.info("Mort liée appliquée en jeu : " + (mon.nickname() != null ? mon.nickname() : "#" + mon.species()));
            toast.accept("☠ " + (mon.nickname() != null ? mon.nickname() : "Pokémon") + " : mort dans le Soul Link, mis K.O.");
        }
    }

    /** Nicknames of the links, items given back, dead Pokémon to the PC box (once per second at most). */
    private void applyWrites(CoreHost core, Mem m) {
        List<Mon> party = engine.party();
        Map<String, Object> names = nicknames;
        boolean renamed = false;
        for (Mon mon : party) {
            Object want = names.get(TrackerEngine.uidText(mon.uid()));
            if (want == null) {
                continue;
            }
            List<GameReader.Write> w = reader.nickname(m, mon, want.toString());
            if (w != null && !w.isEmpty() && write(core, w)) {
                Log.info("Surnom du lien écrit en jeu : " + want);
                toast.accept("✎ " + want + " : surnom du lien appliqué");
                renamed = true;
            }
        }
        if (renamed) {
            return; // the memory copy is now out of date: the rest waits for the next reading
        }
        if (applyTrade(core, m)) {
            return;
        }
        List<Object> done = new ArrayList<>();
        for (Object o : refunds) {
            Map<String, Object> r = Json.obj(o);
            String id = Json.str(r, "id");
            if (id == null) {
                continue;
            }
            if (appliedRefunds.contains(id)) {
                done.add(id);
                continue;
            }
            int item = (int) Json.num(r, "item", 0);
            int qty = (int) Json.num(r, "qty", 0);
            List<GameReader.Write> w = reader.addItem(m, item, qty);
            if (w != null && write(core, w)) {
                appliedRefunds.add(id);
                saveRefunds();
                done.add(id);
                String name = Json.str(r, "name");
                toast.accept("🎒 Rendu : " + qty + "× " + (name == null ? "objet" : name));
                Log.info("Objets rendus en jeu : " + qty + "× " + item);
                break; // the bag changed: the next refund waits for a fresh reading
            }
        }
        if (!done.isEmpty()) {
            ack.accept(done);
        }
        if (setMode) {
            // Hardcore: no switch offered after knocking out the foe's Pokémon (the options of the game itself)
            List<GameReader.Write> w = reader.setBattleStyle(m);
            if (w != null && !w.isEmpty() && write(core, w)) {
                Log.info("Style de combat Défini imposé (Hardcore)");
                toast.accept("⚔ Style de combat : Défini (Nuzlocke Hardcore)");
            }
        }
        // out of battle for sure: no battle (games that know), or a map change (DS games: during a battle,
        // the game works on a copy of the party and would write it back over the change)
        boolean field = engine.battleState() == 0 || (engine.battleState() < 0 && engine.mapJustChanged());
        reader.cartridgeRamLive(core.cartridgeRamLive());
        // the rules on items and moves: while the player walks on the field with no menu open (the presence in game
        // tells; without it, out of battle), so that no screen of the game holds a copy of the bag or of the team
        boolean calm = presenceKnown ? freeFrames >= 30 : field; // half a second with the controls
        if (calm && enforce(core, m, party)) {
            return; // the bag or the team changed: the rest waits for a fresh reading
        }
        if (field) {
            // released (against the rules, cancelled zone): gone from the party and from the PC
            for (long uid : release) {
                List<GameReader.Write> w = reader.release(m, uid);
                if (w != null && write(core, w)) {
                    Log.info("Pokémon relâché en jeu : " + uid);
                    toast.accept("🕊 Pokémon relâché (règles de la salle)");
                    return; // the party changed: next round with a fresh reading
                }
            }
        }
        if (field && !park.isEmpty()) {
            // Sololocke: one living Pokémon in the team, the others to the PC; Hardcore: above the level cap (one at a time)
            for (Mon mon : party) {
                if (!park.contains(mon.uid()) || depositFailures.contains(mon.uid())) {
                    continue;
                }
                List<GameReader.Write> w = reader.store(m, mon);
                if (w == null) {
                    continue; // not possible right now (battle, PC full…): later
                }
                if (write(core, w)) {
                    String name = mon.nickname() != null ? mon.nickname() : "Pokémon";
                    boolean cap = "cap".equals(parkWhy.get(TrackerEngine.uidText(mon.uid())));
                    Log.info((cap ? "Plafond de niveau : " : "Sololocke : ") + name + " envoyé au PC");
                    toast.accept("📦 " + name + " va au PC : " + (cap ? "au-dessus du plafond de niveau (Hardcore)"
                            : "un seul Pokémon dans l'équipe (Sololocke)"));
                } else {
                    depositFailures.add(mon.uid());
                }
                return; // the party has changed
            }
        }
        if (deathBox && field) {
            Set<Long> doomed = engine.doomed();
            for (Mon mon : party) {
                if (!doomed.contains(mon.uid()) || release.contains(mon.uid()) || depositFailures.contains(mon.uid())) {
                    continue;
                }
                List<GameReader.Write> w = reader.deposit(m, mon);
                if (w == null) {
                    continue;
                }
                if (write(core, w)) {
                    String name = mon.nickname() != null ? mon.nickname() : "Pokémon";
                    Log.info("Pokémon mort déposé dans " + reader.deadBox() + " : " + name);
                    toast.accept("📦 " + name + " rejoint " + reader.deadBox());
                } else {
                    depositFailures.add(mon.uid());
                }
                break; // one at a time: the party has changed
            }
        }
    }

    /**
     * The room's rules on items and moves, one change at a time: an item they forbid taken out of the bag, a held item
     * put back in the bag (or taken away when forbidden), Fly forgotten. Each change is told to the room; the bag
     * changes are not reported as the player's.
     * @return true when the game's memory changed
     */
    private boolean enforce(CoreHost core, Mem m, List<Mon> party) {
        int gen = gen();
        Set<String> cats = ban;
        Set<Integer> banned = slt.player.track.ItemRules.banned(gen, cats);
        Map<Integer, Integer> bag = engine.bag();
        if (bag != null) {
            for (int item : banned) {
                int qty = bag.getOrDefault(item, 0);
                if (qty <= 0) {
                    continue;
                }
                List<GameReader.Write> w = reader.removeItem(m, item);
                if (w != null && write(core, w)) {
                    String cat = slt.player.track.ItemRules.category(gen, item, cats);
                    String name = itemName(item);
                    engine.ruleChange(item, -qty);
                    engine.report(Json.map("type", "removed", "item", (long) item, "name", name, "qty", (long) qty, "rule", cat));
                    Log.info("Règles de la salle : " + qty + "× " + name + " retiré du sac");
                    toast.accept("🚫 " + name + " retiré du sac (" + slt.player.track.ItemRules.label(cat) + ")");
                    return true;
                }
            }
        }
        if (noHeld || !banned.isEmpty()) {
            for (Mon mon : party) {
                int item = reader.heldItem(m, mon);
                boolean forbidden = banned.contains(item);
                if (item <= 0 || (!forbidden && (!noHeld || slt.player.track.ItemRules.mail(gen, item)))) {
                    continue;
                }
                List<GameReader.Write> take = reader.setHeldItem(m, mon, 0);
                if (take == null) {
                    continue;
                }
                List<GameReader.Write> w = new ArrayList<>(take);
                if (!forbidden) {
                    List<GameReader.Write> add = reader.addItem(m, item, 1);
                    if (add == null) {
                        if (heldNoRoom.add(mon.uid())) {
                            toast.accept("🎒 Sac plein : " + monName(mon) + " garde son objet pour l'instant (pas d'objet tenu)");
                        }
                        continue;
                    }
                    w.addAll(add);
                }
                if (write(core, w)) {
                    String name = itemName(item);
                    String cat = forbidden ? slt.player.track.ItemRules.category(gen, item, cats) : null;
                    if (!forbidden) {
                        engine.ruleChange(item, 1);
                    }
                    engine.report(Json.map("type", "held", "uid", TrackerEngine.uidText(mon.uid()), "nickname", mon.nickname(),
                            "item", (long) item, "name", name, "rule", forbidden ? cat : "held"));
                    Log.info("Règles de la salle : objet tenu " + name + " retiré à " + monName(mon));
                    toast.accept(forbidden ? "🚫 " + monName(mon) + " : " + name + " retiré (" + slt.player.track.ItemRules.label(cat) + ")"
                            : "🎒 " + monName(mon) + " : " + name + " remis dans le sac (pas d'objet tenu)");
                    return true;
                }
            }
        }
        if (noFly) {
            for (Mon mon : party) {
                List<GameReader.Write> w = reader.forgetMove(m, mon, slt.player.track.ItemRules.FLY);
                if (w != null && write(core, w)) {
                    engine.report(Json.map("type", "forgot", "uid", TrackerEngine.uidText(mon.uid()), "nickname", mon.nickname(),
                            "move", (long) slt.player.track.ItemRules.FLY));
                    Log.info("Règles de la salle : " + monName(mon) + " oublie Vol");
                    toast.accept("🚫 " + monName(mon) + " oublie Vol (pas de Vol)");
                    return true;
                }
            }
        }
        return false;
    }

    private String itemName(int item) {
        String n = engine.info().itemName(item);
        return n == null ? "Objet #" + item : n;
    }

    private static String monName(Mon mon) {
        return mon.nickname() != null ? mon.nickname() : "Pokémon";
    }

    /**
     * Trade between partners, in two steps: the Pokémon given is read and sent to the room;
     * once both are there, each game puts the one received in place of the one given.
     * @return true when the party changed
     */
    private boolean applyTrade(CoreHost core, Mem m) {
        Map<String, Object> t = trade;
        if (t == null) {
            return false;
        }
        String id = Json.str(t, "id");
        String phase = Json.str(t, "phase");
        long uid;
        try {
            uid = Long.parseLong(String.valueOf(t.get("uid")));
        } catch (NumberFormatException e) {
            return false;
        }
        long now = System.currentTimeMillis();
        if ("extract".equals(phase)) {
            if (id.equals(tradeSent) && now - tradeSentAt < 10_000) {
                return false; // sent: waiting for the partner
            }
            byte[] raw = reader.tradeOut(m, uid);
            if (raw != null) {
                tradeSent = id;
                tradeSentAt = now;
                tradeSink.accept(Json.map("id", id, "phase", "extract", "blob", java.util.Base64.getEncoder().encodeToString(raw)));
            }
            return false;
        }
        if (!"swap".equals(phase)) {
            return false;
        }
        if (appliedTrades.contains(id)) {
            if (now - tradeSentAt > 5000) {
                tradeSentAt = now;
                tradeSink.accept(Json.map("id", id, "phase", "done"));
            }
            return false;
        }
        // never during a battle: the game would write its copy of the party back over the trade
        int b = engine.battleState();
        if (b > 0 || (b < 0 && !engine.noWildBattle())) {
            return false;
        }
        byte[] blob;
        try {
            blob = java.util.Base64.getDecoder().decode(String.valueOf(t.get("blob")));
        } catch (IllegalArgumentException e) {
            return false;
        }
        List<GameReader.Write> w = reader.tradeIn(m, uid, blob);
        if (w == null || !write(core, w)) {
            return false;
        }
        appliedTrades.add(id);
        saveRefunds();
        tradeSentAt = now;
        tradeSink.accept(Json.map("id", id, "phase", "done"));
        Log.info("Échange appliqué en jeu : " + id);
        toast.accept("🔁 Échange terminé : le Pokémon de votre partenaire a rejoint l'équipe");
        return true;
    }

    private void loadRefunds() {
        try {
            if (Files.isRegularFile(refundsFile)) {
                Map<String, Object> done = Json.parseObject(Files.readString(refundsFile));
                List<Object> l = Json.arr(done.get("applied"));
                if (l != null) {
                    for (Object o : l) {
                        appliedRefunds.add(String.valueOf(o));
                    }
                }
                List<Object> t = Json.arr(done.get("trades"));
                if (t != null) {
                    for (Object o : t) {
                        appliedTrades.add(String.valueOf(o));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("Remboursements déjà appliqués illisibles : " + e.getMessage());
        }
    }

    private void saveRefunds() {
        try {
            Files.createDirectories(refundsFile.getParent());
            Files.writeString(refundsFile, Json.stringify(Json.map("applied", new ArrayList<Object>(appliedRefunds),
                    "trades", new ArrayList<Object>(appliedTrades))));
        } catch (IOException e) {
            Log.warn("Remboursements non enregistrés : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------- tests (ScriptRunner)

    /** The bag as the reader sees it now (tests). */
    Map<Integer, Integer> testBag(CoreHost core) {
        Mem m = capture(core);
        slt.player.track.Snapshot s = m == null ? null : reader.read(m);
        return s == null ? null : s.bag();
    }

    /** Gives an item back right now, as a refund would (tests): true when written. */
    boolean testAddItem(CoreHost core, int item, int qty) {
        Mem m = capture(core);
        if (m == null || reader.read(m) == null) {
            return false;
        }
        List<GameReader.Write> w = reader.addItem(m, item, qty);
        return w != null && write(core, w);
    }

    /** Gives a party Pokémon a held item (0: none), or makes it forget a move (tests): true when written. */
    boolean testEdit(CoreHost core, long uid, String what, int value) {
        Mem m = capture(core);
        if (m == null || reader.read(m) == null) {
            return false;
        }
        for (Mon mon : engine.party()) {
            if (mon.uid() == uid) {
                List<GameReader.Write> w = "forget".equals(what) ? reader.forgetMove(m, mon, value) : reader.setHeldItem(m, mon, value);
                return w != null && write(core, w);
            }
        }
        return false;
    }

    /** What the reader sees of a party Pokémon: held item and battle set (tests). */
    String testMon(CoreHost core, long uid) {
        Mem m = capture(core);
        if (m == null || reader.read(m) == null) {
            return null;
        }
        for (Mon mon : engine.party()) {
            if (mon.uid() == uid) {
                return "objet " + reader.heldItem(m, mon) + " " + reader.battleSet(m, mon);
            }
        }
        return null;
    }

    /** Sends a party Pokémon to the PC (dead: the dead box; else the first boxes), as the rules would (tests). */
    boolean testToBox(CoreHost core, long uid, boolean dead) {
        Mem m = capture(core);
        if (m == null || reader.read(m) == null) {
            return false;
        }
        for (Mon mon : engine.party()) {
            if (mon.uid() == uid) {
                List<GameReader.Write> w = dead ? reader.deposit(m, mon) : reader.store(m, mon);
                return w != null && write(core, w);
            }
        }
        return false;
    }

    void close() {
        if (worker != null) {
            worker.shutdownNow();
        }
    }
}
