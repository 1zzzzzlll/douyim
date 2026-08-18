package com.zz.douyin.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
