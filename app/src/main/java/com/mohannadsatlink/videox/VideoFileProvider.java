package com.mohannadsatlink.videox;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Locale;

/**
 * Read-only provider that exposes processed files to players and share targets.
 * Only plain file names inside the app's own output folders are accepted.
 */
public final class VideoFileProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith("."))
            throw new FileNotFoundException("Invalid path");
        Context context = getContext();
        if (context == null) throw new FileNotFoundException("Provider not ready");
        // Same two folders MainActivity writes to: external app storage, or internal storage when it is unavailable.
        File[] roots = {context.getExternalFilesDir("Movies"), new File(context.getFilesDir(), "Movies")};
        for (File root : roots) {
            if (root == null) continue;
            File file = new File(root, name);
            try {
                if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) continue;
            } catch (Exception e) {
                continue;
            }
            if (file.isFile()) return file;
        }
        throw new FileNotFoundException(name);
    }

    static String mimeFor(String name) {
        if (name == null) return "application/octet-stream";
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        return "video/mp4";
    }

    @Override public String getType(Uri uri) { return mimeFor(uri.getLastPathSegment()); }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
