package com.group_finity.mascot.platform.win;

import com.group_finity.mascot.Main;
import com.group_finity.mascot.environment.AbstractEnvironment;
import com.group_finity.mascot.environment.Area;
import com.group_finity.mascot.platform.win.jna.Dwmapi;
import com.group_finity.mascot.platform.win.jna.User32Extra;
import com.sun.jna.Pointer;
import com.sun.jna.platform.WindowUtils;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.VersionHelpers;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.platform.win32.WinUser.HMONITOR;
import com.sun.jna.platform.win32.WinUser.MONITORINFO;
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uses JNI to obtain environment information that is difficult to obtain with Java.
 *
 * @author Yuki Yamada
 * @author Shimeji-ee Group
 */
class WindowsEnvironment extends AbstractEnvironment {
    private static final Logger log = LoggerFactory.getLogger(WindowsEnvironment.class);

    private final HashMap<HWND, Boolean> interactiveCache = new LinkedHashMap<>();

    private final Area activeWindow = new Area();

    private String activeWindowTitle = "";

    private HWND activeWindowHandle = null;

    private String[] windowTitles = null;

    private String[] windowTitlesBlacklist = null;

    /**
     * Enumeration of the possible return statuses when checking whether
     * a given window is valid to be interactive at any given moment.
     *
     * @author LavenderSnek
     */
    private enum WindowStatus {
        /** The window is valid and will prevent other windows from being valid. */
        VALID,
        /** The window is invalid and will prevent other windows from being valid. */
        INVALID,
        /** The window is invalid but will not prevent other windows from being valid. */
        IGNORED,
    /**
         * The window is valid, but it is out of bounds and should be ignored.
         * It will not prevent other windows from being valid.
         */
        OUT_OF_BOUNDS
    }

    @Override
    public void tick() {
        super.tick();

        long prevWindowId = getActiveWindowId();
        // Get DPI-unaware window rectangle
        final Rectangle windowRect = getWindowRect(findActiveWindow(), true);
        if (windowRect == null) {
            activeWindow.setRect(-1, -1, 0, 0);
        } else {
            activeWindow.set(windowRect);
        }
        activeWindow.setVisible(activeWindow.intersects(getScreen()));

        if (prevWindowId != getActiveWindowId()) {
            // If the active window has changed, reset the active window's deltas to 0
            activeWindow.resetDeltas();
        }

        activeWindowTitle = WindowUtils.getWindowTitle(activeWindowHandle);
    }

    private boolean isInteractive(final HWND hWnd) {
        final Boolean cachedValue = interactiveCache.get(hWnd);
        if (cachedValue != null) {
            return cachedValue;
        }

        // Determine whether the window is interactive based on its title
        final String windowTitle = WindowUtils.getWindowTitle(hWnd);

        // optimisation to remove empty windows from consideration without the loop.
        if (windowTitle.isEmpty()) {
            interactiveCache.put(hWnd, false);
            return false;
        }

        // blacklist takes precedence over whitelist
        boolean blacklistInUse = false;
        if (windowTitlesBlacklist == null) {
            windowTitlesBlacklist = Main.getInstance().getSettings().interactiveWindowsBlacklist.toArray(Main.EMPTY_STRING_ARRAY);
        }
        for (String title : windowTitlesBlacklist) {
            if (!title.trim().isEmpty()) {
                blacklistInUse = true;
                if (windowTitle.contains(title)) {
                    interactiveCache.put(hWnd, false);
                    return false;
                }
            }
        }

        // whitelist
        boolean whitelistInUse = false;
        if (windowTitles == null) {
            windowTitles = Main.getInstance().getSettings().interactiveWindows.toArray(Main.EMPTY_STRING_ARRAY);
        }
        for (String title : windowTitles) {
            if (!title.trim().isEmpty()) {
                // Window is interactive
                whitelistInUse = true;
                if (windowTitle.contains(title)) {
                    interactiveCache.put(hWnd, true);
                    return true;
                }
            }
        }

        if (whitelistInUse || !blacklistInUse) {
            // Window is not interactive
            interactiveCache.put(hWnd, false);
            return false;
        } else {
            // Window is interactive
            interactiveCache.put(hWnd, true);
            return true;
        }
    }

