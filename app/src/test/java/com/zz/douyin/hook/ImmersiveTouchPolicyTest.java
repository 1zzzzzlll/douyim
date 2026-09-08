package com.zz.douyin.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ImmersiveTouchPolicyTest {
    @Test
    public void hiddenControlsAreBlockedButTheirCancellationIsDelivered() {
        assertTrue(ImmersiveTouchPolicy.shouldBlock(true, false, false));
        assertFalse(ImmersiveTouchPolicy.shouldBlock(true, false, true));
    }

    @Test
    public void nativeGesturePathAndRestoredControlsRemainTouchable() {
        assertFalse(ImmersiveTouchPolicy.shouldBlock(true, true, false));
        assertFalse(ImmersiveTouchPolicy.shouldBlock(false, false, false));
        assertFalse(ImmersiveTouchPolicy.shouldBlock(false, true, false));
    }

    @Test
    public void onlyTheNativeFeedGestureReceiverIsAllowed() {
        assertTrue(ImmersiveTouchPolicy.isGestureView(
                "com.ss.android.ugc.aweme.feed.ui.LongPressLayout"));
        assertFalse(ImmersiveTouchPolicy.isGestureView(
                "com.ss.android.ugc.aweme.common.widget.DiggLayout"));
        assertFalse(ImmersiveTouchPolicy.isGestureView(
                "com.ss.android.ugc.aweme.feed.danmaku.view.DanmakuVisibilityFrameLayout"));
        assertFalse(ImmersiveTouchPolicy.isGestureView(null));
    }

    @Test
    public void longPressOnlyRunsInTheSideQuarters() {
        assertTrue(ImmersiveTouchPolicy.isSideLongPress(0, 1000));
        assertTrue(ImmersiveTouchPolicy.isSideLongPress(250, 1000));
        assertTrue(ImmersiveTouchPolicy.isSideLongPress(750, 1000));
        assertTrue(ImmersiveTouchPolicy.isSideLongPress(1000, 1000));
        assertFalse(ImmersiveTouchPolicy.isSideLongPress(500, 1000));
        assertFalse(ImmersiveTouchPolicy.isSideLongPress(-1, 1000));
        assertFalse(ImmersiveTouchPolicy.isSideLongPress(1001, 1000));
        assertFalse(ImmersiveTouchPolicy.isSideLongPress(0, 0));
    }
}
