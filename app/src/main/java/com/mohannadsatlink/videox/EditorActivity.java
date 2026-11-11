package com.mohannadsatlink.videox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Timeline editor for video (and audio, with {@link #EXTRA_AUDIO}): split at the playhead,
 * delete, duplicate, trim with handles, add more clips, reorder by long-press, change speed,
 * mute, undo/redo, then export everything as one file through {@link ProcessingService}.
 */
public final class EditorActivity extends Activity implements TimelineView.Listener, MediaPreviewCache.Listener {
    static final String EXTRA_AUDIO = "audio";
    private static final int PICK = 301;
    private static final int BG = Color.rgb(7, 11, 22), PANEL = Color.rgb(16, 25, 44);
    private static final int BLUE = Color.rgb(35, 142, 255), VIOLET = Color.rgb(140, 65, 255);
    private static final int MAX_UNDO = 50;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<TimelineClip> clips = new ArrayList<>();
    private final ArrayList<ArrayList<TimelineClip>> undo = new ArrayList<>(), redo = new ArrayList<>();

    private boolean audioMode;
    private boolean edited;
    private TimelineView timeline;
    private MediaPreviewCache previews;
    private TextureView videoView;
    private TextView audioLabel, timeLabel, emptyHint;
    private Button playButton, undoButton, redoButton, muteButton;
    private FrameLayout root, overlay;
    private ProgressBar overlayProgress;
    private TextView overlayPercent;

    // Preview playback
    private MediaPlayer player;
    private Surface surface;
    private Uri loadedUri;
    private boolean prepared, playing;
    private int playingClip = -1;
    private long pendingSeekSourceMs = -1;
    private boolean pendingStart;
    private long scrubTarget = -1;
    private boolean receiverRegistered;

    private final Runnable ticker = new Runnable() {
        @Override public void run() { tick(); if (playing) ui.postDelayed(this, 30); }
    };
    private final Runnable scrubSeek = () -> { if (scrubTarget >= 0) showFrameAt(scrubTarget); };

    private final BroadcastReceiver status = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String state = i.getStringExtra("state");
            if ("running".equals(state)) updateOverlay(i.getIntExtra("progress", 0));
            else if ("done".equals(state)) openResult();
            else if ("error".equals(state) || "cancelled".equals(state)) {
                hideOverlay();
                toast("error".equals(state) ? i.getStringExtra("error") : "تم إلغاء التصدير");
            }
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        audioMode = getIntent().getBooleanExtra(EXTRA_AUDIO, false);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        previews = new MediaPreviewCache(this);
        previews.setListener(this);
        buildUi();
        if (ProcessingService.running) showOverlay();
        else pickMedia();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(ProcessingService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(status, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(status, f);
        receiverRegistered = true;
        if (overlay != null && overlay.getVisibility() == View.VISIBLE && !ProcessingService.running) {
            if (ProcessingService.completedPath != null) openResult(); else hideOverlay();
        }
    }

    @Override protected void onStop() {
        if (receiverRegistered) { unregisterReceiver(status); receiverRegistered = false; }
        pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        if (surface != null) { surface.release(); surface = null; }
        previews.release();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE) {
            // Export keeps running in the background; hand the progress screen to MainActivity.
            startActivity(new Intent(this, MainActivity.class).putExtra("showRunning", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
            return;
        }
        confirmExit();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // Top bar: close, title, export
        LinearLayout top = row();
        top.setPadding(dp(10), dp(8), dp(10), dp(8));
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button close = flat("✕", 22);
        close.setOnClickListener(v -> confirmExit());
        top.addView(close, new LinearLayout.LayoutParams(dp(48), dp(44)));
        TextView title = label(audioMode ? "محرر الصوت" : "محرر الفيديو", 18, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button export = pill("تصدير", VIOLET);
        export.setOnClickListener(v -> showExportDialog(false));
        top.addView(export, new LinearLayout.LayoutParams(-2, dp(42)));
        page.addView(top);

        // Preview
        FrameLayout preview = new FrameLayout(this);
        preview.setBackgroundColor(Color.BLACK);
        if (audioMode) {
            audioLabel = label("♫", 64, Color.rgb(80, 220, 190), true);
            audioLabel.setGravity(Gravity.CENTER);
            preview.addView(audioLabel, new FrameLayout.LayoutParams(-1, -1));
        } else {
            videoView = new TextureView(this);
            videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    surface = new Surface(st);
                    if (player != null) { try { player.setSurface(surface); } catch (Exception ignored) {} }
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    if (player != null && prepared) fitVideo(player.getVideoWidth(), player.getVideoHeight());
                }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    if (player != null) { try { player.setSurface(null); } catch (Exception ignored) {} }
                    if (surface != null) { surface.release(); surface = null; }
                    return true;
                }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            });
            preview.addView(videoView, new FrameLayout.LayoutParams(-1, -1));
            videoView.setOnClickListener(v -> togglePlay());
        }
        emptyHint = label("أضف مقطعاً للبدء", 15, Color.rgb(151, 170, 205), false);
        emptyHint.setGravity(Gravity.CENTER);
        preview.addView(emptyHint, new FrameLayout.LayoutParams(-1, -1));
        page.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));

        // Transport row: undo / redo, time, play
        LinearLayout transport = row();
        transport.setGravity(Gravity.CENTER_VERTICAL);
        transport.setPadding(dp(8), dp(6), dp(8), dp(2));
        undoButton = flat("↶", 24);
        undoButton.setOnClickListener(v -> undo());
        redoButton = flat("↷", 24);
        redoButton.setOnClickListener(v -> redo());
        transport.addView(undoButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        transport.addView(redoButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        timeLabel = label("00:00 / 00:00", 15, Color.WHITE, true);
        timeLabel.setGravity(Gravity.CENTER);
        transport.addView(timeLabel, new LinearLayout.LayoutParams(0, -2, 1));
        playButton = flat("▶", 24);
        playButton.setOnClickListener(v -> togglePlay());
        transport.addView(playButton, new LinearLayout.LayoutParams(dp(56), dp(44)));
        page.addView(transport);

        // Timeline with a floating "+" button
        FrameLayout timelineFrame = new FrameLayout(this);
        timeline = new TimelineView(this);
        timeline.setPreviews(previews);
        timeline.setAudioMode(audioMode);
        timeline.setListener(this);
        timeline.setClips(clips);
        timelineFrame.addView(timeline, new FrameLayout.LayoutParams(-1, -1));
        Button add = pill("+", VIOLET);
        add.setTextSize(24);
        add.setOnClickListener(v -> pickMedia());
        FrameLayout.LayoutParams addParams = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        addParams.rightMargin = dp(8);
        addParams.topMargin = dp(6);
        timelineFrame.addView(add, addParams);
        page.addView(timelineFrame, new LinearLayout.LayoutParams(-1, dp(160)));

        // Tool bar
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(PANEL);
        LinearLayout tools = row();
        tools.setPadding(dp(6), dp(6), dp(6), dp(10));
        tools.addView(tool("＋", "إضافة", v -> pickMedia()));
        tools.addView(tool("✂", "تقسيم", v -> split()));
        tools.addView(tool("🗑", "حذف", v -> deleteClip()));
        tools.addView(tool("⧉", "تكرار", v -> duplicate()));
        tools.addView(tool("⏱", "السرعة", v -> chooseSpeed()));
        View mute = tool("🔊", "كتم", v -> toggleMute());
        muteButton = (Button) ((LinearLayout) mute).getChildAt(0);
        tools.addView(mute);
        if (!audioMode) tools.addView(tool("♫", "إلى MP3", v -> showExportDialog(true)));
        scroll.addView(tools);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = label("اسحب الخط الزمني للتنقل • اضغط مقطعاً لتحديده واسحب طرفيه للقص • ضغطة مطوّلة واسحب لتغيير الترتيب • إصبعين للتكبير",
                11, Color.rgb(120, 138, 170), false);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(PANEL);
        hint.setPadding(dp(10), 0, dp(10), dp(8));
        page.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        overlay = buildOverlay();
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        refreshState();
    }

    private View tool(String icon, String name, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        Button b = flat(icon, 20);
        b.setOnClickListener(click);
        box.addView(b, new LinearLayout.LayoutParams(dp(64), dp(40)));
        TextView t = label(name, 12, Color.rgb(200, 212, 235), false);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(click);
        box.addView(t, new LinearLayout.LayoutParams(dp(64), -2));
        return box;
    }

    private FrameLayout buildOverlay() {
        FrameLayout o = new FrameLayout(this);
        o.setBackgroundColor(Color.argb(235, 7, 11, 22));
        o.setClickable(true);
        o.setVisibility(View.GONE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(28), dp(28), dp(28), dp(28));
        box.addView(label("جاري التصدير…", 22, Color.WHITE, true));
        overlayProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        overlayProgress.setMax(100);
        overlayProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(VIOLET));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.topMargin = dp(24);
        box.addView(overlayProgress, pp);
        overlayPercent = label("0%", 30, Color.WHITE, true);
        box.addView(overlayPercent);
        box.addView(label("يمكنك الخروج وسيستمر التصدير في الخلفية", 13, Color.rgb(151, 170, 205), false));
        Button cancel = pill("إلغاء", PANEL);
        cancel.setOnClickListener(v -> startService(new Intent(this, ProcessingService.class).setAction(ProcessingService.ACTION_CANCEL)));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(160), dp(48));
        cp.topMargin = dp(28);
        box.addView(cancel, cp);
        o.addView(box, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        return o;
    }

    private void refreshState() {
        boolean hasClips = !clips.isEmpty();
        emptyHint.setVisibility(hasClips ? View.GONE : View.VISIBLE);
        if (videoView != null) videoView.setVisibility(hasClips ? View.VISIBLE : View.INVISIBLE);
        undoButton.setAlpha(undo.isEmpty() ? .35f : 1f);
        redoButton.setAlpha(redo.isEmpty() ? .35f : 1f);
        int target = targetIndex();
        boolean muted = target >= 0 && clips.get(target).muted;
        muteButton.setText(muted ? "🔇" : "🔊");
        updateTime(timeline.getCurrentMs());
    }

    private void updateTime(long ms) {
        timeLabel.setText(TimelineView.formatTime(ms, false) + " / " + TimelineView.formatTime(timeline.totalMs(), false));
        if (audioLabel != null && !clips.isEmpty()) {
            int i = timeline.clipIndexAt(ms);
            if (i >= 0) audioLabel.setText("♫\n" + clips.get(i).name);
            audioLabel.setTextSize(i >= 0 ? 18 : 64);
        }
    }

    // ------------------------------------------------------------------ media picking

    private void pickMedia() {
        pause();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        if (audioMode) i.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
        else i.setType("video/*");
        try { startActivityForResult(i, PICK); }
        catch (Exception e) { toast("لا يوجد تطبيق لاختيار الملفات"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK) return;
        if (result != RESULT_OK || data == null) {
            if (clips.isEmpty()) finish();
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int n = 0; n < clip.getItemCount(); n++) uris.add(clip.getItemAt(n).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        for (Uri u : uris) {
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
        }
        if (uris.isEmpty()) { if (clips.isEmpty()) finish(); return; }
        toast("جاري تجهيز " + uris.size() + " ملف…");
        worker.execute(() -> {
            ArrayList<TimelineClip> loaded = new ArrayList<>();
            int failed = 0;
            for (Uri u : uris) {
                TimelineClip c = probe(u);
                if (c == null) failed++; else loaded.add(c);
            }
            int failedCount = failed;
            ui.post(() -> {
                if (isFinishing()) return;
                if (failedCount > 0) toast("تعذر قراءة " + failedCount + " ملف");
                if (loaded.isEmpty()) { if (clips.isEmpty()) finish(); return; }
                insertClips(loaded);
            });
        });
    }

    private TimelineClip probe(Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            long duration = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (duration <= 0) return null;
            boolean video = "yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
            boolean audio = "yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            if (audioMode && !audio) return null;
            int w = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rotation = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rotation == 90 || rotation == 270) { int t = w; w = h; h = t; }
            return new TimelineClip(uri, displayName(uri), duration, video, audio, w, h);
        } catch (Throwable e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private void insertClips(List<TimelineClip> added) {
        if (!clips.isEmpty()) pushUndo();
        int at = timeline.getSelected() >= 0 ? timeline.getSelected() + 1 : clips.size();
        clips.addAll(at, added);
        for (TimelineClip c : added) previews.request(c);
        edited = edited || at > 0;
        timeline.setClips(clips);
        timeline.setSelected(-1);
        timeline.seekTo(timeline.clipStartMs(at));
        refreshState();
    }

    // ------------------------------------------------------------------ editing

    private int targetIndex() {
        if (clips.isEmpty()) return -1;
        int s = timeline.getSelected();
        return s >= 0 && s < clips.size() ? s : timeline.clipIndexAt(timeline.getCurrentMs());
    }

    private void split() {
        if (clips.isEmpty()) return;
        pause();
        long t = timeline.getCurrentMs();
        int i = timeline.clipIndexAt(t);
        TimelineClip c = clips.get(i);
        long offset = t - timeline.clipStartMs(i);
        if (offset < TimelineClip.MIN_LENGTH_MS || c.timelineLengthMs() - offset < TimelineClip.MIN_LENGTH_MS) {
            toast("حرّك المؤشر إلى داخل المقطع لتقسيمه");
            return;
        }
        pushUndo();
        long at = c.sourceAt(offset);
        TimelineClip second = c.copy();
        c.outMs = at;
        second.inMs = at;
        clips.add(i + 1, second);
        changed();
        timeline.setSelected(i + 1);
    }

    private void deleteClip() {
        int i = targetIndex();
        if (i < 0) return;
        pause();
        pushUndo();
        long start = timeline.clipStartMs(i);
        clips.remove(i);
        timeline.setSelected(-1);
        changed();
        timeline.seekTo(Math.min(start, timeline.totalMs()));
        if (clips.isEmpty()) releasePlayer();
    }

    private void duplicate() {
        int i = targetIndex();
        if (i < 0) return;
        pause();
        pushUndo();
        clips.add(i + 1, clips.get(i).copy());
        changed();
        timeline.setSelected(i + 1);
    }

    private void chooseSpeed() {
        int i = targetIndex();
        if (i < 0) return;
        pause();
        final float[] values = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        String[] names = {"0.5× بطيء", "0.75×", "1× الأصلية", "1.25×", "1.5×", "2× سريع"};
        new AlertDialog.Builder(this).setTitle("سرعة المقطع " + (i + 1)).setItems(names, (d, which) -> {
            pushUndo();
            clips.get(i).speed = values[which];
            changed();
        }).show();
    }

    private void toggleMute() {
        int i = targetIndex();
        if (i < 0) return;
        if (!clips.get(i).hasAudio) { toast("هذا المقطع بدون صوت"); return; }
        pushUndo();
        clips.get(i).muted = !clips.get(i).muted;
        changed();
        toast(clips.get(i).muted ? "تم كتم صوت المقطع" : "تم تشغيل صوت المقطع");
    }

    private void changed() {
        edited = true;
        timeline.setClips(clips);
        refreshState();
    }

    private ArrayList<TimelineClip> snapshot() {
        ArrayList<TimelineClip> copy = new ArrayList<>();
        for (TimelineClip c : clips) copy.add(c.copy());
        return copy;
    }

    private void pushUndo() {
        undo.add(snapshot());
        if (undo.size() > MAX_UNDO) undo.remove(0);
        redo.clear();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        pause();
        redo.add(snapshot());
        restore(undo.remove(undo.size() - 1));
    }

    private void redo() {
        if (redo.isEmpty()) return;
        pause();
        undo.add(snapshot());
        restore(redo.remove(redo.size() - 1));
    }

    private void restore(ArrayList<TimelineClip> state) {
        clips.clear();
        clips.addAll(state);
        timeline.setSelected(-1);
        changed();
        timeline.seekTo(Math.min(timeline.getCurrentMs(), timeline.totalMs()));
    }

    private void confirmExit() {
        if (!edited || clips.isEmpty()) { finish(); return; }
        new AlertDialog.Builder(this).setTitle("الخروج من المحرر؟")
                .setMessage("ستضيع التعديلات غير المصدّرة.")
                .setPositiveButton("خروج", (d, w) -> finish())
                .setNegativeButton("بقاء", null).show();
    }

    // ------------------------------------------------------------------ TimelineView.Listener

    @Override public void onScrub(long ms, boolean fromUser) {
        updateTime(ms);
        if (fromUser) {
            if (playing) pause();
            scrubTarget = ms;
            ui.removeCallbacks(scrubSeek);
            ui.postDelayed(scrubSeek, 50);
        } else {
            showFrameAt(ms);
        }
    }

    @Override public void onSelectionChanged(int index) { refreshState(); }
    @Override public void onEditStarted() { pause(); pushUndo(); }
    @Override public void onClipsEdited() { changed(); }

    @Override public void onTrimPreview(int index, long sourceMs) {
        if (index < 0 || index >= clips.size()) return;
        loadAndSeek(clips.get(index).uri, sourceMs, false);
        updateTime(timeline.getCurrentMs());
    }

    @Override public void onPreviewReady() { timeline.invalidate(); }

    // ------------------------------------------------------------------ playback

    private void togglePlay() { if (playing) pause(); else play(); }

    private void play() {
        if (clips.isEmpty()) return;
        long t = timeline.getCurrentMs();
        if (t >= timeline.totalMs() - 50) { t = 0; timeline.seekTo(0); }
        startClipAt(timeline.clipIndexAt(t), t);
    }

    private void startClipAt(int index, long timelineMs) {
        if (index < 0 || index >= clips.size()) { pause(); return; }
        TimelineClip c = clips.get(index);
        playingClip = index;
        playing = true;
        playButton.setText("❚❚");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        long source = c.sourceAt(timelineMs - timeline.clipStartMs(index));
        loadAndSeek(c.uri, source, true);
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private void pause() {
        playing = false;
        pendingStart = false;
        ui.removeCallbacks(ticker);
        if (playButton != null) playButton.setText("▶");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (player != null && prepared) { try { if (player.isPlaying()) player.pause(); } catch (Exception ignored) {} }
    }

    private void tick() {
        if (!playing || player == null || !prepared || playingClip < 0 || playingClip >= clips.size()) return;
        TimelineClip c = clips.get(playingClip);
        long pos;
        try { pos = player.getCurrentPosition(); } catch (Exception e) { return; }
        boolean finished = false;
        try { finished = !player.isPlaying() && !pendingStart; } catch (Exception ignored) {}
        if (pos >= c.outMs - 20 || finished) {
            int next = playingClip + 1;
            if (next >= clips.size()) {
                pause();
                timeline.seekTo(timeline.totalMs());
            } else {
                startClipAt(next, timeline.clipStartMs(next));
            }
            return;
        }
        long t = timeline.clipStartMs(playingClip) + c.timelineOffsetOf(pos);
        timeline.setCurrentMs(t);
        updateTime(t);
    }

    /** Show the frame at a timeline position (paused). */
    private void showFrameAt(long timelineMs) {
        if (clips.isEmpty()) return;
        int i = timeline.clipIndexAt(timelineMs);
        TimelineClip c = clips.get(i);
        loadAndSeek(c.uri, c.sourceAt(timelineMs - timeline.clipStartMs(i)), false);
    }

    private void loadAndSeek(Uri uri, long sourceMs, boolean start) {
        pendingStart = start;
        if (player != null && prepared && uri.equals(loadedUri)) {
            seekAndMaybeStart(sourceMs, start);
            return;
        }
        pendingSeekSourceMs = sourceMs;
        if (player != null && !prepared && uri.equals(loadedUri)) return; // still preparing; seek applied when ready
        releasePlayer();
        try {
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            loadedUri = uri;
            prepared = false;
            mp.setDataSource(this, uri);
            if (surface != null && !audioMode) mp.setSurface(surface);
            mp.setOnVideoSizeChangedListener((p, w, h) -> fitVideo(w, h));
            mp.setOnPreparedListener(p -> {
                if (player != p) return;
                prepared = true;
                fitVideo(p.getVideoWidth(), p.getVideoHeight());
                seekAndMaybeStart(pendingSeekSourceMs, pendingStart);
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (player == p) { prepared = false; pause(); toast("تعذر عرض المعاينة لهذا المقطع؛ التصدير يبقى ممكناً"); }
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
        }
    }

    private void seekAndMaybeStart(long sourceMs, boolean start) {
        if (player == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) player.seekTo(Math.max(0, sourceMs), MediaPlayer.SEEK_CLOSEST);
            else player.seekTo((int) Math.max(0, sourceMs));
            if (start && playing && playingClip >= 0 && playingClip < clips.size()) {
                TimelineClip c = clips.get(playingClip);
                float volume = c.muted ? 0f : 1f;
                player.setVolume(volume, volume);
                try { player.setPlaybackParams(new PlaybackParams().setSpeed(c.speed)); } catch (Exception ignored) {}
                player.start();
            }
        } catch (Exception ignored) {
        } finally {
            pendingStart = false;
        }
    }

    private void fitVideo(int videoW, int videoH) {
        if (videoView == null || videoW <= 0 || videoH <= 0 || videoView.getWidth() <= 0 || videoView.getHeight() <= 0) return;
        float vw = videoView.getWidth(), vh = videoView.getHeight();
        float scale = Math.min(vw / videoW, vh / videoH);
        Matrix m = new Matrix();
        m.setScale(videoW * scale / vw, videoH * scale / vh, vw / 2f, vh / 2f);
        videoView.setTransform(m);
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.reset(); player.release(); } catch (Exception ignored) {}
        }
        player = null;
        loadedUri = null;
        prepared = false;
    }

    // ------------------------------------------------------------------ export

    private void showExportDialog(boolean audioOnly) {
        if (clips.isEmpty()) { toast("أضف مقطعاً أولاً"); return; }
        pause();
        boolean exportAudio = audioOnly || audioMode;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));

        final String[] formats = exportAudio ? new String[]{"MP3", "M4A", "WAV"} : new String[]{"MP4"};
        final int[] format = {0};
        final String[] ratios = {"تلقائي", "9:16", "16:9", "1:1"};
        final int[] ratio = {0};
        final String[] qualities = exportAudio ? new String[]{"128 kbps", "192 kbps", "256 kbps", "320 kbps"} : new String[]{"جودة عالية", "متوازن", "حجم صغير"};
        final int[] quality = {exportAudio ? 1 : 0};
        TextView estimate = label("", 14, Color.rgb(94, 205, 137), true);
        CheckBox blur = new CheckBox(this);
        Runnable update = () -> estimate.setText("المدة " + TimelineView.formatTime(timeline.totalMs(), false)
                + "  •  الحجم المتوقع ≈ " + VideoInfo.bytes(estimateBytes(exportAudio, formats[format[0]], quality[0], ratio[0])));

        if (exportAudio) {
            box.addView(dialogLabel("الصيغة"));
            box.addView(chips(formats, format, update));
        } else {
            box.addView(dialogLabel("أبعاد الفيديو"));
            box.addView(chips(ratios, ratio, update));
            blur.setText("ملء الفراغ بخلفية مضببة بدل الأسود");
            blur.setChecked(true);
            box.addView(blur);
        }
        box.addView(dialogLabel(exportAudio ? "جودة الصوت" : "الجودة"));
        box.addView(chips(qualities, quality, update));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
        ep.topMargin = dp(14);
        box.addView(estimate, ep);
        update.run();

        new AlertDialog.Builder(this).setTitle(exportAudio ? "تصدير الصوت" : "تصدير الفيديو").setView(box)
                .setPositiveButton("تصدير", (d, w) -> startExport(exportAudio, formats[format[0]], quality[0], ratio[0], blur.isChecked()))
                .setNegativeButton("إلغاء", null).show();
    }

    private LinearLayout chips(String[] names, int[] selected, Runnable onChange) {
        LinearLayout r = row();
        Button[] buttons = new Button[names.length];
        for (int i = 0; i < names.length; i++) {
            int index = i;
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(names[i]);
            b.setTextSize(13);
            b.setTextColor(Color.WHITE);
            b.setOnClickListener(v -> {
                selected[0] = index;
                for (int j = 0; j < buttons.length; j++) styleChip(buttons[j], j == index);
                onChange.run();
            });
            buttons[i] = b;
            styleChip(b, i == selected[0]);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (i > 0) p.setMarginStart(dp(6));
            r.addView(b, p);
        }
        return r;
    }

    private void styleChip(Button b, boolean active) {
        b.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        b.setBackground(round(active ? Color.rgb(44, 91, 175) : PANEL, 12, active ? BLUE : Color.rgb(42, 58, 84)));
    }

    private TextView dialogLabel(String t) {
        TextView v = label(t, 14, Color.rgb(170, 188, 218), true);
        v.setPadding(0, dp(14), 0, dp(6));
        return v;
    }

    /** Output canvas: {width, height}. */
    private int[] canvasSize(int ratio) {
        int w = 0, h = 0;
        for (TimelineClip c : clips) if (c.hasVideo && c.width > 0 && c.height > 0) { w = c.width; h = c.height; break; }
        if (w == 0) { w = 1280; h = 720; }
        int shortEdge = Math.min(1080, Math.min(w, h));
        if (ratio == 1) return new int[]{even(shortEdge), even(shortEdge * 16 / 9)};
        if (ratio == 2) return new int[]{even(shortEdge * 16 / 9), even(shortEdge)};
        if (ratio == 3) return new int[]{even(shortEdge), even(shortEdge)};
        float scale = Math.min(1f, Math.min(1920f / Math.max(w, h), 1080f / Math.min(w, h)));
        return new int[]{even(Math.round(w * scale)), even(Math.round(h * scale))};
    }

    private int videoBitrate(int quality, int[] size) {
        double bpp = quality == 0 ? 0.12 : quality == 1 ? 0.08 : 0.05;
        return (int) Math.max(800_000, Math.min(20_000_000, size[0] * (double) size[1] * 30 * bpp));
    }

    private int audioKbps(int quality) { return quality == 0 ? 128 : quality == 1 ? 192 : quality == 2 ? 256 : 320; }

    private long estimateBytes(boolean audio, String format, int quality, int ratio) {
        long ms = timeline.totalMs();
        if (audio) {
            long rate = "WAV".equals(format) ? 1_536_000L : audioKbps(quality) * 1000L;
            return rate * ms / 8000;
        }
        return (videoBitrate(quality, canvasSize(ratio)) + 192_000L) * ms / 8000;
    }

    private void startExport(boolean audio, String format, int quality, int ratio, boolean blur) {
        if (ProcessingService.running) { toast("هناك عملية أخرى قيد التنفيذ"); return; }
        File dir = getExternalFilesDir("Movies");
        if (dir == null) dir = new File(getFilesDir(), "Movies");
        dir.mkdirs();
        String ext = audio ? ("WAV".equals(format) ? ".wav" : "M4A".equals(format) ? ".m4a" : ".mp3") : ".mp4";
        File out = new File(dir, (audio ? "VideoX_Audio_" : "VideoX_Project_") + System.currentTimeMillis() + ext);
        int n = clips.size();
        String[] uris = new String[n];
        long[] ins = new long[n], outs = new long[n];
        float[] speeds = new float[n];
        boolean[] muted = new boolean[n], hasVideo = new boolean[n], hasAudio = new boolean[n];
        for (int i = 0; i < n; i++) {
            TimelineClip c = clips.get(i);
            uris[i] = c.uri.toString();
            ins[i] = c.inMs;
            outs[i] = c.outMs;
            speeds[i] = c.speed;
            muted[i] = c.muted;
            hasVideo[i] = c.hasVideo;
            hasAudio[i] = c.hasAudio;
        }
        int[] size = canvasSize(ratio);
        Intent job = new Intent(this, ProcessingService.class).setAction(ProcessingService.ACTION_START)
                .putExtra("jobKind", "timeline").putExtra("output", out.getAbsolutePath()).putExtra("original", 0L)
                .putExtra("label", audio ? "جاري تصدير الصوت…" : "جاري تصدير الفيديو…")
                .putExtra("uris", uris).putExtra("ins", ins).putExtra("outs", outs).putExtra("speeds", speeds)
                .putExtra("muted", muted).putExtra("hasVideo", hasVideo).putExtra("hasAudio", hasAudio)
                .putExtra("width", size[0]).putExtra("height", size[1]).putExtra("videoBitrate", videoBitrate(quality, size))
                .putExtra("blur", blur).putExtra("audioFormat", audio ? format : "").putExtra("audioBitrate", audioKbps(quality));
        ProcessingService.completedPath = null;
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(job); else startService(job);
        showOverlay();
    }

    private void showOverlay() {
        overlay.setVisibility(View.VISIBLE);
        updateOverlay(ProcessingService.currentProgress);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void updateOverlay(int p) {
        if (overlay.getVisibility() != View.VISIBLE) showOverlay();
        overlayProgress.setProgress(p);
        overlayPercent.setText(p + "%");
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void openResult() {
        String path = ProcessingService.completedPath;
        ProcessingService.completedPath = null;
        hideOverlay();
        if (path == null) return;
        edited = false;
        startActivity(new Intent(this, MainActivity.class).putExtra("resultPath", path)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    // ------------------------------------------------------------------ helpers

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i); }
        } catch (Exception ignored) {}
        String v = uri.getLastPathSegment();
        return v == null ? "مقطع" : v;
    }

    private static long parse(String v) { try { return v == null ? 0 : Long.parseLong(v.trim()); } catch (Exception e) { return 0; } }
    private static int even(int v) { return Math.max(2, v & ~1); }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }

    private TextView label(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button flat(String t, int sp) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(sp);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        b.setBackgroundColor(Color.TRANSPARENT);
        return b;
    }

    private Button pill(String t, int color) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(16);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setBackground(round(color, 12, Color.TRANSPARENT));
        return b;
    }

    private GradientDrawable round(int fill, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String t) { Toast.makeText(this, t == null ? "حدث خطأ" : t, Toast.LENGTH_LONG).show(); }
}