    private WindowStatus getWindowStatus(HWND hWnd) {
        if (User32.INSTANCE.IsWindowVisible(hWnd)) {
            // DWMWA_CLOAKED is not supported on Windows 7 and earlier, so check that we are on at least Windows 8
            if (VersionHelpers.IsWindows8OrGreater()) {
                // metro apps can be closed or minimised and still be considered "visible" by User32
                // have to consider the new cloaked variable instead
                LongByReference flagsRef = new LongByReference();
                HRESULT result = Dwmapi.INSTANCE.DwmGetWindowAttribute(hWnd, Dwmapi.DWMWA_CLOAKED, flagsRef.getPointer(), 8);
                if (result.equals(WinError.S_OK) && flagsRef.getValue() != 0) {
                    return WindowStatus.IGNORED;
                }
            }

            if (User32Extra.INSTANCE.IsZoomed(hWnd)) {
                // Window is maximized and is therefore invalid
                return WindowStatus.INVALID;
            }

            if (isInteractive(hWnd) && !User32Extra.INSTANCE.IsIconic(hWnd)) {
                // Window is valid
                Rectangle windowRect = getWindowRect(hWnd, true);
                if (windowRect != null && getScreen().intersects(windowRect)) {
                    return WindowStatus.VALID;
                } else {
                    // Window is out of bounds and will be ignored
                    return WindowStatus.OUT_OF_BOUNDS;
                }
            }
        }

        // Window is ignored
        return WindowStatus.IGNORED;
    }

    private HWND findActiveWindow() {
        activeWindowHandle = null;

        User32.INSTANCE.EnumWindows((hWnd, data) -> switch (getWindowStatus(hWnd)) {
            case VALID -> {
                activeWindowHandle = hWnd;
                yield false;
            }
            case IGNORED, OUT_OF_BOUNDS -> true;
            default -> { // The window is invalid, so abort the search here
                activeWindowHandle = null;
                yield false;
            }
        }, null);

        return activeWindowHandle;
    }

    /**
     * Gets the given window's area.
     *
     * @return the window's area
     */
    private static Rectangle getWindowRect(HWND hWnd, boolean dpiAware) {
        if (hWnd == null) {
            return null;
        }
        // Get and return window rectangle
        final Rectangle rect;
        try {
            rect = WindowUtils.getWindowLocationAndSize(hWnd);
        } catch (Win32Exception e) {
            if (e.getHR().intValue() != WinError.E_HANDLE) {
                // The exception was not due to the window handle being invalid, so rethrow the exception
                throw e;
            }
            return null;
        }
        if (dpiAware) {
            double dpiScaleInverse = 96.0 / Toolkit.getDefaultToolkit().getScreenResolution();
            if (dpiScaleInverse != 1) {
                rect.x = (int) Math.round(rect.x * dpiScaleInverse);
                rect.y = (int) Math.round(rect.y * dpiScaleInverse);
                rect.width = (int) Math.round(rect.width * dpiScaleInverse);
                rect.height = (int) Math.round(rect.height * dpiScaleInverse);
            }
        }
        return rect;
    }

