package io.github.timer_err.qml4j.demo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The host's logical-coordinate contract as pure arithmetic. Five of the nine rows below
// (Windows/X11, fractional, non-uniform) have no hardware on this project, so this is their
// only executable verification -- the rest are also covered by real Retina measurements.
class DesktopMetricsTest {

    private static final float EPS = 1e-3f;

    // Every published snapshot must let the logical root cover the framebuffer exactly:
    // a rounded root would leave a sliver of surface unpainted (see the 1001 @ 1.5 row).
    private static void assertCoversFramebuffer(DesktopMetrics m, int fbW, int fbH) {
        assertEquals(fbW, m.rootWidth() * m.uiScale(), EPS);
        assertEquals(fbH, m.rootHeight() * m.uiScale(), EPS);
    }

    @Test
    void macOsRetinaMapsFramebufferPixelsBackToWindowPoints() {
        DesktopMetrics m = DesktopMetrics.of(1440, 1440, 720, 720, 2f, 2f);
        assertTrue(m.valid());
        assertEquals(2f, m.uiScale(), 0f);
        assertEquals(720f, m.rootWidth(), 0f);
        assertEquals(720f, m.rootHeight(), 0f);
        // The window point IS the logical unit here, so the raw cursor passes through.
        assertEquals(125f, m.toLogicalX(125), 0f);
        assertEquals(300f, m.toLogicalY(300), 0f);
        assertCoversFramebuffer(m, 1440, 1440);
    }

    @Test
    void unscaledDisplayIsAnIdentityMapping() {
        DesktopMetrics m = DesktopMetrics.of(720, 720, 720, 720, 1f, 1f);
        assertTrue(m.valid());
        assertEquals(1f, m.uiScale(), 0f);
        assertEquals(720f, m.rootWidth(), 0f);
        assertEquals(720f, m.rootHeight(), 0f);
        assertEquals(123.5f, m.toLogicalX(123.5), 0f);
        assertEquals(456.25f, m.toLogicalY(456.25), 0f);
        assertCoversFramebuffer(m, 720, 720);
    }

    @Test
    void windowsAt150PercentWithoutScaleToMonitorShrinksRoot() {
        DesktopMetrics m = DesktopMetrics.of(720, 720, 720, 720, 1.5f, 1.5f);
        assertEquals(1.5f, m.uiScale(), 0f);
        assertEquals(480f, m.rootWidth(), EPS);
        assertEquals(480f, m.rootHeight(), EPS);
        assertEquals(80f, m.toLogicalX(120), EPS);
        assertEquals(80f, m.toLogicalY(120), EPS);
        assertCoversFramebuffer(m, 720, 720);
    }

    @Test
    void windowsAt150PercentWithScaleToMonitorKeepsTheRequestedLogicalSize() {
        DesktopMetrics m = DesktopMetrics.of(1080, 1080, 1080, 1080, 1.5f, 1.5f);
        assertEquals(1.5f, m.uiScale(), 0f);
        assertEquals(720f, m.rootWidth(), EPS);
        assertEquals(720f, m.rootHeight(), EPS);
        assertEquals(120f, m.toLogicalX(180), EPS);
        assertEquals(120f, m.toLogicalY(180), EPS);
        assertCoversFramebuffer(m, 1080, 1080);
    }

    @Test
    void fractionalScaleOf125KeepsTheRequestedLogicalSize() {
        DesktopMetrics m = DesktopMetrics.of(900, 900, 900, 900, 1.25f, 1.25f);
        assertEquals(1.25f, m.uiScale(), 0f);
        assertEquals(720f, m.rootWidth(), EPS);
        assertEquals(720f, m.rootHeight(), EPS);
        assertEquals(80f, m.toLogicalX(100), EPS);
        assertEquals(80f, m.toLogicalY(100), EPS);
        assertCoversFramebuffer(m, 900, 900);
    }

    @Test
    void oddFramebufferKeepsAFractionalRootSoTheSurfaceStaysCovered() {
        DesktopMetrics m = DesktopMetrics.of(1001, 1001, 1001, 1001, 1.5f, 1.5f);
        assertEquals(1001f / 1.5f, m.rootWidth(), 0f);
        assertEquals(1001f / 1.5f, m.rootHeight(), 0f);
        // Rounding to 667 would leave 1001 - 667 * 1.5 = 0.5 px unpainted.
        assertCoversFramebuffer(m, 1001, 1001);
        assertEquals(200f, m.toLogicalX(300), EPS);
        assertEquals(200f, m.toLogicalY(300), EPS);
    }

