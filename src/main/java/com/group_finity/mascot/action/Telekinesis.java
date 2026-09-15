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
    private int occlusionCooldown;
    private boolean targetOccluded;

    private static final double PULL_STEP = 28.0;
    private static final double PULL_ARRIVE = 30.0;
    private static final int PULL_RAMP_TICKS = 300;
    private static final int PULL_RED_TICKS = 150;
    private static final int PULL_MIN_TICKS = 180;

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
    private int shakeAppliedX;
    private int shakeAppliedY;
    private String teleFullKey;
    private boolean teleFullLoaded;

    private Area target;

    private GlowOverlay glow;

    /**
     * Live holds by mascot. When a behavior swap abandons a lift mid-flight
     * (e.g. the user grabs Nigel), the hold is converted into a physics
     * window-fall instead of freezing the window and leaking the glow.
     */
    private static final Map<Mascot, Telekinesis> HOLDS =
            Collections.synchronizedMap(new IdentityHashMap<>());

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
        if (action.live && action.target != null) {
            action.beginFall();
        } else {
            action.disposeGlows();
        }
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);

        // Clear any stale hold (no fall: it never really started).
        cancelFor(mascot);

        // Either a window lift or a mouse reel, never both.
        pullMouse = Math.random() < 0.5;
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
            shakeAppliedX = 0;
            shakeAppliedY = 0;
            live = true;
            synchronized (HOLDS) {
                HOLDS.put(mascot, this);
            }
            log.info("Telekinesis init: reeling the cursor in");
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
        occlusionCooldown = 0;
        targetOccluded = false;
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
    public boolean hasNext() throws VariableException {
        if (target == null && !pullMouse) {
            return false;
        }
        final boolean more = super.hasNext();
        if (!more) {
            endHold();
        }
        return more;
    }

    private void endHold() {
        live = false;
        synchronized (HOLDS) {
            HOLDS.remove(getMascot());
        }
        // Revert any shake offset so the anchor doesn't rest displaced.
        if (shakeAppliedX != 0 || shakeAppliedY != 0) {
            getMascot().getAnchor().translate(-shakeAppliedX, -shakeAppliedY);
            shakeAppliedX = 0;
            shakeAppliedY = 0;
        }
        disposeGlows();
    }

    /**
     * Rattles Nigel's body in place while reeling: a slow tremble after a
     * bit, violent shaking at full strength. Absolute sine offsets, so the
     * anchor can never wander off.
     */
    private void shakeBody() {
        if (pullTicks <= 90) {
            return;
        }
        final double progress = Math.min(1.0, pullTicks / (double) PULL_RAMP_TICKS);
        final double amplitude = 1.0 + 5.0 * progress;
        final int nextX = (int) Math.round(Math.sin(pullTicks * 0.6) * amplitude);
        final int nextY = (int) Math.round(Math.cos(pullTicks * 0.8) * amplitude * 0.7);
        getMascot().getAnchor().translate(nextX - shakeAppliedX, nextY - shakeAppliedY);
        shakeAppliedX = nextX;
        shakeAppliedY = nextY;
        final Area screen = getEnvironment().getScreen();
        final Point anchor = getMascot().getAnchor();
        anchor.x = Math.max(screen.getLeft(), Math.min(screen.getRight(), anchor.x));
        anchor.y = Math.max(screen.getTop(), Math.min(screen.getBottom(), anchor.y));
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
                // Occlusion is a z-order walk, so check it a few times a
                // second instead of every tick.
                if (--occlusionCooldown <= 0) {
                    occlusionCooldown = 4;
                    final boolean occluded = getEnvironment().isWindowOccluded(target);
                    if (occluded != targetOccluded) {
                        log.info("Telekinesis glow occluded={} for target at ({}, {})",
                                occluded, target.getLeft(), target.getTop());
                    }
                    targetOccluded = occluded;
                }
                if (targetOccluded) {
                    glow.hide();
                } else {
                    glow.showAt(new Rectangle(sendX - 10, sendY - 10, winW + 20, winH + 20), getTime(),
                            getEnvironment().getNativeWindowHandle(target));
                }
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
        getMascot().setLookRight(anchor.x < raw.x);
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
        // Minimum show length: hold the caught cursor in the reddening glow
        // a beat before the struggle takes over.
        if (distance <= PULL_ARRIVE && pullTicks >= PULL_MIN_TICKS) {
            final boolean devour = pullTicks >= PULL_RAMP_TICKS;
            log.info("Telekinesis mouse pull arrived, {}",
                    devour ? "devouring straight into swallow" : "starting struggle");
            getMascot().setDevourNext(devour);
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
     */
    private static final class GlowOverlay {
        private JWindow window;
        private volatile int phase;
        private volatile float heat;
        private volatile boolean clickThrough;

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
                                g2.setColor(new Color(glowR, glowG, glowB, 45));
                                g2.fillRoundRect(10, 10, w - 20, h - 20, 14, 14);
                                g2.setColor(new Color(glowR, glowG, glowB, 110 + (int) (pulse * 70)));
                                g2.setStroke(new BasicStroke(12));
                                g2.drawRoundRect(7, 7, w - 14, h - 14, 20, 20);
                                g2.setColor(new Color(
                                        (int) (216 + (252 - 216) * heat),
                                        (int) (180 + (165 - 180) * heat),
                                        (int) (254 + (165 - 254) * heat),
                                        150 + (int) (pulse * 80)));
                                g2.setStroke(new BasicStroke(7));
                                g2.drawRoundRect(7, 7, w - 14, h - 14, 20, 20);
                                g2.setColor(new Color(249, 168, 212, 90));
                                g2.setStroke(new BasicStroke(5));
                                g2.drawRoundRect(7, 7, w - 14, h - 14, 20, 20);
                                g2.setColor(new Color(249, 168, 212));
                                g2.setStroke(new BasicStroke(3));
                                g2.drawRoundRect(7, 7, w - 14, h - 14, 20, 20);
                                g2.dispose();
                            }
                        };
                        panel.setOpaque(false);
                        window.setContentPane(panel);
                    } catch (final RuntimeException e) {
                        log.warn("Could not create telekinesis glow window", e);
                    }
                });
            } catch (final RuntimeException e) {
                log.warn("Could not schedule telekinesis glow creation", e);
            }
        }

        void showAt(final Rectangle bounds, final int animationPhase, final long targetHandle) {
            showAt(bounds, animationPhase, targetHandle, 0f);
        }

        void showAt(final Rectangle bounds, final int animationPhase, final long targetHandle, final float heat) {
            phase = animationPhase;
            this.heat = heat;
            try {
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (window != null) {
                            // Visible first: the window must be displayable
                            // before getWindowPointer works.
                            if (!window.isVisible()) {
                                window.setVisible(true);
                            }
                            // Click-through so the glow never eats clicks meant
                            // for the held app (Windows only, once, best effort).
                            if (!clickThrough) {
                                clickThrough = true;
                                try {
                                    if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                                            .contains("win")) {
                                        final com.sun.jna.platform.win32.WinDef.HWND own =
                                                new com.sun.jna.platform.win32.WinDef.HWND(
                                                        com.sun.jna.Native.getWindowPointer(window));
                                        final int ex = com.sun.jna.platform.win32.User32.INSTANCE
                                                .GetWindowLong(own,
                                                        com.sun.jna.platform.win32.User32.GWL_EXSTYLE);
                                        com.sun.jna.platform.win32.User32.INSTANCE.SetWindowLong(own,
                                                com.sun.jna.platform.win32.User32.GWL_EXSTYLE,
                                                ex | com.sun.jna.platform.win32.User32.WS_EX_TRANSPARENT
                                                        | com.sun.jna.platform.win32.User32.WS_EX_LAYERED);
                                    }
                                } catch (final RuntimeException | UnsatisfiedLinkError | NoClassDefFoundError e) {
                                    log.warn("Could not make telekinesis glow click-through", e);
                                }
                            }
                            if (targetHandle != 0) {
                                // Restack directly above the target instead of
                                // topmost, so covering windows cover the glow too.
                                final com.sun.jna.platform.win32.WinDef.HWND insertAfter =
                                        new com.sun.jna.platform.win32.WinDef.HWND(
                                                new com.sun.jna.Pointer(targetHandle));
                                final com.sun.jna.platform.win32.WinDef.HWND own =
                                        new com.sun.jna.platform.win32.WinDef.HWND(
                                                com.sun.jna.Native.getWindowPointer(window));
                                com.sun.jna.platform.win32.User32.INSTANCE.SetWindowPos(own, insertAfter,
                                        bounds.x, bounds.y, bounds.width, bounds.height,
                                        com.sun.jna.platform.win32.User32.SWP_NOACTIVATE);
                            } else {
                                window.setBounds(bounds);
                            }
                            window.repaint();
                        }
                    } catch (final RuntimeException | UnsatisfiedLinkError e) {
                        log.warn("Could not move telekinesis glow window", e);
                    }
                });
            } catch (final RuntimeException e) {
                log.warn("Could not schedule telekinesis glow update", e);
            }
        }

        void hide() {
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
            try {
                SwingUtilities.invokeLater(() -> {
                    try {
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
