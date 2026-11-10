package slt.server;

import slt.Http;
import slt.Json;
import slt.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The public modes (point 2d): mode files the players publish on the server, for everyone to find, take and play.
 * A published mode is checked like any imported mode and tied to the account that published it (shown next to the
 * author the file names). Moderation comes after publication, as for the chat: a mode can be reported, and a
 * moderator takes it out of the gallery. Kept in modes-publics.json.
 */
final class ModeGallery {

    /** Modes one account may have in the gallery. */
    static final int MAX_PER_ACCOUNT = 20;
    /** Publications (new or updated) one account may make in a window of time. */
    static final int MAX_PUBLICATIONS = 10;
    static final long PUBLICATION_WINDOW_MS = 10 * 60_000L;
    /** Mode files one account may have checked in a window (the editor checks after each change). */
    static final int MAX_CHECKS = 60;
    static final long CHECK_WINDOW_MS = 60_000L;
    /** Modes a listing returns at most. */
    private static final int MAX_LISTED = 100;

    private final Path file;
    private final Accounts accounts;
    private final Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
    final Map<String, Deque<Long>> publications = new ConcurrentHashMap<>();   // by account: when they published
    final Map<String, Deque<Long>> checks = new ConcurrentHashMap<>();         // by account: when they checked a file

    ModeGallery(Path base, Accounts accounts) {
        this.file = base.resolve("modes-publics.json");
        this.accounts = accounts;
        if (Files.isRegularFile(file)) {
            try {
                List<Object> list = Json.arr(Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)).get("modes"));
                for (Object o : list == null ? List.of() : list) {
                    Map<String, Object> e = Json.obj(o);
                    // read like the copy a room keeps: a later, stricter validator does not lose a published mode
                    ModeSpec spec = e == null ? null : ModeSpec.stored(Json.stringify(e.get("mode")));
                    if (spec != null) {
                        e.put("mode", spec.toJson());
                        entries.put(spec.id, e);
                    }
                }
            } catch (IOException | RuntimeException e) {
                Log.warn("Serveur : modes publics illisibles : " + e.getMessage());
            }
        }
    }

    /** Publishes a mode, or updates the one this account published under the same id. */
    synchronized Map<String, Object> publish(Accounts.User user, String text) {
        ModeSpec spec = Rooms.importMode(text);
        Map<String, Object> e = entries.get(spec.id);
        if (e != null && !user.id.equals(Json.str(e, "owner"))) {
            throw new Http.Fail(409, "« " + spec.id + " » est déjà publié par un autre joueur : choisissez un autre identifiant.");
        }
        if (e != null && Json.bool(e, "hidden", false)) {
            throw new Http.Fail(409, "Ce mode a été retiré de la galerie par la modération.");
        }
        if (e == null && entries.values().stream().filter(x -> user.id.equals(Json.str(x, "owner"))).count() >= MAX_PER_ACCOUNT) {
            throw new Http.Fail(409, "Vous avez déjà " + MAX_PER_ACCOUNT + " modes publiés : retirez-en un avant d'en publier un autre.");
        }
        throttle(publications, user.id, System.currentTimeMillis(), MAX_PUBLICATIONS, PUBLICATION_WINDOW_MS,
                "Doucement : réessayez de publier dans quelques minutes.");
        long now = System.currentTimeMillis();
        if (e == null) {
            e = new LinkedHashMap<>();
            e.put("owner", user.id);
            e.put("publishedAt", now);
            e.put("uses", 0L);
            e.put("hidden", false);
            entries.put(spec.id, e);
        }
        e.put("mode", spec.toJson());
        e.put("updatedAt", now);
        save();
        Log.info("Serveur : mode « " + spec.id + " » publié par " + user.username);
        return view(spec.id, e, user);
    }

    /**
     * The owner takes their mode out of the gallery (rooms already created keep their copy). A mode the moderation took
     * out stays recorded: removing it would let its owner publish it again under the same id.
     */
    synchronized void unpublish(Accounts.User user, String id) {
        Map<String, Object> e = entries.get(id);
        if (e == null || !user.id.equals(Json.str(e, "owner"))) {
            throw new Http.Fail(404, "Mode publié introuvable.");
        }
        if (Json.bool(e, "hidden", false)) {
            throw new Http.Fail(409, "Ce mode a été retiré de la galerie par la modération : il n'y est déjà plus visible.");
        }
        entries.remove(id);
        save();
    }

    /** A moderator takes a mode out of the gallery; its owner cannot publish it again under this id. */
    synchronized void hide(Accounts.User moderator, String id) {
        Map<String, Object> e = entries.get(id);
        if (e == null) {
            throw new Http.Fail(404, "Mode publié introuvable.");
        }
        e.put("hidden", true);
        e.put("hiddenBy", moderator.id);
        e.put("hiddenAt", System.currentTimeMillis());
        save();
        Log.info("Serveur : mode « " + id + " » retiré de la galerie par " + moderator.username);
    }

    /**
     * The modes of the gallery, the most played first; {@code query}: words found in the name, the description, the
     * author or the publisher. {@code mine}: the modes of this account, those taken out by the moderation included.
     */
    synchronized List<Object> list(Accounts.User user, String query, boolean mine) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Map.Entry<String, Map<String, Object>>> found = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> en : entries.entrySet()) {
            Map<String, Object> e = en.getValue();
            boolean own = user.id.equals(Json.str(e, "owner"));
            if (mine ? !own : Json.bool(e, "hidden", false)) {
                continue;
            }
            if (!q.isEmpty()) {
                Map<String, Object> m = Json.obj(e.get("mode"));
                String hay = (Json.str(m, "name") + " " + Json.str(m, "description") + " " + Json.str(m, "author") + " "
                        + ownerName(e)).toLowerCase(Locale.ROOT);
                boolean all = true;
                for (String w : q.split("\\s+")) {
                    all &= hay.contains(w);
                }
                if (!all) {
                    continue;
                }
            }
            found.add(en);
        }
        found.sort((a, b) -> {
            int c = Long.compare(Json.num(b.getValue(), "uses", 0), Json.num(a.getValue(), "uses", 0));
            return c != 0 ? c : Long.compare(Json.num(b.getValue(), "updatedAt", 0), Json.num(a.getValue(), "updatedAt", 0));
        });
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> en : found.subList(0, Math.min(MAX_LISTED, found.size()))) {
            out.add(view(en.getKey(), en.getValue(), user));
        }
        return out;
    }

    /** Whether this very mode (the same file) is in the gallery and not taken out: its rooms may then be public. */
    synchronized boolean published(ModeSpec spec) {
        Map<String, Object> e = spec == null ? null : entries.get(spec.id);
        return e != null && !Json.bool(e, "hidden", false) && Json.stringify(e.get("mode")).equals(Json.stringify(spec.toJson()));
    }

    /** A room was created with this mode (the gallery shows the most played first). */
    synchronized void used(ModeSpec spec) {
        if (published(spec)) {
            Map<String, Object> e = entries.get(spec.id);
            e.put("uses", Json.num(e, "uses", 0) + 1);
            save();
        }
    }

    /** The owner of a published mode, and its text for a report (null when it is not in the gallery). */
    synchronized Map<String, Object> forReport(String id) {
        Map<String, Object> e = entries.get(id);
        if (e == null) {
            return null;
        }
        Map<String, Object> m = Json.obj(e.get("mode"));
        return Json.map("owner", Json.str(e, "owner"), "text", "Mode « " + Json.str(m, "name") + " » (" + id + ") par "
                + Json.str(m, "author") + " : " + Json.str(m, "description"));
    }

    /** An account is deleted: its modes leave the gallery. */
    synchronized void forget(String userId) {
        if (entries.values().removeIf(e -> userId.equals(Json.str(e, "owner")))) {
            save();
        }
    }

    private Map<String, Object> view(String id, Map<String, Object> e, Accounts.User user) {
        Map<String, Object> v = Json.map("id", id, "mode", e.get("mode"), "publisher", ownerName(e),
                "publishedAt", e.get("publishedAt"), "updatedAt", e.get("updatedAt"), "uses", e.get("uses"),
                "mine", user.id.equals(Json.str(e, "owner")));
        if (Json.bool(e, "hidden", false)) {
            v.put("hidden", true);
        }
        return v;
    }

    private String ownerName(Map<String, Object> e) {
        Accounts.User owner = accounts.get(Json.str(e, "owner"));
        return owner == null ? "?" : owner.display;
    }

    /** A mode file is about to be checked (/api/modes/check): within {@link #MAX_CHECKS} a minute per account. */
    void limitCheck(String userId, long now) {
        throttle(checks, userId, now, MAX_CHECKS, CHECK_WINDOW_MS, "Doucement : trop de vérifications de mode, réessayez dans un instant.");
    }

    private static void throttle(Map<String, Deque<Long>> byUser, String userId, long now, int max, long window, String message) {
        Deque<Long> times = byUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() < now - window) {
                times.pollFirst();
            }
            if (times.size() >= max) {
                throw new Http.Fail(429, message);
            }
            times.addLast(now);
        }
    }

    private void save() {
        Accounts.write(file, Json.stringify(Json.map("version", 1L, "modes", new ArrayList<Object>(entries.values()))));
    }
}
