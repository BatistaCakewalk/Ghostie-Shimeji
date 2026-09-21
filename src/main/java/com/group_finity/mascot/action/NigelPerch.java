package com.group_finity.mascot.action;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.environment.Area;
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
 * Perching: flies to the top of a window title bar and watches from above.
 * Last slice of #21.
 */
public class NigelPerch extends ActionBase {
    private static final Logger log = LoggerFactory.getLogger(NigelPerch.class);

    private static final int PERCH_DURATION_MIN = 120;
    private static final int PERCH_DURATION_JITTER = 180;

    // Fly speed in px/tick. ~8px feels similar to CatchMouse approach speed.
    private static final double FLY_SPEED = 8.0;

    private int targetX;
    private int targetY;
    private int startX;
    private int startY;
    private int flyTicks;       // ticks to reach the perch
    private int perchDuration;  // total ticks (fly + perch)
    // Remembered window size so we can re-find it each tick via getGrabbableWindows()
    private int windowWidth;
    private int windowHeight;
    private int windowLostCount; // consecutive tracking misses before LostGround

    private String perchKey;
    private String flyWalk1;
    private String flyWalk2;
    private String flyWalkBlink;
    private boolean imagesLoaded;

    public NigelPerch(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);
        startX = mascot.getAnchor().x;
        startY = mascot.getAnchor().y;

        final Area win = pickWindow();
        if (win != null) {
            targetX = win.getLeft() + win.getWidth() / 2;
            targetY = win.getTop();
            windowWidth  = win.getWidth();
            windowHeight = win.getHeight();
        }

        final double dist = Math.sqrt(Math.pow(targetX - startX, 2) + Math.pow(targetY - startY, 2));
        flyTicks = Math.max(20, (int) Math.ceil(dist / FLY_SPEED));

        final int perchTime = PERCH_DURATION_MIN + (int) (Math.random() * PERCH_DURATION_JITTER);
        perchDuration = flyTicks + perchTime;

