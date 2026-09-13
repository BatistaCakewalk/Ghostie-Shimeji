package com.group_finity.mascot.action;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.script.VariableException;
import com.group_finity.mascot.script.VariableMap;

import java.util.List;
import java.util.ResourceBundle;

/**
 * A leap that commits to a single aim point. Unlike {@link Jump}, which
 * re-reads its target every tick, this action freezes the target once at
 * init and flies there, so a moving cursor cannot drag the flight around
 * and turn every catch into a miss.
 */
public class CursorLeap extends ActionBase {
    private static final String PARAMETER_TARGETX = "TargetX";
    private static final int DEFAULT_TARGETX = 0;

    private static final String PARAMETER_TARGETY = "TargetY";
    private static final int DEFAULT_TARGETY = 0;

    private static final String PARAMETER_VELOCITY = "VelocityParam";
    private static final double DEFAULT_VELOCITY = 38.0;

    private static final String VARIABLE_VELOCITYX = "VelocityX";

    private static final String VARIABLE_VELOCITYY = "VelocityY";

    private double scaling;

    private int targetX;

    private int targetY;

    public CursorLeap(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);

        scaling = Main.getInstance().getSettings().scaling;

        // Freeze the aim point on the launch frame.
        targetX = getTargetX();
        targetY = getTargetY();
    }

    @Override
    public boolean hasNext() throws VariableException {
        if (!super.hasNext()) {
            return false;
        }

        final double distanceX = targetX - getMascot().getAnchor().x;
        final double distanceY = targetY - getMascot().getAnchor().y - Math.abs(distanceX) / 2;
        final double distance = Math.sqrt(distanceX * distanceX + distanceY * distanceY);

        return distance != 0;
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        if (getMascot().getAnchor().x != targetX) {
            getMascot().setLookRight(getMascot().getAnchor().x < targetX);
        }

        final double distanceX = targetX - getMascot().getAnchor().x;
        final double distanceY = targetY - getMascot().getAnchor().y - Math.abs(distanceX) / 2;

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
            getMascot().getAnchor().setLocation(targetX, targetY);
        }
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