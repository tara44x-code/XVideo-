package com.mohannadsatlink.videox;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.StreamInformation;

import java.util.Locale;

final class VideoInfo {
    final Uri uri;
    String name = "video";
    long size;
    long durationMs;
    int width;
    int height;
    int bitrateKbps;
    float frameRate;
    String mime = "video";

    VideoInfo(Context context, Uri uri) throws Exception {
        this.uri = uri;
        ContentResolver cr = context.getContentResolver();
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        }
        boolean ok = false;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, uri);
            durationMs = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            width = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            height = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            bitrateKbps = (int)(parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)) / 1000);
            try { String fps=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE); frameRate=fps==null?0:Float.parseFloat(fps); } catch(Exception ignored) {}
            String detected=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE); if(detected!=null)mime=detected;
            int rotation = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rotation == 90 || rotation == 270) { int t = width; width = height; height = t; }
            ok = durationMs > 0 && width > 0;
        } catch (Exception androidCannotRead) {
            ok = false;
        } finally { try { r.release(); } catch (Exception ignored) {} }
        if (!ok) probeWithFfmpeg(context, uri);
    }

    /** True when Android's own decoders could not read the file and FFmpeg must handle it (RMVB, FLV, WMV…). */
    boolean legacy;

    private void probeWithFfmpeg(Context context, Uri uri) throws Exception {
        String path = "file".equals(uri.getScheme()) ? uri.getPath() : FFmpegKitConfig.getSafParameterForRead(context, uri);
        MediaInformationSession session = FFprobeKit.getMediaInformation(path);
        MediaInformation media = session == null ? null : session.getMediaInformation();
        if (media == null) throw new IllegalArgumentException("صيغة الملف غير مدعومة");
        durationMs = Math.round(parseDouble(media.getDuration()) * 1000);
        bitrateKbps = (int) (parse(media.getBitrate()) / 1000);
        String format = media.getFormat();
        mime = format == null ? "video" : format.split(",")[0];
        boolean hasVideo = false;
        if (media.getStreams() != null) for (StreamInformation stream : media.getStreams()) {
            if (!"video".equals(stream.getType()) || hasVideo) continue;
            hasVideo = true;
            width = stream.getWidth() == null ? 0 : stream.getWidth().intValue();
            height = stream.getHeight() == null ? 0 : stream.getHeight().intValue();
            frameRate = (float) fraction(stream.getAverageFrameRate());
        }
        if (!hasVideo || durationMs <= 0) throw new IllegalArgumentException("الملف لا يحتوي على فيديو صالح");
        legacy = true;
    }

    private static double parseDouble(String v) { try { return v == null ? 0 : Double.parseDouble(v.trim()); } catch (Exception e) { return 0; } }

    private static double fraction(String v) {
        if (v == null) return 0;
        try {
            String[] p = v.split("/");
            if (p.length == 2) { double d = Double.parseDouble(p[1]); return d == 0 ? 0 : Double.parseDouble(p[0]) / d; }
            return Double.parseDouble(v);
        } catch (Exception e) { return 0; }
    }

    private static long parse(String value) {
        try { return value == null ? 0 : Long.parseLong(value); }
        catch (NumberFormatException e) { return 0; }
    }

    String details() {
        return width + " × " + height + "  •  " + time(durationMs) + "  •  " + bytes(size);
    }

    String technicalDetails(){String fps=frameRate>0?String.format(Locale.US,"%.1f FPS",frameRate):"FPS تلقائي";String kb=bitrateKbps>0?bitrateKbps+" kbps":"kbps تلقائي";return kb+"  •  "+fps+"  •  "+mime.replace("video/","").toUpperCase(Locale.US);}

    static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    static String timePrecise(long ms) {
        long safe = Math.max(0, ms);
        long totalSeconds = safe / 1000;
        return String.format(Locale.US, "%02d:%02d.%03d", totalSeconds / 60, totalSeconds % 60, safe % 1000);
    }

    static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KB", n / 1024f);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", n / 1048576f);
        return String.format(Locale.US, "%.2f GB", n / 1073741824f);
    }
}
