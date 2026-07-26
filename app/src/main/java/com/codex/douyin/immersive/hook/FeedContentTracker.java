package com.codex.douyin.immersive.hook;

import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.lang.annotation.Annotation;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class FeedContentTracker {
    private static final Map<Object, Long> PANELS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Class<?>, Map<String, Field>> SERIALIZED_FIELDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static Class<?> panelClass;
    private static Method getCurrentAweme;
    private static Field viewPagerField;
    private static WeakReference<Object> selectedPanel = new WeakReference<>(null);

    private FeedContentTracker() {
    }

    static void install(DouyinModule module, ClassLoader loader)
            throws ReflectiveOperationException {
        panelClass = Class.forName(
                "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel",
                false,
                loader
        );
        getCurrentAweme = panelClass.getDeclaredMethod("getCurrentAweme");
        viewPagerField = panelClass.getField("f");

        for (Constructor<?> constructor : panelClass.getDeclaredConstructors()) {
            module.hook(constructor)
                    .setId("douyin-immersive-feed-panel-" + constructor.toGenericString())
                    .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        remember(chain.getThisObject());
                        return result;
                    });
        }

        try {
            Method onPageSelected = panelClass.getDeclaredMethod("y0", int.class);
            module.hook(onPageSelected)
                    .setId("douyin-immersive-feed-page-selected")
                    .setExceptionMode(DouyinModule.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object panel = chain.getThisObject();
                        remember(panel);
                        selectedPanel = new WeakReference<>(panel);
                        ImmersiveUi.onFeedPageSelected();
                        return result;
                    });
        } catch (NoSuchMethodException error) {
            module.log(Log.WARN, DouyinModule.TAG,
                    "feed page-selection hook unavailable; using visible-panel fallback");
        }
        module.log(Log.INFO, DouyinModule.TAG, "current feed model hook installed");
    }

    static Snapshot current(View decor) {
        Class<?> expectedPanelClass = panelClass;
        Method currentMethod = getCurrentAweme;
        Field pagerField = viewPagerField;
        if (decor == null
                || expectedPanelClass == null
                || currentMethod == null
                || pagerField == null) {
            return null;
        }

        List<Object> panels;
        synchronized (PANELS) {
            panels = new ArrayList<>(PANELS.keySet());
        }

        Object bestAweme = null;
        long bestScore = Long.MIN_VALUE;
        Object selected = selectedPanel.get();
        Rect decorVisible = new Rect();
        if (!decor.getGlobalVisibleRect(decorVisible)) {
            return null;
        }
        int centerX = decorVisible.centerX();
        int centerY = decorVisible.centerY();
        Rect visible = new Rect();
        long now = SystemClock.uptimeMillis();
        for (Object panel : panels) {
            if (panel == null || !expectedPanelClass.isInstance(panel)) {
                continue;
            }
            try {
                Object pagerObject = pagerField.get(panel);
                if (!(pagerObject instanceof View pager)
                        || !pager.isAttachedToWindow()
                        || pager.getRootView() != decor
                        || !isEffectivelyVisible(pager, decor)
                        || !pager.getGlobalVisibleRect(visible)
                        || !visible.contains(centerX, centerY)) {
                    continue;
                }
                long area = (long) visible.width() * visible.height();
                if (area < (long) decor.getWidth() * decor.getHeight() / 5L) {
                    continue;
                }

                long recent;
                synchronized (PANELS) {
                    recent = PANELS.getOrDefault(panel, 0L);
                }
                Object aweme = currentMethod.invoke(panel);
                if (aweme == null) {
                    continue;
                }
                long agePenalty = Math.min(999_999L, Math.max(0L, now - recent));
                long score = panel == selected
                        ? Long.MAX_VALUE - agePenalty
                        : area * 1_000_000L - agePenalty;
                if (score > bestScore) {
                    bestScore = score;
                    bestAweme = aweme;
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.d(DouyinModule.TAG, "current feed model lookup failed", error);
            }
        }
        return bestAweme == null ? null : snapshot(bestAweme);
    }

    private static boolean isEffectivelyVisible(View view, View decor) {
        View current = view;
        while (current != null) {
            if (current.getVisibility() != View.VISIBLE || current.getAlpha() <= 0.05f) {
                return false;
            }
            if (current == decor) {
                return true;
            }
            if (!(current.getParent() instanceof View parent)) {
                return false;
            }
            current = parent;
        }
        return false;
    }

    private static void remember(Object panel) {
        if (panel != null) {
            PANELS.put(panel, SystemClock.uptimeMillis());
        }
    }

    private static Snapshot snapshot(Object aweme) {
        Class<?> type = aweme.getClass();
        String aid = stringValue(readField(type, aweme, "aid"));
        int awemeType = intValue(readField(type, aweme, "awemeType"), -1);
        boolean ad = booleanValue(readField(type, aweme, "isAd"));
        Object rawAd = invokeNoArg(type, aweme, "getAwemeRawAd");
        boolean hostImage = booleanValue(invokeNoArg(type, aweme, "isImage"));
        boolean hostMultiImage =
                booleanValue(invokeNoArg(type, aweme, "isMultiImage"));
        boolean slides = booleanValue(readField(type, aweme, "isSlides"));
        Object video = readField(type, aweme, "video");
        if (video == null) {
            video = readSerializedField(aweme, "video");
        }
        Object article = readField(type, aweme, "articleInfo");
        if (article == null) {
            article = readSerializedField(aweme, "article_info");
        }
        Object images = readField(type, aweme, "images");
        if (images == null) {
            images = readSerializedField(aweme, "images");
        }
        Object imageInfos = readField(type, aweme, "imageInfos");
        if (imageInfos == null) {
            imageInfos = readSerializedField(aweme, "image_infos");
        }
        int imageCount = collectionSize(images);
        int imageInfoCount = collectionSize(imageInfos);
        List<PlayUrl> playUrls = resolvePlayUrls(video);
        boolean photo =
                hostImage
                        || hostMultiImage
                        || slides
                        || awemeType == 2
                        || awemeType == 0x44
                        || imageCount > 0
                        || imageInfoCount > 0;

        String reason = null;
        // Sponsored entries can still be ordinary playable videos. Only use the
        // ad model as a filter when the host exposes no video for the entry.
        if ((ad || rawAd != null) && video == null) {
            reason = "advertisement model";
        } else if (awemeType == 0xA3) {
            reason = "long article model";
        } else if (photo) {
            reason = "photo article model";
        } else if (video == null) {
            if (article != null) {
                reason = "article model";
            } else {
                reason = "non-video model";
            }
        }
        return new Snapshot(
                aid,
                awemeType,
                video != null,
                ad,
                rawAd != null,
                article != null,
                imageCount,
                imageInfoCount,
                hostImage,
                hostMultiImage,
                slides,
                reason,
                playUrls
        );
    }

    private static Object readField(Class<?> type, Object instance, String name) {
        try {
            Field field = type.getField(name);
            return field.get(instance);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object readSerializedField(Object instance, String serializedName) {
        if (instance == null) {
            return null;
        }
        Field field = findSerializedField(instance.getClass(), serializedName);
        if (field == null) {
            return null;
        }
        try {
            return field.get(instance);
        } catch (IllegalAccessException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field findSerializedField(Class<?> type, String serializedName) {
        synchronized (SERIALIZED_FIELDS) {
            Map<String, Field> cached = SERIALIZED_FIELDS.computeIfAbsent(
                    type,
                    ignored -> new HashMap<>()
            );
            if (cached.containsKey(serializedName)) {
                return cached.get(serializedName);
            }
            Field resolved = locateSerializedField(type, serializedName);
            cached.put(serializedName, resolved);
            return resolved;
        }
    }

    private static Field locateSerializedField(Class<?> type, String serializedName) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                for (Annotation annotation : field.getDeclaredAnnotations()) {
                    if (!"com.google.gson.annotations.SerializedName"
                            .equals(annotation.annotationType().getName())) {
                        continue;
                    }
                    try {
                        Method valueMethod = annotation.annotationType().getMethod("value");
                        Object value = valueMethod.invoke(annotation);
                        if (serializedName.equals(value)) {
                            field.setAccessible(true);
                            return field;
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // Keep looking: model variants can use a different Gson runtime.
                    }
                }
            }
        }
        return null;
    }

    private static List<PlayUrl> resolvePlayUrls(Object video) {
        if (video == null) {
            return Collections.emptyList();
        }
        List<PlayUrl> urls = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        appendPlayUrls(urls, seen, video, "play_addr");
        appendPlayUrls(urls, seen, video, "play_addr_h264");
        appendPlayUrls(urls, seen, video, "play_addr_bytevc1");
        return urls.isEmpty()
                ? Collections.emptyList()
                : Collections.unmodifiableList(urls);
    }

    private static void appendPlayUrls(
            List<PlayUrl> output,
            Set<String> seen,
            Object video,
            String source
    ) {
        Object address = readSerializedField(video, source);
        if (address == null) {
            return;
        }
        Class<?> addressType = address.getClass();
        Object rawUrls = invokeNoArg(addressType, address, "getUrlList");
        if (!(rawUrls instanceof List<?>)) {
            rawUrls = readField(addressType, address, "urlList");
        }
        if (!(rawUrls instanceof List<?> candidates)) {
            return;
        }

        appendUrlsWithScheme(output, seen, candidates, source, "https://");
        appendUrlsWithScheme(output, seen, candidates, source, "http://");
    }

    private static void appendUrlsWithScheme(
            List<PlayUrl> output,
            Set<String> seen,
            List<?> candidates,
            String source,
            String scheme
    ) {
        for (Object candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String url = candidate.toString().trim();
            if (url.regionMatches(true, 0, scheme, 0, scheme.length())
                    && seen.add(url)) {
                output.add(new PlayUrl(url, source));
            }
        }
    }

    private static Object invokeNoArg(
            Class<?> type,
            Object instance,
            String name
    ) {
        try {
            Method method = type.getMethod(name);
            return method.invoke(instance);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value instanceof Number number && number.intValue() != 0;
    }

    private static int collectionSize(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        return value != null && value.getClass().isArray()
                ? Array.getLength(value)
                : 0;
    }

    private static String stringValue(Object value) {
        return value == null ? "unknown" : value.toString();
    }

    static final class Snapshot {
        final String aid;
        final int awemeType;
        final boolean hasVideo;
        final boolean isAd;
        final boolean hasRawAd;
        final boolean hasArticle;
        final int imageCount;
        final int imageInfoCount;
        final boolean hostImage;
        final boolean hostMultiImage;
        final boolean slides;
        final String filterReason;
        final List<PlayUrl> playUrls;

        Snapshot(
                String aid,
                int awemeType,
                boolean hasVideo,
                boolean isAd,
                boolean hasRawAd,
                boolean hasArticle,
                int imageCount,
                int imageInfoCount,
                boolean hostImage,
                boolean hostMultiImage,
                boolean slides,
                String filterReason,
                List<PlayUrl> playUrls
        ) {
            this.aid = aid;
            this.awemeType = awemeType;
            this.hasVideo = hasVideo;
            this.isAd = isAd;
            this.hasRawAd = hasRawAd;
            this.hasArticle = hasArticle;
            this.imageCount = imageCount;
            this.imageInfoCount = imageInfoCount;
            this.hostImage = hostImage;
            this.hostMultiImage = hostMultiImage;
            this.slides = slides;
            this.filterReason = filterReason;
            this.playUrls = playUrls;
        }

        boolean shouldFilter() {
            return filterReason != null;
        }

        boolean shouldFilterVisibleAdMarker() {
            return !hasVideo;
        }

        boolean hasDownloadUrl() {
            return !playUrls.isEmpty();
        }

        String classificationDetails() {
            return "aid=" + aid
                    + " type=" + awemeType
                    + " video=" + hasVideo
                    + " isAd=" + isAd
                    + " rawAd=" + hasRawAd
                    + " article=" + hasArticle
                    + " images=" + imageCount
                    + " imageInfos=" + imageInfoCount
                    + " hostImage=" + hostImage
                    + " hostMultiImage=" + hostMultiImage
                    + " slides=" + slides;
        }
    }

    static final class PlayUrl {
        final String url;
        final String source;

        PlayUrl(String url, String source) {
            this.url = url;
            this.source = source;
        }
    }
}
