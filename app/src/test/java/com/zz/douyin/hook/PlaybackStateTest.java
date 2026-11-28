package com.zz.douyin.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class PlaybackStateTest {
    @Test
    public void confirmedResumeRequiresSamePlayingEngine() {
        Object engine = new Object();

        assertTrue(PlaybackState.isConfirmedUserResume(
                engine,
                engine,
                engine,
                1
        ));
        assertFalse(PlaybackState.isConfirmedUserResume(
                engine,
                engine,
                engine,
                2
        ));
        assertFalse(PlaybackState.isConfirmedUserResume(
                engine,
                engine,
                new Object(),
                1
        ));
        assertFalse(PlaybackState.isConfirmedUserResume(
                new Object(),
                engine,
                engine,
                1
        ));
    }

    @Test
    public void loopBoundaryRequiresPlaybackToReachTheRealEnd() {
        assertTrue(PlaybackState.isCompletedLoopBoundary(
                9_800L,
                120L,
                10_000L
        ));
        assertFalse(PlaybackState.isCompletedLoopBoundary(
                8_900L,
                120L,
                10_000L
        ));
        assertFalse(PlaybackState.isCompletedLoopBoundary(
                9_800L,
                900L,
                10_000L
        ));
        assertFalse(PlaybackState.isCompletedLoopBoundary(
                9_800L,
                120L,
                900L
        ));
    }

    @Test
    public void contentChangeRequiresTwoDifferentKnownAids() {
        assertTrue(PlaybackState.isDifferentKnownContent(
                "paused-aid",
                "next-aid"
        ));
        assertFalse(PlaybackState.isDifferentKnownContent(
                "paused-aid",
                "paused-aid"
        ));
        assertFalse(PlaybackState.isDifferentKnownContent(
                null,
                "next-aid"
        ));
        assertFalse(PlaybackState.isDifferentKnownContent(
                "paused-aid",
                null
        ));
        assertFalse(PlaybackState.isDifferentKnownContent(
                "unknown",
                "next-aid"
        ));
    }

    @Test
    public void pendingSwitchCandidateMustBeRecentPlayingAndDifferent() {
        Object pausedPlayer = new Object();
        Object nextPlayer = new Object();

        assertTrue(PlaybackState.isRecentSwitchCandidate(
                nextPlayer,
                pausedPlayer,
                1,
                1_000L,
                1_900L
        ));
        assertFalse(PlaybackState.isRecentSwitchCandidate(
                pausedPlayer,
                pausedPlayer,
                1,
                1_000L,
                1_900L
        ));
        assertFalse(PlaybackState.isRecentSwitchCandidate(
                nextPlayer,
                pausedPlayer,
                2,
                1_000L,
                1_900L
        ));
        assertFalse(PlaybackState.isRecentSwitchCandidate(
                nextPlayer,
                pausedPlayer,
                1,
                1_000L,
                2_001L
        ));
    }

    @Test
    public void pageChangeRequiresTwoDifferentNonEmptyIdentities() {
        assertTrue(PlaybackState.isDifferentKnownPage("101#349", "101#350"));
        assertFalse(PlaybackState.isDifferentKnownPage("101#350", "101#350"));
        assertFalse(PlaybackState.isDifferentKnownPage(null, "101#350"));
        assertFalse(PlaybackState.isDifferentKnownPage("101#350", null));
        assertFalse(PlaybackState.isDifferentKnownPage("", "101#350"));
    }

    @Test
    public void activeWatchdogRequiresGenuineClockProgress() {
        assertTrue(PlaybackState.isActivelyAdvancingPosition(0L, 180L));
        assertTrue(PlaybackState.isActivelyAdvancingPosition(1_200L, 1_500L));
        // A prepare_only preloaded engine frozen at 0 ms must not clear user pause.
        assertFalse(PlaybackState.isActivelyAdvancingPosition(0L, 0L));
        assertFalse(PlaybackState.isActivelyAdvancingPosition(500L, 620L));
        assertFalse(PlaybackState.isActivelyAdvancingPosition(-1L, 300L));
    }

    @Test
    public void callbackOwnerPrefersEngineArgumentOverSharedListenerMap() {
        FakeEngine realEngine = new FakeEngine();
        Object listener = new Object();

        assertEquals(
                realEngine,
                PlayerHooks.resolveCallbackOwner(Arrays.asList(realEngine, 1), listener)
        );
        assertNull(
                PlayerHooks.resolveCallbackOwner(Collections.singletonList(1), listener)
        );
    }

    @Test
    public void centeredVideoBoundsAcceptsShiftedWidescreenAndCinemaSurfaces() {
        // 1216 x 2640 portrait screen (centerX=608, centerY=1320)
        // 1. Standard 3:4 video (0,423 - 1216,2044)
        assertTrue(ImmersiveUi.isCenteredVideoBounds(
                0, 1216, 2640, 608, 1320,
                0, 423, 1216, 2044, true
        ));
        // 2. Upper-shifted 16:9 horizontal video (0,569 - 1216,1253; bottom < 1320)
        assertTrue(ImmersiveUi.isCenteredVideoBounds(
                0, 1216, 2640, 608, 1320,
                0, 569, 1216, 1253, true
        ));
        // 3. 2.39:1 cinema video (0,950 - 1216,1458; area < screenArea / 5)
        assertTrue(ImmersiveUi.isCenteredVideoBounds(
                0, 1216, 2640, 608, 1320,
                0, 950, 1216, 1458, true
        ));
        // 4. Top banner ImageView (0,0 - 1216,684) with allowWidescreenBand=false must fail
        assertFalse(ImmersiveUi.isCenteredVideoBounds(
                0, 1216, 2640, 608, 1320,
                0, 0, 1216, 684, false
        ));
        // 5. Right-bar avatar / icon must fail even with allowWidescreenBand=true
        assertFalse(ImmersiveUi.isCenteredVideoBounds(
                0, 1216, 2640, 608, 1320,
                1000, 1000, 1180, 1180, true
        ));
    }

    public static final class FakeEngine {
        public int getPlaybackState() {
            return 1;
        }
    }
}
