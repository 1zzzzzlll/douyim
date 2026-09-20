package com.zz.douyin.hook;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class Mp3EncoderTest {
    @Test public void encodesStereoPcmAsCompleteMpegLayerThreeFrames() throws Exception {
        verifyFrames(44100, 2, false);
    }

    @Test public void encodesMonoFloatPcm() throws Exception {
        verifyFrames(48000, 1, true);
    }

    @Test public void resamplesHighRatePcm() throws Exception {
        verifyFrames(96000, 2, false);
    }

    @Test(expected = IOException.class) public void rejectsEmptyAudio() throws Exception {
        try (Mp3Encoder encoder = new Mp3Encoder(44100, 2)) {
            encoder.finish(new ByteArrayOutputStream());
        }
    }

    @Test(expected = IOException.class) public void rejectsUnsupportedChannelCount() throws Exception {
        new Mp3Encoder(44100, 6);
    }

    private void verifyFrames(int rate, int channels, boolean floating) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Mp3Encoder encoder = new Mp3Encoder(rate, channels)) {
            // Half a second in uneven chunks exercises the streaming adapter.
            for (int base = 0; base < rate / 2; base += 777) {
                int frames = Math.min(777, rate / 2 - base);
                ByteBuffer pcm = ByteBuffer.allocate(frames * channels * (floating ? 4 : 2))
                        .order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < frames; i++) {
                    float value = (float) Math.sin((base + i) * 2 * Math.PI * 440 / rate) * 0.5f;
                    for (int c = 0; c < channels; c++) {
                        if (floating) pcm.putFloat(value);
                        else pcm.putShort((short) (value * 32767));
                    }
                }
                pcm.flip();
                encoder.write(pcm, floating, output);
            }
            encoder.finish(output);
        }
        byte[] mp3 = output.toByteArray();
        int count = 0;
        int offset = 0;
        int[] bitrates = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
        int[] rates = {44100, 48000, 32000};
        while (offset < mp3.length) {
            assertTrue("complete header", offset + 4 <= mp3.length);
            assertEquals(0xff, mp3[offset] & 0xff);
            assertEquals("MPEG1 Layer III", 0xfa, mp3[offset + 1] & 0xfe);
            int bitrate = bitrates[(mp3[offset + 2] & 0xf0) >> 4];
            int sampleRate = rates[(mp3[offset + 2] & 0x0c) >> 2];
            assertEquals(rate > 48000 ? 48000 : rate, sampleRate);
            assertEquals(channels == 1, ((mp3[offset + 3] & 0xc0) >> 6) == 3);
            int length = 144000 * bitrate / sampleRate + ((mp3[offset + 2] >> 1) & 1);
            offset += length;
            count++;
        }
        assertEquals("no partial final frame", mp3.length, offset);
        assertTrue("duration includes all samples", count >= 19);
    }
}
