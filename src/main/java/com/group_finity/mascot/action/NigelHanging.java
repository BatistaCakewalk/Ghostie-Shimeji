package com.group_finity.mascot.action;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.image.Filter;
import com.group_finity.mascot.image.ImagePairs;
import com.group_finity.mascot.script.VariableException;
import com.group_finity.mascot.script.VariableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Ceiling nap: Nigel hangs from the top of the screen like a bat.
 * Idle 30s, tired 10s, tired2 10s, then blinks and snores.
 * Can fall off from time to time.
 */
public class NigelHanging extends BorderedAction {
    private static final Logger log = LoggerFactory.getLogger(NigelHanging.class);

    private static final int MOVE_TO_CEILING_TICKS = 60;
    private static final int IDLE_TICKS = 750;
    private static final int TIRED1_TICKS = 250;
    private static final int TIRED2_TICKS = 250;
    private static final int BLINK_TICKS = 4;
    private static final int BLINK_INTERVAL = 90;
    private static final int SNORE_INTERVAL = 30;

    private String idleKey;
    private String tired1Key;
    private String tired2Key;
    private String blinkKey;
    private String snore1Key;
    private String snore2Key;
    private String flyKey1;
    private String flyKey2;
    private String flyKeyBlink;
    private boolean imagesLoaded;

    private int targetX;
    private int targetY;
    private int startX;
    private int startY;