    /**
     * Gets the bounds of the work area. This area is the display area excluding the taskbar.
     *
     * @param dpiAware {@code true} if the bounds should be scaled to match the DPI of the graphics environment;
     * {@code false} if the bounds should not be scaled
     * @return the bounds of the work area
     */
    private static Rectangle getWorkAreaRect(boolean dpiAware) {
        if (dpiAware) {
            // Swing scales the bounds already, so we can use it here to make things simpler
            GraphicsConfiguration config = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            Rectangle rect = config.getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config);
            rect.x += insets.left;
            rect.y += insets.top;
            rect.width -= insets.left + insets.right;
            rect.height -= insets.top + insets.bottom;
            return rect;
        } else {
            // Get the primary display monitor handle
            final HMONITOR monitor = User32.INSTANCE.MonitorFromPoint(new POINT.ByValue(0, 0), User32.MONITOR_DEFAULTTOPRIMARY);

            final MONITORINFO monitorInfo = new MONITORINFO();
            User32.INSTANCE.GetMonitorInfo(monitor, monitorInfo); // TODO: Look into this method for future patches

            return monitorInfo.rcWork.toRectangle();
        }
    }

    @Override
    public boolean isMouseLocked() {
        // Windows-specific: check if cursor is clipped to a small rect (FPS lock via ClipCursor)
        try {
            final com.sun.jna.platform.win32.WinDef.RECT rect = new com.sun.jna.platform.win32.WinDef.RECT();
            if (User32Extra.INSTANCE.GetClipCursor(rect)) {
                final int w = rect.right - rect.left;
                final int h = rect.bottom - rect.top;
                if (w > 0 && h > 0 && w < 200 && h < 200) {
                    return true;
                }
            }
        } catch (final Exception ignored) {
        }
        return super.isMouseLocked();
    }

    @Override
    public int getCursorSizePixels() {
        // Accessibility pointer size lives in CursorBaseSize; fall back to
        // the system cursor metric, then the default. Big cursors welcome
        // nowhere near Nigel.
        try {
            final int base = Advapi32Util.registryGetIntValue(
                    WinReg.HKEY_CURRENT_USER, "Control Panel\\Cursors", "CursorBaseSize");
            if (base > 0) {
                return base;
            }
        } catch (final RuntimeException ignored) {
        }
        try {
            final int metric = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXCURSOR);
            if (metric > 0) {
                return metric;
            }
        } catch (final RuntimeException ignored) {
        }
        return 32;
    }

    /**
     * Window handles backing the areas returned by {@link #getGrabbableWindows()},
     * so {@link #moveWindow(Area, int, int)} can move the exact window picked.
     * Dead handles are pruned on each enumeration.
     */
    private final Map<Area, HWND> grabbableWindowHandles = new IdentityHashMap<>();

    /**
     * When each window handle was last grabbed, to stop repeat yoinks of the
     * same window back to back. A plain HashMap (not identity): JNA mints a
     * fresh wrapper per enumeration, so identity keys would never match and
     * the cooldown would silently never fire. Value equality holds by peer.
     */
    private final Map<HWND, Long> lastGrabbedAt = new HashMap<>();

    private static final long GRAB_COOLDOWN_MILLIS = 60_000;

    /**
     * Master switch for the TELE-DEBUG filter trace in
     * {@link #getGrabbableWindows()}. Off for normal runs; flip to
     * {@code true} to log every candidate decision (also raise the
     * FileHandler limit in conf/logging.properties so batches survive).
     */
    private static final boolean TELE_DEBUG = false;

    private static void teleDebug(final String format, final Object... args) {
        if (TELE_DEBUG) {
            teleDebug("" + format, args);
        }
    }

    private static boolean isBlacklistedProcess(final String image) {
        return image != null && (image.contains("rainmeter") || image.contains("sharex"));
    }

    @Override
    public List<Area> getGrabbableWindows() {
        final List<Area> result = new ArrayList<>();
        final int ownPid;
        try {
            ownPid = Kernel32.INSTANCE.GetCurrentProcessId();
        } catch (final Exception e) {
            return result;
        }

        User32.INSTANCE.EnumWindows((hWnd, data) -> {
            try {
                // Filter trace (see TELE_DEBUG above): one line per candidate
                // so filter decisions are traceable from the log.
                final char[] titleBuf = new char[256];
                String title = "?";
                try {
                    final int titleLen = User32.INSTANCE.GetWindowText(hWnd, titleBuf, titleBuf.length);
                    if (titleLen > 0) {
                        title = new String(titleBuf, 0, titleLen);
                    }
                } catch (final RuntimeException ignored) {
                }
                if (!User32.INSTANCE.IsWindowVisible(hWnd)) {
                    teleDebug("reject [invisible]: '{}'", title);
                    return true;
                }
                // Minimized and maximized windows are out.
                if (User32Extra.INSTANCE.IsIconic(hWnd) || User32Extra.INSTANCE.IsZoomed(hWnd)) {
                    teleDebug("reject [min/max]: '{}'", title);
                    return true;
                }
                // Cloaked metro/UWP leftovers are out.
                if (VersionHelpers.IsWindows8OrGreater()) {
                    final LongByReference flagsRef = new LongByReference();
                    final HRESULT result2 = Dwmapi.INSTANCE.DwmGetWindowAttribute(hWnd, Dwmapi.DWMWA_CLOAKED, flagsRef.getPointer(), 8);
                    if (result2.equals(WinError.S_OK) && flagsRef.getValue() != 0) {
                        teleDebug("reject [cloaked]: '{}'", title);
                        return true;
                    }
                }
                // Our own windows (mascots, glow, dialogs) are out.
                final IntByReference pidRef = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hWnd, pidRef);
                if (pidRef.getValue() == ownPid) {
                    teleDebug("reject [own pid]: '{}'", title);
                    return true;
                }
                // Shell chrome is out: taskbar, multi-monitor taskbars,
                // desktop/wallpaper hosts. (Open-Shell hooks into these.)
                final char[] className = new char[256];
                final int classLen;
                try {
                    classLen = User32.INSTANCE.GetClassName(hWnd, className, className.length);
                } catch (final RuntimeException e) {
                    return true;
                }
                if (classLen > 0) {
                    final String cls = new String(className, 0, classLen);
                    if (cls.equals("Shell_TrayWnd") || cls.equals("Shell_SecondaryTrayWnd")
                            || cls.equals("Progman") || cls.equals("WorkerW")
                            || cls.equals("tooltips_class32")) {
                        teleDebug("reject [shell chrome {}]: '{}'", cls, title);
                        return true;
                    }
                }
                // NOTE: deliberately no isInteractive() title-list check here:
                // telekinesis yoinks any window, it is not bound by the
                // stand-on-window whitelist/blacklist settings.
                final Rectangle rect = getWindowRect(hWnd, true);
                if (rect == null || rect.width <= 0 || rect.height <= 0) {
                    teleDebug("reject [empty rect]: '{}'", title);
                    return true;
                }
                if (!getScreen().intersects(rect)) {
                    teleDebug("reject [off-screen {}]: '{}'", rect, title);
                    return true;
                }
                // Fullscreen and borderless-fullscreen games cover a monitor;
                // small borderless widgets (Rainmeter etc.) still pass.
                if (coversMonitor(rect)) {
                    teleDebug("reject [covers monitor {}]: '{}'", rect, title);
                    return true;
                }
                // Fully buried windows (e.g. entirely behind a maximized app)
                // are out; anything peeking out still passes.
                try {
                    if (isCovered(hWnd, 95)) {
                        teleDebug("reject [buried>=95% {}]: '{}'", rect, title);
                        return true;
                    }
                } catch (final RuntimeException ignored) {
                }
                teleDebug("ACCEPTED {}: '{}'", rect, title);
                final Area area = new Area();
                area.set(rect);
                area.setVisible(true);
                result.add(area);
                grabbableWindowHandles.put(area, hWnd);
            } catch (final RuntimeException e) {
                // Skip misbehaving windows.
            }
            return true;
        }, null);

        // Prune dead handles so the map can't grow with stale entries.
        grabbableWindowHandles.entrySet().removeIf(entry -> {
            try {
                return !User32.INSTANCE.IsWindow(entry.getValue());
            } catch (final RuntimeException e) {
                return true;
            }
        });

        // Blacklisted by process (recording overlays, widgets and managers
        // alike); only the surviving candidates pay for the handle lookup.
        // Recently grabbed windows sit out for a cooldown so the same window
        // isn't yoinked over and over.
        final long now = System.currentTimeMillis();
        lastGrabbedAt.entrySet().removeIf(entry -> now - entry.getValue() > GRAB_COOLDOWN_MILLIS);
        result.removeIf(area -> {
            final HWND handle = grabbableWindowHandles.get(area);
            if (handle == null) {
                return false;
            }
            final Long grabbedAt = lastGrabbedAt.get(handle);
            if (grabbedAt != null) {
                teleDebug("reject [cooldown, grabbed {}s ago]", (now - grabbedAt) / 1000);
                return true;
            }
            final String image = getProcessImageName(handle);
            final boolean blacklisted = isBlacklistedProcess(image);
            if (blacklisted) {
                teleDebug("reject [blacklisted process {}]", image);
                grabbableWindowHandles.remove(area);
            }
            return blacklisted;
        });

        return result;
    }

    @Override
    public void markWindowGrabbed(final Area area) {
        final HWND handle = grabbableWindowHandles.get(area);
        if (handle != null) {
            lastGrabbedAt.put(handle, System.currentTimeMillis());
        }
    }

    @Override
    public boolean isFullscreen() {
        // Blacklisted overlays (ShareX region capture etc.) go fullscreen
        // without the user gaming; don't let them trip the guard.
        try {
            if (activeWindowHandle != null) {
                final String image = getProcessImageName(activeWindowHandle);
                if (isBlacklistedProcess(image)) {
                    return false;
                }
            }
        } catch (final RuntimeException ignored) {
        }
        return defaultIsFullscreen();
    }

    private static String getProcessImageName(final HWND hWnd) {
        final IntByReference pidRef = new IntByReference();
        try {
            User32.INSTANCE.GetWindowThreadProcessId(hWnd, pidRef);
            final com.sun.jna.platform.win32.WinNT.HANDLE process =
                    Kernel32.INSTANCE.OpenProcess(0x1000, false, pidRef.getValue());
            if (process == null) {
                return null;
            }
            try {
                final char[] buffer = new char[1024];
                final IntByReference sizeRef = new IntByReference(buffer.length);
                if (Kernel32.INSTANCE.QueryFullProcessImageName(process, 0, buffer, sizeRef)) {
                    return new String(buffer, 0, sizeRef.getValue()).toLowerCase(java.util.Locale.ROOT);
                }
            } finally {
                Kernel32.INSTANCE.CloseHandle(process);
            }
        } catch (final Exception e) {
            // Fail open: an unreadable process is not blacklisted.
        }
        return null;
    }

    @Override
    public boolean isWindowOpen(final Area area) {
        final HWND hWnd = grabbableWindowHandles.get(area);
        if (hWnd == null) {
            return false;
        }
        try {
            return User32.INSTANCE.IsWindow(hWnd);
        } catch (final RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean isWindowMinimized(final Area area) {
        final HWND hWnd = grabbableWindowHandles.get(area);
        if (hWnd == null) {
            return false;
        }
        try {
            return User32Extra.INSTANCE.IsIconic(hWnd);
        } catch (final RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean isWindowOccluded(final Area area) {
        final HWND target = grabbableWindowHandles.get(area);
        if (target == null) {
            return false;
        }
        try {
            // Only truly buried windows hide the effect; normal cascade
            // overlap keeps it since layering now handles the rest.
            return isCovered(target, 80);
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /**
     * Checks whether higher windows cover at least the given percent of the
     * target window. Own windows are ignored so Nigel himself never counts.
     *
     * @param target the window to check
     * @param percentThreshold coverage percent (0-100) that counts as covered
     * @return {@code true} if covered past the threshold
     */
    private boolean isCovered(final HWND target, final int percentThreshold) {
        final Rectangle targetRect = getWindowRect(target, true);
        if (targetRect == null || targetRect.width <= 0 || targetRect.height <= 0) {
            return true;
        }
        final long targetArea = (long) targetRect.width * targetRect.height;
        HWND above = User32.INSTANCE.GetWindow(target,
                new com.sun.jna.platform.win32.WinDef.DWORD(User32.GW_HWNDPREV));
        int steps = 0;
        while (above != null && steps++ < 200) {
            final HWND current = above;
            try {
                if (User32.INSTANCE.IsWindowVisible(current) && !isOwnWindow(current)
                        && !User32Extra.INSTANCE.IsIconic(current) && !isCloaked(current)) {
                    final Rectangle rect = getWindowRect(current, true);
                    if (rect != null && !rect.isEmpty()) {
                        final Rectangle intersection = rect.intersection(targetRect);
                        if (!intersection.isEmpty()
                                && (long) intersection.width * intersection.height
                                        >= targetArea * percentThreshold / 100) {
                            return true;
                        }
                    }
                }
            } catch (final RuntimeException ignored) {
            }
            above = User32.INSTANCE.GetWindow(current,
                    new com.sun.jna.platform.win32.WinDef.DWORD(User32.GW_HWNDPREV));
        }
        return false;
    }

    private boolean isOwnWindow(final HWND hWnd) {
        try {
            final IntByReference pidRef = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(hWnd, pidRef);
            return pidRef.getValue() == Kernel32.INSTANCE.GetCurrentProcessId();
        } catch (final Exception e) {
            return false;
        }
    }

    private static boolean isCloaked(final HWND hWnd) {
        if (!VersionHelpers.IsWindows8OrGreater()) {
            return false;
        }
        try {
            final LongByReference flagsRef = new LongByReference();
            final HRESULT result = Dwmapi.INSTANCE.DwmGetWindowAttribute(hWnd, Dwmapi.DWMWA_CLOAKED, flagsRef.getPointer(), 8);
            return result.equals(WinError.S_OK) && flagsRef.getValue() != 0;
        } catch (final RuntimeException e) {
            return false;
        }
    }

    @Override
    public long getNativeWindowHandle(final Area area) {
        final HWND hWnd = grabbableWindowHandles.get(area);
        if (hWnd == null) {
            return 0;
        }
        try {
            return com.sun.jna.Pointer.nativeValue(hWnd.getPointer());
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    @Override
    public boolean isWindowForeground(final Area area) {
        final HWND hWnd = grabbableWindowHandles.get(area);
        if (hWnd == null) {
            return false;
        }
        try {
            return hWnd.equals(User32.INSTANCE.GetForegroundWindow());
        } catch (final RuntimeException e) {
            return false;
        }
    }

    private boolean coversMonitor(final Rectangle rect) {
        final long windowArea = (long) rect.width * rect.height;
        for (final Area screen : getScreens()) {
            final long screenArea = (long) Math.max(1, screen.getWidth()) * Math.max(1, screen.getHeight());
            if (windowArea >= screenArea * 85 / 100) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void moveWindow(final Area area, final int x, final int y) {
        // Entries are only pruned when their window dies, so a live pick stays mapped.
        final HWND hWnd = grabbableWindowHandles.get(area);
        try {
            if (hWnd == null || !User32.INSTANCE.IsWindow(hWnd)) {
                return;
            }
        } catch (final RuntimeException e) {
            return;
        }

        double dpiScale = Toolkit.getDefaultToolkit().getScreenResolution() / 96.0;
        int moveX = x;
        int moveY = y;
        if (dpiScale != 1) {
            moveX = (int) Math.round(x * dpiScale);
            moveY = (int) Math.round(y * dpiScale);
        }

        try {
            // NOZORDER + NOACTIVATE: move background windows without stealing focus.
            User32.INSTANCE.SetWindowPos(hWnd, null, moveX, moveY,
                    0, 0, User32.SWP_NOSIZE | User32.SWP_NOZORDER | User32.SWP_NOACTIVATE);
        } catch (final RuntimeException e) {
            // Window died mid-flight; the tick aborts harmlessly next frame.
        }
    }

    @Override
    public Area getActiveWindow() {
        return activeWindow;
    }

    @Override
    public String getActiveWindowTitle() {
        return activeWindowTitle;
    }

    @Override
    public long getActiveWindowId() {
        return activeWindowHandle == null ? 0 : activeWindowHandle.hashCode();
    }

    @Override
    public void moveActiveWindow(int x, int y) {
        if (activeWindowHandle == null) {
            return;
        }

        double dpiScale = Toolkit.getDefaultToolkit().getScreenResolution() / 96.0;
        if (dpiScale != 1) {
            x = (int) Math.round(x * dpiScale);
            y = (int) Math.round(y * dpiScale);
        }

        /* Use SetWindowPos() instead of MoveWindow() so we don't have to
        pass the previous dimensions of the window to the function */
        User32.INSTANCE.SetWindowPos(activeWindowHandle, null, x, y,
                0, 0, User32.SWP_NOSIZE);
    }

    @Override
    public void restoreWindows() {
        User32.INSTANCE.EnumWindows(new WNDENUMPROC() {
            int offset = 25;
            boolean firstCallback = true;

            @Override
            public boolean callback(HWND hWnd, Pointer data) {
                WindowStatus result = getWindowStatus(hWnd);
                if (result == WindowStatus.OUT_OF_BOUNDS) {
                    // Valid interactive window found

                    // Get the work area rectangle
                    final Rectangle workArea = getWorkAreaRect(false);
                    // Get window rectangle
                    final Rectangle rect;
                    try {
                        rect = WindowUtils.getWindowLocationAndSize(hWnd);
                    } catch (Win32Exception e) {
                        if (e.getHR().intValue() != WinError.E_HANDLE) {
                            // The exception was not due to the window handle being invalid, so rethrow the exception
                            throw e;
                        }
                        return true;
                    }

                    double dpiScaleInverse = 96.0 / Toolkit.getDefaultToolkit().getScreenResolution();
                    if (firstCallback) {
                        if (dpiScaleInverse != 1) {
                            offset = (int) Math.round(offset * dpiScaleInverse);
                        }
                        firstCallback = false;
                    }
                    // Move the window to be on-screen
                    rect.setLocation(workArea.x + offset, workArea.y + offset);
                    User32.INSTANCE.MoveWindow(hWnd, rect.x, rect.y, rect.width, rect.height, true);
                    User32.INSTANCE.BringWindowToTop(hWnd);

                    if (dpiScaleInverse == 1) {
                        offset += 25;
                    } else {
                        offset = (int) Math.round(offset + 25 * dpiScaleInverse);
                    }
                }

                return true;
            }
        }, null);
    }

    @Override
    public void refreshCache() {
        interactiveCache.clear(); // Will be repopulated in the next isInteractive() call
        windowTitles = null;
        windowTitlesBlacklist = null;
    }
}
