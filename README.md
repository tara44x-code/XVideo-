<div dir="rtl">

# VideoX Compressor Pro

[![Android build](../../actions/workflows/android.yml/badge.svg)](../../actions/workflows/android.yml)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Java 17](https://img.shields.io/badge/Java-17-orange)

تطبيق أندرويد لضغط الفيديو وقصّه وتحويله ودمجه، وكل المعالجة تتم **داخل الجهاز** بدون إنترنت وبدون رفع أي ملف.

![VideoX UI concept](docs/VideoX-UI-Concept.png)
<sub>صورة تصميم مفاهيمي للواجهة.</sub>

## الميزات

| الأداة | التفاصيل |
|---|---|
| **ضغط الفيديو** | ثلاث مستويات ذكية (≈35% و55% و75% من الحجم الأصلي)، أو حجم نهائي مستهدف بالميغابايت، وتحكم بالدقة (240p حتى 4K) والفريمات والـ Bitrate |
| **قص الفيديو** | شريط قص بلقطات حقيقية من الفيديو ومقبضين ومعاينة مباشرة |
| **تحويل الصيغ** | MP4 (H.264 / H.265) و3GP وWebM (VP8 / VP9) وMKV وMOV وAVI |
| **دمج المقاطع** | دمج سريع للمقاطع المتوافقة، وتوحيد تلقائي للمختلفة، وانتقال Fade اختياري |
| **استوديو الصوت** | استخراج الصوت إلى MP3 (LAME) أو WAV أو AAC أو M4A أو OGG، مع قص وتعديل مستوى الصوت |
| **معالجة دفعة** | ضغط عدة فيديوهات بالتتابع في الخلفية |
| **أدوات Pro** | تدوير وعكس، وسرعة من 0.5× إلى 2×، وتحسين الصورة، وتنظيف الصوت، وحذف الـ Metadata والموقع |
| **قوالب جاهزة** | واتساب وتيليغرام وجودة عالية، وحفظ إعداداتك كقالب |

ومن التفاصيل الأخرى:
- المعالجة تستمر في الخلفية مع إشعار يعرض التقدم وزر إلغاء.
- شاشة مقارنة بين الأصل والنتيجة ونسبة التوفير الحقيقية.
- الحفظ في `Movies/VideoX Compressor` والمشاركة المباشرة.
- لا يطلب صلاحية الإنترنت ولا صلاحية قراءة كل الملفات.

## المتطلبات

- Android 8.0 (API 26) فأحدث.
- البناء: JDK 17، وAndroid SDK 35.

> **ملاحظة عن المعالجات:** محرك FFmpeg المضمَّن يدعم `arm64-v8a` و`x86_64` فقط. على الهواتف القديمة بمعالج 32-bit تعمل أدوات MediaCodec (الضغط والقص والتحويل إلى MP4/3GP/WebM وMP3 وWAV)، أما الأدوات التي تعتمد على FFmpeg (MKV/MOV/AVI، وأدوات Pro، والدمج بانتقال، وAAC/M4A/OGG) فتعرض رسالة بأنها غير مدعومة بدل أن يتوقف التطبيق.

## البناء

### Android Studio أو Gradle

```bash
./gradlew assembleDebug          # نسخة تجريبية
./gradlew assembleRelease        # نسخة الإصدار (تحتاج إعداد التوقيع أدناه)
```

### بدون Gradle (بدون إنترنت)

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17
./build.sh
```

### توقيع نسخة الإصدار

مفتاح التوقيع **لا يُرفع إلى GitHub أبداً**. انسخ الملف `keystore.properties.example` إلى `keystore.properties` واكتب فيه مسار المفتاح وكلمات المرور. الملف `keystore.properties` ومجلد `signing/` مستثنيان من Git.

ولبناء نسخة موقّعة تلقائياً على GitHub Actions، أضف هذه القيم في
**Settings → Secrets and variables → Actions**:

| Secret | القيمة |
|---|---|
| `VIDEOX_KEYSTORE_BASE64` | ناتج الأمر `base64 -w0 VideoX-release.keystore` |
| `VIDEOX_KEYSTORE_PASSWORD` | كلمة مرور المفتاح |
| `VIDEOX_KEY_ALIAS` | `videox` |
| `VIDEOX_KEY_PASSWORD` | كلمة مرور الـ alias |

بعدها، أي tag يبدأ بحرف `v` (مثل `v5.2`) ينشر ملف APK موقّعاً في صفحة Releases تلقائياً.

> ⚠️ لتحديث التطبيق فوق النسخ المثبتة سابقاً يجب استخدام **نفس المفتاح** الذي وُقّعت به.

## هيكل المشروع

```
app/src/main/java/com/mohannadsatlink/videox/
├── MainActivity.java       الواجهة وكل الشاشات
├── ProcessingService.java  خدمة المعالجة بالخلفية وإشعار التقدم
├── VideoTranscoder.java    ضغط/تحويل بالعتاد (MediaCodec + OpenGL)
├── CodecSurface.java       سطح OpenGL لتحجيم وقص الإطارات
├── FfmpegTranscoder.java   صيغ إضافية وأدوات Pro والدمج بانتقال (FFmpegKit)
├── VideoMerger.java        دمج سريع بدون إعادة ترميز
├── Mp3Transcoder.java      تحويل الصوت إلى MP3 (LAME)
├── WavTranscoder.java      تحويل الصوت إلى WAV
├── VideoTrimBar.java       شريط القص بلقطات الفيديو
├── VideoInfo.java          قراءة معلومات الفيديو
└── VideoFileProvider.java  مشاركة الملفات الناتجة (قراءة فقط)
```

## المكتبات المستخدمة

راجع [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). أهمها FFmpegKit (LGPL-3.0) وTAndroidLame / LAME (LGPL).

## سجل التغييرات

راجع [CHANGELOG.md](CHANGELOG.md).

## المطوّر

**مهند سات لنك**

</div>

---

### English summary

**VideoX Compressor Pro** is an offline Android app (8.0+) for compressing, trimming, converting and merging videos and extracting audio. All processing happens on-device using MediaCodec/OpenGL and a bundled FFmpegKit build. Build with `./gradlew assembleDebug`, or `./build.sh` for an offline build. Release signing is configured through a git-ignored `keystore.properties` file or CI secrets (see above).
