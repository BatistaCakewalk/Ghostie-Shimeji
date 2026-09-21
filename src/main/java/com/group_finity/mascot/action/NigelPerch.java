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

    private Area targetWindow;
    private int targetX;
    private int targetY;
    private int perchDuration;
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
        targetWindow = pickWindow();
        if (targetWindow == null) {
            throw new VariableException("No window to perch on");
        }
        targetX = targetWindow.getLeft() + targetWindow.getWidth() / 2;
        targetY = targetWindow.getTop();
        perchDuration = PERCH_DURATION_MIN + (int) (Math.random() * PERCH_DURATION_JITTER);
        ensureImagesLoaded();
    }

    private Area pickWindow() {
        try {
            final List<Area> candidates = getEnvironment().getGrabbableWindows();
            if (candidates.isEmpty()) {
                // Fallback to any visible window's area (active window).
                final Area active = getEnvironment().getActiveIE();
                if (active != null && active.isVisible() && active.getWidth() > 100) {
                    return active;
                }
                return null;
            }
            // Pick nearest window top to mascot.
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

    @Override
    public boolean hasNext() throws VariableException {
        if (!super.hasNext()) {
            return false;
        }
        return getTime() < perchDuration;
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        final Mascot mascot = getMascot();
        final int t = getTime();

        // Fly toward perch — catch-like leap, walk sprites while moving.
        if (t < 30) {
            final int prevX = mascot.getAnchor().x;
            mascot.getAnchor().x += (int) Math.round((targetX - mascot.getAnchor().x) * 0.25);
            mascot.getAnchor().y += (int) Math.round((targetY - mascot.getAnchor().y) * 0.25) - 2;
            final int dx = mascot.getAnchor().x - prevX;
            if (dx != 0) {
                mascot.setLookRight(dx > 0);
            }
            // Walk sprites while floating up.
            final int walkPhase = (t / 4) % 4;
            String walkKey = null;
            if (walkPhase == 2 && flyWalkBlink != null && ImagePairs.contains(flyWalkBlink)) {
                walkKey = flyWalkBlink;
            } else if (walkPhase % 2 == 0 && flyWalk1 != null && ImagePairs.contains(flyWalk1)) {
                walkKey = flyWalk1;
            } else if (flyWalk2 != null && ImagePairs.contains(flyWalk2)) {
                walkKey = flyWalk2;
            }
            if (walkKey != null) {
                mascot.setImage(ImagePairs.get(walkKey).getImage(mascot.isLookRight()));
            }
            return;
        } else {
            // Window moved — fall instead of teleporting with it.
            if (targetWindow != null) {
                try {
                    if (!targetWindow.isVisible()) {
                        throw new LostGroundException("Window gone");
                    }
                    final int newX = targetWindow.getLeft() + targetWindow.getWidth() / 2;
                    final int newY = targetWindow.getTop();
                    if (Math.abs(newX - targetX) > 3 || Math.abs(newY - targetY) > 3) {
                        throw new LostGroundException("Window moved");
                    }
                } catch (final RuntimeException e) {
                    throw new LostGroundException("Window moved");
                }
            }
            mascot.getAnchor().x = targetX;
            mascot.getAnchor().y = targetY;
        }

        // Face center of window.
        if (targetWindow != null) {
            final int centerX = targetWindow.getLeft() + targetWindow.getWidth() / 2;
            mascot.setLookRight(centerX > mascot.getAnchor().x);
        }

        // Clamp X, Y exact for floor check? Perch is not on floor, so keep Y as is.
        final int left = getEnvironment().getScreen().getLeft() + 1;
        final int right = getEnvironment().getScreen().getRight() - 1;
        mascot.getAnchor().x = Math.max(left, Math.min(right, mascot.getAnchor().x));

        if (perchKey != null && ImagePairs.contains(perchKey)) {
            mascot.setImage(ImagePairs.get(perchKey).getImage(mascot.isLookRight()));
        } else {
            getAnimation().apply(mascot, getTime());
        }

        // Dive chance from perch too.
        if (t > 30 && Math.random() < 0.008) {
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
        if (imagesLoaded) {
            return;
        }
        try {
            final double scaling = Main.getInstance().getSettings().scaling;
            final Filter filter = Main.getInstance().getSettings().filter;
            final double opacity = Main.getInstance().getSettings().opacity;
            final String imageSet = getMascot() != null && getMascot().getImageSet() != null
                    ? getMascot().getImageSet() : "NigelShimeji";
            perchKey = ImagePairs.load(Path.of(imageSet, "Perching.png"), null, 96, 195, scaling, filter, opacity);
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
