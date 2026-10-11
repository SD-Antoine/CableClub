package slt;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Tiny logger writing to stderr and to data/slt.log. */
public final class Log {

    private static Path file;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Log() {
    }

    public static synchronized void init(Path dataDir) {
        init(dataDir, "slt.log");
    }

    public static synchronized void init(Path dataDir, String name) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException ignored) {
            // reported on the first write
        }
        file = dataDir.resolve(name);
        try {
            if (Files.exists(file) && Files.size(file) > 2_000_000) {
                Files.move(file, dataDir.resolve(name.replace(".log", ".old.log")), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // keep logging to the existing file
        }
    }

    public static void info(String msg) {
        write("INFO ", msg);
    }

    public static void warn(String msg) {
        write("WARN ", msg);
    }

    public static void error(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) {
            t.printStackTrace(new PrintWriter(sw));
        }
        write("ERROR", msg + (t == null ? "" : "\n" + sw));
    }

    private static synchronized void write(String level, String msg) {
        String line = LocalDateTime.now().format(TS) + " " + level + " " + msg;
        System.err.println(line);
        if (file != null) {
            try {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // logging must never break the app
            }
        }
    }
}
