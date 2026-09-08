package com.zz.douyin.hook;

final class ImmersiveTouchPolicy {
    static final String GESTURE_VIEW = "com.ss.android.ugc.aweme.feed.ui.LongPressLayout";

    private ImmersiveTouchPolicy() {
    }

    static boolean isGestureView(String className) {
        return GESTURE_VIEW.equals(className);
    }

    static boolean shouldBlock(boolean hidden, boolean gesturePath, boolean cancel) {
        // Cancellation must reach an existing target so it can release its gesture state.
        return hidden && !gesturePath && !cancel;
    }

    static boolean isSideLongPress(float x, int width) {
        return width > 0 && x >= 0 && x <= width
                && (x <= width * 0.25f || x >= width * 0.75f);
    }
}
