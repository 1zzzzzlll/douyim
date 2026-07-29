package com.codex.douyin.immersive.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.annotations.SerializedName;
import com.codex.douyin.immersive.FilterPreferences;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

public final class FeedContentTrackerTest {
    @Test
    public void typeTwoWithPlaceholderVideoIsFiltered() throws Exception {
        FakeAweme aweme = videoAweme(2);

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertTrue(snapshot.shouldFilter());
        assertEquals("photo article model", snapshot.filterReason);
    }

    @Test
    public void multiImageTypeWithPlaceholderVideoIsFiltered() throws Exception {
        FakeAweme aweme = videoAweme(0x44);

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertTrue(snapshot.shouldFilter());
        assertEquals("photo article model", snapshot.filterReason);
    }

    @Test
    public void slidesWithPlaceholderVideoAreFiltered() throws Exception {
        FakeAweme aweme = videoAweme(0);
        aweme.isSlides = true;

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertTrue(snapshot.shouldFilter());
        assertTrue(snapshot.slides);
    }

    @Test
    public void imageInfosWithPlaceholderVideoAreFiltered() throws Exception {
        FakeAweme aweme = videoAweme(0);
        aweme.imageInfos = List.of(new Object(), new Object());

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertTrue(snapshot.shouldFilter());
        assertEquals(2, snapshot.imageInfoCount);
    }

    @Test
    public void hostImageMethodsOverridePlaceholderVideo() throws Exception {
        FakeAweme aweme = videoAweme(0);
        aweme.hostImage = true;
        aweme.hostMultiImage = true;

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertTrue(snapshot.shouldFilter());
        assertTrue(snapshot.hostImage);
        assertTrue(snapshot.hostMultiImage);
    }

    @Test
    public void ordinaryNonAdPlaceholderVideoRemainsAccepted() throws Exception {
        FakeAweme aweme = videoAweme(0);

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertFalse(snapshot.shouldFilter());
        assertTrue(snapshot.hasVideo);
    }

    @Test
    public void videoAdvertisementWithPlaybackUrlIsFiltered() throws Exception {
        FakeAweme aweme = videoAweme(0);
        aweme.video = new FakeVideo(
                List.of("https://example.invalid/video.mp4")
        );
        aweme.isAd = true;
        aweme.rawAd = new Object();

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertTrue(snapshot.shouldFilter());
        assertEquals("advertisement model", snapshot.filterReason);
        assertTrue(snapshot.hasVideo);
        assertTrue(snapshot.isAd);
        assertTrue(snapshot.hasRawAd);
        assertEquals(1, snapshot.playUrls.size());
        assertEquals("play_addr", snapshot.playUrls.get(0).source);
    }

    @Test
    public void advertisementPlaceholderVideoIsFiltered()
            throws Exception {
        FakeAweme aweme = videoAweme(140);
        aweme.isAd = true;
        aweme.rawAd = new Object();

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertTrue(snapshot.hasVideo);
        assertTrue(snapshot.playUrls.isEmpty());
        assertTrue(snapshot.shouldFilter());
        assertEquals("advertisement model", snapshot.filterReason);
    }

    @Test
    public void nonHttpAdvertisementPlaybackUrlIsFiltered()
            throws Exception {
        FakeAweme aweme = videoAweme(0);
        aweme.video = new FakeVideo(
                List.of("ftp://example.invalid/video.mp4")
        );
        aweme.isAd = true;

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertTrue(snapshot.playUrls.isEmpty());
        assertTrue(snapshot.shouldFilter());
        assertEquals("advertisement model", snapshot.filterReason);
    }

    @Test
    public void nonVideoAdvertisementIsFiltered() throws Exception {
        FakeAweme aweme = videoAweme(0);
        aweme.video = null;
        aweme.isAd = true;
        aweme.rawAd = new Object();

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertTrue(snapshot.shouldFilter());
        assertEquals("advertisement model", snapshot.filterReason);
    }

    @Test
    public void photoAdvertisementWithPlaceholderVideoRemainsFiltered()
            throws Exception {
        FakeAweme aweme = videoAweme(2);
        aweme.isAd = true;
        aweme.rawAd = new Object();

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertTrue(snapshot.shouldFilter());
        assertEquals("advertisement model", snapshot.filterReason);
    }

    @Test
    public void disabledTypeSettingsKeepEveryCategory() {
        FilterPreferences.Values disabled =
                new FilterPreferences.Values(false, false, false, false, "");

        FakeAweme ad = videoAweme(0);
        ad.isAd = true;
        assertFalse(snapshot(ad, disabled).shouldFilter());

        FakeAweme image = videoAweme(2);
        assertFalse(snapshot(image, disabled).shouldFilter());

        FakeAweme live = videoAweme(101);
        assertFalse(snapshot(live, disabled).shouldFilter());
    }

