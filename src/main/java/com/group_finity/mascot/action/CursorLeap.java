package com.group_finity.mascot.action;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.script.VariableException;
import com.group_finity.mascot.script.VariableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * A leap that commits to a single aim point. Unlike {@link Jump}, which
 * re-reads its target every tick, this action freezes the target once at
 * init and flies there, so a moving cursor cannot drag the flight around
 * and turn every catch into a miss.
 */
public class CursorLeap extends ActionBase {
    private static final Logger log = LoggerFactory.getLogger(CursorLeap.class);

    private static final String PARAMETER_TARGETX = "TargetX";
    private static final int DEFAULT_TARGETX = 0;

    private static final String PARAMETER_TARGETY = "TargetY";
    private static final int DEFAULT_TARGETY = 0;

    private static final String PARAMETER_VELOCITY = "VelocityParam";
    private static final double DEFAULT_VELOCITY = 38.0;

    private static final String VARIABLE_VELOCITYX = "VelocityX";

    private static final String VARIABLE_VELOCITYY = "VelocityY";

    private double scaling;

    private int frozenTargetX;

    private int frozenTargetY;

    /**
     * Cursor and mascot positions sampled once per tick during the flight,
     * kept to the most recent {@value #MAX_SAMPLES} samples so the closing
     * speed used for tackle detection covers the approach phase.
     */
    private final List<CursorSample> samples = new ArrayList<>();

    private static final int MAX_SAMPLES = 5;

    private static final class CursorSample {
        private final double cursorX;
        private final double cursorY;
        private final double anchorX;
        private final double anchorY;

        CursorSample(final double cursorX, final double cursorY, final double anchorX, final double anchorY) {
            this.cursorX = cursorX;
            this.cursorY = cursorY;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
        }
    }

    public CursorLeap(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);

        scaling = Main.getInstance().getSettings().scaling;

        // Freeze the aim point on the launch frame.
        frozenTargetX = getTargetX();
        frozenTargetY = getTargetY();
        samples.clear();
        // Stale approach readings must not leak from a previous leap.
        mascot.setApproachClosingSpeed(0.0);
        log.info("CursorLeap init: frozenTargetX={}, frozenTargetY={}, mascotAnchor={}", frozenTargetX, frozenTargetY, mascot.getAnchor());
    }

    @Override
    public boolean hasNext() throws VariableException {
        if (!super.hasNext()) {
            return false;
        }

        final double distanceX = frozenTargetX - getMascot().getAnchor().x;
        final double distanceY = frozenTargetY - getMascot().getAnchor().y - Math.abs(distanceX) / 2;
        final double distance = Math.sqrt(distanceX * distanceX + distanceY * distanceY);

        return distance != 0;
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        log.info("CursorLeap tick: anchor={}, target=({},{}), distance={}", getMascot().getAnchor(), frozenTargetX, frozenTargetY,
                getMascot().getAnchor().distance(frozenTargetX, frozenTargetY));

        sampleFlight();

        if (getMascot().getAnchor().x != frozenTargetX) {
            getMascot().setLookRight(getMascot().getAnchor().x < frozenTargetX);
        }

        final double distanceX = frozenTargetX - getMascot().getAnchor().x;
        final double distanceY = frozenTargetY - getMascot().getAnchor().y - Math.abs(distanceX) / 2;

        final double distance = Math.sqrt(distanceX * distanceX + distanceY * distanceY);

        final double velocity = getVelocity() * scaling;

        if (distance != 0) {
            final double velocityX = velocity * distanceX / distance;
            final double velocityY = velocity * distanceY / distance;

            putVariable(getSchema().getString(VARIABLE_VELOCITYX), velocityX);
            putVariable(getSchema().getString(VARIABLE_VELOCITYY), velocityY);

            getMascot().getAnchor().translate((int) Math.round(velocityX), (int) Math.round(velocityY));
            getAnimation().apply(getMascot(), getTime());
        }

        if (distance <= velocity) {
            getMascot().getAnchor().setLocation(frozenTargetX, frozenTargetY);
            // Flight over: publish the approach closing speed for GraspMouse.
            getMascot().setApproachClosingSpeed(measureApproachClosingSpeed());
        }
    }

    private void sampleFlight() {
        final Point cursor = currentCursor();
        if (cursor == null) {
            return;
        }
        final Point anchor = getMascot().getAnchor();
        samples.add(new CursorSample(cursor.x, cursor.y, anchor.x, anchor.y));
        while (samples.size() > MAX_SAMPLES) {
            samples.remove(0);
        }
    }

    private Point currentCursor() {
        try {
            final PointerInfo info = MouseInfo.getPointerInfo();
            return info == null ? null : info.getLocation();
        } catch (final SecurityException e) {
            return null;
        }
    }

    /**
     * Closing speed of the cursor toward Nigel during the approach: the
     * component of the cursor's own velocity along the direction from the
     * cursor to Nigel, averaged over any 3-tick window of the flight. Using
     * the cursor's motion rather than the shrinking gap means a stationary
     * cursor can never count as a tackle just because Nigel flew at it.
     */
    private double measureApproachClosingSpeed() {
        double best = 0.0;
        for (int i = 0; i + 2 < samples.size(); i++) {
            final CursorSample s0 = samples.get(i);
            final CursorSample s2 = samples.get(i + 2);
            final double velocityX = s2.cursorX - s0.cursorX;
            final double velocityY = s2.cursorY - s0.cursorY;
            double toNigelX = s2.anchorX - s2.cursorX;
            double toNigelY = s2.anchorY - s2.cursorY;
            final double toNigelLength = Math.sqrt(toNigelX * toNigelX + toNigelY * toNigelY);
            if (toNigelLength < 1.0) {
                continue;
            }
            toNigelX /= toNigelLength;
            toNigelY /= toNigelLength;
            // Per-tick closing component over the 2-tick window.
            final double closingSpeed = (velocityX * toNigelX + velocityY * toNigelY) / 2.0;
            best = Math.max(best, closingSpeed);
        }
        return best;
    }

    private int getTargetX() throws VariableException {
        return eval(getSchema().getString(PARAMETER_TARGETX), Number.class, DEFAULT_TARGETX).intValue();
    }

    private int getTargetY() throws VariableException {
        return eval(getSchema().getString(PARAMETER_TARGETY), Number.class, DEFAULT_TARGETY).intValue();
    }

    private double getVelocity() throws VariableException {
        return eval(getSchema().getString(PARAMETER_VELOCITY), Number.class, DEFAULT_VELOCITY).doubleValue();
    }
}