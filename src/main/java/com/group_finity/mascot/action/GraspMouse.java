package com.group_finity.mascot.action;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.environment.Area;
import com.group_finity.mascot.image.Filter;
import com.group_finity.mascot.image.ImagePairs;
import com.group_finity.mascot.script.VariableException;
import com.group_finity.mascot.script.VariableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.AWTException;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
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
    private static final double DEFAULT_MISS_THRESHOLD = 150.0;

    private static final String PARAMETER_GRASP_OFFSET_Y = "GraspOffsetY";
    private static final double DEFAULT_GRASP_OFFSET_Y = -96.0;

    private static final String PARAMETER_TACKLE_STAGGER_TICKS = "TackleStaggerTicks";
    private static final int DEFAULT_TACKLE_STAGGER_TICKS = 10;

    private static final String PARAMETER_TACKLE_KNOCKBACK = "TackleKnockback";
    private static final double DEFAULT_TACKLE_KNOCKBACK = 25.0;

    private static final String PARAMETER_CUDDLE_IDLE_TICKS = "CuddleIdleTicks";
    private static final int DEFAULT_CUDDLE_IDLE_TICKS = 600;

    private static final String PARAMETER_CUDDLE_DURATION_TICKS = "CuddleDurationTicks";
    private static final int DEFAULT_CUDDLE_DURATION_TICKS = 18000;

    private static final String PARAMETER_CUDDLE_SHAKE_THRESHOLD = "CuddleShakeThreshold";
    private static final double DEFAULT_CUDDLE_SHAKE_THRESHOLD = 50.0;

    private static final String PARAMETER_CUDDLE_SHAKE_COUNT = "CuddleShakeCount";
    private static final int DEFAULT_CUDDLE_SHAKE_COUNT = 5;

    private static final int CUDDLE_SHAKE_WINDOW = 30;
    private static final int CUDDLE_ANIM_INTERVAL = 30;

    /**
     * Closing speed (in px/tick) above which a catch counts as a head-on
     * tackle instead of a normal grab.
     */
    private static final double TACKLE_CLOSING_SPEED = 15.0;

    /**
     * How many timestamped cursor positions the history buffer holds. The
     * closing speed used for tackle detection spans the whole buffer, so a
     * wider buffer needs a longer free-measurement window before the grasp
     * locks in.
     */
    private static final int CURSOR_HISTORY_SIZE = 4;

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

    private double driftTargetX;

    private double driftTargetY;

    private int driftAppliedX;

    private int driftAppliedY;

    private boolean proximityChecked;

    private boolean cursorHidden;

    private Cursor invisibleCursor;

    private CursorSample[] cursorHistory;

    private int cursorHistoryIndex;

    private int cursorHistoryFill;

    private boolean tackle;

    private int staggerRemaining;

    private int contactRemaining;

    // Cuddle mode state
    private int idleTicks;
    private boolean cuddleMode;
    private int cuddleTicks;
    private int shakeCount;
    private int shakeWindowRemaining;
    private int postCuddleGrace;
    private String cuddleImageKey1;
    private String cuddleImageKey2;
    private boolean cuddleImagesLoaded;

    private static final class CursorSample {
        private final int x;
        private final int y;
        private final long nanoTime;

        CursorSample(final int x, final int y, final long nanoTime) {
            this.x = x;
            this.y = y;
            this.nanoTime = nanoTime;
        }

        double distance(final int px, final int py) {
            final double dx = x - px;
            final double dy = y - py;
            return Math.sqrt(dx * dx + dy * dy);
        }
    }

    public GraspMouse(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);

        maxHp = getMaxStruggle();
        hp = maxHp;
        fatigue = 0;
        driftTargetX = 0.0;
        driftTargetY = 0.0;
        driftAppliedX = 0;
        driftAppliedY = 0;
        proximityChecked = false;
        cursorHidden = false;
        cursorHistory = new CursorSample[CURSOR_HISTORY_SIZE];
        cursorHistoryIndex = 0;
        cursorHistoryFill = 0;
        final Point seed = currentCursor();
        if (seed != null) {
            recordCursor(seed);
        }
        tackle = false;
        staggerRemaining = 0;
        // Free-measurement window before the hold locks in: the cursor stays
        // unheld while the history buffer fills, so tackle detection gets a
        // genuine multi-tick velocity instead of a same-frame delta.
        contactRemaining = Math.max(1, CURSOR_HISTORY_SIZE - 1);
        idleTicks = 0;
        cuddleMode = false;
        cuddleTicks = 0;
        shakeCount = 0;
        shakeWindowRemaining = 0;
        postCuddleGrace = 0;
        // cuddle image keys persist across grasps to avoid reloading

        // Heal any leak from a previous grasp that was interrupted from the
        // outside (e.g. the user grabbed Nigel mid-grasp and swapped behaviors).
        // Preserve our own CursorLeap claim — forceRestore would otherwise release it.
        final Mascot ownerBefore = Mascot.getMouseOwner();
        final boolean wasOwner = ownerBefore == mascot;
        forceRestoreCursor();
        if (wasOwner) {
            Mascot.tryAcquireMouse(mascot);
        }

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

        try {
            // init() cannot throw LostGroundException, so the dodge check runs
            // here on the first tick instead. If Nigel landed too far from the
            // cursor, the user dodged him: abort straight into the Fall behavior.
            if (!proximityChecked) {
                proximityChecked = true;

                // Measure from the hands (grasp point), not the feet: the leap
                // already aimed the hands at the cursor, so a good landing reads
                // near-zero here and the threshold is genuine dodge room.
                final Point hands = getGraspPoint();
                final double cursorX = getEnvironment().getCursor().getX();
                final double cursorY = getEnvironment().getCursor().getY();
                final double landingDistance = hands.distance(cursorX, cursorY);

                final double baseThreshold = getMissThreshold();
                final double approachForProximity = getMascot().getApproachClosingSpeed();
                final double effectiveThreshold = approachForProximity > TACKLE_CLOSING_SPEED ? baseThreshold * 3 : baseThreshold;
                log.info("Proximity check: landingDistance={}, threshold={}, approachClosingSpeed={}", landingDistance, effectiveThreshold, approachForProximity);

                if (landingDistance > effectiveThreshold) {
                    Mascot.releaseMouse(getMascot());
                    throw new LostGroundException("Missed the cursor");
                }

                // Only one Nigel may hold the mouse at a time.
                if (!Mascot.tryAcquireMouse(getMascot())) {
                    final Mascot owner = Mascot.getMouseOwner();
                    log.info("Grasp blocked: mouse already owned by {}", owner);
                    throw new LostGroundException("Mouse already grabbed by " + owner);
                }

                // From here Nigel is untouchable, even mid-stagger, but the cursor stays
                // visible and free until the hold actually locks in.
                getMascot().setGrasping(true);

                // Seed the history with the landing tick. The contact window
                // below keeps sampling so the closing speed is measured over
                // several ticks, not just the instant of impact.
                final Point cursor = currentCursor();
                if (cursor != null) {
                    recordCursor(cursor);
                }
            }

            if (contactRemaining > 0) {
                tickContact();
            } else if (tackle && staggerRemaining > 0) {
                tickStagger();
            } else {
                tickGrasp();
            }
        } catch (final LostGroundException | VariableException | RuntimeException e) {
            // Whatever goes wrong (or the user breaking free), the cursor
            // must come back before we leave.
            restoreCursor();
            throw e;
        } catch (final Throwable t) {
            // Even Errors must not leak a hidden cursor. Rethrow untouched.
            restoreCursor();
            if (t instanceof Error) {
                throw (Error) t;
            }
            throw new RuntimeException(t);
        }
    }

    private void tickGrasp() throws LostGroundException, VariableException {
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
        final double struggle = getGraspPoint().distance(raw);
        final boolean fighting = struggle > STRUGGLE_THRESHOLD;

        // --- Cuddle mode handling ---
        boolean justBrokeCuddle = false;
        if (cuddleMode) {
            // Cuddle duration check
            cuddleTicks++;
            if (cuddleTicks >= getCuddleDurationTicks()) {
                throw new LostGroundException("Nigel reluctantly let go");
            }

            // Shake window countdown
            if (shakeWindowRemaining > 0) {
                shakeWindowRemaining--;
                if (shakeWindowRemaining == 0) {
                    shakeCount = 0;
                }
            }

            // Violent shake detector
            if (struggle > getCuddleShakeThreshold()) {
                if (shakeWindowRemaining == 0) {
                    shakeWindowRemaining = CUDDLE_SHAKE_WINDOW;
                    shakeCount = 1;
                } else {
                    shakeCount++;
                }
                if (shakeCount >= getCuddleShakeCount()) {
                    log.info("Cuddle broken by shaking: {} shakes in window", shakeCount);
                    cuddleMode = false;
                    cuddleTicks = 0;
                    shakeCount = 0;
                    shakeWindowRemaining = 0;
                    // Shorter re-enter after a brief struggle: ~1-1.5s vs 10s
                    try {
                        idleTicks = getCuddleIdleTicks() - 60;
                    } catch (final VariableException e) {
                        idleTicks = DEFAULT_CUDDLE_IDLE_TICKS - 60;
                    }
                    postCuddleGrace = 180;
                    log.info("Cuddle break: keeping idle progress at {} (need ~60 more ticks ~1s), grace {}", idleTicks, postCuddleGrace);
                    justBrokeCuddle = true;
                    // Fall through to normal grasp handling for this tick
                }
            }

            if (cuddleMode) {
                // HP regeneration suspended in cuddle mode
                if (fighting) {
                    fatigue++;
                    final double fatigueMultiplier = Math.max(0.2, 1.0 - fatigue / 100.0);
                    hp -= applyStruggleCurve(struggle) * fatigueMultiplier;
                } else {
                    fatigue = Math.max(0, fatigue - 2);
                }

                // Calm drift regardless of movement
                wrestle(0.0, false);
                clampAnchorToScreen();

                // Cursor stays inside him at the new position.
                final Point hands = getGraspPoint();
                robot.mouseMove(hands.x, hands.y);

                applyCuddleAnimation();
                reassertCursorHidden();

                if (hp <= 0) {
                    throw new LostGroundException("The mouse broke free of Nigel's grasp");
                }
                return;
            }
            // If cuddle was broken this tick, continue to normal handling below
        }

        // --- Normal mode idle tracking ---
        if (postCuddleGrace > 0) {
            postCuddleGrace--;
            if (!fighting) {
                idleTicks++;
                if (idleTicks >= getCuddleIdleTicks()) {
                    log.info("Entering cuddle mode after {} idle ticks (quick re-enter)", idleTicks);
                    cuddleMode = true;
                    cuddleTicks = 0;
                    shakeCount = 0;
                    shakeWindowRemaining = 0;
                    postCuddleGrace = 0;
                }
            }
            // fighting during grace: keep idleTicks, don't reset
        } else if (justBrokeCuddle) {
            // Keep half-idle progress for this tick so a brief struggle doesn't reset to full 10s
        } else if (!fighting) {
            idleTicks++;
            if (idleTicks >= getCuddleIdleTicks()) {
                log.info("Entering cuddle mode after {} idle ticks", idleTicks);
                cuddleMode = true;
                cuddleTicks = 0;
                shakeCount = 0;
                shakeWindowRemaining = 0;
            }
        } else {
            idleTicks = 0;
        }

        if (!fighting) {
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

        // Wrestle: drift or thrash Nigel around, bounded so the anchor can
        // never wander off on its own.
        wrestle(struggle, fighting);
        clampAnchorToScreen();

        // Cursor stays inside him at the new position.
        final Point hands = getGraspPoint();
        robot.mouseMove(hands.x, hands.y);

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
     * The brief dazed pause that follows a head-on tackle. The cursor is NOT
     * held during this window: the user can still bolt, and there is real
     * dodge room until the hold snaps in on the last stagger tick.
     */
    /**
     * The contact window right after landing. The cursor is NOT held during
     * it: every tick it is sampled into the history buffer, so once the window
     * closes the closing speed can be measured over multiple ticks rather
     * than a same-frame delta. If the user bolts during the window, that's a
     * genuine escape.
     */
    private void tickContact() throws LostGroundException, VariableException {
        final Point cursor = currentCursor();
        if (cursor == null) {
            throw new LostGroundException("Lost track of the cursor");
        }
        recordCursor(cursor);

        final Point hands = getGraspPoint();
        if (hands.distance(cursor.x, cursor.y) > getMissThreshold()) {
            throw new LostGroundException("Escaped during the contact window");
        }

        // Nigel processes the hit while the user still has the cursor.
        wrestle(0.0, false);
        clampAnchorToScreen();
        getAnimation().apply(getMascot(), getTime());

        if (--contactRemaining > 0) {
            return;
        }

        // Window over: decide how this catch plays out from the buffered
        // closing speed.
        if (isTackle()) {
            tackle = true;
            applyKnockback();
            staggerRemaining = Math.max(1, getTackleStaggerTicks());
            resetDrift();
        } else {
            hideCursor();
        }
    }

    /**
     * The brief dazed pause that follows a head-on tackle. The cursor is NOT
     * held during this window: the user can still bolt, and there is real
     * dodge room until the hold snaps in on the last stagger tick.
     */
    private void tickStagger() throws LostGroundException, VariableException {
        log.info("Tackle stagger tick {}", staggerRemaining);

        final Point cursor = currentCursor();
        if (cursor == null) {
            throw new LostGroundException("Lost track of the cursor");
        }

        final Point hands = getGraspPoint();
        // Tackle stagger is forgiving: cursor has high momentum from the
        // collision and will naturally coast past Nigel. Require active
        // flight, not just overshoot, to escape.
        final double staggerEscapeThreshold = getMissThreshold() * 3;
        if (hands.distance(cursor.x, cursor.y) > staggerEscapeThreshold) {
            throw new LostGroundException("Escaped during the stagger");
        }

        // Dazed wobble, then the grasp locks in.
        wrestle(0.0, false);
        clampAnchorToScreen();
        getAnimation().apply(getMascot(), getTime());

        if (--staggerRemaining <= 0) {
            hideCursor();
            tackle = false;
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

    private void recordCursor(final Point cursor) {
        if (cursorHistory == null) {
            cursorHistory = new CursorSample[CURSOR_HISTORY_SIZE];
            cursorHistoryIndex = 0;
            cursorHistoryFill = 0;
        }
        cursorHistory[cursorHistoryIndex] = new CursorSample(cursor.x, cursor.y, System.nanoTime());
        cursorHistoryIndex = (cursorHistoryIndex + 1) % CURSOR_HISTORY_SIZE;
        cursorHistoryFill = Math.min(cursorHistoryFill + 1, CURSOR_HISTORY_SIZE);
    }

    private CursorSample oldestSample() {
        if (cursorHistoryFill <= 1) {
            return null;
        }
        return cursorHistory[cursorHistoryFill == CURSOR_HISTORY_SIZE ? cursorHistoryIndex : 0];
    }

    private CursorSample newestSample() {
        if (cursorHistoryFill == 0) {
            return null;
        }
        return cursorHistory[(cursorHistoryIndex - 1 + CURSOR_HISTORY_SIZE) % CURSOR_HISTORY_SIZE];
    }

    /**
     * A tackle is a head-on collision. The approach-phase reading (cursor's
     * own closing speed toward Nigel during the leap, measured by
     * {@code CursorLeap}) is the primary signal, since a charging user
     * decelerates at the last moment to "meet" Nigel. The post-landing
     * contact window reading is kept as a fallback for catches where the
     * charge only happens right at the grab.
     */
    private boolean isTackle() {
        final Point anchor = getMascot().getAnchor();

        final double approachClosingSpeed = getMascot().getApproachClosingSpeed();

        double contactClosingSpeed = 0.0;
        final CursorSample oldest = oldestSample();
        final CursorSample newest = newestSample();
        if (oldest != null && newest != null && oldest.nanoTime < newest.nanoTime) {
            final int intervals = Math.min(cursorHistoryFill, CURSOR_HISTORY_SIZE) - 1;
            if (intervals >= 2) {
                final double prevDistance = oldest.distance(anchor.x, anchor.y);
                final double curDistance = newest.distance(anchor.x, anchor.y);
                contactClosingSpeed = (prevDistance - curDistance) / intervals;
            }
        }

        log.info("Tackle check: approachClosingSpeed={}, contactClosingSpeed={}, threshold={}",
                approachClosingSpeed, contactClosingSpeed, TACKLE_CLOSING_SPEED);
        return approachClosingSpeed > TACKLE_CLOSING_SPEED
                || contactClosingSpeed > TACKLE_CLOSING_SPEED;
    }

    /**
     * Shoves Nigel away from the direction the cursor came charging in from.
     */
    private void applyKnockback() throws VariableException {
        final CursorSample oldest = oldestSample();
        final CursorSample newest = newestSample();
        if (oldest == null || newest == null) {
            return;
        }
        final double dx = newest.x - oldest.x;
        final double dy = newest.y - oldest.y;
        final double speed = Math.sqrt(dx * dx + dy * dy);
        if (speed <= 1.0) {
            return;
        }
        final double knockback = getTackleKnockback();
        getMascot().getAnchor().translate(
                (int) Math.round(-dx / speed * knockback),
                (int) Math.round(-dy / speed * knockback));
        clampAnchorToScreen();
    }

    private void resetDrift() {
        driftTargetX = 0.0;
        driftTargetY = 0.0;
        driftAppliedX = 0;
        driftAppliedY = 0;
    }

    /**
     * Moves Nigel each tick to sell the wrestling: a slow floaty sine when
     * calm, violent jitter scaled by fight intensity when struggling. All
     * offsets are clamped to a small box around the catch point, and applied
     * in whole pixels, so the anchor can never drift away over time.
     */
    private void wrestle(final double struggle, final boolean fighting) throws VariableException {
        if (!fighting) {
            driftTo(Math.sin(getTime() * 0.05) * 3.0, Math.cos(getTime() * 0.07) * 2.0);
        } else {
            final double intensity = Math.min(1.0, struggle / Math.max(1.0, getFuriousThreshold()));
            driftTo(driftTargetX + (Math.random() * 12.0 - 6.0) * intensity,
                    driftTargetY + (Math.random() * 8.0 - 4.0) * intensity);
        }
    }

    private void driftTo(final double targetX, final double targetY) {
        // Clamp the target box first, then move in whole pixels so no
        // fractional residue can accumulate into long-term drift.
        final int nextX = (int) Math.round(Math.max(-12.0, Math.min(12.0, targetX)));
        final int nextY = (int) Math.round(Math.max(-8.0, Math.min(8.0, targetY)));
        getMascot().getAnchor().translate(nextX - driftAppliedX, nextY - driftAppliedY);
        driftAppliedX = nextX;
        driftAppliedY = nextY;
        driftTargetX = Math.max(-12.0, Math.min(12.0, targetX));
        driftTargetY = Math.max(-8.0, Math.min(8.0, targetY));
    }

    private void clampAnchorToScreen() {
        try {
            final Area screen = getEnvironment().getScreen();
            final Point anchor = getMascot().getAnchor();
            anchor.x = Math.max(screen.getLeft(), Math.min(screen.getRight(), anchor.x));
            anchor.y = Math.max(screen.getTop(), Math.min(screen.getBottom(), anchor.y));
        } catch (final RuntimeException e) {
            log.warn("Could not clamp Nigel to the screen during grasp", e);
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
        Mascot.releaseMouse(getMascot());
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

    private void ensureCuddleImagesLoaded() {
        if (cuddleImagesLoaded) {
            return;
        }
        try {
            final double scaling = Main.getInstance().getSettings().scaling;
            final Filter filter = Main.getInstance().getSettings().filter;
            final double opacity = Main.getInstance().getSettings().opacity;
            final String imageSet = getMascot() != null && getMascot().getImageSet() != null
                    ? getMascot().getImageSet() : "NigelShimeji";
            // Images are 192x192 with anchor 96,200 matching struggle pose
            cuddleImageKey1 = ImagePairs.load(Path.of(imageSet, "cuddlemouse1.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(cuddleImageKey1, imageSet);
            cuddleImageKey2 = ImagePairs.load(Path.of(imageSet, "cuddlemouse2.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(cuddleImageKey2, imageSet);
            cuddleImagesLoaded = true;
        } catch (final IOException | RuntimeException e) {
            log.warn("Failed to load cuddle images for GraspMouse", e);
        }
    }

    private void applyCuddleAnimation() {
        ensureCuddleImagesLoaded();
        final String key = (cuddleTicks / CUDDLE_ANIM_INTERVAL) % 2 == 0 ? cuddleImageKey1 : cuddleImageKey2;
        if (key != null && ImagePairs.contains(key)) {
            getMascot().setImage(ImagePairs.get(key).getImage(getMascot().isLookRight()));
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

    private int getTackleStaggerTicks() throws VariableException {
        return eval(getSchema().getString(PARAMETER_TACKLE_STAGGER_TICKS), Number.class, DEFAULT_TACKLE_STAGGER_TICKS).intValue();
    }

    private double getTackleKnockback() throws VariableException {
        return eval(getSchema().getString(PARAMETER_TACKLE_KNOCKBACK), Number.class, DEFAULT_TACKLE_KNOCKBACK).doubleValue();
    }

    private int getCuddleIdleTicks() throws VariableException {
        return eval(getSchema().getString(PARAMETER_CUDDLE_IDLE_TICKS), Number.class, DEFAULT_CUDDLE_IDLE_TICKS).intValue();
    }

    private int getCuddleDurationTicks() throws VariableException {
        return eval(getSchema().getString(PARAMETER_CUDDLE_DURATION_TICKS), Number.class, DEFAULT_CUDDLE_DURATION_TICKS).intValue();
    }

    private double getCuddleShakeThreshold() throws VariableException {
        return eval(getSchema().getString(PARAMETER_CUDDLE_SHAKE_THRESHOLD), Number.class, DEFAULT_CUDDLE_SHAKE_THRESHOLD).doubleValue();
    }

    private int getCuddleShakeCount() throws VariableException {
        return eval(getSchema().getString(PARAMETER_CUDDLE_SHAKE_COUNT), Number.class, DEFAULT_CUDDLE_SHAKE_COUNT).intValue();
    }
}
