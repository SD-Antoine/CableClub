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

    /**
     * One report (point 9 of the follow-up of the audit of 2026-11-10): typed reading of the map stored in the file,
     * kept as it was read and written back as it is.
     */
    static final class Report {
        private final Map<String, Object> raw;

        Report(Map<String, Object> raw) {
            this.raw = raw;
        }

        long id() {
            return Json.num(raw, "id", 0);
        }

        /** The account that reported. */
        String by() {
            return Json.str(raw, "by");
        }

        /** The account reported. */
        String userId() {
            return Json.str(raw, "userId");
        }

        /** The message reported (0: the player, not a message) and its channel. */
        long msgId() {
            return Json.num(raw, "msgId", 0);
        }

        String channel() {
            return Json.str(raw, "ch");
        }

        boolean done() {
            return Json.bool(raw, "done", false);
        }

        void done(boolean done, String byName) {
            raw.put("done", done);
            raw.put("doneBy", done ? byName : null);
        }

        Map<String, Object> toJson() {
            return raw;
        }
    }

    private final Path file;
    private final List<Report> list = new ArrayList<>();
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
                            list.add(new Report(r));
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
        for (Report r : list) {
            // the same thing reported twice by the same player: once is enough
            if (!r.done() && by.id.equals(r.by()) && about.id.equals(r.userId())
                    && (message == null ? r.msgId() == 0
                    : r.msgId() == Json.num(message, "id", -1) && String.valueOf(channel).equals(r.channel()))) {
                return r.toJson();
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
        list.add(new Report(r));
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
            out.add(new LinkedHashMap<>(list.get(i).toJson()));
        }
        return out;
    }

    synchronized void done(long id, Accounts.User by, boolean done) {
        for (Report r : list) {
            if (r.id() == id) {
                r.done(done, by.display);
                save();
                return;
            }
        }
        throw new Http.Fail(404, "Signalement introuvable.");
    }

    /** A deleted account: the reports it made and the reports about it go. */
    synchronized void forget(String userId) {
        if (list.removeIf(r -> userId.equals(r.by()) || userId.equals(r.userId()))) {
            save();
        }
    }

    synchronized long open() {
        return list.stream().filter(r -> !r.done()).count();
    }

    private void save() {
        List<Object> out = new ArrayList<>();
        for (Report r : list) {
            out.add(r.toJson());
        }
        Accounts.write(file, Json.stringify(Json.map("version", 1L, "reports", out)));
    }
}
