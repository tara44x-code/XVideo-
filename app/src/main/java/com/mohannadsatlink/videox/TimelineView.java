package com.mohannadsatlink.videox;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Horizontal editing timeline in the style of mobile video editors: the playhead is fixed in the
 * centre and the clips scroll under it. Supports scrolling with fling, pinch-to-zoom, tapping to
 * select a clip, dragging the handles of the selected clip to trim it, and long-press + drag to
 * reorder clips.
 */
final class TimelineView extends View {
    interface Listener {
        /** Playhead moved; fromUser is true while the user scrolls the timeline. */
        void onScrub(long timelineMs, boolean fromUser);
        void onSelectionChanged(int index);
        /** Called once before a trim or reorder gesture changes the clips (to save an undo step). */
        void onEditStarted();
        /** Trim/reorder gesture finished and the clips changed. */
        void onClipsEdited();
        /** While trimming: show this source frame of the clip being trimmed. */
        void onTrimPreview(int index, long sourceMs);
    }

    private static final int MODE_NONE = 0, MODE_SCROLL = 1, MODE_TRIM_LEFT = 2, MODE_TRIM_RIGHT = 3, MODE_REORDER = 4, MODE_PINCH = 5;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF tmp = new RectF();
    private final Path clipPath = new Path();
    private final OverScroller scroller;
    private final ScaleGestureDetector scaleDetector;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int touchSlop;
    private final float density;

    private List<TimelineClip> clips = new ArrayList<>();
    private MediaPreviewCache previews;
    private Listener listener;
    private long currentMs;
    private int selected = -1;
    private float pxPerMs;
    private boolean audioMode;

    private int mode = MODE_NONE;
    private float downX, downY, lastX;
    private boolean moved;
    private int reorderIndex = -1;
    private float reorderFingerX;
    private VelocityTracker velocity;
    private final Runnable longPress = this::startReorder;