    public NigelHanging(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);
        startX = mascot.getAnchor().x;
        startY = mascot.getAnchor().y;
        final int screenLeft = getEnvironment().getScreen().getLeft();
        final int screenRight = getEnvironment().getScreen().getRight();
        final int screenTop = getEnvironment().getScreen().getTop();
        targetX = screenLeft + 80 + (int) (Math.random() * Math.max(1, screenRight - screenLeft - 160));
        // Anchor for hanging: image anchor 96,0 at top, so anchor at top.
        // Fly target is 200px higher (center diff) so visual top stays put when switching.
        targetY = screenTop + 2;
        // Keep fly target separate to avoid 200px jump when swapping anchors.
        // (Stored in targetY for hang; fly uses targetY+200.)
    }

    @Override
    public boolean hasNext() throws VariableException {
        if (!super.hasNext()) {
            return false;
        }
        // Hang for a while, then wake or fall.
        final int total = MOVE_TO_CEILING_TICKS + IDLE_TICKS + TIRED1_TICKS + TIRED2_TICKS + 1200;
        return getTime() < total;
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        final Mascot mascot = getMascot();
        final int t = getTime();
        ensureImagesLoaded();

        if (t < MOVE_TO_CEILING_TICKS) {
            // Fly up visibly to the ceiling — keep a floor-anchored sprite
            // until we reach the top, otherwise the hanging image (anchor
            // at top) sits with its feet at the floor and reads as off-screen.
            // Fly anchor is 200px below hang anchor so visual top stays put.
            final double p = t / (double) MOVE_TO_CEILING_TICKS;
            final double eased = 1 - Math.pow(1 - p, 3);
            final int flyTargetY = targetY + 200;
            mascot.getAnchor().x = startX + (int) Math.round((targetX - startX) * eased);
            mascot.getAnchor().y = startY + (int) Math.round((flyTargetY - startY) * eased);
            mascot.getAnchor().y += (int) Math.round(Math.sin(t * 0.4) * 1.5);
            // Walk sprites while floating up.
            final int walkPhase = (t / 4) % 4;
            String flyKey = null;
            if (walkPhase == 2 && flyKeyBlink != null && ImagePairs.contains(flyKeyBlink)) {
                flyKey = flyKeyBlink;
            } else if (walkPhase % 2 == 0 && flyKey1 != null && ImagePairs.contains(flyKey1)) {
                flyKey = flyKey1;
            } else if (flyKey2 != null && ImagePairs.contains(flyKey2)) {
                flyKey = flyKey2;
            }
            if (flyKey != null) {
                mascot.setImage(ImagePairs.get(flyKey).getImage(mascot.isLookRight()));
            }
            return;
        }
        if (t == MOVE_TO_CEILING_TICKS) {
            // Snap anchor to hang position so top doesn't jump 200px.
            mascot.getAnchor().x = targetX;
            mascot.getAnchor().y = targetY;
        }
        if (t < MOVE_TO_CEILING_TICKS + IDLE_TICKS) {
            mascot.getAnchor().x = targetX;
            mascot.getAnchor().y = targetY;
            if (t % BLINK_INTERVAL < BLINK_TICKS && blinkKey != null && ImagePairs.contains(blinkKey)) {
                mascot.setImage(ImagePairs.get(blinkKey).getImage(mascot.isLookRight()));
            } else if (idleKey != null && ImagePairs.contains(idleKey)) {
                mascot.setImage(ImagePairs.get(idleKey).getImage(mascot.isLookRight()));
            }
        } else if (t < MOVE_TO_CEILING_TICKS + IDLE_TICKS + TIRED1_TICKS) {
            mascot.getAnchor().x = targetX;
            mascot.getAnchor().y = targetY;
            if (t % BLINK_INTERVAL < BLINK_TICKS && blinkKey != null && ImagePairs.contains(blinkKey)) {
                mascot.setImage(ImagePairs.get(blinkKey).getImage(mascot.isLookRight()));
            } else if (tired1Key != null && ImagePairs.contains(tired1Key)) {
                mascot.setImage(ImagePairs.get(tired1Key).getImage(mascot.isLookRight()));
            }
        } else if (t < MOVE_TO_CEILING_TICKS + IDLE_TICKS + TIRED1_TICKS + TIRED2_TICKS) {
            mascot.getAnchor().x = targetX;
            mascot.getAnchor().y = targetY;
            if (t % BLINK_INTERVAL < BLINK_TICKS && blinkKey != null && ImagePairs.contains(blinkKey)) {
                mascot.setImage(ImagePairs.get(blinkKey).getImage(mascot.isLookRight()));
            } else if (tired2Key != null && ImagePairs.contains(tired2Key)) {
                mascot.setImage(ImagePairs.get(tired2Key).getImage(mascot.isLookRight()));
            }
        } else {
            // Snore loop with occasional blinks, stays hanging.
            mascot.getAnchor().x = targetX;
            mascot.getAnchor().y = targetY;
            final int snoreT = t - (MOVE_TO_CEILING_TICKS + IDLE_TICKS + TIRED1_TICKS + TIRED2_TICKS);
            // Blink every ~5 seconds.
            if (snoreT % BLINK_INTERVAL < BLINK_TICKS) {
                if (blinkKey != null && ImagePairs.contains(blinkKey)) {
                    mascot.setImage(ImagePairs.get(blinkKey).getImage(mascot.isLookRight()));
                }
            } else {
                final String snoreKey = (snoreT / SNORE_INTERVAL) % 2 == 0 ? snore1Key : snore2Key;
                if (snoreKey != null && ImagePairs.contains(snoreKey)) {
                    mascot.setImage(ImagePairs.get(snoreKey).getImage(mascot.isLookRight()));
                }
            }
            // Every 10 minutes, roll to fall: 0.5% base, +0.5% per interval.
            if (snoreT > 0 && snoreT % 15000 == 0) {
                final int intervals = snoreT / 15000;
                final double chance = 0.005 + intervals * 0.005;
                if (Math.random() < chance) {
                    throw new LostGroundException("Fell off ceiling");
                }
            }
        }

        // Keep facing center.
        final int centerX = (getEnvironment().getScreen().getLeft() + getEnvironment().getScreen().getRight()) / 2;
        mascot.setLookRight(targetX < centerX);
    }

    private void ensureImagesLoaded() {
        if (imagesLoaded) {
            return;
        }
        try {
            final double scaling = Main.getInstance().getSettings().scaling;
            final Filter filter = Main.getInstance().getSettings().filter;
            final double opacity = Main.getInstance().getSettings().opacity;
            final String imageSet = getMascot() != null && getMascot().getImageSet() != null
                    ? getMascot().getImageSet() : "NigelShimeji";
            // Hanging images anchor at top (96,0) so they hang down from ceiling.
            flyKey1 = ImagePairs.load(Path.of(imageSet, "walk_2.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(flyKey1, imageSet);
            flyKey2 = ImagePairs.load(Path.of(imageSet, "walk_3.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(flyKey2, imageSet);
            flyKeyBlink = ImagePairs.load(Path.of(imageSet, "walk_blink.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(flyKeyBlink, imageSet);
            idleKey = ImagePairs.load(Path.of(imageSet, "HangingIdle.png"), null, 96, 0, scaling, filter, opacity);
            ImagePairs.addUsage(idleKey, imageSet);
            tired1Key = ImagePairs.load(Path.of(imageSet, "HangingTired.png"), null, 96, 0, scaling, filter, opacity);
            ImagePairs.addUsage(tired1Key, imageSet);
            tired2Key = ImagePairs.load(Path.of(imageSet, "HangingTired2.png"), null, 96, 0, scaling, filter, opacity);
            ImagePairs.addUsage(tired2Key, imageSet);
            blinkKey = ImagePairs.load(Path.of(imageSet, "HangingBlinkAndClosedEyes.png"), null, 96, 0, scaling, filter, opacity);
            ImagePairs.addUsage(blinkKey, imageSet);
            snore1Key = ImagePairs.load(Path.of(imageSet, "SleepHangingSnore1.png"), null, 96, 0, scaling, filter, opacity);
            ImagePairs.addUsage(snore1Key, imageSet);
            snore2Key = ImagePairs.load(Path.of(imageSet, "SleepHangingSnore2.png"), null, 96, 0, scaling, filter, opacity);
            ImagePairs.addUsage(snore2Key, imageSet);
            imagesLoaded = true;
        } catch (final IOException | RuntimeException e) {
            log.warn("Failed to load hanging images", e);
        }
    }
}
