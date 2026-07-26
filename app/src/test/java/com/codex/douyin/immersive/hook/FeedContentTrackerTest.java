package com.codex.douyin.immersive.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void ordinaryVideoRemainsAccepted() throws Exception {
        FakeAweme aweme = videoAweme(0);

        FeedContentTracker.Snapshot snapshot = snapshot(aweme);

        assertFalse(snapshot.shouldFilter());
        assertTrue(snapshot.hasVideo);
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
}
