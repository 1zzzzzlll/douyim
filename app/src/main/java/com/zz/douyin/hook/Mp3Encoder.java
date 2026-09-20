package com.zz.douyin.hook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import de.sciss.jump3r.mp3.*;
import de.sciss.jump3r.mpg.Common;
import de.sciss.jump3r.mpg.Interface;
import de.sciss.jump3r.mpg.MPGLib;

/** Android PCM adapter for the Java LAME core; does not require Java Sound or JNI. */
final class Mp3Encoder implements AutoCloseable {
    private final Lame lame = new Lame();
    private final LameGlobalFlags flags;
    private final int channels;
    private final int sampleRate;
    private final int[] left = new int[1152];
    private final int[] right = new int[1152];
    private final byte[] encoded = new byte[16384];
    private long frames;

    Mp3Encoder(int sampleRate, int channels) throws IOException {
        if (channels < 1 || channels > 2 || sampleRate < 8000 || sampleRate > 192000) {
            throw new IOException("unsupported PCM format: " + sampleRate + "Hz / " + channels);
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        GainAnalysis gain = new GainAnalysis();
        BitStream bits = new BitStream();
        Presets presets = new Presets();
        QuantizePVT quantizePvt = new QuantizePVT();
        Quantize quantize = new Quantize();
        VBRTag tag = new VBRTag();
        Version version = new Version();
        ID3Tag id3 = new ID3Tag();
        Reservoir reservoir = new Reservoir();
        Takehiro takehiro = new Takehiro();
        MPGLib mpg = new MPGLib();
        Interface decoder = new Interface();
        Common common = new Common();
        lame.setModules(gain, bits, presets, quantizePvt, quantize, tag, version, id3, mpg);
        bits.setModules(gain, mpg, version, tag);
        id3.setModules(bits, version);
        presets.setModules(lame);
        quantize.setModules(bits, reservoir, quantizePvt, takehiro);
        quantizePvt.setModules(takehiro, reservoir, lame.enc.psy);
        reservoir.setModules(bits);
        takehiro.setModules(quantizePvt);
        tag.setModules(lame, bits, version);
        mpg.setModules(decoder, common);
        decoder.setModules(tag, common);
        flags = lame.lame_init();
        flags.num_channels = channels;
        flags.in_samplerate = sampleRate;
        flags.out_samplerate = sampleRate > 48000 ? 48000 : sampleRate;
        flags.mode = channels == 1 ? MPEGMode.MONO : MPEGMode.JOINT_STEREO;
        flags.brate = channels == 1 ? 128 : 192;
        flags.quality = 5;
        // Constant bitrate needs no seek-back to patch a Xing header.
        flags.bWriteVbrTag = false;
        flags.write_id3tag_automatic = false;
        id3.id3tag_init(flags);
        if (lame.lame_init_params(flags) < 0) {
            lame.lame_close(flags);
            throw new IOException("MP3 encoder initialization failed");
        }
    }

    boolean matches(int rate, int count) {
        return sampleRate == rate && channels == count;
    }

    void write(ByteBuffer pcm, boolean floatPcm, OutputStream output) throws IOException {
        pcm.order(ByteOrder.LITTLE_ENDIAN);
        int frameBytes = channels * (floatPcm ? 4 : 2);
        if (pcm.remaining() % frameBytes != 0) throw new IOException("unaligned PCM buffer");
        while (pcm.hasRemaining()) {
            int count = Math.min(left.length, pcm.remaining() / frameBytes);
            for (int i = 0; i < count; i++) {
                left[i] = sample(pcm, floatPcm);
                right[i] = channels == 2 ? sample(pcm, floatPcm) : left[i];
            }
            int bytes = lame.lame_encode_buffer_int(
                    flags, left, right, count, encoded, 0, encoded.length);
            writeEncoded(output, bytes);
            frames += count;
        }
    }

    private static int sample(ByteBuffer pcm, boolean floating) {
        if (!floating) return pcm.getShort() << 16;
        float value = pcm.getFloat();
        if (!Float.isFinite(value)) return 0;
        return (int) (Math.max(-1.0, Math.min(1.0, value)) * Integer.MAX_VALUE);
    }

    void finish(OutputStream output) throws IOException {
        if (frames == 0) throw new IOException("audio track contains no samples");
        writeEncoded(output, lame.lame_encode_flush(flags, encoded, 0, encoded.length));
        output.flush();
    }

    private void writeEncoded(OutputStream output, int bytes) throws IOException {
        if (bytes < 0) throw new IOException("MP3 encoding failed: " + bytes);
        output.write(encoded, 0, bytes);
    }

    @Override public void close() {
        lame.lame_close(flags);
    }
}
