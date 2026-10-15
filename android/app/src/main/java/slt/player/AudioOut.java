package slt.player;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import slt.Log;

/**
 * Stereo 16-bit output on Android. Samples are resampled to the device rate; the blocking write is what paces the
 * emulation to real time (as on the PC).
 */
final class AudioOut implements AutoCloseable {

    private static final int OUT_RATE = 48000;

    private final AudioTrack track;
    private final double step;
    private double pos;
    private short lastL;
    private short lastR;
    private short[] out = new short[8192];
    private volatile float volume = 1f;
    private volatile boolean muted;

    private AudioOut(AudioTrack track, double inRate) {
        this.track = track;
        this.step = inRate / OUT_RATE;
    }

    /** @return an output, or null when no sound device is available */
    static AudioOut open(double inRate) {
        try {
            int min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            // ~80 ms of buffer: low latency but tolerant to small hiccups
            int size = Math.max(min, OUT_RATE * 4 * 80 / 1000);
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(OUT_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            t.play();
            return new AudioOut(t, inRate);
        } catch (RuntimeException e) {
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
        if (out.length < maxOut * 2) {
            out = new short[maxOut * 2];
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
            out[o++] = (short) ((l0 + (l1 - l0) * frac) * vol);
            out[o++] = (short) ((r0 + (r1 - r0) * frac) * vol);
            pos += step;
        }
        pos -= frames;
        lastL = in[(frames - 1) * 2];
        lastR = in[(frames - 1) * 2 + 1];
        track.write(out, 0, o, AudioTrack.WRITE_BLOCKING);
    }

    @Override
    public void close() {
        try {
            track.stop();
            track.release();
        } catch (RuntimeException ignored) {
            // device already gone
        }
    }
}
