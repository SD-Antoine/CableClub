package slt.server;

import slt.Http;
import slt.Json;
import slt.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports of the players about a message or a player (moderation): kept in signalements.json, sent to the
 * moderators who are online and listed for them in the interface, until one of them marks it handled.
 */
final class Reports {

    private static final int KEEP = 1000;
    private static final int MAX_REASON = 300;

    private final Path file;
    private final List<Map<String, Object>> list = new ArrayList<>();
    private long seq;

    Reports(Path base) {
        this.file = base.resolve("signalements.json");
        if (Files.isRegularFile(file)) {
            try {
                List<Object> l = Json.arr(Json.parseObject(Files.readString(file)).get("reports"));
                if (l != null) {
                    for (Object o : l) {
                        Map<String, Object> r = Json.obj(o);
                        if (r != null) {
                            list.add(r);
                            seq = Math.max(seq, Json.num(r, "id", 0));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                Log.warn("Serveur : signalements illisibles : " + e.getMessage());
            }
        }
    }

    /** A new report; {@code message} is the reported message (null for a player). */
    synchronized Map<String, Object> add(Accounts.User by, Accounts.User about, String channel, Map<String, Object> message, String reason) {
        String why = reason == null ? "" : reason.replaceAll("\\p{Cntrl}", " ").strip();
        if (why.codePointCount(0, why.length()) > MAX_REASON) {
            throw new Http.Fail(400, "Motif trop long (" + MAX_REASON + " caractères au plus).");
        }
        for (Map<String, Object> r : list) {
            // the same thing reported twice by the same player: once is enough
            if (!Json.bool(r, "done", false) && by.id.equals(Json.str(r, "by")) && about.id.equals(Json.str(r, "userId"))
                    && (message == null ? Json.num(r, "msgId", 0) == 0
                    : Json.num(r, "msgId", 0) == Json.num(message, "id", -1) && String.valueOf(channel).equals(Json.str(r, "ch")))) {
                return r;
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", ++seq);
        r.put("at", System.currentTimeMillis());
        r.put("by", by.id);
        r.put("byName", by.display);
        r.put("userId", about.id);
        r.put("userName", about.display);
        r.put("username", about.username);
        if (message != null) {
            r.put("ch", channel);
            r.put("msgId", Json.num(message, "id", 0));
            r.put("text", Json.str(message, "text"));
        }
        r.put("reason", why);
        r.put("done", false);
        list.add(r);
        while (list.size() > KEEP) {
            list.remove(0);
        }
        save();
        Log.info("Serveur : signalement de " + about.username + " par " + by.username);
        return r;
    }

    /** The reports, newest first. */
    synchronized List<Object> all() {
        List<Object> out = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            out.add(new LinkedHashMap<>(list.get(i)));
        }
        return out;
    }

    synchronized void done(long id, Accounts.User by, boolean done) {
        for (Map<String, Object> r : list) {
            if (Json.num(r, "id", 0) == id) {
                r.put("done", done);
                r.put("doneBy", done ? by.display : null);
                save();
                return;
            }
        }
        throw new Http.Fail(404, "Signalement introuvable.");
    }

    /** A deleted account: the reports it made and the reports about it go. */
    synchronized void forget(String userId) {
        if (list.removeIf(r -> userId.equals(Json.str(r, "by")) || userId.equals(Json.str(r, "userId")))) {
            save();
        }
    }

    synchronized long open() {
        return list.stream().filter(r -> !Json.bool(r, "done", false)).count();
    }

    private void save() {
        Accounts.write(file, Json.stringify(Json.map("version", 1L, "reports", new ArrayList<Object>(list))));
    }
}
