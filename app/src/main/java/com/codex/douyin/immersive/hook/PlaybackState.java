package com.codex.douyin.immersive.hook;

import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

final class PlaybackState {
    private static final long TRANSITION_GRACE_MS = 2_000L;
    private static final long AUTO_SWITCH_GRACE_MS = 2_500L;
    private static final long ERROR_CONFIRM_MS = 900L;

    private static volatile boolean playing = true;
    private static volatile boolean userPaused;
    private static volatile long userPausedAt;
    private static volatile WeakReference<Object> userPausedEngine =
            new WeakReference<>(null);
    private static volatile long expectedVideoSwitchUntil;
    private static volatile long autoSwitchUntil;
    private static volatile WeakReference<Object> engine = new WeakReference<>(null);
    private static final Map<Object, Long> CANDIDATE_PLAYERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile long generation;
    private static volatile int lastResolvedState = Integer.MIN_VALUE;
    private static volatile long pausedAt;

    private static volatile Object trackedPlayer;
    private static volatile long trackedDuration;
    private static volatile long lastPosition;
    private static volatile boolean completionArmed = true;
    private static volatile long lastAutoAdvanceAt;

    private static volatile WeakReference<Object> errorPlayer = new WeakReference<>(null);
    private static volatile long errorAt;

    private PlaybackState() {
    }

    static void playing(Object player) {
        markPlaying(player, true, false);
    }

    static void playingFromCallback(Object player) {
        markPlaying(player, false, true);
    }

    private static void markPlaying(
            Object player,
            boolean explicitPlayCall,
            boolean confirmedPlaying
    ) {
        if (player != null) {
            CANDIDATE_PLAYERS.put(player, SystemClock.uptimeMillis());
            Object current = engine.get();
            int state = playbackState(player);
            boolean adopt =
                    current == null
                            || current == player
                            || confirmedPlaying
                            || state == 1;
            if (adopt) {
                engine = new WeakReference<>(player);
                if (explicitPlayCall || player != current) {
                    resetProgressTracking(player);
                }
            } else {
                return;
            }
        }
        if (userPaused) {
            if (SystemClock.uptimeMillis() > expectedVideoSwitchUntil) {
                return;
            }
            userPaused = false;
            userPausedAt = 0L;
            userPausedEngine.clear();
            expectedVideoSwitchUntil = 0L;
            Log.i(DouyinModule.TAG, "new video confirmed after paused swipe");
        }
        playing = true;
        pausedAt = 0L;
        errorAt = 0L;
        errorPlayer.clear();
        autoSwitchUntil = 0L;
        generation++;
        Log.i(DouyinModule.TAG, "playback=playing");
        ImmersiveUi.onPlaybackChanged(true);
    }

    static void paused(Object player) {
        Object current = engine.get();
        if (player != null && current != null && player != current) {
            Log.d(DouyinModule.TAG, "ignored pause from stale player "
                    + player.getClass().getName());
            return;
        }
        if (player != null && current == null) {
            engine = new WeakReference<>(player);
        }
        playing = false;
        if (pausedAt == 0L) {
            pausedAt = SystemClock.uptimeMillis();
        }
        generation++;
        Log.i(DouyinModule.TAG, "playback=paused");
        ImmersiveUi.onPlaybackChanged(false);
    }

    static void completed(Object player) {
        if (userPaused) {
            return;
        }
        Object current = engine.get();
        if (player == null || (current != null && player != current)) {
            return;
        }
        if (current == null) {
            engine = new WeakReference<>(player);
        }
        beginAutoSwitch();
        playing = false;
        pausedAt = SystemClock.uptimeMillis();
        generation++;
        Log.i(DouyinModule.TAG, "playback=completed");
        ImmersiveUi.onPlaybackCompleted("completion callback");
    }

    static void error(Object player) {
        if (userPaused) {
            return;
        }
        Object current = engine.get();
        if (player == null || (current != null && player != current)) {
            return;
        }
        if (current == null) {
            engine = new WeakReference<>(player);
        }
        playing = false;
        pausedAt = SystemClock.uptimeMillis();
        errorPlayer = new WeakReference<>(player);
        errorAt = pausedAt;
        autoSwitchUntil = pausedAt + AUTO_SWITCH_GRACE_MS;
        generation++;
        Log.w(DouyinModule.TAG, "playback=error");
    }

    static boolean isPlaying() {
        if (userPaused) {
            return false;
        }
        Object player = resolveActiveEngine();
        int state = playbackState(player);
        if (state != -1) {
            if (state != lastResolvedState) {
                lastResolvedState = state;
                Log.i(DouyinModule.TAG, "resolved playback state=" + state
                        + " from " + player.getClass().getName());
            }
            if (state == 1) {
                playing = true;
                pausedAt = 0L;
            } else if (state == 0 || state == 2 || state == 3) {
                playing = false;
                if (pausedAt == 0L) {
                    pausedAt = SystemClock.uptimeMillis();
                }
            }
        }
        return playing;
    }

    static boolean isUserPaused() {
        return userPaused;
    }

    static boolean shouldKeepUiHidden() {
        if (userPaused) {
            Object pausedPlayer = userPausedEngine.get();
            if (pausedPlayer == null
                    || SystemClock.uptimeMillis() - userPausedAt < 600L
                    || playbackState(pausedPlayer) != 1) {
                return false;
            }
            userPaused = false;
            userPausedAt = 0L;
            userPausedEngine.clear();
            playing = true;
            pausedAt = 0L;
            Log.i(DouyinModule.TAG,
                    "cleared stale user pause because the same video is playing");
        }
        long now = SystemClock.uptimeMillis();
        return now < autoSwitchUntil
                || isPlaying()
                || (pausedAt != 0L && now - pausedAt < TRANSITION_GRACE_MS);
    }

