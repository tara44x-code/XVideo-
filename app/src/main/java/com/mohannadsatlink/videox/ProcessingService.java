package com.mohannadsatlink.videox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that runs long video jobs (compress, convert, trim, batch, Pro edits)
 * so they keep going when the user leaves the app. Progress is reported through a
 * notification and a package-local broadcast that {@link MainActivity} listens to.
 */
public final class ProcessingService extends Service {
    static final String ACTION_START = "com.mohannadsatlink.videox.START";
    static final String ACTION_CANCEL = "com.mohannadsatlink.videox.CANCEL";
    static final String ACTION_STATUS = "com.mohannadsatlink.videox.STATUS";

    static volatile boolean running;
    static volatile int currentProgress;
    static volatile String completedPath;
    static volatile ArrayList<String> completedPaths;
    static volatile long completedOriginalSize;
    static volatile String currentLabel = "جاري معالجة الفيديو…";
    static volatile long startedAt;

    private static final String CHANNEL = "videox_processing";
    private static final int NOTIFICATION_ID = 71;
    /** Android drops notification updates sent faster than a few per second. */
    private static final long NOTIFY_INTERVAL_MS = 500;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;
    private int lastNotifiedProgress = -1;
    private long lastNotifyAt;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "معالجة الفيديو", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("تقدم ضغط وتحويل الفيديو");
            c.setLightColor(Color.rgb(90, 120, 255));
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoX:Processing");
        wakeLock.setReferenceCounted(false);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i == null) return START_NOT_STICKY;
        if (ACTION_CANCEL.equals(i.getAction())) { cancelled.set(true); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;

        running = true;
        cancelled.set(false);
        currentProgress = 0;
        lastNotifiedProgress = -1;
        startedAt = System.currentTimeMillis();
        completedPath = null;
        completedPaths = null;
        completedOriginalSize = i.getLongExtra("original", 0);
        currentLabel = i.getStringExtra("label");
        if (currentLabel == null) currentLabel = "جاري معالجة الفيديو…";

        startForeground(NOTIFICATION_ID, notification(currentLabel, 0, false));
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(6L * 60 * 60 * 1000);

        String input = i.getStringExtra("input"), output = i.getStringExtra("output");
        String[] batch = i.getStringArrayExtra("batchInputs");
        if (batch != null && batch.length > 0) executor.execute(() -> runBatch(i, batch));
        else executor.execute(() -> runJob(i, input, output));
        return START_NOT_STICKY;
    }

    private void runJob(Intent intent, String input, String output) {
        File temp = null;
        File destination = output == null ? null : new File(output);
        boolean success = false;
        try {
            if (destination == null) throw new IllegalArgumentException("بيانات العملية غير مكتملة");
            if ("timeline".equals(intent.getStringExtra("jobKind"))) {
                runTimeline(intent, destination);
                success = true;
                finishSuccess(output, "اكتمل تصدير المشروع");
                return;
            }
            if (input == null) throw new IllegalArgumentException("بيانات العملية غير مكتملة");
            Uri source = Uri.parse(input);

            if ("studio".equals(intent.getStringExtra("jobKind"))) {
                long start = intent.getLongExtra("start", 0), end = intent.getLongExtra("end", 0);
                new FfmpegTranscoder(this, cancelled).studioEdit(source, destination, start, end,
                        intent.getIntExtra("rotation", 0), intent.getBooleanExtra("mirror", false),
                        intent.getFloatExtra("speed", 1f), intent.getIntExtra("visual", 0),
                        intent.getIntExtra("studioAudio", 0), intent.getBooleanExtra("stripMetadata", true),
                        this::progress);
                success = true;
                finishSuccess(output, "اكتملت تعديلات Pro");
                return;
            }

            VideoTranscoder.Options o = new VideoTranscoder.Options();
            String presetName = intent.getStringExtra("preset");
            o.preset = presetName == null ? VideoTranscoder.Preset.MEDIUM : VideoTranscoder.Preset.valueOf(presetName);
            o.startMs = intent.getLongExtra("start", 0);
            o.endMs = intent.getLongExtra("end", 0);
            o.keepAudio = intent.getBooleanExtra("audio", true);
            o.outputFormat = intent.getStringExtra("outputFormat");
            o.targetBytes = intent.getLongExtra("target", 0);
            String aspect = intent.getStringExtra("aspect");
            if (aspect != null) o.aspect = aspect;
            o.shortEdge = intent.getIntExtra("shortEdge", 0);
            o.videoBitrate = intent.getIntExtra("videoBitrate", 0);
            o.frameRate = intent.getIntExtra("frameRate", 0);
            o.audioBitrate = intent.getIntExtra("audioBitrate", 128) * 1000;
            o.smartQuality = intent.getBooleanExtra("smart", true);
            o.scalePercent = intent.getIntExtra("scalePercent", 0);
            o.preferVbr = intent.getBooleanExtra("vbr", false);
            int audioBitrate = intent.getIntExtra("audioBitrate", 128);
            int audioSampleRate = intent.getIntExtra("audioSampleRate", 0);
            long duration = o.endMs > o.startMs ? o.endMs - o.startMs : new VideoInfo(this, source).durationMs;
            String requested = o.outputFormat;
            boolean legacy = (requested == null || !requested.startsWith("REMUX_")) && !VideoTranscoder.canDecode(this, source);

            if (requested != null && requested.startsWith("REMUX_")) {
                // Change container only: no re-encoding, so quality is identical and it takes seconds.
                String container = requested.endsWith("MKV") ? "matroska" : requested.endsWith("MOV") ? "mov" : "mp4";
                new FfmpegTranscoder(this, cancelled).remux(source, destination, container, o.startMs, o.endMs, duration, this::progress);
            } else if ("AUDIO_MP3".equals(requested) && legacy) {
                long end = o.endMs > o.startMs ? o.endMs : o.startMs + duration;
                new FfmpegTranscoder(this, cancelled).convertAudio(source, destination, "MP3", audioBitrate, audioSampleRate, o.startMs, end, 1f, this::progress);
            } else if ("AUDIO_MP3".equals(requested)) {
                // Built-in LAME encoder: works on every CPU, including 32-bit phones.
                new Mp3Transcoder(this, cancelled).convert(source, destination, audioBitrate, audioSampleRate, o.startMs, o.endMs, 1f, this::progress);
            } else if (requested != null && requested.startsWith("AUDIO_")) {
                long end = o.endMs > o.startMs ? o.endMs : o.startMs + duration;
                new FfmpegTranscoder(this, cancelled).convertAudio(source, destination, "M4A", audioBitrate, audioSampleRate, o.startMs, end, 1f, this::progress);
            } else if (legacy) {
                // RMVB / FLV / WMV …: FFmpeg decodes, the hardware encoder still does the heavy work.
                transcodeLegacy(source, destination, o, audioBitrate, duration, this::progress);
            } else if (o.keepAudio || (requested != null && requested.startsWith("FFMPEG"))) {
                temp = encodeWithSourceAudio(source, destination, o, audioBitrate, audioSampleRate, duration, 0, 100);
            } else {
                new VideoTranscoder(this, cancelled).transcode(source, destination, o, this::progress);
            }
            success = true;
            finishSuccess(output, "اكتملت معالجة الفيديو");
        } catch (Throwable e) {
            // Throwable (not only Exception): a missing native library or OOM must not crash the app.
            finishFailure(e, "فشلت المعالجة");
        } finally {
            if (temp != null) temp.delete();
            if (!success && destination != null && destination.exists()) destination.delete();
            stopSelf();
        }
    }

    /**
     * Encodes the video with MediaCodec (no audio), then lets FFmpeg add the source audio at the
     * requested quality in the requested container. Progress is mapped into [from, to].
     * Returns the temporary file (caller deletes it).
     */
    private File encodeWithSourceAudio(Uri source, File destination, VideoTranscoder.Options o, int audioKbps,
                                       int sampleRate, long durationMs, int from, int to) throws Exception {
        String requested = o.outputFormat == null ? "MP4_AVC" : o.outputFormat;
        String suffix = requested.startsWith("WEBM") ? ".webm" : requested.startsWith("3GP") ? ".3gp" : ".mp4";
        File temp = new File(destination.getParentFile(), ".videox_video_" + System.currentTimeMillis() + suffix);
        VideoTranscoder.Options videoOnly = o;
        if (requested.startsWith("FFMPEG")) o.outputFormat = "MP4_AVC";
        videoOnly.muxAudio = false;
        int split = from + (to - from) * 85 / 100;
        try {
            new VideoTranscoder(this, cancelled).transcode(source, temp, videoOnly, p -> progress(from + (split - from) * p / 100));
            if (o.keepAudio) {
                new FfmpegTranscoder(this, cancelled).muxWithSourceAudio(Uri.fromFile(temp), source, o.startMs, o.endMs, destination,
                        requested, audioKbps, sampleRate, durationMs, p -> progress(split + (to - split) * p / 100));
            } else {
                // Container change only (MKV/MOV/AVI without audio).
                new FfmpegTranscoder(this, cancelled).muxWithSourceAudio(Uri.fromFile(temp), Uri.fromFile(temp), 0, 0, destination,
                        requested, audioKbps, sampleRate, durationMs, p -> progress(split + (to - split) * p / 100));
            }
        } catch (Exception e) {
            temp.delete();
            throw e;
        } finally {
            o.outputFormat = requested;
        }
        return temp;
    }

    /** Picks output size/bitrate like {@link VideoTranscoder} does, then lets FFmpeg do the whole job. */
    private void transcodeLegacy(Uri source, File destination, VideoTranscoder.Options o, int audioKbps, long durationMs,
                                 FfmpegTranscoder.Listener listener) throws Exception {
        VideoInfo vi = new VideoInfo(this, source);
        int srcW = Math.max(16, vi.width), srcH = Math.max(16, vi.height);
        float scale;
        if (o.scalePercent > 0) scale = Math.min(1f, o.scalePercent / 100f);
        else if (o.shortEdge > 0) scale = Math.min(1f, o.shortEdge / (float) Math.min(srcW, srcH));
        else {
            int maxLong = o.preset == VideoTranscoder.Preset.LOW ? 854 : o.preset == VideoTranscoder.Preset.MEDIUM ? 1280 : 1920;
            scale = Math.min(1f, maxLong / (float) Math.max(srcW, srcH));
        }
        int w = Math.max(2, Math.round(srcW * scale) & ~1), h = Math.max(2, Math.round(srcH * scale) & ~1);
        String f = o.outputFormat == null ? "MP4_AVC" : o.outputFormat;
        long lengthMs = o.endMs > o.startMs ? o.endMs - o.startMs : durationMs;
        int bitrate;
        if (o.videoBitrate > 0) bitrate = o.videoBitrate;
        else if (o.targetBytes > 0) {
            long seconds = Math.max(1, lengthMs / 1000);
            bitrate = (int) Math.max(150_000, Math.min(20_000_000, o.targetBytes * 8 / seconds * 96 / 100 - (o.keepAudio ? audioKbps * 1000L : 0)));
        } else {
            double bpp = o.preset == VideoTranscoder.Preset.LOW ? 0.05 : o.preset == VideoTranscoder.Preset.MEDIUM ? 0.075 : 0.11;
            if (f.endsWith("HEVC") || f.endsWith("VP9")) bpp *= 0.65;
            double fps = o.frameRate > 0 ? o.frameRate : vi.frameRate > 1 ? Math.min(60, vi.frameRate) : 30;
            bitrate = (int) Math.max(250_000, (double) w * h * fps * bpp);
        }
        new FfmpegTranscoder(this, cancelled).transcodeLegacy(source, destination, f, o.startMs, o.endMs, w, h, bitrate,
                o.frameRate, o.keepAudio, audioKbps, durationMs, listener);
    }

    /** Renders the clips arranged in {@link EditorActivity}. */
    private void runTimeline(Intent intent, File destination) throws Exception {
        String[] uris = intent.getStringArrayExtra("uris");
        long[] ins = intent.getLongArrayExtra("ins"), outs = intent.getLongArrayExtra("outs");
        float[] speeds = intent.getFloatArrayExtra("speeds");
        boolean[] muted = intent.getBooleanArrayExtra("muted"), hasVideo = intent.getBooleanArrayExtra("hasVideo"), hasAudio = intent.getBooleanArrayExtra("hasAudio");
        if (uris == null || ins == null || outs == null || speeds == null || muted == null || hasVideo == null || hasAudio == null || uris.length == 0)
            throw new IllegalArgumentException("المشروع فارغ");
        ArrayList<FfmpegTranscoder.Segment> segments = new ArrayList<>();
        for (int i = 0; i < uris.length; i++) {
            FfmpegTranscoder.Segment seg = new FfmpegTranscoder.Segment();
            seg.uri = Uri.parse(uris[i]);
            seg.inMs = ins[i];
            seg.outMs = outs[i];
            seg.speed = speeds[i];
            seg.muted = muted[i];
            seg.hasVideo = hasVideo[i];
            seg.hasAudio = hasAudio[i];
            segments.add(seg);
        }
        new FfmpegTranscoder(this, cancelled).exportTimeline(segments, destination,
                intent.getIntExtra("width", 1280), intent.getIntExtra("height", 720), intent.getIntExtra("videoBitrate", 4_000_000),
                intent.getBooleanExtra("blur", false), intent.getStringExtra("audioFormat"), intent.getIntExtra("audioBitrate", 192),
                this::progress);
    }

    private void runBatch(Intent intent, String[] inputs) {
        ArrayList<String> results = new ArrayList<>();
        File current = null;
        try {
            String dirPath = intent.getStringExtra("outputDir");
            if (dirPath == null) throw new IllegalArgumentException("مجلد الحفظ غير محدد");
            File dir = new File(dirPath);
            dir.mkdirs();
            String presetName = intent.getStringExtra("preset");
            VideoTranscoder.Preset preset = presetName == null ? VideoTranscoder.Preset.MEDIUM : VideoTranscoder.Preset.valueOf(presetName);
            for (int index = 0; index < inputs.length; index++) {
                if (cancelled.get()) throw new InterruptedException("تم إلغاء العملية");
                current = new File(dir, "VideoX_Batch_" + (index + 1) + "_" + System.currentTimeMillis() + ".mp4");
                VideoTranscoder.Options o = new VideoTranscoder.Options();
                o.preset = preset;
                o.startMs = 0;
                o.endMs = 0;
                o.keepAudio = true;
                o.outputFormat = "MP4_AVC";
                o.audioBitrate = 128_000;
                Uri src = Uri.parse(inputs[index]);
                long duration = new VideoInfo(this, src).durationMs;
                int from = index * 100 / inputs.length, to = (index + 1) * 100 / inputs.length;
                if (!VideoTranscoder.canDecode(this, src)) {
                    transcodeLegacy(src, current, o, 128, duration, p -> progress(from + (to - from) * p / 100));
                } else {
                    File tmp = encodeWithSourceAudio(src, current, o, 128, 0, duration, from, to);
                    if (tmp != null) tmp.delete();
                }
                results.add(current.getAbsolutePath());
                current = null;
            }
            running = false;
            completedPaths = results;
            completedPath = results.isEmpty() ? null : results.get(results.size() - 1);
            progress(100);
            notifyDone("اكتملت معالجة " + results.size() + " فيديو");
            broadcast("done", 100, null);
        } catch (Throwable e) {
            if (current != null && current.exists()) current.delete();
            finishFailure(e, "فشلت معالجة الدفعة");
        } finally {
            stopSelf();
        }
    }

    private void finishSuccess(String output, String message) {
        running = false;
        completedPath = output;
        progress(100);
        notifyDone(message);
        broadcast("done", 100, null);
    }

    private void finishFailure(Throwable e, String fallbackMessage) {
        running = false;
        String m = e.getMessage() == null ? fallbackMessage : e.getMessage();
        if (e instanceof UnsatisfiedLinkError || e instanceof NoClassDefFoundError)
            m = "هذه الميزة غير مدعومة على معالج هذا الجهاز";
        else if (e instanceof OutOfMemoryError)
            m = "الذاكرة غير كافية لمعالجة هذا الفيديو؛ جرّب دقة أقل";
        broadcast(cancelled.get() ? "cancelled" : "error", currentProgress, m);
        stopForeground(true);
    }

    private void progress(int p) {
        currentProgress = p;
        long now = SystemClock.elapsedRealtime();
        if (p != lastNotifiedProgress && (p >= 100 || now - lastNotifyAt >= NOTIFY_INTERVAL_MS)) {
            lastNotifiedProgress = p;
            lastNotifyAt = now;
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(currentLabel, p, false));
        }
        broadcast("running", p, null);
    }

    private void notifyDone(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text, 100, true));
        stopForeground(false);
    }

    private Notification notification(String title, int p, boolean done) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 7, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_videox)
                .setContentTitle(title)
                .setContentText(done ? "اضغط لعرض الملف" : p + "%")
                .setOnlyAlertOnce(true)
                .setOngoing(!done)
                .setContentIntent(pi);
        if (!done) {
            b.setProgress(100, p, false);
            Intent cancel = new Intent(this, ProcessingService.class).setAction(ACTION_CANCEL);
            PendingIntent cancelPi = PendingIntent.getService(this, 8, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, "إلغاء", cancelPi).build());
        }
        return b.build();
    }

    private void broadcast(String state, int p, String error) {
        Intent b = new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra("state", state).putExtra("progress", p);
        if (error != null) b.putExtra("error", error);
        sendBroadcast(b);
    }

    /**
     * Android 15+ limits "mediaProcessing" foreground services to 6 hours a day. When the limit
     * is hit the system calls this; the service must stop within a few seconds or the app crashes.
     */
    @Override public void onTimeout(int startId, int fgsType) {
        cancelled.set(true);
        stopForeground(true);
        stopSelf();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        cancelled.set(true);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        executor.shutdownNow();
        super.onDestroy();
    }
}
