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
            if (input == null || destination == null) throw new IllegalArgumentException("بيانات العملية غير مكتملة");
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
            int audioBitrate = intent.getIntExtra("audioBitrate", 128);
            int audioSampleRate = intent.getIntExtra("audioSampleRate", 0);
            long duration = o.endMs > o.startMs ? o.endMs - o.startMs : new VideoInfo(this, source).durationMs;
            String requested = o.outputFormat;

            if (requested != null && requested.startsWith("FFMPEG")) {
                // MKV / MOV / AVI: encode with MediaCodec first, then remux/convert with FFmpeg.
                temp = new File(destination.getParentFile(), ".videox_intermediate_" + System.currentTimeMillis() + ".mp4");
                o.outputFormat = "MP4_AVC";
                new VideoTranscoder(this, cancelled).transcode(source, temp, o, p -> progress(p * 75 / 100));
                new FfmpegTranscoder(this, cancelled).convertContainer(Uri.fromFile(temp), destination, requested,
                        duration, audioBitrate, audioSampleRate, p -> progress(75 + p / 4));
            } else if (o.keepAudio) {
                // Encode video, then re-encode audio to the requested quality.
                String suffix = requested != null && requested.startsWith("WEBM") ? ".webm"
                        : requested != null && requested.startsWith("3GP") ? ".3gp" : ".mp4";
                temp = new File(destination.getParentFile(), ".videox_audio_" + System.currentTimeMillis() + suffix);
                new VideoTranscoder(this, cancelled).transcode(source, temp, o, p -> progress(p * 75 / 100));
                new FfmpegTranscoder(this, cancelled).adjustVideoAudio(Uri.fromFile(temp), destination,
                        requested == null ? "MP4_AVC" : requested, duration, audioBitrate, audioSampleRate,
                        p -> progress(75 + p / 4));
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
                int item = index;
                new VideoTranscoder(this, cancelled).transcode(Uri.parse(inputs[index]), current, o,
                        p -> progress((item * 100 + p) / inputs.length));
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
