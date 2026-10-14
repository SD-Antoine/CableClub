package slt.server;

import slt.Http;
import slt.Json;
import slt.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Written chat of the server: the general channel (everybody), one channel
 * per room (its players) and private messages between friends.
 *
 * <p>The interface names a channel "global", "room-ROOMID" or "dm-USERID"
 * (the other person); on disk a private channel is the pair of users. The
 * last messages of each channel are kept (files chat/*.jsonl), with what each
 * user has read. Limits: 500 characters, a few messages per second, blocked
 * users are not seen, the author or a moderator (admins.txt) may delete a
 * message, a moderator may silence someone in the general channel.
 */
final class Chat {

    static final int MAX_TEXT = 500;
    private static final int KEEP = 300;
    private static final int PAGE = 60;
    private static final long FILE_LIMIT = 2_000_000;
    // anti-flood: a burst of BURST messages, then one every REFILL_MS
    private static final int BURST = 6;
    private static final long REFILL_MS = 1500;

    /** The last messages of one channel (loaded from its file the first time). */
    private static final class Channel {
        final String key;
        final Deque<Map<String, Object>> msgs = new ArrayDeque<>();
        long seq;

        Channel(String key) {
            this.key = key;
        }
    }

    private record Bucket(double tokens, long at) {
    }

    private final Path dir;
    private final Path readsFile;
    private final Path adminsFile;
    private final Accounts accounts;
    private final Rooms rooms;
    private final Hub hub;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    /** userId -> channel key -> id of the last message read. */
    private final Map<String, Map<String, Long>> reads = new ConcurrentHashMap<>();
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private volatile Set<String> admins = Set.of();
    private volatile long adminsAt = -1;
    private volatile boolean readsDirty;

    Chat(Path base, Accounts accounts, Rooms rooms, Hub hub) throws IOException {
        this.dir = base.resolve("chat");
        this.readsFile = dir.resolve("lus.json");
        this.adminsFile = base.resolve("admins.txt");
        this.accounts = accounts;
        this.rooms = rooms;
        this.hub = hub;
        Files.createDirectories(dir);
        if (Files.isRegularFile(readsFile)) {
            try {
                Map<String, Object> m = Json.parseObject(Files.readString(readsFile));
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    Map<String, Long> per = new ConcurrentHashMap<>();
                    Map<String, Object> o = Json.obj(e.getValue());
                    if (o != null) {
                        for (Map.Entry<String, Object> c : o.entrySet()) {
                            if (c.getValue() instanceof Number n) {
                                per.put(c.getKey(), n.longValue());
                            }
                        }
                    }
                    reads.put(e.getKey(), per);
                }
            } catch (IOException | RuntimeException e) {
                Log.warn("Serveur : messages lus illisibles : " + e.getMessage());
            }
        }
        if (!Files.exists(adminsFile)) {
            Files.writeString(adminsFile, "# Modérateurs du chat : un identifiant de compte par ligne\n");
        }
    }

    // ------------------------------------------------------------ moderators

    /** Moderators: usernames listed in admins.txt (read again when the file changes). */
    boolean admin(Accounts.User u) {
        try {
            long at = Files.isRegularFile(adminsFile) ? Files.getLastModifiedTime(adminsFile).toMillis() : 0;
            if (at != adminsAt) {
                Set<String> s = new LinkedHashSet<>();
                if (at > 0) {
                    for (String line : Files.readAllLines(adminsFile, StandardCharsets.UTF_8)) {
                        String l = line.trim();
                        if (!l.isEmpty() && !l.startsWith("#")) {
                            s.add(l.toLowerCase(Locale.ROOT));
                        }
                    }
                }
                admins = s;
                adminsAt = at;
            }
        } catch (IOException e) {
            Log.warn("Serveur : admins.txt illisible : " + e.getMessage());
        }
        return u != null && admins.contains(u.username.toLowerCase(Locale.ROOT));
    }

    // -------------------------------------------------------------- channels

    /** The channel behind a name of the interface, checking that this user may read it. */
    private String key(Accounts.User u, String ch) {
        if ("global".equals(ch)) {
            return "global";
        }
        if (ch != null && ch.startsWith("room-")) {
            Room r = rooms.find(ch.substring(5));
            if (r == null) {
                throw new Http.Fail(404, "Salle introuvable.");
            }
            synchronized (r) {
                if (r.member(u.id) == null && !r.history.contains(u.id)) {
                    throw new Http.Fail(403, "Vous ne faites pas partie de cette salle.");
                }
            }
            return "room-" + r.id;
        }
        if (ch != null && ch.startsWith("dm-")) {
            Accounts.User other = accounts.get(ch.substring(3));
            if (other == null || other.id.equals(u.id)) {
                throw new Http.Fail(404, "Joueur introuvable.");
            }
            return dmKey(u.id, other.id);
        }
        throw new Http.Fail(404, "Discussion inconnue.");
    }

    private static String dmKey(String a, String b) {
        return a.compareTo(b) < 0 ? "dm-" + a + "-" + b : "dm-" + b + "-" + a;
    }

    /** How this user names a channel (a private channel is named after the other person). */
    private static String nameFor(String key, String userId) {
        if (!key.startsWith("dm-")) {
            return key;
        }
        String[] p = key.substring(3).split("-");
        return "dm-" + (p[0].equals(userId) ? p[1] : p[0]);
    }

    private Channel channel(String key) {
        return channels.computeIfAbsent(key, k -> {
            Channel c = new Channel(k);
            Path f = dir.resolve(k + ".jsonl");
            if (Files.isRegularFile(f)) {
                try {
                    Set<Long> deleted = new java.util.HashSet<>();
                    List<Map<String, Object>> all = new ArrayList<>();
                    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        if (line.isBlank()) {
                            continue;
                        }
                        Map<String, Object> m = Json.parseObject(line);
                        if (m.containsKey("del")) {
                            deleted.add(Json.num(m, "del", -1));
                        } else {
                            all.add(m);
                        }
                    }
                    for (Map<String, Object> m : all) {
                        c.seq = Math.max(c.seq, Json.num(m, "id", 0));
                        if (!deleted.contains(Json.num(m, "id", 0))) {
                            c.msgs.addLast(m);
                            if (c.msgs.size() > KEEP) {
                                c.msgs.pollFirst();
                            }
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    Log.warn("Serveur : discussion illisible " + f.getFileName() + " : " + e.getMessage());
                }
            }
            return c;
        });
    }

    /** Who receives the messages of a channel. */
    private Collection<String> audience(String key) {
        if ("global".equals(key)) {
            return new ArrayList<>(hub.onlineUsers());
        }
        if (key.startsWith("room-")) {
            Room r = rooms.find(key.substring(5));
            List<String> out = new ArrayList<>();
            if (r != null) {
                synchronized (r) {
                    for (Room.Member m : r.members) {
                        out.add(m.userId);
                    }
                }
            }
            return out;
        }
        String[] p = key.substring(3).split("-");
        return List.of(p[0], p[1]);
    }

    // -------------------------------------------------------------- messages

    /** The messages before {@code before} (newest last), without those of users this user blocked. */
    Map<String, Object> history(Accounts.User u, String ch, long before, int limit) {
        String key = key(u, ch);
        Channel c = channel(key);
        int n = Math.max(1, Math.min(PAGE, limit));
        List<Object> out = new ArrayList<>();
        boolean more = false;
        synchronized (c) {
            List<Map<String, Object>> list = new ArrayList<>(c.msgs);
            for (int i = list.size() - 1; i >= 0; i--) {
                Map<String, Object> m = list.get(i);
                if (before > 0 && Json.num(m, "id", 0) >= before) {
                    continue;
                }
                if (u.blocked.contains(Json.str(m, "from"))) {
                    continue;
                }
                if (out.size() >= n) {
                    more = true;
                    break;
                }
                out.add(0, view(m, nameFor(key, u.id)));
            }
        }
        return Json.map("ch", nameFor(key, u.id), "messages", out, "more", more, "lastRead", lastRead(u.id, key),
                "admin", admin(u));
    }

    Map<String, Object> post(Accounts.User u, String ch, String text) {
        String key = key(u, ch);
        String t = clean(text);
        if (t.isEmpty()) {
            throw new Http.Fail(400, "Message vide.");
        }
        long now = System.currentTimeMillis();
        if ("global".equals(key) && u.mutedUntil > now) {
            throw new Http.Fail(403, "Un modérateur vous a rendu muet dans le chat général jusqu'à "
                    + java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm").format(
                    java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(u.mutedUntil), java.time.ZoneId.systemDefault()))
                    + ".");
        }
        if (key.startsWith("room-")) {
            Room r = rooms.find(key.substring(5));
            synchronized (r) {
                if (r.member(u.id) == null) {
                    throw new Http.Fail(403, "Seuls les joueurs de la salle peuvent y écrire.");
                }
            }
        }
        if (key.startsWith("dm-")) {
            Accounts.User other = accounts.get(nameFor(key, u.id).substring(3));
            if (other == null || !u.friends.contains(other.id)) {
                throw new Http.Fail(403, "Les messages privés se font entre amis.");
            }
            if (other.blocked.contains(u.id)) {
                throw new Http.Fail(403, other.display + " ne reçoit pas vos messages.");
            }
        }
        throttle(u.id, now);
        Channel c = channel(key);
        Map<String, Object> m;
        synchronized (c) {
            m = Json.map("id", ++c.seq, "from", u.id, "name", u.display, "avatar", u.avatarAt, "text", t, "at", now);
            c.msgs.addLast(m);
            if (c.msgs.size() > KEEP) {
                c.msgs.pollFirst();
            }
            append(c, m);
        }
        markRead(u.id, key, Json.num(m, "id", 0));
        for (String to : audience(key)) {
            Accounts.User r = accounts.get(to);
            if (r != null && !r.blocked.contains(u.id)) {
                hub.send(to, "chat", Json.map("ch", nameFor(key, to), "msg", view(m, nameFor(key, to))));
            }
        }
        return view(m, nameFor(key, u.id));
    }

    /** The author, or a moderator, deletes a message. */
    void delete(Accounts.User u, String ch, long id) {
        String key = key(u, ch);
        Channel c = channel(key);
        synchronized (c) {
            Map<String, Object> found = null;
            for (Map<String, Object> m : c.msgs) {
                if (Json.num(m, "id", 0) == id) {
                    found = m;
                    break;
                }
            }
            if (found == null) {
                throw new Http.Fail(404, "Message introuvable.");
            }
            if (!u.id.equals(Json.str(found, "from")) && !admin(u)) {
                throw new Http.Fail(403, "Seuls l'auteur et les modérateurs peuvent supprimer ce message.");
            }
            c.msgs.remove(found);
            append(c, Json.map("del", id));
        }
        for (String to : audience(key)) {
            hub.send(to, "chat-delete", Json.map("ch", nameFor(key, to), "id", id));
        }
    }

    /** A moderator silences someone in the general channel for some minutes (0 = no longer). */
    void mute(Accounts.User u, String userId, long minutes) {
        if (!admin(u)) {
            throw new Http.Fail(403, "Réservé aux modérateurs.");
        }
        Accounts.User target = accounts.get(userId);
        if (target == null) {
            throw new Http.Fail(404, "Joueur introuvable.");
        }
        accounts.mute(target, minutes <= 0 ? 0 : System.currentTimeMillis() + Math.min(minutes, 60L * 24 * 365) * 60_000L);
        Log.info("Serveur : " + u.username + (minutes <= 0 ? " rend la parole à " : " rend muet ") + target.username
                + (minutes > 0 ? " (" + minutes + " min)" : ""));
    }

    void read(Accounts.User u, String ch, long id) {
        markRead(u.id, key(u, ch), id);
    }

    /** The channels of a user: general, their rooms, their friends; last message and unread count of each. */
    Map<String, Object> list(Accounts.User u) {
        List<Object> out = new ArrayList<>();
        out.add(summary(u, "global", "Général", null));
        for (Room r : rooms.activeOf(u.id)) {
            out.add(summary(u, "room-" + r.id, r.name, Json.map("roomId", r.id, "state", r.state)));
        }
        for (String f : u.friends) {
            Accounts.User o = accounts.get(f);
            if (o != null) {
                Map<String, Object> s = summary(u, dmKey(u.id, o.id), o.display, Json.map("user", o.pub(), "online", hub.online(o.id)));
                out.add(s);
            }
        }
        return Json.map("channels", out, "online", (long) hub.onlineUsers().size(), "admin", admin(u));
    }

    private Map<String, Object> summary(Accounts.User u, String key, String title, Map<String, Object> extra) {
        Channel c = channel(key);
        Map<String, Object> last = null;
        long unread = 0;
        long read = lastRead(u.id, key);
        synchronized (c) {
            for (Map<String, Object> m : c.msgs) {
                if (u.blocked.contains(Json.str(m, "from"))) {
                    continue;
                }
                last = m;
                if (Json.num(m, "id", 0) > read && !u.id.equals(Json.str(m, "from"))) {
                    unread++;
                }
            }
        }
        Map<String, Object> s = Json.map("ch", nameFor(key, u.id), "title", title, "unread", unread,
                "last", last == null ? null : view(last, nameFor(key, u.id)));
        if (extra != null) {
            s.putAll(extra);
        }
        return s;
    }

    private static Map<String, Object> view(Map<String, Object> m, String ch) {
        Map<String, Object> v = new LinkedHashMap<>(m);
        v.put("ch", ch);
        return v;
    }

    /** One line, at most MAX_TEXT characters; line breaks are kept (at most 5 in a row). */
    static String clean(String text) {
        String t = text == null ? "" : text.replace("\r\n", "\n").replaceAll("[\\p{Cntrl}&&[^\n]]", " ")
                .replaceAll("\n{3,}", "\n\n").strip();
        if (t.codePointCount(0, t.length()) > MAX_TEXT) {
            throw new Http.Fail(400, "Message trop long (" + MAX_TEXT + " caractères au plus).");
        }
        return t;
    }

    private void throttle(String userId, long now) {
        Bucket b = buckets.get(userId);
        double tokens = b == null ? BURST : Math.min(BURST, b.tokens() + (now - b.at()) / (double) REFILL_MS);
        if (tokens < 1) {
            throw new Http.Fail(429, "Doucement : attendez un peu avant d'envoyer un autre message.");
        }
        buckets.put(userId, new Bucket(tokens - 1, now));
    }

    // ----------------------------------------------------------- persistence

    private void append(Channel c, Map<String, Object> line) {
        Path f = dir.resolve(c.key + ".jsonl");
        try {
            Files.writeString(f, Json.stringify(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (Files.size(f) > FILE_LIMIT) {
                // only the kept messages stay in the file
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> m : c.msgs) {
                    sb.append(Json.stringify(m)).append('\n');
                }
                Accounts.write(f, sb.toString());
            }
        } catch (IOException e) {
            Log.warn("Serveur : message non enregistré : " + e.getMessage());
        }
    }

    private long lastRead(String userId, String key) {
        Map<String, Long> per = reads.get(userId);
        Long v = per == null ? null : per.get(key);
        return v == null ? 0 : v;
    }

    private void markRead(String userId, String key, long id) {
        Map<String, Long> per = reads.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        Long before = per.get(key);
        if (before == null || id > before) {
            per.put(key, id);
            readsDirty = true;
        }
    }

    /** Called every few seconds: writes what users read when it changed. */
    void flush() {
        if (!readsDirty) {
            return;
        }
        readsDirty = false;
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Long>> e : reads.entrySet()) {
            Map<String, Object> per = new LinkedHashMap<>();
            for (Map.Entry<String, Long> c : e.getValue().entrySet()) {
                per.put(c.getKey(), c.getValue());
            }
            m.put(e.getKey(), per);
        }
        Accounts.write(readsFile, Json.stringify(m));
    }

    /** A room was deleted: its channel goes too. */
    void roomDeleted(String roomId) {
        String key = "room-" + roomId;
        channels.remove(key);
        try {
            Files.deleteIfExists(dir.resolve(key + ".jsonl"));
        } catch (IOException e) {
            Log.warn("Serveur : discussion de la salle " + roomId + " non supprimée : " + e.getMessage());
        }
    }
}
