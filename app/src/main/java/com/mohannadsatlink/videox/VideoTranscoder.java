package com.mohannadsatlink.videox;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

final class VideoTranscoder {
    interface Listener { void onProgress(int percent); }
    enum Preset { LOW, MEDIUM, HIGH }

    static final class Options {
        Preset preset = Preset.MEDIUM;
        long startMs;
        long endMs;
        boolean keepAudio = true;
        String outputFormat = "MP4_AVC";
        long targetBytes;
        String aspect = "الأصلي";
        int shortEdge;
        int videoBitrate;
        int frameRate;
        /** Bitrate (bps) the audio will be re-encoded at afterwards; 0 = keep source audio. */
        int audioBitrate;
        /** Lower resolution/FPS automatically when the bitrate is too low for a clean picture. */
        boolean smartQuality = true;
        /** Copy the source audio track with MediaMuxer. Off when FFmpeg adds the audio afterwards. */
        boolean muxAudio = true;
        /** Resolution as a percentage of the source (profiles); 0 = use shortEdge/preset. */
        int scalePercent;
        /** Use variable bitrate even with an explicit bitrate (better quality per byte). */
        boolean preferVbr;
    }

    /** Minimum bits per pixel per frame for a clean picture (hardware encoders). */
    private static final double MIN_BPP_AVC = 0.045, MIN_BPP_HEVC = 0.028;

    private final Context context;
    private final AtomicBoolean cancelled;

    VideoTranscoder(Context context, AtomicBoolean cancelled) {
        this.context = context.getApplicationContext();
        this.cancelled = cancelled;
    }

