package com.mohannadsatlink.videox;

import android.app.Activity;
import android.Manifest;
import android.content.ClipData;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    /** Normal videos plus old formats (RMVB/RM, FLV, WMV/ASF, MKV, VOB) that phones often label oddly. */
    static final String[] VIDEO_MIME_TYPES={"video/*","application/vnd.rn-realmedia","application/vnd.rn-realmedia-vbr","application/x-flash-video","application/x-matroska","application/x-mpegURL","application/vnd.ms-asf","application/octet-stream"};
    private static final int PICK_ONE = 201, PICK_MANY = 202, SAVE_OLD = 203;
    private static final int BG = Color.rgb(7, 11, 22), CARD = Color.rgb(16, 25, 44);
    private static final int BLUE = Color.rgb(35, 142, 255), VIOLET = Color.rgb(140, 65, 255);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private FrameLayout root;
    private Uri selectedUri;
    private VideoInfo info;
    private String pendingMode = "ضغط";
    private File lastOutput;
    private File pendingExport;
    private boolean homeScreen;
    private boolean processing;
    private boolean serviceJob;
    private boolean receiverRegistered;
    private TextureView editorPreview;
    private MediaPlayer editorPlayer;
    private Surface editorSurface;
    private boolean editorPlayerReady;
    private Runnable playerTicker;
    private volatile int editorGeneration;
    private final BroadcastReceiver statusReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){String state=intent.getStringExtra("state");int p=intent.getIntExtra("progress",0);if("running".equals(state))updateProgress(p);else if("done".equals(state)){ArrayList<String> paths=ProcessingService.completedPaths;String path=ProcessingService.completedPath;long original=ProcessingService.completedOriginalSize;ProcessingService.completedPaths=null;ProcessingService.completedPath=null;serviceJob=false;if(paths!=null&&!paths.isEmpty())showBatchResult(paths);else if(path!=null)showResult(new File(path),original);}else if("error".equals(state)||"cancelled".equals(state)){serviceJob=false;toast("error".equals(state)?intent.getStringExtra("error"):"تم إلغاء العملية");if("دفعة".equals(pendingMode)||"محرر".equals(pendingMode))showHome();else if("تحرير".equals(pendingMode))showStudioEditPageSafely();else showEditorSafely();}}};

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root = new FrameLayout(this); root.setBackgroundColor(BG); setContentView(root);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},501);
        Uri incoming = incomingUri(getIntent());
        if(handleEditorIntent(getIntent()))return;
        if(ProcessingService.running){serviceJob=true;showProgress(ProcessingService.currentLabel);updateProgress(ProcessingService.currentProgress);}
        else if (incoming != null) loadVideo(incoming, "ضغط"); else showHome();
    }

    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter(ProcessingService.ACTION_STATUS);if(Build.VERSION.SDK_INT>=33)registerReceiver(statusReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(statusReceiver,f);receiverRegistered=true;if(!ProcessingService.running&&ProcessingService.completedPaths!=null){ArrayList<String> paths=ProcessingService.completedPaths;ProcessingService.completedPaths=null;ProcessingService.completedPath=null;showBatchResult(paths);}else if(!ProcessingService.running&&ProcessingService.completedPath!=null){String path=ProcessingService.completedPath;long original=ProcessingService.completedOriginalSize;ProcessingService.completedPath=null;showResult(new File(path),original);}}
    @Override protected void onStop(){if(receiverRegistered){unregisterReceiver(statusReceiver);receiverRegistered=false;}super.onStop();}

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); if (handleEditorIntent(intent)) return; Uri incoming = incomingUri(intent); if (incoming != null) loadVideo(incoming, "ضغط");
    }

    private Uri incomingUri(Intent i) {
        if (Intent.ACTION_SEND.equals(i.getAction())) return i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (Intent.ACTION_VIEW.equals(i.getAction())) return i.getData();
        return null;
    }

    private void showHome() {
        homeScreen = true; processing = false;
        pendingMode = "ضغط";
        LinearLayout page = page();
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this); icon.setImageResource(com.mohannadsatlink.videox.R.drawable.app_icon);
        header.addView(icon, size(58,58));
        LinearLayout names = column(); names.setPadding(dp(10),0,0,0);
        LinearLayout brandLine=row();brandLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView appName=label("VideoX Compressor",22,Color.WHITE,true);appName.setSingleLine(true);brandLine.addView(appName);
        TextView pro=label(" PRO ",12,Color.WHITE,true);pro.setGravity(Gravity.CENTER);pro.setPadding(dp(7),dp(3),dp(7),dp(3));pro.setBackground(round(VIOLET,8,Color.rgb(190,130,255)));LinearLayout.LayoutParams proParams=new LinearLayout.LayoutParams(-2,-2);proParams.leftMargin=dp(7);brandLine.addView(pro,proParams);names.addView(brandLine);
        TextView developer=label("المطور: مهند سات لنك",13,Color.rgb(150,170,205),false);developer.setSingleLine(true);names.addView(developer);
        header.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1));
        page.addView(header);
        page.addView(space(14));

        TextView hero = label("ضغط قوي مع الحفاظ على نقاوة الفيديو", 20, Color.WHITE, true);
        hero.setGravity(Gravity.CENTER); page.addView(hero);
        TextView sub = label("كل المعالجة تتم داخل جهازك بدون رفع الفيديو", 14, Color.rgb(151,170,205), false);
        sub.setGravity(Gravity.CENTER); page.addView(sub);
        page.addView(space(14));

        View compressTool = tool("◫", "ضغط وتحويل", "حجم أصغر بجودة عالية • MP4 • MKV • MP3", v -> pick(false,"ضغط"));
        page.addView(compressTool, match()); page.addView(space(12));
        LinearLayout tools1 = row();
        tools1.addView(tool("✂", "محرر الفيديو", "قص • حذف جزء • دمج • سرعة", v -> openEditor(false)), weight());
        tools1.addView(tool("♫", "محرر الصوت", "قص ودمج MP3 • تحويل فيديو لصوت", v -> openEditor(true)), weightMargin());
        page.addView(tools1); page.addView(space(12));
        LinearLayout tools2 = row();
        tools2.addView(tool("≡", "معالجة دفعة", "ضغط عدة فيديوهات", v -> pick(true,"دفعة")), weight());
        tools2.addView(tool("✦", "أدوات Pro", "تدوير • تحسين • خصوصية", v -> pick(false,"تحرير")), weightMargin());
        page.addView(tools2); page.addView(space(16));

        Button history = secondary("سجل الملفات"); history.setOnClickListener(v -> { homeScreen=false; showHistory(); }); page.addView(history, match());
        Button settings = secondary("الإعدادات ومعلومات التطبيق"); settings.setOnClickListener(v -> { homeScreen=false; showSettings(); }); page.addView(settings, matchTop(10));
        setPage(page);
    }

    private void openEditor(boolean audio) {
        if (ProcessingService.running) { toast("انتظر حتى تنتهي العملية الحالية"); return; }
        startActivity(new Intent(this, EditorActivity.class).putExtra(EditorActivity.EXTRA_AUDIO, audio));
    }

    /** Results/progress handed over from {@link EditorActivity}. */
    private boolean handleEditorIntent(Intent intent) {
        if (intent == null) return false;
        String result = intent.getStringExtra("resultPath");
        if (result != null) {
            intent.removeExtra("resultPath");
            serviceJob = false;
            ProcessingService.completedPath = null;
            showResult(new File(result), 0);
            return true;
        }
        if (intent.getBooleanExtra("showRunning", false)) {
            intent.removeExtra("showRunning");
            if (ProcessingService.running) {
                serviceJob = true; pendingMode = "محرر";
                showProgress(ProcessingService.currentLabel); updateProgress(ProcessingService.currentProgress);
                return true;
            }
        }
        return false;
    }

    private View tool(String glyph, String title, String subtitle, View.OnClickListener click) {
        LinearLayout box = card(); box.setGravity(Gravity.CENTER); box.setMinimumHeight(dp(126)); box.setPadding(dp(10),dp(14),dp(10),dp(14)); box.setOnClickListener(click); box.setClickable(true);
        TextView g = label(glyph, 34, Color.rgb(96,175,255), true); g.setGravity(Gravity.CENTER); box.addView(g);
        TextView t = label(title, 17, Color.WHITE, true); t.setGravity(Gravity.CENTER); box.addView(t,matchTop(3));
        TextView s = label(subtitle, 13, Color.rgb(170,188,218), false); s.setGravity(Gravity.CENTER); box.addView(s,matchTop(4));
        return box;
    }

    private void pick(boolean many, String mode) {
        pendingMode = mode;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, VIDEO_MIME_TYPES).addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (many) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, many ? PICK_MANY : PICK_ONE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data); if (result != RESULT_OK || data == null) return;
        if (request == SAVE_OLD) {
            Uri target = data.getData(); File source = pendingExport;
            if (target != null && source != null) copyToUri(source, target);
            return;
        }
        if (request == PICK_ONE) {
            Uri uri = data.getData(); if (uri != null) { persist(uri,data); loadVideo(uri,pendingMode); }
        } else if (request == PICK_MANY) {
            ArrayList<Uri> list = new ArrayList<>(); ClipData clip = data.getClipData();
            if (clip != null) for (int n=0;n<clip.getItemCount();n++) { Uri u=clip.getItemAt(n).getUri(); persist(u,data); list.add(u); }
            else if (data.getData()!=null) list.add(data.getData());
            if (list.isEmpty()) toast("اختر فيديو واحداً على الأقل");
            else if(list.size()<2)toast("اختر مقطعين على الأقل");
            else if("دفعة".equals(pendingMode))showBatchPage(list);else showMergeOrder(list);
        }
    }


    private void persist(Uri uri, Intent data) {
        try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
    }

    private void loadVideo(Uri uri, String mode) {
        if (uri == null) { toast("لم يتم اختيار فيديو"); return; }
        selectedUri = uri;
        final String requestedMode = mode == null ? "ضغط" : mode;
        pendingMode = requestedMode;
        worker.execute(() -> {
            try {
                VideoInfo loaded = new VideoInfo(getApplicationContext(), uri);
                ui.post(() -> {
                    if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                    info = loaded;
                    pendingMode = requestedMode;
                    if ("صوت".equals(requestedMode)) showAudioPageSafely();
                    else if ("تحرير".equals(requestedMode)) showStudioEditPageSafely();
                    else showEditorSafely();
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                    toast("تعذر قراءة الفيديو: " + clean(e));
                    showHome();
                });
            }
        });
    }

    private void showEditorSafely() {
        try {
            if (info == null || selectedUri == null) throw new IllegalStateException("بيانات الفيديو غير جاهزة");
            showEditor();
        } catch (Throwable e) {
            stopPlayerTicker();
            toast("تعذر فتح إعدادات الفيديو: " + clean(e));
            showHome();
        }
    }

    private void showAudioPageSafely() {
        try {
            if (info == null || selectedUri == null) throw new IllegalStateException("بيانات الفيديو غير جاهزة");
            showAudioPage();
        } catch (Throwable e) {
            toast("تعذر فتح إعدادات الصوت: " + clean(e));
            showHome();
        }
    }

    private void showStudioEditPageSafely() {
        try {
            if (info == null || selectedUri == null) throw new IllegalStateException("بيانات الفيديو غير جاهزة");
            showStudioEditPage();
        } catch (Throwable e) {
            toast("تعذر فتح أدوات Pro: " + clean(e));
            showHome();
        }
    }

    private void showStudioEditPage() {
        homeScreen=false; processing=false;
        LinearLayout p=page(); p.addView(titleBar("أدوات تعديل Pro",this::showHome));
        LinearLayout file=card(); file.addView(label(info.name,17,Color.WHITE,true));
        file.addView(label(info.details(),13,Color.rgb(151,170,205),false),matchTop(5));
        file.addView(label(info.technicalDetails(),13,Color.rgb(94,205,137),true),matchTop(6)); p.addView(file,matchTop(12));

        p.addView(section("الصورة والحركة"));
        String[] rotationNames={"بدون تدوير","تدوير 90° لليمين","تدوير 90° لليسار","تدوير 180°"};
        int[] rotationValues={0,90,-90,180}; Spinner rotation=styledSpinner(rotationNames); p.addView(rotation);
        CheckBox mirror=new CheckBox(this); mirror.setText("عكس الفيديو أفقياً"); mirror.setTextColor(Color.WHITE); mirror.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE)); p.addView(mirror,matchTop(8));
        String[] speedNames={"السرعة: 0.50×","السرعة: 0.75×","السرعة: 1.00× — الأصلية","السرعة: 1.25×","السرعة: 1.50×","السرعة: 2.00×"};
        float[] speedValues={.5f,.75f,1f,1.25f,1.5f,2f}; Spinner speed=styledSpinner(speedNames); speed.setSelection(2); p.addView(speed,matchTop(8));
        String[] visualNames={"الصورة: أصلية","إضاءة تلقائية","ألوان حيوية","إزالة تشويش + زيادة الحدة"};
        Spinner visual=styledSpinner(visualNames); p.addView(visual,matchTop(8));

        p.addView(section("الصوت والخصوصية"));
        String[] audioNames={"الصوت: أصلي","تنظيف الضوضاء","وضوح الحوار","كتم الصوت بالكامل"};
        Spinner audio=styledSpinner(audioNames); p.addView(audio);
        CheckBox privacy=new CheckBox(this); privacy.setText("حذف الموقع وبيانات الكاميرا والـ Metadata"); privacy.setTextColor(Color.WHITE); privacy.setChecked(true); privacy.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE)); p.addView(privacy,matchTop(8));

        p.addView(section("قص اختياري بدقة"));
        LinearLayout range=row(); EditText start=numberField("البداية بالثواني"); EditText end=numberField("النهاية بالثواني");
        range.addView(start,weight()); range.addView(end,weightMargin()); p.addView(range);
        p.addView(label("الناتج MP4/H.264 عالي التوافق. تُطبّق الخيارات فعلياً بمحرك FFmpeg داخل الجهاز.",12,Color.rgb(151,170,205),false),matchTop(12));
        Button go=action("✦ بدء التعديل Pro",VIOLET); go.setOnClickListener(v->{
            long startMs=parseSeconds(start,0),endMs=parseSeconds(end,info.durationMs);
            if(startMs<0||endMs<=startMs||endMs>info.durationMs+1000){toast("تحقق من وقت البداية والنهاية");return;}
            startStudioEdit(rotationValues[rotation.getSelectedItemPosition()],mirror.isChecked(),speedValues[speed.getSelectedItemPosition()],visual.getSelectedItemPosition(),audio.getSelectedItemPosition(),privacy.isChecked(),startMs,endMs);
        }); p.addView(go,matchTop(20)); setPage(p);
    }

    private void startStudioEdit(int rotation,boolean mirror,float speed,int visual,int audio,boolean stripMetadata,long startMs,long endMs){
        cancelled.set(false); serviceJob=true; File dir=getExternalFilesDir("Movies"); if(dir==null)dir=new File(getFilesDir(),"Movies"); dir.mkdirs();
        String base=info.name.replaceAll("(?i)\\.[a-z0-9]{2,5}$","").replaceAll("[^a-zA-Z0-9._\u0600-\u06ff-]+","_");
        File output=new File(dir,base+"_VideoX_Studio_"+System.currentTimeMillis()+".mp4"); showProgress("جاري تطبيق تعديلات Pro…");
        Intent job=new Intent(this,ProcessingService.class).setAction(ProcessingService.ACTION_START).putExtra("jobKind","studio").putExtra("input",selectedUri.toString()).putExtra("output",output.getAbsolutePath()).putExtra("original",info.size).putExtra("label","جاري تطبيق تعديلات Pro…").putExtra("rotation",rotation).putExtra("mirror",mirror).putExtra("speed",speed).putExtra("visual",visual).putExtra("studioAudio",audio).putExtra("stripMetadata",stripMetadata).putExtra("start",startMs).putExtra("end",endMs);
        if(Build.VERSION.SDK_INT>=26)startForegroundService(job);else startService(job);
    }

    private void showEditor() {
        homeScreen = false; processing = false;
        boolean trimMode = "قص".equals(pendingMode);
        boolean convertMode = "تحويل".equals(pendingMode);
        String editorTitle = trimMode ? "قص الفيديو" : convertMode ? "تحويل الفيديو" : "ضغط وتحويل";
        LinearLayout page = page(); page.addView(titleBar(editorTitle, this::showHome)); page.addView(space(10));
        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        TextureView preview = new TextureView(this); preview.setOpaque(true);
        previewFrame.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(previewFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight()));
        TextView previewStatus=label("جاري تجهيز معاينة الفيديو…",13,Color.rgb(151,170,205),false);previewStatus.setGravity(Gravity.CENTER);page.addView(previewStatus,matchTop(5));

        LinearLayout playerCard=card(); TextView playTime=label("00:00 / "+VideoInfo.time(info.durationMs),14,Color.WHITE,true);playTime.setGravity(Gravity.CENTER);playerCard.addView(playTime);
        SeekBar playback=new SeekBar(this);playback.setMax((int)Math.max(1,Math.min(Integer.MAX_VALUE,info.durationMs)));playerCard.addView(playback,matchTop(6));
        LinearLayout transport=row();Button rewind=secondary("−10 ث");Button playPause=action("▶ تشغيل",BLUE);Button forward=secondary("+10 ث");transport.addView(rewind,weight());transport.addView(playPause,weightMargin());transport.addView(forward,weightMargin());playerCard.addView(transport,matchTop(6));
        page.addView(playerCard,matchTop(8));
        LinearLayout file = card(); TextView n = label(info.name,17,Color.WHITE,true); n.setSingleLine(); file.addView(n); file.addView(label(info.details(),13,Color.rgb(150,171,205),false));file.addView(label(info.technicalDetails(),13,Color.rgb(94,205,137),true),matchTop(6));
        page.addView(file, matchTop(12));

        if (!trimMode) page.addView(section(convertMode ? "جودة التحويل" : "مستوى الضغط"));
        LinearLayout presets = row(); final VideoTranscoder.Preset[] chosen={VideoTranscoder.Preset.MEDIUM}; final Button[] pbs=new Button[3];
        String[] pn={"ضغط قوي\nحجم 35%","متوازن\nحجم 55%","نقاوة أعلى\nحجم 75%"}; VideoTranscoder.Preset[] pv=VideoTranscoder.Preset.values();
        for(int i=0;i<3;i++){int x=i; pbs[i]=chip(pn[i],i==1); pbs[i].setMinHeight(dp(64)); pbs[i].setOnClickListener(v->{chosen[0]=pv[x];for(int j=0;j<3;j++)styleChip(pbs[j],j==x);}); presets.addView(pbs[i],i==0?weight():weightMargin());}
        if (!trimMode) page.addView(presets);

        TextView trimSection=section("نطاق القص");page.addView(trimSection);
        LinearLayout trim=card(); TextView times=label("00:00  —  "+VideoInfo.time(info.durationMs),15,Color.WHITE,true); times.setGravity(Gravity.CENTER); trim.addView(times);
        TextView trimHint=label("تظهر لقطات الفيديو هنا — اسحب المقبضين لتحديد الجزء",12,Color.rgb(151,170,205),false);trimHint.setGravity(Gravity.CENTER);trim.addView(trimHint,matchTop(5));
        VideoTrimBar trimBar=new VideoTrimBar(this);trim.addView(trimBar,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(104)));page.addView(trim);
        LinearLayout markButtons=row();Button markStart=secondary("تعيين الحالية كبداية");Button markEnd=secondary("تعيين الحالية كنهاية");markButtons.addView(markStart,weight());markButtons.addView(markEnd,weightMargin());trim.addView(markButtons,matchTop(8));
        final long[] mediaDuration={Math.max(1,info.durationMs)};
        trimBar.setListener((rangeStart,rangeEnd,fromUser)->{long a=(long)(mediaDuration[0]*rangeStart),b=(long)(mediaDuration[0]*rangeEnd);times.setText(VideoInfo.timePrecise(a)+"  —  "+VideoInfo.timePrecise(b));if(fromUser)seekEditor(Math.abs(editorPosition()-a)<=Math.abs(editorPosition()-b)?a:b);});
        if(!trimMode){trimSection.setVisibility(View.GONE);trim.setVisibility(View.GONE);}

        final boolean[] playbackDragging={false};
        playback.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean fromUser){if(fromUser){seekEditor(p);playTime.setText(VideoInfo.time(p)+" من "+VideoInfo.time(mediaDuration[0]));}}public void onStartTrackingTouch(SeekBar s){playbackDragging[0]=true;}public void onStopTrackingTouch(SeekBar s){playbackDragging[0]=false;seekEditor(s.getProgress());}});
        playPause.setEnabled(false);playPause.setAlpha(.55f);playPause.setOnClickListener(v->{if(!editorPlayerReady||editorPlayer==null)return;if(isEditorPlaying()){editorPlayer.pause();playPause.setText("▶ تشغيل");}else{long endMs=(long)(mediaDuration[0]*trimBar.getEnd());if(editorPosition()>=endMs-200)seekEditor((long)(mediaDuration[0]*trimBar.getStart()));editorPlayer.start();playPause.setText("❚❚ إيقاف");}});
        preview.setOnClickListener(v->playPause.performClick());
        rewind.setOnClickListener(v->seekEditor(Math.max(0,editorPosition()-10000L)));
        forward.setOnClickListener(v->seekEditor(Math.min(mediaDuration[0],editorPosition()+10000L)));
        markStart.setOnClickListener(v->trimBar.setStart(editorPosition()/(float)Math.max(1,mediaDuration[0])));
        markEnd.setOnClickListener(v->trimBar.setEnd(editorPosition()/(float)Math.max(1,mediaDuration[0])));

        CheckBox audio=new CheckBox(this); audio.setText("الاحتفاظ بالصوت الأصلي"); audio.setTextColor(Color.WHITE); audio.setChecked(true); audio.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE)); page.addView(audio,matchTop(12));
        TextView advancedSection=section(trimMode?"إعدادات القص":"إعدادات احترافية");page.addView(advancedSection);
        String[] formatNames={"MP4 — H.264 (يشتغل بكل مكان)","MP4 — H.265 (أصغر حجماً)","3GP — H.264","WebM — VP8","WebM — VP9","MKV — Matroska","MOV — QuickTime","AVI — MPEG-4/MP3","MP4 — بدون إعادة ضغط (سريع، نفس الجودة)","MKV — بدون إعادة ضغط (سريع، نفس الجودة)","MOV — بدون إعادة ضغط (سريع، نفس الجودة)","صوت فقط — MP3","صوت فقط — M4A (AAC)"};
        String[] formatValues={"MP4_AVC","MP4_HEVC","3GP_AVC","WEBM_VP8","WEBM_VP9","FFMPEG_MKV","FFMPEG_MOV","FFMPEG_AVI","REMUX_MP4","REMUX_MKV","REMUX_MOV","AUDIO_MP3","AUDIO_M4A"};
        Spinner outputFormat=styledSpinner(formatNames);page.addView(outputFormat);
        TextView targetLabel=label("الحجم الذكي المستهدف",15,Color.WHITE,true);page.addView(targetLabel,matchTop(12));
        EditText targetMb=new EditText(this);targetMb.setHint("اكتب الحجم المطلوب بالميغابايت — اختياري");targetMb.setHintTextColor(Color.GRAY);targetMb.setTextColor(Color.WHITE);targetMb.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);targetMb.setBackground(round(CARD,14,Color.rgb(48,72,110)));targetMb.setPadding(dp(14),dp(12),dp(14),dp(12));page.addView(targetMb,matchTop(7));
        LinearLayout targetChips=row();String[] targetNames={"حجم 35%","حجم 55%","حجم 75%","يدوي"};double[] targetRatios={.35,.55,.75,0};Button[] targetButtons=new Button[targetNames.length];for(int i=0;i<targetNames.length;i++){int x=i;targetButtons[i]=chip(targetNames[i],i==1);targetButtons[i].setTextSize(12);targetButtons[i].setOnClickListener(v->{targetMb.setText(x==3?"":String.format(Locale.US,"%.1f",info.size*targetRatios[x]/1024d/1024d));for(int j=0;j<targetButtons.length;j++)if(targetButtons[j]!=null)styleChip(targetButtons[j],j==x);});targetChips.addView(targetButtons[i],i==0?weight():weightMargin());}page.addView(targetChips,matchTop(7));if(!trimMode&&!convertMode&&info.size>0)targetMb.setText(String.format(Locale.US,"%.1f",info.size*.55/1024d/1024d));
        String[] profileNames={"PRO ذكي — إعداد يدوي","واتساب سريع — 720p","تيليغرام متوازن — 1080p","جودة عالية — 1080p/60"};Spinner profiles=styledSpinner(profileNames);page.addView(profiles,matchTop(10));
        String[] resolutionNames={"الدقة: الأصلية","الدقة: 240p","الدقة: 360p","الدقة: 480p","الدقة: 720p","الدقة: 1080p","الدقة: 2K 1440p","الدقة: 4K 2160p"};int[] resolutionValues={0,240,360,480,720,1080,1440,2160};Spinner resolution=styledSpinner(resolutionNames);resolution.setSelection(0);page.addView(resolution,matchTop(10));
        String[] bitrateNames={"Video Bitrate: تلقائي","Video: 500 kbps","Video: 800 kbps","Video: 1000 kbps","Video: 1500 kbps","Video: 2000 kbps","Video: 3000 kbps","Video: 4000 kbps","Video: 6000 kbps","Video: 8000 kbps","Video: 12000 kbps","Video: 20000 kbps"};int[] bitrateValues={0,500,800,1000,1500,2000,3000,4000,6000,8000,12000,20000};Spinner videoBitrate=styledSpinner(bitrateNames);videoBitrate.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?>a,View v,int position,long id){if(position>0)targetMb.setText("");}public void onNothingSelected(android.widget.AdapterView<?>a){}});page.addView(videoBitrate,matchTop(10));
        String[] fpsNames={"الفريمات: الأصلية","15 FPS","24 FPS","25 FPS","30 FPS","50 FPS","60 FPS","90 FPS","120 FPS"};int[] fpsValues={0,15,24,25,30,50,60,90,120};Spinner fps=styledSpinner(fpsNames);page.addView(fps,matchTop(10));
        String[] videoAudioBitrateNames={"جودة الصوت: 64 kbps","جودة الصوت: 96 kbps","جودة الصوت: 128 kbps","جودة الصوت: 192 kbps","جودة الصوت: 256 kbps","جودة الصوت: 320 kbps"};int[] videoAudioBitrateValues={64,96,128,192,256,320};Spinner videoAudioBitrate=styledSpinner(videoAudioBitrateNames);videoAudioBitrate.setSelection(2);page.addView(videoAudioBitrate,matchTop(10));
        String[] videoSampleNames={"معدل عينة الصوت: الأصلي","32000 Hz","44100 Hz","48000 Hz"};int[] videoSampleValues={0,32000,44100,48000};Spinner videoSampleRate=styledSpinner(videoSampleNames);page.addView(videoSampleRate,matchTop(10));
        profiles.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?>a,View v,int position,long id){if(position==1){resolution.setSelection(4);videoBitrate.setSelection(4);fps.setSelection(4);outputFormat.setSelection(0);}else if(position==2){resolution.setSelection(5);videoBitrate.setSelection(6);fps.setSelection(4);}else if(position==3){resolution.setSelection(5);videoBitrate.setSelection(8);fps.setSelection(6);}}public void onNothingSelected(android.widget.AdapterView<?>a){}});
        Button savePreset=secondary("حفظ الإعدادات الحالية كقالب Pro");savePreset.setOnClickListener(v->{getPreferences(MODE_PRIVATE).edit().putInt("pro_resolution",resolution.getSelectedItemPosition()).putInt("pro_bitrate",videoBitrate.getSelectedItemPosition()).putInt("pro_fps",fps.getSelectedItemPosition()).putInt("pro_format",outputFormat.getSelectedItemPosition()).putInt("pro_audio_bitrate",videoAudioBitrate.getSelectedItemPosition()).putInt("pro_audio_rate",videoSampleRate.getSelectedItemPosition()).apply();toast("تم حفظ قالب Pro");});page.addView(savePreset,matchTop(10));
        int savedResolution=getPreferences(MODE_PRIVATE).getInt("pro_resolution",-1);if(savedResolution>=0){resolution.setSelection(Math.min(savedResolution,resolutionNames.length-1));videoBitrate.setSelection(Math.min(getPreferences(MODE_PRIVATE).getInt("pro_bitrate",0),bitrateNames.length-1));fps.setSelection(Math.min(getPreferences(MODE_PRIVATE).getInt("pro_fps",0),fpsNames.length-1));outputFormat.setSelection(Math.min(getPreferences(MODE_PRIVATE).getInt("pro_format",0),formatNames.length-1));videoAudioBitrate.setSelection(Math.min(getPreferences(MODE_PRIVATE).getInt("pro_audio_bitrate",2),videoAudioBitrateNames.length-1));videoSampleRate.setSelection(Math.min(getPreferences(MODE_PRIVATE).getInt("pro_audio_rate",0),videoSampleNames.length-1));}
        if(savedResolution<0&&!convertMode&&!trimMode&&supportsEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC))outputFormat.setSelection(1);
        CheckBox smart=new CheckBox(this);smart.setText("تحسين ذكي: يخفض الدقة تلقائياً بدل ظهور تكسّر بالصورة");smart.setTextColor(Color.WHITE);smart.setChecked(getPreferences(MODE_PRIVATE).getBoolean("smart_quality",true));smart.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));smart.setOnCheckedChangeListener((b,on)->getPreferences(MODE_PRIVATE).edit().putBoolean("smart_quality",on).apply());if(!trimMode)page.addView(smart,matchTop(10));
        Spinner aspect=new Spinner(this);String[] aspects={"الأصلي","16:9","9:16","1:1"};ArrayAdapter<String> aspectAdapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,aspects);aspect.setAdapter(aspectAdapter);aspect.setBackground(round(CARD,14,Color.rgb(48,72,110)));page.addView(aspect,matchTop(10));
        TextView formatNote=label("الصيغ حقيقية مع اختيار الترميز، وليست تغييراً لامتداد الملف.",12,Color.rgb(151,170,205),false);page.addView(formatNote,matchTop(8));
        TextView estimate=label("الحجم المتوقع: " + estimate(chosen[0],info.durationMs),14,Color.rgb(94,205,137),true); estimate.setGravity(Gravity.CENTER); page.addView(estimate,matchTop(8));
        Button calculate=secondary("حساب Pro للحجم والجودة");calculate.setOnClickListener(v->{long duration=Math.max(1,(long)(info.durationMs*(trimBar.getEnd()-trimBar.getStart())));Double targetValue=parseTargetMb(targetMb);if(targetValue!=null&&(Double.isNaN(targetValue)||targetValue<=0)){toast("اكتب حجماً صحيحاً أكبر من صفر");return;}if(targetValue!=null){long bytes=(long)(targetValue*1024*1024);long videoRate=Math.max(0,bytes*8_000/duration-128_000);String warning=info.size>0&&bytes>=info.size?"  ⚠ اختر حجماً أصغر من الأصلي":videoRate<250_000?"  ⚠ الحجم صغير جداً لهذه المدة":"";estimate.setText("المستهدف: "+VideoInfo.bytes(bytes)+"  •  Video ≈ "+(videoRate/1000)+" kbps"+warning);}else{int kb=bitrateValues[videoBitrate.getSelectedItemPosition()];long rate=kb>0?(kb+128L)*1000:(chosen[0]==VideoTranscoder.Preset.LOW?828_000:chosen[0]==VideoTranscoder.Preset.MEDIUM?1_628_000:3_128_000);long expected=rate*duration/8000;estimate.setText("الحجم المتوقع: "+VideoInfo.bytes(expected)+(info.size>0&&expected>=info.size?"  ⚠ قد يصبح أكبر من الأصلي":""));}});page.addView(calculate,matchTop(8));
        for(int i=0;i<pbs.length;i++){int x=i;pbs[i].setOnClickListener(v->{chosen[0]=pv[x];for(int j=0;j<pbs.length;j++)styleChip(pbs[j],j==x);if(!convertMode&&info.size>0){double ratio=x==0?.35:x==1?.55:.75;targetMb.setText(String.format(Locale.US,"%.1f",info.size*ratio/1024d/1024d));resolution.setSelection(x==0?4:x==1?5:0);fps.setSelection(0);estimate.setText("الحجم المستهدف: "+VideoInfo.bytes((long)(info.size*ratio))+"  •  توفير تقريبي "+Math.round((1-ratio)*100)+"%");}else estimate.setText("الحجم المتوقع: "+estimate(chosen[0],info.durationMs));});}
        if(!trimMode){
            // Advanced settings are folded away so the screen stays simple.
            View[] advanced={profiles,resolution,videoBitrate,fps,videoAudioBitrate,videoSampleRate,savePreset,aspect,formatNote,calculate};
            Button more=secondary("إعدادات متقدمة ▾");
            boolean open=getPreferences(MODE_PRIVATE).getBoolean("advanced_open",false);
            for(View view:advanced)view.setVisibility(open?View.VISIBLE:View.GONE);
            more.setText(open?"إخفاء الإعدادات المتقدمة ▴":"إعدادات متقدمة ▾");
            more.setOnClickListener(v->{boolean show=advanced[0].getVisibility()!=View.VISIBLE;for(View view:advanced)view.setVisibility(show?View.VISIBLE:View.GONE);more.setText(show?"إخفاء الإعدادات المتقدمة ▴":"إعدادات متقدمة ▾");getPreferences(MODE_PRIVATE).edit().putBoolean("advanced_open",show).apply();});
            page.addView(more,page.indexOfChild(profiles),matchTop(10));
            outputFormat.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?>a,View v,int position,long id){String value=formatValues[position];if(profiles!=null)profiles.setEfficientCodec(value.endsWith("HEVC")||value.endsWith("VP9"));boolean copyOnly=value.startsWith("REMUX_")||value.startsWith("AUDIO_");presets.setAlpha(copyOnly?.4f:1f);targetMb.setEnabled(!copyOnly);targetChips.setAlpha(copyOnly?.4f:1f);smart.setEnabled(!copyOnly);if(value.startsWith("REMUX_"))estimate.setText("تغيير الصيغة فقط: نفس الجودة ونفس الحجم تقريباً، ويخلص بثواني");else if(value.startsWith("AUDIO_"))estimate.setText("سيتم استخراج الصوت فقط بالجودة المختارة بالإعدادات المتقدمة");}public void onNothingSelected(android.widget.AdapterView<?>a){}});
        }
        if(trimMode){advancedSection.setVisibility(View.GONE);outputFormat.setVisibility(View.GONE);targetLabel.setVisibility(View.GONE);targetMb.setVisibility(View.GONE);targetChips.setVisibility(View.GONE);profiles.setVisibility(View.GONE);resolution.setVisibility(View.GONE);videoBitrate.setVisibility(View.GONE);fps.setVisibility(View.GONE);videoAudioBitrate.setVisibility(View.GONE);videoSampleRate.setVisibility(View.GONE);savePreset.setVisibility(View.GONE);aspect.setVisibility(View.GONE);formatNote.setVisibility(View.GONE);estimate.setVisibility(View.GONE);calculate.setVisibility(View.GONE);}
        profiles=null;
        if(!trimMode&&!convertMode){
            int savedProfile=getPreferences(MODE_PRIVATE).getInt("profile",2);
            profiles=new CompressProfiles(this,info,savedProfile,targetMode->{targetLabel.setVisibility(targetMode?View.VISIBLE:View.GONE);targetMb.setVisibility(targetMode?View.VISIBLE:View.GONE);targetChips.setVisibility(targetMode?View.VISIBLE:View.GONE);getPreferences(MODE_PRIVATE).edit().putInt("profile",profiles.selected()).apply();});
            String fmt=formatValues[outputFormat.getSelectedItemPosition()];
            profiles.setEfficientCodec(fmt.endsWith("HEVC")||fmt.endsWith("VP9"));
            profiles.setAudioKbps(videoAudioBitrateValues[videoAudioBitrate.getSelectedItemPosition()]);
            presets.setVisibility(View.GONE);
            boolean targetNow=profiles.isTargetMode();
            targetLabel.setVisibility(targetNow?View.VISIBLE:View.GONE);targetMb.setVisibility(targetNow?View.VISIBLE:View.GONE);targetChips.setVisibility(targetNow?View.VISIBLE:View.GONE);
            page.addView(profiles.view(),page.indexOfChild(presets)+1);
            videoAudioBitrate.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?>a,View v,int position,long id){if(profiles!=null)profiles.setAudioKbps(videoAudioBitrateValues[position]);}public void onNothingSelected(android.widget.AdapterView<?>a){}});
            estimate.setText("الحجم المتوقع مكتوب جنب كل خيار");
        }
        Button go=action(trimMode?"✂ بدء القص":convertMode?"↻ بدء التحويل":"◫ بدء الضغط Pro",VIOLET); go.setOnClickListener(v->{if(profiles!=null&&!profiles.isTargetMode()){String pf=formatValues[outputFormat.getSelectedItemPosition()];if(!pf.startsWith("REMUX_")&&!pf.startsWith("AUDIO_")){smartQuality=false;profileScale=profiles.scalePercent();startTranscode(profiles.quality(),0,0,audio.isChecked(),aspects[aspect.getSelectedItemPosition()],0,pf,0,profiles.videoBitrate(),fpsValues[fps.getSelectedItemPosition()],videoAudioBitrateValues[videoAudioBitrate.getSelectedItemPosition()],videoSampleValues[videoSampleRate.getSelectedItemPosition()]);profileScale=0;return;}}long a=(long)(info.durationMs*trimBar.getStart()),b=(long)(info.durationMs*trimBar.getEnd());long target=0;String chosenFormat=trimMode?"MP4_AVC":formatValues[outputFormat.getSelectedItemPosition()];boolean copyOnly=chosenFormat.startsWith("REMUX_")||chosenFormat.startsWith("AUDIO_");Double targetValue=copyOnly?null:parseTargetMb(targetMb);if(targetValue!=null&&(Double.isNaN(targetValue)||targetValue<=0)){toast("اكتب حجماً صحيحاً أكبر من صفر");return;}if(targetValue!=null){target=(long)(targetValue*1024*1024);long videoRate=target*8_000/Math.max(1,b-a)-(audio.isChecked()?128_000:0);if(!convertMode&&info.size>0&&target>=info.size){toast("الحجم المستهدف يجب أن يكون أصغر من حجم الفيديو الأصلي");return;}if(videoRate<150_000){toast("الحجم المطلوب صغير جداً لهذه المدة؛ اختر حجماً أكبر");return;}}smartQuality=smart.isChecked();String selectedFormat=trimMode?"MP4_AVC":formatValues[outputFormat.getSelectedItemPosition()];startTranscode(chosen[0],a,b,audio.isChecked(),aspects[aspect.getSelectedItemPosition()],target,selectedFormat,resolutionValues[resolution.getSelectedItemPosition()],bitrateValues[videoBitrate.getSelectedItemPosition()]*1000,fpsValues[fps.getSelectedItemPosition()],videoAudioBitrateValues[videoAudioBitrate.getSelectedItemPosition()],videoSampleValues[videoSampleRate.getSelectedItemPosition()]);}); page.addView(go,matchTop(16));
        page.addView(label("قد يختلف الحجم النهائي حسب محتوى الفيديو وقدرات جهازك.",12,Color.GRAY,false),matchTop(10));
        setPage(page);
        int thumbnailGeneration=editorGeneration;
        if(trimMode)loadTrimThumbnails(trimBar,selectedUri,info.durationMs,thumbnailGeneration);
        editorPreview=preview;
        Runnable openPlayer=()->{if(editorPreview!=preview||!preview.isAvailable()||editorPlayer!=null)return;try{editorSurface=new Surface(preview.getSurfaceTexture());MediaPlayer mp=new MediaPlayer();editorPlayer=mp;mp.setDataSource(this,selectedUri);mp.setSurface(editorSurface);mp.setScreenOnWhilePlaying(true);mp.setOnVideoSizeChangedListener((player,w,h)->fitPreview(preview,w,h));mp.setOnPreparedListener(player->{if(editorPlayer!=player||editorPreview!=preview)return;editorPlayerReady=true;int duration=player.getDuration()>0?player.getDuration():(int)Math.min(Integer.MAX_VALUE,info.durationMs);mediaDuration[0]=Math.max(1,duration);playback.setMax((int)mediaDuration[0]);playTime.setText("00:00 من "+VideoInfo.time(mediaDuration[0]));times.setText("00:00.000  —  "+VideoInfo.timePrecise(mediaDuration[0]));previewStatus.setVisibility(View.GONE);playPause.setEnabled(true);playPause.setAlpha(1f);player.seekTo(1,MediaPlayer.SEEK_CLOSEST);playerTicker=new Runnable(){@Override public void run(){if(editorPreview!=preview||editorPlayer!=player)return;int current=editorPosition();long rangeEnd=(long)(mediaDuration[0]*trimBar.getEnd());if(isEditorPlaying()&&current>=rangeEnd){player.pause();seekEditor(rangeEnd);playPause.setText("▶ تشغيل");current=(int)rangeEnd;}if(!playbackDragging[0])playback.setProgress(Math.min(playback.getMax(),current));trimBar.setPlayhead(current/(float)Math.max(1,mediaDuration[0]));playTime.setText(VideoInfo.time(current)+" من "+VideoInfo.time(mediaDuration[0]));ui.postDelayed(this,250);}};ui.post(playerTicker);});mp.setOnCompletionListener(player->playPause.setText("▶ تشغيل"));mp.setOnErrorListener((player,what,extra)->{editorPlayerReady=false;playPause.setEnabled(false);playPause.setAlpha(.55f);previewStatus.setText("تعذر عرض المعاينة بسبب ترميز الفيديو في هذا الجهاز. يمكنك متابعة الضغط أو التحويل.");previewStatus.setTextColor(Color.rgb(255,170,80));return true;});mp.prepareAsync();}catch(Exception e){previewStatus.setText("تعذر فتح معاينة الفيديو: "+clean(e));previewStatus.setTextColor(Color.rgb(255,170,80));}};
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){public void onSurfaceTextureAvailable(SurfaceTexture surface,int width,int height){openPlayer.run();}public void onSurfaceTextureSizeChanged(SurfaceTexture surface,int width,int height){}public boolean onSurfaceTextureDestroyed(SurfaceTexture surface){stopPlayerTicker();return true;}public void onSurfaceTextureUpdated(SurfaceTexture surface){}});
        if(preview.isAvailable())openPlayer.run();
    }

    private boolean smartQuality=true;
    private int profileScale;
    private CompressProfiles profiles;
    /** Preview box follows the video's shape (tall for vertical videos), capped at half the screen. */
    private int previewHeight(){int screenW=getResources().getDisplayMetrics().widthPixels-dp(36),screenH=getResources().getDisplayMetrics().heightPixels;if(info==null||info.width<=0||info.height<=0)return dp(220);int h=Math.round(screenW*info.height/(float)info.width);return Math.max(dp(200),Math.min(h,Math.round(screenH*0.5f)));}

    private String estimate(VideoTranscoder.Preset p,long duration){long rate=p==VideoTranscoder.Preset.LOW?828_000:p==VideoTranscoder.Preset.MEDIUM?1_628_000:3_128_000;return VideoInfo.bytes(rate*Math.max(1,duration)/8000);}
    private Double parseTargetMb(EditText field){try{String value=field.getText().toString().trim();return value.isEmpty()?null:Double.parseDouble(value);}catch(Exception ignored){return Double.NaN;}}

    private void loadTrimThumbnails(VideoTrimBar bar,Uri uri,long durationMs,int generation){
        previewWorker.execute(()->{ArrayList<Bitmap> frames=new ArrayList<>();MediaMetadataRetriever retriever=new MediaMetadataRetriever();try{retriever.setDataSource(this,uri);int count=8;for(int i=0;i<count;i++){if(generation!=editorGeneration)break;long timeUs=Math.max(0,durationMs)*1000L*i/Math.max(1,count-1);Bitmap frame=retriever.getFrameAtTime(timeUs,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(frame!=null){Bitmap scaled=Bitmap.createScaledBitmap(frame,160,90,true);if(scaled!=frame)frame.recycle();frames.add(scaled);}}ui.post(()->{if(generation==editorGeneration&&bar.isAttachedToWindow())bar.setThumbnails(frames);else for(Bitmap frame:frames)if(!frame.isRecycled())frame.recycle();});}catch(Throwable ignored){for(Bitmap frame:frames)if(!frame.isRecycled())frame.recycle();}finally{try{retriever.release();}catch(Exception ignored){}}});
    }

    private void startTranscode(VideoTranscoder.Preset preset,long start,long end,boolean audio,String aspect,long target,String outputFormat,int shortEdge,int videoBitrate,int frameRate,int audioBitrate,int audioSampleRate) {
        String codecMime=outputFormat.endsWith("HEVC")?MediaFormat.MIMETYPE_VIDEO_HEVC:outputFormat.endsWith("VP9")?MediaFormat.MIMETYPE_VIDEO_VP9:outputFormat.endsWith("VP8")?MediaFormat.MIMETYPE_VIDEO_VP8:MediaFormat.MIMETYPE_VIDEO_AVC;
        if((outputFormat.startsWith("MP4_")||outputFormat.startsWith("3GP_")||outputFormat.startsWith("WEBM_"))&&!supportsEncoder(codecMime)){toast("ترميز الصيغة المختارة غير مدعوم في هذا الجهاز");return;}
        if(outputFormat.startsWith("WEBM")&&audio)toast("ملاحظة: صوت WebM يُحفظ فقط إذا كان المصدر Opus أو Vorbis");
        cancelled.set(false); File dir=getExternalFilesDir("Movies"); if(dir==null)dir=new File(getFilesDir(),"Movies"); dir.mkdirs();
        String base=info.name.replaceAll("(?i)\\.[a-z0-9]{2,5}$","").replaceAll("[^a-zA-Z0-9._\u0600-\u06ff-]+","_");
        String extension="AUDIO_MP3".equals(outputFormat)?".mp3":"AUDIO_M4A".equals(outputFormat)?".m4a":"REMUX_MKV".equals(outputFormat)?".mkv":"REMUX_MOV".equals(outputFormat)?".mov":"REMUX_MP4".equals(outputFormat)?".mp4":outputFormat.startsWith("3GP")?".3gp":outputFormat.startsWith("WEBM")?".webm":outputFormat.endsWith("MKV")?".mkv":outputFormat.endsWith("MOV")?".mov":outputFormat.endsWith("AVI")?".avi":".mp4";
        File output=new File(dir,base+"_VideoX_"+System.currentTimeMillis()+extension); serviceJob=true; showProgress("جاري "+pendingMode+" الفيديو…");
        Intent job=new Intent(this,ProcessingService.class).setAction(ProcessingService.ACTION_START).putExtra("input",selectedUri.toString()).putExtra("output",output.getAbsolutePath()).putExtra("original",info.size).putExtra("label","جاري "+pendingMode+" الفيديو…").putExtra("preset",preset.name()).putExtra("start",start).putExtra("end",end).putExtra("audio",audio).putExtra("aspect",aspect).putExtra("target",target).putExtra("outputFormat",outputFormat).putExtra("shortEdge",shortEdge).putExtra("videoBitrate",videoBitrate).putExtra("frameRate",frameRate).putExtra("audioBitrate",audioBitrate).putExtra("audioSampleRate",audioSampleRate).putExtra("smart",smartQuality).putExtra("scalePercent",profileScale).putExtra("vbr",profileScale>0);if(Build.VERSION.SDK_INT>=26)startForegroundService(job);else startService(job);
    }

    private void showAudioPage(){homeScreen=false;processing=false;LinearLayout p=page();p.addView(titleBar("استوديو الصوت Pro",this::showHome));LinearLayout c=card();c.addView(label(info.name,17,Color.WHITE,true));c.addView(label(info.details(),13,Color.LTGRAY,false),matchTop(5));c.addView(label(info.technicalDetails(),13,Color.rgb(94,205,137),true),matchTop(6));p.addView(c,matchTop(12));p.addView(section("صيغة الإخراج"));String[] audioFormats={"MP3 — مشفّر LAME","WAV — PCM بدون فقدان","AAC — ملف ADTS حقيقي","M4A — AAC داخل MP4","OGG — Vorbis"};Spinner audioFormat=styledSpinner(audioFormats);p.addView(audioFormat);p.addView(section("الجودة ومعدل العينة"));String[] qualityNames={"64 kbps — صوت صغير","96 kbps","128 kbps — موصى به","160 kbps","192 kbps — جودة عالية","256 kbps","320 kbps — أعلى جودة"};int[] qualityValues={64,96,128,160,192,256,320};Spinner quality=styledSpinner(qualityNames);quality.setSelection(2);p.addView(quality);String[] sampleNames={"معدل العينة: الأصلي","32000 Hz","44100 Hz","48000 Hz"};int[] sampleValues={0,32000,44100,48000};Spinner sampleRate=styledSpinner(sampleNames);p.addView(sampleRate,matchTop(9));String[] volumeNames={"الصوت: 50%","الصوت: 100% — الأصلي","الصوت: 150%","الصوت: 200%"};float[] volumeValues={.5f,1f,1.5f,2f};Spinner volume=styledSpinner(volumeNames);volume.setSelection(1);p.addView(volume,matchTop(9));p.addView(section("قص الصوت بدقة"));LinearLayout range=row();EditText start=numberField("البداية بالثواني");EditText end=numberField("النهاية بالثواني");range.addView(start,weight());range.addView(end,weightMargin());p.addView(range);TextView expected=label("الحجم المتوقع عند 128 kbps: "+VideoInfo.bytes(info.durationMs*128/8),14,Color.rgb(94,205,137),true);expected.setGravity(Gravity.CENTER);p.addView(expected,matchTop(12));quality.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(android.widget.AdapterView<?>a,View v,int position,long id){int kb=qualityValues[position];expected.setText("الحجم المتوقع عند "+kb+" kbps: "+VideoInfo.bytes(info.durationMs*kb/8));}public void onNothingSelected(android.widget.AdapterView<?>a){}});p.addView(label("الجودة ومعدل العينة ومستوى الصوت والقص تُطبّق فعلياً على الملف الناتج.",12,Color.rgb(151,170,205),false),matchTop(12));Button go=action("♫ بدء تحويل الصوت",VIOLET);go.setOnClickListener(v->{long startMs=parseSeconds(start,0),endMs=parseSeconds(end,info.durationMs);if(startMs<0||endMs<=startMs||endMs>info.durationMs+1000){toast("تحقق من وقت بداية ونهاية الصوت");return;}startAudioConvert(audioFormat.getSelectedItemPosition(),qualityValues[quality.getSelectedItemPosition()],sampleValues[sampleRate.getSelectedItemPosition()],volumeValues[volume.getSelectedItemPosition()],startMs,endMs);});p.addView(go,matchTop(20));setPage(p);}
    private void startAudioConvert(int format,int bitrate,int sampleRate,float gain,long startMs,long endMs){cancelled.set(false);serviceJob=false;File dir=getExternalFilesDir("Movies");if(dir==null)dir=new File(getFilesDir(),"Movies");dir.mkdirs();String base=info.name.replaceAll("(?i)\\.[a-z0-9]{2,5}$","").replaceAll("[^a-zA-Z0-9._\u0600-\u06ff-]+","_");String extension=format==0?".mp3":format==1?".wav":format==2?".aac":format==3?".m4a":".ogg";File out=new File(dir,base+"_VideoX_Audio_"+System.currentTimeMillis()+extension);showProgress("جاري تحويل الصوت إلى "+extension.substring(1).toUpperCase(Locale.US));worker.execute(()->{try{if(format==0)new Mp3Transcoder(this,cancelled).convert(selectedUri,out,bitrate,sampleRate,startMs,endMs,gain,p->ui.post(()->updateProgress(p)));else if(format==1)new WavTranscoder(this,cancelled).convert(selectedUri,out,sampleRate,startMs,endMs,gain,p->ui.post(()->updateProgress(p)));else new FfmpegTranscoder(this,cancelled).convertAudio(selectedUri,out,format==2?"AAC":format==3?"M4A":"OGG",bitrate,sampleRate,startMs,endMs,gain,p->ui.post(()->updateProgress(p)));ui.post(()->showAudioResult(out));}catch(Throwable e){ui.post(()->{toast(clean(e));showAudioPageSafely();});}});}
    private void showAudioResult(File output){homeScreen=false;processing=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);lastOutput=output;saveHistory(output);String mime=videoMime(output);LinearLayout p=page();p.addView(titleBar("اكتمل تحويل الصوت",this::showHome));TextView ok=label("♫",80,Color.rgb(62,212,134),true);ok.setGravity(Gravity.CENTER);p.addView(ok,matchTop(25));p.addView(label(output.getName()+"\n"+VideoInfo.bytes(output.length()),16,Color.WHITE,true),matchTop(18));Button save=action("حفظ ملف الصوت",BLUE);save.setOnClickListener(v->{pendingExport=output;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).putExtra(Intent.EXTRA_TITLE,output.getName()).addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,SAVE_OLD);});p.addView(save,matchTop(22));Button share=action("مشاركة الصوت",VIOLET);share.setOnClickListener(v->{Uri u=fileUri(output);Intent i=new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"مشاركة الصوت"));});p.addView(share,matchTop(10));Button home=secondary("العودة للرئيسية");home.setOnClickListener(v->showHome());p.addView(home,matchTop(10));setPage(p);}

    private boolean supportsEncoder(String mime){try{for(MediaCodecInfo i:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()){if(!i.isEncoder())continue;for(String t:i.getSupportedTypes())if(t.equalsIgnoreCase(mime))return true;}}catch(Exception ignored){}return false;}

    private void showBatchPage(ArrayList<Uri> inputs){homeScreen=false;pendingMode="دفعة";LinearLayout p=page();p.addView(titleBar("معالجة دفعة Pro",this::showHome));p.addView(label("سيتم ضغط "+inputs.size()+" فيديو بالتتابع مع استمرار العمل بالخلفية.",15,Color.rgb(151,170,205),false),matchTop(12));LinearLayout list=card();for(int i=0;i<inputs.size();i++)list.addView(label((i+1)+". "+displayName(inputs.get(i)),14,Color.WHITE,false),i==0?match():matchTop(7));p.addView(list,matchTop(14));p.addView(section("قوة الضغط لكل الملفات"));final VideoTranscoder.Preset[] selected={VideoTranscoder.Preset.MEDIUM};Button[] buttons=new Button[3];String[] names={"قوي\n500 kbps","متوسط\n900 kbps","خفيف\n1600 kbps"};VideoTranscoder.Preset[] values=VideoTranscoder.Preset.values();LinearLayout row=row();for(int i=0;i<3;i++){int x=i;buttons[i]=chip(names[i],i==1);buttons[i].setOnClickListener(v->{selected[0]=values[x];for(int j=0;j<buttons.length;j++)styleChip(buttons[j],j==x);});row.addView(buttons[i],i==0?weight():weightMargin());}p.addView(row);Button start=action("بدء معالجة الدفعة",VIOLET);start.setOnClickListener(v->startBatch(inputs,selected[0]));p.addView(start,matchTop(20));setPage(p);}

    private void startBatch(ArrayList<Uri> inputs,VideoTranscoder.Preset preset){File dir=getExternalFilesDir("Movies");if(dir==null)dir=new File(getFilesDir(),"Movies");dir.mkdirs();String[] values=new String[inputs.size()];for(int i=0;i<inputs.size();i++)values[i]=inputs.get(i).toString();serviceJob=true;showProgress("جاري معالجة الدفعة…");Intent job=new Intent(this,ProcessingService.class).setAction(ProcessingService.ACTION_START).putExtra("batchInputs",values).putExtra("outputDir",dir.getAbsolutePath()).putExtra("preset",preset.name()).putExtra("label","معالجة دفعة Pro — "+inputs.size()+" فيديو");if(Build.VERSION.SDK_INT>=26)startForegroundService(job);else startService(job);}

    private void showBatchResult(ArrayList<String> paths){homeScreen=false;processing=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);LinearLayout p=page();p.addView(titleBar("اكتملت الدفعة",this::showHome));TextView ok=label("✓ تم تجهيز "+paths.size()+" فيديو",23,Color.rgb(62,212,134),true);ok.setGravity(Gravity.CENTER);p.addView(ok,matchTop(20));for(String path:paths){File file=new File(path);if(!file.isFile())continue;saveHistory(file);LinearLayout card=card();card.addView(label(file.getName(),14,Color.WHITE,true));card.addView(label(VideoInfo.bytes(file.length()),13,Color.rgb(94,205,137),true),matchTop(5));Button play=secondary("تشغيل");play.setOnClickListener(v->open(file));card.addView(play,matchTop(7));p.addView(card,matchTop(9));}Button home=action("العودة للرئيسية",VIOLET);home.setOnClickListener(v->showHome());p.addView(home,matchTop(18));setPage(p);}

    private void showMergeOrder(ArrayList<Uri> inputs){homeScreen=false;pendingMode="دمج";LinearLayout p=page();p.addView(titleBar("ترتيب مقاطع الدمج",this::showHome));p.addView(label("رتّب المقاطع قبل الدمج. سيقوم التطبيق بتوحيد الدقة والترميز عند الحاجة.",14,Color.rgb(151,170,205),false),matchTop(10));for(int i=0;i<inputs.size();i++){int index=i;LinearLayout card=card();card.addView(label((i+1)+". "+displayName(inputs.get(i)),14,Color.WHITE,true));LinearLayout controls=row();Button up=secondary("↑ للأعلى");Button down=secondary("↓ للأسفل");up.setEnabled(i>0);down.setEnabled(i<inputs.size()-1);up.setOnClickListener(v->{Uri item=inputs.remove(index);inputs.add(index-1,item);showMergeOrder(inputs);});down.setOnClickListener(v->{Uri item=inputs.remove(index);inputs.add(index+1,item);showMergeOrder(inputs);});controls.addView(up,weight());controls.addView(down,weightMargin());card.addView(controls,matchTop(7));p.addView(card,matchTop(9));}p.addView(section("الانتقال بين المقاطع"));Spinner transition=styledSpinner(new String[]{"بدون انتقال — دمج سريع","تلاشي بصري وصوتي — ثانية واحدة"});p.addView(transition);Button merge=action("بدء الدمج بالترتيب",VIOLET);merge.setOnClickListener(v->startMerge(inputs,transition.getSelectedItemPosition()==1));p.addView(merge,matchTop(18));setPage(p);}

    private String displayName(Uri uri){try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){int index=cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);if(index>=0)return cursor.getString(index);}}catch(Exception ignored){}String value=uri.getLastPathSegment();return value==null?"فيديو":value;}

    private void startMerge(List<Uri> inputs,boolean transition) {
        cancelled.set(false); serviceJob=false; File dir=getExternalFilesDir("Movies"); if(dir==null)dir=new File(getFilesDir(),"Movies"); dir.mkdirs(); File finalDir=dir; File out=new File(dir,"VideoX_Merged_"+System.currentTimeMillis()+".mp4");
        showProgress(transition?"جاري تجهيز الانتقالات…":"جاري دمج المقاطع…"); worker.execute(()->{ArrayList<File> temps=new ArrayList<>();try{if(transition){ArrayList<Uri> normalized=new ArrayList<>();long[] durations=new long[inputs.size()];for(int i=0;i<inputs.size();i++){VideoInfo vi=new VideoInfo(this,inputs.get(i));durations[i]=vi.durationMs;File tmp=new File(finalDir,".transition_"+System.currentTimeMillis()+"_"+i+".mp4");temps.add(tmp);VideoTranscoder.Options o=new VideoTranscoder.Options();o.preset=VideoTranscoder.Preset.MEDIUM;o.startMs=0;o.endMs=vi.durationMs;o.keepAudio=true;o.aspect="16:9";o.shortEdge=720;int index=i;new VideoTranscoder(this,cancelled).transcode(inputs.get(i),tmp,o,p->ui.post(()->updateProgress((index*65+p*65/100)/inputs.size())));normalized.add(Uri.fromFile(tmp));}new FfmpegTranscoder(this,cancelled).mergeWithFade(normalized,out,durations,1.0,p->ui.post(()->updateProgress(65+p*35/100)));}else try{new VideoMerger(this,cancelled).merge(inputs,out,p->ui.post(()->updateProgress(p)));}catch(IllegalArgumentException mismatch){ui.post(()->toast("يتم الآن توحيد دقة وترميز المقاطع تلقائيًا"));ArrayList<Uri> normalized=new ArrayList<>();for(int i=0;i<inputs.size();i++){VideoInfo vi=new VideoInfo(this,inputs.get(i));File tmp=new File(finalDir,".normalize_"+System.currentTimeMillis()+"_"+i+".mp4");temps.add(tmp);VideoTranscoder.Options o=new VideoTranscoder.Options();o.preset=VideoTranscoder.Preset.MEDIUM;o.startMs=0;o.endMs=vi.durationMs;o.keepAudio=true;o.aspect="16:9";int index=i;new VideoTranscoder(this,cancelled).transcode(inputs.get(i),tmp,o,p->ui.post(()->updateProgress((index*75+p*75/100)/inputs.size())));normalized.add(Uri.fromFile(tmp));}new VideoMerger(this,cancelled).merge(normalized,out,p->ui.post(()->updateProgress(75+p/4)));}ui.post(()->showResult(out,0));}catch(Throwable e){ui.post(()->{toast(clean(e));showHome();});}finally{for(File f:temps)f.delete();}});
    }

    private ProgressBar progress; private TextView progressText,etaText; private long localProgressStarted;
    private void showProgress(String message){homeScreen=false;processing=true;localProgressStarted=System.currentTimeMillis();getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);LinearLayout p=column();p.setPadding(dp(28),dp(70),dp(28),dp(28));p.setGravity(Gravity.CENTER_HORIZONTAL);ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.app_icon);p.addView(icon,size(110,110));p.addView(label(message,23,Color.WHITE,true),matchTop(25));p.addView(label(serviceJob?"يمكنك الخروج وسيستمر العمل في الخلفية":"لا تغلق التطبيق أثناء المعالجة",14,Color.rgb(155,174,205),false),matchTop(8));progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(VIOLET));p.addView(progress,matchTop(35));progressText=label("0%",34,Color.WHITE,true);progressText.setGravity(Gravity.CENTER);p.addView(progressText,matchTop(12));etaText=label("جاري حساب الوقت المتبقي…",14,Color.rgb(151,170,205),false);etaText.setGravity(Gravity.CENTER);p.addView(etaText,matchTop(6));Button cancel=secondary("إلغاء العملية");cancel.setOnClickListener(v->{if(serviceJob)startService(new Intent(this,ProcessingService.class).setAction(ProcessingService.ACTION_CANCEL));else cancelled.set(true);cancel.setEnabled(false);cancel.setText("جاري الإلغاء…");});p.addView(cancel,matchTop(30));setPage(p);}
    private void updateProgress(int value){if(progress!=null){progress.setProgress(value);progressText.setText(value+"%");long started=serviceJob&&ProcessingService.startedAt>0?ProcessingService.startedAt:localProgressStarted;if(etaText!=null&&value>=2&&value<100){long elapsed=Math.max(1,System.currentTimeMillis()-started);long remaining=elapsed*(100-value)/value;etaText.setText("الوقت المتبقي تقريباً: "+VideoInfo.time(remaining));}else if(etaText!=null&&value>=100)etaText.setText("اكتملت العملية");}}

    private void showResult(File output,long original){homeScreen=false;processing=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);lastOutput=output;saveHistory(output);LinearLayout p=page();p.setGravity(Gravity.CENTER_HORIZONTAL);p.addView(titleBar("اكتملت العملية",this::showHome));TextView check=label("✓",74,Color.rgb(52,218,116),true);check.setGravity(Gravity.CENTER);p.addView(check,matchTop(30));TextView done=label("تم تجهيز الفيديو بنجاح",24,Color.WHITE,true);done.setGravity(Gravity.CENTER);p.addView(done);LinearLayout card=card();card.addView(label(output.getName(),16,Color.WHITE,true));card.addView(label("الحجم النهائي: "+VideoInfo.bytes(output.length()),15,Color.rgb(64,221,126),true),matchTop(8));if(original>0)card.addView(label("الحجم الأصلي: "+VideoInfo.bytes(original)+"  •  التوفير: "+saving(original,output.length()),13,Color.LTGRAY,false),matchTop(5));p.addView(card,matchTop(24));Button play=action("▶  تشغيل الفيديو",BLUE);play.setOnClickListener(v->open(output));p.addView(play,matchTop(18));Button compare=secondary("مقارنة الأصل والنتيجة قبل الحفظ");compare.setOnClickListener(v->showCompare(output,original));p.addView(compare,matchTop(10));Button gallery=action("حفظ في معرض الجهاز",VIOLET);gallery.setOnClickListener(v->export(output));p.addView(gallery,matchTop(10));Button share=secondary("مشاركة الفيديو");share.setOnClickListener(v->share(output));p.addView(share,matchTop(10));Button another=secondary("معالجة فيديو آخر");another.setOnClickListener(v->showHome());p.addView(another,matchTop(10));setPage(p);}
    private void showCompare(File output,long original){LinearLayout p=page();p.addView(titleBar("مقارنة Pro",()->showResult(output,original)));LinearLayout before=card();before.addView(label("الأصل",19,Color.WHITE,true));if(info!=null){before.addView(label(info.name,14,Color.LTGRAY,false),matchTop(6));before.addView(label(info.details(),13,Color.rgb(151,170,205),false),matchTop(5));before.addView(label(info.technicalDetails(),13,Color.rgb(94,205,137),true),matchTop(5));}Button playBefore=secondary("▶ تشغيل الأصل");playBefore.setOnClickListener(v->openUri(selectedUri,"video/*"));before.addView(playBefore,matchTop(8));p.addView(before,matchTop(12));LinearLayout after=card();after.addView(label("النتيجة",19,Color.WHITE,true));after.addView(label(output.getName(),14,Color.LTGRAY,false),matchTop(6));try{VideoInfo resultInfo=new VideoInfo(this,Uri.fromFile(output));after.addView(label(resultInfo.details(),13,Color.rgb(151,170,205),false),matchTop(5));after.addView(label(resultInfo.technicalDetails(),13,Color.rgb(94,205,137),true),matchTop(5));}catch(Exception ignored){after.addView(label("الحجم: "+VideoInfo.bytes(output.length()),13,Color.rgb(94,205,137),true),matchTop(5));}after.addView(label(original>0?"التوفير الحقيقي: "+saving(original,output.length()):"تم تحليل الملف الناتج",14,Color.rgb(94,205,137),true),matchTop(6));Button playAfter=secondary("▶ تشغيل النتيجة");playAfter.setOnClickListener(v->open(output));after.addView(playAfter,matchTop(8));p.addView(after,matchTop(12));Button save=action("اعتماد النتيجة وحفظها",VIOLET);save.setOnClickListener(v->export(output));p.addView(save,matchTop(18));setPage(p);}
    private String saving(long a,long b){if(a<=0)return "—";return Math.max(0,100-b*100/a)+"%";}

    private Uri fileUri(File f){return new Uri.Builder().scheme("content").authority(getPackageName()+".files").appendPath("files").appendPath(f.getName()).build();}
    private String videoMime(File f){return VideoFileProvider.mimeFor(f.getName());}
    private void open(File f){try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(f),videoMime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception e){toast("لا يوجد مشغل مناسب");}}
    private void openUri(Uri uri,String mime){if(uri==null)return;try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception e){toast("لا يوجد مشغل مناسب");}}
    private void share(File f){Intent i=new Intent(Intent.ACTION_SEND).setType(videoMime(f)).putExtra(Intent.EXTRA_STREAM,fileUri(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"مشاركة الفيديو"));}
    private void export(File f){if(Build.VERSION.SDK_INT<29){pendingExport=f;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(videoMime(f)).putExtra(Intent.EXTRA_TITLE,f.getName()).addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,SAVE_OLD);return;}worker.execute(()->{try{ContentValues v=new ContentValues();v.put(MediaStore.Video.Media.DISPLAY_NAME,f.getName());v.put(MediaStore.Video.Media.MIME_TYPE,videoMime(f));v.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/VideoX Compressor");v.put(MediaStore.Video.Media.IS_PENDING,1);Uri u=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);if(u==null)throw new Exception("تعذر إنشاء الملف");copyBytes(f,u);v.clear();v.put(MediaStore.Video.Media.IS_PENDING,0);getContentResolver().update(u,v,null,null);ui.post(()->toast("تم الحفظ في مجلد Movies/VideoX Compressor"));}catch(Exception e){ui.post(()->toast("تعذر الحفظ: "+clean(e)));}});}
    private void copyToUri(File f,Uri u){worker.execute(()->{try{copyBytes(f,u);ui.post(()->toast("تم حفظ الفيديو"));}catch(Exception e){ui.post(()->toast("تعذر الحفظ: "+clean(e)));}});}
    private void copyBytes(File f,Uri u)throws Exception{try(OutputStream o=getContentResolver().openOutputStream(u);FileInputStream in=new FileInputStream(f)){if(o==null)throw new Exception("تعذر فتح مكان الحفظ");byte[]b=new byte[1024*1024];int n;while((n=in.read(b))>0)o.write(b,0,n);}}

    private void showHistory(){LinearLayout p=page();p.addView(titleBar("سجل الملفات",this::showHome));String raw=getPreferences(MODE_PRIVATE).getString("history","");if(raw.isEmpty())p.addView(label("لا توجد عمليات محفوظة بعد.",16,Color.GRAY,false),matchTop(30));else for(String path:raw.split("\\n")){File f=new File(path);if(!f.isFile())continue;LinearLayout c=card();c.addView(label(f.getName(),15,Color.WHITE,true));c.addView(label(VideoInfo.bytes(f.length()),13,Color.rgb(108,188,255),false));c.setOnClickListener(v->open(f));p.addView(c,matchTop(10));}Button clear=secondary("مسح السجل فقط");clear.setOnClickListener(v->{getPreferences(MODE_PRIVATE).edit().remove("history").apply();showHistory();});p.addView(clear,matchTop(20));setPage(p);}
    private void saveHistory(File f){String old=getPreferences(MODE_PRIVATE).getString("history","");String next=f.getAbsolutePath()+"\n"+old;String[] lines=next.split("\\n");StringBuilder b=new StringBuilder();for(int i=0;i<Math.min(20,lines.length);i++)b.append(lines[i]).append('\n');getPreferences(MODE_PRIVATE).edit().putString("history",b.toString().trim()).apply();}

    private void showSettings(){LinearLayout p=page();p.addView(titleBar("الإعدادات",this::showHome));p.addView(section("معلومات التطبيق"));LinearLayout c=card();c.addView(label("VideoX Compressor Pro",21,Color.WHITE,true));c.addView(label("الإصدار "+appVersion(),14,Color.LTGRAY,false),matchTop(5));c.addView(label("المطور: مهند سات لنك",16,Color.rgb(98,180,255),true),matchTop(12));c.addView(label("ضاغط ومحوّل Pro لمعالجة الفيديو محليًا مع الحفاظ على خصوصية ملفاتك.",14,Color.LTGRAY,false),matchTop(12));p.addView(c);p.addView(section("ميزات Pro"));LinearLayout pro=card();pro.addView(label("✓ ضغط ذكي إلى 35% أو 55% أو 75% من الحجم",15,Color.rgb(67,218,128),true));pro.addView(label("✓ شريط قص بلقطات حقيقية من الفيديو",15,Color.rgb(67,218,128),true),matchTop(8));pro.addView(label("✓ معالجة فيديو بالخلفية مع إشعار التقدم",15,Color.rgb(67,218,128),true),matchTop(8));pro.addView(label("✓ H.264 وH.265 مع دقة وفريمات يدوية",15,Color.rgb(67,218,128),true),matchTop(8));p.addView(pro);p.addView(section("الخصوصية والتوافق"));LinearLayout d=card();d.addView(label("✓ لا يحتاج إلى اتصال بالإنترنت",15,Color.rgb(67,218,128),true));d.addView(label("✓ لا يرفع فيديوهاتك إلى أي خادم",15,Color.rgb(67,218,128),true),matchTop(8));d.addView(label("✓ يدعم Android 8.0 والأحدث",15,Color.rgb(67,218,128),true),matchTop(8));d.addView(label("✓ يدعم H.265 عند توفره في الجهاز",15,Color.rgb(67,218,128),true),matchTop(8));d.addView(label("✓ يحتوي مشفّر MP3 مدمجًا ويدعم MP4 و3GP وWebM",15,Color.rgb(67,218,128),true),matchTop(8));d.addView(label("دعم صيغ الإدخال وسرعة الترميز يعتمدان على قدرات جهازك.",13,Color.GRAY,false),matchTop(15));p.addView(d);setPage(p);}

    @Override public void onBackPressed(){if(processing&&serviceJob){moveTaskToBack(true);return;}if(processing){toast("ألغِ العملية أولاً");return;}if(homeScreen)super.onBackPressed();else showHome();}
    @Override protected void onDestroy(){stopPlayerTicker();cancelled.set(true);worker.shutdownNow();previewWorker.shutdownNow();super.onDestroy();}

    private LinearLayout page(){LinearLayout p=column();p.setPadding(dp(18),dp(18),dp(18),dp(28));return p;}
    private void setPage(LinearLayout p){stopPlayerTicker();root.removeAllViews();ScrollView s=new ScrollView(this);s.setFillViewport(true);s.addView(p,new ScrollView.LayoutParams(-1,-2));root.addView(s,new FrameLayout.LayoutParams(-1,-1));}
    private int editorPosition(){try{return editorPlayerReady&&editorPlayer!=null?editorPlayer.getCurrentPosition():0;}catch(Exception ignored){return 0;}}
    private boolean isEditorPlaying(){try{return editorPlayerReady&&editorPlayer!=null&&editorPlayer.isPlaying();}catch(Exception ignored){return false;}}
    private void seekEditor(long ms){try{if(editorPlayerReady&&editorPlayer!=null)editorPlayer.seekTo((int)Math.max(0,Math.min(Integer.MAX_VALUE,ms)),MediaPlayer.SEEK_CLOSEST);}catch(Exception ignored){}}
    private void fitPreview(TextureView view,int videoWidth,int videoHeight){if(videoWidth<=0||videoHeight<=0||view.getWidth()<=0||view.getHeight()<=0)return;float scale=Math.min((float)view.getWidth()/videoWidth,(float)view.getHeight()/videoHeight);android.graphics.Matrix matrix=new android.graphics.Matrix();matrix.setScale(videoWidth*scale/view.getWidth(),videoHeight*scale/view.getHeight(),view.getWidth()/2f,view.getHeight()/2f);view.setTransform(matrix);}
    private void stopPlayerTicker(){editorGeneration++;if(playerTicker!=null){ui.removeCallbacks(playerTicker);playerTicker=null;}editorPlayerReady=false;if(editorPlayer!=null){try{editorPlayer.reset();editorPlayer.release();}catch(Exception ignored){}editorPlayer=null;}if(editorSurface!=null){editorSurface.release();editorSurface=null;}editorPreview=null;}
    private LinearLayout titleBar(String title,Runnable back){LinearLayout r=row();r.setGravity(Gravity.CENTER_VERTICAL);Button b=secondary("‹");b.setTextSize(28);b.setOnClickListener(v->back.run());r.addView(b,size(52,52));TextView t=label(title,23,Color.WHITE,true);t.setGravity(Gravity.CENTER);r.addView(t,new LinearLayout.LayoutParams(0,52==0?-2:dp(52),1));Space x=new Space(this);r.addView(x,size(52,52));return r;}
    private TextView section(String t){TextView v=label(t,16,Color.WHITE,true);v.setPadding(0,dp(22),0,dp(9));return v;}
    private Button chip(String text,boolean active){Button b=new Button(this);b.setText(text);b.setTextSize(14);b.setAllCaps(false);b.setPadding(dp(5),dp(5),dp(5),dp(5));styleChip(b,active);return b;}
    private void styleChip(Button b,boolean active){b.setTextColor(Color.WHITE);b.setTypeface(null,active?Typeface.BOLD:Typeface.NORMAL);b.setBackground(round(active?Color.rgb(44,91,175):CARD,14,active?BLUE:Color.rgb(42,58,84)));}
    private Button action(String text,int color){Button b=new Button(this);b.setText(text);b.setTextColor(Color.WHITE);b.setTextSize(17);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setAllCaps(false);b.setMinHeight(dp(58));b.setBackground(round(color,18,Color.TRANSPARENT));return b;}
    private Button secondary(String text){Button b=action(text,CARD);b.setBackground(round(CARD,16,Color.rgb(48,72,110)));return b;}
    private Spinner styledSpinner(String[] values){Spinner s=new Spinner(this);ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values);s.setAdapter(a);s.setBackground(round(CARD,14,Color.rgb(48,72,110)));s.setPadding(dp(8),dp(5),dp(8),dp(5));return s;}
    private EditText numberField(String hint){EditText field=new EditText(this);field.setHint(hint);field.setHintTextColor(Color.GRAY);field.setTextColor(Color.WHITE);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);field.setBackground(round(CARD,14,Color.rgb(48,72,110)));field.setPadding(dp(12),dp(10),dp(12),dp(10));return field;}
    private long parseSeconds(EditText field,long defaultMs){try{String value=field.getText().toString().trim();return value.isEmpty()?defaultMs:(long)(Double.parseDouble(value)*1000);}catch(Exception ignored){return -1;}}
    private LinearLayout card(){LinearLayout l=column();l.setPadding(dp(16),dp(16),dp(16),dp(16));l.setBackground(round(CARD,18,Color.rgb(28,48,80)));return l;}
    private TextView label(String text,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(text);v.setTextSize(sp);v.setTextColor(color);v.setGravity(Gravity.START);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private View space(int h){Space s=new Space(this);s.setLayoutParams(new LinearLayout.LayoutParams(1,dp(h)));return s;}
    private GradientDrawable round(int fill,int radius,int stroke){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(radius));if(stroke!=Color.TRANSPARENT)g.setStroke(dp(1),stroke);return g;}
    private LinearLayout.LayoutParams match(){return new LinearLayout.LayoutParams(-1,-2);}
    private LinearLayout.LayoutParams matchTop(int top){LinearLayout.LayoutParams p=match();p.topMargin=dp(top);return p;}
    private LinearLayout.LayoutParams weight(){return new LinearLayout.LayoutParams(0,-2,1);}
    private LinearLayout.LayoutParams weightMargin(){LinearLayout.LayoutParams p=weight();p.leftMargin=dp(10);return p;}
    private LinearLayout.LayoutParams size(int w,int h){return new LinearLayout.LayoutParams(dp(w),dp(h));}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void toast(String t){Toast.makeText(this,t,Toast.LENGTH_LONG).show();}
    private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){return "—";}}
    private String clean(Throwable e){if(e instanceof UnsatisfiedLinkError||e instanceof NoClassDefFoundError)return "هذه الميزة غير مدعومة على معالج هذا الجهاز";if(e instanceof OutOfMemoryError)return "الذاكرة غير كافية لمعالجة هذا الفيديو؛ جرّب دقة أقل";String m=e.getMessage();return m==null?"حدث خطأ أثناء المعالجة":m.replace("java.lang.","");}
}
