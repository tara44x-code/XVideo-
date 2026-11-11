package com.mohannadsatlink.videox;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads and caches timeline previews per source file: video thumbnails (evenly spaced frames)
 * and an audio waveform (peak level per time bucket). Work runs on a background thread;
 * {@link Listener#onPreviewReady()} is called on the main thread when new data arrives.
 */
final class MediaPreviewCache {
    interface Listener { void onPreviewReady(); }

    static final class Thumbs {
        final long[] timesMs;
        final Bitmap[] frames;
        Thumbs(long[] timesMs, Bitmap[] frames) { this.timesMs = timesMs; this.frames = frames; }

        Bitmap nearest(long sourceMs) {
            if (frames.length == 0) return null;
            int best = 0;
            long bestDistance = Long.MAX_VALUE;
            for (int i = 0; i < timesMs.length; i++) {
                long d = Math.abs(timesMs[i] - sourceMs);
                if (d < bestDistance && frames[i] != null) { bestDistance = d; best = i; }
            }
            return frames[best];
        }
    }

    static final class Wave {
        final float[] peaks;   // 0..1
        final long bucketMs;
        Wave(float[] peaks, long bucketMs) { this.peaks = peaks; this.bucketMs = bucketMs; }

        float peakAt(long sourceFromMs, long sourceToMs) {
            if (peaks.length == 0) return 0f;
            int a = (int) Math.max(0, sourceFromMs / bucketMs);
            int b = (int) Math.min(peaks.length - 1, Math.max(a, sourceToMs / bucketMs));
            float max = 0f;
            for (int i = a; i <= b && i < peaks.length; i++) max = Math.max(max, peaks[i]);
            return max;
        }
    }

    private static final int THUMB_HEIGHT = 96;
    private static final int MAX_THUMBS = 48;
    private static final long WAVE_BUCKET_MS = 50;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Thumbs> thumbs = new HashMap<>();
    private final Map<String, Wave> waves = new HashMap<>();
    private final Set<String> requested = new HashSet<>();
    private Listener listener;
    private volatile boolean released;

    MediaPreviewCache(Context context) { this.context = context.getApplicationContext(); }

    void setListener(Listener l) { listener = l; }

    Thumbs thumbs(Uri uri) { synchronized (thumbs) { return thumbs.get(uri.toString()); } }
    Wave wave(Uri uri) { synchronized (waves) { return waves.get(uri.toString()); } }

    /** Queue preview loading for a source (no-op if already requested). */
    void request(TimelineClip clip) {
        String key = clip.uri.toString();
        if (!requested.add(key)) return;
        final Uri uri = clip.uri;
        final long duration = clip.sourceDurationMs;
        final boolean video = clip.hasVideo, audio = clip.hasAudio;
        worker.execute(() -> {
            if (released) return;
            if (video) {
                Thumbs t = loadThumbs(uri, duration);
                if (t != null) { synchronized (thumbs) { thumbs.put(key, t); } notifyReady(); }
            }
            if (audio && !released) {
                Wave w = loadWave(uri, duration);
                if (w != null) { synchronized (waves) { waves.put(key, w); } notifyReady(); }
            }
        });
    }

    private void notifyReady() {
        main.post(() -> { if (!released && listener != null) listener.onPreviewReady(); });
    }

    private Thumbs loadThumbs(Uri uri, long durationMs) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, uri);
            int count = (int) Math.max(2, Math.min(MAX_THUMBS, durationMs / 1000 + 1));
            long[] times = new long[count];
            Bitmap[] frames = new Bitmap[count];
            for (int i = 0; i < count && !released; i++) {
                times[i] = durationMs * i / Math.max(1, count - 1);
                Bitmap frame = r.getFrameAtTime(times[i] * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame == null) continue;
                int h = THUMB_HEIGHT;
                int w = Math.max(1, Math.round(frame.getWidth() * h / (float) Math.max(1, frame.getHeight())));
                Bitmap scaled = Bitmap.createScaledBitmap(frame, w, h, true);
                if (scaled != frame) frame.recycle();
                frames[i] = scaled;
                // Publish progressively so the first frames show quickly.
                if (i == 3 || i == count - 1) {
                    Thumbs partial = new Thumbs(times.clone(), frames.clone());
                    synchronized (thumbs) { thumbs.put(uri.toString(), partial); }
                    notifyReady();
                }
            }
            return new Thumbs(times, frames);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    /** Decodes the first audio track to PCM and records the peak level of every 50 ms bucket. */
    private Wave loadWave(Uri uri, long durationMs) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (afd == null) return null;
                if (afd.getDeclaredLength() < 0) extractor.setDataSource(afd.getFileDescriptor());
                else extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getDeclaredLength());
            }
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; break; }
            }
            if (track < 0) return null;
            MediaFormat format = extractor.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) : 2;
            float[] peaks = new float[(int) (durationMs / WAVE_BUCKET_MS) + 1];
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();
            extractor.selectTrack(track);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            while (!outputDone && !released) {
                if (!inputDone) {
                    int in = decoder.dequeueInputBuffer(5000);
                    if (in >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(in);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = decoder.dequeueOutputBuffer(info, 5000);
                if (out >= 0) {
                    ByteBuffer pcm = decoder.getOutputBuffer(out);
                    if (pcm != null && info.size > 0) {
                        pcm.position(info.offset);
                        pcm.limit(info.offset + info.size);
                        ShortBuffer samples = pcm.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                        long startMs = info.presentationTimeUs / 1000;
                        int frames = samples.remaining() / channels;
                        for (int f = 0; f < frames; f++) {
                            long t = startMs + f * 1000L / sampleRate;
                            int bucket = (int) (t / WAVE_BUCKET_MS);
                            if (bucket < 0 || bucket >= peaks.length) continue;
                            float v = Math.abs(samples.get(f * channels)) / 32768f;
                            if (v > peaks[bucket]) peaks[bucket] = v;
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(out, false);
                }
            }
            // Normalise so quiet recordings are still visible.
            float max = 0.05f;
            for (float p : peaks) max = Math.max(max, p);
            for (int i = 0; i < peaks.length; i++) peaks[i] = (float) Math.sqrt(peaks[i] / max);
            return new Wave(peaks, WAVE_BUCKET_MS);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) {}
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    void release() {
        released = true;
        worker.shutdownNow();
        synchronized (thumbs) {
            for (Thumbs t : thumbs.values()) for (Bitmap b : t.frames) if (b != null && !b.isRecycled()) b.recycle();
            thumbs.clear();
        }
        synchronized (waves) { waves.clear(); }
    }
}
