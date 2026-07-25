package io.github.timer_err.qml4j.demo;

import io.github.timer_err.qml4j.demo.DesktopShortcut.DesktopAction;
import io.github.timer_err.qml4j.demo.DesktopShortcut.ShortcutPlatform;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The host's reserved editing shortcuts as a pure decision. Windows, X11, Wayland and the
// unknown-platform rows -- most of the frozen table -- have no hardware on this project, and
// DesktopMain itself needs GLFW natives plus the macOS first thread, so these unit tests are
// where those rows are checked in code.
class DesktopShortcutTest {

    private static final ShortcutPlatform[] NON_MAC = {
        ShortcutPlatform.WINDOWS, ShortcutPlatform.X11, ShortcutPlatform.WAYLAND,
    };

    private static final int[] CLIPBOARD_KEYS = {
        GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_V,
    };

    // Keys the policy must never claim, swept alongside the three it owns. A and Z are listed
    // because select-all/undo have no core capability and must stay unimplemented here.
    private static final int[] SWEPT_KEYS = {
        GLFW.GLFW_KEY_C, GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_V,
        GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_B,
        GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_UNKNOWN,
    };

    private static final int[] ACTIONS = {GLFW.GLFW_PRESS, GLFW.GLFW_REPEAT, GLFW.GLFW_RELEASE};

    // The accept list as data. Written as literal bit values rather than recomputed from the
    // production mask arithmetic, so a mutated implementation cannot drag the oracle with it.
    // UNKNOWN is deliberately absent: no modifier combination is ever accepted there.
    private static final Map<ShortcutPlatform, Integer> ACCEPTED_MODS =
        new EnumMap<>(ShortcutPlatform.class);

    private static final Map<Integer, DesktopAction> KEY_ACTIONS = new HashMap<>();

    static {
        ACCEPTED_MODS.put(ShortcutPlatform.MACOS, 0x8);
        ACCEPTED_MODS.put(ShortcutPlatform.WINDOWS, 0x2);
        ACCEPTED_MODS.put(ShortcutPlatform.X11, 0x2);
        ACCEPTED_MODS.put(ShortcutPlatform.WAYLAND, 0x2);

        KEY_ACTIONS.put(GLFW.GLFW_KEY_C, DesktopAction.COPY);
        KEY_ACTIONS.put(GLFW.GLFW_KEY_X, DesktopAction.CUT);
        KEY_ACTIONS.put(GLFW.GLFW_KEY_V, DesktopAction.PASTE);
    }

    private static DesktopAction expected(ShortcutPlatform platform, int key, int rawAction, int mods) {
        if (rawAction != GLFW.GLFW_PRESS) return DesktopAction.NONE;
        Integer accepted = ACCEPTED_MODS.get(platform);
        if (accepted == null || accepted.intValue() != mods) return DesktopAction.NONE;
        DesktopAction action = KEY_ACTIONS.get(key);
        return action == null ? DesktopAction.NONE : action;
    }

    private static String row(ShortcutPlatform platform, int key, int rawAction, int mods) {
        return "row platform=" + platform + " key=" + keyName(key) + " action=" + actionName(rawAction)
            + " mods=0x" + Integer.toHexString(mods);
    }

    private static String keyName(int key) {
        switch (key) {
            case GLFW.GLFW_KEY_C: return "C";
            case GLFW.GLFW_KEY_X: return "X";
            case GLFW.GLFW_KEY_V: return "V";
            case GLFW.GLFW_KEY_A: return "A";
            case GLFW.GLFW_KEY_Z: return "Z";
            case GLFW.GLFW_KEY_B: return "B";
            case GLFW.GLFW_KEY_ENTER: return "ENTER";
            case GLFW.GLFW_KEY_UNKNOWN: return "UNKNOWN";
            default: return "key#" + key;
        }
    }

    private static String actionName(int rawAction) {
        switch (rawAction) {
            case GLFW.GLFW_PRESS: return "PRESS";
            case GLFW.GLFW_REPEAT: return "REPEAT";
            case GLFW.GLFW_RELEASE: return "RELEASE";
            default: return "action#" + rawAction;
        }
    }

    private static void assertClassified(DesktopAction want, ShortcutPlatform platform,
                                         int key, int rawAction, int mods) {
        assertEquals(want, DesktopShortcut.classify(platform, key, rawAction, mods),
            row(platform, key, rawAction, mods));
    }

    private static void assertRejected(ShortcutPlatform platform, int rawAction, int mods) {
        for (int key : CLIPBOARD_KEYS) {
            assertClassified(DesktopAction.NONE, platform, key, rawAction, mods);
        }
    }

