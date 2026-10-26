package com.zz.douyin.hook;

/**
 * Recognizes the danmaku layers Douyin 40.8 creates.
 *
 * <p>40.8 renders bullet comments through several independent layers instead of a single
 * compose view: the compose renderer ({@code DDanmakuComposeView}), the Skity/functor
 * renderer ({@code DanmakuFunctorView}), the legacy canvas renderer ({@code DanmakuView}),
 * all hosted by a {@code DanmakuAsyncRenderParent} container. Keeping only the compose
 * layer hid every comment drawn by the other renderers, which is why fewer comments were
 * visible than in the native player.</p>
 */
final class DanmakuViewClassifier {
    private static final String ULTRA_PACKAGE = "com.bytedance.common.ultra.danmaku.";
    private static final String CONTAINER =
            "com.bytedance.common.ultra.danmaku.view.DanmakuAsyncRenderParent";
    private static final String[] RENDERERS = {
            "com.bytedance.common.ultra.danmaku.ddanmaku.DDanmakuComposeView",
            "com.bytedance.common.ultra.danmaku.view.DanmakuFunctorView",
            "com.bytedance.common.ultra.danmaku.view.DanmakuView",
            "com.bytedance.common.ultra.danmaku.view.compose.BaseComposeView",
    };

    private DanmakuViewClassifier() {
    }

    /** The layer that hosts every renderer; preserving it keeps future renderers visible. */
    static boolean isContainer(String className) {
        return CONTAINER.equals(className);
    }

    static boolean isRenderView(String className) {
        if (className == null) {
            return false;
        }
        for (String renderer : RENDERERS) {
            if (renderer.equals(className)) {
                return true;
            }
        }
        if (!className.startsWith(ULTRA_PACKAGE)) {
            return false;
        }
        String simpleName = className.substring(ULTRA_PACKAGE.length()).toLowerCase(
                java.util.Locale.ROOT);
        return (simpleName.endsWith("view") || simpleName.endsWith("parent"))
                && !simpleName.contains("permission")
                && !simpleName.contains("setting")
                && !simpleName.contains("input")
                && !simpleName.contains("panel");
    }
}
