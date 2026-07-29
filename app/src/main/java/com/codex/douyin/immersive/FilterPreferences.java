package com.codex.douyin.immersive;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FilterPreferences {
    public static final String NAME = "content_filter";
    public static final String KEY_SKIP_ADS = "skip_ads";
    public static final String KEY_SKIP_IMAGES = "skip_images";
    public static final String KEY_SKIP_LIVES = "skip_lives";
    public static final String KEY_SKIP_VIDEOS = "skip_videos";
    public static final String KEY_VIDEO_KEYWORDS = "video_keywords";

    public static final boolean DEFAULT_SKIP_ADS = true;
    public static final boolean DEFAULT_SKIP_IMAGES = true;
    public static final boolean DEFAULT_SKIP_LIVES = true;
    public static final boolean DEFAULT_SKIP_VIDEOS = false;

    private FilterPreferences() {
    }

    public static Values defaults() {
        return new Values(
                DEFAULT_SKIP_ADS,
                DEFAULT_SKIP_IMAGES,
                DEFAULT_SKIP_LIVES,
                DEFAULT_SKIP_VIDEOS,
                ""
        );
    }

    public static Values read(SharedPreferences preferences) {
        if (preferences == null) {
            return defaults();
        }
        return new Values(
                preferences.getBoolean(KEY_SKIP_ADS, DEFAULT_SKIP_ADS),
                preferences.getBoolean(KEY_SKIP_IMAGES, DEFAULT_SKIP_IMAGES),
                preferences.getBoolean(KEY_SKIP_LIVES, DEFAULT_SKIP_LIVES),
                preferences.getBoolean(KEY_SKIP_VIDEOS, DEFAULT_SKIP_VIDEOS),
                preferences.getString(KEY_VIDEO_KEYWORDS, "")
        );
    }

    public static final class Values {
        public final boolean skipAds;
        public final boolean skipImages;
        public final boolean skipLives;
        public final boolean skipVideos;
        public final String keywordText;
        private final List<String> keywords;

        public Values(
                boolean skipAds,
                boolean skipImages,
                boolean skipLives,
                boolean skipVideos,
                String keywordText
        ) {
            this.skipAds = skipAds;
            this.skipImages = skipImages;
            this.skipLives = skipLives;
            this.skipVideos = skipVideos;
            this.keywordText = keywordText == null ? "" : keywordText;
            this.keywords = parseKeywords(this.keywordText);
        }

        public List<String> keywords() {
            return keywords;
        }

        public String matchingVideoKeyword(String title, String description) {
            if (keywords.isEmpty()) {
                return null;
            }
            String searchable = safeLower(title) + "\n" + safeLower(description);
            for (String keyword : keywords) {
                if (searchable.contains(safeLower(keyword))) {
                    return keyword;
                }
            }
            return null;
        }

        private static List<String> parseKeywords(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return Collections.emptyList();
            }
            Set<String> unique = new LinkedHashSet<>();
            for (String part : raw.split("[\\r\\n,，;；]+")) {
                String keyword = part.trim();
                if (!keyword.isEmpty()) {
                    unique.add(keyword);
                }
            }
            return unique.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(unique));
        }

        private static String safeLower(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT);
        }
    }
}
