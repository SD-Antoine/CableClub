package slt.server;

import slt.Http;
import slt.Json;
import slt.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The journal of each room, one JSON line per event in journaux/ (taken out of {@link Rooms}, point 8 of the
 * follow-up of the audit of 2026-11-10): written at the end, read from the end, rewritten without the name of a
 * deleted account.
 */
final class RoomJournal {

    private final Path dir;

    RoomJournal(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    private Path file(String roomId) {
        return dir.resolve(roomId + ".jsonl");
    }

    /** One more line at the end of the room's journal. */
    void append(Room r, Map<String, Object> line) {
        try {
            Files.writeString(file(r.id), Json.stringify(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.warn("Serveur : journal non écrit : " + e.getMessage());
        }
    }

    /** The lines after {@code after}, {@code limit} at most (the reader is a member, checked by the caller). */
    List<Object> read(Room r, long after, int limit) {
        Path f = file(r.id);
        if (!Files.isRegularFile(f)) {
            return new ArrayList<>();
        }
        try {
            return tail(f, after, limit);
        } catch (IOException | RuntimeException e) {
            throw new Http.Fail(500, "Journal illisible : " + e.getMessage());
        }
    }

    /**
     * The last {@code limit} lines of a journal numbered after {@code after}, in their order. Read from the end by
     * blocks: a request costs at most {@code limit} lines, however long the journal (a long run has thousands). The
     * lines are numbered in order, so the reading stops at the first one not after {@code after}.
     */
    static List<Object> tail(Path f, long after, int limit) throws IOException {
        java.util.ArrayDeque<Object> out = new java.util.ArrayDeque<>();
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(f, StandardOpenOption.READ)) {
            long pos = ch.size();
            byte[] rest = new byte[0]; // the start of a line cut by the beginning of the block read
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(64 * 1024);
            while (out.size() < limit) {
                if (pos == 0) {
                    if (rest.length > 0) {
                        add(out, rest, 0, rest.length, after); // the first line of the journal
                    }
                    break;
                }
                int n = (int) Math.min(buf.capacity(), pos);
                pos -= n;
                buf.clear().limit(n);
                while (buf.hasRemaining()) {
                    if (ch.read(buf, pos + buf.position()) < 0) {
                        throw new IOException("fin du journal inattendue");
                    }
                }
                byte[] block = new byte[n + rest.length];
                System.arraycopy(buf.array(), 0, block, 0, n);
                System.arraycopy(rest, 0, block, n, rest.length);
                int end = block.length;
                boolean more = true;
                for (int i = block.length - 1; i >= 0 && more && out.size() < limit; i--) {
                    if (block[i] == '\n') {
                        more = add(out, block, i + 1, end, after);
                        end = i;
                    }
                }
                if (!more || out.size() >= limit) {
                    break;
                }
                rest = java.util.Arrays.copyOf(block, end);
            }
        }
        return new ArrayList<>(out);
    }

    /** One line of a journal read from the end: added in front if numbered after {@code after}; false to stop. */
    private static boolean add(java.util.ArrayDeque<Object> out, byte[] b, int from, int to, long after) {
        String line = new String(b, from, to - from, StandardCharsets.UTF_8);
        if (line.isBlank()) {
            return true;
        }
        Map<String, Object> l = Json.parseObject(line);
        long seq = Json.num(l, "seq", 0);
        if (seq > after) {
            out.addFirst(l);
            return true;
        }
        return seq <= 0; // a line without a number does not stop the reading
    }

    /** The journal of a room without the name of a deleted account. */
    void anonymize(Room r, Accounts.User user) {
        Path f = file(r.id);
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> m = Json.parseObject(line);
                if (user.id.equals(Json.str(m, "user"))) {
                    m.put("name", Rooms.DELETED);
                }
                String text = Json.str(m, "text");
                if (text != null && user.display.length() >= 2) {
                    m.put("text", text.replace(user.display, Rooms.DELETED));
                }
                sb.append(Json.stringify(m)).append('\n');
            }
            Accounts.write(f, sb.toString());
        } catch (IOException | RuntimeException e) {
            Log.warn("Serveur : journal de la salle " + r.id + " non anonymisé : " + e.getMessage());
        }
    }

    /** The journal of a deleted room. */
    void delete(String roomId) throws IOException {
        Files.deleteIfExists(file(roomId));
    }
}
