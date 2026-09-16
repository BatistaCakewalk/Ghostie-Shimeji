package com.group_finity.mascot.action;

import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.environment.Area;
import com.group_finity.mascot.script.VariableException;
import com.group_finity.mascot.script.VariableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

/**
 * Telekinesis: Nigel grabs a window with his mind, floats it around in a
 * slow drift, then sets it back where he found it. A purple glow with a
 * light pink outline marks the held window.
 */
public class Telekinesis extends ActionBase {
    private static final Logger log = LoggerFactory.getLogger(Telekinesis.class);

    private static final String PARAMETER_RADIUS_X = "TeleRadiusX";
    private static final double DEFAULT_RADIUS_X = 60.0;

    private static final String PARAMETER_RADIUS_Y = "TeleRadiusY";
    private static final double DEFAULT_RADIUS_Y = 40.0;

    private static final String PARAMETER_LIFT = "TeleLift";
    private static final double DEFAULT_LIFT = 30.0;

    private static final String PARAMETER_RETURN_TICKS = "TeleReturnTicks";
    private static final int DEFAULT_RETURN_TICKS = 60;

    private static final String PARAMETER_DURATION = "Duration";

    private static final String PARAMETER_TELE_MODE = "TeleMode";

    /**
     * Windows are kept this far inside the screen so edge-triggered managers
     * (Aero Snap, FancyZones) never grab them mid-flight and stutter.
     */
    private static final int EDGE_MARGIN = 16;

    private double startX;
    private double startY;
    private double curX;
    private double curY;
    private int winW;
    private int winH;
    private int lastSentX;
    private int lastSentY;

    private static final double PULL_STEP = 28.0;
    private static final double PULL_ARRIVE = 40.0;
    private static final int PULL_RAMP_TICKS = 300;
    private static final int PULL_RED_TICKS = 150;

    /**
     * Hands height above the anchor, mirroring GraspMouse's GraspOffsetY, so
     * the reeled cursor arrives at his hands instead of his ghost tail.
     */
    private static final double PULL_OFFSET_Y = -96.0;

    private boolean pullMouse;
    private Robot robot;
    private GlowOverlay cursorGlow;
    private int pullTicks;
    private boolean warnedForcing;
    private int shakeBaseX;
    private int shakeBaseY;
    private boolean shaking;
    private String teleFullKey;
    private boolean teleFullLoaded;

    /**
     * Chance to lift a fellow Nigel instead of a window or the cursor.
     * Victims show Fall.png until a real sprite exists.
     */
    private static final double NIGEL_CHANCE = 0.1;

    private Area target;

    private Mascot victim;

    private boolean victimWasPaused;

    private String victimFallKey;
    private String victimFallSet;

    private GlowOverlay glow;

    /**
     * Live holds by mascot. When a behavior swap abandons a lift mid-flight
     * (e.g. the user grabs Nigel), the hold is converted into a physics
     * window-fall instead of freezing the window and leaking the glow.
     */
    private static final Map<Mascot, Telekinesis> HOLDS =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /**
     * Victims currently held, so a second attacker can never take a paused
     * or already-lifted Nigel and freeze it permanently on interleaved release.
     */
    private static final java.util.Set<Mascot> HELD_VICTIMS =
            Collections.synchronizedSet(java.util.Collections.newSetFromMap(new IdentityHashMap<>()));

    private volatile boolean live;

    private double fallVY;

    public Telekinesis(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    /**
     * Called from {@link Mascot#setBehavior} and {@link Mascot#dispose}: if
     * this mascot was mid-lift, drop the window with physics instead of
     * freezing it and leaking the glow.
     *
     * @param mascot the mascot whose behavior is being swapped or disposed
     */
    public static void cancelFor(final Mascot mascot) {
        final Telekinesis action;
        synchronized (HOLDS) {
            action = HOLDS.remove(mascot);
        }
        if (action == null) {
            return;
        }
        action.releaseVictim();
        if (action.live && action.target != null) {
            action.beginFall();
        } else {
            action.disposeGlows();
        }
    }

    /**
     * Hands the victim back to its own engine: unpauses (restoring prior
     * paused state) so it drops and recovers by itself. Safe to call with no
     * victim.
     */
    private void releaseVictim() {
        if (victim == null) {
            return;
        }
        try {
            victim.setPaused(victimWasPaused);
        } catch (final RuntimeException ignored) {
        }
        HELD_VICTIMS.remove(victim);
        victim = null;
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);

        // Clear any stale hold (no fall: it never really started).
        cancelFor(mascot);

        // Either a fellow Nigel, a window lift or a mouse reel, never more
        // than one. A TeleMode reference parameter forces one side;
        // otherwise roll it all.
        victim = null;
        final String mode = getTeleMode();
        if ("mouse".equalsIgnoreCase(mode)) {
            pullMouse = true;
        } else if ("window".equalsIgnoreCase(mode)) {
            pullMouse = false;
        } else if ("nigel".equalsIgnoreCase(mode)) {
            pullMouse = false;
            victim = pickVictim(mascot);
            if (victim != null) {
                initVictimMode(mascot);
                return;
            }
            log.info("Telekinesis init: forced Nigel mode but no victim, skipping");
            return;
        } else {
            pullMouse = false;
            final double roll = Math.random();
            if (roll < NIGEL_CHANCE) {
                victim = pickVictim(mascot);
            }
            if (victim == null) {
                pullMouse = Math.random() < 0.5;
            }
        }
        robot = null;
        cursorGlow = null;
        if (pullMouse) {
            try {
                robot = new Robot();
            } catch (final AWTException | SecurityException e) {
                log.warn("Could not create Robot for telekinesis mouse pull", e);
                pullMouse = false;
            }
        }
        if (pullMouse) {
            target = null;
            cursorGlow = new GlowOverlay(true);
            pullTicks = 0;
            warnedForcing = false;
            shakeBaseX = mascot.getAnchor().x;
            shakeBaseY = mascot.getAnchor().y;
            shaking = false;
            live = true;
            synchronized (HOLDS) {
                HOLDS.put(mascot, this);
            }
            log.info("Telekinesis init: reeling the cursor in");
            return;
        }
        if (victim != null) {
            initVictimMode(mascot);
            return;
        }


        // Yoink any grabbable window (fullscreen/maximized excluded by the
        // environment), not just the active one.
        final List<Area> candidates = new java.util.ArrayList<>();
        for (final Area area : getEnvironment().getGrabbableWindows()) {
            if (area.isVisible()) {
                candidates.add(area);
            }
        }
        if (candidates.isEmpty()) {
            target = null;
            log.info("Telekinesis init: no grabbable window, skipping");
            return;
        }
        target = candidates.get((int) (Math.random() * candidates.size()));
        getEnvironment().markWindowGrabbed(target);
        startX = target.getLeft();
        startY = target.getTop();
        curX = startX;
        curY = startY;
        winW = Math.max(1, target.getWidth());
        winH = Math.max(1, target.getHeight());
        lastSentX = Integer.MIN_VALUE;
        lastSentY = Integer.MIN_VALUE;
        glow = new GlowOverlay(false);
        live = true;
        synchronized (HOLDS) {
            HOLDS.put(mascot, this);
        }
        faceWindow();
        log.info("Telekinesis init: holding window at ({}, {}) size {}x{}",
                (int) startX, (int) startY, winW, winH);
    }

