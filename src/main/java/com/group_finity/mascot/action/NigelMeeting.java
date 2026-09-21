package com.group_finity.mascot.action;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.script.VariableException;
import com.group_finity.mascot.script.VariableMap;

import java.util.List;
import java.util.ResourceBundle;

/**
 * Two idle Nigels near each other stop and stare at each other for a while.
 * That's it. That's the feature.
 */
public class NigelMeeting extends BorderedAction {
    private static final int MEETING_RADIUS = 180;
    private static final int MEETING_DURATION_TICKS = 180;

    private Mascot partner;

    public NigelMeeting(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);
        partner = pickPartner(mascot);
        if (partner == null) {
            throw new VariableException("No partner to meet");
        }
        // Face each other.
        getMascot().setLookRight(partner.getAnchor().x > getMascot().getAnchor().x);
    }

    private Mascot pickPartner(final Mascot self) {
        try {
            final List<Mascot> all = self.getManager() != null ? self.getManager().getMascots() : List.of();
            Mascot best = null;
            double bestDist = Double.MAX_VALUE;
            for (final Mascot candidate : all) {
                if (candidate == self || candidate.isPaused()) {
                    continue;
                }
                final double dx = candidate.getAnchor().x - self.getAnchor().x;
                final double dy = candidate.getAnchor().y - self.getAnchor().y;
                final double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist > MEETING_RADIUS || dist < 20) {
                    continue;
                }
                if (dist < bestDist) {
                    best = candidate;
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
        if (getTime() >= MEETING_DURATION_TICKS) {
            return false;
        }
        if (partner == null || partner.getManager() == null) {
            return false;
        }
        return true;
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        super.tick();
        if (getBorder() != null && !getBorder().isOn(getMascot().getAnchor())) {
            throw new LostGroundException("Mascot is not on border");
        }
        if (partner != null) {
            try {
                getMascot().setLookRight(partner.getAnchor().x > getMascot().getAnchor().x);
            } catch (final RuntimeException ignored) {
            }
        }
        getAnimation().apply(getMascot(), getTime());
    }
}
