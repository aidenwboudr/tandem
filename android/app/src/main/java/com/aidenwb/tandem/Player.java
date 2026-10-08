package com.aidenwb.tandem;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Plays the laptop's stream (48 kHz stereo s16, raw or Opus) into the headphones.
 * It never asks for audio focus, so it mixes with whatever the phone is playing, calls included (LinkService
 * hands it the headphones' call channel then).
 */
final class Player {
    static final int CODEC_PCM = 0, CODEC_OPUS = 1;
    private static final int RATE = 48000, FRAME_BYTES = 4;
    // AudioFlinger won't start a track until its buffer is full, so the buffer size IS the latency
    // target: it fills to this before playing, and non-blocking writes past it are dropped.
    private static final int TARGET_FRAMES = RATE * 120 / 1000;

    private AudioTrack track;
    private int deviceId = -1;
    private MediaCodec opus;
    private long ptsUs;
    private byte[] pcm = new byte[960 * FRAME_BYTES * 2];
    long dropped;

    void feed(AudioDeviceInfo out, int codec, byte[] buf, int off, int len) {
        if (track == null || out.getId() != deviceId) open(out);
        if (codec == CODEC_PCM) {
            write(buf, off, len);
        } else if (codec == CODEC_OPUS) {
            decode(buf, off, len);
        }
    }

    private void open(AudioDeviceInfo out) {
        release();
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(min, RATE / 4 * FRAME_BYTES)) // 250 ms capacity
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.setBufferSizeInFrames(TARGET_FRAMES);
        // Only ever play into the headphones: if they go away we stop, never the speaker.
        track.setPreferredDevice(out);
        deviceId = out.getId();
        track.play();
    }

    private void write(byte[] b, int off, int len) {
        if (audible(b, off, len)) LinkService.lastLoudAt = SystemClock.elapsedRealtime();
        int n = track.write(b, off, len, AudioTrack.WRITE_NON_BLOCKING);
        if (n < len) dropped++; // buffer full: the laptop is ahead, drop to hold latency down
    }

    /** Louder than about -50 dBFS? (s16 LE; every 4th frame's left sample is plenty.) An app that
     *  holds a stream open on the laptop streams silence, and that shouldn't count as playing. */
    private static boolean audible(byte[] b, int off, int len) {
        for (int i = off; i + 1 < off + len; i += 16) {
            int s = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            if (s > 100 || s < -100) return true;
        }
        return false;
    }

    private void decode(byte[] b, int off, int len) {
        try {
            if (opus == null) opus = newOpusDecoder();
            int in = opus.dequeueInputBuffer(5000);
            if (in >= 0) {
                ByteBuffer ib = opus.getInputBuffer(in);
                ib.clear();
                ib.put(b, off, len);
                opus.queueInputBuffer(in, 0, len, ptsUs, 0);
                ptsUs += 20000;
            } else {
                dropped++;
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int o;
            while ((o = opus.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (o < 0) continue; // format/buffers changed
                if (info.size > 0) {
                    ByteBuffer ob = opus.getOutputBuffer(o);
                    if (pcm.length < info.size) pcm = new byte[info.size];
                    ob.position(info.offset);
                    ob.get(pcm, 0, info.size);
                    write(pcm, 0, info.size);
                }
                opus.releaseOutputBuffer(o, false);
            }
        } catch (RuntimeException e) {
            // A wedged decoder shouldn't take the service down; start a fresh one next packet.
            releaseOpus();
            LinkService.error = "opus: " + e;
        }
    }

    private static MediaCodec newOpusDecoder() {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 2);
        ByteBuffer head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put("OpusHead".getBytes());
        head.put((byte) 1); // version
        head.put((byte) 2); // channels
        head.putShort((short) 0); // pre-skip
        head.putInt(RATE); // input sample rate
        head.putShort((short) 0); // output gain
        head.put((byte) 0); // channel mapping family
        head.flip();
        f.setByteBuffer("csd-0", head);
        f.setByteBuffer("csd-1", le64(0)); // codec delay, ns
        f.setByteBuffer("csd-2", le64(80_000_000L)); // seek pre-roll, ns
        try {
            MediaCodec c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS);
            c.configure(f, null, null, 0);
            c.start();
            return c;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ByteBuffer le64(long v) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(v);
        b.flip();
        return b;
    }

    private void releaseOpus() {
        if (opus != null) {
            try {
                opus.stop();
            } catch (RuntimeException ignored) {
            }
            opus.release();
            opus = null;
        }
        ptsUs = 0;
    }

    boolean isOpen() {
        return track != null;
    }

    void release() {
        releaseOpus();
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (IllegalStateException ignored) {
            }
            track.release();
            track = null;
        }
        deviceId = -1;
    }
}
