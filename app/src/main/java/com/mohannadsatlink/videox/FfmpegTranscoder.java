package com.mohannadsatlink.videox;

import android.content.Context;
import android.net.Uri;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class FfmpegTranscoder {
    interface Listener { void onProgress(int percent); }
    private final Context context; private final AtomicBoolean cancelled;
    FfmpegTranscoder(Context context,AtomicBoolean cancelled){this.context=context.getApplicationContext();this.cancelled=cancelled;}

    void convertContainer(Uri input,File output,String format,long durationMs,int audioBitrateKbps,int audioSampleRate,Listener listener)throws Exception{
        String source="file".equals(input.getScheme())?input.getPath():FFmpegKitConfig.getSafParameterForRead(context,input);
        ArrayList<String> args=new ArrayList<>();args.add("-y");args.add("-i");args.add(source);args.add("-map");args.add("0:v:0");args.add("-map");args.add("0:a?");
        if("FFMPEG_AVI".equals(format)){args.add("-c:v");args.add("mpeg4");args.add("-q:v");args.add("4");args.add("-c:a");args.add("libmp3lame");args.add("-b:a");args.add(Math.max(64,audioBitrateKbps)+"k");args.add("-f");args.add("avi");}
        else if("FFMPEG_MOV".equals(format)){args.add("-c:v");args.add("copy");args.add("-c:a");args.add("aac");args.add("-b:a");args.add(Math.max(64,audioBitrateKbps)+"k");args.add("-f");args.add("mov");}
        else{args.add("-c:v");args.add("copy");args.add("-c:a");args.add("aac");args.add("-b:a");args.add(Math.max(64,audioBitrateKbps)+"k");args.add("-f");args.add("matroska");}
        if(audioSampleRate>0){args.add("-ar");args.add(String.valueOf(audioSampleRate));}
        args.add(output.getAbsolutePath());CountDownLatch done=new CountDownLatch(1);AtomicReference<FFmpegSession> result=new AtomicReference<>();FFmpegSession session=FFmpegKit.executeWithArgumentsAsync(args.toArray(new String[0]),s->{result.set(s);done.countDown();},null,statistics->{int p=(int)Math.min(99,statistics.getTime()*100/Math.max(1,durationMs));listener.onProgress(p);});
        while(!done.await(300,TimeUnit.MILLISECONDS)){if(cancelled.get()){FFmpegKit.cancel(session.getSessionId());throw new InterruptedException("تم إلغاء العملية");}}
        FFmpegSession finished=result.get();if(finished==null||!ReturnCode.isSuccess(finished.getReturnCode())){if(output.exists())output.delete();String log=finished==null?"":shortFailure(finished.getAllLogsAsString());throw new IllegalStateException("فشل محرك الصيغ"+(log.isEmpty()?"":" — "+log));}
        listener.onProgress(100);
    }

    void adjustVideoAudio(Uri input,File output,String profile,long durationMs,int bitrateKbps,int sampleRate,Listener listener)throws Exception{String source="file".equals(input.getScheme())?input.getPath():FFmpegKitConfig.getSafParameterForRead(context,input);ArrayList<String> args=new ArrayList<>();args.add("-y");args.add("-i");args.add(source);args.add("-map");args.add("0:v:0");args.add("-map");args.add("0:a?");args.add("-c:v");args.add("copy");args.add("-c:a");if(profile.startsWith("WEBM"))args.add("libopus");else args.add("aac");args.add("-b:a");args.add(Math.max(64,bitrateKbps)+"k");if(sampleRate>0){args.add("-ar");args.add(String.valueOf(sampleRate));}args.add(output.getAbsolutePath());execute(args,output,durationMs,listener);}

    void mergeWithFade(List<Uri> inputs,File output,long[] durationsMs,double fadeSeconds,Listener listener)throws Exception{
        ArrayList<String> args=new ArrayList<>();args.add("-y");for(Uri input:inputs){args.add("-i");args.add("file".equals(input.getScheme())?input.getPath():FFmpegKitConfig.getSafParameterForRead(context,input));}
        StringBuilder filter=new StringBuilder();double offset=Math.max(.1,durationsMs[0]/1000d-fadeSeconds);String video="0:v",audio="0:a";for(int i=1;i<inputs.size();i++){String nextVideo="v"+i,nextAudio="a"+i;filter.append('[').append(video).append("][").append(i).append(":v]xfade=transition=fade:duration=").append(fadeSeconds).append(":offset=").append(String.format(java.util.Locale.US,"%.3f",offset)).append('[').append(nextVideo).append("];[").append(audio).append("][").append(i).append(":a]acrossfade=d=").append(fadeSeconds).append('[').append(nextAudio).append("];");video=nextVideo;audio=nextAudio;offset+=Math.max(.1,durationsMs[i]/1000d-fadeSeconds);}
        if(filter.length()>0)filter.setLength(filter.length()-1);args.add("-filter_complex");args.add(filter.toString());args.add("-map");args.add("["+video+"]");args.add("-map");args.add("["+audio+"]");args.add("-c:v");args.add("mpeg4");args.add("-q:v");args.add("3");args.add("-c:a");args.add("aac");args.add("-b:a");args.add("192k");args.add("-movflags");args.add("+faststart");args.add(output.getAbsolutePath());
        long total=0;for(long duration:durationsMs)total+=duration;total-=Math.round((inputs.size()-1)*fadeSeconds*1000);execute(args,output,Math.max(1,total),listener);
    }

    void convertAudio(Uri input,File output,String format,int bitrateKbps,int sampleRate,long startMs,long endMs,float gain,Listener listener)throws Exception{
        String source="file".equals(input.getScheme())?input.getPath():FFmpegKitConfig.getSafParameterForRead(context,input);ArrayList<String> args=new ArrayList<>();args.add("-y");args.add("-i");args.add(source);args.add("-ss");args.add(String.format(java.util.Locale.US,"%.3f",startMs/1000d));args.add("-to");args.add(String.format(java.util.Locale.US,"%.3f",endMs/1000d));args.add("-vn");args.add("-af");args.add(String.format(java.util.Locale.US,"volume=%.2f",gain));if(sampleRate>0){args.add("-ar");args.add(String.valueOf(sampleRate));}args.add("-b:a");args.add(bitrateKbps+"k");if("AAC".equals(format)){args.add("-c:a");args.add("aac");args.add("-f");args.add("adts");}else if("M4A".equals(format)){args.add("-c:a");args.add("aac");args.add("-f");args.add("mp4");}else{args.add("-c:a");args.add("libvorbis");args.add("-f");args.add("ogg");}args.add(output.getAbsolutePath());execute(args,output,Math.max(1,endMs-startMs),listener);
    }

    void studioEdit(Uri input,File output,long startMs,long endMs,int rotation,boolean mirror,float speed,int visual,int audioMode,boolean stripMetadata,Listener listener)throws Exception{
        String source="file".equals(input.getScheme())?input.getPath():FFmpegKitConfig.getSafParameterForRead(context,input);
        long inputDuration=Math.max(1000,endMs-startMs);float safeSpeed=Math.max(.5f,Math.min(2f,speed));
        ArrayList<String> args=new ArrayList<>();args.add("-y");args.add("-ss");args.add(seconds(startMs));args.add("-i");args.add(source);args.add("-t");args.add(seconds(inputDuration));args.add("-map");args.add("0:v:0");if(audioMode!=3){args.add("-map");args.add("0:a?");}
        ArrayList<String> vf=new ArrayList<>();if(rotation==90)vf.add("transpose=1");else if(rotation==-90)vf.add("transpose=2");else if(rotation==180){vf.add("hflip");vf.add("vflip");}if(mirror)vf.add("hflip");if(visual==1)vf.add("eq=brightness=0.05:contrast=1.06");else if(visual==2)vf.add("eq=saturation=1.28:contrast=1.08");else if(visual==3){vf.add("atadenoise=0a=0.02:0b=0.04");vf.add("unsharp=5:5:0.6");}if(Math.abs(safeSpeed-1f)>.001f)vf.add(String.format(java.util.Locale.US,"setpts=PTS/%.3f",safeSpeed));if(!vf.isEmpty()){args.add("-vf");args.add(join(vf));}
        int videoCodecAt=args.size();args.add("-c:v");args.add("h264_mediacodec");args.add("-b:v");args.add("6000000");
        if(audioMode==3)args.add("-an");else{ArrayList<String> af=new ArrayList<>();if(audioMode==1)af.add("afftdn=nf=-25");else if(audioMode==2){af.add("highpass=f=100");af.add("lowpass=f=8000");af.add("dynaudnorm");}if(Math.abs(safeSpeed-1f)>.001f)af.add(String.format(java.util.Locale.US,"atempo=%.3f",safeSpeed));if(!af.isEmpty()){args.add("-af");args.add(join(af));}args.add("-c:a");args.add("aac");args.add("-b:a");args.add("192k");}
        if(stripMetadata){args.add("-map_metadata");args.add("-1");args.add("-map_chapters");args.add("-1");}
        args.add("-movflags");args.add("+faststart");args.add(output.getAbsolutePath());
        long outputDuration=Math.max(1,(long)(inputDuration/safeSpeed));
        // Some phones reject FFmpeg's MediaCodec H.264 encoder; fall back to OpenH264, then MPEG-4 Part 2.
        String[][] encoders={{"h264_mediacodec","-b:v","6000000"},{"libopenh264","-b:v","6000000"},{"mpeg4","-q:v","3"}};
        Exception last=null;
        for(String[] encoder:encoders){
            ArrayList<String> attempt=new ArrayList<>(args);
            attempt.set(videoCodecAt+1,encoder[0]);attempt.set(videoCodecAt+2,encoder[1]);attempt.set(videoCodecAt+3,encoder[2]);
            try{runPass(attempt,outputDuration,listener,"فشل تطبيق تعديلات Pro");last=null;break;}
            catch(InterruptedException cancelledError){if(output.exists())output.delete();throw cancelledError;}
            catch(Exception failed){last=failed;if(output.exists())output.delete();if(cancelled.get())throw new InterruptedException("تم إلغاء العملية");}
        }
        if(last!=null)throw last;
        listener.onProgress(100);
    }

    private String seconds(long valueMs){return String.format(java.util.Locale.US,"%.3f",valueMs/1000d);}
    private String join(List<String> values){StringBuilder out=new StringBuilder();for(String value:values){if(out.length()>0)out.append(',');out.append(value);}return out.toString();}
    private void runPass(ArrayList<String> args,long durationMs,Listener listener,String errorPrefix)throws Exception{
        CountDownLatch done=new CountDownLatch(1);AtomicReference<FFmpegSession> result=new AtomicReference<>();
        FFmpegSession session=FFmpegKit.executeWithArgumentsAsync(args.toArray(new String[0]),s->{result.set(s);done.countDown();},null,statistics->listener.onProgress((int)Math.min(99,statistics.getTime()*100/Math.max(1,durationMs))));
        while(!done.await(300,TimeUnit.MILLISECONDS)){if(cancelled.get()){FFmpegKit.cancel(session.getSessionId());throw new InterruptedException("تم إلغاء العملية");}}
        FFmpegSession finished=result.get();if(finished==null||!ReturnCode.isSuccess(finished.getReturnCode())){String log=finished==null?"":shortFailure(finished.getAllLogsAsString());throw new IllegalStateException(errorPrefix+(log.isEmpty()?"":" — "+log));}
    }

    private String shortFailure(String log){if(log==null)return "";String[] lines=log.split("\\r?\\n");for(int i=lines.length-1;i>=0;i--){String line=lines[i].trim();if(line.isEmpty()||line.contains("configuration:")||line.startsWith("--enable-")||line.startsWith("libav")||line.startsWith("built with"))continue;return line.length()>180?line.substring(0,180):line;}return "";}

    private void execute(ArrayList<String> args,File output,long durationMs,Listener listener)throws Exception{CountDownLatch done=new CountDownLatch(1);AtomicReference<FFmpegSession> result=new AtomicReference<>();FFmpegSession session=FFmpegKit.executeWithArgumentsAsync(args.toArray(new String[0]),s->{result.set(s);done.countDown();},null,statistics->listener.onProgress((int)Math.min(99,statistics.getTime()*100/Math.max(1,durationMs))));while(!done.await(300,TimeUnit.MILLISECONDS)){if(cancelled.get()){FFmpegKit.cancel(session.getSessionId());throw new InterruptedException("تم إلغاء العملية");}}FFmpegSession finished=result.get();if(finished==null||!ReturnCode.isSuccess(finished.getReturnCode())){if(output.exists())output.delete();String log=finished==null?"":shortFailure(finished.getAllLogsAsString());throw new IllegalStateException("فشلت معالجة FFmpeg"+(log.isEmpty()?"":" — "+log));}listener.onProgress(100);}
}