    @Test
    void nonUniformContentScaleRendersUniformlyFromTheXAxis() {
        DesktopMetrics m = DesktopMetrics.of(720, 720, 720, 720, 1f, 1.5f);
        assertTrue(m.nonUniformScale());
        assertEquals(1f, m.uiScale(), 0f);
        // A per-axis CTM would stretch a square logical box on a square-pixel display.
        assertEquals(720f, m.rootWidth(), 0f);
        assertEquals(720f, m.rootHeight(), 0f);
        assertEquals(200f, m.toLogicalX(200), 0f);
        assertEquals(200f, m.toLogicalY(200), 0f);
        assertCoversFramebuffer(m, 720, 720);
    }

    @Test
    void uniformContentScaleIsNotReportedAsNonUniform() {
        assertFalse(DesktopMetrics.of(1440, 1440, 720, 720, 2f, 2f).nonUniformScale());
        assertFalse(DesktopMetrics.of(720, 720, 720, 720, 1.5f, 1.5f).nonUniformScale());
    }

    @Test
    void pointerMappingStaysPerAxisWhenTheAxesRoundDifferently() {
        // A window whose two axes do not share the same framebuffer ratio: 1440/721 != 1382/691.
        DesktopMetrics m = DesktopMetrics.of(1440, 1382, 721, 691, 2f, 2f);
        assertEquals(720f, m.rootWidth(), 0f);
        assertEquals(691f, m.rootHeight(), 0f);
        // cursor * root / window, per axis -- not cursor / uiScale (which would give 180.25).
        assertEquals(360f, m.toLogicalX(360.5), EPS);
        assertEquals(345.5f, m.toLogicalY(345.5), EPS);
        assertCoversFramebuffer(m, 1440, 1382);
    }

    @Test
    void movingToADisplayWithADifferentScaleKeepsTheLogicalSize() {
        DesktopMetrics retina = DesktopMetrics.of(1440, 1440, 720, 720, 2f, 2f);
        DesktopMetrics standard = DesktopMetrics.of(720, 720, 720, 720, 1f, 1f);
        assertEquals(retina.rootWidth(), standard.rootWidth(), 0f);
        assertEquals(retina.rootHeight(), standard.rootHeight(), 0f);
        assertEquals(2f, retina.uiScale(), 0f);
        assertEquals(1f, standard.uiScale(), 0f);
        assertEquals(200f, retina.toLogicalX(200), 0f);
        assertEquals(200f, standard.toLogicalX(200), 0f);
    }

    @Test
    void degenerateContentScaleFallsBackToOne() {
        assertEquals(1f, DesktopMetrics.of(720, 720, 720, 720, 0f, 0f).uiScale(), 0f);
        assertEquals(1f, DesktopMetrics.of(720, 720, 720, 720, -2f, -2f).uiScale(), 0f);
        assertEquals(1f, DesktopMetrics.of(720, 720, 720, 720, Float.NaN, Float.NaN).uiScale(), 0f);
        assertEquals(1f, DesktopMetrics.of(720, 720, 720, 720,
            Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY).uiScale(), 0f);
        assertEquals(1f, DesktopMetrics.of(720, 720, 720, 720,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY).uiScale(), 0f);
    }

    @Test
    void aFallbackScaleStillYieldsAnIdentityMapping() {
        DesktopMetrics m = DesktopMetrics.of(720, 720, 720, 720, Float.NaN, Float.NaN);
        assertTrue(m.valid());
        assertEquals(720f, m.rootWidth(), 0f);
        assertEquals(150f, m.toLogicalX(150), 0f);
        assertCoversFramebuffer(m, 720, 720);
    }

    @Test
    void aMinimizedWindowIsNotAValidSnapshot() {
        assertFalse(DesktopMetrics.of(0, 0, 0, 0, 2f, 2f).valid());
    }

    @Test
    void anyZeroAxisInvalidatesTheWholeSnapshot() {
        assertFalse(DesktopMetrics.of(0, 720, 720, 720, 1f, 1f).valid());
        assertFalse(DesktopMetrics.of(720, 0, 720, 720, 1f, 1f).valid());
        assertFalse(DesktopMetrics.of(720, 720, 0, 720, 1f, 1f).valid());
        assertFalse(DesktopMetrics.of(720, 720, 720, 0, 1f, 1f).valid());
        assertTrue(DesktopMetrics.of(720, 720, 720, 720, 1f, 1f).valid());
    }

    @Test
    void framebufferSizeIsReportedInPixelsForTheSurface() {
        DesktopMetrics m = DesktopMetrics.of(1440, 1382, 720, 691, 2f, 2f);
        assertEquals(1440, m.fbWidth());
        assertEquals(1382, m.fbHeight());
    }
}