    @Override
    public boolean isDraggable() throws VariableException {
        // Window lifts can be grabbed out of (dropping the window);
        // mouse reels cannot be cancelled by grabbing.
        return !pullMouse;
    }

    @Override
    public boolean hasNext() throws VariableException {
        if (target == null && !pullMouse && victim == null) {
            return false;
        }
        final boolean more = super.hasNext();
        if (!more) {
            endHold();
        }
        return more;
    }

    /**
     * Picks another mascot to lift. Skips self, anyone holding the mouse,
     * and the mouse owner, so ongoing grasps are never disturbed.
     */
    private void initVictimMode(final Mascot mascot) {
        target = null;
        HELD_VICTIMS.add(victim);
        // Freeze the victim's own ticking: we become the sole writer of its
        // anchor and image, so placement and aura stay deterministic.
        victimWasPaused = victim.isPaused();
        victim.setPaused(true);
        startX = victim.getAnchor().x;
        startY = victim.getAnchor().y;
        curX = startX;
        curY = startY;
        glow = new GlowOverlay(false);
        live = true;
        synchronized (HOLDS) {
            HOLDS.put(mascot, this);
        }
        log.info("Telekinesis init: lifting fellow mascot {}", victim);
    }

    private Mascot pickVictim(final Mascot mascot) {
        try {
            if (mascot.getManager() == null) {
                return null;
            }
            final List<Mascot> candidates = new java.util.ArrayList<>();
            for (final Mascot other : mascot.getManager().getMascots()) {
                if (other != mascot && !other.isGrasping() && Mascot.getMouseOwner() != other
                        && !other.isPaused() && !HELD_VICTIMS.contains(other)) {
                    candidates.add(other);
                }
            }
            if (candidates.isEmpty()) {
                return null;
            }
            return candidates.get((int) (Math.random() * candidates.size()));
        } catch (final RuntimeException e) {
            log.warn("Could not pick a victim mascot", e);
            return null;
        }
    }

    private void ensureVictimFallImageLoaded(final Mascot target) {
        final String imageSet = target.getImageSet() != null ? target.getImageSet() : "NigelShimeji";
        if (victimFallKey != null && imageSet.equals(victimFallSet)
                && com.group_finity.mascot.image.ImagePairs.contains(victimFallKey)) {
            return;
        }
        try {
            final double scaling = com.group_finity.mascot.Main.getInstance().getSettings().scaling;
            final com.group_finity.mascot.image.Filter filter =
                    com.group_finity.mascot.Main.getInstance().getSettings().filter;
            final double opacity = com.group_finity.mascot.Main.getInstance().getSettings().opacity;
            victimFallKey = com.group_finity.mascot.image.ImagePairs.load(
                    java.nio.file.Path.of(imageSet, "fall.png"), null, 96, 200,
                    scaling, filter, opacity);
            victimFallSet = imageSet;
            com.group_finity.mascot.image.ImagePairs.addUsage(victimFallKey, imageSet);
        } catch (final java.io.IOException | RuntimeException e) {
            log.warn("Failed to load victim fall image for Telekinesis", e);
        }
    }

    private void endHold() {
        live = false;
        synchronized (HOLDS) {
            HOLDS.remove(getMascot());
        }
        // Restore the exact pre-shake anchor so the pull never ends airborne.
        if (shaking) {
            shaking = false;
            getMascot().getAnchor().setLocation(shakeBaseX, shakeBaseY);
        }
        releaseVictim();
        disposeGlows();
    }