    void transcode(Uri input, File output, Options options, Listener listener) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaExtractor audioExtractor = null;
        MediaCodec decoder = null, encoder = null;
        MediaMuxer muxer = null;
        CodecSurface codecSurface = null;
        Surface encoderInput = null;
        boolean muxerStarted = false;
        try {
            setSource(extractor, input);
            int videoTrack = findTrack(extractor, "video/");
            if (videoTrack < 0) throw new IllegalArgumentException("لا يوجد مسار فيديو صالح");
            MediaFormat in = extractor.getTrackFormat(videoTrack);
            String mime = in.getString(MediaFormat.KEY_MIME);
            int srcW = in.getInteger(MediaFormat.KEY_WIDTH), srcH = in.getInteger(MediaFormat.KEY_HEIGHT);
            long durationUs = in.containsKey(MediaFormat.KEY_DURATION) ? in.getLong(MediaFormat.KEY_DURATION) : Math.max(1, options.endMs * 1000);
            long startUs = Math.max(0, options.startMs * 1000);
            long endUs = options.endMs <= 0 ? durationUs : Math.min(durationUs, options.endMs * 1000);
            if (endUs <= startUs) throw new IllegalArgumentException("نطاق القص غير صالح");

            Preset preset = options.preset;
            int maxLong = preset == Preset.LOW ? 854 : preset == Preset.MEDIUM ? 1280 : 1920;
            float scale = options.scalePercent>0?1f:options.shortEdge>0?Math.min(1f,options.shortEdge/(float)Math.min(srcW,srcH)):Math.min(1f, maxLong / (float)Math.max(srcW, srcH));
            int outW = even(Math.round(srcW * scale)), outH = even(Math.round(srcH * scale));
            if (!"الأصلي".equals(options.aspect)) {
                float ratio = "1:1".equals(options.aspect) ? 1f : "9:16".equals(options.aspect) ? 9f/16f : 16f/9f;
                int edge = even(Math.round(Math.max(srcW, srcH) * scale));
                if (ratio >= 1f) { outW = edge; outH = even(Math.round(edge / ratio)); }
                else { outH = edge; outW = even(Math.round(edge * ratio)); }
            }
            String profile = options.outputFormat == null ? "MP4_AVC" : options.outputFormat;
            String outputMime = profile.endsWith("HEVC") ? MediaFormat.MIMETYPE_VIDEO_HEVC
                    : profile.endsWith("VP9") ? MediaFormat.MIMETYPE_VIDEO_VP9
                    : profile.endsWith("VP8") ? MediaFormat.MIMETYPE_VIDEO_VP8
                    : MediaFormat.MIMETYPE_VIDEO_AVC;
            boolean efficientCodec = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(outputMime) || MediaFormat.MIMETYPE_VIDEO_VP9.equals(outputMime);
            int sourceFps = in.containsKey(MediaFormat.KEY_FRAME_RATE) ? safeInt(in, MediaFormat.KEY_FRAME_RATE, 30) : 30;
            int outputFps = options.frameRate > 0 ? options.frameRate : Math.min(60, Math.max(15, sourceFps));
            int sourceBitrate = in.containsKey(MediaFormat.KEY_BIT_RATE) ? safeInt(in, MediaFormat.KEY_BIT_RATE, 0) : 0;

            int bitrate;
            if (options.videoBitrate > 0) {
                bitrate = options.videoBitrate;
            } else if (options.targetBytes > 0) {
                long seconds = Math.max(1, (endUs - startUs) / 1_000_000);
                long totalRate = options.targetBytes * 8 / seconds;
                int audioRate = !options.keepAudio ? 0 : options.audioBitrate > 0 ? options.audioBitrate : sourceAudioBitrate(input, profile.startsWith("WEBM"));
                // Reserve four percent for the container and codec headers so the result stays near the requested size.
                bitrate = (int) Math.max(150_000, Math.min(20_000_000, totalRate * 96 / 100 - audioRate));
            } else {
                // Content-aware: bitrate follows resolution and frame rate instead of fixed values.
                double bpp = preset == Preset.LOW ? 0.05 : preset == Preset.MEDIUM ? 0.075 : 0.11;
                if (efficientCodec) bpp *= 0.65;
                bitrate = (int) Math.round((double) outW * outH * outputFps * bpp);
                if (sourceBitrate > 0) {
                    double cap = preset == Preset.LOW ? 0.5 : preset == Preset.MEDIUM ? 0.7 : 0.9;
                    bitrate = (int) Math.min(bitrate, sourceBitrate * cap);
                }
                bitrate = Math.max(250_000, bitrate);
            }

            // Quality guard: too few bits for this many pixels gives blocky video. Trading some
            // resolution for bits looks much cleaner at the same file size. The frame rate is never
            // touched automatically: dropping frames makes motion look jerky.
            if (options.scalePercent > 0 && options.scalePercent < 100 && "الأصلي".equals(options.aspect)) {
                float pct = options.scalePercent / 100f;
                outW = even(Math.round(outW * pct));
                outH = even(Math.round(outH * pct));
            }
            if (options.smartQuality && options.videoBitrate <= 0 && options.scalePercent <= 0 && "الأصلي".equals(options.aspect)) {
                double minBpp = efficientCodec ? MIN_BPP_HEVC : MIN_BPP_AVC;
                while (bitrate / ((double) outW * outH * outputFps) < minBpp && Math.min(outW, outH) > 480) {
                    float shrink = Math.max(480f / Math.min(outW, outH), 0.75f);
                    outW = even(Math.round(outW * shrink));
                    outH = even(Math.round(outH * shrink));
                }
            }

            MediaCodecInfo.CodecProfileLevel best = null;
            encoder = MediaCodec.createEncoderByType(outputMime);
            int bitrateMode = -1;
            try {
                MediaCodecInfo.CodecCapabilities caps = encoder.getCodecInfo().getCapabilitiesForType(outputMime);
                MediaCodecInfo.EncoderCapabilities capabilities = caps.getEncoderCapabilities();
                if ((options.targetBytes > 0 || (options.videoBitrate > 0 && !options.preferVbr)) && capabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
                    bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
                else if (capabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                    bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
                // H.264 High profile compresses noticeably better than Baseline at the same bitrate.
                int wanted = MediaFormat.MIMETYPE_VIDEO_AVC.equals(outputMime) ? MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                        : MediaFormat.MIMETYPE_VIDEO_HEVC.equals(outputMime) ? MediaCodecInfo.CodecProfileLevel.HEVCProfileMain : -1;
                if (wanted > 0 && caps.profileLevels != null)
                    for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels)
                        if (pl.profile == wanted && (best == null || pl.level > best.level)) best = pl;
            } catch (Exception ignored) {}
            try {
                encoder.configure(videoFormat(outputMime, outW, outH, bitrate, outputFps, bitrateMode, best), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Exception profileRejected) {
                // Some encoders advertise a profile but refuse it: retry with the plain settings.
                encoder.reset();
                encoder.configure(videoFormat(outputMime, outW, outH, bitrate, outputFps, bitrateMode, null), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            encoderInput = encoder.createInputSurface();
            codecSurface = new CodecSurface(encoderInput, outW, outH, srcW, srcH);
            encoder.start();

            decoder = MediaCodec.createDecoderByType(mime);
            try {
                in.setInteger(MediaFormat.KEY_PRIORITY, 0);
                in.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);
                decoder.configure(in, codecSurface.decoderSurface(), null, 0);
            } catch (Exception rateRejected) {
                decoder.reset();
                decoder.configure(extractor.getTrackFormat(videoTrack), codecSurface.decoderSurface(), null, 0);
            }
            decoder.start();

            int muxerType = profile.startsWith("WEBM") ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                    : profile.startsWith("3GP") ? MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
                    : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
            muxer = new MediaMuxer(output.getAbsolutePath(), muxerType);
            int rotation = rotation(input);
            if (rotation != 0 && !profile.startsWith("WEBM")) muxer.setOrientationHint(rotation);

            int audioOutTrack = -1;
            if (options.keepAudio && options.muxAudio) {
                audioExtractor = new MediaExtractor(); setSource(audioExtractor, input);
                int at = findTrack(audioExtractor, "audio/");
                if (at >= 0) {
                    MediaFormat audioFormat = audioExtractor.getTrackFormat(at);
                    String audioMime = audioFormat.getString(MediaFormat.KEY_MIME);
                    boolean webmAudio = audioMime != null && (audioMime.equals("audio/opus") || audioMime.equals("audio/vorbis"));
                    if (!profile.startsWith("WEBM") || webmAudio) {
                        try {
                            audioOutTrack = muxer.addTrack(audioFormat);
                            audioExtractor.selectTrack(at);
                        } catch (Exception unsupportedAudio) {
                            // e.g. Opus/Vorbis/AC-3 cannot go into MP4 through MediaMuxer: keep the video only.
                            audioOutTrack = -1;
                        }
                    }
                }
            }

            extractor.selectTrack(videoTrack);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false, decoderDone = false, encoderDone = false;
            int videoOutTrack = -1;
            long rangeUs = Math.max(1, endUs - startUs), lastProgress = -1;
            // Only drop frames when the user asked for a lower frame rate. Frames are picked on a
            // fixed time grid so motion stays even (e.g. 60 -> 30 keeps every 2nd frame).
            long frameIntervalUs = options.frameRate > 0 && options.frameRate < sourceFps - 1 ? 1_000_000L / options.frameRate : 0;
            long nextFrameUs = 0;
            final long timeoutUs = 2_500;

            while (!encoderDone) {
                if (cancelled.get()) throw new InterruptedException("تم إلغاء العملية");

                // 1) Feed the decoder with every free input buffer (no waiting).
                while (!inputDone) {
                    int index = decoder.dequeueInputBuffer(0);
                    if (index < 0) break;
                    ByteBuffer buffer = decoder.getInputBuffer(index);
                    int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                    long pts = extractor.getSampleTime();
                    if (size < 0 || pts < 0 || pts > endUs) {
                        decoder.queueInputBuffer(index, 0, 0, Math.max(startUs, endUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        decoder.queueInputBuffer(index, 0, size, pts, extractor.getSampleFlags());
                        extractor.advance();
                    }
                }

                // 2) Drain everything the encoder has ready. Only wait once the decoder is finished.
                while (true) {
                    int encStatus = encoder.dequeueOutputBuffer(encInfo, decoderDone ? timeoutUs : 0);
                    if (encStatus == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw new IllegalStateException("تغير تنسيق الإخراج مرتين");
                        videoOutTrack = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start(); muxerStarted = true;
                    } else if (encStatus >= 0) {
                        ByteBuffer data = encoder.getOutputBuffer(encStatus);
                        if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) encInfo.size = 0;
                        if (encInfo.size > 0 && data != null) {
                            if (!muxerStarted) throw new IllegalStateException("لم يبدأ ملف الإخراج");
                            data.position(encInfo.offset); data.limit(encInfo.offset + encInfo.size);
                            muxer.writeSampleData(videoOutTrack, data, encInfo);
                        }
                        encoderDone = (encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        encoder.releaseOutputBuffer(encStatus, false);
                        if (encoderDone) break;
                    }
                    if (cancelled.get()) throw new InterruptedException("تم إلغاء العملية");
                }
                if (encoderDone) break;

                // 3) Take one decoded frame and render it into the encoder.
                if (!decoderDone) {
                    int decStatus = decoder.dequeueOutputBuffer(decInfo, timeoutUs);
                    if (decStatus >= 0) {
                        boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean render = decInfo.size > 0 && decInfo.presentationTimeUs >= startUs && decInfo.presentationTimeUs <= endUs;
                        long adjusted = Math.max(0, decInfo.presentationTimeUs - startUs);
                        if (render && frameIntervalUs > 0) {
                            if (adjusted + 1000 < nextFrameUs) render = false;
                            else while (nextFrameUs <= adjusted + 1000) nextFrameUs += frameIntervalUs;
                        }
                        decoder.releaseOutputBuffer(decStatus, render);
                        if (render) {
                            codecSurface.awaitAndDraw();
                            codecSurface.setPresentationTime(adjusted * 1000);
                            codecSurface.swap();
                            int p = (int) Math.min(94, adjusted * 94 / rangeUs);
                            if (p != lastProgress) { lastProgress = p; listener.onProgress(p); }
                        }
                        if (eos) { decoderDone = true; encoder.signalEndOfInputStream(); }
                    }
                }
            }

            if (options.keepAudio && audioExtractor != null && audioOutTrack >= 0 && muxerStarted) {
                copyAudio(audioExtractor, muxer, audioOutTrack, startUs, endUs, cancelled);
            }
            listener.onProgress(100);
        } catch (Exception e) {
            if (output.exists()) output.delete();
            throw e;
        } finally {
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) {}
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
            try { if (codecSurface != null) codecSurface.release(); } catch (Exception ignored) {}
            try { if (encoderInput != null) encoderInput.release(); } catch (Exception ignored) {}
            try { extractor.release(); } catch (Exception ignored) {}
            try { if (audioExtractor != null) audioExtractor.release(); } catch (Exception ignored) {}
            try { if (muxer != null) { if (muxerStarted) muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
        }
    }

    private void copyAudio(MediaExtractor ex, MediaMuxer muxer, int outTrack, long startUs, long endUs,
                           AtomicBoolean cancel) {
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!cancel.get()) {
            long time = ex.getSampleTime();
            if (time < 0 || time > endUs) break;
            buffer.clear(); int size = ex.readSampleData(buffer, 0); if (size < 0) break;
            info.set(0, size, Math.max(0, time - startUs), ex.getSampleFlags());
            muxer.writeSampleData(outTrack, buffer, info); ex.advance();
        }
    }

    private void setSource(MediaExtractor ex, Uri uri) throws Exception {
        try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd == null) throw new IllegalArgumentException("تعذر فتح الفيديو");
            if (afd.getDeclaredLength() < 0) ex.setDataSource(afd.getFileDescriptor());
            else ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getDeclaredLength());
        }
    }
    private int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }
    private int rotation(Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try { r.setDataSource(context, uri); String v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION); return v == null ? 0 : Integer.parseInt(v); }
        catch (Exception e) { return 0; } finally { try { r.release(); } catch (Exception ignored) {} }
    }
    private int sourceAudioBitrate(Uri uri, boolean webmOutput) {
        MediaExtractor ex = new MediaExtractor();
        try {
            setSource(ex, uri);
            int track = findTrack(ex, "audio/");
            if (track < 0) return 0;
            MediaFormat format = ex.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (webmOutput && !("audio/opus".equals(mime) || "audio/vorbis".equals(mime))) return 0;
            int value = format.containsKey(MediaFormat.KEY_BIT_RATE) ? format.getInteger(MediaFormat.KEY_BIT_RATE) : 128_000;
            return Math.max(32_000, Math.min(512_000, value));
        } catch (Exception ignored) {
            return 128_000;
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
        }
    }
    /** False when Android has no decoder for this file's video (RMVB/RM, FLV, WMV…): use FFmpeg instead. */
    static boolean canDecode(Context context, Uri uri) {
        MediaExtractor ex = new MediaExtractor();
        try {
            try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (afd == null) return false;
                if (afd.getDeclaredLength() < 0) ex.setDataSource(afd.getFileDescriptor());
                else ex.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getDeclaredLength());
            }
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                return new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null;
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    private static MediaFormat videoFormat(String mime, int w, int h, int bitrate, int fps, int bitrateMode, MediaCodecInfo.CodecProfileLevel profile) {
        MediaFormat out = MediaFormat.createVideoFormat(mime, w, h);
        out.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        out.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        out.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        out.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        if (bitrateMode >= 0) out.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode);
        if (profile != null) {
            // Ask the hardware to run as fast as it can (we are not recording in real time).
            out.setInteger(MediaFormat.KEY_PRIORITY, 0);
            out.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);
        }
        if (profile != null) {
            out.setInteger(MediaFormat.KEY_PROFILE, profile.profile);
            out.setInteger(MediaFormat.KEY_LEVEL, profile.level);
        }
        return out;
    }

    private static int safeInt(MediaFormat f, String key, int fallback) {
        try { return f.getInteger(key); } catch (Exception e) { try { return Math.round(f.getFloat(key)); } catch (Exception ignored) { return fallback; } }
    }

    private static int even(int value) { return Math.max(2, value & ~1); }
}
