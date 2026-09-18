package com.group_finity.mascot.sound;

import com.group_finity.mascot.Main;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
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
        // Wet burp: low slide with a fast gargle flutter and grit.
        // Bigger power means longer, lower and wetter.
        final double clamped = Math.max(1.0, Math.min(4.0, power));
        play(gargle(190.0, 70.0, 0.25 + clamped * 0.09, 28.0, 0.45));
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

    public static void playLeap() {
        if (!isEnabled()) {
            return;
        }
        // Pounce: fast rising whistle.
        play(tone(500.0, 1400.0, 0.14, 0.0, false));
    }

    public static void playBreakFree() {
        if (!isEnabled()) {
            return;
        }
        // The one that got away: sad little slide down.
        play(tone(600.0, 220.0, 0.22, 0.0, false));
    }

    public static void playCuddle() {
        if (!isEnabled()) {
            return;
        }
        // Warm two-note pop, up a perfect fifth.
        play(notes(new double[] { 440.0, 660.0 }, 0.09, 0.0, false));
    }

    public static void playHeave() {
        if (!isEnabled()) {
            return;
        }
        // Queasy wobble before the burp: pitch wavers instead of sliding.
        play(wobble(160.0, 0.3, 9.0, 40.0, 0.0));
    }

    public static void playDropThud() {
        if (!isEnabled()) {
            return;
        }
        // Window dropped: dull low thud with grit.
        play(tone(120.0, 60.0, 0.14, 0.4, false));
    }

    /**
     * Starts the telekinesis drone: a looping hum that lasts the whole hold.
     * Restart-safe (restarts cleanly) so re-pitching mid-pull and rapid
     * re-lifts never stack drones. Higher base pitch and harshness read as
     * more force; full harshness sounds unstable on purpose. Re-pitches
     * crossfade: the new loop starts first and the old one closes after a
     * short overlap, so there is no dropout gap.
     */
    public static synchronized void startHum(final double baseFreq, final double harshness) {
        if (!isEnabled()) {
            return;
        }
        if (humClip != null && baseFreq == humBase && harshness == humHarsh) {
            return;
        }
        humBase = baseFreq;
        humHarsh = harshness;
        final Clip outgoing = humClip;
        humClip = null;
        final long gen = ++humGen;
        PLAYER.execute(() -> {
            Clip incoming = null;
            try {
                incoming = openHum(baseFreq, harshness);
                incoming.start();
            } catch (final Exception ignored) {
            }
            if (outgoing != null) {
                try {
                    Thread.sleep(HUM_OVERLAP_MILLIS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (NigelSounds.class) {
                if (gen == humGen && humClip == null) {
                    humClip = incoming;
                    incoming = null;
                }
            }
            closeQuietly(outgoing);
            closeQuietly(incoming);
        });
    }

    /**
     * Stops the telekinesis drone. Idempotent: safe from every hold exit
     * path at once (endHold and disposeGlows both call it). Also cancels a
     * crossfade in flight: the generation bump strands it, and it closes
     * everything it opened.
     */
    public static synchronized void stopHum() {
        humGen++;
        humBase = Double.NaN;
        humHarsh = Double.NaN;
        closeQuietly(humClip);
        humClip = null;
    }

    private static final long HUM_OVERLAP_MILLIS = 120;

    private static Clip humClip;

    private static long humGen;

    private static double humBase = Double.NaN;

    private static double humHarsh = Double.NaN;

    private static Clip openHum(final double baseFreq, final double harshness) throws Exception {
        final byte[] drone = drone(baseFreq, Math.max(0.0, Math.min(1.0, harshness)));
        final AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        final Clip clip = AudioSystem.getClip();
        clip.open(format, drone, 0, drone.length);
        clip.loop(Clip.LOOP_CONTINUOUSLY);
        return clip;
    }

    private static void closeQuietly(final Clip clip) {
        if (clip != null) {
            try {
                clip.stop();
                clip.close();
            } catch (final Exception ignored) {
            }
        }
    }

    private static byte[] drone(final double baseFreq, final double harshness) {
        // Layered hum with slow beating, quiet enough to sit under
        // everything. Harshness adds grit and a faster wobble until it
        // sounds unstable. Both ends sit at zero so the loop has no click.
        final double seconds = 2.0;
        final int samples = Math.max(1, (int) (SAMPLE_RATE * seconds));
        final byte[] pcm = new byte[samples * 2];
        double smoothNoise = 0.0;
        for (int i = 0; i < samples; i++) {
            final double time = i / (double) SAMPLE_RATE;
            final double progress = i / (double) samples;
            final double wave = Math.sin(2.0 * Math.PI * baseFreq * time) * 0.5
                    + Math.sin(2.0 * Math.PI * baseFreq * 1.5 * time) * 0.3
                    + Math.sin(2.0 * Math.PI * baseFreq * 2.0 * time) * 0.2;
            final double beat = 0.7 + 0.3 * Math.sin(2.0 * Math.PI * (0.5 + harshness * 5.0) * time);
            // Instability as beating and dark rumble, not white-noise hiss:
            // a detuned partial plus heavily smoothed noise.
            final double detune = Math.sin(2.0 * Math.PI * baseFreq * 2.01 * time) * harshness * 0.15;
            smoothNoise += 0.08 * ((Math.random() * 2.0 - 1.0) - smoothNoise);
            final double rumble = smoothNoise * harshness * 0.5;
            final double edge = Math.min(1.0, Math.min(progress, 1.0 - progress) * samples / (SAMPLE_RATE * 0.05));
            final short value = (short) ((wave * (1.0 - harshness * 0.2) + detune + rumble)
                    * beat * edge * 0.22 * 32767);
            pcm[i * 2] = (byte) (value & 0xFF);
            pcm[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return pcm;
    }

    public static void playGlug() {
        if (!isEnabled()) {
            return;
        }
        // Cursor fully inside: deep two-stage GLUG-glug.
        play(notes(new double[] { 280.0, 190.0 }, 0.1, 0.25, false));
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

    private static byte[] notes(final double[] freqs, final double eachSeconds, final double noiseMix,
            final boolean square) {
        final int each = Math.max(1, (int) (SAMPLE_RATE * eachSeconds));
        final byte[] pcm = new byte[each * freqs.length * 2];
        for (int n = 0; n < freqs.length; n++) {
            for (int i = 0; i < each; i++) {
                final double progress = (n * each + i) / (double) (each * freqs.length);
                final double time = i / (double) SAMPLE_RATE;
                double wave = Math.sin(2.0 * Math.PI * freqs[n] * time);
                if (square) {
                    wave = Math.signum(wave) * 0.8;
                }
                final double sample = wave * (1.0 - noiseMix) + (Math.random() * 2.0 - 1.0) * noiseMix;
                final double attack = Math.min(1.0, i / (SAMPLE_RATE * 0.005));
                final double envelope = attack * Math.exp(-2.0 * progress);
                final short value = (short) (sample * envelope * VOLUME * 32767);
                final int at = (n * each + i) * 2;
                pcm[at] = (byte) (value & 0xFF);
                pcm[at + 1] = (byte) ((value >> 8) & 0xFF);
            }
        }
        return pcm;
    }

    private static byte[] wobble(final double baseFreq, final double seconds, final double wobbleHz,
            final double wobbleDepth, final double noiseMix) {
        final int samples = Math.max(1, (int) (SAMPLE_RATE * seconds));
        final byte[] pcm = new byte[samples * 2];
        double phase = 0.0;
        for (int i = 0; i < samples; i++) {
            final double time = i / (double) SAMPLE_RATE;
            final double freq = baseFreq + Math.sin(2.0 * Math.PI * wobbleHz * time) * wobbleDepth;
            phase += 2.0 * Math.PI * freq / SAMPLE_RATE;
            final double progress = i / (double) samples;
            final double attack = Math.min(1.0, i / (SAMPLE_RATE * 0.005));
            final double envelope = attack * (1.0 - progress * 0.5);
            final double sample = Math.sin(phase) * (1.0 - noiseMix)
                    + (Math.random() * 2.0 - 1.0) * noiseMix;
            final short value = (short) (sample * envelope * VOLUME * 32767);
            pcm[i * 2] = (byte) (value & 0xFF);
            pcm[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return pcm;
    }

    private static byte[] gargle(final double freqFrom, final double freqTo, final double seconds,
            final double flutterHz, final double noiseMix) {
        final int samples = Math.max(1, (int) (SAMPLE_RATE * seconds));
        final byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            final double progress = i / (double) samples;
            final double time = i / (double) SAMPLE_RATE;
            final double phase = 2.0 * Math.PI
                    * (freqFrom * time + (freqTo - freqFrom) * time * time / (2.0 * seconds));
            // Fast amplitude flutter reads as wetness; noise reads as grit.
            final double flutter = 0.55 + 0.45 * Math.sin(2.0 * Math.PI * flutterHz * time);
            final double sample = (Math.sin(phase) * (1.0 - noiseMix)
                    + (Math.random() * 2.0 - 1.0) * noiseMix) * flutter;
            final double attack = Math.min(1.0, i / (SAMPLE_RATE * 0.005));
            final double envelope = attack * Math.exp(-2.5 * progress);
            final short value = (short) (sample * envelope * VOLUME * 32767);
            pcm[i * 2] = (byte) (value & 0xFF);
            pcm[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return pcm;
    }

    private static void play(final byte[] pcm) {        PLAYER.execute(() -> {
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
