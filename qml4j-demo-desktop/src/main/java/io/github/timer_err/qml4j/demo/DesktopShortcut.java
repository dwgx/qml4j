package io.github.timer_err.qml4j.demo;

import org.lwjgl.glfw.GLFW;

// The editing shortcuts the host reserves for itself, decided from one key callback's own
// arguments and nothing else -- no window handle, no mutable state, no os.name. The platform is
// injected, which is what lets the Windows, X11, Wayland and unknown-platform rows be verified on
// a machine that has none of them: DesktopMain itself needs GLFW natives and, on macOS, the
// first thread, so it stays out of these unit tests.
final class DesktopShortcut {

    // Only the modifiers that take part in the decision. GLFW_LOCK_KEY_MODS is left off in this
    // host so Caps/Num never arrive; masking them anyway stops a stray lock bit from turning an
    // otherwise exact match into a mismatch.
    static final int MOD_MASK = GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_CONTROL
        | GLFW.GLFW_MOD_ALT | GLFW.GLFW_MOD_SUPER;

    private DesktopShortcut() {
    }

    enum DesktopAction { NONE, COPY, CUT, PASTE }

    enum ShortcutPlatform { MACOS, WINDOWS, X11, WAYLAND, UNKNOWN }

    static ShortcutPlatform platformOf(int glfwPlatform) {
        switch (glfwPlatform) {
            case GLFW.GLFW_PLATFORM_COCOA: return ShortcutPlatform.MACOS;
            case GLFW.GLFW_PLATFORM_WIN32: return ShortcutPlatform.WINDOWS;
            case GLFW.GLFW_PLATFORM_X11: return ShortcutPlatform.X11;
            case GLFW.GLFW_PLATFORM_WAYLAND: return ShortcutPlatform.WAYLAND;
            default: return ShortcutPlatform.UNKNOWN;
        }
    }

    // Zero where the convention is unknown: a platform we cannot name rejects rather than
    // guessing Control, and zero is also why classify has to test it separately -- an unmodified
    // keystroke would otherwise compare equal to it.
    static int primaryModifier(ShortcutPlatform platform) {
        switch (platform) {
            case MACOS: return GLFW.GLFW_MOD_SUPER;
            case WINDOWS:
            case X11:
            case WAYLAND: return GLFW.GLFW_MOD_CONTROL;
            default: return 0;
        }
    }

    static DesktopAction classify(ShortcutPlatform platform, int key, int rawAction, int rawMods) {
        // A held key repeats and then releases; a clipboard action must happen once.
        if (rawAction != GLFW.GLFW_PRESS) return DesktopAction.NONE;
        int primary = primaryModifier(platform);
        // Exact equality, never "contains": Windows reports AltGr as CONTROL|ALT and still
        // delivers the character through WM_CHAR, so a containment test would copy and type from
        // the same keystroke. On the macOS, Windows, X11 and Wayland backends the bundled GLFW
        // source shows the accepted combination produces no character.
        if (primary == 0 || (rawMods & MOD_MASK) != primary) return DesktopAction.NONE;
        switch (key) {
            case GLFW.GLFW_KEY_C: return DesktopAction.COPY;
            case GLFW.GLFW_KEY_X: return DesktopAction.CUT;
            case GLFW.GLFW_KEY_V: return DesktopAction.PASTE;
            default: return DesktopAction.NONE;
        }
    }
}
