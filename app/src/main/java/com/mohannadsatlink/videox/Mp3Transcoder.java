package com.mohannadsatlink.videox;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import com.naman14.androidlame.AndroidLame;
import com.naman14.androidlame.LameBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

final class Mp3Transcoder {
    interface Listener { void onProgress(int percent); }

    private final Context context;
    private final AtomicBoolean cancelled;

    Mp3Transcoder(Context context, AtomicBoolean cancelled) {
        this.context = context.getApplicationContext();
        this.cancelled = cancelled;
    }

    void convert(Uri input, File output, int bitrateKbps, Listener listener) throws Exception {
        convert(input, output, bitrateKbps, 0, 0, 0, 1f, listener);
    }

    void convert(Uri input, File output, int bitrateKbps, int outputSampleRate, long startMs, long endMs,
                 float gain, Listener listener) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        AndroidLame lame = null;
        FileOutputStream stream = null;
        try {
            try (AssetFileDescriptor afd = context.getContentResolver().openAssetFileDescriptor(input, "r")) {
                if (afd == null) throw new IllegalArgumentException("تعذر فتح الفيديو");
                if (afd.getDeclaredLength() < 0) extractor.setDataSource(afd.getFileDescriptor());
                else extractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getDeclaredLength());
            }

            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; break; }
            }
            if (track < 0) throw new IllegalArgumentException("الفيديو لا يحتوي على مسار صوتي");

            MediaFormat inputFormat = extractor.getTrackFormat(track);
            String inputMime = inputFormat.getString(MediaFormat.KEY_MIME);
            int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (channels < 1 || channels > 2) throw new IllegalArgumentException("تحويل MP3 يدعم Mono أو Stereo");
            long sourceDurationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION) : 1;
            long startUs = Math.max(0, startMs * 1000);
            long endUs = endMs > 0 ? Math.min(sourceDurationUs, endMs * 1000) : sourceDurationUs;
            long durationUs = Math.max(1, endUs - startUs);
            if (endUs <= startUs) throw new IllegalArgumentException("نطاق قص الصوت غير صالح");

            decoder = MediaCodec.createDecoderByType(inputMime);
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            int safeBitrate = Math.max(64, Math.min(320, bitrateKbps));
            lame = new LameBuilder()
                    .setInSampleRate(sampleRate)
                    .setOutSampleRate(outputSampleRate > 0 ? outputSampleRate : sampleRate)
                    .setOutChannels(channels)
                    .setOutBitrate(safeBitrate)
                    .setQuality(4)
                    .build();

            extractor.selectTrack(track);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            stream = new FileOutputStream(output);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int lastProgress = -1;

            while (!outputDone) {
                if (cancelled.get()) throw new InterruptedException("تم إلغاء العملية");
                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        int size = extractor.readSampleData(inputBuffer, 0);
                        long pts = extractor.getSampleTime();
                        if (size < 0 || pts < 0 || pts > endUs) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, pts, extractor.getSampleFlags());
                            int progress = (int) Math.min(94, Math.max(0, pts-startUs) * 94 / durationUs);
                            if (progress != lastProgress) { lastProgress = progress; listener.onProgress(progress); }
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, 10_000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat decoded = decoder.getOutputFormat();
                    if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
                        throw new IllegalArgumentException("تنسيق الصوت الداخلي غير مدعوم");
                    }
                } else if (outputIndex >= 0) {
                    ByteBuffer pcmBuffer = decoder.getOutputBuffer(outputIndex);
                    if (pcmBuffer != null && info.size > 0 && info.presentationTimeUs >= startUs && info.presentationTimeUs <= endUs) {
                        pcmBuffer.position(info.offset);
                        pcmBuffer.limit(info.offset + info.size);
                        ShortBuffer shorts = pcmBuffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                        short[] pcm = new short[shorts.remaining()];
                        shorts.get(pcm);
                        if (gain != 1f) for (int i=0;i<pcm.length;i++) pcm[i]=(short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,Math.round(pcm[i]*gain)));
                        int samplesPerChannel = pcm.length / channels;
                        byte[] encoded = new byte[Math.max(16_384, 7200 + samplesPerChannel * 5 / 4)];
                        int bytes = channels == 2
                                ? lame.encodeBufferInterLeaved(pcm, samplesPerChannel, encoded)
                                : lame.encode(pcm, pcm, samplesPerChannel, encoded);
                        if (bytes < 0) throw new IllegalStateException("فشل مشفّر MP3: " + bytes);
                        if (bytes > 0) stream.write(encoded, 0, bytes);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outputIndex, false);
                }
            }

            byte[] tail = new byte[16_384];
            int tailBytes = lame.flush(tail);
            if (tailBytes > 0) stream.write(tail, 0, tailBytes);
            stream.flush();
            listener.onProgress(100);
        } catch (Exception e) {
            if (output.exists()) output.delete();
            throw e;
        } finally {
            try { if (stream != null) stream.close(); } catch (Exception ignored) {}
            try { if (lame != null) lame.close(); } catch (Exception ignored) {}
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) {}
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }
}
