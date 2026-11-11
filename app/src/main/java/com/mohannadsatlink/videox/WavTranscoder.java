package com.mohannadsatlink.videox;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

final class WavTranscoder {
    interface Listener { void onProgress(int percent); }
    private final Context context; private final AtomicBoolean cancelled;
    WavTranscoder(Context context,AtomicBoolean cancelled){this.context=context.getApplicationContext();this.cancelled=cancelled;}

    void convert(Uri input,File output,int requestedRate,long startMs,long endMs,float gain,Listener listener)throws Exception{
        MediaExtractor extractor=new MediaExtractor();MediaCodec decoder=null;RandomAccessFile wav=null;
        try{
            try(AssetFileDescriptor afd=context.getContentResolver().openAssetFileDescriptor(input,"r")){if(afd==null)throw new IllegalArgumentException("تعذر فتح الفيديو");if(afd.getDeclaredLength()<0)extractor.setDataSource(afd.getFileDescriptor());else extractor.setDataSource(afd.getFileDescriptor(),afd.getStartOffset(),afd.getDeclaredLength());}
            int track=-1;for(int i=0;i<extractor.getTrackCount();i++){String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){track=i;break;}}
            if(track<0)throw new IllegalArgumentException("الفيديو لا يحتوي على صوت");MediaFormat format=extractor.getTrackFormat(track);String mime=format.getString(MediaFormat.KEY_MIME);int inputRate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE);int channels=Math.max(1,Math.min(2,format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)));int outputRate=requestedRate>0?requestedRate:inputRate;long sourceDuration=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):1;long startUs=Math.max(0,startMs*1000),endUs=endMs>0?Math.min(sourceDuration,endMs*1000):sourceDuration;if(endUs<=startUs)throw new IllegalArgumentException("نطاق قص الصوت غير صالح");
            decoder=MediaCodec.createDecoderByType(mime);format.setInteger(MediaFormat.KEY_PCM_ENCODING,AudioFormat.ENCODING_PCM_16BIT);decoder.configure(format,null,null,0);decoder.start();extractor.selectTrack(track);extractor.seekTo(startUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            wav=new RandomAccessFile(output,"rw");wav.setLength(0);writeHeader(wav,outputRate,channels,0);long dataBytes=0;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();boolean inputDone=false,outputDone=false;int last=-1;
            while(!outputDone){if(cancelled.get())throw new InterruptedException("تم إلغاء العملية");if(!inputDone){int index=decoder.dequeueInputBuffer(10000);if(index>=0){ByteBuffer buffer=decoder.getInputBuffer(index);int size=extractor.readSampleData(buffer,0);long pts=extractor.getSampleTime();if(size<0||pts<0||pts>endUs){decoder.queueInputBuffer(index,0,0,endUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{decoder.queueInputBuffer(index,0,size,pts,extractor.getSampleFlags());extractor.advance();}}}int index=decoder.dequeueOutputBuffer(info,10000);if(index>=0){ByteBuffer pcm=decoder.getOutputBuffer(index);if(pcm!=null&&info.size>0&&info.presentationTimeUs>=startUs&&info.presentationTimeUs<=endUs){pcm.position(info.offset);pcm.limit(info.offset+info.size);ShortBuffer shortBuffer=pcm.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();short[] source=new short[shortBuffer.remaining()];shortBuffer.get(source);short[] samples=resample(source,channels,inputRate,outputRate,gain);ByteBuffer bytes=ByteBuffer.allocate(samples.length*2).order(ByteOrder.LITTLE_ENDIAN);bytes.asShortBuffer().put(samples);wav.write(bytes.array());dataBytes+=bytes.capacity();int progress=(int)Math.min(99,Math.max(0,info.presentationTimeUs-startUs)*100/Math.max(1,endUs-startUs));if(progress!=last){last=progress;listener.onProgress(progress);}}outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;decoder.releaseOutputBuffer(index,false);}}
            writeHeader(wav,outputRate,channels,dataBytes);listener.onProgress(100);
        }catch(Exception e){if(output.exists())output.delete();throw e;}finally{try{if(wav!=null)wav.close();}catch(Exception ignored){}try{if(decoder!=null){decoder.stop();decoder.release();}}catch(Exception ignored){}extractor.release();}
    }

    private short[] resample(short[] input,int channels,int inRate,int outRate,float gain){int inFrames=input.length/channels;int outFrames=Math.max(1,(int)((long)inFrames*outRate/inRate));short[] output=new short[outFrames*channels];for(int frame=0;frame<outFrames;frame++){int sourceFrame=Math.min(inFrames-1,(int)((long)frame*inRate/outRate));for(int channel=0;channel<channels;channel++){int value=Math.round(input[sourceFrame*channels+channel]*gain);output[frame*channels+channel]=(short)Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,value));}}return output;}
    private void writeHeader(RandomAccessFile file,int sampleRate,int channels,long dataBytes)throws Exception{file.seek(0);file.writeBytes("RIFF");writeInt(file,(int)(36+dataBytes));file.writeBytes("WAVEfmt ");writeInt(file,16);writeShort(file,1);writeShort(file,channels);writeInt(file,sampleRate);writeInt(file,sampleRate*channels*2);writeShort(file,channels*2);writeShort(file,16);file.writeBytes("data");writeInt(file,(int)dataBytes);if(dataBytes>0)file.seek(44+dataBytes);}
    private void writeInt(RandomAccessFile file,int value)throws Exception{file.write(value&255);file.write((value>>8)&255);file.write((value>>16)&255);file.write((value>>24)&255);}
    private void writeShort(RandomAccessFile file,int value)throws Exception{file.write(value&255);file.write((value>>8)&255);}
}
