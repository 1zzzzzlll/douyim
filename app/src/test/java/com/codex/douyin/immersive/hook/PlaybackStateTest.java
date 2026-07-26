package com.codex.douyin.immersive.hook;

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
}
