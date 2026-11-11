package com.mohannadsatlink.videox;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class VideoMerger {
    interface Listener { void onProgress(int percent); }
    private final Context context;
    private final AtomicBoolean cancelled;

    VideoMerger(Context context, AtomicBoolean cancelled) {
        this.context = context.getApplicationContext(); this.cancelled = cancelled;
    }

    void merge(List<Uri> inputs, File output, Listener listener) throws Exception {
        if (inputs.size() < 2) throw new IllegalArgumentException("اختر مقطعين على الأقل");
        MediaMuxer muxer = null; boolean started = false;
        try {
            MediaFormat baseVideo = format(inputs.get(0), "video/");
            MediaFormat baseAudio = format(inputs.get(0), "audio/");
            if (baseVideo == null) throw new IllegalArgumentException("الفيديو الأول غير صالح");
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoOut = muxer.addTrack(baseVideo);
            int audioOut = baseAudio == null ? -1 : muxer.addTrack(baseAudio);
            muxer.start(); started = true;
            long offsetUs = 0;
            for (int i = 0; i < inputs.size(); i++) {
                if (cancelled.get()) throw new InterruptedException("تم إلغاء العملية");
                Uri uri = inputs.get(i);
                MediaFormat vf = format(uri, "video/");
                MediaFormat af = format(uri, "audio/");
                if (!compatible(baseVideo, vf, true) || (baseAudio != null && !compatible(baseAudio, af, false)))
                    throw new IllegalArgumentException("المقاطع غير متوافقة للدمج السريع. اجعل الدقة والترميز متشابهين");
                long vEnd = copyTrack(uri, "video/", muxer, videoOut, offsetUs);
                long aEnd = audioOut >= 0 && af != null ? copyTrack(uri, "audio/", muxer, audioOut, offsetUs) : offsetUs;
                offsetUs = Math.max(vEnd, aEnd) + 33_333;
                listener.onProgress((i + 1) * 100 / inputs.size());
            }
        } catch (Exception e) {
            if (output.exists()) output.delete(); throw e;
        } finally {
            if (muxer != null) { try { if (started) muxer.stop(); } catch (Exception ignored) {} try { muxer.release(); } catch (Exception ignored) {} }
        }
    }

    private long copyTrack(Uri uri, String prefix, MediaMuxer muxer, int outTrack, long offset) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            setSource(ex, uri); int track = find(ex, prefix); if (track < 0) return offset;
            ex.selectTrack(track); ByteBuffer buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); long first = -1, last = offset;
            while (!cancelled.get()) {
                long t = ex.getSampleTime(); if (t < 0) break;
                if (first < 0) first = t;
                buffer.clear(); int size = ex.readSampleData(buffer, 0); if (size < 0) break;
                last = offset + Math.max(0, t - first); info.set(0, size, last, ex.getSampleFlags());
                muxer.writeSampleData(outTrack, buffer, info); ex.advance();
            }
            return last;
        } finally { ex.release(); }
    }

    private MediaFormat format(Uri uri, String prefix) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try { setSource(ex, uri); int t = find(ex, prefix); return t < 0 ? null : ex.getTrackFormat(t); }
        finally { ex.release(); }
    }
    private boolean compatible(MediaFormat a, MediaFormat b, boolean video) {
        if (a == null || b == null || !same(a, b, MediaFormat.KEY_MIME)) return false;
        if (video) return number(a, MediaFormat.KEY_WIDTH) == number(b, MediaFormat.KEY_WIDTH)
                && number(a, MediaFormat.KEY_HEIGHT) == number(b, MediaFormat.KEY_HEIGHT);
        return number(a, MediaFormat.KEY_SAMPLE_RATE) == number(b, MediaFormat.KEY_SAMPLE_RATE)
                && number(a, MediaFormat.KEY_CHANNEL_COUNT) == number(b, MediaFormat.KEY_CHANNEL_COUNT);
    }
    private boolean same(MediaFormat a, MediaFormat b, String key) { return a.containsKey(key) && b.containsKey(key) && a.getString(key).equals(b.getString(key)); }
    private int number(MediaFormat f, String key) { return f.containsKey(key) ? f.getInteger(key) : 0; }
    private int find(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) { String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME); if (m != null && m.startsWith(prefix)) return i; }
        return -1;
    }
    private void setSource(MediaExtractor ex, Uri uri) throws Exception {
        try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd == null) throw new IllegalArgumentException("تعذر فتح ملف");
            if (afd.getDeclaredLength() < 0) ex.setDataSource(afd.getFileDescriptor());
            else ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getDeclaredLength());
        }
    }
}
