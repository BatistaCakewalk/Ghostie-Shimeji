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
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

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

    private static final String PARAMETER_SWALLOW_CHANCE = "SwallowChance";
    private static final double DEFAULT_SWALLOW_CHANCE = 0.3;

    private static final String PARAMETER_SWALLOW_CLICK_COUNT = "SwallowClickCount";
    private static final int DEFAULT_SWALLOW_CLICK_COUNT = 12;

    private static final String PARAMETER_SWALLOW_CLICK_WINDOW = "SwallowClickWindow";
    private static final int DEFAULT_SWALLOW_CLICK_WINDOW = 90;

    private static final String PARAMETER_SICK_PHASE1_TICKS = "SickPhase1Ticks";
    private static final int DEFAULT_SICK_PHASE1_TICKS = 75;

    private static final String PARAMETER_SICK_PHASE2_TICKS = "SickPhase2Ticks";
    private static final int DEFAULT_SICK_PHASE2_TICKS = 50;

    private static final String PARAMETER_SPIT_SPEED_X = "SpitSpeedX";
    private static final double DEFAULT_SPIT_SPEED_X = 12.0;

    private static final String PARAMETER_SPIT_SPEED_Y = "SpitSpeedY";
    private static final double DEFAULT_SPIT_SPEED_Y = 22.0;

    private static final String PARAMETER_SPIT_GRAVITY = "SpitGravity";
    private static final double DEFAULT_SPIT_GRAVITY = 2.0;

    private static final String PARAMETER_SPIT_BOUNCE = "SpitBounce";
    private static final double DEFAULT_SPIT_BOUNCE = 0.6;

    private static final String PARAMETER_SICK_CLICK_POWER = "SickClickPower";
    private static final double DEFAULT_SICK_CLICK_POWER = 0.05;

    private static final String PARAMETER_SICK_BURP_CLICKS = "SickBurpClicks";
    private static final int DEFAULT_SICK_BURP_CLICKS = 6;

    private static final String PARAMETER_SICK_POWER_CAP = "SickPowerCap";
    private static final double DEFAULT_SICK_POWER_CAP = 3.0;

    private static final String PARAMETER_MAX_STRUGGLE_BONUS = "MaxStruggleBonus";
    private static final double DEFAULT_MAX_STRUGGLE_BONUS = 600.0;

    private static final String PARAMETER_MAX_CATCHABLE_CURSOR_SIZE = "MaxCatchableCursorSize";
    private static final double DEFAULT_MAX_CATCHABLE_CURSOR_SIZE = 96.0;

    private static final int CUDDLE_SHAKE_WINDOW = 30;
    private static final int CUDDLE_ANIM_INTERVAL = 30;
    private static final int SWALLOW_GULP_TICKS = 40;
    private static final int SWALLOW_AFTER_TICKS = 40;
    private static final int BLOAT_ANIM_INTERVAL = 30;

    /**
     * Stuffed-swallow intro choreography (big-cursor art): Big1/Big2/Big3,
     * then After1 (5s dizzy), After2 (3s looking down), then After3/After4
     * alternating 4 cycles (~10s). Suspended in the air throughout; normal
     * sink/waddle resumes after.
     */
    private static final int BIG_SWALLOW1_TICKS = 125;
    private static final int BIG_SWALLOW2_TICKS = 125;
    private static final int BIG_SWALLOW3_TICKS = 75;
    private static final int BIG_AFTER1_TICKS = 125;
    private static final int BIG_AFTER2_TICKS = 75;
    private static final int BIG_AFTER34_FRAME = 31;
    private static final int BIG_AFTER34_CYCLES = 4;
    private static final int BIG_INTRO_END = BIG_SWALLOW1_TICKS + BIG_SWALLOW2_TICKS + BIG_SWALLOW3_TICKS
            + BIG_AFTER1_TICKS + BIG_AFTER2_TICKS + BIG_AFTER34_FRAME * 2 * BIG_AFTER34_CYCLES;

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

    /**
     * Maximum HP drained in a single tick, no matter how far the cursor was
     * flung. Keeps full-screen whips survivable while normal fights feel the same.
     */
    private static final double MAX_DRAIN_PER_TICK = 30.0;

    /**
     * Belly ticks with no registered clicks before Nigel gets sick on his
     * own (90000 ticks at 40ms = an hour). Anti-softlock: if the cursor
     * ever stops landing clicks on Nigel, the swallow must still end.
     * Why? Fuck you that's why-
     */
    private static final int SWALLOW_MAX_TICKS = 90000;

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
    private boolean catchSoundPlayed;
    private int cuddleTicks;
    private int shakeCount;
    private int shakeWindowRemaining;
    private int postCuddleGrace;

    // Swallow mode state: cursor fully trapped, only rapid clicking frees it
    private boolean cuddleQueued;
    private boolean devourQueued;
    private boolean swallowMode;
    private int swallowTicks;
    private int swallowClicks;
    private int swallowWindowRemaining;
    private int swallowDir;
    private int swallowWander;
    private String swallowKeyStart;
    private String swallowKeyAfter;
    private String bloatKey1;
    private String bloatKey2;
    private String bloatWalkKey1;
    private String bloatWalkKey2;
    private String bloatBigKey1;
    private String bloatBigKey2;
    private String bloatBigWalkKey1;
    private String bloatBigWalkKey2;
    private String bigSwallowKey1;
    private String bigSwallowKey2;
    private String bigSwallowKey3;
    private String bigAfterKey1;
    private String bigAfterKey2;
    private String bigAfterKey3;
    private String bigAfterKey4;
    private boolean swallowImagesLoaded;

    // Sick + spit-fling state after a swallow is shaken loose
    private int sickPhase;
    private int sickTicks;
    private int sickClicks;
    private boolean flingActive;
    private double flingX;
    private double flingY;
    private double flingVX;
    private double flingVY;
    private int flingTicks;
    private String sickKey1;
    private String sickKey2;
    private boolean sickImagesLoaded;

    // Burp + recover state after the fling lands
    private boolean recoverActive;
    private int recoverTicks;
    private double recoilVX;
    private double recoilVY;
    private String burpKey;
    private String burpAftermathKey;
    private boolean burpImageLoaded;

    // Swallow failsafe state: ticks since the last registered click
    private int swallowStuckTicks;

    // Swallow size multiplier cached at swallow enter: scales the gulp
    // length, click requirement, window and bleed-off together.
    private double swallowSizeMult = 1.0;

    /**
     * Scaled gulp phase lengths: bigger cursors take longer to force down.
     */
    private int getScaledSwallowGulpTicks() {
        return Math.max(1, (int) Math.round(SWALLOW_GULP_TICKS * swallowSizeMult));
    }

    private int getScaledSwallowAfterTicks() {
        return Math.max(1, (int) Math.round(SWALLOW_AFTER_TICKS * swallowSizeMult));
    }

    // Mega-burp state: floor bounces left on the current fling
    private int flingBounces;

    // Sick extension: extra phase-2 ticks granted while burp clicks run short
    private int sickExtraTicks;

    private static final int RECOVER_MIN_TICKS = 75;

    /**
     * Ticks after the spit launch before the burp recover starts, so Nigel
     * is already coming down while the cursor is still flying. Short
     * flights land before this and recover on the ground as before.
     */
    private static final int RECOVER_LEAD_TICKS = 25;
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

    /**
     * Saliva droplets for the spit trail. Self-expiring on a Swing Timer (EDT)
     * so no action-end hook is needed: even if the grasp is interrupted
     * mid-fling, every droplet fades and disposes itself on its own clock.
     */
    private static final class Droplet {
        double x;
        double y;
        double vx;
        double vy;
        long born;
        JWindow window;
    }

    private static final long DROPLET_LIFE_MILLIS = 650;
    private static final int DROPLET_MAX_ALIVE = 40;
    private static final List<Droplet> DROPLETS = new ArrayList<>();
    private static javax.swing.Timer dropletTimer;

    private static synchronized void ensureDropletTimer() {
        if (dropletTimer != null) {
            return;
        }
        dropletTimer = new javax.swing.Timer(50, e -> tickDroplets());
        dropletTimer.setRepeats(true);
        dropletTimer.start();
    }

    private static synchronized void tickDroplets() {
        final long now = System.currentTimeMillis();
        for (int i = DROPLETS.size() - 1; i >= 0; i--) {
            final Droplet d = DROPLETS.get(i);
            final long age = now - d.born;
            if (age >= DROPLET_LIFE_MILLIS) {
                try {
                    d.window.dispose();
                } catch (final RuntimeException ignored) {
                }
                DROPLETS.remove(i);
                continue;
            }
            d.vy += 0.6;
            d.x += d.vx;
            d.y += d.vy;
            try {
                d.window.setLocation((int) Math.round(d.x), (int) Math.round(d.y));
                d.window.setOpacity(1.0f - (float) age / DROPLET_LIFE_MILLIS);
            } catch (final RuntimeException ignored) {
            }
        }
        if (DROPLETS.isEmpty() && dropletTimer != null) {
            dropletTimer.stop();
            dropletTimer = null;
        }
    }

    /**
     * Spawns a burst of saliva droplets at the given screen position. Window
     * creation happens on the EDT; safe to call from the mascot tick thread.
     */
    private static void spawnDroplets(final double x, final double y, final double power) {
        ensureDropletTimer();
        final int count = Math.min(8, 2 + (int) Math.round(power * 2.0));
        SwingUtilities.invokeLater(() -> {
            synchronized (GraspMouse.class) {
                for (int i = 0; i < count; i++) {
                    while (DROPLETS.size() >= DROPLET_MAX_ALIVE) {
                        final Droplet oldest = DROPLETS.remove(0);
                        try {
                            oldest.window.dispose();
                        } catch (final RuntimeException ignored) {
                        }
                    }
                    final int size = 8 + (int) (Math.random() * 7);
                    final JWindow window = new JWindow();
                    try {
                        window.setAlwaysOnTop(true);
                        window.setBackground(new Color(0, 0, 0, 0));
                        final JComponent dot = new JComponent() {
                            @Override
                            protected void paintComponent(final Graphics g) {
                                super.paintComponent(g);
                                g.setColor(new Color(150, 220, 90));
                                g.fillOval(0, 0, size, size);
                                g.setColor(new Color(205, 255, 150));
                                g.fillOval(size / 4, size / 4, size / 3, size / 3);
                            }
                        };
                        dot.setPreferredSize(new Dimension(size, size));
                        window.add(dot);
                        window.pack();
                        final Droplet d = new Droplet();
                        d.x = x + (Math.random() * 28.0 - 14.0) - size / 2.0;
                        d.y = y + (Math.random() * 28.0 - 14.0) - size / 2.0;
                        d.vx = Math.random() * 8.0 - 4.0;
                        d.vy = -Math.random() * 3.0;
                        d.born = System.currentTimeMillis();
                        d.window = window;
                        window.setLocation((int) Math.round(d.x), (int) Math.round(d.y));
                        window.setVisible(true);
                        DROPLETS.add(d);
                    } catch (final RuntimeException e) {
                        try {
                            window.dispose();
                        } catch (final RuntimeException ignored) {
                        }
                    }
                }
            }
        });
    }

    public GraspMouse(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);

        maxHp = getMaxStruggle() + Math.random() * Math.max(0.0, getMaxStruggleBonus());
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
        catchSoundPlayed = false;
        cuddleTicks = 0;
        shakeCount = 0;
        shakeWindowRemaining = 0;
        postCuddleGrace = 0;
        cuddleQueued = false;
        devourQueued = false;
        swallowMode = false;
        swallowTicks = 0;
        swallowClicks = 0;
        swallowWindowRemaining = 0;
        swallowDir = 0;
        swallowWander = 0;
        sickPhase = 0;
        sickTicks = 0;
        sickClicks = 0;
        sickExtraTicks = 0;
        flingActive = false;
        flingTicks = 0;
        flingBounces = 0;
        recoverActive = false;
        recoverTicks = 0;
        recoilVX = 0.0;
        recoilVY = 0.0;
        // cuddle/swallow/sick/burp image keys persist across grasps to avoid reloading

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
            if (getEnvironment().isFullscreen() || getEnvironment().isMouseLocked()) {
                log.info("Grasp suppressed: fullscreen/mouse-lock detected for {}", getMascot());
                throw new LostGroundException("Fullscreen/mouse-lock active");
            }
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
                    getMascot().consumeDevourNext();
                    getMascot().consumeCuddleNext();
                    throw new LostGroundException("Missed the cursor");
                }

                // Too big to hold, even delivered: telekinesis can drag a
                // huge cursor onto the sprite, but the arms still refuse it.
                if (isCursorTooBig()) {
                    log.info("Grasp refused: cursor too big ({}px)", getEnvironment().getCursorSizePixels());
                    Mascot.releaseMouse(getMascot());
                    getMascot().consumeDevourNext();
                    getMascot().consumeCuddleNext();
                    throw new LostGroundException("Cursor too big to hold");
                }

                // Only one Nigel may hold the mouse at a time.
                if (!Mascot.tryAcquireMouse(getMascot())) {
                    final Mascot owner = Mascot.getMouseOwner();
                    log.info("Grasp blocked: mouse already owned by {}", owner);
                    getMascot().consumeDevourNext();
                    getMascot().consumeCuddleNext();
                    throw new LostGroundException("Mouse already grabbed by " + owner);
                }

                // Max-strength telekinesis devour: skip the contact window,
                // the hold starts already eaten.
                if (getMascot().consumeDevourNext()) {
                    devourQueued = true;
                    contactRemaining = 0;
                } else if (getMascot().consumeCuddleNext()) {
                    cuddleQueued = true;
                    contactRemaining = 0;
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

    /**
     * Rolls what a long idle turns into: usually a cuddle, sometimes a
     * swallow. Resets the relevant state either way.
     */
    private void enterIdleReward(final boolean quickReenter) throws VariableException {
        idleTicks = 0;
        postCuddleGrace = 0;
        if (Main.getInstance().getSettings().nigelSwallowEnabled && !isCursorTooBig()
                && Math.random() < getSwallowChance()) {
            log.info("Entering swallow mode (quick re-enter: {}, cursor {}px, needs {} clicks)",
                    quickReenter, getEnvironment().getCursorSizePixels(), getSwallowClicksRequired());
            com.group_finity.mascot.sound.NigelSounds.playGulp();
            swallowMode = true;
            swallowTicks = 0;
            swallowClicks = 0;
            swallowWindowRemaining = 0;
            swallowDir = 0;
            swallowWander = 0;
            swallowStuckTicks = 0;
            swallowSizeMult = getSwallowSizeMultiplier();
            sickPhase = 0;
            sickTicks = 0;
            sickClicks = 0;
            flingActive = false;
            flingTicks = 0;
            recoverActive = false;
            recoverTicks = 0;
            recoilVX = 0.0;
            recoilVY = 0.0;
            return;
        }
            log.info("Entering cuddle mode after idle (quick re-enter: {})", quickReenter);
            com.group_finity.mascot.sound.NigelSounds.playCuddle();
        cuddleMode = true;
        catchSoundPlayed = true;
        cuddleTicks = 0;
        shakeCount = 0;
        shakeWindowRemaining = 0;
    }

    private void tickGrasp() throws LostGroundException, VariableException {
        if (getEnvironment().isFullscreen() || getEnvironment().isMouseLocked()) {
            throw new LostGroundException("Fullscreen/mouse-lock active");
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
        final double struggle = getGraspPoint().distance(raw);
        final boolean fighting = struggle > STRUGGLE_THRESHOLD;

        // Drain presses every tick so the counter never goes stale in normal/cuddle mode.
        final int clicks = getMascot().getAndResetGraspClicks();

        // Lock-in: the hold is real from this first tickGrasp call on. Hug
        // and swallow orders route elsewhere with their own sounds, so
        // reaching here unsuppressed means a genuine catch. Gotcha.
        if (!catchSoundPlayed && !cuddleQueued && !devourQueued) {
            catchSoundPlayed = true;
            com.group_finity.mascot.sound.NigelSounds.playCatch();
        }

        // Menu-ordered cuddle: skip the idle wait and enter cuddle mode immediately.
        if (cuddleQueued) {
            cuddleQueued = false;
            log.info("Entering cuddle mode immediately (menu-ordered)");
            com.group_finity.mascot.sound.NigelSounds.playCuddle();
            hideCursor();
            idleTicks = 0;
            postCuddleGrace = 0;
            cuddleMode = true;
            catchSoundPlayed = true;
            cuddleTicks = 0;
            shakeCount = 0;
            shakeWindowRemaining = 0;
            return;
        }

        // Devoured straight out of a max-strength pull: no idle wait, no cuddle roll.
        // (Unless swallowing is disabled in Nigel Settings, or the cursor is
        // too big to fit: then the devour just becomes a normal grasp.)
        if (devourQueued && (!Main.getInstance().getSettings().nigelSwallowEnabled || isCursorTooBig())) {
            devourQueued = false;
            log.info("Devoured but swallowing is off the table, normal grasp continues");
        }
        if (devourQueued) {
            devourQueued = false;
            log.info("Devoured straight into swallow mode (cursor {}px, needs {} clicks)",
                    getEnvironment().getCursorSizePixels(), getSwallowClicksRequired());
            com.group_finity.mascot.sound.NigelSounds.playGulp();
            // The contact window (which normally hides the cursor) was skipped.
            hideCursor();
            swallowMode = true;
            swallowTicks = 0;
            swallowClicks = 0;
            swallowWindowRemaining = 0;
            swallowDir = 0;
            swallowWander = 0;
            swallowStuckTicks = 0;
            swallowSizeMult = getSwallowSizeMultiplier();
            sickPhase = 0;
            sickTicks = 0;
            sickClicks = 0;
            flingActive = false;
            flingTicks = 0;
            recoverActive = false;
            recoverTicks = 0;
            recoilVX = 0.0;
            recoilVY = 0.0;
        }

        // --- Swallow mode handling ---
        if (swallowMode) {
            // Burp recover first, but never ahead of an active fling: while
            // the cursor still flies, tickSickFling owns the ticks and runs
            // the recover visuals itself once the lead-in passes.
            if (recoverActive && !flingActive) {
                tickRecover();
                return;
            }
            // Sick + fling run their own sub-state; cursor stays covered (hidden) throughout.
            if (sickPhase > 0 || flingActive) {
                tickSickFling(clicks);
                return;
            }
            swallowTicks++;
            final boolean bigIntro = isStuffedSwallow() && hasBigSwallowArt();
            // Glug marks the moment it's fully inside: SwallowAfter's first
            // frame normally, Big3's last frame for the stuffed intro.
            final int gulpEnd = bigIntro
                    ? BIG_SWALLOW1_TICKS + BIG_SWALLOW2_TICKS + BIG_SWALLOW3_TICKS
                    : getScaledSwallowGulpTicks();
            if (swallowTicks == gulpEnd) {
                com.group_finity.mascot.sound.NigelSounds.playGlug();
            }

            if (swallowWindowRemaining > 0) {
                swallowWindowRemaining--;
                if (swallowWindowRemaining == 0) {
                    swallowClicks = 0;
                }
            }
            // No clicking out during the stuffed intro: the choreography
            // plays first, escape starts once he's waddling with it.
            if (clicks > 0 && !inBigSwallowIntro()) {
                if (swallowWindowRemaining == 0) {
                    swallowWindowRemaining = (int) Math.round(
                            getSwallowClickWindow() * getSwallowSizeMultiplier());
                    swallowClicks = clicks;
                } else {
                    swallowClicks += clicks;
                }
                log.info("Swallow clicks: {} this tick, {} in window", clicks, swallowClicks);
            } else if (swallowClicks > 0
                    && swallowTicks % Math.max(1, Math.round(15 * getSwallowSizeMultiplier())) == 0) {
                // Stale clicks bleed off, slower for big cursors: stop
                // clicking and progress fades ~1 click per scaled interval.
                swallowClicks--;
            }
            if (swallowClicks >= getSwallowClicksRequired()) {
                log.info("Swallow shaken loose after {} clicks, Nigel feels sick", swallowClicks);
                com.group_finity.mascot.sound.NigelSounds.playHeave();
                sickPhase = 1;
                sickTicks = 0;
                sickClicks = 0;
                sickExtraTicks = 0;
            }
            if (swallowClicks > 0) {
                swallowStuckTicks = 0;
            } else if (++swallowStuckTicks >= SWALLOW_MAX_TICKS) {
                // Anti-softlock: clicks only register on Nigel's window. If
                // none ever land, don't hold the cursor hostage forever.
                log.info("Swallow failsafe: no clicks registered, Nigel feels sick anyway");
                sickPhase = 1;
                sickTicks = 0;
                sickClicks = 0;
                sickExtraTicks = 0;
            }

            // Fully trapped: HP frozen, cursor pinned hard. The big-swallow
            // intro suspends him in the air for its whole choreography, but
            // the forcing (shakes, chokes) stops once Big3 lands the mouse
            // inside: the After frames are calm aftermath.
            final boolean forcingDown = bigIntro
                    ? swallowTicks < BIG_SWALLOW1_TICKS + BIG_SWALLOW2_TICKS + BIG_SWALLOW3_TICKS
                    : swallowTicks <= getScaledSwallowGulpTicks() + getScaledSwallowAfterTicks();
            final boolean gulping = forcingDown || (bigIntro && swallowTicks < BIG_INTRO_END);
            final boolean grounded = getEnvironment().getFloor().isOn(getMascot().getAnchor());
            boolean waddling = false;
            if (gulping) {
                // Gulp in place where he caught it. While forcing down a
                // stuffed cursor, the body heaves once with each choke;
                // otherwise he holds still and strains. Smaller cursors go
                // down easy with no theatrics at all.
                if (forcingDown && isStuffedSwallow() && swallowTicks % 30 == 0) {
                    com.group_finity.mascot.sound.NigelSounds.playChoke();
                    getMascot().getAnchor().translate(
                            (int) Math.round(Math.random() * 4.0 - 2.0),
                            (int) Math.round(Math.random() * 4.0 - 2.0));
                } else {
                    wrestle(0.0, false);
                }
            } else if (!grounded) {
                // Sway side to side on the way down instead of dropping like an elevator.
                final int sway = (int) Math.round(Math.sin(swallowTicks * 0.15) * 2.0);
                getMascot().getAnchor().translate(sway, 3);
            } else {
                // Waddle in bursts with idle pauses, like he's showing off his prize.
                if (swallowWander == 0) {
                    if (swallowDir == 0 || Math.random() < 0.6) {
                        swallowDir = Math.random() < 0.5 ? -1 : 1;
                        swallowWander = 60 + (int) (Math.random() * 90);
                    } else {
                        swallowWander = -(40 + (int) (Math.random() * 60));
                    }
                }
                if (swallowWander > 0) {
                    getMascot().getAnchor().translate(swallowDir * 2, 0);
                    final Area screen = getEnvironment().getScreen();
                    final Point anchor = getMascot().getAnchor();
                    if (anchor.x <= screen.getLeft() + 2 || anchor.x >= screen.getRight() - 2) {
                        swallowDir = -swallowDir;
                    }
                    getMascot().setLookRight(swallowDir > 0);
                    waddling = true;
                    swallowWander--;
                } else {
                    swallowWander++;
                }
            }
            // Fully trapped, pinned dead center on Nigel: clicks only
            // register on his window, so the cursor stays exactly on him.
            clampAnchorToScreen();
            final Point hands = getGraspPoint();
            robot.mouseMove(hands.x, hands.y);
            applySwallowAnimation(waddling);
            reassertCursorHidden();
            return;
        }

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
                    enterIdleReward(true);
                }
            }
            // fighting during grace: keep idleTicks, don't reset
        } else if (justBrokeCuddle) {
            // Keep half-idle progress for this tick so a brief struggle doesn't reset to full 10s
        } else if (!fighting) {
            idleTicks++;
            if (idleTicks >= getCuddleIdleTicks()) {
                enterIdleReward(false);
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
            // mashing tires itself out through fatigue. Per-tick drain is
            // capped so full-screen whips can't nuke the meter in one frame.
            // Audible strain every so often, hotter the harder the fight.
            if (getTime() % 15 == 0) {
                com.group_finity.mascot.sound.NigelSounds.playStruggle(
                        Math.min(1.0, struggle / 150.0));
            }
            fatigue++;
            final double fatigueMultiplier = Math.max(0.2, 1.0 - fatigue / 100.0);
            hp -= Math.min(applyStruggleCurve(struggle) * fatigueMultiplier, MAX_DRAIN_PER_TICK);
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

        if (getTime() % 25 == 0) {
            log.info("Grasp status: tick={}, hp={}/{}, struggle={}, fatigue={}",
                    getTime(), hp, maxHp, struggle, fatigue);
        }

        if (hp <= 0) {
            log.info("Grasp broken: ticksHeld={}, finalStruggle={}, fatigue={}", getTime(), struggle, fatigue);
            com.group_finity.mascot.sound.NigelSounds.playBreakFree();
            throw new LostGroundException("The mouse broke free of Nigel's grasp");
        }
    }

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
        com.group_finity.mascot.sound.NigelSounds.playBonk();
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

    private void ensureSwallowImagesLoaded() {
        if (swallowImagesLoaded) {
            return;
        }
        try {
            final double scaling = Main.getInstance().getSettings().scaling;
            final Filter filter = Main.getInstance().getSettings().filter;
            final double opacity = Main.getInstance().getSettings().opacity;
            final String imageSet = getMascot() != null && getMascot().getImageSet() != null
                    ? getMascot().getImageSet() : "NigelShimeji";
            // All 192x192 with anchor 96,200 matching the struggle pose
            swallowKeyStart = ImagePairs.load(Path.of(imageSet, "Swallow.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(swallowKeyStart, imageSet);
            swallowKeyAfter = ImagePairs.load(Path.of(imageSet, "SwallowAfter.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(swallowKeyAfter, imageSet);
            bloatKey1 = ImagePairs.load(Path.of(imageSet, "BloatStand.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(bloatKey1, imageSet);
            bloatKey2 = ImagePairs.load(Path.of(imageSet, "BloatStand2.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(bloatKey2, imageSet);
            bloatWalkKey1 = ImagePairs.load(Path.of(imageSet, "BloatWalk1.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(bloatWalkKey1, imageSet);
            bloatWalkKey2 = ImagePairs.load(Path.of(imageSet, "BloatWalk2.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(bloatWalkKey2, imageSet);
            // Stuffed tier (BloatBig*.png) is optional: missing files just fall
            // back to the normal bloat sprites, one file at a time.
            bloatBigKey1 = loadOptionalSwallowImage(imageSet, "FatterStand.png", scaling, filter, opacity);
            bloatBigKey2 = loadOptionalSwallowImage(imageSet, "FatterStand2.png", scaling, filter, opacity);
            bloatBigWalkKey1 = loadOptionalSwallowImage(imageSet, "WalkBigBloated1.png", scaling, filter, opacity);
            bloatBigWalkKey2 = loadOptionalSwallowImage(imageSet, "WalkBigBloated2.png", scaling, filter, opacity);
            // Stuffed intro set (SwallowBig1-3, SwallowAfter1-4): all or
            // nothing, the choreography needs every frame.
            bigSwallowKey1 = loadOptionalSwallowImage(imageSet, "SwallowBig1.png", scaling, filter, opacity);
            bigSwallowKey2 = loadOptionalSwallowImage(imageSet, "SwallowBig2.png", scaling, filter, opacity);
            bigSwallowKey3 = loadOptionalSwallowImage(imageSet, "SwallowBig3.png", scaling, filter, opacity);
            bigAfterKey1 = loadOptionalSwallowImage(imageSet, "SwallowAfter1.png", scaling, filter, opacity);
            bigAfterKey2 = loadOptionalSwallowImage(imageSet, "SwallowAfter2.png", scaling, filter, opacity);
            bigAfterKey3 = loadOptionalSwallowImage(imageSet, "SwallowAfter3.png", scaling, filter, opacity);
            bigAfterKey4 = loadOptionalSwallowImage(imageSet, "SwallowAfter4.png", scaling, filter, opacity);
            swallowImagesLoaded = true;
        } catch (final IOException | RuntimeException e) {
            log.warn("Failed to load swallow images for GraspMouse", e);
        }
    }

    private static String loadOptionalSwallowImage(final String imageSet, final String file,
            final double scaling, final Filter filter, final double opacity) {
        try {
            final String key = ImagePairs.load(Path.of(imageSet, file), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(key, imageSet);
            return key;
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    private void applySwallowAnimation(final boolean waddling) {
        ensureSwallowImagesLoaded();
        final boolean stuffed = isStuffedSwallow();
        final String key;
        if (inBigSwallowIntro()) {
            final int t = swallowTicks;
            final int big2End = BIG_SWALLOW1_TICKS + BIG_SWALLOW2_TICKS;
            final int big3End = big2End + BIG_SWALLOW3_TICKS;
            final int after1End = big3End + BIG_AFTER1_TICKS;
            final int after2End = after1End + BIG_AFTER2_TICKS;
            if (t < BIG_SWALLOW1_TICKS) {
                key = bigSwallowKey1;
            } else if (t < big2End) {
                key = bigSwallowKey2;
            } else if (t < big3End) {
                key = bigSwallowKey3;
            } else if (t < after1End) {
                key = bigAfterKey1;
            } else if (t < after2End) {
                key = bigAfterKey2;
            } else {
                key = ((t - after2End) / BIG_AFTER34_FRAME) % 2 == 0 ? bigAfterKey3 : bigAfterKey4;
            }
        } else if (swallowTicks < getScaledSwallowGulpTicks()) {
            key = swallowKeyStart;
        } else if (swallowTicks < getScaledSwallowGulpTicks() + getScaledSwallowAfterTicks()) {
            key = swallowKeyAfter;
        } else if (waddling) {
            key = ((swallowTicks - getScaledSwallowGulpTicks() - getScaledSwallowAfterTicks()) / BLOAT_ANIM_INTERVAL) % 2 == 0
                    ? orElse(bloatBigWalkKey1, bloatWalkKey1, stuffed) : orElse(bloatBigWalkKey2, bloatWalkKey2, stuffed);
        } else {
            key = ((swallowTicks - getScaledSwallowGulpTicks() - getScaledSwallowAfterTicks()) / BLOAT_ANIM_INTERVAL) % 2 == 0
                    ? orElse(bloatBigKey1, bloatKey1, stuffed) : orElse(bloatBigKey2, bloatKey2, stuffed);
        }
        if (key != null && ImagePairs.contains(key)) {
            getMascot().setImage(ImagePairs.get(key).getImage(getMascot().isLookRight()));
        }
    }

    private static String orElse(final String big, final String normal, final boolean stuffed) {
        if (stuffed && big != null && ImagePairs.contains(big)) {
            return big;
        }
        return normal;
    }

    private boolean hasBigSwallowArt() {
        return bigSwallowKey1 != null && ImagePairs.contains(bigSwallowKey1)
                && bigSwallowKey2 != null && ImagePairs.contains(bigSwallowKey2)
                && bigSwallowKey3 != null && ImagePairs.contains(bigSwallowKey3)
                && bigAfterKey1 != null && ImagePairs.contains(bigAfterKey1)
                && bigAfterKey2 != null && ImagePairs.contains(bigAfterKey2)
                && bigAfterKey3 != null && ImagePairs.contains(bigAfterKey3)
                && bigAfterKey4 != null && ImagePairs.contains(bigAfterKey4);
    }

    private boolean inBigSwallowIntro() {
        return isStuffedSwallow() && hasBigSwallowArt() && swallowTicks < BIG_INTRO_END;
    }

    /**
     * Sick window after a swallow is shaken loose, then the spit-fling.
     * The cursor stays hidden (covered) until it lands.
     */
    private void tickSickFling(final int clicks) throws LostGroundException, VariableException {
        if (!flingActive) {
            sickTicks++;
            sickClicks += clicks;
            final int phase1 = getSickPhase1Ticks();
            final int phase2 = getSickPhase2Ticks();
            ensureSickImagesLoaded();
            if (sickTicks <= phase1) {
                sickPhase = 1;
                // Slight queasy tremble.
                driftTo(Math.random() * 2.0 - 1.0, Math.random() * 2.0 - 1.0);
                setSickImage(sickKey1);
            } else if (sickTicks <= phase1 + phase2) {
                sickPhase = 2;
                // Faster, harder shakes.
                driftTo(Math.random() * 6.0 - 3.0, Math.random() * 4.0 - 2.0);
                setSickImage(sickKey2);
            } else if (sickClicks >= getSickBurpClicks() || sickExtraTicks >= phase2) {
                // The burp needs its clicks; short one extra phase-2 worth of
                // heaving first (failsafe so a clickless swallow still ends).
                launchFling();
                return;
            } else {
                sickExtraTicks++;
                sickPhase = 2;
                // Heave harder the longer the burp is held in.
                final double heave = 1.0 + sickExtraTicks / (double) Math.max(1, phase2);
                driftTo((Math.random() * 6.0 - 3.0) * heave, (Math.random() * 4.0 - 2.0) * heave);
                setSickImage(sickKey2);
            }
            clampAnchorToScreen();
            // Still pinned while sick.
            final Point hands = getGraspPoint();
            robot.mouseMove(hands.x, hands.y);
            reassertCursorHidden();
            return;
        }

        flingTicks++;
        // Nigel rides out his recoil while the cursor flies.
        if (Math.abs(recoilVX) > 0.5 || Math.abs(recoilVY) > 0.5) {
            getMascot().getAnchor().translate((int) Math.round(recoilVX), (int) Math.round(recoilVY));
            recoilVX *= 0.75;
            recoilVY *= 0.75;
            clampAnchorToScreen();
        }
        flingVY += getSpitGravity();
        flingX += flingVX;
        flingY += flingVY;
        final double bounce = getSpitBounce();
        final Area screen = getEnvironment().getScreen();
        if (flingX < screen.getLeft()) {
            flingX = screen.getLeft();
            flingVX = -flingVX * bounce;
        } else if (flingX > screen.getRight()) {
            flingX = screen.getRight();
            flingVX = -flingVX * bounce;
        }
        if (flingY < screen.getTop()) {
            flingY = screen.getTop();
            flingVY = -flingVY * bounce;
        }
        robot.mouseMove((int) Math.round(flingX), (int) Math.round(flingY));
        reassertCursorHidden();
        // Drool trail along the flight path.
        if (flingTicks % 2 == 0) {
            spawnDroplets(flingX, flingY, 1.0);
        }
        // Burp recover starts mid-flight (after a short lead-in), so Nigel
        // is already coming down while the cursor still flies. The handoff
        // itself stays gated on landing inside tickRecover.
        if (!recoverActive && flingTicks >= RECOVER_LEAD_TICKS) {
            log.info("Burp recover starting mid-flight");
            recoverActive = true;
            recoverTicks = 0;
        }
        if (recoverActive) {
            tickRecover();
        }
        // Burp frame from the launch stays put; don't paint Sicken2 back over it.

        final Area workArea = getEnvironment().getWorkArea();
        final double speed = Math.sqrt(flingVX * flingVX + flingVY * flingVY);
        if (flingY >= workArea.getBottom() - 2 && flingBounces > 0) {
            // Mega-burp: bounce off the floor instead of landing.
            flingY = workArea.getBottom() - 2;
            flingVY = -Math.abs(flingVY) * bounce;
            flingBounces--;
            spawnDroplets(flingX, flingY, 2.0);
        } else if (flingY >= workArea.getBottom() - 2 || speed < 2.0 || flingTicks > 300) {
            log.info("Cursor landed after fling at ({}, {}), starting burp recover", (int) flingX, (int) flingY);
            // Hand the cursor back now; Nigel floats down gently instead of falling.
            restoreCursor();
            flingActive = false;
            // Clear the sick state or the router keeps sending ticks to tickSickFling,
            // which instantly relaunches (sickTicks already past both phases) and
            // tickRecover never runs — the endless burp loop.
            sickPhase = 0;
            sickTicks = 0;
            sickClicks = 0;
            sickExtraTicks = 0;
            if (!recoverActive) {
                recoverActive = true;
                recoverTicks = 0;
            }
            // Stay untouchable through the recover: no pickup, no menu.
            getMascot().setGrasping(true);
        }
    }

    private void launchFling() throws VariableException {
        // Clicks during the sick window power the fling, capped at 1+cap.
        // Rapid clicking earns a mega-burp: more power plus floor bounces,
        // so the cursor pinballs instead of landing straight away.
        final double power = 1.0 + Math.min(getSickPowerCap(), sickClicks * getSickClickPower());
        final int required = Math.max(1, getSickBurpClicks());
        flingBounces = Math.min(6, sickClicks / required);
        final double dir = getMascot().isLookRight() ? 1.0 : -1.0;
        final Point hands = getGraspPoint();
        flingX = hands.x;
        flingY = hands.y;
        flingVX = dir * getSpitSpeedX() * power + (Math.random() * 4.0 - 2.0);
        flingVY = -getSpitSpeedY() * power;
        flingTicks = 0;
        flingActive = true;
        // Recoil: a visible shove opposite the launch, decaying over the flight
        // (an instant teleport reads as a glitch, not a kick). Scales with power.
        recoilVX = -dir * 6.0 * power;
        recoilVY = 2.0 * power;
        ensureBurpImageLoaded();
        if (burpKey != null && ImagePairs.contains(burpKey)) {
            getMascot().setImage(ImagePairs.get(burpKey).getImage(getMascot().isLookRight()));
        }
        spawnDroplets(flingX, flingY, power);
        com.group_finity.mascot.sound.NigelSounds.playBurp(power);
        com.group_finity.mascot.sound.NigelSounds.playLaunch();
        log.info("Spit fling launched: power={}, bounces={}, v=({},{})", power, flingBounces, flingVX, flingVY);
    }

    /**
     * Burp recover: Nigel floats back down with a sway (same gentle descent
     * as the swallow sink) showing the burp sprite, then hands off to
     * StandUp so the Fall animation never plays. Starts mid-flight after a
     * short lead-in, but the handoff only fires once the fling is over —
     * never strand the cursor mid-air by ending the grasp early.
     */
    private void tickRecover() throws LostGroundException, VariableException {
        recoverTicks++;
        ensureBurpImageLoaded();
        final boolean grounded = getEnvironment().getFloor().isOn(getMascot().getAnchor());
        if (!grounded) {
            final int sway = (int) Math.round(Math.sin(getTime() * 0.15) * 2.0);
            getMascot().getAnchor().translate(sway, 3);
            clampAnchorToScreen();
            if (burpAftermathKey != null && ImagePairs.contains(burpAftermathKey)) {
                getMascot().setImage(ImagePairs.get(burpAftermathKey).getImage(getMascot().isLookRight()));
            }
            return;
        }
        // Landed fast, or still flying: hold the aftermath frame. The handoff
        // waits for both touchdown and the minimum beat, so a long flight
        // can never end the grasp (and strand the hidden cursor) early.
        if (flingActive || recoverTicks < RECOVER_MIN_TICKS) {
            if (burpAftermathKey != null && ImagePairs.contains(burpAftermathKey)) {
                getMascot().setImage(ImagePairs.get(burpAftermathKey).getImage(getMascot().isLookRight()));
            }
            return;
        }
        log.info("Burp recover complete, handing off to StandUp");
        try {
            final com.group_finity.mascot.behavior.Behavior standUp = Main.getInstance()
                    .getConfiguration(getMascot().getImageSet()).buildBehavior("StandUp", getMascot());
            getMascot().setBehavior(standUp);
        } catch (final com.group_finity.mascot.config.BehaviorInstantiationException
                | com.group_finity.mascot.behavior.BehaviorExecutionException e) {
            log.warn("StandUp handoff failed after burp recover, falling back to Fall", e);
            getMascot().setGrasping(false);
            throw new LostGroundException("Recovered from the spit");
        }
    }

    private void ensureBurpImageLoaded() {
        if (burpImageLoaded) {
            return;
        }
        try {
            final double scaling = Main.getInstance().getSettings().scaling;
            final Filter filter = Main.getInstance().getSettings().filter;
            final double opacity = Main.getInstance().getSettings().opacity;
            final String imageSet = getMascot() != null && getMascot().getImageSet() != null
                    ? getMascot().getImageSet() : "NigelShimeji";
            burpKey = ImagePairs.load(Path.of(imageSet, "burpcursor.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(burpKey, imageSet);
            burpAftermathKey = ImagePairs.load(Path.of(imageSet, "burpcursoraftermath.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(burpAftermathKey, imageSet);
            burpImageLoaded = true;
        } catch (final IOException | RuntimeException e) {
            log.warn("Failed to load burp image for GraspMouse", e);
        }
    }

    private void ensureSickImagesLoaded() {
        if (sickImagesLoaded) {
            return;
        }
        try {
            final double scaling = Main.getInstance().getSettings().scaling;
            final Filter filter = Main.getInstance().getSettings().filter;
            final double opacity = Main.getInstance().getSettings().opacity;
            final String imageSet = getMascot() != null && getMascot().getImageSet() != null
                    ? getMascot().getImageSet() : "NigelShimeji";
            sickKey1 = ImagePairs.load(Path.of(imageSet, "Sicken1.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(sickKey1, imageSet);
            sickKey2 = ImagePairs.load(Path.of(imageSet, "Sicken2.png"), null, 96, 200, scaling, filter, opacity);
            ImagePairs.addUsage(sickKey2, imageSet);
            sickImagesLoaded = true;
        } catch (final IOException | RuntimeException e) {
            log.warn("Failed to load sick images for GraspMouse", e);
        }
    }

    private void setSickImage(final String key) {
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

    private double getSwallowChance() throws VariableException {
        return eval(getSchema().getString(PARAMETER_SWALLOW_CHANCE), Number.class, DEFAULT_SWALLOW_CHANCE).doubleValue();
    }

    private int getSwallowClickCount() throws VariableException {
        return eval(getSchema().getString(PARAMETER_SWALLOW_CLICK_COUNT), Number.class, DEFAULT_SWALLOW_CLICK_COUNT).intValue();
    }

    private int getSwallowClickWindow() throws VariableException {
        return eval(getSchema().getString(PARAMETER_SWALLOW_CLICK_WINDOW), Number.class, DEFAULT_SWALLOW_CLICK_WINDOW).intValue();
    }

    private int getSickPhase1Ticks() throws VariableException {
        return eval(getSchema().getString(PARAMETER_SICK_PHASE1_TICKS), Number.class, DEFAULT_SICK_PHASE1_TICKS).intValue();
    }

    private int getSickPhase2Ticks() throws VariableException {
        return eval(getSchema().getString(PARAMETER_SICK_PHASE2_TICKS), Number.class, DEFAULT_SICK_PHASE2_TICKS).intValue();
    }

    private double getSpitSpeedX() throws VariableException {
        return eval(getSchema().getString(PARAMETER_SPIT_SPEED_X), Number.class, DEFAULT_SPIT_SPEED_X).doubleValue();
    }

    private double getSpitSpeedY() throws VariableException {
        return eval(getSchema().getString(PARAMETER_SPIT_SPEED_Y), Number.class, DEFAULT_SPIT_SPEED_Y).doubleValue();
    }

    private double getSpitGravity() throws VariableException {
        return eval(getSchema().getString(PARAMETER_SPIT_GRAVITY), Number.class, DEFAULT_SPIT_GRAVITY).doubleValue();
    }

    private double getSpitBounce() throws VariableException {
        return eval(getSchema().getString(PARAMETER_SPIT_BOUNCE), Number.class, DEFAULT_SPIT_BOUNCE).doubleValue();
    }

    private double getSickClickPower() throws VariableException {
        return eval(getSchema().getString(PARAMETER_SICK_CLICK_POWER), Number.class, DEFAULT_SICK_CLICK_POWER).doubleValue();
    }

    private int getSickBurpClicks() throws VariableException {
        return eval(getSchema().getString(PARAMETER_SICK_BURP_CLICKS), Number.class, DEFAULT_SICK_BURP_CLICKS).intValue();
    }

    private double getSickPowerCap() throws VariableException {
        return eval(getSchema().getString(PARAMETER_SICK_POWER_CAP), Number.class, DEFAULT_SICK_POWER_CAP).doubleValue();
    }

    private double getMaxStruggleBonus() throws VariableException {
        return eval(getSchema().getString(PARAMETER_MAX_STRUGGLE_BONUS), Number.class, DEFAULT_MAX_STRUGGLE_BONUS).doubleValue();
    }

    private double getMaxCatchableCursorSize() throws VariableException {
        return eval(getSchema().getString(PARAMETER_MAX_CATCHABLE_CURSOR_SIZE), Number.class, DEFAULT_MAX_CATCHABLE_CURSOR_SIZE).doubleValue();
    }

    private boolean isCursorTooBig() throws VariableException {
        return getEnvironment().getCursorSizePixels() > getMaxCatchableCursorSize();
    }

    /**
     * Swallow difficulty multiplier from cursor size: 1x at normal size
     * (32px and under), up to 4x at the refusal threshold. Scales the click
     * requirement, the click window, and the bleed-off together, so big
     * cursors take longer but stay feasible.
     */
    private double getSwallowSizeMultiplier() {
        final int size = getEnvironment().getCursorSizePixels();
        if (size <= 32) {
            return 1.0;
        }
        return 1.0 + 3.0 * Math.min(1.0, (size - 32) / 64.0);
    }

    /**
     * Swallow click requirement scaled by cursor size: normal cursors need
     * the base count, bigger ones up to quadruple it. He has to work the
     * big ones down before they fit.
     */
    private int getSwallowClicksRequired() throws VariableException {
        return (int) Math.round(getSwallowClickCount() * getSwallowSizeMultiplier());
    }

    /**
     * Stuffed tier: cursors over 64px show the big-belly sprites while
     * swallowed, when the image set provides them.
     */
    private boolean isStuffedSwallow() {
        return getEnvironment().getCursorSizePixels() > 64;
    }
}
