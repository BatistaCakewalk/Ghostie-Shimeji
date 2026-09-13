package com.group_finity.mascot.action;

import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.script.VariableException;
import com.group_finity.mascot.script.VariableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Action for grabbing the user's cursor and refusing to let go.
 * <p>
 * On init the cursor is snapped to the mascot's anchor. Every tick the raw
 * OS cursor position is compared against the anchor: the distance the user
 * managed to drag it away is added to a struggle meter that drains the
 * grasp HP, while staying (nearly) still regenerates HP. The cursor is then
 * yanked back to the anchor.
 * <p>
 * When HP hits 0 a {@link LostGroundException} is thrown, which makes the
 * engine play the {@code Fall} behavior, so Nigel drops the mouse and lands.
 */
public class GraspMouse extends ActionBase {
    private static final Logger log = LoggerFactory.getLogger(GraspMouse.class);

    private static final String PARAMETER_MAX_STRUGGLE = "MaxStruggle";
    private static final double DEFAULT_MAX_STRUGGLE = 300.0;

    private static final String PARAMETER_REGEN = "Regen";
    private static final double DEFAULT_REGEN = 2.0;

    private static final String PARAMETER_MISS_THRESHOLD = "MissThreshold";
    private static final double DEFAULT_MISS_THRESHOLD = 100.0;

    /**
     * Cursor movement at or below this distance (in pixels) counts as "not
     * fighting" and regenerates HP instead of draining it. Filters out
     * sensor noise so the grasp doesn't die on its own.
     */
    private static final double STRUGGLE_THRESHOLD = 3.0;

    private Robot robot;

    private double maxHp;

    private double hp;

    private boolean proximityChecked;

    public GraspMouse(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);

        maxHp = getMaxStruggle();
        hp = maxHp;
        proximityChecked = false;

        try {
            robot = new Robot();
        } catch (final AWTException | SecurityException e) {
            log.warn("Could not create Robot for GraspMouse, skipping grasp", e);
            robot = null;
            return;
        }

        // Snatch the cursor immediately.
        final Point anchor = getMascot().getAnchor().getLocation();
        robot.mouseMove(anchor.x, anchor.y);
    }

    @Override
    public boolean hasNext() throws VariableException {
        // Without a Robot there is nothing to grasp with, so let the
        // sequence fall through to whatever comes after us (usually Falling).
        return super.hasNext() && robot != null;
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        getMascot().setDragging(true);

        final Point anchor = getMascot().getAnchor().getLocation();

        // init() cannot throw LostGroundException, so the dodge check runs
        // here on the first tick instead. If Nigel landed too far from the
        // cursor, the user dodged him: abort straight into the Fall behavior.
        if (!proximityChecked) {
            proximityChecked = true;

            final double cursorX = getEnvironment().getCursor().getX();
            final double cursorY = getEnvironment().getCursor().getY();
            final double landingDistance = anchor.distance(cursorX, cursorY);

            if (landingDistance > getMissThreshold()) {
                throw new LostGroundException("Missed the cursor");
            }
        }

        Point raw = null;
        try {
            if (MouseInfo.getPointerInfo() != null) {
                raw = MouseInfo.getPointerInfo().getLocation();
            }
        } catch (final SecurityException e) {
            raw = null;
        }
        if (raw == null) {
            throw new LostGroundException("Lost track of the cursor");
        }

        // How far the user dragged the cursor away since we last snapped it back.
        final double struggle = anchor.distance(raw);
        if (struggle <= STRUGGLE_THRESHOLD) {
            hp = Math.min(maxHp, hp + getRegen());
        } else {
            hp -= struggle;
        }

        // Yank it back.
        robot.mouseMove(anchor.x, anchor.y);

        // Look like we're holding on.
        getAnimation().apply(getMascot(), getTime());

        if (hp <= 0) {
            throw new LostGroundException("The mouse broke free of Nigel's grasp");
        }
    }

    private double getMaxStruggle() throws VariableException {
        return eval(getSchema().getString(PARAMETER_MAX_STRUGGLE), Number.class, DEFAULT_MAX_STRUGGLE).doubleValue();
    }

    private double getRegen() throws VariableException {
        return eval(getSchema().getString(PARAMETER_REGEN), Number.class, DEFAULT_REGEN).doubleValue();
    }

    private double getMissThreshold() throws VariableException {
        return eval(getSchema().getString(PARAMETER_MISS_THRESHOLD), Number.class, DEFAULT_MISS_THRESHOLD).doubleValue();
    }
}