    // Every platform x key x action x modifier combination in one pass: 5 * 8 * 3 * 16 rows.
    @Test
    void everyPlatformKeyActionAndModifierCombinationFollowsTheFrozenTable() {
        for (ShortcutPlatform platform : ShortcutPlatform.values()) {
            for (int key : SWEPT_KEYS) {
                for (int rawAction : ACTIONS) {
                    for (int mods = 0; mods <= DesktopShortcut.MOD_MASK; mods++) {
                        assertClassified(expected(platform, key, rawAction, mods), platform, key, rawAction, mods);
                    }
                }
            }
        }
    }

    @Test
    void macosSuperCXVMapsToClipboardActions() {
        assertClassified(DesktopAction.COPY, ShortcutPlatform.MACOS,
            GLFW.GLFW_KEY_C, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SUPER);
        assertClassified(DesktopAction.CUT, ShortcutPlatform.MACOS,
            GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SUPER);
        assertClassified(DesktopAction.PASTE, ShortcutPlatform.MACOS,
            GLFW.GLFW_KEY_V, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SUPER);
    }

    @Test
    void nonMacControlCXVMapsToClipboardActions() {
        for (ShortcutPlatform platform : NON_MAC) {
            assertClassified(DesktopAction.COPY, platform,
                GLFW.GLFW_KEY_C, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
            assertClassified(DesktopAction.CUT, platform,
                GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
            assertClassified(DesktopAction.PASTE, platform,
                GLFW.GLFW_KEY_V, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
        }
    }

    @Test
    void wrongPrimaryForThePlatformIsRejected() {
        assertRejected(ShortcutPlatform.MACOS, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
        for (ShortcutPlatform platform : NON_MAC) {
            assertRejected(platform, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SUPER);
        }
    }

    @Test
    void extraModifiersAreRejected() {
        int mac = GLFW.GLFW_MOD_SUPER;
        assertRejected(ShortcutPlatform.MACOS, GLFW.GLFW_PRESS, mac | GLFW.GLFW_MOD_SHIFT);
        assertRejected(ShortcutPlatform.MACOS, GLFW.GLFW_PRESS, mac | GLFW.GLFW_MOD_ALT);
        assertRejected(ShortcutPlatform.MACOS, GLFW.GLFW_PRESS, mac | GLFW.GLFW_MOD_CONTROL);
        assertRejected(ShortcutPlatform.MACOS, GLFW.GLFW_PRESS,
            mac | GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_ALT);
        int other = GLFW.GLFW_MOD_CONTROL;
        for (ShortcutPlatform platform : NON_MAC) {
            assertRejected(platform, GLFW.GLFW_PRESS, other | GLFW.GLFW_MOD_SHIFT);
            assertRejected(platform, GLFW.GLFW_PRESS, other | GLFW.GLFW_MOD_SUPER);
            assertRejected(platform, GLFW.GLFW_PRESS,
                other | GLFW.GLFW_MOD_SHIFT | GLFW.GLFW_MOD_SUPER);
        }
    }

    // Windows reports AltGr as CONTROL|ALT while the character still arrives through WM_CHAR,
    // so a "contains CONTROL" test would copy and insert a character from one keystroke.
    @Test
    void altGrComboIsRejected() {
        int altGr = GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_ALT;
        for (ShortcutPlatform platform : NON_MAC) {
            assertRejected(platform, GLFW.GLFW_PRESS, altGr);
            assertRejected(platform, GLFW.GLFW_PRESS, altGr | GLFW.GLFW_MOD_SHIFT);
        }
        assertRejected(ShortcutPlatform.MACOS, GLFW.GLFW_PRESS, altGr);
        assertRejected(ShortcutPlatform.MACOS, GLFW.GLFW_PRESS, altGr | GLFW.GLFW_MOD_SUPER);
    }

    // A held key repeats; a clipboard action must not.
    @Test
    void repeatAndReleaseDoNotRepeatTheAction() {
        assertRejected(ShortcutPlatform.MACOS, GLFW.GLFW_REPEAT, GLFW.GLFW_MOD_SUPER);
        assertRejected(ShortcutPlatform.MACOS, GLFW.GLFW_RELEASE, GLFW.GLFW_MOD_SUPER);
        for (ShortcutPlatform platform : NON_MAC) {
            assertRejected(platform, GLFW.GLFW_REPEAT, GLFW.GLFW_MOD_CONTROL);
            assertRejected(platform, GLFW.GLFW_RELEASE, GLFW.GLFW_MOD_CONTROL);
        }
    }

    @Test
    void plainKeystrokesWithoutTheModifierAreRejected() {
        for (ShortcutPlatform platform : ShortcutPlatform.values()) {
            assertRejected(platform, GLFW.GLFW_PRESS, 0);
            assertRejected(platform, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SHIFT);
            assertRejected(platform, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_ALT);
        }
    }

    @Test
    void unknownPlatformRejectsEverything() {
        for (int mods = 0; mods <= DesktopShortcut.MOD_MASK; mods++) {
            assertRejected(ShortcutPlatform.UNKNOWN, GLFW.GLFW_PRESS, mods);
        }
    }

    // Select-all, undo and redo have no core capability, so the policy must not claim their
    // keys: the plain key path stays free to handle them if core ever grows one.
    @Test
    void keysWithoutACoreCapabilityStayNone() {
        int[] unclaimed = {
            GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_Z, GLFW.GLFW_KEY_B,
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_UNKNOWN,
        };
        for (int key : unclaimed) {
            assertClassified(DesktopAction.NONE, ShortcutPlatform.MACOS,
                key, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SUPER);
            assertClassified(DesktopAction.NONE, ShortcutPlatform.MACOS,
                key, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_SHIFT);
            for (ShortcutPlatform platform : NON_MAC) {
                assertClassified(DesktopAction.NONE, platform,
                    key, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL);
                assertClassified(DesktopAction.NONE, platform,
                    key, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SHIFT);
            }
        }
    }

    // GLFW_LOCK_KEY_MODS is off in this host, so these bits should never arrive; masking them
    // is depth in defence, and a Caps Lock user must not lose the shortcut if it is ever on.
    @Test
    void lockKeyBitsDoNotAffectTheDecision() {
        int[] locks = {
            GLFW.GLFW_MOD_CAPS_LOCK,
            GLFW.GLFW_MOD_NUM_LOCK,
            GLFW.GLFW_MOD_CAPS_LOCK | GLFW.GLFW_MOD_NUM_LOCK,
        };
        for (int lock : locks) {
            assertClassified(DesktopAction.COPY, ShortcutPlatform.MACOS,
                GLFW.GLFW_KEY_C, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_SUPER | lock);
            assertClassified(DesktopAction.CUT, ShortcutPlatform.WINDOWS,
                GLFW.GLFW_KEY_X, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL | lock);
            assertClassified(DesktopAction.PASTE, ShortcutPlatform.X11,
                GLFW.GLFW_KEY_V, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL | lock);
            assertClassified(DesktopAction.COPY, ShortcutPlatform.WAYLAND,
                GLFW.GLFW_KEY_C, GLFW.GLFW_PRESS, GLFW.GLFW_MOD_CONTROL | lock);
            // The lock bit must not rescue a combination the mask rejects either.
            assertRejected(ShortcutPlatform.MACOS, GLFW.GLFW_PRESS,
                GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_SHIFT | lock);
        }
    }

    @Test
    void platformOfMapsGlfwPlatformTokens() {
        assertEquals(ShortcutPlatform.MACOS, DesktopShortcut.platformOf(GLFW.GLFW_PLATFORM_COCOA));
        assertEquals(ShortcutPlatform.WINDOWS, DesktopShortcut.platformOf(GLFW.GLFW_PLATFORM_WIN32));
        assertEquals(ShortcutPlatform.X11, DesktopShortcut.platformOf(GLFW.GLFW_PLATFORM_X11));
        assertEquals(ShortcutPlatform.WAYLAND, DesktopShortcut.platformOf(GLFW.GLFW_PLATFORM_WAYLAND));
        assertEquals(ShortcutPlatform.UNKNOWN, DesktopShortcut.platformOf(GLFW.GLFW_PLATFORM_NULL));
        assertEquals(ShortcutPlatform.UNKNOWN, DesktopShortcut.platformOf(0));
        assertEquals(ShortcutPlatform.UNKNOWN, DesktopShortcut.platformOf(-1));
    }

    @Test
    void primaryModifierIsExactPerPlatform() {
        assertEquals(GLFW.GLFW_MOD_SUPER, DesktopShortcut.primaryModifier(ShortcutPlatform.MACOS));
        for (ShortcutPlatform platform : NON_MAC) {
            assertEquals(GLFW.GLFW_MOD_CONTROL, DesktopShortcut.primaryModifier(platform),
                "primary modifier for " + platform);
        }
        // Zero can never equal a masked modifier set that contains the pressed primary, which
        // is what makes an unrecognised platform reject rather than default to Control.
        assertEquals(0, DesktopShortcut.primaryModifier(ShortcutPlatform.UNKNOWN));
        assertEquals(0x0f, DesktopShortcut.MOD_MASK);
    }
}
