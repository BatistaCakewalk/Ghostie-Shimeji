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
 * Nigel's idle stand: the base hover-bob animation, plus a random
 * glance-back or glance-forward every so often. Pure flavor, no gameplay.
 */
public class NigelStay extends Stay {
    private static final Logger log = LoggerFactory.getLogger(NigelStay.class);

    private static final int GLANCE_EVERY_MIN = 400;
    private static final int GLANCE_EVERY_JITTER = 500;
    private static final int GLANCE_SHOW_TICKS = 40;
    private static final int BLINK_SHOW_TICKS = 4;

    private String lookBackKey;
    private String lookForwardKey;
    private String blinkKey;
    private boolean imagesLoaded;
    private int glanceCooldown = GLANCE_EVERY_MIN;
    private int glanceRemaining;
    private int blinkRemaining;
    private String glanceKey;
    private int stareLift = 8;

    public NigelStay(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);
        // First glance comes soon so short idle stretches still show one.
        glanceCooldown = 80 + (int) (Math.random() * 120);
        glanceRemaining = 0;
        blinkRemaining = 0;
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        super.tick();
        ensureImagesLoaded();
        if (glanceRemaining > 0) {
            glanceRemaining--;
            applyGlance(glanceKey, (getTime() / 20) % 2 == 0);
        } else if (blinkRemaining > 0) {
            blinkRemaining--;
            applyGlance(blinkKey, false);
            if (blinkRemaining == 0) {
                glanceRemaining = GLANCE_SHOW_TICKS;
            }
        } else if (--glanceCooldown <= 0) {
            glanceKey = Math.random() < 0.5 ? lookBackKey : lookForwardKey;
            glanceCooldown = GLANCE_EVERY_MIN + (int) (Math.random() * GLANCE_EVERY_JITTER);
            if (blinkKey != null && ImagePairs.contains(blinkKey)) {
                blinkRemaining = BLINK_SHOW_TICKS;
            } else {
                glanceRemaining = GLANCE_SHOW_TICKS;
            }
            applyGlance(blinkRemaining > 0 ? blinkKey : glanceKey, false);
        }
    }

    private void applyGlance(final String key, final boolean up) {
        if (key == null || !ImagePairs.contains(key)) {
            return;
        }
        // Float lives in the frames (base/raised twins): the anchor is
        // never touched, so Stay's border check always passes.
        String show = key;
        if (up) {
            final String imageSet = getMascot() != null && getMascot().getImageSet() != null
                    ? getMascot().getImageSet() : "NigelShimeji";
            show = ImagePairs.raisedVariant(key, stareLift, imageSet);
        }
        getMascot().setImage(ImagePairs.get(show).getImage(getMascot().isLookRight()));
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
            // 192x192 with anchor 96,200 matching the stand pose
            lookBackKey = ImagePairs.load(Path.of(imageSet, "standlookback.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(lookBackKey, imageSet);
            lookForwardKey = ImagePairs.load(Path.of(imageSet, "standlookforward.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(lookForwardKey, imageSet);
            try {
                blinkKey = ImagePairs.load(Path.of(imageSet, "stand2.png"), null, 96, 200, scaling, filter, opacity);
                ImagePairs.addUsage(blinkKey, imageSet);
            } catch (final IOException | RuntimeException ignored) {
                blinkKey = null;
            }
            stareLift = Math.max(2, (int) Math.round(8 * scaling));
            imagesLoaded = true;
        } catch (final IOException | RuntimeException e) {
            log.warn("Failed to load idle glance images for NigelStay", e);
        }
    }
}
