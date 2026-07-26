package com.codex.douyin.immersive.hook;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

final class ImmersiveUi {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long SCAN_INTERVAL_MS = 100L;
    private static final long FALLBACK_CONTENT_CHECK_INTERVAL_MS = 900L;
    private static final Map<View, SavedView> HIDDEN =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Integer> ROOT_SYSTEM_UI =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, ViewGroup.LayoutParams> EXPANDED_VIEWPORTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static WeakReference<Activity> active = new WeakReference<>(null);
    private static WeakReference<View> activeRoot = new WeakReference<>(null);
    private static boolean scanScheduled;
    private static boolean swipeRunning;
    private static long swipeToken;
    private static boolean videoMissingLogged;
    private static boolean activityResolveErrorLogged;
    private static long lastScanFailureAt;
    private static long transitionBoostUntil;
    private static float touchDownX;
    private static float touchDownY;
    private static long touchDownAt;
    private static long touchGestureToken;
    private static String touchDownAid;
    private static Object touchDownEngine;
    private static int touchDownEngineState;
    private static WeakReference<TextView> downloadButton =
            new WeakReference<>(null);
    private static long lastHandledTouchDownTime;
    private static long lastHandledTouchUpAt;
    private static long contentCheckNotBefore;
    private static long contentCheckUntil;
    private static long lastContentCheckAt;
    private static long lastFilteredSwipeAt;
    private static long lastSwipeAt;
    private static String filterCandidateAid;
    private static String filterCandidateReason;
    private static int filterCandidateCount;
    private static long filterCandidateAt;
    private static String lastAcceptedAid;

    private ImmersiveUi() {
    }

    static void onActivityResumed(Activity activity) {
        if (activity == null) {
            return;
        }
        MAIN.post(() -> {
            active = new WeakReference<>(activity);
            activeRoot = new WeakReference<>(activity.getWindow().getDecorView());
            Log.i(DouyinModule.TAG, "activity resumed: " + activity.getClass().getName());
            armContentFilter(1_000L);
            scheduleScan(120L);
        });
    }

    static void onActivityPaused(Activity activity) {
        MAIN.post(() -> {
            Activity current = active.get();
            if (current == activity) {
                removeDownloadButton();
                restoreAll(current);
                active.clear();
                activeRoot.clear();
            }
        });
    }

    static void onFeedPageSelected() {
        MAIN.post(() -> {
            transitionBoostUntil = SystemClock.uptimeMillis() + 1_200L;
            PlaybackState.beginAutoSwitch();
            armContentFilter(120L);
            scheduleScan(0L);
        });
    }

    static void onActivityTouch(Activity activity, MotionEvent event) {
        if (swipeRunning) {
            return;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touchGestureToken++;
            touchDownX = event.getRawX();
            touchDownY = event.getRawY();
            touchDownAt = event.getEventTime();
            View decor = activeDecor(activity);
            FeedContentTracker.Snapshot model = FeedContentTracker.current(decor);
            touchDownAid = model == null ? lastAcceptedAid : model.aid;
            touchDownEngine = PlaybackState.engine();
            touchDownEngineState = PlaybackState.engineState(touchDownEngine);
            return;
        }
        if (action != MotionEvent.ACTION_UP) {
            return;
        }
        long gestureId = event.getDownTime();
        if (gestureId == lastHandledTouchDownTime) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastHandledTouchUpAt < 300L) {
            return;
        }
        lastHandledTouchDownTime = gestureId;
        lastHandledTouchUpAt = now;

        float dx = event.getRawX() - touchDownX;
        float dy = event.getRawY() - touchDownY;
        long elapsed = event.getEventTime() - touchDownAt;
        View decor = activeDecor(activity);
        float density = decor == null
                ? 1f
                : decor.getResources().getDisplayMetrics().density;
        if (decor != null
                && Math.abs(dy) > 72f * density
                && Math.abs(dy) > Math.abs(dx)) {
            armContentFilter(700L);
            confirmUserFeedSwitch(
                    touchGestureToken,
                    touchDownAid,
                    decor,
                    8
            );
            return;
        }
        if (decor == null
                || dx * dx + dy * dy > 1_600f
                || elapsed > 500L
                || event.getRawX() < decor.getWidth() * 0.18f
                || event.getRawX() > decor.getWidth() * 0.82f
                || event.getRawY() < decor.getHeight() * 0.12f
                || event.getRawY() > decor.getHeight() * 0.86f) {
            return;
        }

