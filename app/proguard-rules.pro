# App classes are referenced from the manifest and through lambdas/callbacks.
-keep class com.mohannadsatlink.videox.** { *; }

# LAME MP3 encoder: Java wrapper around native code.
-keep class com.naman14.androidlame.** { *; }

# FFmpegKit calls back into Java from native code (logs, statistics, SAF).
# The AAR ships its own rules; keeping the whole package avoids subtle
# release-only crashes when the native side looks up classes by name.
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.**
