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
    }

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
            float scale = options.shortEdge>0?Math.min(1f,options.shortEdge/(float)Math.min(srcW,srcH)):Math.min(1f, maxLong / (float)Math.max(srcW, srcH));
            int outW = even(Math.round(srcW * scale)), outH = even(Math.round(srcH * scale));
            if (!"الأصلي".equals(options.aspect)) {
                float ratio = "1:1".equals(options.aspect) ? 1f : "9:16".equals(options.aspect) ? 9f/16f : 16f/9f;
                int edge = even(Math.round(Math.max(srcW, srcH) * scale));
                if (ratio >= 1f) { outW = edge; outH = even(Math.round(edge / ratio)); }
                else { outH = edge; outW = even(Math.round(edge * ratio)); }
            }
            String profile = options.outputFormat == null ? "MP4_AVC" : options.outputFormat;
            int bitrate = preset == Preset.LOW ? 700_000 : preset == Preset.MEDIUM ? 1_500_000 : 3_000_000;
            if (in.containsKey(MediaFormat.KEY_BIT_RATE)) bitrate = Math.min(bitrate, Math.max(300_000, in.getInteger(MediaFormat.KEY_BIT_RATE)));
            if(options.videoBitrate>0)bitrate=options.videoBitrate;
            if (options.targetBytes > 0) {
                long seconds = Math.max(1, (endUs - startUs) / 1_000_000);
                long totalRate = options.targetBytes * 8 / seconds;
                int copiedAudioRate = options.keepAudio ? sourceAudioBitrate(input, profile.startsWith("WEBM")) : 0;
                // Reserve four percent for the container and codec headers so the result stays near the requested size.
                bitrate = (int)Math.max(250_000, Math.min(20_000_000, totalRate * 96 / 100 - copiedAudioRate));
            }

            String outputMime = profile.endsWith("HEVC") ? MediaFormat.MIMETYPE_VIDEO_HEVC
                    : profile.endsWith("VP9") ? MediaFormat.MIMETYPE_VIDEO_VP9
                    : profile.endsWith("VP8") ? MediaFormat.MIMETYPE_VIDEO_VP8
                    : MediaFormat.MIMETYPE_VIDEO_AVC;
            MediaFormat out = MediaFormat.createVideoFormat(outputMime, outW, outH);
            out.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            out.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            int outputFps=options.frameRate>0?options.frameRate:(in.containsKey(MediaFormat.KEY_FRAME_RATE) ? Math.min(60, Math.max(15, in.getInteger(MediaFormat.KEY_FRAME_RATE))) : 30);
            out.setInteger(MediaFormat.KEY_FRAME_RATE, outputFps);
            out.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);

            encoder = MediaCodec.createEncoderByType(outputMime);
            try {
                MediaCodecInfo.EncoderCapabilities capabilities = encoder.getCodecInfo()
                        .getCapabilitiesForType(outputMime).getEncoderCapabilities();
                if ((options.targetBytes > 0 || options.videoBitrate > 0) && capabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
                    out.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                else if (capabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                    out.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            } catch (Exception ignored) {}
            encoder.configure(out, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInput = encoder.createInputSurface();
            codecSurface = new CodecSurface(encoderInput, outW, outH, srcW, srcH);
            encoder.start();

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(in, codecSurface.decoderSurface(), null, 0);
            decoder.start();

            int muxerType = profile.startsWith("WEBM") ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                    : profile.startsWith("3GP") ? MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
                    : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
            muxer = new MediaMuxer(output.getAbsolutePath(), muxerType);
            int rotation = rotation(input);
            if (rotation != 0 && !profile.startsWith("WEBM")) muxer.setOrientationHint(rotation);

            int audioOutTrack = -1;
            if (options.keepAudio) {
                audioExtractor = new MediaExtractor(); setSource(audioExtractor, input);
                int at = findTrack(audioExtractor, "audio/");
                if (at >= 0) {
                    MediaFormat audioFormat = audioExtractor.getTrackFormat(at);
                    String audioMime = audioFormat.getString(MediaFormat.KEY_MIME);
                    boolean webmAudio = audioMime != null && (audioMime.equals("audio/opus") || audioMime.equals("audio/vorbis"));
                    if (!profile.startsWith("WEBM") || webmAudio) {
                        audioExtractor.selectTrack(at);
                        audioOutTrack = muxer.addTrack(audioFormat);
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
            long nextFrameUs=0, frameIntervalUs=options.frameRate>0?1_000_000L/options.frameRate:0;

            while (!encoderDone) {
                if (cancelled.get()) throw new InterruptedException("تم إلغاء العملية");

                int encStatus = encoder.dequeueOutputBuffer(encInfo, 10_000);
                if (encStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) throw new IllegalStateException("تغير تنسيق الإخراج مرتين");
                    videoOutTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start(); muxerStarted = true;
                } else if (encStatus >= 0) {
                    ByteBuffer data = encoder.getOutputBuffer(encStatus);
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) encInfo.size = 0;
                    if (encInfo.size > 0) {
                        if (!muxerStarted) throw new IllegalStateException("لم يبدأ ملف الإخراج");
                        data.position(encInfo.offset); data.limit(encInfo.offset + encInfo.size);
                        muxer.writeSampleData(videoOutTrack, data, encInfo);
                    }
                    encoderDone = (encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(encStatus, false);
                }

                if (!inputDone) {
                    int index = decoder.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = extractor.readSampleData(buffer, 0);
                        long pts = extractor.getSampleTime();
                        if (size < 0 || pts < 0 || pts > endUs) {
                            decoder.queueInputBuffer(index, 0, 0, Math.max(startUs, endUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, pts, extractor.getSampleFlags());
                            extractor.advance();
                        }
                    }
                }

                if (!decoderDone) {
                    int decStatus = decoder.dequeueOutputBuffer(decInfo, 10_000);
                    if (decStatus >= 0) {
                        boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean render = decInfo.size > 0 && decInfo.presentationTimeUs >= startUs && decInfo.presentationTimeUs <= endUs;
                        long adjusted = Math.max(0, decInfo.presentationTimeUs - startUs);
                        if(render&&frameIntervalUs>0){if(adjusted+1000<nextFrameUs)render=false;else nextFrameUs=adjusted+frameIntervalUs;}
                        decoder.releaseOutputBuffer(decStatus, render);
                        if (render) {
                            codecSurface.awaitAndDraw();
                            codecSurface.setPresentationTime(adjusted * 1000);
                            codecSurface.swap();
                            int p = (int)Math.min(94, adjusted * 94 / rangeUs);
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
    private static int even(int value) { return Math.max(2, value & ~1); }
}
