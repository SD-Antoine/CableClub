package slt.player;

import slt.Log;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Stereo 16-bit output. Samples are resampled to the device rate; the
 * blocking write is what paces the emulation to real time.
 */
final class AudioOut implements AutoCloseable {

    private static final int OUT_RATE = 48000;

    private final SourceDataLine line;
    private final double step;
    private double pos;
    private short lastL;
    private short lastR;
    private byte[] bytes = new byte[16384];
    private volatile float volume = 1f;
    private volatile boolean muted;

    private AudioOut(SourceDataLine line, double inRate) {
        this.line = line;
        this.step = inRate / OUT_RATE;
    }

    /** @return an output, or null when no sound device is available */
    static AudioOut open(double inRate) {
        try {
            AudioFormat fmt = new AudioFormat(OUT_RATE, 16, 2, true, false);
            SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
            // ~70 ms of buffer: low latency but tolerant to small hiccups
            line.open(fmt, (OUT_RATE * 4 * 70 / 1000) & ~3);
            line.start();
            return new AudioOut(line, inRate);
        } catch (Exception | LinkageError e) {
            Log.warn("Pas de sortie audio : " + e.getMessage());
            return null;
        }
    }

    void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
    }

    float volume() {
        return volume;
    }

    void setMuted(boolean m) {
        muted = m;
    }

    boolean muted() {
        return muted;
    }

    /** Resamples and writes interleaved stereo samples; blocks while the device buffer is full. */
    void write(short[] in, int len) {
        int frames = len / 2;
        if (frames == 0) {
            return;
        }
        int maxOut = (int) (frames / step) + 4;
        if (bytes.length < maxOut * 4) {
            bytes = new byte[maxOut * 4];
        }
        float vol = muted ? 0f : volume;
        int o = 0;
        while (pos < frames) {
            int i = (int) pos;
            double frac = pos - i;
            short l0 = i == 0 ? lastL : in[(i - 1) * 2];
            short r0 = i == 0 ? lastR : in[(i - 1) * 2 + 1];
            short l1 = in[i * 2];
            short r1 = in[i * 2 + 1];
            int l = (int) ((l0 + (l1 - l0) * frac) * vol);
            int r = (int) ((r0 + (r1 - r0) * frac) * vol);
            bytes[o++] = (byte) l;
            bytes[o++] = (byte) (l >> 8);
            bytes[o++] = (byte) r;
            bytes[o++] = (byte) (r >> 8);
            pos += step;
        }
        pos -= frames;
        lastL = in[(frames - 1) * 2];
        lastR = in[(frames - 1) * 2 + 1];
        line.write(bytes, 0, o);
    }

    /** Audio still waiting in the device buffer, in milliseconds. */
    int queuedMillis() {
        int queued = line.getBufferSize() - line.available();
        return queued * 1000 / (OUT_RATE * 4);
    }

    @Override
    public void close() {
        try {
            line.stop();
            line.flush();
            line.close();
        } catch (Exception ignored) {
            // device already gone
        }
    }
}
