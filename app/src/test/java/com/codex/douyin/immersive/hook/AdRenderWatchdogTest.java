package com.codex.douyin.immersive.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdRenderWatchdogTest {
    @Test
    public void skipsAfterGraceAndThreeSpacedMissingObservations() {
        AdRenderWatchdog watchdog =
                new AdRenderWatchdog(2_000L, 250L, 900L, 3);

        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 1_000L
        ));
        observeDuringGrace(watchdog, "ad-1");
        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 2_999L
        ));
        assertEquals(0, watchdog.missingObservations());

        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 3_000L
        ));
        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 3_100L
        ));
        assertEquals(1, watchdog.missingObservations());
        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 3_250L
        ));
        assertTrue(watchdog.shouldSkip(
                "ad-1", true, false, false, 3_500L
        ));
        assertEquals(3, watchdog.missingObservations());
    }

    @Test
    public void validSurfaceCancelsCurrentMissingSequence() {
        AdRenderWatchdog watchdog =
                new AdRenderWatchdog(2_000L, 250L, 900L, 3);

        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 1_000L
        ));
        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, true, 1_500L
        ));
        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 5_000L
        ));
        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 6_999L
        ));
        assertEquals(0, watchdog.missingObservations());
    }

    @Test
    public void aidChangeRestartsGracePeriod() {
        AdRenderWatchdog watchdog =
                new AdRenderWatchdog(2_000L, 250L, 900L, 3);

        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 1_000L
        ));
        observeDuringGrace(watchdog, "ad-1");
        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 3_000L
        ));
        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 3_250L
        ));
        assertEquals(2, watchdog.missingObservations());

        assertFalse(watchdog.shouldSkip(
                "ad-2", true, false, false, 3_500L
        ));
        assertEquals(0, watchdog.missingObservations());
        assertFalse(watchdog.shouldSkip(
                "ad-2", true, false, false, 5_499L
        ));
    }

    @Test
    public void pauseOrNonAdvertisementResetsCandidate() {
        AdRenderWatchdog watchdog =
                new AdRenderWatchdog(2_000L, 250L, 900L, 3);

        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 1_000L
        ));
        observeDuringGrace(watchdog, "ad-1");
        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 3_000L
        ));
        assertEquals(1, watchdog.missingObservations());

        assertFalse(watchdog.shouldSkip(
                "ad-1", true, true, false, 3_250L
        ));
        assertEquals(0, watchdog.missingObservations());
        assertFalse(watchdog.shouldSkip(
                "video-1", false, false, false, 10_000L
        ));
        assertEquals(0, watchdog.missingObservations());
    }

    @Test
    public void longSamplingGapRestartsGracePeriod() {
        AdRenderWatchdog watchdog =
                new AdRenderWatchdog(2_000L, 250L, 900L, 3);

        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 1_000L
        ));
        observeDuringGrace(watchdog, "ad-1");
        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 3_000L
        ));
        assertEquals(1, watchdog.missingObservations());

        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 4_000L
        ));
        assertEquals(0, watchdog.missingObservations());
        assertFalse(watchdog.shouldSkip(
                "ad-1", true, false, false, 5_999L
        ));
    }

    private static void observeDuringGrace(
            AdRenderWatchdog watchdog,
            String aid
    ) {
        assertFalse(watchdog.shouldSkip(
                aid, true, false, false, 1_500L
        ));
        assertFalse(watchdog.shouldSkip(
                aid, true, false, false, 2_000L
        ));
        assertFalse(watchdog.shouldSkip(
                aid, true, false, false, 2_500L
        ));
    }
}
