package com.zz.douyin.hook;

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
    private static final long PENDING_SWITCH_PLAYER_MAX_AGE_MS = 1_000L;

    private static volatile boolean playing = true;
    private static volatile boolean userPaused;
    private static volatile long userPausedAt;
    private static volatile WeakReference<Object> userPausedEngine =
            new WeakReference<>(null);
    private static volatile String userPausedAid;
    private static volatile long expectedVideoSwitchUntil;
    private static volatile long autoSwitchUntil;
    private static volatile WeakReference<Object> engine = new WeakReference<>(null);
    private static volatile WeakReference<Object> pendingSwitchPlayer =
            new WeakReference<>(null);
    private static volatile long pendingSwitchPlayerAt;
    private static final Map<Object, Long> CANDIDATE_PLAYERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile long generation;
    private static volatile int lastResolvedState = Integer.MIN_VALUE;
    private static volatile long pausedAt;
    private static volatile long lastPauseSignalAt;
    private static volatile WeakReference<Object> lastPauseSignalEngine =
            new WeakReference<>(null);

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

    private static synchronized void markPlaying(
            Object player,
            boolean explicitPlayCall,
            boolean confirmedPlaying
    ) {
        long now = SystemClock.uptimeMillis();
        if (player != null) {
            // Keep a playing candidate even when a paused-feed transition has
            // not opened its acceptance window yet. The visible aid check can
            // then recover the correct active engine after the page changes.
            CANDIDATE_PLAYERS.put(player, now);
        }
        boolean clearUserPause = false;
        boolean sameEngineResumed = false;
        if (userPaused) {
            Object pausedPlayer = userPausedEngine.get();
            sameEngineResumed =
                    player != null
                            && player == pausedPlayer
                            && (explicitPlayCall
                            || confirmedPlaying
                            || playbackState(player) == 1);
            if (now > expectedVideoSwitchUntil && !sameEngineResumed) {
                if (player != null
                        && (confirmedPlaying || playbackState(player) == 1)) {
                    pendingSwitchPlayer =
                            new WeakReference<>(player);
                    pendingSwitchPlayerAt = now;
                }
                return;
            }
            clearUserPause = true;
        }
        if (player != null) {
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
        if (clearUserPause && userPaused) {
            userPaused = false;
            userPausedAt = 0L;
            userPausedEngine.clear();
            userPausedAid = null;
            clearPendingSwitchPlayer();
            expectedVideoSwitchUntil = 0L;
            Log.i(DouyinModule.TAG,
                    sameEngineResumed
                            ? "cleared user pause after confirmed resume"
                            : "new video confirmed after paused swipe");
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

    static synchronized void paused(Object player) {
        Object current = engine.get();
        if (player != null && current != null && player != current) {
            Log.d(DouyinModule.TAG, "ignored pause from stale player "
                    + player.getClass().getName());
            return;
        }
        if (player != null && current == null) {
            engine = new WeakReference<>(player);
        }
        lastPauseSignalAt = SystemClock.uptimeMillis();
        lastPauseSignalEngine = new WeakReference<>(player);
        playing = false;
        if (pausedAt == 0L) {
            pausedAt = lastPauseSignalAt;
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

    static int engineState(Object candidate) {
        return playbackState(candidate);
    }

    static synchronized boolean confirmUserPaused(
            Object expectedEngine,
            long uptimeMillis,
            int initialState,
            String aid
    ) {
        if (expectedEngine == null) {
            return false;
        }
        boolean matchingCallback =
                lastPauseSignalEngine.get() == expectedEngine
                        && lastPauseSignalAt >= uptimeMillis;
        boolean confirmed =
                engine.get() == expectedEngine
                && (matchingCallback
                || (initialState == 1
                && playbackState(expectedEngine) == 2));
        if (!confirmed) {
            return false;
        }
        userPaused = true;
        userPausedAt = SystemClock.uptimeMillis();
        userPausedEngine = new WeakReference<>(expectedEngine);
        userPausedAid = knownAid(aid) ? aid : null;
        clearPendingSwitchPlayer();
        expectedVideoSwitchUntil = 0L;
        autoSwitchUntil = 0L;
        playing = false;
        pausedAt = 0L;
        generation++;
        Log.i(DouyinModule.TAG, "playback=user-paused");
        return true;
    }

    static synchronized boolean shouldKeepUiHidden() {
        if (userPaused) {
            Object pausedPlayer = userPausedEngine.get();
            long pauseAge = SystemClock.uptimeMillis() - userPausedAt;
            if (pausedPlayer == null) {
                if (pauseAge < 1_500L) {
                    return false;
                }
                userPaused = false;
                userPausedAt = 0L;
                userPausedEngine.clear();
                userPausedAid = null;
                clearPendingSwitchPlayer();
                playing = true;
                pausedAt = 0L;
                Log.w(DouyinModule.TAG,
                        "cleared unbacked stale user pause");
            } else if (pauseAge < 600L || playbackState(pausedPlayer) != 1) {
                return false;
            } else {
                userPaused = false;
                userPausedAt = 0L;
                userPausedEngine.clear();
                userPausedAid = null;
                clearPendingSwitchPlayer();
                playing = true;
                pausedAt = 0L;
                Log.i(DouyinModule.TAG,
                        "cleared stale user pause because the same video is playing");
            }
        }
        // Host engines emit pause/stop/release callbacks for preloaded and recycled
        // players. Only an explicitly confirmed user pause may expose the host UI;
        // otherwise the visible current renderer remains in immersive mode.
        return true;
    }

    static synchronized boolean consumeLoopBoundary() {
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
                    isCompletedLoopBoundary(lastPosition, position, duration);
            long now = SystemClock.uptimeMillis();
            if (completionArmed
                    && now - lastAutoAdvanceAt > 1_500L
                    && loopBoundary) {
                completionArmed = false;
                lastAutoAdvanceAt = now;
                autoSwitchUntil = now + AUTO_SWITCH_GRACE_MS;
                Log.i(DouyinModule.TAG,
                        "completed loop boundary detected: "
                                + position + "/" + duration);
                lastPosition = position;
                return true;
            }

            if (position + 1_000L < lastPosition) {
                completionArmed = true;
            }
            lastPosition = position;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Player variants without both timing methods rely on completion callbacks.
        }
        return false;
    }

    static boolean isCompletedLoopBoundary(
            long previousPosition,
            long currentPosition,
            long duration
    ) {
        if (duration < 1_000L
                || previousPosition < 0L
                || currentPosition < 0L) {
            return false;
        }
        long endWindow = Math.max(250L, Math.min(750L, duration / 20L));
        long startWindow = Math.max(100L, Math.min(500L, duration / 20L));
        return previousPosition >= duration - endWindow
                && currentPosition <= startWindow;
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

    static synchronized boolean confirmUserPlaying(Object expectedEngine) {
        if (!userPaused
                || !isConfirmedUserResume(
                expectedEngine,
                userPausedEngine.get(),
                engine.get(),
                playbackState(expectedEngine)
        )) {
            return false;
        }
        markPlaying(expectedEngine, false, true);
        Log.i(DouyinModule.TAG, "playback=user-resumed");
        return true;
    }

    static boolean isConfirmedUserResume(
            Object expectedEngine,
            Object pausedEngine,
            Object currentEngine,
            int currentState
    ) {
        return expectedEngine != null
                && expectedEngine == pausedEngine
                && expectedEngine == currentEngine
                && currentState == 1;
    }

    static synchronized void userPlaying() {
        userPaused = false;
        userPausedAt = 0L;
        userPausedEngine.clear();
        userPausedAid = null;
        clearPendingSwitchPlayer();
        expectedVideoSwitchUntil = 0L;
        markPlaying(null, false, true);
        Log.i(DouyinModule.TAG, "playback=user-resumed");
    }

    static synchronized boolean clearUserPauseForContentChange(String visibleAid) {
        String pausedAid = userPausedAid;
        if (!userPaused
                || !isDifferentKnownContent(pausedAid, visibleAid)) {
            return false;
        }
        userPaused = false;
        userPausedAt = 0L;
        userPausedEngine.clear();
        userPausedAid = null;
        clearPendingSwitchPlayer();
        expectedVideoSwitchUntil = 0L;
        markPlaying(null, false, true);
        Log.i(DouyinModule.TAG,
                "cleared user pause after feed item changed: "
                        + pausedAid + " -> " + visibleAid);
        return true;
    }

    static boolean isDifferentKnownContent(String previousAid, String currentAid) {
        return knownAid(previousAid)
                && knownAid(currentAid)
                && !previousAid.equals(currentAid);
    }

    private static boolean knownAid(String aid) {
        return aid != null
                && !aid.isEmpty()
                && !"unknown".equals(aid);
    }

    static synchronized void beginAutoSwitch() {
        long now = SystemClock.uptimeMillis();
        autoSwitchUntil = now + AUTO_SWITCH_GRACE_MS;
        if (userPaused) {
            expectedVideoSwitchUntil = autoSwitchUntil;
            Log.d(DouyinModule.TAG, "waiting for new video after paused swipe");
            Object candidate = pendingSwitchPlayer.get();
            Object pausedPlayer = userPausedEngine.get();
            int candidateState = playbackState(candidate);
            if (isRecentSwitchCandidate(
                    candidate,
                    pausedPlayer,
                    candidateState,
                    pendingSwitchPlayerAt,
                    now
            )) {
                clearPendingSwitchPlayer();
                Log.i(DouyinModule.TAG,
                        "adopting pending player after paused feed switch");
                markPlaying(candidate, false, true);
            } else if (candidate == null
                    || now - pendingSwitchPlayerAt
                    > PENDING_SWITCH_PLAYER_MAX_AGE_MS) {
                clearPendingSwitchPlayer();
            }
        }
    }

    static boolean isRecentSwitchCandidate(
            Object candidate,
            Object pausedPlayer,
            int candidateState,
            long candidateAt,
            long now
    ) {
        return candidate != null
                && candidate != pausedPlayer
                && candidateState == 1
                && candidateAt > 0L
                && now >= candidateAt
                && now - candidateAt <= PENDING_SWITCH_PLAYER_MAX_AGE_MS;
    }

    private static void clearPendingSwitchPlayer() {
        pendingSwitchPlayer.clear();
        pendingSwitchPlayerAt = 0L;
    }

    static synchronized void confirmVideoSwitch() {
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