        ensureImagesLoaded();
    }

    private Area pickWindow() {
        try {
            final List<Area> candidates = getEnvironment().getGrabbableWindows();
            if (candidates.isEmpty()) {
                final Area active = getEnvironment().getActiveIE();
                if (active != null && active.isVisible() && active.getWidth() > 100) {
                    return active;
                }
                return null;
            }
            Area best = null;
            double bestDist = Double.MAX_VALUE;
            for (final Area area : candidates) {
                final int wx = area.getLeft() + area.getWidth() / 2;
                final int wy = area.getTop();
                final double dx = wx - getMascot().getAnchor().x;
                final double dy = wy - getMascot().getAnchor().y;
                final double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < bestDist) {
                    best = area;
                    bestDist = dist;
                }
            }
            return best;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /** Re-query grabbable windows and find the one we're perching on by size + proximity. */
    private Area findCurrentWindow() {
        try {
            Area best = null;
            double bestDist = 300; // max pixels the window centre can drift before we give up
            for (final Area w : getEnvironment().getGrabbableWindows()) {
                if (Math.abs(w.getWidth()  - windowWidth)  > 6) continue;
                if (Math.abs(w.getHeight() - windowHeight) > 6) continue;
                final int cx = w.getLeft() + w.getWidth() / 2;
                final int cy = w.getTop();
                final double dist = Math.sqrt(Math.pow(cx - targetX, 2) + Math.pow(cy - targetY, 2));
                if (dist < bestDist) {
                    best = w;
                    bestDist = dist;
                }
            }
            return best;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean hasNext() throws VariableException {
        if (!super.hasNext()) return false;
        if (windowWidth == 0) return false; // no window was picked
        return getTime() < perchDuration;
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        final Mascot mascot = getMascot();
        final int t = getTime();

        // ── Fly phase ──────────────────────────────────────────────────────
        if (t < flyTicks) {
            // Linear constant-speed movement (feels like CatchMouse approach).
            final double frac = (double)(t + 1) / flyTicks;
            final int prevX = mascot.getAnchor().x;
            mascot.getAnchor().x = startX + (int) Math.round((targetX - startX) * frac);
            mascot.getAnchor().y = startY + (int) Math.round((targetY - startY) * frac);
            // Small sine bob so it doesn't look like a straight laser line.
            mascot.getAnchor().y += (int) Math.round(Math.sin(t * 0.35) * 2.0);

            final int dx = mascot.getAnchor().x - prevX;
            if (dx != 0) mascot.setLookRight(dx > 0);

            final int walkPhase = (t / 5) % 4;
            String walkKey = null;
            if (walkPhase == 2 && flyWalkBlink != null && ImagePairs.contains(flyWalkBlink)) {
                walkKey = flyWalkBlink;
            } else if (walkPhase % 2 == 0 && flyWalk1 != null && ImagePairs.contains(flyWalk1)) {
                walkKey = flyWalk1;
            } else if (flyWalk2 != null && ImagePairs.contains(flyWalk2)) {
                walkKey = flyWalk2;
            }
            if (walkKey != null) mascot.setImage(ImagePairs.get(walkKey).getImage(mascot.isLookRight()));
            return;
        }

        // ── Perch phase ────────────────────────────────────────────────────

        // Every 5 ticks, re-find the window so we follow it if it moves.
        if ((t - flyTicks) % 5 == 0) {
            final Area current = findCurrentWindow();
            if (current != null) {
                windowLostCount = 0;
                targetX = current.getLeft() + current.getWidth() / 2;
                targetY = current.getTop();
            } else {
                windowLostCount++;
                if (windowLostCount > 6) { // ~30 ticks / 1.2 s of no window → fall
                    throw new LostGroundException("Window gone");
                }
            }
        }

        mascot.getAnchor().x = targetX;
        mascot.getAnchor().y = targetY; // anchor Y=189 in image → feet exactly on title bar

        final int left  = getEnvironment().getScreen().getLeft() + 1;
        final int right = getEnvironment().getScreen().getRight() - 1;
        mascot.getAnchor().x = Math.max(left, Math.min(right, mascot.getAnchor().x));

        // Face window centre.
        mascot.setLookRight(targetX < (getEnvironment().getScreen().getLeft()
                + getEnvironment().getScreen().getRight()) / 2);

        if (perchKey != null && ImagePairs.contains(perchKey)) {
            mascot.setImage(ImagePairs.get(perchKey).getImage(mascot.isLookRight()));
        } else {
            getAnimation().apply(mascot, getTime());
        }

        // Rare dive toward cursor (~0.04 % / tick ≈ once per ~70 s if cursor in range).
        final int perchT = t - flyTicks;
        if (perchT > 20 && perchT % 10 == 0 && Math.random() < 0.004) {
            try {
                final com.group_finity.mascot.environment.Location cursor = getEnvironment().getCursor();
                final int dx = cursor.getX() - mascot.getAnchor().x;
                final int dy = cursor.getY() - mascot.getAnchor().y;
                if (dy > 30 && dy < 600 && Math.abs(dx) < 500
                        && !mascot.isMouseOwnedByOther()
                        && !getEnvironment().isFullscreen()
                        && !getEnvironment().isMouseLocked()) {
                    final String imageSet = mascot.getImageSet();
                    final com.group_finity.mascot.config.Configuration cfg =
                            Main.getInstance().getConfiguration(imageSet);
                    mascot.setBehavior(cfg.buildBehavior("CatchMouse", mascot));
                    return;
                }
            } catch (final Exception ignored) {
            }
        }
    }

    private void ensureImagesLoaded() {
        if (imagesLoaded) return;
        try {
            final double scaling = Main.getInstance().getSettings().scaling;
            final Filter filter  = Main.getInstance().getSettings().filter;
            final double opacity = Main.getInstance().getSettings().opacity;
            final String imageSet = getMascot() != null && getMascot().getImageSet() != null
                    ? getMascot().getImageSet() : "NigelShimeji";
            // Anchor Y=189: last non-transparent row of Perching.png (192×192),
            // so feet land exactly on window.getTop().
            perchKey = ImagePairs.load(Path.of(imageSet, "Perching.png"), null, 96, 189, scaling, filter, opacity);
            ImagePairs.addUsage(perchKey, imageSet);
            flyWalk1 = ImagePairs.load(Path.of(imageSet, "walk_2.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(flyWalk1, imageSet);
            flyWalk2 = ImagePairs.load(Path.of(imageSet, "walk_3.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(flyWalk2, imageSet);
            flyWalkBlink = ImagePairs.load(Path.of(imageSet, "walk_blink.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(flyWalkBlink, imageSet);
            imagesLoaded = true;
        } catch (final IOException | RuntimeException e) {
            log.warn("Failed to load perching image", e);
        }
    }
}