        boolean resumeRequested = PlaybackState.isUserPaused();
        long gestureToken = touchGestureToken;
        long pauseIntentAt = touchDownAt;
        Object pauseIntentEngine = touchDownEngine;
        int pauseIntentInitialState = touchDownEngineState;
        touchDownEngine = null;
        if (resumeRequested) {
            confirmUserResume(
                    gestureToken,
                    pauseIntentEngine,
                    4
            );
        } else {
            confirmUserPause(
                    gestureToken,
                    pauseIntentAt,
                    pauseIntentEngine,
                    pauseIntentInitialState,
                    activity,
                    decor,
                    4
            );
        }
    }

    private static void confirmUserResume(
            long gestureToken,
            Object resumeIntentEngine,
            int attemptsLeft
    ) {
        MAIN.postDelayed(() -> {
            if (gestureToken != touchGestureToken
                    || !PlaybackState.isUserPaused()) {
                return;
            }
            if (PlaybackState.confirmUserPlaying(resumeIntentEngine)) {
                removeDownloadButton();
                scheduleScan(0L);
                return;
            }
            if (attemptsLeft > 1) {
                confirmUserResume(
                        gestureToken,
                        resumeIntentEngine,
                        attemptsLeft - 1
                );
            } else {
                Log.d(DouyinModule.TAG,
                        "ignored center tap because playback stayed paused");
                scheduleScan(0L);
            }
        }, 120L);
    }

    private static void confirmUserPause(
            long gestureToken,
            long pauseIntentAt,
            Object pauseIntentEngine,
            int pauseIntentInitialState,
            Activity activity,
            View decor,
            int attemptsLeft
    ) {
        MAIN.postDelayed(() -> {
            if (gestureToken != touchGestureToken
                    || PlaybackState.isUserPaused()) {
                return;
            }
            boolean confirmedPause =
                    PlaybackState.confirmUserPaused(
                            pauseIntentEngine,
                            pauseIntentAt,
                            pauseIntentInitialState
                    );
            if (confirmedPause) {
                restoreAll(activity, decor);
                showDownloadButton(activity, decor);
                return;
            }
            if (attemptsLeft > 1) {
                confirmUserPause(
                        gestureToken,
                        pauseIntentAt,
                        pauseIntentEngine,
                        pauseIntentInitialState,
                        activity,
                        decor,
                        attemptsLeft - 1
                );
            } else {
                Log.d(DouyinModule.TAG,
                        "ignored center tap because playback stayed active");
                scheduleScan(0L);
            }
        }, 180L);
    }

    private static void confirmUserFeedSwitch(long token,
                                              String previousAid,
                                              View decor,
                                              int attemptsLeft) {
        MAIN.postDelayed(() -> {
            if (token != touchGestureToken || previousAid == null) {
                return;
            }
            FeedContentTracker.Snapshot model = FeedContentTracker.current(decor);
            if (model != null && !previousAid.equals(model.aid)) {
                if (PlaybackState.isUserPaused()) {
                    PlaybackState.userPlaying();
                } else {
                    PlaybackState.confirmVideoSwitch();
                }
                scheduleScan(0L);
                return;
            }
            if (attemptsLeft > 1) {
                confirmUserFeedSwitch(token, previousAid, decor, attemptsLeft - 1);
            }
        }, 160L);
    }

    static void onPlaybackChanged(boolean playing) {
        MAIN.post(() -> {
            if (playing) {
                transitionBoostUntil = SystemClock.uptimeMillis() + 1_200L;
            }
            Activity activity = activeActivity();
            View decor = activeDecor(activity);
            if (decor == null) {
                scheduleScan(250L);
                return;
            }
            if (playing) {
                removeDownloadButton();
                scheduleScan(0L);
            } else {
                long token = PlaybackState.generation();
                MAIN.postDelayed(() -> {
                    if (token == PlaybackState.generation()
                            && !PlaybackState.shouldKeepUiHidden()) {
                        Activity currentActivity = activeActivity();
                        View currentDecor = activeDecor(currentActivity);
                        restoreAll(currentActivity, currentDecor);
                        showDownloadButton(currentActivity, currentDecor);
                    }
                }, 720L);
            }
        });
    }

    static void onPlaybackCompleted(String reason) {
        MAIN.post(() -> {
            Activity activity = activeActivity();
            View decor = activeDecor(activity);
            if (decor == null) {
                return;
            }
            swipeToNext(decor, reason);
        });
    }

    private static void scheduleScan(long delayMs) {
        if (scanScheduled) {
            return;
        }
        scanScheduled = true;
        MAIN.postDelayed(ImmersiveUi::scan, delayMs);
    }

    private static void scan() {
        scanScheduled = false;
        try {
            scanOnce();
        } catch (Throwable error) {
            long now = SystemClock.uptimeMillis();
            if (now - lastScanFailureAt >= 2_000L) {
                lastScanFailureAt = now;
                Log.e(DouyinModule.TAG,
                        "immersive UI scan failed; watchdog will retry", error);
            }
        } finally {
            if (!scanScheduled) {
                scheduleScan(SCAN_INTERVAL_MS);
            }
        }
    }

    private static void scanOnce() {
        Activity activity = activeActivity();
        View decor = activeDecor(activity);
        if (decor == null) {
            scheduleScan(250L);
            return;
        }
        if (activity != null && (activity.isFinishing() || activity.isDestroyed())) {
            return;
        }

        if (PlaybackState.consumePlaybackError()) {
            onPlaybackCompleted("playback error");
            scheduleNextScan();
            return;
        }

        if (PlaybackState.consumeLoopBoundary()) {
            onPlaybackCompleted("completed loop boundary");
            scheduleNextScan();
            return;
        }

        if (checkCurrentFeedContent(decor)) {
            scheduleNextScan();
            return;
        }

        if (!PlaybackState.shouldKeepUiHidden()) {
            restoreAll(activity, decor);
            showDownloadButton(activity, decor);
            scheduleScan(SCAN_INTERVAL_MS);
            return;
        }

        removeDownloadButton();
        List<View> videos = findVisibleVideoViews(decor);
        if (!videos.isEmpty()) {
            videoMissingLogged = false;
            expandVideoViewport(decor, videos);
            restoreVideoPaths(videos);
            hideOutsideVideoPaths(decor, videos);
            hideSystemBars(activity, decor);
        } else {
            restoreAll(activity, decor);
            if (!videoMissingLogged) {
                videoMissingLogged = true;
                Log.w(DouyinModule.TAG,
                        "no centered visible video SurfaceView/TextureView found");
            }
        }
        scheduleNextScan();
    }

    private static void scheduleNextScan() {
        long delay = SystemClock.uptimeMillis() < transitionBoostUntil
                ? 16L
                : SCAN_INTERVAL_MS;
        scheduleScan(delay);
    }

    private static Activity activeActivity() {
        Activity current = active.get();
        if (current != null && !current.isFinishing() && !current.isDestroyed()) {
            return current;
        }
        Activity resolved = resolveTopActivity();
        if (resolved != null) {
            active = new WeakReference<>(resolved);
            Log.i(DouyinModule.TAG, "resolved activity: " + resolved.getClass().getName());
        }
        return resolved;
    }

    private static View activeDecor(Activity activity) {
        if (activity != null) {
            View decor = activity.getWindow().getDecorView();
            activeRoot = new WeakReference<>(decor);
            return decor;
        }
        View current = activeRoot.get();
        if (current != null && current.isAttachedToWindow()) {
            return current;
        }
        View resolved = resolveLargestWindowRoot();
        if (resolved != null) {
            activeRoot = new WeakReference<>(resolved);
            Log.i(DouyinModule.TAG, "resolved window root: "
                    + resolved.getClass().getName() + " "
                    + resolved.getWidth() + "x" + resolved.getHeight());
        }
        return resolved;
    }

    private static void showDownloadButton(Activity activity, View decor) {
        if (activity == null
                || decor == null
                || !PlaybackState.isUserPaused()
                || !(decor instanceof FrameLayout container)) {
            removeDownloadButton();
            return;
        }

        TextView current = downloadButton.get();
        if (current != null && current.getParent() == container) {
            current.setVisibility(View.VISIBLE);
            current.bringToFront();
            return;
        }
        removeDownloadButton();

        int size = dp(decor, 52);
        int verticalGap = dp(decor, 10);
        int rightMargin = dp(decor, 4);
        TextView button = new TextView(activity);
        button.setText("↓\n下载");
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setContentDescription("下载无水印视频");
        button.setClickable(true);
        button.setFocusable(true);
        button.setElevation(dp(decor, 6));

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0x73000000);
        background.setStroke(dp(decor, 1), 0x66FFFFFF);
        button.setBackground(background);
        button.setOnClickListener(ignored -> {
            if (!PlaybackState.isUserPaused()) {
                removeDownloadButton();
                return;
            }
            Activity currentActivity = activeActivity();
            View currentDecor = activeDecor(currentActivity);
            FeedContentTracker.Snapshot snapshot =
                    FeedContentTracker.current(currentDecor);
            VideoDownloader.download(currentActivity, snapshot);
        });

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                size,
                size,
                Gravity.TOP | Gravity.END
        );
        params.topMargin = resolveDownloadButtonTop(decor, size, verticalGap);
        params.rightMargin = rightMargin;
        container.addView(button, params);
        button.bringToFront();
        downloadButton = new WeakReference<>(button);
        MAIN.postDelayed(() -> repositionDownloadButton(button, decor), 120L);
        Log.i(DouyinModule.TAG,
                "pause download button shown: top=" + params.topMargin);
    }

    private static void repositionDownloadButton(TextView button, View decor) {
        if (downloadButton.get() != button
                || !PlaybackState.isUserPaused()
                || !(button.getLayoutParams() instanceof FrameLayout.LayoutParams params)) {
            return;
        }
        params.topMargin = resolveDownloadButtonTop(
                decor,
                params.width,
                dp(decor, 10)
        );
        button.setLayoutParams(params);
        button.bringToFront();
    }

    private static void removeDownloadButton() {
        TextView button = downloadButton.get();
        if (button != null) {
            HIDDEN.remove(button);
            button.setOnClickListener(null);
            if (button.getParent() instanceof ViewGroup parent) {
                parent.removeView(button);
            }
        }
        downloadButton.clear();
    }

    private static int resolveDownloadButtonTop(View decor, int size, int gap) {
        int[] rootLocation = new int[2];
        decor.getLocationOnScreen(rootLocation);
        int nativeTop = findActiveRightMenuTop(
                decor,
                rootLocation[0],
                rootLocation[1],
                decor.getWidth(),
                decor.getHeight()
        );
        if (nativeTop == Integer.MAX_VALUE) {
            nativeTop = findRightActionTop(
                    decor,
                    rootLocation[0],
                    rootLocation[1],
                    decor.getWidth(),
                    decor.getHeight()
            );
        }
        int desired = nativeTop == Integer.MAX_VALUE
                ? Math.round(decor.getHeight() * 0.38f)
                : nativeTop - rootLocation[1] - size - gap;
        int minimum = Math.round(decor.getHeight() * 0.20f);
        int maximum = Math.max(minimum, Math.round(decor.getHeight() * 0.60f));
        return Math.max(minimum, Math.min(maximum, desired));
    }

    private static int findActiveRightMenuTop(
            View view,
            int rootLeft,
            int rootTop,
            int rootWidth,
            int rootHeight
    ) {
        int best = Integer.MAX_VALUE;
        if ("com.ss.android.ugc.aweme.feed.ui.FeedRightScaleView"
                .equals(view.getClass().getName())
                && view.isAttachedToWindow()
                && view.isShown()
                && view.getAlpha() > 0.1f) {
            Rect visible = new Rect();
            if (view.getGlobalVisibleRect(visible)
                    && visible.centerX() >= rootLeft + Math.round(rootWidth * 0.72f)
                    && visible.top >= rootTop + Math.round(rootHeight * 0.25f)
                    && visible.height() >= Math.round(rootHeight * 0.30f)) {
                best = visible.top;
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                best = Math.min(
                        best,
                        findActiveRightMenuTop(
                                group.getChildAt(i),
                                rootLeft,
                                rootTop,
                                rootWidth,
                                rootHeight
                        )
                );
            }
        }
        return best;
    }

    private static int findRightActionTop(
            View view,
            int rootLeft,
            int rootTop,
            int rootWidth,
            int rootHeight
    ) {
        int best = Integer.MAX_VALUE;
        if (view != downloadButton.get()
                && view.getVisibility() == View.VISIBLE
                && view.isShown()
                && view.getAlpha() > 0.1f
                && (view.isClickable() || view.getContentDescription() != null)) {
            Rect visible = new Rect();
            if (view.getGlobalVisibleRect(visible)) {
                int centerX = visible.centerX();
                int maxWidth = Math.min(rootWidth / 4, dp(view, 104));
                int maxHeight = dp(view, 128);
                if (centerX >= rootLeft + Math.round(rootWidth * 0.78f)
                        && visible.top >= rootTop + Math.round(rootHeight * 0.30f)
                        && visible.bottom <= rootTop + Math.round(rootHeight * 0.95f)
                        && visible.width() > 0
                        && visible.width() <= maxWidth
                        && visible.height() > 0
                        && visible.height() <= maxHeight) {
                    best = visible.top;
                }
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                best = Math.min(
                        best,
                        findRightActionTop(
                                group.getChildAt(i),
                                rootLeft,
                                rootTop,
                                rootWidth,
                                rootHeight
                        )
                );
            }
        }
        return best;
    }

    private static int dp(View view, int value) {
        return Math.round(
                value * view.getResources().getDisplayMetrics().density
        );
    }

    private static View resolveLargestWindowRoot() {
        try {
            Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = globalClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object global = getInstance.invoke(null);
            Field viewsField = globalClass.getDeclaredField("mViews");
            viewsField.setAccessible(true);
            Object value = viewsField.get(global);
            if (!(value instanceof List<?> roots)) {
                return null;
            }

            View best = null;
            long bestArea = 0L;
            for (Object item : roots) {
                if (!(item instanceof View root) || !root.isAttachedToWindow()) {
                    continue;
                }
                long area = (long) root.getWidth() * root.getHeight();
                if (area > bestArea) {
                    best = root;
                    bestArea = area;
                }
            }
            return best;
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!activityResolveErrorLogged) {
                activityResolveErrorLogged = true;
                Log.e(DouyinModule.TAG, "failed to resolve current window root", error);
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Activity resolveTopActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentThreadMethod =
                    activityThreadClass.getDeclaredMethod("currentActivityThread");
            currentThreadMethod.setAccessible(true);
            Object activityThread = currentThreadMethod.invoke(null);
            if (activityThread == null) {
                return null;
            }

            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object records = activitiesField.get(activityThread);
            if (!(records instanceof Map<?, ?> activities)) {
                return null;
            }

            Activity fallback = null;
            for (Object record : activities.values()) {
                if (record == null) {
                    continue;
                }
                Field activityField = record.getClass().getDeclaredField("activity");
                activityField.setAccessible(true);
                Object value = activityField.get(record);
                if (!(value instanceof Activity candidate)
                        || candidate.isFinishing()
                        || candidate.isDestroyed()) {
                    continue;
                }
                fallback = candidate;
                try {
                    Field pausedField = record.getClass().getDeclaredField("paused");
                    pausedField.setAccessible(true);
                    if (!pausedField.getBoolean(record)) {
                        return candidate;
                    }
                } catch (NoSuchFieldException ignored) {
                    return candidate;
                }
            }
            return fallback;
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!activityResolveErrorLogged) {
                activityResolveErrorLogged = true;
                Log.e(DouyinModule.TAG, "failed to resolve current Activity", error);
            }
            return null;
        }
    }

    private static List<View> findVisibleVideoViews(View root) {
        List<View> candidates = new ArrayList<>();
        collectRealVideoViews(root, candidates);
        List<View> visible = centeredVisibleVideoViews(root, candidates);
        if (!visible.isEmpty()) {
            return visible;
        }
        candidates.clear();
        collectFallbackVideoViews(root, candidates);
        return centeredVisibleVideoViews(root, candidates);
    }

    private static List<View> centeredVisibleVideoViews(
            View root,
            List<View> candidates
    ) {
        Rect rootVisible = new Rect();
        if (!root.getGlobalVisibleRect(rootVisible)) {
            return Collections.emptyList();
        }
        int centerX = rootVisible.centerX();
        int centerY = rootVisible.centerY();
        long screenArea =
                (long) Math.max(1, rootVisible.width())
                        * Math.max(1, rootVisible.height());
        List<View> videoPaths = new ArrayList<>();
        Rect visible = new Rect();
        for (View candidate : candidates) {
            boolean alphaVisible =
                    candidate.getAlpha() > 0.05f || HIDDEN.containsKey(candidate);
            if (!candidate.isAttachedToWindow()
                    || candidate.getVisibility() != View.VISIBLE
                    || !candidate.isShown()
                    || !alphaVisible
                    || !candidate.getGlobalVisibleRect(visible)
                    || !visible.contains(centerX, centerY)) {
                continue;
            }
            long area = (long) visible.width() * visible.height();
            if (area >= screenArea / 5L
                    && !videoPaths.contains(candidate)) {
                videoPaths.add(candidate);
            }
        }
        return videoPaths.isEmpty()
                ? Collections.emptyList()
                : videoPaths;
    }

    private static void collectRealVideoViews(View view, List<View> out) {
        if (view instanceof SurfaceView || view instanceof TextureView) {
            out.add(view);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectRealVideoViews(group.getChildAt(i), out);
            }
        }
    }

    private static void expandVideoViewport(View decor, List<View> videos) {
        for (View video : videos) {
            if (expandVideoViewportFrom(decor, video)) {
                return;
            }
        }
    }

    private static boolean expandVideoViewportFrom(View decor, View video) {
        View viewport = video;
        while (viewport.getParent() instanceof View parent && parent != decor) {
            boolean targetViewport = "com.ss.android.ugc.aweme.common.widget.RTViewPager"
                    .equals(viewport.getClass().getName());
            int missingHeight = parent.getHeight() - viewport.getHeight();
            boolean parentFillsWindow =
                    parent.getWidth() >= decor.getWidth() * 0.9f
                            && parent.getHeight() >= decor.getHeight() * 0.95f;
            boolean viewportLeavesBottomSlot =
                    viewport.getWidth() >= decor.getWidth() * 0.9f
                            && missingHeight > 32
                            && missingHeight < decor.getHeight() / 3;
            if (targetViewport && parentFillsWindow && viewportLeavesBottomSlot) {
                applyExpandedViewport(viewport);
                return true;
            }
            viewport = parent;
        }
        return false;
    }

    private static void applyExpandedViewport(View viewport) {
        if (EXPANDED_VIEWPORTS.containsKey(viewport)) {
            return;
        }
        ViewGroup.LayoutParams original = viewport.getLayoutParams();
        if (original == null) {
            return;
        }

        if (!(original instanceof RelativeLayout.LayoutParams relative)) {
            Log.w(DouyinModule.TAG,
                    "RTViewPager layout not RelativeLayout.LayoutParams: "
                            + original.getClass().getName());
            return;
        }
        RelativeLayout.LayoutParams expanded = new RelativeLayout.LayoutParams(relative);
        expanded.height = ViewGroup.LayoutParams.MATCH_PARENT;
        expanded.topMargin = 0;
        expanded.bottomMargin = 0;
        expanded.removeRule(RelativeLayout.ABOVE);
        expanded.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        expanded.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        EXPANDED_VIEWPORTS.put(viewport, original);
        viewport.setLayoutParams(expanded);
        viewport.requestLayout();
        Log.d(DouyinModule.TAG,
                "expanded video viewport: " + viewport.getClass().getName());
    }

    private static void collectFallbackVideoViews(View view, List<View> out) {
        if (looksLikeVideoSurface(view)) {
            out.add(view);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectFallbackVideoViews(group.getChildAt(i), out);
            }
        }
    }

    private static boolean looksLikeVideoSurface(View view) {
        String name = view.getClass().getName().toLowerCase();
        return name.contains("videosurface")
                || name.contains("playerview")
                || name.endsWith("surfaceview")
                || name.endsWith("textureview");
    }

    private static void hideOutsideVideoPaths(View node, List<View> videos) {
        if (videos.contains(node)) {
            return;
        }
        if (!(node instanceof ViewGroup group) || !containsAny(group, videos)) {
            hide(node);
            return;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (videos.contains(child) || containsAny(child, videos)) {
                hideOutsideVideoPaths(child, videos);
            } else {
                hide(child);
            }
        }
    }

    private static boolean containsAny(View node, List<View> targets) {
        for (View target : targets) {
            if (contains(node, target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(View node, View target) {
        if (node == target) {
            return true;
        }
        if (node instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (contains(group.getChildAt(i), target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void restoreVideoPaths(List<View> videos) {
        synchronized (HIDDEN) {
            for (Map.Entry<View, SavedView> entry :
                    new ArrayList<>(HIDDEN.entrySet())) {
                View view = entry.getKey();
                SavedView saved = entry.getValue();
                if (view == null || saved == null
                        || (!videos.contains(view) && !containsAny(view, videos))) {
                    continue;
                }
                view.setAlpha(saved.alpha);
                view.setVisibility(saved.visibility);
                view.setImportantForAccessibility(saved.accessibility);
                HIDDEN.remove(view);
            }
        }
    }

    private static void hide(View view) {
        if (!HIDDEN.containsKey(view)) {
            HIDDEN.put(view, new SavedView(
                    view.getAlpha(),
                    view.getImportantForAccessibility(),
                    view.getVisibility()
            ));
        }
        view.setAlpha(0f);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private static void restoreAll(Activity activity) {
        restoreAll(activity, activeDecor(activity), true);
    }

    private static void restoreAll(Activity activity, View decor) {
        restoreAll(activity, decor, false);
    }

    private static void restoreAll(
            Activity activity,
            View decor,
            boolean leavingActivity
    ) {
        if (leavingActivity) {
            restoreExpandedViewports();
        }
        int restored = 0;
        StringBuilder samples = new StringBuilder();
        synchronized (HIDDEN) {
            for (Map.Entry<View, SavedView> entry : new ArrayList<>(HIDDEN.entrySet())) {
                View view = entry.getKey();
                SavedView saved = entry.getValue();
                if (view != null && saved != null) {
                    view.setAlpha(saved.alpha);
                    view.setVisibility(saved.visibility);
                    view.setImportantForAccessibility(saved.accessibility);
                    if (restored < 5) {
                        if (samples.length() > 0) {
                            samples.append(',');
                        }
                        samples.append(view.getClass().getSimpleName())
                                .append('@')
                                .append(saved.alpha);
                    }
                    restored++;
                }
            }
            HIDDEN.clear();
        }
        if (restored > 0) {
            Log.d(DouyinModule.TAG,
                    "restored hidden views=" + restored + " samples=" + samples);
        }
        if (decor != null) {
            Integer systemUi = ROOT_SYSTEM_UI.remove(decor);
            if (systemUi != null) {
                decor.setSystemUiVisibility(systemUi);
            }
        }
        restoreSystemBars(activity);
    }

    private static void restoreExpandedViewports() {
        synchronized (EXPANDED_VIEWPORTS) {
            for (Map.Entry<View, ViewGroup.LayoutParams> entry :
                    new ArrayList<>(EXPANDED_VIEWPORTS.entrySet())) {
                View view = entry.getKey();
                ViewGroup.LayoutParams original = entry.getValue();
                if (view != null && original != null) {
                    view.setLayoutParams(original);
                    view.requestLayout();
                }
            }
            EXPANDED_VIEWPORTS.clear();
        }
    }

    private static void hideSystemBars(Activity activity, View decor) {
        if (!ROOT_SYSTEM_UI.containsKey(decor)) {
            ROOT_SYSTEM_UI.put(decor, decor.getSystemUiVisibility());
        }
        decor.setSystemUiVisibility(
                decor.getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private static void restoreSystemBars(Activity activity) {
        // Window flags are deliberately left untouched; see hideSystemBars.
    }

    private static boolean swipeToNext(View decor, String reason) {
        long now = SystemClock.uptimeMillis();
        if (swipeRunning
                || !decor.isAttachedToWindow()
                || now - lastSwipeAt < 1_500L) {
            return false;
        }
        int width = decor.getWidth();
        int height = decor.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        swipeRunning = true;
        long currentSwipeToken = ++swipeToken;
        lastSwipeAt = now;
        PlaybackState.beginAutoSwitch();
        armContentFilter(700L);
        Log.i(DouyinModule.TAG, "swipe to next feed item: " + reason);

        final float x = width * 0.5f;
        final float startY = height * 0.72f;
        final float endY = height * 0.24f;
        final long downTime = SystemClock.uptimeMillis();
        try {
            dispatch(decor, downTime, downTime, MotionEvent.ACTION_DOWN, x, startY);
        } catch (Throwable error) {
            finishSwipe(currentSwipeToken, "initial dispatch failed", error);
            return false;
        }
        MAIN.postDelayed(
                () -> finishSwipe(currentSwipeToken, "watchdog timeout", null),
                1_200L
        );

        int steps = 8;
        for (int i = 1; i <= steps; i++) {
            final int step = i;
            MAIN.postDelayed(() -> {
                if (!swipeRunning || currentSwipeToken != swipeToken) {
                    return;
                }
                float fraction = step / (float) steps;
                float y = startY + (endY - startY) * fraction;
                int action = step == steps ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                try {
                    dispatch(
                            decor,
                            downTime,
                            SystemClock.uptimeMillis(),
                            action,
                            x,
                            y
                    );
                } catch (Throwable error) {
                    finishSwipe(currentSwipeToken, "gesture dispatch failed", error);
                    return;
                }
                if (step == steps) {
                    MAIN.postDelayed(
                            () -> finishSwipe(currentSwipeToken, null, null),
                            500L
                    );
                }
            }, i * 22L);
        }
        return true;
    }

    private static void finishSwipe(long token, String reason, Throwable error) {
        if (token != swipeToken || !swipeRunning) {
            return;
        }
        swipeRunning = false;
        if (reason != null) {
            if (error == null) {
                Log.w(DouyinModule.TAG,
                        "synthetic swipe recovered: " + reason);
            } else {
                Log.w(DouyinModule.TAG,
                        "synthetic swipe recovered: " + reason, error);
            }
        }
        scheduleScan(0L);
    }

    private static void dispatch(View view, long downTime, long eventTime,
                                 int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        try {
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static void armContentFilter(long delayMs) {
        long now = SystemClock.uptimeMillis();
        contentCheckNotBefore = now + delayMs;
        contentCheckUntil = now + Math.max(delayMs + 2_500L, 3_000L);
        lastContentCheckAt = 0L;
        resetFilterCandidate();
    }

    private static boolean checkCurrentFeedContent(View decor) {
        long now = SystemClock.uptimeMillis();
        boolean candidatePending =
                filterCandidateCount > 0 && now - filterCandidateAt <= 900L;
        if (now < contentCheckNotBefore) {
            return candidatePending;
        }
        boolean activelyArmed = now <= contentCheckUntil || candidatePending;
        long minimumInterval = activelyArmed
                ? 220L
                : FALLBACK_CONTENT_CHECK_INTERVAL_MS;
        if (now - lastContentCheckAt < minimumInterval
                || now - lastFilteredSwipeAt < 1_500L) {
            return candidatePending;
        }
        lastContentCheckAt = now;

        FeedContentTracker.Snapshot model = FeedContentTracker.current(decor);
        if (model != null) {
            String reason = model.shouldFilter()
                    ? model.filterReason
                    : model.shouldFilterVisibleAdMarker()
                    && containsVisibleAdMarker(decor)
                    ? "advertisement marker"
                    : null;
            if (reason != null) {
                if (!activelyArmed) {
                    contentCheckNotBefore = 0L;
                    contentCheckUntil = now + 2_500L;
                }
                if (!confirmFilterCandidate(model.aid, reason, now)) {
                    return true;
                }
                filterCurrentItem(
                        decor,
                        reason + " " + model.classificationDetails()
                );
                return true;
            }
            resetFilterCandidate();
            if (!model.aid.equals(lastAcceptedAid)) {
                lastAcceptedAid = model.aid;
                Log.d(DouyinModule.TAG,
                        "feed item accepted as video: "
                                + model.classificationDetails());
            }
            return false;
        }

        if (!candidatePending) {
            resetFilterCandidate();
        }
        return candidatePending;
    }

    private static boolean confirmFilterCandidate(
            String aid,
            String reason,
            long now
    ) {
        boolean sameCandidate =
                aid != null
                        && aid.equals(filterCandidateAid)
                        && reason.equals(filterCandidateReason)
                        && now - filterCandidateAt <= 750L;
        if (sameCandidate) {
            filterCandidateCount++;
        } else {
            filterCandidateAid = aid;
            filterCandidateReason = reason;
            filterCandidateCount = 1;
        }
        filterCandidateAt = now;
        Log.d(DouyinModule.TAG,
                "filter candidate " + filterCandidateCount + "/3: "
                        + reason + " aid=" + aid);
        return filterCandidateCount >= 3;
    }

    private static void resetFilterCandidate() {
        filterCandidateAid = null;
        filterCandidateReason = null;
        filterCandidateCount = 0;
        filterCandidateAt = 0L;
    }

    private static boolean containsVisibleAdMarker(View root) {
        if (root instanceof TextView textView) {
            Rect visible = new Rect();
            CharSequence value = textView.getText();
            if (value == null || value.length() == 0) {
                value = textView.getContentDescription();
            }
            if (value != null && textView.isShown() && textView.getGlobalVisibleRect(visible)) {
                String marker = value.toString().trim();
                if (marker.length() <= 12
                        && (marker.equals("广告")
                        || marker.startsWith("广告·")
                        || marker.startsWith("广告 ·"))) {
                    return true;
                }
            }
        }
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsVisibleAdMarker(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean filterCurrentItem(View decor, String reason) {
        long now = SystemClock.uptimeMillis();
        if (swipeRunning || now - lastFilteredSwipeAt < 1_500L) {
            return false;
        }
        if (!swipeToNext(decor, reason)) {
            return false;
        }
        lastFilteredSwipeAt = now;
        Log.i(DouyinModule.TAG, "filtering feed item: " + reason);
        return true;
    }

    private static final class SavedView {
        final float alpha;
        final int accessibility;
        final int visibility;

        SavedView(float alpha, int accessibility, int visibility) {
            this.alpha = alpha;
            this.accessibility = accessibility;
            this.visibility = visibility;
        }
    }

}
