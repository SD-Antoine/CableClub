package slt.server;

import slt.Http;
import slt.Ids;
import slt.Json;
import slt.Log;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;

/**
 * Accounts of the SLT server: users (password hashed with PBKDF2), session
 * tokens (only their SHA-256 is stored) and friendships.
 */
public final class Accounts {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,20}");
    private static final int ITERATIONS = 150_000;
    private static final long SESSION_DAYS = 60;
    private static final int MAX_FAILURES = 8;
    private static final long FAILURE_WINDOW_MS = 10 * 60_000L;
    /** Accounts created from one address (an IPv6 /64) in an hour: a family or a club behind one box stays under it. */
    static final int MAX_REGISTRATIONS = 10;
    private static final long REGISTRATION_WINDOW_MS = 60 * 60_000L;

    /** One account. Friend lists hold user ids. */
    public static final class User {
        public final String id;
        public final String username;
        public String display;
        String hash;
        String salt;
        int iterations;
        public final long createdAt;
        public final Set<String> friends = new LinkedHashSet<>();
        public final Set<String> incoming = new LinkedHashSet<>();
        public final Set<String> outgoing = new LinkedHashSet<>();
        /** Users whose messages this user does not want to see (chat). */
        public final Set<String> blocked = new LinkedHashSet<>();
        // profile
        public String bio = "";
        public String favGame = "";
        public String favPokemon = "";
        /** When the profile picture was last changed (0 = none): its version in the addresses. */
        public long avatarAt;
        /** No message in the general chat until then (moderation). */
        public long mutedUntil;
        /** When the user accepted the terms of use (0 = account older than them, or an old client). */
        public long termsAt;
        /** The user chose to appear in the leaderboards (off until they ask: point 3). */
        public boolean ranked;

        User(String id, String username, String display, long createdAt) {
            this.id = id;
            this.username = username;
            this.display = display;
            this.createdAt = createdAt;
        }

        /** What other users may see. */
        public Map<String, Object> pub() {
            return Json.map("id", id, "username", username, "name", display, "avatar", avatarAt);
        }

        /** The profile card: what other users may see, and more. */
        public Map<String, Object> profile() {
            Map<String, Object> m = pub();
            m.put("bio", bio);
            m.put("favGame", favGame);
            m.put("favPokemon", favPokemon);
            m.put("createdAt", createdAt);
            return m;
        }

        Map<String, Object> toJson() {
            return Json.map("id", id, "username", username, "display", display, "hash", hash, "salt", salt,
                    "iterations", (long) iterations, "createdAt", createdAt,
                    "friends", new ArrayList<Object>(friends), "incoming", new ArrayList<Object>(incoming),
                    "outgoing", new ArrayList<Object>(outgoing), "blocked", new ArrayList<Object>(blocked),
                    "bio", bio, "favGame", favGame, "favPokemon", favPokemon, "avatarAt", avatarAt,
                    "mutedUntil", mutedUntil, "termsAt", termsAt, "ranked", ranked);
        }

        static User fromJson(Map<String, Object> m) {
            User u = new User(Json.str(m, "id"), Json.str(m, "username"), Json.str(m, "display"),
                    Json.num(m, "createdAt", 0));
            u.hash = Json.str(m, "hash");
            u.salt = Json.str(m, "salt");
            u.iterations = (int) Json.num(m, "iterations", ITERATIONS);
            strings(m.get("friends"), u.friends);
            strings(m.get("incoming"), u.incoming);
            strings(m.get("outgoing"), u.outgoing);
            strings(m.get("blocked"), u.blocked);
            u.bio = Json.str(m, "bio") == null ? "" : Json.str(m, "bio");
            u.favGame = Json.str(m, "favGame") == null ? "" : Json.str(m, "favGame");
            u.favPokemon = Json.str(m, "favPokemon") == null ? "" : Json.str(m, "favPokemon");
            u.avatarAt = Json.num(m, "avatarAt", 0);
            u.mutedUntil = Json.num(m, "mutedUntil", 0);
            u.termsAt = Json.num(m, "termsAt", 0);
            u.ranked = Json.bool(m, "ranked", false);
            return u;
        }
    }

    private record Session(String userId, long createdAt, long expiresAt) {
    }

    private final Path usersFile;
    private final Path sessionsFile;
    /** Every account in creation order (search, comptes.json), changed and read under the lock. */
    private final Map<String, User> users = new LinkedHashMap<>();
    /**
     * The same accounts by id, read without the lock: every request with a session looks its user up here, and
     * must not wait for a password being hashed or a file being written. Changed under the lock, with users.
     */
    private final Map<String, User> byId = new ConcurrentHashMap<>();
    private final Map<String, String> byName = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> registrations = new ConcurrentHashMap<>();

    Accounts(Path dir) throws IOException {
        Files.createDirectories(dir);
        usersFile = dir.resolve("comptes.json");
        sessionsFile = dir.resolve("sessions.json");
        load();
    }

    // ------------------------------------------------------------ accounts

    /** An account made by the server itself or a test: no limit per address. */
    public User register(String username, String password, String display, boolean terms) {
        return register(username, password, display, terms, null);
    }

    /**
     * Creates an account. The password is hashed outside the lock (about 50 ms) and the name checked again inside;
     * at most {@link #MAX_REGISTRATIONS} attempts that reach the hashing per address and per hour.
     */
    public User register(String username, String password, String display, boolean terms, String ip) {
        String u = username == null ? "" : username.trim();
        if (!USERNAME.matcher(u).matches()) {
            throw new Http.Fail(400, "Identifiant invalide : 3 à 20 caractères, lettres, chiffres, « . », « - » ou « _ ».");
        }
        checkPassword(password);
        String key = u.toLowerCase(Locale.ROOT);
        if (byName.containsKey(key)) {
            throw new Http.Fail(409, "Cet identifiant est déjà pris.");
        }
        String name = cleanDisplay(display == null || display.isBlank() ? u : display);
        if (ip != null) {
            limitRegistrations(address(ip));
        }
        User user = new User(Ids.id(10), u, name, System.currentTimeMillis());
        Credentials c = hash(password);
        user.salt = c.salt();
        user.iterations = c.iterations();
        user.hash = c.hash();
        // clients before 0.9 do not show the terms: the account is created all the same
        user.termsAt = terms ? user.createdAt : 0;
        long v;
        synchronized (this) {
            if (byName.containsKey(key)) {
                throw new Http.Fail(409, "Cet identifiant est déjà pris.");
            }
            users.put(user.id, user);
            byId.put(user.id, user);
            byName.put(key, user.id);
            v = changed();
        }
        persist(v);
        Log.info("Serveur : nouveau compte " + u);
        return user;
    }

    /** Whether this is the user's password: the caller holds a session, wrong guesses are limited all the same. */
    public boolean passwordIs(User user, String password) {
        throttle("pw:" + user.id);
        if (password == null || !verify(credentials(user), password)) {
            fail("pw:" + user.id);
            return false;
        }
        failures.remove("pw:" + user.id);
        return true;
    }

    /**
     * Deletes an account: the user, their sessions, and their place in the friend lists and block lists of the
     * others. What they left elsewhere (rooms, chat, picture) is removed by the caller.
     */
    public void delete(User user) {
        long v;
        synchronized (this) {
            users.remove(user.id);
            byId.remove(user.id);
            byName.remove(user.username.toLowerCase(Locale.ROOT));
            for (User o : users.values()) {
                o.friends.remove(user.id);
                o.incoming.remove(user.id);
                o.outgoing.remove(user.id);
                o.blocked.remove(user.id);
            }
            sessions.values().removeIf(s -> s.userId().equals(user.id));
            v = changed();
        }
        persist(v);
        persistSessions(sessionsChanged());
        Log.info("Serveur : compte supprimé " + user.username);
    }

    /** Checks the password; throttled per identifier and per address. */
    public User login(String username, String password, String ip) {
        String key = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        String from = "ip:" + address(ip);
        throttle("u:" + key);
        throttle(from);
        String id = byName.get(key);
        User user = id == null ? null : byId.get(id);
        if (user == null || password == null || !verify(credentials(user), password)) {
            fail("u:" + key);
            fail(from);
            throw new Http.Fail(401, "Identifiant ou mot de passe incorrect.");
        }
        failures.remove("u:" + key);
        return user;
    }

    /**
     * Both hashes are made outside the lock; the change is refused if the password changed in between (two
     * devices at once). Wrong guesses of the current password are limited as at login.
     */
    public void changePassword(User user, String oldPassword, String newPassword) {
        throttle("pw:" + user.id);
        Credentials before = credentials(user);
        if (!verify(before, oldPassword == null ? "" : oldPassword)) {
            fail("pw:" + user.id);
            throw new Http.Fail(403, "Mot de passe actuel incorrect.");
        }
        failures.remove("pw:" + user.id);
        checkPassword(newPassword);
        Credentials next = hash(newPassword);
        long v;
        synchronized (this) {
            if (!java.util.Objects.equals(user.hash, before.hash())) {
                throw new Http.Fail(409, "Le mot de passe vient d'être changé ailleurs : reconnectez-vous.");
            }
            user.salt = next.salt();
            user.iterations = next.iterations();
            user.hash = next.hash();
            // every other device must log in again (written too: they came back after a restart of the server)
            sessions.values().removeIf(s -> s.userId().equals(user.id));
            v = changed();
        }
        persist(v);
        persistSessions(sessionsChanged());
    }

    public void rename(User user, String display) {
        long v;
        synchronized (this) {
            user.display = cleanDisplay(display);
            v = changed();
        }
        persist(v);
    }

    /** Changes the fields of the profile that are given (name, bio, favourite game and Pokémon, leaderboards). */
    public void updateProfile(User user, Map<String, Object> b) {
        long v;
        synchronized (this) {
            String name = b.containsKey("name") ? cleanDisplay(Json.str(b, "name")) : user.display;
            String bio = b.containsKey("bio") ? cleanText(Json.str(b, "bio"), 160, "Bio") : user.bio;
            String game = b.containsKey("favGame") ? cleanText(Json.str(b, "favGame"), 40, "Jeu favori") : user.favGame;
            String mon = b.containsKey("favPokemon") ? cleanText(Json.str(b, "favPokemon"), 24, "Pokémon favori") : user.favPokemon;
            user.display = name;
            user.bio = bio;
            user.favGame = game;
            user.favPokemon = mon;
            if (b.get("ranked") instanceof Boolean on) {
                user.ranked = on;
            }
            v = changed();
        }
        persist(v);
    }

    public void avatarChanged(User user, boolean present) {
        long v;
        synchronized (this) {
            user.avatarAt = present ? Math.max(System.currentTimeMillis(), user.avatarAt + 1) : 0;
            v = changed();
        }
        persist(v);
    }

    public void block(User user, User other, boolean on) {
        long v;
        synchronized (this) {
            if (user.id.equals(other.id)) {
                throw new Http.Fail(400, "Vous ne pouvez pas vous bloquer vous-même.");
            }
            if (on) {
                user.blocked.add(other.id);
            } else {
                user.blocked.remove(other.id);
            }
            v = changed();
        }
        persist(v);
    }

    public void mute(User user, long until) {
        long v;
        synchronized (this) {
            user.mutedUntil = until;
            v = changed();
        }
        persist(v);
    }

    /** One line of free text (no control characters, no angle brackets), at most {@code max} characters. */
    static String cleanText(String text, int max, String label) {
        String t = text == null ? "" : text.replaceAll("[\\p{Cntrl}<>]", " ").replaceAll("\\s+", " ").trim();
        if (t.codePointCount(0, t.length()) > max) {
            throw new Http.Fail(400, label + " : " + max + " caractères au plus.");
        }
        return t;
    }

    public int count() {
        return byId.size();
    }

    /** Without the lock (see {@link #byId}). */
    public User get(String id) {
        return id == null ? null : byId.get(id);
    }

    public User byUsername(String username) {
        String id = username == null ? null : byName.get(username.trim().toLowerCase(Locale.ROOT));
        return id == null ? null : byId.get(id);
    }

    public synchronized List<Map<String, Object>> search(String q, String excludeId) {
        String s = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        if (s.length() < 2) {
            return out;
        }
        for (User u : users.values()) {
            if (u.id.equals(excludeId)) {
                continue;
            }
            if (u.username.toLowerCase(Locale.ROOT).startsWith(s) || u.display.toLowerCase(Locale.ROOT).startsWith(s)) {
                out.add(u.pub());
                if (out.size() >= 10) {
                    break;
                }
            }
        }
        return out;
    }

    private static String cleanDisplay(String display) {
        String d = display == null ? "" : display.replaceAll("[\\p{Cntrl}<>]", "").trim();
        if (d.isEmpty() || d.length() > 24) {
            throw new Http.Fail(400, "Nom affiché : 1 à 24 caractères.");
        }
        return d;
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 200) {
            throw new Http.Fail(400, "Le mot de passe doit faire au moins 8 caractères.");
        }
    }

    /** The stored password of an account, read together (it is changed under the lock). */
    private record Credentials(String hash, String salt, int iterations) {
    }

    private synchronized Credentials credentials(User user) {
        return new Credentials(user.hash, user.salt, user.iterations);
    }

    /** A new salt and hash: slow on purpose, never under the lock. */
    private static Credentials hash(String password) {
        byte[] salt = Ids.bytes(16);
        return new Credentials(Base64.getEncoder().encodeToString(pbkdf2(password, salt, ITERATIONS)),
                Base64.getEncoder().encodeToString(salt), ITERATIONS);
    }

    private static boolean verify(Credentials c, String password) {
        if (c.hash() == null || c.salt() == null) {
            return false;
        }
        byte[] expected = Base64.getDecoder().decode(c.hash());
        byte[] actual = pbkdf2(password, Base64.getDecoder().decode(c.salt()), c.iterations());
        return Ids.same(expected, actual);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void throttle(String key) {
        Deque<Long> d = failures.get(key);
        if (d == null) {
            return;
        }
        long limit = System.currentTimeMillis() - FAILURE_WINDOW_MS;
        while (!d.isEmpty() && d.peekFirst() < limit) {
            d.pollFirst();
        }
        if (d.isEmpty()) {
            failures.remove(key, d); // the map does not keep every identifier ever tried
        } else if (d.size() >= MAX_FAILURES) {
            throw new Http.Fail(429, "Trop de tentatives. Réessayez dans quelques minutes.");
        }
    }

    /** Counts one more registration from an address, or refuses it beyond {@link #MAX_REGISTRATIONS} in an hour. */
    private void limitRegistrations(String address) {
        long now = System.currentTimeMillis();
        Deque<Long> d = registrations.computeIfAbsent(address, k -> new java.util.ArrayDeque<>());
        synchronized (d) {
            while (!d.isEmpty() && d.peekFirst() < now - REGISTRATION_WINDOW_MS) {
                d.pollFirst();
            }
            if (d.size() >= MAX_REGISTRATIONS) {
                throw new Http.Fail(429, "Trop de comptes créés depuis cette adresse. Réessayez dans une heure.");
            }
            d.addLast(now);
        }
        if (registrations.size() > 10_000) {
            registrations.values().removeIf(q -> {
                synchronized (q) {
                    return q.isEmpty() || q.peekLast() < now - REGISTRATION_WINDOW_MS;
                }
            });
        }
    }

    /**
     * The address a limit counts by: an IPv4 address, or the /64 network of an IPv6 one (a single home or machine
     * gets a whole /64, and could otherwise change address at each attempt).
     */
    static String address(String ip) {
        if (ip == null || ip.indexOf(':') < 0) {
            return ip == null ? "?" : ip;
        }
        try {
            String literal = ip.indexOf('%') >= 0 ? ip.substring(0, ip.indexOf('%')) : ip;
            if (literal.startsWith("[") && literal.endsWith("]")) {
                literal = literal.substring(1, literal.length() - 1);
            }
            // a literal address: no name lookup
            byte[] a = java.net.InetAddress.getByName(literal).getAddress();
            if (a.length == 4) {
                return java.net.InetAddress.getByAddress(a).getHostAddress(); // ::ffff:a.b.c.d
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i += 2) {
                sb.append(Integer.toHexString(((a[i] & 0xFF) << 8) | (a[i + 1] & 0xFF))).append(':');
            }
            return sb.append(":/64").toString();
        } catch (IOException | RuntimeException e) {
            return ip;
        }
    }

    private void fail(String key) {
        failures.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>()).addLast(System.currentTimeMillis());
    }

    // ------------------------------------------------------------ sessions

    public String openSession(User user) {
        String token = Ids.token();
        long now = System.currentTimeMillis();
        sessions.put(Ids.sha256(token), new Session(user.id, now, now + SESSION_DAYS * 86_400_000L));
        persistSessions(sessionsChanged());
        return token;
    }

    public void closeSession(String token) {
        if (token != null && sessions.remove(Ids.sha256(token)) != null) {
            persistSessions(sessionsChanged());
        }
    }

    /** The user behind a token, or null. */
    public User session(String token) {
        if (token == null) {
            return null;
        }
        Session s = sessions.get(Ids.sha256(token));
        if (s == null) {
            return null;
        }
        if (s.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(Ids.sha256(token));
            return null;
        }
        return get(s.userId());
    }

    // ------------------------------------------------------------- friends

    /** Sends (or accepts, when the other user already asked) a friend request. */
    public String requestFriend(User from, User to) {
        String result;
        long v;
        synchronized (this) {
            if (from.id.equals(to.id)) {
                throw new Http.Fail(400, "Vous ne pouvez pas vous ajouter vous-même.");
            }
            if (from.friends.contains(to.id)) {
                return "friends";
            }
            if (from.incoming.contains(to.id)) {
                link(from, to);
                result = "friends";
            } else {
                from.outgoing.add(to.id);
                to.incoming.add(from.id);
                result = "sent";
            }
            v = changed();
        }
        persist(v);
        return result;
    }

    public void accept(User user, User other) {
        long v;
        synchronized (this) {
            if (!user.incoming.contains(other.id)) {
                throw new Http.Fail(404, "Aucune demande de cette personne.");
            }
            link(user, other);
            v = changed();
        }
        persist(v);
    }

    private static void link(User a, User b) {
        a.incoming.remove(b.id);
        a.outgoing.remove(b.id);
        b.incoming.remove(a.id);
        b.outgoing.remove(a.id);
        a.friends.add(b.id);
        b.friends.add(a.id);
    }

    /** Declines a request, cancels one, or removes a friend. */
    public void unlink(User user, User other) {
        long v;
        synchronized (this) {
            user.friends.remove(other.id);
            user.incoming.remove(other.id);
            user.outgoing.remove(other.id);
            other.friends.remove(user.id);
            other.incoming.remove(user.id);
            other.outgoing.remove(user.id);
            v = changed();
        }
        persist(v);
    }

    // --------------------------------------------------------- persistence

    private void load() {
        try {
            if (Files.isRegularFile(usersFile)) {
                List<Object> list = Json.arr(Json.obj(Json.parse(Files.readString(usersFile))).get("users"));
                if (list != null) {
                    for (Object o : list) {
                        User u = User.fromJson(Json.obj(o));
                        users.put(u.id, u);
                        byId.put(u.id, u);
                        byName.put(u.username.toLowerCase(Locale.ROOT), u.id);
                    }
                }
            }
            if (Files.isRegularFile(sessionsFile)) {
                Map<String, Object> m = Json.obj(Json.parse(Files.readString(sessionsFile)));
                long now = System.currentTimeMillis();
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    Map<String, Object> s = Json.obj(e.getValue());
                    Session session = new Session(Json.str(s, "userId"), Json.num(s, "createdAt", now), Json.num(s, "expiresAt", 0));
                    if (session.expiresAt() > now) {
                        sessions.put(e.getKey(), session);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // never a copy loaded on its own: the accounts made since would vanish without anyone noticing
            String copy = "";
            try {
                List<Path> copies = backups(usersFile.getParent());
                if (!copies.isEmpty()) {
                    copy = " ; copie de secours la plus récente : " + copies.get(copies.size() - 1)
                            + " (à remettre à la main en comptes.json, le serveur arrêté)";
                }
            } catch (IOException ignored) {
                // no copy to name
            }
            throw new IllegalStateException("Comptes illisibles : " + e.getMessage() + copy, e);
        }
    }

    /*
     * Writing. Every change counts a version under the lock (changed()); the caller then writes, outside the lock,
     * a file holding at least its version (persist()), and answers only once it is on the disk: nothing is
     * deferred. Changes made while a write is on its way share the next one, so a burst does not write the whole
     * file once per change, and the requests with a session never wait for the disk (they do not take the lock).
     */

    /** Changes counted (under the lock) and written (under usersWrite). */
    private long usersVersion;
    private long usersWritten;
    private final Object usersWrite = new Object();
    private final java.util.concurrent.atomic.AtomicLong sessionsVersion = new java.util.concurrent.atomic.AtomicLong();
    private long sessionsWritten;
    private final Object sessionsWrite = new Object();
    /** Files written, for the tests (fewer than the changes when they come together). */
    final java.util.concurrent.atomic.AtomicLong writes = new java.util.concurrent.atomic.AtomicLong();

    /** Copies of comptes.json kept, one a day. */
    static final int BACKUPS = 7;
    private static final Pattern BACKUP = Pattern.compile("comptes-\\d{4}-\\d{2}-\\d{2}\\.json");

    /** Counts a change of the accounts; called under the lock, the caller then calls {@link #persist}. */
    private long changed() {
        return ++usersVersion;
    }

    private long sessionsChanged() {
        return sessionsVersion.incrementAndGet();
    }

    /** Writes comptes.json unless a write made after the change {@code version} is already on the disk. */
    private void persist(long version) {
        synchronized (usersWrite) {
            if (usersWritten >= version) {
                return;
            }
            long v;
            List<Object> list = new ArrayList<>();
            synchronized (this) {
                v = usersVersion;
                for (User u : users.values()) {
                    list.add(u.toJson());
                }
            }
            String text = Json.stringify(Json.map("version", 1L, "users", list));
            backup();
            writeDurable(usersFile, text);
            writes.incrementAndGet();
            usersWritten = v;
        }
    }

    private void persistSessions(long version) {
        synchronized (sessionsWrite) {
            if (sessionsWritten >= version) {
                return;
            }
            long v = sessionsVersion.get();
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Session> e : sessions.entrySet()) {
                Session s = e.getValue();
                m.put(e.getKey(), Json.map("userId", s.userId(), "createdAt", s.createdAt(), "expiresAt", s.expiresAt()));
            }
            writeDurable(sessionsFile, Json.stringify(m));
            writes.incrementAndGet();
            sessionsWritten = v;
        }
    }

    /**
     * Before the first write of a day, copies comptes.json as it was (comptes-AAAA-MM-JJ.json) and keeps the
     * {@link #BACKUPS} latest copies. They hold the password hashes, as comptes.json.
     */
    private void backup() {
        Path dir = usersFile.getParent();
        Path today = dir.resolve("comptes-" + java.time.LocalDate.now() + ".json");
        if (Files.exists(today) || !Files.isRegularFile(usersFile)) {
            return;
        }
        try {
            Files.copy(usersFile, today, StandardCopyOption.COPY_ATTRIBUTES);
            List<Path> copies = backups(dir);
            for (int i = 0; i < copies.size() - BACKUPS; i++) {
                Files.deleteIfExists(copies.get(i));
            }
        } catch (IOException e) {
            Log.error("Serveur : copie de secours impossible de " + usersFile, e);
        }
    }

    /** The daily copies of comptes.json, oldest first. */
    static List<Path> backups(Path dir) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> BACKUP.matcher(f.getFileName().toString()).matches()).sorted().toList();
        }
    }

    /** Locks of the files being written (one per file: two files are written at the same time). */
    private static final Map<Path, Object> FILE_LOCKS = new ConcurrentHashMap<>();

    /** Writes a file whole (a temporary file, then moved over it): a crash never leaves it half written. */
    static void write(Path file, String text) {
        write(file, text, false);
    }

    /** As {@link #write(Path, String)}, and on the disk before it returns (a power cut keeps it). */
    static void writeDurable(Path file, String text) {
        write(file, text, true);
    }

    private static void write(Path file, String text, boolean durable) {
        synchronized (FILE_LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), k -> new Object())) {
            try {
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                if (durable) {
                    try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmp,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        while (b.hasRemaining()) {
                            ch.write(b);
                        }
                        ch.force(true);
                    }
                } else {
                    Files.writeString(tmp, text);
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                if (durable) {
                    syncDirectory(file.getParent());
                }
            } catch (IOException e) {
                Log.error("Serveur : écriture impossible de " + file, e);
            }
        }
    }

    /** The new name of the file is on the disk too (Linux; a directory cannot be opened this way on Windows). */
    private static void syncDirectory(Path dir) {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(dir, java.nio.file.StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | RuntimeException ignored) {
            // not possible here
        }
    }

    static void strings(Object list, Set<String> into) {
        List<Object> l = Json.arr(list);
        if (l != null) {
            for (Object o : l) {
                if (o != null) {
                    into.add(o.toString());
                }
            }
        }
    }
}
