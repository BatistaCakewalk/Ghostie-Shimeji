package com.group_finity.mascot.action;

import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.script.VariableException;
import com.group_finity.mascot.script.VariableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Action for grabbing the user's cursor and refusing to let go.
 * <p>
 * Once the dodge check passes, the cursor is snapped to the mascot's grasp
 * point (anchor raised to hand height). Every tick the raw OS cursor
 * position is compared against that point: the distance the user managed
 * to drag it away is added to a struggle meter that drains the grasp HP,
 * while staying (nearly) still regenerates HP. The cursor is then yanked
 * back to the grasp point.
 * <p>
 * When HP hits 0 a {@link LostGroundException} is thrown, which makes the
 * engine play the {@code Fall} behavior, so Nigel drops the mouse and lands.
 */
public class GraspMouse extends ActionBase {
    private static final Logger log = LoggerFactory.getLogger(GraspMouse.class);

    private static final String PARAMETER_MAX_STRUGGLE = "MaxStruggle";
    private static final double DEFAULT_MAX_STRUGGLE = 1200.0;

    private static final String PARAMETER_REGEN = "Regen";
    private static final double DEFAULT_REGEN = 5.0;

    private static final String PARAMETER_FAST_THRESHOLD = "FastThreshold";
    private static final double DEFAULT_FAST_THRESHOLD = 10.0;

    private static final String PARAMETER_FAST_MULTIPLIER = "FastMultiplier";
    private static final double DEFAULT_FAST_MULTIPLIER = 0.5;

    private static final String PARAMETER_FURIOUS_THRESHOLD = "FuriousThreshold";
    private static final double DEFAULT_FURIOUS_THRESHOLD = 30.0;

    private static final String PARAMETER_FURIOUS_MULTIPLIER = "FuriousMultiplier";
    private static final double DEFAULT_FURIOUS_MULTIPLIER = 0.25;

    private static final String PARAMETER_MISS_THRESHOLD = "MissThreshold";
    private static final double DEFAULT_MISS_THRESHOLD = 100.0;

    private static final String PARAMETER_GRASP_OFFSET_Y = "GraspOffsetY";
    private static final double DEFAULT_GRASP_OFFSET_Y = -96.0;

    /**
     * Cursor movement at or below this distance (in pixels) counts as "not
     * fighting" and regenerates HP instead of draining it. Filters out
     * sensor noise so the grasp doesn't die on its own.
     */
    private static final double STRUGGLE_THRESHOLD = 3.0;

    private Robot robot;

    private double maxHp;

    private double hp;

    private int fatigue;

    private boolean proximityChecked;

    private boolean cursorHidden;

    private Cursor invisibleCursor;

    public GraspMouse(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);

        maxHp = getMaxStruggle();
        hp = maxHp;
        fatigue = 0;
        proximityChecked = false;
        cursorHidden = false;

        // Heal any leak from a previous grasp that was interrupted from the
        // outside (e.g. the user grabbed Nigel mid-grasp and swapped behaviors).
        forceRestoreCursor();

        try {
            robot = new Robot();
        } catch (final AWTException | SecurityException e) {
            log.warn("Could not create Robot for GraspMouse, skipping grasp", e);
            robot = null;
            return;
        }

