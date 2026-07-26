package com.codex.douyin.immersive.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.annotations.SerializedName;

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
    public void videoAdvertisementWithPlaybackUrlRemainsPlayable() throws Exception {
        FakeAweme aweme = videoAweme(0);
        aweme.video = new FakeVideo(
                List.of("https://example.invalid/video.mp4")
        );
        aweme.isAd = true;
        aweme.rawAd = new Object();

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertFalse(snapshot.shouldFilter());
        assertFalse(snapshot.shouldFilterVisibleAdMarker());
        assertTrue(snapshot.hasVideo);
        assertTrue(snapshot.isAd);
        assertTrue(snapshot.hasRawAd);
        assertEquals(1, snapshot.playUrls.size());
        assertEquals("play_addr", snapshot.playUrls.get(0).source);
    }

    @Test
    public void advertisementPlaceholderVideoWaitsForRenderWatchdog()
            throws Exception {
        FakeAweme aweme = videoAweme(140);
        aweme.isAd = true;
        aweme.rawAd = new Object();

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertTrue(snapshot.hasVideo);
        assertTrue(snapshot.playUrls.isEmpty());
        assertFalse(snapshot.shouldFilter());
        assertFalse(snapshot.shouldFilterVisibleAdMarker());
    }

    @Test
    public void nonHttpAdvertisementPlaybackUrlWaitsForRenderWatchdog()
            throws Exception {
        FakeAweme aweme = videoAweme(0);
        aweme.video = new FakeVideo(
                List.of("ftp://example.invalid/video.mp4")
        );
        aweme.isAd = true;

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertTrue(snapshot.playUrls.isEmpty());
        assertFalse(snapshot.shouldFilter());
        assertFalse(snapshot.shouldFilterVisibleAdMarker());
    }

    @Test
    public void nonVideoAdvertisementIsFiltered() throws Exception {
        FakeAweme aweme = videoAweme(0);
        aweme.video = null;
        aweme.isAd = true;
        aweme.rawAd = new Object();

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertTrue(snapshot.shouldFilter());
        assertTrue(snapshot.shouldFilterVisibleAdMarker());
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
        assertEquals("photo article model", snapshot.filterReason);
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

    public static final class FakeAweme {
        public String aid;
        public int awemeType;
        public boolean isAd;
        public boolean isSlides;
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
