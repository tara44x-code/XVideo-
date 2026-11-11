package com.mohannadsatlink.videox;

import android.net.Uri;

/** One piece of media on the editor timeline: a range [inMs, outMs) of a source file. */
final class TimelineClip {
    static final long MIN_LENGTH_MS = 300;

    final Uri uri;
    final String name;
    final long sourceDurationMs;
    final boolean hasVideo;
    final boolean hasAudio;
    final int width;
    final int height;
    long inMs;
    long outMs;
    float speed = 1f;
    boolean muted;

    TimelineClip(Uri uri, String name, long sourceDurationMs, boolean hasVideo, boolean hasAudio, int width, int height) {
        this.uri = uri;
        this.name = name;
        this.sourceDurationMs = Math.max(1, sourceDurationMs);
        this.hasVideo = hasVideo;
        this.hasAudio = hasAudio;
        this.width = width;
        this.height = height;
        this.inMs = 0;
        this.outMs = this.sourceDurationMs;
    }

    TimelineClip copy() {
        TimelineClip c = new TimelineClip(uri, name, sourceDurationMs, hasVideo, hasAudio, width, height);
        c.inMs = inMs;
        c.outMs = outMs;
        c.speed = speed;
        c.muted = muted;
        return c;
    }

    /** Length of the source range, before speed is applied. */
    long sourceLengthMs() { return Math.max(1, outMs - inMs); }

    /** Length this clip occupies on the timeline (after speed). */
    long timelineLengthMs() { return Math.max(1, Math.round(sourceLengthMs() / (double) speed)); }

    /** Source position for a time offset inside this clip on the timeline. */
    long sourceAt(long offsetOnTimelineMs) {
        long offset = Math.max(0, Math.min(timelineLengthMs(), offsetOnTimelineMs));
        return Math.min(outMs, inMs + Math.round(offset * (double) speed));
    }

    /** Timeline offset (inside this clip) for a source position. */
    long timelineOffsetOf(long sourceMs) {
        long clamped = Math.max(inMs, Math.min(outMs, sourceMs));
        return Math.round((clamped - inMs) / (double) speed);
    }
}
