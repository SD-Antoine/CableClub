package slt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reading whole streams, also on the Android versions whose Java has no InputStream.readAllBytes (before 13). */
public final class Io {

    private Io() {
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(4096, in.available()));
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
