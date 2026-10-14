package com.yourname.voicestudio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Streams a {@code blob:} download handed over by the page into the phone's
 * Downloads folder, one base64 chunk at a time.
 *
 * <p>The studio exports MP3 audio and SRT/JSON files. Gradio builds those
 * exports in JavaScript and hands the browser a {@code blob:} URL, which a
 * WebView has no way of saving on its own ({@link android.webkit.DownloadListener}
 * only sees real network requests). The injected page script therefore reads the
 * blob, cuts it into 256 KiB slices and pushes each one through the
 * {@code VoiceDownload} bridge; this class writes them to disk.
 *
 * <p>Only one export can be open at a time, and every method is synchronized
 * because the {@code @JavascriptInterface} methods run on the WebView's own
 * JavaBridge thread, not on the UI thread.
 *
 * <ul>
 *   <li>Android 10+ (API 29) — a {@code MediaStore.Downloads} row with
 *       {@code IS_PENDING}, so the file appears in the Files/Downloads app as
 *       soon as it is complete and needs no storage permission.</li>
 *   <li>Android 7-9 (API 24-28) — the public Downloads directory when the caller
 *       says it may write there (runtime permission granted), otherwise the
 *       app's own external files directory, which never needs a permission.</li>
 * </ul>
 */
final class BlobDownloadSaver {
    static final String DEFAULT_NAME = "voice-studio-file";

    private final Context context;
    private OutputStream stream;
    private Uri mediaUri;
    private File file;
    private String name = DEFAULT_NAME;
    private long written;
    private boolean open;

    BlobDownloadSaver(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Starts a download and returns whether the destination could be created.
     *
     * @param preferPublicDir {@code true} when writing to the public Downloads
     *                        folder is allowed (Android 10+ always is).
     */
    synchronized boolean open(String displayName, String mimeType, boolean preferPublicDir) {
        abort();
        name = safeName(displayName);
        String mime = (mimeType == null || mimeType.isEmpty()) ? guessMimeType(name) : mimeType;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (!openMediaStore(mime)) return false;
            } else {
                if (!openDirectFile(preferPublicDir)) return false;
            }
            written = 0;
            open = true;
            return true;
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            abort();
            return false;
        }
    }

    /** Appends one base64 slice. Returns false when the download is broken. */
    synchronized boolean append(String base64Slice) {
        if (!open || stream == null || base64Slice == null) return false;
        byte[] bytes;
        try {
            bytes = Base64.decode(base64Slice, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            stream.write(bytes);
            written += bytes.length;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Completes the download.
     *
     * @return the folder-relative location for a confirmation toast
     *         ({@code Download/name.mp3}), or {@code null} when nothing usable
     *         was saved — in which case the partial file is removed.
     */
    synchronized String finish() {
        if (!open || stream == null) {
            abort();
            return null;
        }
        try {
            stream.flush();
            stream.close();
        } catch (IOException e) {
            stream = null;
            abort();
            return null;
        }
        stream = null;
        open = false;
        if (written == 0) {
            abort();
            return null;
        }
        if (mediaUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            try {
                context.getContentResolver().update(mediaUri, values, null, null);
            } catch (RuntimeException ignored) {
                // The row exists and holds the bytes; a failed un-pending only
                // delays its visibility in the Downloads app.
            }
            mediaUri = null;
            return Environment.DIRECTORY_DOWNLOADS + "/" + name;
        }
        String path = file == null ? null : file.getAbsolutePath();
        file = null;
        return path;
    }

    /** Drops whatever is open and deletes the partial file. */
    synchronized void abort() {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
            stream = null;
        }
        if (mediaUri != null) {
            try {
                context.getContentResolver().delete(mediaUri, null, null);
            } catch (RuntimeException ignored) {
            }
            mediaUri = null;
        }
        if (file != null) {
            file.delete();
            file = null;
        }
        written = 0;
        open = false;
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private boolean openMediaStore(String mime) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return false;
        OutputStream out = resolver.openOutputStream(uri);
        if (out == null) {
            resolver.delete(uri, null, null);
            return false;
        }
        mediaUri = uri;
        stream = out;
        return true;
    }

    @SuppressWarnings("deprecation")
    private boolean openDirectFile(boolean preferPublicDir) throws IOException {
        File dir = null;
        if (preferPublicDir) {
            dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists() && !dir.mkdirs()) dir = null;
            if (dir != null && !dir.canWrite()) dir = null;
        }
        if (dir == null) dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = context.getFilesDir();
        if (dir == null) return false;
        if (!dir.exists() && !dir.mkdirs()) return false;
        file = uniqueFile(dir, name);
        stream = new FileOutputStream(file);
        return true;
    }

    /** {@code audio.mp3} → {@code audio (1).mp3} when the name is already taken. */
    private static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 1; index < 1000; index++) {
            candidate = new File(dir, base + " (" + index + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + extension);
    }

    /** Keeps the page's file name but makes it a safe, non-empty file name. */
    static String safeName(String displayName) {
        if (displayName == null) return DEFAULT_NAME;
        String name = displayName.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        while (name.startsWith(".")) name = name.substring(1);
        if (name.isEmpty()) return DEFAULT_NAME;
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 8 ? name.substring(dot) : "";
            name = name.substring(0, 120 - extension.length()) + extension;
        }
        return name;
    }

    static String guessMimeType(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }
}
