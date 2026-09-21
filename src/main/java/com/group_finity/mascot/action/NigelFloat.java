package com.group_finity.mascot.action;

import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.script.VariableException;
import com.group_finity.mascot.script.VariableMap;

import java.util.List;
import java.util.ResourceBundle;

/**
 * Nigel rises from ground-float height, drifts around higher up, then
 * comes back down on his own schedule. First slice of #21 — only rise
 * and drift for now, perching/dive/ceiling later.
 */
public class NigelFloat extends ActionBase {
    private static final int RISE_TICKS = 35;
    private static final int DRIFT_TICKS = 180;
    private static final int DESCEND_TICKS = 35;
    private static final int TOTAL_TICKS = RISE_TICKS + DRIFT_TICKS + DESCEND_TICKS;

    private static final int RISE_HEIGHT_MIN = 140;
    private static final int RISE_HEIGHT_MAX = 280;
    private static final int DRIFT_SPEED = 2;

    private int startY;
    private int targetRiseY;
    private int driftDir;

    public NigelFloat(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);
        startY = mascot.getAnchor().y;
        final int rise = RISE_HEIGHT_MIN + (int) (Math.random() * (RISE_HEIGHT_MAX - RISE_HEIGHT_MIN));
        // Stay well inside screen top.
        final int top = getEnvironment().getScreen().getTop() + 20;
        targetRiseY = Math.max(top, startY - rise);
        driftDir = Math.random() < 0.5 ? -1 : 1;
        mascot.setLookRight(driftDir > 0);
    }

    @Override
    public boolean hasNext() throws VariableException {
        if (!super.hasNext()) {
            return false;
        }
        return getTime() < TOTAL_TICKS;
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        final Mascot mascot = getMascot();
        final int t = getTime();

        if (t < RISE_TICKS) {
            // Smooth rise.
            final double progress = t / (double) RISE_TICKS;
            final int y = startY + (int) Math.round((targetRiseY - startY) * progress);
            mascot.getAnchor().y = y;
            // Slight horizontal nudge.
            mascot.getAnchor().x += driftDir;
        } else if (t < RISE_TICKS + DRIFT_TICKS) {
            // Drift with gentle bob.
            final int driftT = t - RISE_TICKS;
            mascot.getAnchor().x += driftDir * DRIFT_SPEED;
            // Bob 4px up/down.
            final double bob = Math.sin(driftT * 0.15) * 2;
            mascot.getAnchor().y = targetRiseY + (int) Math.round(bob);
            // Bounce off screen edges.
            final int left = getEnvironment().getScreen().getLeft() + 20;
            final int right = getEnvironment().getScreen().getRight() - 20;
            if (mascot.getAnchor().x <= left || mascot.getAnchor().x >= right) {
                driftDir = -driftDir;
                mascot.setLookRight(driftDir > 0);
            }
            // Occasionally flip drift direction.
            if (Math.random() < 0.01) {
                driftDir = -driftDir;
                mascot.setLookRight(driftDir > 0);
            }
        } else {
            // Descend back toward start.
            final int descendT = t - RISE_TICKS - DRIFT_TICKS;
            final double progress = descendT / (double) DESCEND_TICKS;
            // Current drift Y to startY.
            final int curY = mascot.getAnchor().y;
            final int targetY = startY;
            mascot.getAnchor().y = curY + (int) Math.round((targetY - curY) * 0.15) + 1;
            if (Math.abs(mascot.getAnchor().y - startY) < 3) {
                mascot.getAnchor().y = startY;
            }
            // Keep drifting a little while descending.
            mascot.getAnchor().x += driftDir;
        }

        // Clamp to screen.
        final int left = getEnvironment().getScreen().getLeft();
        final int right = getEnvironment().getScreen().getRight();
        final int top = getEnvironment().getScreen().getTop();
        final int bottom = getEnvironment().getScreen().getBottom();
        mascot.getAnchor().x = Math.max(left + 1, Math.min(right - 1, mascot.getAnchor().x));
        mascot.getAnchor().y = Math.max(top + 1, Math.min(bottom - 1, mascot.getAnchor().y));

        getAnimation().apply(mascot, getTime());
    }
}