        // Deliberately no cursor snap here: the proximity check on the first
        // tick must see the cursor where the user left it, otherwise every
        // dodge would look like a hit. The first yank happens in tickGrasp(),
        // only after the check passes.
    }

    @Override
    public boolean hasNext() throws VariableException {
        // Without a Robot there is nothing to grasp with, so let the
        // sequence fall through to whatever comes after us (usually Falling).
        final boolean more = super.hasNext() && robot != null;
        if (!more) {
            // Duration expired (or no Robot): never leave the cursor hidden.
            restoreCursor();
        }
        return more;
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        getMascot().setDragging(true);

        final Point anchor = getGraspPoint();

        // init() cannot throw LostGroundException, so the dodge check runs
        // here on the first tick instead. If Nigel landed too far from the
        // cursor, the user dodged him: abort straight into the Fall behavior.
        if (!proximityChecked) {
            proximityChecked = true;

            // Measure from the hands (grasp point), not the feet: the leap
            // already aimed the hands at the cursor, so a good landing reads
            // near-zero here and the threshold is genuine dodge room.
            final double cursorX = getEnvironment().getCursor().getX();
            final double cursorY = getEnvironment().getCursor().getY();
            final double landingDistance = anchor.distance(cursorX, cursorY);

            if (landingDistance > getMissThreshold()) {
                throw new LostGroundException("Missed the cursor");
            }

            // Grasp confirmed: swallow the cursor while we hold it.
            getMascot().setGrasping(true);
            hideCursor();
        }

        try {
            tickGrasp(anchor);
        } catch (final RuntimeException | VariableException | LostGroundException e) {
            // Whatever goes wrong (or the user breaking free), the cursor
            // must come back before we leave.
            restoreCursor();
            throw e;
        }
    }

    private void tickGrasp(final Point anchor) throws LostGroundException, VariableException {
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
            // Resting: cool down and recover.
            fatigue = Math.max(0, fatigue - 2);
            hp = Math.min(maxHp, hp + getRegen());
        } else {
            // Fighting: frantic shaking is dulled by the curve, and sustained
            // mashing tires itself out through fatigue.
            fatigue++;
            final double fatigueMultiplier = Math.max(0.2, 1.0 - fatigue / 100.0);
            hp -= applyStruggleCurve(struggle) * fatigueMultiplier;
        }

        // Yank it back.
        robot.mouseMove(anchor.x, anchor.y);

        // Look like we're holding on.
        getAnimation().apply(getMascot(), getTime());

        // The mascot's own hover logic resets its window cursor on every
        // mouse motion event, which our yanks trigger constantly, so
        // re-assert the hide every tick or it flickers back.
        reassertCursorHidden();

        if (hp <= 0) {
            throw new LostGroundException("The mouse broke free of Nigel's grasp");
        }
    }

    /**
     * Gets the point Nigel grabs with: his anchor (feet) raised by
     * {@code GraspOffsetY} so the cursor sits at his hands instead of
     * disappearing underneath him.
     */
    private Point getGraspPoint() throws VariableException {
        final Point point = getMascot().getAnchor().getLocation();
        point.translate(0, (int) getGraspOffsetY());
        return point;
    }

    private void hideCursor() {
        try {
            final Component window = getMascot().getWindowComponent();
            if (window != null) {
                // There is no predefined invisible cursor, so build a
                // fully transparent 1x1 one instead. Reuse it so the
                // per-tick re-assert below stays cheap.
                if (invisibleCursor == null) {
                    invisibleCursor = Toolkit.getDefaultToolkit().createCustomCursor(
                            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
                            new Point(0, 0), "grasped");
                }
                window.setCursor(invisibleCursor);
                cursorHidden = true;
            }
        } catch (final RuntimeException e) {
            log.warn("Could not hide cursor for GraspMouse", e);
        }
    }

    private void reassertCursorHidden() {
        if (!cursorHidden || invisibleCursor == null) {
            return;
        }
        try {
            final Component window = getMascot().getWindowComponent();
            if (window != null && window.getCursor() != invisibleCursor) {
                window.setCursor(invisibleCursor);
            }
        } catch (final RuntimeException e) {
            log.warn("Could not re-hide cursor for GraspMouse", e);
        }
    }

    private void forceRestoreCursor() {
        cursorHidden = true;
        restoreCursor();
    }

    private void restoreCursor() {
        getMascot().setGrasping(false);
        if (!cursorHidden) {
            return;
        }
        cursorHidden = false;
        try {
            final Component window = getMascot().getWindowComponent();
            if (window != null) {
                window.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            }
        } catch (final RuntimeException e) {
            log.warn("Could not restore cursor after GraspMouse", e);
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

    private double getGraspOffsetY() throws VariableException {
        return eval(getSchema().getString(PARAMETER_GRASP_OFFSET_Y), Number.class, DEFAULT_GRASP_OFFSET_Y).doubleValue();
    }

    /**
     * Dulls frantic mouse shaking: slow steady pulls count fully, fast
     * yanks are discounted, furious flailing barely registers.
     */
    private double applyStruggleCurve(final double distance) throws VariableException {
        if (distance < getFastThreshold()) {
            return distance;
        }
        if (distance < getFuriousThreshold()) {
            return distance * getFastMultiplier();
        }
        return distance * getFuriousMultiplier();
    }

    private double getFastThreshold() throws VariableException {
        return eval(getSchema().getString(PARAMETER_FAST_THRESHOLD), Number.class, DEFAULT_FAST_THRESHOLD).doubleValue();
    }

    private double getFastMultiplier() throws VariableException {
        return eval(getSchema().getString(PARAMETER_FAST_MULTIPLIER), Number.class, DEFAULT_FAST_MULTIPLIER).doubleValue();
    }

    private double getFuriousThreshold() throws VariableException {
        return eval(getSchema().getString(PARAMETER_FURIOUS_THRESHOLD), Number.class, DEFAULT_FURIOUS_THRESHOLD).doubleValue();
    }

    private double getFuriousMultiplier() throws VariableException {
        return eval(getSchema().getString(PARAMETER_FURIOUS_MULTIPLIER), Number.class, DEFAULT_FURIOUS_MULTIPLIER).doubleValue();
    }
}
