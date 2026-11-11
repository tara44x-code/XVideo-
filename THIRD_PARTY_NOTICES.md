# Third-party notices

This app bundles the following prebuilt libraries in `app/libs/`.

| Library | Version | License | Source |
|---|---|---|---|
| FFmpegKit (full, LGPL build) | 8.1.7 | LGPL-3.0 | https://github.com/arthenica/ffmpeg-kit |
| FFmpeg and the libraries inside FFmpegKit (dav1d, libvpx, libaom, opus, vorbis, lame, openh264, kvazaar, …) | — | Various (LGPL / BSD / MIT, …). Full texts ship in the AAR under `res/raw/license_*.txt` | https://ffmpeg.org |
| TAndroidLame (LAME MP3 encoder wrapper) | 1.1 | LGPL | https://github.com/naman14/TAndroidLame |
| smart-exception | 0.2.1 | BSD-3-Clause | https://github.com/tanersener/smart-exception |

The bundled FFmpeg build is configured **without** `--enable-gpl`, so no GPL-only components (such as libx264/libx265) are included.

Under the LGPL, users must be able to replace the LGPL libraries with modified versions. Because the libraries are shipped as separate shared objects (`.so`) inside the APK and the full app source is published here, this requirement is met.
