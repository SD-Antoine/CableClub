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
                    "mutedUntil", mutedUntil);
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
            return u;
        }
    }

    private record Session(String userId, long createdAt, long expiresAt) {
    }

    private final Path usersFile;
    private final Path sessionsFile;
    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, String> byName = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    Accounts(Path dir) throws IOException {
        Files.createDirectories(dir);
        usersFile = dir.resolve("comptes.json");
        sessionsFile = dir.resolve("sessions.json");
        load();
    }

    // ------------------------------------------------------------ accounts

    public synchronized User register(String username, String password, String display) {
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
        User user = new User(Ids.id(10), u, name, System.currentTimeMillis());
        setPassword(user, password);
        users.put(user.id, user);
        byName.put(key, user.id);
        save();
        Log.info("Serveur : nouveau compte " + u);
        return user;
    }

    /** Checks the password; throttled per identifier and per address. */
    public User login(String username, String password, String ip) {
        String key = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        throttle("u:" + key);
        throttle("ip:" + ip);
        User user;
        synchronized (this) {
            String id = byName.get(key);
            user = id == null ? null : users.get(id);
        }
        if (user == null || password == null || !verify(user, password)) {
            fail("u:" + key);
            fail("ip:" + ip);
            throw new Http.Fail(401, "Identifiant ou mot de passe incorrect.");
        }
        failures.remove("u:" + key);
        return user;
    }

    public synchronized void changePassword(User user, String oldPassword, String newPassword) {
        if (!verify(user, oldPassword == null ? "" : oldPassword)) {
            throw new Http.Fail(403, "Mot de passe actuel incorrect.");
        }
        checkPassword(newPassword);
        setPassword(user, newPassword);
        // every other device must log in again
        sessions.values().removeIf(s -> s.userId().equals(user.id));
        save();
    }

    public synchronized void rename(User user, String display) {
        user.display = cleanDisplay(display);
        save();
    }

    /** Changes the fields of the profile that are given (name, bio, favourite game and Pokémon). */
    public synchronized void updateProfile(User user, Map<String, Object> b) {
        String name = b.containsKey("name") ? cleanDisplay(Json.str(b, "name")) : user.display;
        String bio = b.containsKey("bio") ? cleanText(Json.str(b, "bio"), 160, "Bio") : user.bio;
        String game = b.containsKey("favGame") ? cleanText(Json.str(b, "favGame"), 40, "Jeu favori") : user.favGame;
        String mon = b.containsKey("favPokemon") ? cleanText(Json.str(b, "favPokemon"), 24, "Pokémon favori") : user.favPokemon;
        user.display = name;
        user.bio = bio;
        user.favGame = game;
        user.favPokemon = mon;
        save();
    }

    public synchronized void avatarChanged(User user, boolean present) {
        user.avatarAt = present ? Math.max(System.currentTimeMillis(), user.avatarAt + 1) : 0;
        save();
    }

    public synchronized void block(User user, User other, boolean on) {
        if (user.id.equals(other.id)) {
            throw new Http.Fail(400, "Vous ne pouvez pas vous bloquer vous-même.");
        }
        if (on) {
            user.blocked.add(other.id);
        } else {
            user.blocked.remove(other.id);
        }
        save();
    }

    public synchronized void mute(User user, long until) {
        user.mutedUntil = until;
        save();
    }

    /** One line of free text (no control characters, no angle brackets), at most {@code max} characters. */
    static String cleanText(String text, int max, String label) {
        String t = text == null ? "" : text.replaceAll("[\\p{Cntrl}<>]", " ").replaceAll("\\s+", " ").trim();
        if (t.codePointCount(0, t.length()) > max) {
            throw new Http.Fail(400, label + " : " + max + " caractères au plus.");
        }
        return t;
    }

    public synchronized int count() {
        return users.size();
    }

    public synchronized User get(String id) {
        return id == null ? null : users.get(id);
    }

    public synchronized User byUsername(String username) {
        String id = username == null ? null : byName.get(username.trim().toLowerCase(Locale.ROOT));
        return id == null ? null : users.get(id);
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

    private void setPassword(User user, String password) {
        byte[] salt = Ids.bytes(16);
        user.salt = Base64.getEncoder().encodeToString(salt);
        user.iterations = ITERATIONS;
        user.hash = Base64.getEncoder().encodeToString(pbkdf2(password, salt, ITERATIONS));
    }

    private static boolean verify(User user, String password) {
        if (user.hash == null || user.salt == null) {
            return false;
        }
        byte[] expected = Base64.getDecoder().decode(user.hash);
        byte[] actual = pbkdf2(password, Base64.getDecoder().decode(user.salt), user.iterations);
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
        if (d.size() >= MAX_FAILURES) {
            throw new Http.Fail(429, "Trop de tentatives. Réessayez dans quelques minutes.");
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
        saveSessions();
        return token;
    }

    public void closeSession(String token) {
        if (token != null && sessions.remove(Ids.sha256(token)) != null) {
            saveSessions();
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
    public synchronized String requestFriend(User from, User to) {
        if (from.id.equals(to.id)) {
            throw new Http.Fail(400, "Vous ne pouvez pas vous ajouter vous-même.");
        }
        if (from.friends.contains(to.id)) {
            return "friends";
        }
        if (from.incoming.contains(to.id)) {
            link(from, to);
            save();
            return "friends";
        }
        from.outgoing.add(to.id);
        to.incoming.add(from.id);
        save();
        return "sent";
    }

    public synchronized void accept(User user, User other) {
        if (!user.incoming.contains(other.id)) {
            throw new Http.Fail(404, "Aucune demande de cette personne.");
        }
        link(user, other);
        save();
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
    public synchronized void unlink(User user, User other) {
        user.friends.remove(other.id);
        user.incoming.remove(other.id);
        user.outgoing.remove(other.id);
        other.friends.remove(user.id);
        other.incoming.remove(user.id);
        other.outgoing.remove(user.id);
        save();
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
            throw new IllegalStateException("Comptes illisibles : " + e.getMessage(), e);
        }
    }

    private synchronized void save() {
        List<Object> list = new ArrayList<>();
        for (User u : users.values()) {
            list.add(u.toJson());
        }
        write(usersFile, Json.stringify(Json.map("version", 1L, "users", list)));
    }

    private void saveSessions() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            m.put(e.getKey(), Json.map("userId", s.userId(), "createdAt", s.createdAt(), "expiresAt", s.expiresAt()));
        }
        write(sessionsFile, Json.stringify(m));
    }

    static synchronized void write(Path file, String text) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.error("Serveur : écriture impossible de " + file, e);
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
