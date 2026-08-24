package com.zz.douyin.hook;

final class DanmakuViewClassifier {
    private static final String ULTRA_COMPOSE_RENDERER =
            "com.bytedance.common.ultra.danmaku.ddanmaku.DDanmakuComposeView";

    private DanmakuViewClassifier() {
    }

    static boolean isRenderView(String className) {
        return ULTRA_COMPOSE_RENDERER.equals(className);
    }
}