    @Test
    public void liveSettingFiltersTypeOneHundredOne() {
        FakeAweme live = videoAweme(101);

        FeedContentTracker.Snapshot snapshot = snapshot(
                live,
                new FilterPreferences.Values(false, false, true, false, "")
        );

        assertTrue(snapshot.live);
        assertTrue(snapshot.shouldFilter());
        assertEquals("live model", snapshot.filterReason);
    }

    @Test
    public void videoSettingFiltersOrdinaryVideo() {
        FakeAweme aweme = videoAweme(0);

        FeedContentTracker.Snapshot snapshot = snapshot(
                aweme,
                new FilterPreferences.Values(false, false, false, true, "")
        );

        assertTrue(snapshot.shouldFilter());
        assertEquals("video type setting", snapshot.filterReason);
    }

    @Test
    public void keywordMatchesVideoItemTitle() {
        FakeAweme aweme = videoAweme(0);
        aweme.itemTitle = "今天一起玩超级游戏";

        FeedContentTracker.Snapshot snapshot = snapshot(
                aweme,
                new FilterPreferences.Values(
                        false,
                        false,
                        false,
                        false,
                        "推广\n游戏"
                )
        );

        assertTrue(snapshot.shouldFilter());
        assertEquals("video keyword: 游戏", snapshot.filterReason);
        assertEquals(aweme.itemTitle, snapshot.title);
    }

    @Test
    public void keywordMatchesVideoDescriptionIgnoringEnglishCase() {
        FakeAweme aweme = videoAweme(0);
        aweme.desc = "A closer look at the New Gadget today";

        FeedContentTracker.Snapshot snapshot = snapshot(
                aweme,
                new FilterPreferences.Values(
                        false,
                        false,
                        false,
                        false,
                        "GADGET，其他"
                )
        );

        assertTrue(snapshot.shouldFilter());
        assertEquals("video keyword: GADGET", snapshot.filterReason);
        assertEquals(aweme.desc, snapshot.description);
    }

    @Test
    public void videoKeywordsDoNotOverrideDisabledAdvertisementType() {
        FakeAweme aweme = videoAweme(0);
        aweme.isAd = true;
        aweme.desc = "游戏推广";

        FeedContentTracker.Snapshot snapshot = snapshot(
                aweme,
                new FilterPreferences.Values(
                        false,
                        false,
                        false,
                        false,
                        "游戏"
                )
        );

        assertFalse(snapshot.shouldFilter());
    }

    private static FakeAweme videoAweme(int awemeType) {
        FakeAweme aweme = new FakeAweme();
        aweme.aid = "test-" + awemeType;
        aweme.awemeType = awemeType;
        aweme.video = new Object();
        aweme.images = Collections.emptyList();
        aweme.imageInfos = Collections.emptyList();
        return aweme;
    }

    private static FeedContentTracker.Snapshot snapshot(Object aweme)
            throws Exception {
        Method method = FeedContentTracker.class.getDeclaredMethod(
                "snapshot",
                Object.class
        );
        method.setAccessible(true);
        return (FeedContentTracker.Snapshot) method.invoke(null, aweme);
    }

    private static FeedContentTracker.Snapshot snapshot(
            Object aweme,
            FilterPreferences.Values settings
    ) {
        return FeedContentTracker.snapshot(aweme, settings);
    }

    public static final class FakeAweme {
        public String aid;
        public int awemeType;
        public boolean isAd;
        public boolean isSlides;
        public String itemTitle;
        public String title;
        public String desc;
        public Object video;
        public Object articleInfo;
        public List<Object> images;
        public List<Object> imageInfos;
        public boolean hostImage;
        public boolean hostMultiImage;
        public Object rawAd;

        public boolean isImage() {
            return hostImage;
        }

        public boolean isMultiImage() {
            return hostMultiImage;
        }

        public Object getAwemeRawAd() {
            return rawAd;
        }
    }

    public static final class FakeVideo {
        @SerializedName("play_addr")
        public final FakeUrlModel playAddress;

        FakeVideo(List<String> urls) {
            playAddress = new FakeUrlModel(urls);
        }
    }

    public static final class FakeUrlModel {
        private final List<String> urls;

        FakeUrlModel(List<String> urls) {
            this.urls = urls;
        }

        public List<String> getUrlList() {
            return urls;
        }
    }
}
