package com.mohannadsatlink.videox;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Compression profile picker for the compress screen: ready-made profiles (resolution % + quality),
 * "exact size in MB", and a custom mode with resolution/bitrate sliders. Every option shows the
 * estimated output size, computed with the same formula the encoder uses.
 */
final class CompressProfiles {
    interface Listener { void onChanged(boolean targetSizeMode); }

    static final int TARGET = 6, CUSTOM = 7;
    private static final int CARD = Color.rgb(16, 25, 44), ACTIVE = Color.rgb(30, 52, 92), BLUE = Color.rgb(35, 142, 255);

    private static final String[] TITLES = {"حجم صغير جداً", "حجم صغير HD", "متوسط", "متوسط HD", "كبير", "كبير HD", "حجم محدد بالميغابايت", "مخصص"};
    private static final int[] SCALE = {33, 50, 75, 75, 100, 100, 0, 0};
    private static final VideoTranscoder.Preset[] QUALITY = {
            VideoTranscoder.Preset.LOW, VideoTranscoder.Preset.HIGH, VideoTranscoder.Preset.MEDIUM, VideoTranscoder.Preset.HIGH,
            VideoTranscoder.Preset.MEDIUM, VideoTranscoder.Preset.HIGH, VideoTranscoder.Preset.MEDIUM, VideoTranscoder.Preset.MEDIUM};
    private static final String[] QUALITY_NAME = {"مقبولة", "عالية", "جيدة", "عالية", "جيدة", "عالية", "", ""};
    private static final String[] SPEED_NAME = {"الأسرع", "سريع", "متوسط", "متوسط", "أبطأ", "أبطأ", "", ""};

    private final Context context;
    private final VideoInfo info;
    private final Listener listener;
    private final LinearLayout root;
    private final List<LinearLayout> rows = new ArrayList<>();
    private final List<TextView> estimates = new ArrayList<>();
    private LinearLayout customBox;
    private TextView customScaleLabel, customRateLabel;

    private int selected = 2;
    private int customScale = 75, customRate = 60;
    private boolean efficientCodec;
    private int audioKbps = 128;
    private long durationMs;

