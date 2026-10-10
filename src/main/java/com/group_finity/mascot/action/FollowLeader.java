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
        if (leader != null) {
            // Spread followers behind the leader so they don't stack.
            int followers = 0;
            try {
                final List<Mascot> all = mascot.getManager() != null ? mascot.getManager().getMascots() : List.of();
                for (final Mascot m : all) {
                    if (m != leader && m != mascot) {
                        final double ddx = m.getAnchor().x - leader.getAnchor().x;
                        final double ddy = m.getAnchor().y - leader.getAnchor().y;
                        if (Math.sqrt(ddx * ddx + ddy * ddy) < FOLLOW_DISTANCE * 2.5) {
                            // Count those already trailing this leader (other followers).
                            try {
                                if (m.getBehavior() instanceof com.group_finity.mascot.behavior.UserBehavior ub) {
                                    if (ub.getName().equals("FollowLeader")) {
                                        followers++;
                                    }
                                }
                            } catch (final RuntimeException ignored) {
                            }
                        }
                    }
                }
            } catch (final RuntimeException ignored) {
            }
            final int trailGap = FOLLOW_DISTANCE + followers * 60;
            offsetX = leader.isLookRight() ? -trailGap : trailGap;
        }
        // If no leader, hasNext will be false and behavior ends gracefully — no error dialog.
    }

    private static final int MAX_FOLLOW_TICKS = 350;

    private Mascot pickLeader(final Mascot self) {
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
                if (dist > SEARCH_RADIUS || dist < 30) {
                    continue;
                }
                // Prefer moving leaders (Walk) so the line actually travels.
                boolean isMoving = false;
                try {
                    if (candidate.getBehavior() instanceof com.group_finity.mascot.behavior.UserBehavior ub) {
                        final String n = ub.getName().toLowerCase();
                        isMoving = n.contains("walk") || n.contains("follow");
                    }
                } catch (final RuntimeException ignored) {
                }
                // Score: nearer is better, moving is bonus.
                double score = dist - (isMoving ? 100 : 0);
                if (score < bestDist) {
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
                    bestDist = score;
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
        if (getTime() >= MAX_FOLLOW_TICKS) {
            return false;
        }
        // Leader disposed or too far — that's the break.
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
        // Target: behind the leader — update offset direction if leader turned.
        final int dir = leader.isLookRight() ? -1 : 1;
        final int absOffset = Math.abs(offsetX);
        offsetX = dir * absOffset;
        final int targetX = leader.getAnchor().x + offsetX;
        final int targetY = leader.getAnchor().y;

        final int curX = getMascot().getAnchor().x;
        final int curY = getMascot().getAnchor().y;

        // Keep leader walking while being followed — otherwise the line stalls
        // and the follower seizures left/right at the end of the leader's Walk.
        try {
            if (leader.getBehavior() instanceof com.group_finity.mascot.behavior.UserBehavior ub) {
                final String n = ub.getName().toLowerCase();
                final boolean leaderWalking = n.contains("walk") || n.contains("follow");
                if (!leaderWalking) {
                    final String imageSet = leader.getImageSet();
                    final com.group_finity.mascot.config.Configuration cfg =
                            Main.getInstance().getConfiguration(imageSet);
                    leader.setBehavior(cfg.buildBehavior("Walk", leader));
                }
            }
        } catch (final RuntimeException | com.group_finity.mascot.config.BehaviorInstantiationException
                | com.group_finity.mascot.behavior.BehaviorExecutionException ignored) {
        }

        // Move toward target, capped speed — only turn when actually moving.
        int dx = 0;
        if (Math.abs(targetX - curX) > 5) {
            dx = targetX > curX ? Math.min(SPEED, targetX - curX) : Math.max(-SPEED, targetX - curX);
        }
        if (dx != 0) {
            getMascot().setLookRight(dx > 0);
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
