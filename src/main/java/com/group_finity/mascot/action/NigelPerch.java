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

        // Fly toward perch quickly.
        if (t < 30) {
            final double p = t / 30.0;
            final double eased = 1 - Math.pow(1 - p, 3);
            mascot.getAnchor().x = (int) Math.round(mascot.getAnchor().x + (targetX - mascot.getAnchor().x) * eased * 0.3);
            mascot.getAnchor().y = (int) Math.round(mascot.getAnchor().y + (targetY - mascot.getAnchor().y) * eased * 0.3);
        } else {
            mascot.getAnchor().x = targetX;
            mascot.getAnchor().y = targetY;
            // Window may have moved — follow its top.
            if (targetWindow != null) {
                try {
                    if (targetWindow.isVisible()) {
                        targetX = targetWindow.getLeft() + targetWindow.getWidth() / 2;
                        targetY = targetWindow.getTop();
                        mascot.getAnchor().x = targetX;
                        mascot.getAnchor().y = targetY;
                    } else {
                        throw new LostGroundException("Window gone");
                    }
                } catch (final RuntimeException e) {
                    throw new LostGroundException("Window gone");
                }
            }
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
            perchKey = ImagePairs.load(Path.of(imageSet, "Perching.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(perchKey, imageSet);
            imagesLoaded = true;
        } catch (final IOException | RuntimeException e) {
            log.warn("Failed to load perching image", e);
        }
    }
}
