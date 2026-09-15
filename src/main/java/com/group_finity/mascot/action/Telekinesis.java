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
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
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
        if (action != null && action.live) {
            action.beginFall();
        } else if (action != null) {
            action.disposeGlow();
        }
    }

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);

        // Clear any stale hold (no fall: it never really started).
        cancelFor(mascot);

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
        glow = new GlowOverlay(getEnvironment().getNativeWindowHandle(target));
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
        if (target == null) {
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
        disposeGlow();
    }

    /**
     * Drops the held window with gravity. Self-driven on a Swing timer so it
     * keeps falling even though this action is no longer ticking.
     */
    private void beginFall() {
        live = false;
        fallVY = 0.0;
        // The drop is unmarked: glow goes away the moment the hold breaks.
        disposeGlow();
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
        disposeGlow();
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        try {
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
            if (getEnvironment().isFullscreen() || getEnvironment().isMouseLocked()) {
                throw new LostGroundException("Fullscreen/mouse-lock active");
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
                    occlusionCooldown = 8;
                    targetOccluded = getEnvironment().isWindowOccluded(target);
                }
                if (targetOccluded) {
                    glow.hide();
                } else {
                    glow.showAt(new Rectangle(sendX - 10, sendY - 10, winW + 20, winH + 20), getTime());
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

    private void faceWindow() {
        if (target == null) {
            return;
        }
        final int centerX = target.getLeft() + target.getWidth() / 2;
        getMascot().setLookRight(getMascot().getAnchor().x < centerX);
    }

    private void disposeGlow() {
        if (glow != null) {
            try {
                glow.dispose();
            } catch (final RuntimeException e) {
                log.warn("Could not dispose telekinesis glow", e);
            }
            glow = null;
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
        private volatile long targetHandle;
        private volatile boolean clickThrough;

        GlowOverlay(final long targetHandle) {
            this.targetHandle = targetHandle;
            try {
                SwingUtilities.invokeLater(() -> {
                    try {
                        window = new JWindow();
                        window.setBackground(new Color(0, 0, 0, 0));
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
                                g2.setColor(new Color(168, 85, 247, 45));
                                g2.fillRoundRect(10, 10, w - 20, h - 20, 14, 14);
                                g2.setColor(new Color(168, 85, 247, 110 + (int) (pulse * 70)));
                                g2.setStroke(new BasicStroke(12));
                                g2.drawRoundRect(7, 7, w - 14, h - 14, 20, 20);
                                g2.setColor(new Color(216, 180, 254, 150 + (int) (pulse * 80)));
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

        void showAt(final Rectangle bounds, final int animationPhase) {
            phase = animationPhase;
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
