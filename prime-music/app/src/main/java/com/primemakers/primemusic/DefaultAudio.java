package com.primemakers.primemusic;

import android.content.Context;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Generates three small local demo tracks once. No network, no bundled copyrighted music. */
public final class DefaultAudio {
    private static final int SAMPLE_RATE = 22050;
    private static final int SECONDS = 18;

    private DefaultAudio() {}

    public static List<Track> ensure(Context context) {
        File dir = new File(context.getFilesDir(), "prime_default_music");
        if (!dir.exists()) dir.mkdirs();

        String[] names = {"Neon Drift", "Midnight Drive", "Cosmic Pulse"};
        double[] roots = {196.0, 146.83, 220.0};
        int[] styles = {0, 1, 2};
        ArrayList<Track> tracks = new ArrayList<>();

        for (int i = 0; i < names.length; i++) {
            File f = new File(dir, "prime_default_" + (i + 1) + ".wav");
            if (!f.exists() || f.length() < 10000) {
                try { writeTrack(f, roots[i], styles[i]); } catch (IOException ignored) {}
            }
            tracks.add(new Track(
                    "default:" + (i + 1), names[i], "Prime Makers",
                    f.getAbsolutePath(), "", SECONDS * 1000L, true, false));
        }
        return tracks;
    }

    private static void writeTrack(File file, double root, int style) throws IOException {
        int totalSamples = SAMPLE_RATE * SECONDS;
        int dataSize = totalSamples * 2; // mono, 16 bit
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            writeAscii(out, "RIFF");
            writeLE32(out, 36 + dataSize);
            writeAscii(out, "WAVE");
            writeAscii(out, "fmt ");
            writeLE32(out, 16);
            writeLE16(out, 1);
            writeLE16(out, 1);
            writeLE32(out, SAMPLE_RATE);
            writeLE32(out, SAMPLE_RATE * 2);
            writeLE16(out, 2);
            writeLE16(out, 16);
            writeAscii(out, "data");
            writeLE32(out, dataSize);

            double[] ratios = style == 0
                    ? new double[]{1.0, 1.189207, 1.498307, 1.781797}
                    : style == 1
                    ? new double[]{1.0, 1.122462, 1.334840, 1.681793}
                    : new double[]{1.0, 1.259921, 1.498307, 2.0};

            for (int n = 0; n < totalSamples; n++) {
                double t = n / (double) SAMPLE_RATE;
                int step = ((int) (t / 2.25)) % ratios.length;
                double f = root * ratios[step];
                double beat = t % 0.5;
                double kick = Math.exp(-beat * 12.0) * Math.sin(2 * Math.PI * (55 + 35 * Math.exp(-beat * 10)) * t);
                double pad = 0.55 * Math.sin(2 * Math.PI * f * t)
                        + 0.22 * Math.sin(2 * Math.PI * (f * 1.5) * t)
                        + 0.12 * Math.sin(2 * Math.PI * (f * 2.0) * t);
                double pulse = (style == 2 ? 0.20 : 0.12) * Math.sin(2 * Math.PI * (f / 2.0) * t);
                double fadeIn = Math.min(1.0, t / 0.8);
                double fadeOut = Math.min(1.0, (SECONDS - t) / 1.2);
                double sample = (pad * 0.52 + kick * 0.32 + pulse) * fadeIn * fadeOut;
                sample = Math.max(-1.0, Math.min(1.0, sample));
                short s = (short) Math.round(sample * 28000.0);
                out.write(s & 0xff);
                out.write((s >>> 8) & 0xff);
            }
        }
    }

    private static void writeAscii(BufferedOutputStream out, String s) throws IOException {
        for (int i = 0; i < s.length(); i++) out.write((byte) s.charAt(i));
    }
    private static void writeLE16(BufferedOutputStream out, int v) throws IOException {
        out.write(v & 0xff); out.write((v >>> 8) & 0xff);
    }
    private static void writeLE32(BufferedOutputStream out, int v) throws IOException {
        out.write(v & 0xff); out.write((v >>> 8) & 0xff); out.write((v >>> 16) & 0xff); out.write((v >>> 24) & 0xff);
    }
}