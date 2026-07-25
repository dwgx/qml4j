package io.github.timer_err.qml4j.demo;

import io.github.timer_err.qml4j.demo.DesktopShortcut.DesktopAction;
import io.github.timer_err.qml4j.demo.DesktopShortcut.ShortcutPlatform;
import io.github.timer_err.qml4j.render.Clipboard;
import io.github.timer_err.qml4j.render.QmlView;
import io.github.timer_err.qml4j.runtime.color.StyleManager;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWWindowContentScaleCallback;
import org.lwjgl.glfw.GLFWWindowSizeCallback;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class DesktopMain {

    private static final int INITIAL_W = 720;
    private static final int INITIAL_H = 720;

    private long window;
    private boolean glfwInitialized;
    private GlfwSurfaceBackend backend;
    private DesktopHost host;
    private ShortcutPlatform shortcutPlatform;

    // The one source of truth for the logical coordinate space, refreshed once per iteration
    // before the frame it will be drawn with. The metrics callbacks only raise the flag.
    private DesktopMetrics metrics;
    private boolean metricsDirty;
    private boolean nonUniformScaleWarned;

    // glfwGetCursorPos reports window screen coordinates; they are stored raw and converted at
    // dispatch time, so a pointer event is hit-tested against the snapshot that produced the
    // frame the user was actually looking at.
    private double rawCursorX;
    private double rawCursorY;
    // Latest cursor position, dispatched once per frame. GLFW delivers many cursor-move
    // events per poll; firing pointerMove on each runs the full hit-test + handler chain
    // (e.g. a ColorPicker slider's onMoved regenerates the whole MD3 scheme, ~6 ms) several
    // times before a single repaint -- so coalesce to one move per frame, as Qt does with
    // AA_CompressHighFrequencyEvents.
    private boolean cursorMoved;

    public static void main(String[] args) {
        new DesktopMain().run(args);
    }

    // `<projectDir> <entry.qml>` runs that QML from disk (quickshell-style); `app`
    // runs the bundled upstream MD3 app; `mock <projectDir> <entry.qml>` runs a document
    // that expects a `client` context object, supplying a MockClient (Haedus ClickGui).
    private void run(String[] args) {
        boolean app = args.length >= 1 && "app".equals(args[0]);
        boolean mock = args.length >= 3 && "mock".equals(args[0]);
        if (!app && !mock && args.length < 2) {
            System.err.println("usage:  <projectDir> <entry.qml>   |   app   |   mock <projectDir> <entry.qml>");
            return;
        }
        GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit()) {
            freeErrorCallback();
            throw new IllegalStateException("glfwInit failed");
        }
        glfwInitialized = true;

        // Past this point a GL context may exist (on Linux the NVIDIA driver spins its
        // exit-fault worker), so every path -- normal close, init failure, render error --
        // must run the ordered cleanup and then pick the platform exit. Capture the failure,
        // release in finally, then decide.
        Throwable failure = null;
        try {
            createWindow();
            // GLFW is the authority on the backend it selected; os.name cannot tell X11 from
            // Wayland, and the editing shortcuts follow the windowing system's convention.
            shortcutPlatform = DesktopShortcut.platformOf(GLFW.glfwGetPlatform());

            metrics = queryMetrics();
            warnOnceOnNonUniformScale(metrics);

            backend = new GlfwSurfaceBackend(window, metrics.fbWidth(), metrics.fbHeight());
            backend.init(metrics.fbWidth(), metrics.fbHeight());
            backend.setUiScale(metrics.uiScale());

            boolean dark = !"false".equals(System.getProperty("qml4j.dark", "true"));
            ((StyleManager) StyleManager.__instance()).isDarkTheme.set(dark);

            float rootW = metrics.rootWidth();
            float rootH = metrics.rootHeight();
            Clipboard clipboard = new GlfwClipboard(window);
            if (app) {
                host = new DesktopHost(new AppResourceLoader(), rootW, rootH, clipboard);
                host.startApp();
            } else if (mock) {
                host = new DesktopHost(new DirResourceLoader(Paths.get(args[1])), rootW, rootH, clipboard);
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("client", new MockClient());
                host.run(args[2], ctx);
            } else {
                host = new DesktopHost(new DirResourceLoader(Paths.get(args[0])), rootW, rootH, clipboard);
                host.run(args[1]);
            }

            installCallbacks();

            while (!GLFW.glfwWindowShouldClose(window)) {
                if (metricsDirty) applyMetrics();
                host.renderFrame(backend);
                GLFW.glfwPollEvents();
                if (cursorMoved) {
                    cursorMoved = false;
                    host.pointerMove(logicalCursorX(), logicalCursorY());
                }
            }
        } catch (RuntimeException | Error e) {
            failure = e;
        } finally {
            try {
                shutdown();
            } catch (RuntimeException | Error cleanupError) {
                if (failure != null) {
                    failure.addSuppressed(cleanupError);
                } else {
                    failure = cleanupError;
                }
            }
        }

        // Exit only after the release above. Linux keeps the SIGKILL driver workaround (see
        // killSelf); every other host returns, so a real leftover non-daemon thread surfaces
        // as a hang rather than being masked, and a normal close exits 0. A failure re-throws
        // for a non-zero status with its stack trace; on Linux, where killSelf pre-empts the
        // JVM's own report, print it first.
        if (isLinux()) {
            if (failure != null) {
                failure.printStackTrace();
            }
            killSelf();
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
    }

    private void createWindow() {
        // Where screen coordinates and pixels map 1:1 (Windows, X11) the requested size would
        // otherwise be a pixel count, so the window would come up two thirds of its intended
        // logical size at 150%. A no-op where they can already differ (macOS, Wayland).
        GLFW.glfwWindowHint(GLFW.GLFW_SCALE_TO_MONITOR, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);

        window = GLFW.glfwCreateWindow(INITIAL_W, INITIAL_H, "qml4j showcases", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            // run()'s finally drives glfwTerminate through shutdown(); don't tear down here.
            throw new IllegalStateException("glfwCreateWindow failed");
        }
        GLFW.glfwMakeContextCurrent(window);
        // vsync on by default; -Dqml4j.vsync=false uncaps the loop to measure real FPS.
        boolean vsync = !"false".equals(System.getProperty("qml4j.vsync", "true"));
        GLFW.glfwSwapInterval(vsync ? 1 : 0);
    }

    // Rebuild the whole snapshot from GLFW getters instead of mixing callback arguments.
    // Callbacks only mark it dirty, so their delivery order cannot publish an intermediate state.
    private DesktopMetrics queryMetrics() {
        int[] fw = new int[1];
        int[] fh = new int[1];
        GLFW.glfwGetFramebufferSize(window, fw, fh);
        int[] ww = new int[1];
        int[] wh = new int[1];
        GLFW.glfwGetWindowSize(window, ww, wh);
        float[] sx = new float[1];
        float[] sy = new float[1];
        GLFW.glfwGetWindowContentScale(window, sx, sy);
        return DesktopMetrics.of(fw[0], fh[0], ww[0], wh[0], sx[0], sy[0]);
    }

    // Publish a snapshot before the frame drawn from it. If a platform temporarily reports zero
    // dimensions (for example while minimised), the snapshot is dropped whole -- surface, scale
    // and root all keep their last good values -- and the flag stays raised so the next iteration
    // retries without needing the platform to send a further event. The surface is rebuilt only
    // by a framebuffer change: backend.resize returns immediately when the size is unchanged, and
    // the scale has its own setter, so moving to a display with a different DPI never reallocates
    // the GPU surface.
    private void applyMetrics() {
        DesktopMetrics m = queryMetrics();
        if (!m.valid()) return;
        metricsDirty = false;
        metrics = m;
        warnOnceOnNonUniformScale(m);
        backend.resize(m.fbWidth(), m.fbHeight());
        backend.setUiScale(m.uiScale());
        host.resize(m.rootWidth(), m.rootHeight());
    }

    // Rendering is uniform: the renderer's picture cache keys on a single device scale, so a
    // per-axis transform would stretch the scene on a square-pixel display. Report the axis
    // being ignored rather than silently branching on it.
    private void warnOnceOnNonUniformScale(DesktopMetrics m) {
        if (nonUniformScaleWarned || !m.nonUniformScale()) return;
        nonUniformScaleWarned = true;
        System.err.println("[qml4j] window content scale differs between axes; "
            + "rendering with the x scale " + m.uiScale());
    }

    private float logicalCursorX() {
        return metrics.toLogicalX(rawCursorX);
    }

    private float logicalCursorY() {
        return metrics.toLogicalY(rawCursorY);
    }

    private void installCallbacks() {
        GLFW.glfwSetFramebufferSizeCallback(window, new GLFWFramebufferSizeCallback() {
            @Override public void invoke(long win, int w, int h) {
                metricsDirty = true;
            }
        });
        // Redundant on every platform GLFW supports (a window resize always moves the
        // framebuffer too), but it costs one flag and removes the need to prove that.
        GLFW.glfwSetWindowSizeCallback(window, new GLFWWindowSizeCallback() {
            @Override public void invoke(long win, int w, int h) {
                metricsDirty = true;
            }
        });
        GLFW.glfwSetWindowContentScaleCallback(window, new GLFWWindowContentScaleCallback() {
            @Override public void invoke(long win, float sx, float sy) {
                metricsDirty = true;
            }
        });
        GLFW.glfwSetCursorPosCallback(window, new GLFWCursorPosCallback() {
            @Override public void invoke(long win, double x, double y) {
                rawCursorX = x;
                rawCursorY = y;
                cursorMoved = true;
            }
        });
        GLFW.glfwSetMouseButtonCallback(window, new GLFWMouseButtonCallback() {
            @Override public void invoke(long win, int button, int action, int mods) {
                if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
                if (action == GLFW.GLFW_PRESS) host.pointerDown(logicalCursorX(), logicalCursorY());
                else if (action == GLFW.GLFW_RELEASE) host.pointerUp(logicalCursorX(), logicalCursorY());
            }
        });
        GLFW.glfwSetKeyCallback(window, new GLFWKeyCallback() {
            @Override public void invoke(long win, int key, int scancode, int action, int mods) {
                dispatchKey(key, mods, action);
            }
        });
        GLFW.glfwSetCharCallback(window, new GLFWCharCallback() {
            @Override public void invoke(long win, int codepoint) {
                host.text(new String(Character.toChars(codepoint)));
            }
        });
        GLFW.glfwSetScrollCallback(window, new GLFWScrollCallback() {
            @Override public void invoke(long win, double xoffset, double yoffset) {
                // The offsets are scroll deltas, not coordinates; only the anchor converts.
                host.wheel(logicalCursorX(), logicalCursorY(), (float) xoffset, (float) yoffset);
            }
        });
    }

    // The raw GLFW action reaches here intact: the reserved editing shortcuts fire on PRESS
    // alone, while every other control key keeps the repeat it has today.
    private void dispatchKey(int glfwKey, int mods, int action) {
        if (runShortcut(DesktopShortcut.classify(shortcutPlatform, glfwKey, action, mods))) return;
        int code = mapKey(glfwKey, mods);
        if (code == 0) return;
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        host.key(code, null, action != GLFW.GLFW_RELEASE, shift);
    }

    // A recognised shortcut is consumed whatever core reports: pressing the copy chord with
    // nothing selected does nothing, and must still not reach the plain key path.
    private boolean runShortcut(DesktopAction action) {
        switch (action) {
            case COPY: host.copy(); return true;
            case CUT: host.cut(); return true;
            case PASTE: host.paste(); return true;
            default: return false;
        }
    }

    // Printable characters arrive via the char callback; this maps only the control
    // keys QmlView understands. 0 means "not a control key" -> ignored here.
    private static int mapKey(int key, int mods) {
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE: return QmlView.KEY_BACKSPACE;
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER: return QmlView.KEY_ENTER;
            case GLFW.GLFW_KEY_LEFT: return QmlView.KEY_LEFT;
            case GLFW.GLFW_KEY_RIGHT: return QmlView.KEY_RIGHT;
            case GLFW.GLFW_KEY_UP: return QmlView.KEY_UP;
            case GLFW.GLFW_KEY_DOWN: return QmlView.KEY_DOWN;
            case GLFW.GLFW_KEY_HOME: return QmlView.KEY_HOME;
            case GLFW.GLFW_KEY_END: return QmlView.KEY_END;
            case GLFW.GLFW_KEY_ESCAPE: return QmlView.KEY_ESCAPE;
            case GLFW.GLFW_KEY_TAB:
                return (mods & GLFW.GLFW_MOD_SHIFT) != 0 ? QmlView.KEY_BACKTAB : QmlView.KEY_TAB;
            default: return 0;
        }
    }

    // Ordered teardown: scene and GPU resources first (while the GL context is still
    // current), then unbind, free callbacks, destroy the window, and terminate GLFW. Each
    // step is attempted independently so one failure cannot strand a later native release --
    // the first error becomes primary and the rest are attached as suppressed. Guards keep it
    // safe after a partial init, and each handle is cleared once its own step succeeds (host,
    // backend, and the window only after destroy), so completed work is not redone; a step
    // that throws keeps its guard, so this is not unconditionally idempotent after a failed
    // cleanup. Does not exit the process; run() owns the platform exit.
    private void shutdown() {
        Throwable error = null;
        error = step(error, () -> { if (host != null) { host.dispose(); host = null; } });
        error = step(error, () -> { if (backend != null) { backend.dispose(); backend = null; } });
        error = step(error, () -> { if (window != MemoryUtil.NULL) GLFW.glfwMakeContextCurrent(MemoryUtil.NULL); });
        error = step(error, () -> { if (window != MemoryUtil.NULL) Callbacks.glfwFreeCallbacks(window); });
        error = step(error, () -> {
            if (window != MemoryUtil.NULL) {
                GLFW.glfwDestroyWindow(window);
                window = MemoryUtil.NULL;
            }
        });
        error = step(error, () -> { if (glfwInitialized) { GLFW.glfwTerminate(); glfwInitialized = false; } });
        error = step(error, DesktopMain::freeErrorCallback);
        if (error instanceof RuntimeException) {
            throw (RuntimeException) error;
        }
        if (error instanceof Error) {
            throw (Error) error;
        }
    }

    // Run one cleanup action, folding any failure into the running aggregate: the first
    // becomes primary, later ones are suppressed on it, and every step still runs.
    private static Throwable step(Throwable primary, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error e) {
            if (primary == null) {
                return e;
            }
            primary.addSuppressed(e);
        }
        return primary;
    }

    // Released manually, not via try-with-resources; a second call is a no-op because
    // setErrorCallback(null) then returns null.
    @SuppressWarnings("resource")
    private static void freeErrorCallback() {
        GLFWErrorCallback cb = GLFW.glfwSetErrorCallback(null);
        if (cb != null) cb.free();
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    // The NVIDIA EGL driver spins a worker thread that SIGSEGVs the instant this process
    // begins to exit. Every in-process exit path reproduces it -- return, System.exit,
    // Runtime.halt -- even with zero GL teardown, so it cannot be fixed by ordering the
    // cleanup above. SIGKILL terminates the whole process atomically in the kernel, before
    // the worker can fault, so there is no JVM fatal-error log or core dump. Reached only on
    // Linux; other hosts return normally after shutdown() releases everything. Last resort
    // for this driver bug.
    private static void killSelf() {
        // Java 8 has no ProcessHandle; the RuntimeMXBean name is "<pid>@<host>".
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        long pid = Long.parseLong(runtimeName.substring(0, runtimeName.indexOf('@')));
        try {
            new ProcessBuilder("kill", "-9", Long.toString(pid)).start();
            Thread.sleep(10_000);
        } catch (IOException | InterruptedException e) {
            Runtime.getRuntime().halt(0);
        }
    }
}
