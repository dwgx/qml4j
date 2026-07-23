package io.github.timer_err.qml4j.demo;

// An immutable snapshot of the three quantities GLFW reports for a window -- framebuffer size
// in pixels, window size in screen coordinates, content scale as a DPI ratio -- and the logical
// (QML unit) coordinate space derived from them:
//
//     uiScale = content scale             uniform, taken from the x axis
//     root    = framebuffer / uiScale     float, never rounded
//     pointer = cursor * root / window    per axis
//
// The framebuffer/window ratio is not a DPI scale -- it is 1 on Windows and X11 at any DPI --
// so it never sets uiScale; its only job is to bring a cursor's screen coordinate into the same
// space as the root. Deliberately free of GLFW and Skija: the platforms this contract has to
// hold on (Windows/X11 fractional scaling) are not reachable from here except as arithmetic.
final class DesktopMetrics {

    // Content scales are quantised well above this (1.0, 1.25, 1.5, 2.0), so anything larger
    // is a genuine per-axis difference rather than float noise.
    private static final float SCALE_EPSILON = 0.01f;

    private final int fbW;
    private final int fbH;
    private final int winW;
    private final int winH;
    private final float uiScale;
    private final float scaleY;
    private final float rootW;
    private final float rootH;

    private DesktopMetrics(int fbW, int fbH, int winW, int winH, float scaleX, float scaleY) {
        this.fbW = fbW;
        this.fbH = fbH;
        this.winW = winW;
        this.winH = winH;
        this.uiScale = sanitize(scaleX);
        this.scaleY = sanitize(scaleY);
        this.rootW = fbW / this.uiScale;
        this.rootH = fbH / this.uiScale;
    }

    static DesktopMetrics of(int fbW, int fbH, int winW, int winH, float scaleX, float scaleY) {
        return new DesktopMetrics(fbW, fbH, winW, winH, scaleX, scaleY);
    }

    // Refresh-time callers reject a zero extent without replacing the last valid snapshot.
    boolean valid() {
        return fbW > 0 && fbH > 0 && winW > 0 && winH > 0;
    }

    int fbWidth() {
        return fbW;
    }

    int fbHeight() {
        return fbH;
    }

    float uiScale() {
        return uiScale;
    }

    float rootWidth() {
        return rootW;
    }

    float rootHeight() {
        return rootH;
    }

    float toLogicalX(double cursorX) {
        return (float) (cursorX * rootW / winW);
    }

    float toLogicalY(double cursorY) {
        return (float) (cursorY * rootH / winH);
    }

    // Rendering is uniform (the renderer's picture cache keys on a single device scale), so a
    // per-axis content scale is reported rather than honoured.
    boolean nonUniformScale() {
        return Math.abs(uiScale - scaleY) > SCALE_EPSILON;
    }

    // `> 0` already excludes NaN (NaN > 0 is false), so only infinity needs its own guard.
    private static float sanitize(float scale) {
        return (scale > 0f && !Float.isInfinite(scale)) ? scale : 1f;
    }
}
