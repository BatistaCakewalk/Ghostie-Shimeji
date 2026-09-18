package com.group_finity.mascot.sound;

import com.group_finity.mascot.Main;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runtime-synthesized sound blips for Nigel's big moments. No audio assets:
 * every sound is a pitch sweep and/or noise burst rendered to PCM in memory.
 * Playback runs on a single daemon thread so the mascot tick never blocks,
 * and everything is silent unless enabled in Nigel Settings (off by default).
 */
public final class NigelSounds {
    private static final int SAMPLE_RATE = 22050;
    private static final double VOLUME = 0.4;

    private static final ExecutorService PLAYER = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "nigel-sounds");
        thread.setDaemon(true);
        return thread;
    });

    private NigelSounds() {
    }

    private static boolean isEnabled() {
        try {
            return Main.getInstance().getSettings().nigelSoundsEnabled;
        } catch (final RuntimeException e) {
            return false;
        }
    }

    public static void playGulp() {
        if (!isEnabled()) {
            return;
        }
        // Quick upward blip: down the hatch.
        play(tone(300.0, 650.0, 0.12, 0.0, false));
    }

    public static void playBurp(final double power) {
        if (!isEnabled()) {
            return;
        }
        // Low downward slide with grit; bigger power means longer and lower.
        final double clamped = Math.max(1.0, Math.min(4.0, power));
        play(tone(200.0, 90.0 - clamped * 5.0, 0.2 + clamped * 0.08, 0.35, false));
    }

    public static void playLaunch() {
        if (!isEnabled()) {
            return;
        }
        // Spit whoosh: pure noise burst with a fast decay.
        play(tone(1200.0, 300.0, 0.18, 0.9, false));
    }

    public static void playBonk() {
        if (!isEnabled()) {
            return;
        }
        // Tackle impact: short low square thud.
        play(tone(150.0, 90.0, 0.09, 0.0, true));
    }

    private static byte[] tone(final double freqFrom, final double freqTo, final double seconds,
            final double noiseMix, final boolean square) {
        final int samples = Math.max(1, (int) (SAMPLE_RATE * seconds));
        final byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            final double progress = i / (double) samples;
            final double time = i / (double) SAMPLE_RATE;
            final double phase = 2.0 * Math.PI
                    * (freqFrom * time + (freqTo - freqFrom) * time * time / (2.0 * seconds));
            double wave = Math.sin(phase);
            if (square) {
                wave = Math.signum(wave) * 0.8;
            }
            final double sample = wave * (1.0 - noiseMix) + (Math.random() * 2.0 - 1.0) * noiseMix;
            // Fast attack ramp plus exponential decay: no clicks, natural fade.
            final double attack = Math.min(1.0, i / (SAMPLE_RATE * 0.005));
            final double envelope = attack * Math.exp(-3.0 * progress);
            final short value = (short) (sample * envelope * VOLUME * 32767);
            pcm[i * 2] = (byte) (value & 0xFF);
            pcm[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return pcm;
    }

    private static void play(final byte[] pcm) {
        PLAYER.execute(() -> {
            try {
                final AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
                try (final SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                    line.open(format);
                    line.start();
                    line.write(pcm, 0, pcm.length);
                    line.drain();
                }
            } catch (final Exception ignored) {
                // No audio device, unsupported format, security manager:
                // Nigel suffers in silence.
            }
        });
    }
}
