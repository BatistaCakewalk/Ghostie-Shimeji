package com.group_finity.mascot.action;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.script.VariableException;
import com.group_finity.mascot.script.VariableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ResourceBundle;

/**
 * Idle conga: an idle Nigel trails a moving Nigel in a line. Capped so
 * a 10-Nigel train doesn't eat the tick budget. Pure flavor, never
 * steals from catches or tele lifts due to low frequency.
 */
public class FollowLeader extends BorderedAction {
    private static final Logger log = LoggerFactory.getLogger(FollowLeader.class);

    private static final int FOLLOW_DISTANCE = 80;
    private static final int MAX_FOLLOWERS_PER_LEADER = 3;
    private static final int SEARCH_RADIUS = 500;
    private static final int SPEED = 4;

    private Mascot leader;
    private int offsetX;

    public FollowLeader(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);
        leader = pickLeader(mascot);
        if (leader == null) {
            throw new VariableException("No leader to follow");
        }
        // Behind the leader: left if leader looks right, right otherwise.
        offsetX = leader.isLookRight() ? -FOLLOW_DISTANCE : FOLLOW_DISTANCE;
        // Count followers for cap (best-effort, not synchronized).
    }

    private Mascot pickLeader(final Mascot self) {
        try {
            final List<Mascot> all = self.getManager() != null ? self.getManager().getMascots() : List.of();
            Mascot best = null;
            double bestDist = Double.MAX_VALUE;
            for (final Mascot candidate : all) {
                if (candidate == self || candidate.isPaused()) {
                    continue;
                }
                // Don't follow someone who's already following (avoid chain loops).
                // We can't inspect candidate behavior easily, so use distance + moving check.
                final double dx = candidate.getAnchor().x - self.getAnchor().x;
                final double dy = candidate.getAnchor().y - self.getAnchor().y;
                final double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist > SEARCH_RADIUS || dist < 30) {
                    continue;
                }
                // Prefer nearer leaders.
                if (dist < bestDist) {
                    // Rough cap: count how many others are already close to this candidate.
                    int followers = 0;
                    for (final Mascot m : all) {
                        if (m != candidate && m != self) {
                            final double ddx = m.getAnchor().x - candidate.getAnchor().x;
                            final double ddy = m.getAnchor().y - candidate.getAnchor().y;
                            if (Math.sqrt(ddx * ddx + ddy * ddy) < FOLLOW_DISTANCE * 1.5) {
                                followers++;
                            }
                        }
                    }
                    if (followers >= MAX_FOLLOWERS_PER_LEADER) {
                        continue;
                    }
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
        if (leader == null) {
            return false;
        }
        // Leader disposed or too far.
        try {
            if (leader.getManager() == null) {
                return false;
            }
            final double dx = leader.getAnchor().x - getMascot().getAnchor().x;
            final double dy = leader.getAnchor().y - getMascot().getAnchor().y;
            if (Math.sqrt(dx * dx + dy * dy) > SEARCH_RADIUS + 100) {
                return false;
            }
        } catch (final RuntimeException e) {
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
        if (leader == null) {
            throw new LostGroundException("Leader gone");
        }
        // Target: behind the leader.
        final int targetX = leader.getAnchor().x + offsetX;
        final int targetY = leader.getAnchor().y;

        final int curX = getMascot().getAnchor().x;
        final int curY = getMascot().getAnchor().y;

        // Turn to face direction of travel.
        if (targetX != curX) {
            getMascot().setLookRight(targetX > curX);
        }

        // Move toward target, capped speed.
        int dx = 0;
        if (Math.abs(targetX - curX) > 5) {
            dx = targetX > curX ? Math.min(SPEED, targetX - curX) : Math.max(-SPEED, targetX - curX);
        }
        int dy = 0;
        if (Math.abs(targetY - curY) > 2) {
            dy = targetY > curY ? 1 : -1;
        }
        getMascot().getAnchor().translate(dx, dy);

        // Keep on floor (let BorderedAction's border do its job, just don't drift off).
        getAnimation().apply(getMascot(), getTime());
    }
}