    CompressProfiles(Context context, VideoInfo info, int initial, Listener listener) {
        this.context = context;
        this.info = info;
        this.listener = listener;
        this.selected = initial >= 0 && initial < TITLES.length ? initial : 2;
        this.durationMs = info.durationMs;
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < TITLES.length; i++) root.addView(buildRow(i), rowParams());
        customBox = buildCustom();
        root.addView(customBox);
        refresh();
    }

    View view() { return root; }
    int selected() { return selected; }
    boolean isTargetMode() { return selected == TARGET; }
    VideoTranscoder.Preset quality() { return QUALITY[selected]; }

    int scalePercent() {
        int pct = selected == CUSTOM ? customScale : SCALE[selected];
        // Never go below 240p on the short edge.
        int shortEdge = Math.max(1, Math.min(info.width, info.height));
        if (shortEdge > 0 && info.width > 0) pct = Math.max(pct, Math.min(100, (int) Math.ceil(240f * 100 / shortEdge)));
        return Math.max(10, Math.min(100, pct));
    }

    void setEfficientCodec(boolean value) { efficientCodec = value; refresh(); }
    void setAudioKbps(int value) { audioKbps = value; refresh(); }
    void setDurationMs(long value) { durationMs = Math.max(1, value); refresh(); }

    /** Video bitrate in bps for the current choice (same formula as {@link VideoTranscoder}). */
    int videoBitrate() { return bitrateFor(selected); }

    private int sourceVideoBitrate() {
        long total = info.bitrateKbps > 0 ? info.bitrateKbps * 1000L
                : info.size > 0 && info.durationMs > 0 ? info.size * 8000L / info.durationMs : 0;
        return (int) Math.max(0, total - 128_000);
    }

    private int bitrateFor(int index) {
        int source = sourceVideoBitrate();
        if (index == CUSTOM) {
            int base = source > 0 ? source : 2_000_000;
            return Math.max(150_000, base * customRate / 100);
        }
        float pct = (index == CUSTOM ? customScale : SCALE[index]) / 100f;
        double w = Math.max(16, info.width * pct), h = Math.max(16, info.height * pct);
        double fps = info.frameRate > 1 ? Math.min(60, info.frameRate) : 30;
        VideoTranscoder.Preset q = QUALITY[index];
        double bpp = q == VideoTranscoder.Preset.LOW ? 0.05 : q == VideoTranscoder.Preset.MEDIUM ? 0.075 : 0.11;
        if (efficientCodec) bpp *= 0.65;
        long rate = Math.round(w * h * fps * bpp);
        if (source > 0) {
            double cap = q == VideoTranscoder.Preset.LOW ? 0.45 : q == VideoTranscoder.Preset.MEDIUM ? 0.65 : 0.85;
            // Smaller pictures need proportionally fewer bits, so scale the cap with the area too.
            double areaFactor = Math.max(0.25, pct * pct);
            rate = Math.min(rate, Math.round(source * cap * Math.max(areaFactor, q == VideoTranscoder.Preset.HIGH ? 0.5 : 0.35)));
        }
        return (int) Math.max(150_000, rate);
    }

    private long estimateBytes(int index) {
        return (bitrateFor(index) + audioKbps * 1000L) * durationMs / 8000L;
    }

    private LinearLayout buildRow(int index) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setClickable(true);
        row.setOnClickListener(v -> { selected = index; refresh(); if (listener != null) listener.onChanged(isTargetMode()); });

        TextView radio = text("○", 20, Color.WHITE, false);
        row.addView(radio, new LinearLayout.LayoutParams(dp(30), -2));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(TITLES[index], 16, Color.WHITE, true);
        texts.addView(title);
        String sub;
        if (index == TARGET) sub = "التطبيق يحسب الدقة والجودة حتى يوصل للحجم اللي تكتبه";
        else if (index == CUSTOM) sub = "اختر نسبة الدقة والـ Bitrate بنفسك";
        else sub = "الدقة " + (SCALE[index] == 100 ? "الأصلية" : SCALE[index] + "%") + " • جودة " + QUALITY_NAME[index] + " • " + SPEED_NAME[index];
        texts.addView(text(sub, 12, Color.rgb(160, 178, 210), false));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));

        TextView est = text("", 14, Color.rgb(94, 205, 137), true);
        est.setGravity(Gravity.END);
        row.addView(est, new LinearLayout.LayoutParams(-2, -2));
        rows.add(row);
        estimates.add(est);
        return row;
    }

    private LinearLayout buildCustom() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(4), dp(18), dp(10));
        customScaleLabel = text("", 14, Color.WHITE, true);
        box.addView(customScaleLabel);
        box.addView(slider(10, 100, customScale, v -> { customScale = v; refresh(); }));
        customRateLabel = text("", 14, Color.WHITE, true);
        box.addView(customRateLabel);
        box.addView(slider(10, 100, customRate, v -> { customRate = v; refresh(); }));
        return box;
    }

    private interface IntChange { void on(int value); }

    private SeekBar slider(int min, int max, int value, IntChange change) {
        SeekBar bar = new SeekBar(context);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(BLUE));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { if (fromUser) change.on(p + min); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
        bar.setLayoutParams(lp);
        return bar;
    }

    private void refresh() {
        for (int i = 0; i < rows.size(); i++) {
            boolean on = i == selected;
            rows.get(i).setBackground(round(on ? ACTIVE : CARD, on ? BLUE : Color.rgb(32, 46, 72)));
            ((TextView) rows.get(i).getChildAt(0)).setText(on ? "◉" : "○");
            ((TextView) rows.get(i).getChildAt(0)).setTextColor(on ? BLUE : Color.rgb(150, 165, 195));
            estimates.get(i).setText(i == TARGET ? "" : "≈ " + VideoInfo.bytes(estimateBytes(i)));
        }
        customBox.setVisibility(selected == CUSTOM ? View.VISIBLE : View.GONE);
        if (customScaleLabel != null) {
            int w = even(Math.round(info.width * customScale / 100f)), h = even(Math.round(info.height * customScale / 100f));
            customScaleLabel.setText(String.format(Locale.US, "الدقة: %d%%  (%d × %d)", customScale, w, h));
            customRateLabel.setText(String.format(Locale.US, "Bitrate: %d%%  (%d kbps)", customRate, bitrateFor(CUSTOM) / 1000));
        }
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(6);
        return p;
    }

    private TextView text(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(context);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private GradientDrawable round(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private static int even(int v) { return Math.max(2, v & ~1); }
    private int dp(float v) { return Math.round(v * context.getResources().getDisplayMetrics().density); }
}