    static synchronized boolean consumeNearCompletion() {
        if (userPaused) {
            return false;
        }
        Object player = resolveActiveEngine();
        if (player == null || playbackState(player) != 1) {
            return false;
        }
        try {
            long duration = invokeLong(player, "getDuration");
            long position = invokeLong(player, "getCurrentPlaybackTime");
            if (duration < 1_000L || position < 0L) {
                return false;
            }

            boolean samePlayback =
                    player == trackedPlayer && duration == trackedDuration;
            if (!samePlayback) {
                trackedPlayer = player;
                trackedDuration = duration;
                lastPosition = position;
                completionArmed = true;
                Log.i(DouyinModule.TAG,
                        "tracking playback: " + player.getClass().getName()
                                + ", duration=" + duration);
                return false;
            }

            boolean loopBoundary =
                    lastPosition >= Math.max(3_000L, duration * 3L / 5L)
                            && position <= Math.min(1_500L, duration / 10L);
            long threshold = Math.max(700L, Math.min(1_100L, duration / 15L));
            boolean nearEnd =
                    position >= Math.max(1_000L, duration / 2L)
                            && duration - position <= threshold;
            long now = SystemClock.uptimeMillis();
            if (completionArmed
                    && now - lastAutoAdvanceAt > 1_500L
                    && (nearEnd || loopBoundary)) {
                completionArmed = false;
                lastAutoAdvanceAt = now;
                autoSwitchUntil = now + AUTO_SWITCH_GRACE_MS;
                Log.i(DouyinModule.TAG,
                        (loopBoundary ? "loop boundary detected: " : "near completion: ")
                                + position + "/" + duration);
                lastPosition = position;
                return true;
            }

            if (position + 1_000L < lastPosition && !loopBoundary) {
                completionArmed = true;
            }
            lastPosition = position;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Player variants without both timing methods rely on completion callbacks.
        }
        return false;
    }

    static synchronized boolean consumePlaybackError() {
        long startedAt = errorAt;
        if (startedAt == 0L
                || userPaused
                || SystemClock.uptimeMillis() - startedAt < ERROR_CONFIRM_MS) {
            return false;
        }
        Object failed = errorPlayer.get();
        Object current = engine.get();
        if (failed == null || failed != current) {
            errorAt = 0L;
            return false;
        }
        int state = playbackState(current);
        if (state == 1 || state == 4) {
            errorAt = 0L;
            return false;
        }
        errorAt = 0L;
        lastAutoAdvanceAt = SystemClock.uptimeMillis();
        autoSwitchUntil = lastAutoAdvanceAt + AUTO_SWITCH_GRACE_MS;
        Log.w(DouyinModule.TAG, "confirmed playback error; advancing");
        return true;
    }

    private static long invokeLong(Object target, String name)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        Object value = method.invoke(target);
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private static int playbackState(Object player) {
        if (player == null) {
            return -1;
        }
        try {
            long value = invokeLong(player, "getPlaybackState");
            return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                    ? (int) value
                    : -1;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1;
        }
    }

    private static Object resolveActiveEngine() {
        Object current = engine.get();
        if (current != null && playbackState(current) == 1) {
            return current;
        }
        Object best = null;
        long bestAt = Long.MIN_VALUE;
        synchronized (CANDIDATE_PLAYERS) {
            for (Map.Entry<Object, Long> entry : CANDIDATE_PLAYERS.entrySet()) {
                Object candidate = entry.getKey();
                if (candidate != null
                        && entry.getValue() != null
                        && entry.getValue() > bestAt
                        && playbackState(candidate) == 1) {
                    best = candidate;
                    bestAt = entry.getValue();
                }
            }
        }
        if (best != null) {
            engine = new WeakReference<>(best);
            if (best != trackedPlayer) {
                resetProgressTracking(best);
            }
            return best;
        }
        return current;
    }

    static Object engine() {
        return engine.get();
    }

    static void userPaused() {
        userPaused = true;
        userPausedAt = SystemClock.uptimeMillis();
        userPausedEngine = new WeakReference<>(engine.get());
        expectedVideoSwitchUntil = 0L;
        autoSwitchUntil = 0L;
        playing = false;
        pausedAt = 0L;
        generation++;
        Log.i(DouyinModule.TAG, "playback=user-paused");
    }

    static void userPlaying() {
        userPaused = false;
        userPausedAt = 0L;
        userPausedEngine.clear();
        expectedVideoSwitchUntil = 0L;
        markPlaying(null, false, true);
        Log.i(DouyinModule.TAG, "playback=user-resumed");
    }

    static void beginAutoSwitch() {
        long now = SystemClock.uptimeMillis();
        autoSwitchUntil = now + AUTO_SWITCH_GRACE_MS;
        if (userPaused) {
            expectedVideoSwitchUntil = autoSwitchUntil;
            Log.d(DouyinModule.TAG, "waiting for new video after paused swipe");
        }
    }

    static void confirmVideoSwitch() {
        autoSwitchUntil = 0L;
        errorAt = 0L;
        resetProgressTracking(engine.get());
    }

    private static synchronized void resetProgressTracking(Object player) {
        trackedPlayer = player;
        trackedDuration = 0L;
        lastPosition = 0L;
        completionArmed = true;
    }

    static long generation() {
        return generation;
    }
}
