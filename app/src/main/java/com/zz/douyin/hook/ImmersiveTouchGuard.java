package com.zz.douyin.hook;

import android.annotation.SuppressLint;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

final class ImmersiveTouchGuard {
    private static final Map<View, SavedGesture> LONG_PRESS_FLAGS = new WeakHashMap<>();
    private static Field menuEnabled;
    private static Field longClickEnabled;
    private static long blockedGestureTime;
    private static int blockedControls;

    private ImmersiveTouchGuard() {
    }

    // LSPosed grants its injected module non-SDK access. This hook is never installed
    // in our settings app; verified in the Android 16 host process with API 102.
    @SuppressLint("SoonBlockedPrivateApi")
    static void install(DouyinModule module, ClassLoader loader)
            throws ReflectiveOperationException {
        Class<?> gestureClass = Class.forName(ImmersiveTouchPolicy.GESTURE_VIEW, false, loader);
        menuEnabled = gestureClass.getDeclaredField("longPressEnable");
        longClickEnabled = gestureClass.getDeclaredField("enableLongClick");
        menuEnabled.setAccessible(true);
        longClickEnabled.setAccessible(true);

        Method gestureDispatch = gestureClass.getDeclaredMethod(
                "dispatchTouchEvent", MotionEvent.class);
        module.hook(gestureDispatch)
                .setId("douyin-immersive-native-gesture")
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    View view = (View) chain.getThisObject();
                    MotionEvent event = (MotionEvent) chain.getArgs().get(0);
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        restoreLongPress(view);
                        if (ImmersiveUi.isImmersiveGestureView(view)) {
                            LONG_PRESS_FLAGS.put(view, new SavedGesture(
                                    menuEnabled.getBoolean(view), longClickEnabled.getBoolean(view)));
                            // Douyin evaluates speed/seek listeners before its generic menu flag.
                            menuEnabled.setBoolean(view, false);
                            longClickEnabled.setBoolean(view,
                                    longClickEnabled.getBoolean(view)
                                            && ImmersiveTouchPolicy.isSideLongPress(
                                            event.getX(), view.getWidth()));
                            Log.d(DouyinModule.TAG, "immersive gesture down: side="
                                    + ImmersiveTouchPolicy.isSideLongPress(
                                    event.getX(), view.getWidth()) + ", blockedControls="
                                    + (blockedGestureTime == event.getDownTime() ? blockedControls : 0));
                        }
                    }
                    try {
                        return chain.proceed();
                    } finally {
                        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                            SavedGesture saved = LONG_PRESS_FLAGS.get(view);
                            if (saved != null && action == MotionEvent.ACTION_UP
                                    && event.getEventTime() - event.getDownTime() > 500L) {
                                long position = readPosition(saved.engine);
                                Log.d(DouyinModule.TAG, "immersive long press finished: heldMs="
                                        + (SystemClock.uptimeMillis() - saved.startedAt)
                                        + ", playbackDeltaMs=" + (position < 0 || saved.position < 0
                                        ? "unknown" : position - saved.position));
                            }
                            restoreLongPress(view);
                        }
                    }
                });

        // Filter before child.dispatchTouchEvent(), including custom View overrides and
        // children of transparent containers. Returning false lets the video layer receive it.
        Method dispatchChild = ViewGroup.class.getDeclaredMethod(
                "dispatchTransformedTouchEvent", MotionEvent.class, boolean.class,
                View.class, int.class);
        module.hook(dispatchChild)
                .setId("douyin-immersive-hidden-touch")
                .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    MotionEvent event = (MotionEvent) chain.getArgs().get(0);
                    boolean cancel = (boolean) chain.getArgs().get(1)
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL;
                    View child = (View) chain.getArgs().get(2);
                    int action = event.getActionMasked();
                    // Once a target accepted DOWN, keep MOVE/UP/CANCEL flowing even if the
                    // renderer changes. Dropping UP could leave native fast playback engaged.
                    if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)
                            && child != null && ImmersiveUi.shouldBlockHiddenTouch(child, cancel)) {
                        if (blockedGestureTime != event.getDownTime()) {
                            blockedGestureTime = event.getDownTime();
                            blockedControls = 0;
                        }
                        blockedControls++;
                        return false;
                    }
                    return chain.proceed();
                });
    }

    private static void restoreLongPress(View view) throws IllegalAccessException {
        SavedGesture saved = LONG_PRESS_FLAGS.remove(view);
        if (saved != null) {
            menuEnabled.setBoolean(view, saved.menuEnabled);
            longClickEnabled.setBoolean(view, saved.longClickEnabled);
        }
    }

    private static long readPosition(Object engine) {
        if (engine == null) {
            return -1L;
        }
        try {
            return ((Number) engine.getClass().getMethod("getCurrentPlaybackTime")
                    .invoke(engine)).longValue();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1L;
        }
    }

    private static final class SavedGesture {
        final boolean menuEnabled;
        final boolean longClickEnabled;
        final Object engine = PlaybackState.engine();
        final long startedAt = SystemClock.uptimeMillis();
        final long position = readPosition(engine);

        SavedGesture(boolean menuEnabled, boolean longClickEnabled) {
            this.menuEnabled = menuEnabled;
            this.longClickEnabled = longClickEnabled;
        }
    }
}
