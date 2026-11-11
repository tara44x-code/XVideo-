package com.mohannadsatlink.videox;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VideoTrimBar extends View {
    interface Listener { void onRangeChanged(float start, float end, boolean fromUser); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float start = 0f;
    private float end = 1f;
    private float playhead = 0f;
    private int activeHandle;
    private Listener listener;
    private List<Bitmap> thumbnails = Collections.emptyList();

    VideoTrimBar(Context context) { super(context); init(); }
    VideoTrimBar(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setMinimumHeight(dp(82));
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    void setListener(Listener value) { listener = value; }
    void setThumbnails(List<Bitmap> value) {
        recycleThumbnails();
        thumbnails = value == null ? Collections.emptyList() : new ArrayList<>(value);
        invalidate();
    }
    float getStart() { return start; }
    float getEnd() { return end; }
    void setPlayhead(float value) { playhead = clamp(value, 0f, 1f); invalidate(); }

    void setStart(float value) {
        start = clamp(value, 0f, end - .01f);
        notifyChanged(false);
    }

    void setEnd(float value) {
        end = clamp(value, start + .01f, 1f);
        notifyChanged(false);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = dp(14), top = dp(14), bottom = getHeight() - dp(14);
        float left = pad, right = getWidth() - pad, width = Math.max(1, right - left);
        RectF track = new RectF(left, top, right, bottom);
        paint.setColor(Color.rgb(21, 35, 59));
        canvas.drawRoundRect(track, dp(12), dp(12), paint);

        int count = thumbnails.isEmpty() ? 7 : thumbnails.size();
        float segment = width / Math.max(1, count);
        for (int i = 0; i < count; i++) {
            float x = left + i * segment;
            RectF destination = new RectF(x, top, Math.min(right, x + segment + 1), bottom);
            if (!thumbnails.isEmpty() && !thumbnails.get(i).isRecycled()) {
                Bitmap bitmap = thumbnails.get(i);
                float sourceRatio = bitmap.getWidth() / (float)Math.max(1, bitmap.getHeight());
                float targetRatio = destination.width() / Math.max(1f, destination.height());
                Rect source;
                if (sourceRatio > targetRatio) {
                    int cropWidth = Math.max(1, Math.round(bitmap.getHeight() * targetRatio));
                    int offset = Math.max(0, (bitmap.getWidth() - cropWidth) / 2);
                    source = new Rect(offset, 0, Math.min(bitmap.getWidth(), offset + cropWidth), bitmap.getHeight());
                } else {
                    int cropHeight = Math.max(1, Math.round(bitmap.getWidth() / targetRatio));
                    int offset = Math.max(0, (bitmap.getHeight() - cropHeight) / 2);
                    source = new Rect(0, offset, bitmap.getWidth(), Math.min(bitmap.getHeight(), offset + cropHeight));
                }
                canvas.drawBitmap(bitmap, source, destination, paint);
            } else {
                paint.setColor(i % 2 == 0 ? Color.rgb(31, 68, 112) : Color.rgb(24, 53, 91));
                canvas.drawRect(destination, paint);
            }
        }

        float sx = left + start * width, ex = left + end * width;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4));
        paint.setColor(Color.rgb(140, 65, 255));
        canvas.drawRoundRect(new RectF(sx, top, ex, bottom), dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(Color.argb(145, 4, 8, 18));
        canvas.drawRect(left, top, sx, bottom, paint);
        canvas.drawRect(ex, top, right, bottom, paint);

        drawHandle(canvas, sx, top, bottom);
        drawHandle(canvas, ex, top, bottom);

        float px = left + playhead * width;
        paint.setColor(Color.WHITE);
        canvas.drawRect(px - dp(1), top - dp(5), px + dp(1), bottom + dp(5), paint);
    }

    private void drawHandle(Canvas canvas, float x, float top, float bottom) {
        paint.setColor(Color.rgb(140, 65, 255));
        canvas.drawRoundRect(new RectF(x - dp(7), top - dp(3), x + dp(7), bottom + dp(3)), dp(7), dp(7), paint);
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(new RectF(x - dp(1), top + dp(15), x + dp(1), bottom - dp(15)), dp(1), dp(1), paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float pad = dp(14), width = Math.max(1, getWidth() - pad * 2);
        float value = clamp((event.getX() - pad) / width, 0f, 1f);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            activeHandle = Math.abs(value - start) <= Math.abs(value - end) ? 1 : 2;
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (activeHandle == 1) start = clamp(value, 0f, end - .01f);
            else end = clamp(value, start + .01f, 1f);
            notifyChanged(true);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            getParent().requestDisallowInterceptTouchEvent(false);
            activeHandle = 0;
            performClick();
            return true;
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    @Override protected void onDetachedFromWindow() {
        recycleThumbnails();
        super.onDetachedFromWindow();
    }

    private void recycleThumbnails() {
        for (Bitmap bitmap : thumbnails) if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        thumbnails = Collections.emptyList();
    }

    private void notifyChanged(boolean fromUser) {
        invalidate();
        if (listener != null) listener.onRangeChanged(start, end, fromUser);
    }

    private float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
