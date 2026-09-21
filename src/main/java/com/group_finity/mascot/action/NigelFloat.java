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
    private static final int DESCEND_TICKS = 35;

    private int driftTicks;
    private int totalTicks;

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
        // Short to medium float — its own stand/walk window, not a full patrol.
        driftTicks = 80 + (int) (Math.random() * 120);
        totalTicks = RISE_TICKS + driftTicks + DESCEND_TICKS;
    }

    @Override
    public boolean hasNext() throws VariableException {
        if (!super.hasNext()) {
            return false;
        }
        return getTime() < totalTicks;
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        final Mascot mascot = getMascot();
        final int t = getTime();

        if (t < RISE_TICKS) {
            // Ease-out rise — starts quick, slows as he reaches height.
            final double p = t / (double) RISE_TICKS;
            final double eased = 1 - Math.pow(1 - p, 3);
            mascot.getAnchor().y = startY + (int) Math.round((targetRiseY - startY) * eased);
            mascot.getAnchor().x += driftDir * (0.5 + p * 0.5);
            // Wobble slightly on the way up.
            mascot.getAnchor().y += (int) Math.round(Math.sin(t * 0.3) * 0.5);
        } else if (t < RISE_TICKS + driftTicks) {
            // Ghostly drift: figure-8 with varying speed, never robotic.
            final int driftT = t - RISE_TICKS;
            final double swayX = Math.sin(driftT * 0.03) * 1.2 + Math.sin(driftT * 0.07) * 0.6;
            final double bobY = Math.sin(driftT * 0.08) * 3.0 + Math.cos(driftT * 0.04) * 1.5;
            mascot.getAnchor().x += (int) Math.round(driftDir * (1.2 + swayX * 0.3));
            mascot.getAnchor().y = targetRiseY + (int) Math.round(bobY);
            // Soft bounce off edges.
            final int left = getEnvironment().getScreen().getLeft() + 20;
            final int right = getEnvironment().getScreen().getRight() - 20;
            if (mascot.getAnchor().x <= left || mascot.getAnchor().x >= right) {
                driftDir = -driftDir;
                mascot.setLookRight(driftDir > 0);
            }
            // Gentle direction change, not snap.
            if (Math.random() < 0.008) {
                driftDir = -driftDir;
                mascot.setLookRight(driftDir > 0);
            }
        } else {
            // Ease-in descent — lingers up top, then drops with weight.
            final int descendT = t - RISE_TICKS - driftTicks;
            final double p = descendT / (double) DESCEND_TICKS;
            final double eased = p * p * p;
            final int curY = mascot.getAnchor().y;
            // Blend from current drift height back to startY.
            mascot.getAnchor().y = curY + (int) Math.round((startY - curY) * (0.08 + eased * 0.12));
            if (descendT > DESCEND_TICKS - 5) {
                mascot.getAnchor().y = startY;
            }
            mascot.getAnchor().x += driftDir * (1.0 + Math.sin(descendT * 0.2) * 0.3);
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
