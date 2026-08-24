package com.zz.douyin.hook;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DanmakuViewClassifierTest {
    @Test
    public void recognizesSupportedFeedDanmakuRenderViews() {
        assertTrue(DanmakuViewClassifier.isRenderView(
                "com.bytedance.common.ultra.danmaku.ddanmaku.DDanmakuComposeView"
        ));
    }

    @Test
    public void rejectsPanelsAndUnrelatedOverlays() {
        assertFalse(DanmakuViewClassifier.isRenderView(
                "com.ss.android.ugc.aweme.feed.danmaku.view.DanmakuVisibilityFrameLayout"
        ));
        assertFalse(DanmakuViewClassifier.isRenderView(
                "com.ss.android.ugc.aweme.feed.danmaku.ui.setting.DanmakuSettingSeekbar"
        ));
        assertFalse(DanmakuViewClassifier.isRenderView(
                "com.ss.android.ugc.aweme.feed.ui.FeedRightScaleView"
        ));
        assertFalse(DanmakuViewClassifier.isRenderView(null));
    }
}