    /**
     * Rattles Nigel's body in place while reeling: a slow tremble after a
     * bit, violent shaking at full strength. Absolute positioning around the
     * recorded base, so screen clamping can never desync into drift.
     */
    private void shakeBody() {
        if (pullTicks <= 90) {
            return;
        }
        shaking = true;
        final double progress = Math.min(1.0, pullTicks / (double) PULL_RAMP_TICKS);
        final double amplitude = 1.0 + 5.0 * progress;
        final int nextX = shakeBaseX + (int) Math.round(Math.sin(pullTicks * 0.6) * amplitude);
        final int nextY = shakeBaseY + (int) Math.round(Math.cos(pullTicks * 0.8) * amplitude * 0.7);
        final Area screen = getEnvironment().getScreen();
        final Point anchor = getMascot().getAnchor();
        anchor.setLocation(
                Math.max(screen.getLeft(), Math.min(screen.getRight(), nextX)),
                Math.max(screen.getTop(), Math.min(screen.getBottom(), nextY)));
    }

    /**
     * Drops the held window with gravity. Self-driven on a Swing timer so it
     * keeps falling even though this action is no longer ticking.
     */
    private void beginFall() {
        live = false;
        fallVY = 0.0;
        // The drop is unmarked: glow goes away the moment the hold breaks.
        disposeGlows();
        log.info("Telekinesis cancelled: dropping window at ({}, {})", (int) curX, (int) curY);
        final Timer timer = new Timer(40, null);
        timer.addActionListener(event -> {
            try {
                if (!getEnvironment().isWindowOpen(target)) {
                    finishFall(timer);
                    return;
                }
                fallVY = Math.min(30.0, fallVY + 2.0);
                curY += fallVY;
                final Area screen = getEnvironment().getScreen();
                final double floorY = screen.getBottom() - winH;
                if (curY >= floorY) {
                    curY = Math.max(screen.getTop(), floorY);
                    finishFall(timer);
                    return;
                }
                getEnvironment().moveWindow(target, (int) Math.round(curX), (int) Math.round(curY));
            } catch (final RuntimeException e) {
                finishFall(timer);
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    private void finishFall(final Timer timer) {
        try {
            timer.stop();
        } catch (final RuntimeException ignored) {
        }
        disposeGlows();
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        try {
            if (getEnvironment().isFullscreen() || getEnvironment().isMouseLocked()) {
                throw new LostGroundException("Fullscreen/mouse-lock active");
            }
            // Mouse-reel mode: no window involved at all.
            if (pullMouse) {
                pullCursorTowardsMascot();
                shakeBody();
                getAnimation().apply(getMascot(), getTime());
                // Full strength pose once the ramp completes.
                if (pullTicks >= PULL_RAMP_TICKS) {
                    ensureTeleFullImageLoaded();
                    if (teleFullKey != null
                            && com.group_finity.mascot.image.ImagePairs.contains(teleFullKey)) {
                        getMascot().setImage(com.group_finity.mascot.image.ImagePairs.get(teleFullKey)
                                .getImage(getMascot().isLookRight()));
                    }
                }
                return;
            }
            // Fellow-Nigel mode: snap the victim's anchor along the same
            // drift the windows ride. It keeps ticking underneath, so its
            // own engine drops and recovers it the moment we let go.
            if (victim != null) {
                if (victim.isDragging()) {
                    log.info("Telekinesis victim grabbed by user, letting go");
                    throw new LostGroundException("Victim grabbed");
                }
                getMascot().setLookRight(getMascot().getAnchor().x < victim.getAnchor().x);

                final int elapsed = getTime();
                final int total = eval(getSchema().getString(PARAMETER_DURATION),
                        Number.class, Integer.MAX_VALUE).intValue();
                final int returnTicks = Math.max(1, getReturnTicks());
                double targetX;
                double targetY;
                if (elapsed < total - returnTicks) {
                    targetX = startX + Math.sin(elapsed * 0.05) * getRadiusX();
                    targetY = startY - getLift() + Math.sin(elapsed * 0.07) * getRadiusY();
                } else {
                    final int remaining = Math.max(1, total - elapsed);
                    targetX = curX + (startX - curX) / remaining;
                    targetY = curY + (startY - curY) / remaining;
                }

                final Area screen = getEnvironment().getScreen();
                targetX = clampInside(targetX, screen.getLeft() + EDGE_MARGIN, screen.getRight() - 192 - EDGE_MARGIN,
                        screen.getLeft(), screen.getRight() - 192);
                targetY = clampInside(targetY, screen.getTop() + EDGE_MARGIN, screen.getBottom() - 200 - EDGE_MARGIN,
                        screen.getTop(), screen.getBottom() - 200);
                curX = targetX;
                curY = targetY;

                victim.getAnchor().setLocation((int) Math.round(targetX), (int) Math.round(targetY));
                // Paused victims never reposition their own window, so drive
                // it here or the body floats free of the aura.
                try {
                    final java.awt.Component victimWindow = victim.getWindowComponent();
                    if (victimWindow != null) {
                        final Rectangle windowBounds = victim.getBounds();
                        if (victimWindow.getX() != windowBounds.x || victimWindow.getY() != windowBounds.y
                                || victimWindow.getWidth() != windowBounds.width
                                || victimWindow.getHeight() != windowBounds.height) {
                            final Rectangle frozen = new Rectangle(windowBounds);
                            javax.swing.SwingUtilities.invokeLater(() -> victimWindow.setBounds(frozen));
                        }
                    }
                } catch (final RuntimeException ignored) {
                }
                ensureVictimFallImageLoaded(victim);
                if (victimFallKey != null
                        && com.group_finity.mascot.image.ImagePairs.contains(victimFallKey)) {
                    victim.setImage(com.group_finity.mascot.image.ImagePairs.get(victimFallKey)
                            .getImage(victim.isLookRight()));
                }
                if (glow != null) {
                    glow.showAt(tightFrame(victim), getTime(), 0);
                }
                getAnimation().apply(getMascot(), getTime());
                return;
            }
            if (target == null) {
                throw new LostGroundException("No window to lift");
            }
            if (!getEnvironment().isWindowOpen(target)) {
                log.info("Telekinesis cancelled: window closed mid-lift");
                throw new LostGroundException("Window closed");
            }
            if (getEnvironment().isWindowMinimized(target)) {
                log.info("Telekinesis cancelled: window minimized mid-lift");
                throw new LostGroundException("Window minimized");
            }
            faceWindow();

            final int elapsed = getTime();
            final int total = eval(getSchema().getString(PARAMETER_DURATION),
                    Number.class, Integer.MAX_VALUE).intValue();
            final int returnTicks = Math.max(1, getReturnTicks());

            double targetX;
            double targetY;
            if (elapsed < total - returnTicks) {
                targetX = startX + Math.sin(elapsed * 0.05) * getRadiusX();
                targetY = startY - getLift() + Math.sin(elapsed * 0.07) * getRadiusY();
            } else {
                // Ease it back where he found it.
                final int remaining = Math.max(1, total - elapsed);
                targetX = curX + (startX - curX) / remaining;
                targetY = curY + (startY - curY) / remaining;
            }

            final Area screen = getEnvironment().getScreen();
            targetX = clampInside(targetX, screen.getLeft() + EDGE_MARGIN, screen.getRight() - winW - EDGE_MARGIN,
                    screen.getLeft(), screen.getRight() - winW);
            targetY = clampInside(targetY, screen.getTop() + EDGE_MARGIN, screen.getBottom() - winH - EDGE_MARGIN,
                    screen.getTop(), screen.getBottom() - winH);
            curX = targetX;
            curY = targetY;

            final int sendX = (int) Math.round(targetX);
            final int sendY = (int) Math.round(targetY);
            // Skip redundant moves: re-sending an identical position every
            // tick makes edge managers fight us and stutter.
            if (sendX != lastSentX || sendY != lastSentY) {
                getEnvironment().moveWindow(target, sendX, sendY);
                lastSentX = sendX;
                lastSentY = sendY;
            }
            if (glow != null) {
                glow.showAt(new Rectangle(sendX - 10, sendY - 10, winW + 20, winH + 20), getTime(),
                        getEnvironment().getNativeWindowHandle(target));
            }
            getAnimation().apply(getMascot(), getTime());
        } catch (final LostGroundException | VariableException | RuntimeException e) {
            endHold();
            throw e;
        }
    }

    private static double clampInside(final double value, final double preferredMin, final double preferredMax,
            final double fallbackMin, final double fallbackMax) {
        if (preferredMin <= preferredMax) {
            return Math.max(preferredMin, Math.min(preferredMax, value));
        }
        return Math.max(fallbackMin, Math.min(Math.max(fallbackMin, fallbackMax), value));
    }

    private void ensureTeleFullImageLoaded() {
        if (teleFullLoaded) {
            return;
        }
        try {
            final double scaling = com.group_finity.mascot.Main.getInstance().getSettings().scaling;
            final com.group_finity.mascot.image.Filter filter =
                    com.group_finity.mascot.Main.getInstance().getSettings().filter;
            final double opacity = com.group_finity.mascot.Main.getInstance().getSettings().opacity;
            final String imageSet = getMascot() != null && getMascot().getImageSet() != null
                    ? getMascot().getImageSet() : "NigelShimeji";
            teleFullKey = com.group_finity.mascot.image.ImagePairs.load(
                    java.nio.file.Path.of(imageSet, "telefullstrength.png"), null, 96, 200,
                    scaling, filter, opacity);
            com.group_finity.mascot.image.ImagePairs.addUsage(teleFullKey, imageSet);
            teleFullLoaded = true;
        } catch (final java.io.IOException | RuntimeException e) {
            log.warn("Failed to load telefullstrength image for Telekinesis", e);
        }
    }

    /**
     * Tight opaque-pixel bounds per mascot image, so victim frames hug the
     * body instead of the whole 192x192 canvas.
     */
    private static final java.util.Map<com.group_finity.mascot.image.MascotImage, Rectangle>
            TIGHT_BOUNDS_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static Rectangle tightBounds(final com.group_finity.mascot.image.MascotImage image) {
        if (image == null || image.getImage() == null) {
            return null;
        }
        final boolean miss = !TIGHT_BOUNDS_CACHE.containsKey(image);
        final Rectangle box = TIGHT_BOUNDS_CACHE.computeIfAbsent(image, img -> {
            final java.awt.image.BufferedImage bitmap = img.getImage();
            int minX = bitmap.getWidth();
            int minY = bitmap.getHeight();
            int maxX = -1;
            int maxY = -1;
            for (int y = 0; y < bitmap.getHeight(); y++) {
                for (int x = 0; x < bitmap.getWidth(); x++) {
                    if ((((bitmap.getRGB(x, y) >>> 24) & 0xff) > 16)) {
                        if (x < minX) minX = x;
                        if (y < minY) minY = y;
                        if (x > maxX) maxX = x;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX < 0) {
                return new Rectangle(0, 0, bitmap.getWidth(), bitmap.getHeight());
            }
            return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
        });
        if (miss) {
            final java.awt.image.BufferedImage bitmap = image.getImage();
            log.info("Telekinesis tight frame: {}x{} -> box ({}, {}) {}x{}",
                    bitmap.getWidth(), bitmap.getHeight(), box.x, box.y, box.width, box.height);
        }
        return box;
    }

    /**
     * Frames the victim's actual body: the tight opaque box of its current
     * image, scaled proportionally onto {@link Mascot#getBounds()} (the
     * engine's own screen truth, valid under any DPI scaling or anchor).
     */
    private static Rectangle tightFrame(final Mascot victim) {
        final Rectangle bounds = victim.getBounds();
        final com.group_finity.mascot.image.MascotImage image = victim.getImage();
        final Rectangle tight = tightBounds(image);
        final Rectangle frame;
        if (tight != null && image != null && image.getImage() != null
                && image.getImage().getWidth() > 0 && image.getImage().getHeight() > 0) {
            final double scaleX = (double) bounds.width / image.getImage().getWidth();
            final double scaleY = (double) bounds.height / image.getImage().getHeight();
            frame = new Rectangle(
                    bounds.x + (int) Math.round(tight.x * scaleX),
                    bounds.y + (int) Math.round(tight.y * scaleY),
                    (int) Math.round(tight.width * scaleX),
                    (int) Math.round(tight.height * scaleY));
        } else {
            frame = new Rectangle(bounds);
        }
        frame.grow(10, 10);
        return frame;
    }

    private void faceWindow() {
        if (target == null) {
            return;
        }
        final int centerX = target.getLeft() + target.getWidth() / 2;
        getMascot().setLookRight(getMascot().getAnchor().x < centerX);
    }

    /**
     * Reels the OS cursor toward Nigel's hands a step per tick, wrapped in
     * the tele glow. On arrival the reel ends and the normal catch sequence
     * (leap lands on the spot, grasp locks) takes over into the struggle.
     */
    private void pullCursorTowardsMascot() throws VariableException {
        if (!pullMouse || robot == null) {
            return;
        }
        final Point raw;
        try {
            final PointerInfo info = MouseInfo.getPointerInfo();
            raw = info == null ? null : info.getLocation();
        } catch (final SecurityException e) {
            return;
        }
        if (raw == null) {
            return;
        }
        final Point anchor = getMascot().getAnchor();
        final double handsX = anchor.x;
        final double handsY = anchor.y + PULL_OFFSET_Y;
        // Deadband: flipping facing every tick when the cursor sits on top of
        // him thrashes the image pipeline and stalls the grab handoff.
        if (Math.abs(anchor.x - raw.x) > 4) {
            getMascot().setLookRight(anchor.x < raw.x);
        }
        final double dx = handsX - raw.x;
        final double dy = handsY - raw.y;
        final double distance = Math.sqrt(dx * dx + dy * dy);
        // Redness runs on the clock: fully red ~6s into the pull.
        pullTicks++;
        final double heat = Math.min(1.0, pullTicks / (double) PULL_RED_TICKS);
        if (!warnedForcing && heat >= 0.5) {
            warnedForcing = true;
            log.info("Nigel forcing the pull harder, heat={}", heat);
        }
        if (cursorGlow != null) {
            cursorGlow.showAt(new Rectangle(raw.x - 24, raw.y - 24, 48, 48), getTime(), 0, (float) heat);
        }
        // Arrival: the cursor overlaps Nigel's 192x192 sprite canvas
        // (anchor 96,200 sits 8px below it for the hover gap) or is at hands.
        final int relX = raw.x - anchor.x;
        final int relY = raw.y - anchor.y;
        final boolean onSprite = Math.abs(relX) <= 96 && relY <= 0 && relY >= -200;
        // Immediate handoff on contact: no minimum-show wait.
        if (onSprite || distance <= PULL_ARRIVE) {
            final boolean devour = pullTicks >= PULL_RAMP_TICKS;
            log.info("Telekinesis mouse pull arrived: raw=({}, {}), anchor=({}, {}), dist={}, ticks={}, {}",
                    raw.x, raw.y, anchor.x, anchor.y, distance, pullTicks,
                    devour ? "devouring straight into swallow" : "starting struggle");
            if (devour) getMascot().setDevourNext();
            endHold();
            try {
                final com.group_finity.mascot.behavior.Behavior catchMouse =
                        com.group_finity.mascot.Main.getInstance()
                                .getConfiguration(getMascot().getImageSet())
                                .buildBehavior("CatchMouse", getMascot());
                getMascot().setBehavior(catchMouse);
            } catch (final com.group_finity.mascot.config.BehaviorInstantiationException
                    | com.group_finity.mascot.behavior.BehaviorExecutionException e) {
                log.warn("CatchMouse handoff failed after telekinesis mouse pull", e);
            }
            return;
        }
        // Strength ramps with time held: starts buffed and triples over ~300 ticks.
        final double step = PULL_STEP * (1.0 + 2.0 * Math.min(1.0, pullTicks / (double) PULL_RAMP_TICKS));
        robot.mouseMove((int) Math.round(raw.x + dx / distance * step),
                (int) Math.round(raw.y + dy / distance * step));
    }

    private void disposeGlows() {
        if (glow != null) {
            try {
                glow.dispose();
            } catch (final RuntimeException e) {
                log.warn("Could not dispose telekinesis glow", e);
            }
            glow = null;
        }
        if (cursorGlow != null) {
            try {
                cursorGlow.dispose();
            } catch (final RuntimeException e) {
                log.warn("Could not dispose telekinesis cursor glow", e);
            }
            cursorGlow = null;
        }
    }

    private String getTeleMode() throws VariableException {
        return eval(getSchema().getString(PARAMETER_TELE_MODE), String.class, "");
    }

    private double getRadiusX() throws VariableException {
        return eval(getSchema().getString(PARAMETER_RADIUS_X), Number.class, DEFAULT_RADIUS_X).doubleValue();
    }

    private double getRadiusY() throws VariableException {
        return eval(getSchema().getString(PARAMETER_RADIUS_Y), Number.class, DEFAULT_RADIUS_Y).doubleValue();
    }

    private double getLift() throws VariableException {
        return eval(getSchema().getString(PARAMETER_LIFT), Number.class, DEFAULT_LIFT).doubleValue();
    }

    private int getReturnTicks() throws VariableException {
        return eval(getSchema().getString(PARAMETER_RETURN_TICKS), Number.class, DEFAULT_RETURN_TICKS).intValue();
    }

    /**
     * Borderless always-on-top outline around the held window: pulsing purple
     * glow strokes, a faint purple wash over the window, and a light pink
     * outline. Never focusable so it can't steal the window it is framing.
     *
     * Z-order is maintained by a 16 ms Swing timer that continuously restacks
     * the glow directly above the target window, so focus changes and taskbar
     * clicks can never bury it.
     */
    private static final class GlowOverlay {
        private JWindow window;
        private volatile int phase;
        private volatile float heat;
        private volatile boolean clickThrough;
        private volatile Rectangle latestBounds;
        private volatile long latestHandle;
        private Timer restackTimer;
        private volatile boolean visible;
        private volatile long lastRepaintNanos;
        private static final long REPAINT_MIN_NANOS = 100_000_000;

        /**
         * Builds a closed, organically wobbling border around a {@code w} by
         * {@code h} box (origin at 0,0). The perimeter is walked with outward
         * normals and offset by layered sines, so it reads as a living aura
         * rather than a rounded rectangle.
         *
         * @param w width of the box to frame
         * @param h height of the box to frame
         * @param wavePhase animation phase; advance it every frame to wobble
         * @param smallestSide smaller box dimension, used to tame the wobble
         * @return the closed aura path
         */
        private static java.awt.Shape wavyBorder(final int w, final int h, final double wavePhase,
                final int smallestSide) {
            final float radius = 18f;
            final float x0 = 0f;
            final float y0 = 0f;
            final float x1 = w;
            final float y1 = h;
            final double ampScale = Math.max(0.35, Math.min(1.0, smallestSide / 200.0));
            final double amp1 = 7.0 * ampScale;
            final double amp2 = 3.0 * ampScale;
            final double perimeter = 2.0 * (w + h);
            final double lambda1 = perimeter / 7.0;
            final double lambda2 = perimeter / 11.0;

            final java.util.List<double[]> points = new java.util.ArrayList<>();
            // Each entry: x, y, normalX, normalY, arcLength.
            final double[] length = {0.0};
            final double[] last = {0.0, 0.0};
            final boolean[] started = {false};
            final java.util.function.BiConsumer<double[], double[]> addPoint =
                    (point, normal) -> {
                        if (started[0]) {
                            final double dx = point[0] - last[0];
                            final double dy = point[1] - last[1];
                            length[0] += Math.sqrt(dx * dx + dy * dy);
                        } else {
                            started[0] = true;
                        }
                        last[0] = point[0];
                        last[1] = point[1];
                        final double s = length[0];
                        final double wobble = amp1 * Math.sin(2.0 * Math.PI * s / lambda1 + wavePhase)
                                + amp2 * Math.sin(2.0 * Math.PI * s / lambda2 - 1.7 * wavePhase);
                        points.add(new double[]{point[0] + normal[0] * wobble, point[1] + normal[1] * wobble});
                    };

            final int edgeSteps = 12;
            final int arcSteps = 10;
            for (int i = 0; i <= edgeSteps; i++) {
                final double t = (double) i / edgeSteps;
                addPoint.accept(new double[]{x0 + radius + t * (x1 - x0 - 2 * radius), y0}, new double[]{0, -1});
            }
            for (int i = 1; i <= arcSteps; i++) {
                final double a = -Math.PI / 2 + (double) i / arcSteps * Math.PI / 2;
                addPoint.accept(new double[]{x1 - radius + radius * Math.cos(a), y0 + radius + radius * Math.sin(a)},
                        new double[]{Math.cos(a), Math.sin(a)});
            }
            for (int i = 1; i <= edgeSteps; i++) {
                final double t = (double) i / edgeSteps;
                addPoint.accept(new double[]{x1, y0 + radius + t * (y1 - y0 - 2 * radius)}, new double[]{1, 0});
            }
            for (int i = 1; i <= arcSteps; i++) {
                final double a = (double) i / arcSteps * Math.PI / 2;
                addPoint.accept(new double[]{x1 - radius + radius * Math.cos(a), y1 - radius + radius * Math.sin(a)},
                        new double[]{Math.cos(a), Math.sin(a)});
            }
            for (int i = 1; i <= edgeSteps; i++) {
                final double t = (double) i / edgeSteps;
                addPoint.accept(new double[]{x1 - radius - t * (x1 - x0 - 2 * radius), y1}, new double[]{0, 1});
            }
            for (int i = 1; i <= arcSteps; i++) {
                final double a = Math.PI / 2 + (double) i / arcSteps * Math.PI / 2;
                addPoint.accept(new double[]{x0 + radius + radius * Math.cos(a), y1 - radius + radius * Math.sin(a)},
                        new double[]{Math.cos(a), Math.sin(a)});
            }
            for (int i = 1; i <= edgeSteps; i++) {
                final double t = (double) i / edgeSteps;
                addPoint.accept(new double[]{x0, y1 - radius - t * (y1 - y0 - 2 * radius)}, new double[]{-1, 0});
            }
            for (int i = 1; i < arcSteps; i++) {
                final double a = Math.PI + (double) i / arcSteps * Math.PI / 2;
                addPoint.accept(new double[]{x0 + radius + radius * Math.cos(a), y0 + radius + radius * Math.sin(a)},
                        new double[]{Math.cos(a), Math.sin(a)});
            }

            final java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
            boolean first = true;
            for (final double[] point : points) {
                if (first) {
                    path.moveTo(point[0], point[1]);
                    first = false;
                } else {
                    path.lineTo(point[0], point[1]);
                }
            }
            path.closePath();
            return path;
        }

        GlowOverlay(final boolean topmost) {
            try {
                SwingUtilities.invokeLater(() -> {
                    try {
                        window = new JWindow();
                        window.setBackground(new Color(0, 0, 0, 0));
                        window.setAlwaysOnTop(topmost);
                        window.setFocusableWindowState(false);
                        final JPanel panel = new JPanel() {
                            @Override
                            protected void paintComponent(final Graphics g) {
                                super.paintComponent(g);
                                final Graphics2D g2 = (Graphics2D) g.create();
                                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                        RenderingHints.VALUE_ANTIALIAS_ON);
                                final int w = getWidth();
                                final int h = getHeight();
                                final double pulse = 0.5 + 0.5 * Math.sin(phase * 0.25);
                                final float heat = Math.max(0f, Math.min(1f, GlowOverlay.this.heat));
                                final int glowR = (int) (168 + (239 - 168) * heat);
                                final int glowG = (int) (85 + (68 - 85) * heat);
                                final int glowB = (int) (247 + (68 - 247) * heat);
                                final java.awt.Shape aura = wavyBorder(w - 20, h - 20,
                                        phase * 0.35, Math.min(w, h));
                                g2.translate(10, 10);
                                g2.setColor(new Color(glowR, glowG, glowB, 40));
                                g2.fillRoundRect(0, 0, w - 20, h - 20, 14, 14);
                                g2.setColor(new Color(glowR, glowG, glowB, 45));
                                g2.fill(aura);
                                final float small = Math.min(w, h) < 100 ? 0.5f : 1f;
                                g2.setColor(new Color(glowR, glowG, glowB, 110 + (int) (pulse * 70)));
                                g2.setStroke(new BasicStroke(12 * small));
                                g2.draw(aura);
                                g2.setColor(new Color(
                                        (int) (216 + (252 - 216) * heat),
                                        (int) (180 + (165 - 180) * heat),
                                        (int) (254 + (165 - 254) * heat),
                                        150 + (int) (pulse * 80)));
                                g2.setStroke(new BasicStroke(7 * small));
                                g2.draw(aura);
                                g2.setColor(new Color(249, 168, 212, 90));
                                g2.setStroke(new BasicStroke(5 * small));
                                g2.draw(aura);
                                g2.setColor(new Color(249, 168, 212));
                                g2.setStroke(new BasicStroke(3 * small));
                                g2.draw(aura);
                                g2.dispose();
                            }
                        };
                        panel.setOpaque(false);
                        window.setContentPane(panel);

                        // Restack timer: runs every 16 ms on the EDT, independently
                        // of the action tick. This keeps the glow above the target
                        // even after focus changes and taskbar clicks.
                        restackTimer = new Timer(16, e -> restack());
                        restackTimer.setRepeats(true);
                        restackTimer.start();
                    } catch (final RuntimeException e) {
                        log.warn("Could not create telekinesis glow window", e);
                    }
                });
            } catch (final RuntimeException e) {
                log.warn("Could not schedule telekinesis glow creation", e);
            }
        }

        /**
         * Re-positions the glow window directly above the target in z-order.
         * Called every 16 ms by the restack timer so focus changes can't bury it.
         */
        private void restack() {
            if (window == null || !visible) return;
            // setVisible creates the native peer synchronously, so the
            // displayable guard below passes from the very first tick.
            // Without this the window starts invisible, isDisplayable stays
            // false forever, and the effect never appears.
            if (!window.isVisible()) {
                window.setVisible(true);
            }
            if (!window.isDisplayable()) return; // peer not yet created, skip
            final Rectangle current = latestBounds;
            final long handle = latestHandle;
            if (current == null) return;
            try {
                ensureClickThrough();
                if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                    final com.sun.jna.platform.win32.WinDef.HWND own =
                            new com.sun.jna.platform.win32.WinDef.HWND(
                                    com.sun.jna.Native.getWindowPointer(window));
                    final com.sun.jna.platform.win32.WinDef.HWND insertAfter;
                    if (handle != 0) {
                        final com.sun.jna.platform.win32.WinDef.HWND targetHwnd =
                                new com.sun.jna.platform.win32.WinDef.HWND(new com.sun.jna.Pointer(handle));
                        // Walk z-order above the target, skipping our own glow window,
                        // to find the first real window above it.
                        com.sun.jna.platform.win32.WinDef.HWND aboveTarget =
                                com.sun.jna.platform.win32.User32.INSTANCE.GetWindow(
                                        targetHwnd,
                                        new com.sun.jna.platform.win32.WinDef.DWORD(
                                                com.sun.jna.platform.win32.User32.GW_HWNDPREV));
                        if (aboveTarget != null && aboveTarget.equals(own)) {
                            aboveTarget = com.sun.jna.platform.win32.User32.INSTANCE.GetWindow(
                                    aboveTarget,
                                    new com.sun.jna.platform.win32.WinDef.DWORD(
                                            com.sun.jna.platform.win32.User32.GW_HWNDPREV));
                        }
                        // If a maximized window is above the target, it fully covers
                        // the grabbed window — hide the glow entirely.
                        if (aboveTarget != null) {
                            final com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT wp =
                                    new com.sun.jna.platform.win32.WinUser.WINDOWPLACEMENT();
                            // GetWindowPlacement fails unless length is set.
                            wp.length = wp.size();
                            boolean placementOk = false;
                            try {
                                placementOk = com.sun.jna.platform.win32.User32.INSTANCE
                                        .GetWindowPlacement(aboveTarget, wp).booleanValue();
                            } catch (final RuntimeException e) {
                                placementOk = false;
                            }
                            if (placementOk
                                    && wp.showCmd == com.sun.jna.platform.win32.WinUser.SW_SHOWMAXIMIZED) {
                                if (window.isVisible()) window.setVisible(false);
                                return;
                            }
                        }
                        if (!window.isVisible()) window.setVisible(true);
                        insertAfter = (aboveTarget != null)
                                ? aboveTarget
                                : new com.sun.jna.platform.win32.WinDef.HWND(new com.sun.jna.Pointer(0)); // HWND_TOP
                    } else {
                        if (!window.isVisible()) window.setVisible(true);
                        insertAfter = new com.sun.jna.platform.win32.WinDef.HWND(
                                new com.sun.jna.Pointer(-1)); // HWND_TOPMOST for cursor glow
                    }
                    com.sun.jna.platform.win32.User32.INSTANCE.SetWindowPos(own, insertAfter,
                            current.x, current.y, current.width, current.height,
                            com.sun.jna.platform.win32.User32.SWP_NOACTIVATE
                                    | com.sun.jna.platform.win32.User32.SWP_SHOWWINDOW);
                } else {
                    window.setBounds(current);
                }
                // Repaints rebuild the whole wavy path: throttle them so the
                // 16 ms z-order work never queues behind paint work. The wobble
                // still animates at 10 fps; positioning stays immediate.
                final long now = System.nanoTime();
                if (now - lastRepaintNanos >= REPAINT_MIN_NANOS) {
                    lastRepaintNanos = now;
                    window.repaint();
                }
            } catch (final RuntimeException | UnsatisfiedLinkError e) {
                log.warn("Could not restack telekinesis glow window", e);
            }
        }

        private void ensureClickThrough() {
            if (clickThrough) return;
            clickThrough = true;
            try {
                if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                    final com.sun.jna.platform.win32.WinDef.HWND own =
                            new com.sun.jna.platform.win32.WinDef.HWND(
                                    com.sun.jna.Native.getWindowPointer(window));
                    final int ex = com.sun.jna.platform.win32.User32.INSTANCE
                            .GetWindowLong(own, com.sun.jna.platform.win32.User32.GWL_EXSTYLE);
                    com.sun.jna.platform.win32.User32.INSTANCE.SetWindowLong(own,
                            com.sun.jna.platform.win32.User32.GWL_EXSTYLE,
                            ex | com.sun.jna.platform.win32.User32.WS_EX_TRANSPARENT
                                    | com.sun.jna.platform.win32.User32.WS_EX_LAYERED);
                }
            } catch (final RuntimeException | UnsatisfiedLinkError | NoClassDefFoundError e) {
                log.warn("Could not make telekinesis glow click-through", e);
            }
        }

        void showAt(final Rectangle bounds, final int animationPhase, final long targetHandle) {
            showAt(bounds, animationPhase, targetHandle, 0f);
        }

        void showAt(final Rectangle bounds, final int animationPhase, final long targetHandle, final float heat) {
            phase = animationPhase;
            this.heat = heat;
            latestBounds = bounds;
            latestHandle = targetHandle;
            visible = true;
            // Restack timer handles the actual SetWindowPos — nothing else needed here.
        }

        void hide() {
            visible = false;
            try {
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (window != null && window.isVisible()) {
                            window.setVisible(false);
                        }
                    } catch (final RuntimeException e) {
                        log.warn("Could not hide telekinesis glow window", e);
                    }
                });
            } catch (final RuntimeException e) {
                log.warn("Could not schedule telekinesis glow hide", e);
            }
        }

        void dispose() {
            visible = false;
            try {
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (restackTimer != null) {
                            restackTimer.stop();
                            restackTimer = null;
                        }
                        if (window != null) {
                            window.setVisible(false);
                            window.dispose();
                            window = null;
                        }
                    } catch (final RuntimeException e) {
                        log.warn("Could not dispose telekinesis glow window", e);
                    }
                });
            } catch (final RuntimeException e) {
                log.warn("Could not schedule telekinesis glow disposal", e);
            }
        }
    }
}