    TimelineView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        pxPerMs = 60 * density / 1000f; // 60dp per second
        scroller = new OverScroller(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        text.setColor(Color.WHITE);
        text.setTextSize(11 * density);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                mode = MODE_PINCH;
                handler.removeCallbacks(longPress);
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector d) {
                float min = 4 * density / 1000f, max = 600 * density / 1000f;
                pxPerMs = Math.max(min, Math.min(max, pxPerMs * d.getScaleFactor()));
                invalidate();
                return true;
            }
        });
        setMinimumHeight(dp(150));
    }

    void setListener(Listener l) { listener = l; }
    void setPreviews(MediaPreviewCache cache) { previews = cache; }
    void setAudioMode(boolean value) { audioMode = value; invalidate(); }

    void setClips(List<TimelineClip> value) {
        clips = value;
        if (selected >= clips.size()) selected = -1;
        currentMs = Math.min(currentMs, totalMs());
        invalidate();
    }

    int getSelected() { return selected; }
    void setSelected(int index) {
        selected = index >= 0 && index < clips.size() ? index : -1;
        invalidate();
        if (listener != null) listener.onSelectionChanged(selected);
    }

    long getCurrentMs() { return currentMs; }

    /** Moves the playhead without notifying as a user scrub (used during playback). */
    void setCurrentMs(long ms) {
        if (!scroller.isFinished() || mode != MODE_NONE) return;
        currentMs = Math.max(0, Math.min(totalMs(), ms));
        invalidate();
    }

    void seekTo(long ms) {
        scroller.forceFinished(true);
        currentMs = Math.max(0, Math.min(totalMs(), ms));
        invalidate();
        if (listener != null) listener.onScrub(currentMs, false);
    }

    long totalMs() {
        long total = 0;
        for (TimelineClip c : clips) total += c.timelineLengthMs();
        return total;
    }

    long clipStartMs(int index) {
        long t = 0;
        for (int i = 0; i < index && i < clips.size(); i++) t += clips.get(i).timelineLengthMs();
        return t;
    }

    int clipIndexAt(long timelineMs) {
        long t = 0;
        for (int i = 0; i < clips.size(); i++) {
            long len = clips.get(i).timelineLengthMs();
            if (timelineMs < t + len) return i;
            t += len;
        }
        return clips.isEmpty() ? -1 : clips.size() - 1;
    }

    // ---------------------------------------------------------------- drawing

    private float xOf(long timelineMs) { return getWidth() / 2f + (timelineMs - currentMs) * pxPerMs; }
    private long timeOf(float x) { return currentMs + Math.round((x - getWidth() / 2f) / pxPerMs); }
    private float clipTop() { return dp(30); }
    private float clipBottom() { return getHeight() - dp(18); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawRuler(canvas);
        float top = clipTop(), bottom = clipBottom();
        long t = 0;
        for (int i = 0; i < clips.size(); i++) {
            TimelineClip c = clips.get(i);
            long len = c.timelineLengthMs();
            if (i != reorderIndex) {
                float l = xOf(t) + dp(1), r = xOf(t + len) - dp(1);
                if (r > 0 && l < getWidth()) drawClip(canvas, c, t, l, top, r, bottom, i == selected);
            } else {
                // Placeholder where the dragged clip will land.
                paint.setColor(Color.argb(60, 255, 255, 255));
                tmp.set(xOf(t) + dp(1), top, xOf(t + len) - dp(1), bottom);
                canvas.drawRoundRect(tmp, dp(8), dp(8), paint);
            }
            t += len;
        }
        if (reorderIndex >= 0 && reorderIndex < clips.size()) {
            TimelineClip c = clips.get(reorderIndex);
            float w = Math.min(dp(120), c.timelineLengthMs() * pxPerMs);
            drawClip(canvas, c, timeOf(reorderFingerX - w / 2), reorderFingerX - w / 2, top - dp(8), reorderFingerX + w / 2, bottom - dp(8), true);
        }
        // Fixed playhead in the centre.
        float cx = getWidth() / 2f;
        paint.setColor(Color.WHITE);
        canvas.drawRect(cx - dp(1), dp(20), cx + dp(1), getHeight() - dp(4), paint);
        canvas.drawCircle(cx, dp(20), dp(5), paint);
        if (clips.isEmpty()) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(Color.rgb(151, 170, 205));
            canvas.drawText("اضغط + لإضافة مقطع", cx, (top + bottom) / 2, text);
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawRuler(Canvas canvas) {
        long[] steps = {500, 1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000};
        long step = steps[steps.length - 1];
        for (long s : steps) if (s * pxPerMs >= dp(64)) { step = s; break; }
        long first = Math.max(0, (timeOf(0) / step) * step);
        long last = Math.min(totalMs(), timeOf(getWidth()));
        text.setColor(Color.rgb(140, 156, 186));
        text.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.rgb(70, 86, 116));
        for (long ms = first; ms <= last; ms += step) {
            float x = xOf(ms);
            canvas.drawRect(x - dp(0.5f), dp(14), x + dp(0.5f), dp(20), paint);
            canvas.drawText(formatTime(ms, step < 1000), x, dp(11), text);
            float half = xOf(ms + step / 2);
            if (ms + step / 2 <= last) canvas.drawRect(half - dp(0.5f), dp(17), half + dp(0.5f), dp(20), paint);
        }
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawClip(Canvas canvas, TimelineClip c, long clipStart, float l, float top, float r, float bottom, boolean isSelected) {
        float radius = dp(8);
        boolean video = c.hasVideo && !audioMode;
        float waveTop = video && c.hasAudio ? bottom - dp(18) : top;

        canvas.save();
        clipPath.reset();
        tmp.set(l, top, r, bottom);
        clipPath.addRoundRect(tmp, radius, radius, Path.Direction.CW);
        canvas.clipPath(clipPath);
        paint.setColor(video ? Color.rgb(24, 40, 68) : Color.rgb(18, 70, 78));
        canvas.drawRect(l, top, r, bottom, paint);

        if (video) drawThumbnails(canvas, c, clipStart, l, top, r, waveTop);
        if (c.hasAudio) drawWave(canvas, c, clipStart, l, waveTop, r, bottom, video);
        canvas.restore();

        // Badges
        float bx = Math.max(l, 0) + dp(5);
        if (Math.abs(c.speed - 1f) > 0.01f) bx = badge(canvas, speedLabel(c.speed), bx, top + dp(4));
        if (c.muted) badge(canvas, "🔇", bx, top + dp(4));
        if (isSelected) {
            String len = String.format(Locale.US, "%.1fs", c.timelineLengthMs() / 1000f);
            float w = text.measureText(len) + dp(8);
            badge(canvas, len, Math.min(r, getWidth()) - w - dp(4), top + dp(4));

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.5f));
            paint.setColor(Color.WHITE);
            tmp.set(l, top, r, bottom);
            canvas.drawRoundRect(tmp, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
            drawHandle(canvas, l - dp(12), top, l, bottom, true);
            drawHandle(canvas, r, top, r + dp(12), bottom, false);
        }
    }

    private void drawThumbnails(Canvas canvas, TimelineClip c, long clipStart, float l, float top, float r, float bottom) {
        MediaPreviewCache.Thumbs thumbs = previews == null ? null : previews.thumbs(c.uri);
        float h = bottom - top;
        float aspect = c.width > 0 && c.height > 0 ? c.width / (float) c.height : 16f / 9f;
        float tileW = Math.max(dp(24), h * Math.min(aspect, 1.78f));
        float startX = l + (float) Math.floor(Math.max(0f, -l) / tileW) * tileW;
        for (float x = startX; x < r && x < getWidth(); x += tileW) {
            long offset = timeOf(x + tileW / 2) - clipStart;
            long src = c.sourceAt(offset);
            Bitmap frame = thumbs == null ? null : thumbs.nearest(src);
            tmp.set(x, top, x + tileW, bottom);
            if (frame != null && !frame.isRecycled()) {
                float fr = frame.getWidth() / (float) Math.max(1, frame.getHeight()), tr = tileW / Math.max(1f, h);
                if (fr > tr) {
                    int cw = Math.max(1, Math.round(frame.getHeight() * tr));
                    int off = (frame.getWidth() - cw) / 2;
                    srcRect.set(off, 0, off + cw, frame.getHeight());
                } else {
                    int ch = Math.max(1, Math.round(frame.getWidth() / tr));
                    int off = (frame.getHeight() - ch) / 2;
                    srcRect.set(0, off, frame.getWidth(), off + ch);
                }
                canvas.drawBitmap(frame, srcRect, tmp, paint);
            } else {
                paint.setColor(((int) (x / tileW)) % 2 == 0 ? Color.rgb(31, 52, 86) : Color.rgb(27, 45, 76));
                canvas.drawRect(tmp, paint);
            }
        }
    }

    private void drawWave(Canvas canvas, TimelineClip c, long clipStart, float l, float top, float r, float bottom, boolean underVideo) {
        if (underVideo) {
            paint.setColor(Color.rgb(12, 22, 40));
            canvas.drawRect(l, top, r, bottom, paint);
        }
        MediaPreviewCache.Wave wave = previews == null ? null : previews.wave(c.uri);
        float mid = (top + bottom) / 2f, half = (bottom - top) / 2f - dp(2);
        paint.setColor(c.muted ? Color.rgb(90, 100, 120) : underVideo ? Color.rgb(96, 175, 255) : Color.rgb(80, 220, 190));
        float bar = dp(3);
        for (float x = Math.max(l, 0); x < r && x < getWidth(); x += bar) {
            float level;
            if (wave == null) level = 0.08f;
            else {
                long a = c.sourceAt(timeOf(x) - clipStart), b = c.sourceAt(timeOf(x + bar) - clipStart);
                level = Math.max(0.04f, wave.peakAt(a, b));
            }
            float hh = half * level;
            canvas.drawRect(x, mid - hh, x + bar - dp(1), mid + hh, paint);
        }
    }

    private float badge(Canvas canvas, String label, float x, float y) {
        float w = text.measureText(label) + dp(8);
        paint.setColor(Color.argb(170, 0, 0, 0));
        tmp.set(x, y, x + w, y + dp(16));
        canvas.drawRoundRect(tmp, dp(4), dp(4), paint);
        text.setColor(Color.WHITE);
        canvas.drawText(label, x + dp(4), y + dp(12), text);
        return x + w + dp(4);
    }

    private void drawHandle(Canvas canvas, float l, float top, float r, float bottom, boolean left) {
        paint.setColor(Color.WHITE);
        tmp.set(l, top, r, bottom);
        float rad = dp(6);
        canvas.drawRoundRect(tmp, rad, rad, paint);
        // square off the side touching the clip
        if (left) canvas.drawRect(r - rad, top, r, bottom, paint); else canvas.drawRect(l, top, l + rad, bottom, paint);
        paint.setColor(Color.rgb(40, 50, 70));
        float cx = (l + r) / 2f, cy = (top + bottom) / 2f;
        tmp.set(cx - dp(1.5f), cy - dp(10), cx + dp(1.5f), cy + dp(10));
        canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), paint);
    }

    // ---------------------------------------------------------------- touch

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(e);
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                scroller.forceFinished(true);
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = lastX = x; downY = e.getY(); moved = false; mode = MODE_NONE;
                if (selected >= 0 && selected < clips.size() && e.getY() >= clipTop() - dp(10)) {
                    float l = xOf(clipStartMs(selected)), r = xOf(clipStartMs(selected) + clips.get(selected).timelineLengthMs());
                    if (Math.abs(x - (l - dp(6))) <= dp(22)) mode = MODE_TRIM_LEFT;
                    else if (Math.abs(x - (r + dp(6))) <= dp(22)) mode = MODE_TRIM_RIGHT;
                    if (mode != MODE_NONE && listener != null) listener.onEditStarted();
                }
                if (mode == MODE_NONE) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                handler.removeCallbacks(longPress);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (mode == MODE_PINCH) return true;
                float dx = x - lastX;
                if (!moved && (Math.abs(x - downX) > touchSlop || Math.abs(e.getY() - downY) > touchSlop)) {
                    moved = true;
                    handler.removeCallbacks(longPress);
                    if (mode == MODE_NONE) mode = MODE_SCROLL;
                }
                if (!moved) return true;
                lastX = x;
                if (mode == MODE_SCROLL) {
                    currentMs = Math.max(0, Math.min(totalMs(), currentMs - Math.round(dx / pxPerMs)));
                    invalidate();
                    if (listener != null) listener.onScrub(currentMs, true);
                } else if (mode == MODE_TRIM_LEFT || mode == MODE_TRIM_RIGHT) {
                    trimBy(dx);
                } else if (mode == MODE_REORDER) {
                    reorderFingerX = x;
                    moveReorderTarget();
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPress);
                getParent().requestDisallowInterceptTouchEvent(false);
                if (mode == MODE_SCROLL && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    velocity.computeCurrentVelocity(1000);
                    float vx = velocity.getXVelocity();
                    if (Math.abs(vx) > ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity()) {
                        int pos = Math.round(currentMs * pxPerMs);
                        scroller.fling(pos, 0, Math.round(-vx), 0, 0, Math.round(totalMs() * pxPerMs), 0, 0);
                        postInvalidateOnAnimation();
                    }
                } else if (mode == MODE_TRIM_LEFT || mode == MODE_TRIM_RIGHT) {
                    if (listener != null) listener.onClipsEdited();
                } else if (mode == MODE_REORDER) {
                    reorderIndex = -1;
                    invalidate();
                    if (listener != null) listener.onClipsEdited();
                } else if (!moved && mode == MODE_NONE && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    long t = timeOf(x);
                    boolean onClips = e.getY() >= clipTop() && e.getY() <= clipBottom() && t >= 0 && t < totalMs();
                    int hit = onClips ? clipIndexAt(t) : -1;
                    setSelected(hit == selected ? -1 : hit);
                    performClick();
                }
                mode = MODE_NONE;
                if (velocity != null) { velocity.recycle(); velocity = null; }
                return true;
            }
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            currentMs = Math.max(0, Math.min(totalMs(), Math.round(scroller.getCurrX() / pxPerMs)));
            if (listener != null) listener.onScrub(currentMs, true);
            postInvalidateOnAnimation();
        }
    }

    private void trimBy(float dx) {
        if (selected < 0 || selected >= clips.size()) return;
        TimelineClip c = clips.get(selected);
        long deltaSource = Math.round(dx / pxPerMs * c.speed);
        if (mode == MODE_TRIM_LEFT) {
            long before = c.timelineLengthMs();
            c.inMs = Math.max(0, Math.min(c.outMs - TimelineClip.MIN_LENGTH_MS, c.inMs + deltaSource));
            // Keep the clip's right edge under the finger's frame of reference: shift the view.
            currentMs = Math.max(0, currentMs - (before - c.timelineLengthMs()));
            if (listener != null) listener.onTrimPreview(selected, c.inMs);
        } else {
            c.outMs = Math.min(c.sourceDurationMs, Math.max(c.inMs + TimelineClip.MIN_LENGTH_MS, c.outMs + deltaSource));
            if (listener != null) listener.onTrimPreview(selected, c.outMs);
        }
        currentMs = Math.min(currentMs, totalMs());
        invalidate();
    }

    private void startReorder() {
        if (moved || clips.size() < 2) return;
        long t = timeOf(downX);
        if (downY < clipTop() || downY > clipBottom() || t < 0 || t >= totalMs()) return;
        if (listener != null) listener.onEditStarted();
        mode = MODE_REORDER;
        reorderIndex = clipIndexAt(t);
        reorderFingerX = downX;
        selected = reorderIndex;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private void moveReorderTarget() {
        long t = timeOf(reorderFingerX);
        int target = clips.size() - 1;
        long start = 0;
        for (int i = 0; i < clips.size(); i++) {
            long len = clips.get(i).timelineLengthMs();
            if (t < start + len / 2) { target = i; break; }
            start += len;
        }
        if (target != reorderIndex && target >= 0 && target < clips.size()) {
            TimelineClip c = clips.remove(reorderIndex);
            clips.add(target, c);
            reorderIndex = target;
            selected = target;
        }
        // Auto-scroll near the edges.
        if (reorderFingerX < dp(40)) currentMs = Math.max(0, currentMs - Math.round(dp(8) / pxPerMs));
        else if (reorderFingerX > getWidth() - dp(40)) currentMs = Math.min(totalMs(), currentMs + Math.round(dp(8) / pxPerMs));
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    static String formatTime(long ms, boolean withTenths) {
        long s = Math.max(0, ms) / 1000;
        if (withTenths) return String.format(Locale.US, "%d:%02d.%d", s / 60, s % 60, (Math.max(0, ms) % 1000) / 100);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    static String speedLabel(float speed) {
        String s = String.format(Locale.US, "%.2f", speed);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return "x" + s;
    }

    private int dp(float v) { return Math.round(v * density); }
}
