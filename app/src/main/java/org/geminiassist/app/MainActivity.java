package org.geminiassist.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.URLUtilCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * GeminiAssist - a WebView wrapper around https://gemini.google.com.
 *
 * Architecture is the same as duckAssist / gptAssist, but every duck.ai specific
 * behaviour is replaced by Gemini behaviour:
 *  - Gemini has no "?q=" prompt API, so shared text is injected into the prompt box
 *    with JavaScript instead of a URL parameter.
 *  - The user agent is forced to a full Chrome-mobile UA, because Google refuses to
 *    serve the login flow to the default WebView user agent.
 *  - There are NO floating buttons, FABs or injected web buttons anywhere: the only
 *    entry point to the settings ("Patches") page is the in-flow top bar button, or
 *    the same button inside the bottom-drawer header.
 */
public class MainActivity extends Activity {

    private static final String TAG = "GeminiAssist";

    /** The wrapped web app. */
    private static final String GEMINI_HOME = "https://gemini.google.com/";

    private static final String PREFS_NAME = "gemini_assist_prefs";
    private static final String FILEPROVIDER_AUTH = "org.geminiassist.app.fileprovider";
    private static final String DOWNLOAD_DIR = "GeminiAssist";

    /** Settings keys (shared with assets/settings.html). */
    private static final String PREF_USE_DRAWER_ASSISTANT = "use_drawer_assistant";
    private static final String PREF_USE_DRAWER_SHARED = "use_drawer_shared";
    private static final String PREF_AUTO_FOCUS_KEYBOARD = "auto_focus_keyboard";
    private static final String PREF_TRIGGER_VOICE_ASSISTANT = "trigger_voice_assistant";
    private static final String PREF_TEXT_ZOOM = "text_zoom";
    private static final String PREF_ASK_SUFFIX = "ask_gemini_suffix";
    private static final String PREF_SHARED_DOC_SUFFIX = "shared_doc_suffix";

    private static final int TEXT_ZOOM_MIN = 50;
    private static final int TEXT_ZOOM_MAX = 300;
    private static final int TEXT_ZOOM_DEFAULT = 100;

    /**
     * Hosts that stay inside the WebView. accounts.google.com / myaccount.google.com
     * MUST stay inside, otherwise the Google login round-trip breaks out to the
     * system browser and the session cookie never lands in the app.
     */
    private static final String[] INTERNAL_HOSTS = {
            "gemini.google.com",
            "accounts.google.com",
            "myaccount.google.com"
    };

    /**
     * Google blocks the bare WebView UA ("browser may not be secure" / sign-in
     * refused). We strip the "wv" marker from the stock UA and fall back to this
     * full Chrome-mobile UA if the stock one is not Chrome based.
     */
    private static final String CHROME_FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    private static final int FILE_CHOOSER_REQUEST_CODE = 1;
    private static final int CAMERA_REQUEST_CODE = 2;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 124;
    private static final int DOWNLOAD_PERMISSION_REQUEST_CODE = 456;
    private static final int MICROPHONE_PERMISSION_REQUEST_CODE = 123;

    private static boolean isSafeMode = false;

    private WebView chatWebView;
    private ProgressBar progressBar;
    private float currentTextZoom = TEXT_ZOOM_DEFAULT;

    private ValueCallback<Uri[]> mUploadMessage;
    private Uri cameraImageUri = null;

    private boolean pendingAutoFocus = false;
    private String pendingSharedText = null;
    private Uri pendingSharedFileUri = null;

    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadContentDisposition;
    private String pendingDownloadMimetype;
    private long pendingDownloadContentLength;

    private boolean isPendingBlob = false;
    private String pendingBlobData;
    private String pendingBlobMimetype;
    private String pendingBlobContentDisposition;
    private String pendingBlobCurrentUrl;

    private android.app.Dialog patchesDialog = null;
    private android.app.Dialog chatsViewerDialog = null;

    /** Local archive of exported Gemini chats, surfaced by chats_viewer.html. */
    private static final String PREF_CHATS_ARCHIVE = "cached_chats_json";
    private static final int ARCHIVE_MAX_ENTRIES = 60;
    private static final int ARCHIVE_MAX_CHARS_PER_CHAT = 200000;

    // ------------------------------------------------------------------ helpers

    /**
     * True for gemini.google.com and its sub-domains plus the Google account hosts
     * that the sign-in flow needs. Uses equals/sub-domain matching instead of a bare
     * endsWith() so that "evil-gemini.google.com" cannot pretend to be internal.
     */
    private static boolean isInternalHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.US);
        for (String internal : INTERNAL_HOSTS) {
            if (h.equals(internal) || h.endsWith("." + internal)) return true;
        }
        return false;
    }

    private static boolean isGeminiUrl(String url) {
        if (url == null) return false;
        try {
            return isInternalHost(Uri.parse(url).getHost());
        } catch (Exception e) {
            return false;
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    protected void setActivityTheme() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        }
    }

    protected int getLayoutResourceId() {
        return R.layout.activity_main;
    }

    /**
     * Runs an injected script, falling back to a plain reload of Gemini if the
     * script engine throws (Safe Mode). Gemini keeps working, only the patches stop.
     */
    private void safeEvaluateJavascript(WebView view, String script) {
        if (isSafeMode || view == null || script == null) return;
        try {
            view.evaluateJavascript(script, null);
        } catch (Throwable t) {
            Log.e(TAG, "Error executing injected script, enabling Safe Mode fallback", t);
            isSafeMode = true;
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, R.string.safe_mode_toast, Toast.LENGTH_LONG).show();
                if (chatWebView != null) {
                    chatWebView.loadUrl(GEMINI_HOME);
                }
            });
        }
    }

    // ------------------------------------------------------------------ onCreate

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        StrictMode.VmPolicy.Builder strictBuilder = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(strictBuilder.build());

        setActivityTheme();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e(TAG, "Uncaught error intercepted, activating Safe Mode fallback", throwable);
            isSafeMode = true;
            runOnUiThread(() -> {
                try {
                    if (chatWebView != null) {
                        chatWebView.loadUrl(GEMINI_HOME);
                    }
                } catch (Throwable ignored) {
                }
            });
        });

        setContentView(getLayoutResourceId());

        progressBar = findViewById(R.id.progressBar);
        chatWebView = findViewById(R.id.chatWebView);

        // The "Patches" button is an in-flow text button in the top bar (activity_main)
        // or in the drawer header (activity_chat). ChatActivity has no top bar, and the
        // patches dialog WebView itself has neither, so both lookups are guarded.
        View btnPatches = findViewById(R.id.btnPatches);
        if (btnPatches != null) {
            btnPatches.setOnClickListener(v -> showPatchesDialog());
        }

        WebSettings webSettings = chatWebView.getSettings();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        }
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setAllowFileAccess(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setGeolocationEnabled(false);
        webSettings.setSaveFormData(false);

        // Google refuses sign-in from the stock WebView UA, so advertise a plain
        // Chrome-mobile browser instead.
        try {
            String defaultUA = webSettings.getUserAgentString();
            String fixedUA = defaultUA != null
                    ? defaultUA.replace("; wv", "").replace(" Version/4.0", "")
                    : "";
            if (fixedUA.isEmpty() || !fixedUA.contains("Chrome/")) {
                fixedUA = CHROME_FALLBACK_UA;
            }
            webSettings.setUserAgentString(fixedUA);
        } catch (Exception e) {
            Log.w(TAG, "Could not adjust user agent", e);
        }

        currentTextZoom = prefs().getInt(PREF_TEXT_ZOOM, TEXT_ZOOM_DEFAULT);
        webSettings.setTextZoom(clampTextZoom((int) currentTextZoom));

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        // REQUIRED for Google login: the account flow uses third-party cookies.
        cookieManager.setAcceptThirdPartyCookies(chatWebView, true);

        chatWebView.setWebViewClient(new MyWebViewClient());
        chatWebView.setWebChromeClient(new MyWebChromeClient());
        chatWebView.setDownloadListener(this::onDownloadRequested);
        chatWebView.addJavascriptInterface(this, "Android");

        ScaleGestureDetector scaleGestureDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        currentTextZoom = Math.max(TEXT_ZOOM_MIN,
                                Math.min(TEXT_ZOOM_MAX, currentTextZoom * detector.getScaleFactor()));
                        applyTextZoom(Math.round(currentTextZoom));
                        return true;
                    }
                });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            scaleGestureDetector.setQuickScaleEnabled(false);
        }

        chatWebView.setOnTouchListener(new View.OnTouchListener() {
            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                scaleGestureDetector.onTouchEvent(event);
                return event.getPointerCount() > 1 || scaleGestureDetector.isInProgress();
            }
        });

        // Gemini's microphone button needs the runtime permission up front.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO },
                    MICROPHONE_PERMISSION_REQUEST_CODE);
        }

        boolean restored = false;
        if (savedInstanceState != null) {
            if (chatWebView.restoreState(savedInstanceState) != null) {
                restored = true;
            }
        }
        if (!restored) {
            handleIntent(getIntent(), false);
        }
    }

    private int clampTextZoom(int zoom) {
        return Math.max(TEXT_ZOOM_MIN, Math.min(TEXT_ZOOM_MAX, zoom));
    }

    /** Applies and persists the text zoom, clamped to 50%..300%. */
    private void applyTextZoom(int zoom) {
        currentTextZoom = clampTextZoom(zoom);
        prefs().edit().putInt(PREF_TEXT_ZOOM, (int) currentTextZoom).apply();
        if (chatWebView != null) {
            chatWebView.getSettings().setTextZoom((int) currentTextZoom);
        }
    }

    // ------------------------------------------------- "Android" JS bridge (page)

    @JavascriptInterface
    public void showToast(final String message) {
        runOnUiThread(() -> {
            if (message != null && !message.isEmpty()) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public void showSoftKeyboard() {
        runOnUiThread(() -> {
            if (chatWebView == null) return;
            chatWebView.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(chatWebView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    @JavascriptInterface
    public void copyToClipboard(final String text) {
        runOnUiThread(() -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && text != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("GeminiAssist", text));
                Toast.makeText(MainActivity.this, R.string.url_copied, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @JavascriptInterface
    public void processBlob(String base64Data, String mimetype, String contentDisposition, String currentUrl) {
        if (checkDownloadPermissions()) {
            saveBlobToFile(base64Data, mimetype, contentDisposition, currentUrl);
        } else {
            isPendingBlob = true;
            pendingBlobData = base64Data;
            pendingBlobMimetype = mimetype;
            pendingBlobContentDisposition = contentDisposition;
            pendingBlobCurrentUrl = currentUrl;
        }
    }

    /** Used by the offline fallback page (chats_viewer.html). */
    @JavascriptInterface
    public void reloadGemini() {
        runOnUiThread(() -> {
            if (chatWebView != null) {
                chatWebView.loadUrl(GEMINI_HOME);
            }
        });
    }

    /** Used by the offline fallback page (chats_viewer.html). */
    @JavascriptInterface
    public String getChatsJson() {
        return archiveJson();
    }

    // -------------------------------------------------- "Patches" settings dialog

    /**
     * Fullscreen settings ("Patches") dialog. It is opened from the top bar button or
     * from the drawer header button - it is never a floating overlay on the web page.
     */
    public void showPatchesDialog() {
        runOnUiThread(() -> {
            if (patchesDialog != null && patchesDialog.isShowing()) {
                patchesDialog.dismiss();
            }
            patchesDialog = new android.app.Dialog(MainActivity.this);
            patchesDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            WebView settingsView = new WebView(MainActivity.this);
            WebSettings ws = settingsView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);

            settingsView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public String getSettingsJson() {
                    JSONObject obj = new JSONObject();
                    try {
                        SharedPreferences p = prefs();
                        obj.put(PREF_USE_DRAWER_ASSISTANT, p.getBoolean(PREF_USE_DRAWER_ASSISTANT, true));
                        obj.put(PREF_USE_DRAWER_SHARED, p.getBoolean(PREF_USE_DRAWER_SHARED, true));
                        obj.put(PREF_AUTO_FOCUS_KEYBOARD, p.getBoolean(PREF_AUTO_FOCUS_KEYBOARD, true));
                        obj.put(PREF_TRIGGER_VOICE_ASSISTANT, p.getBoolean(PREF_TRIGGER_VOICE_ASSISTANT, true));
                        obj.put(PREF_TEXT_ZOOM, p.getInt(PREF_TEXT_ZOOM, TEXT_ZOOM_DEFAULT));
                        obj.put(PREF_ASK_SUFFIX, p.getString(PREF_ASK_SUFFIX, ""));
                        obj.put(PREF_SHARED_DOC_SUFFIX, p.getString(PREF_SHARED_DOC_SUFFIX, ""));
                        JSONArray chats = archive().optJSONArray("chats");
                        obj.put("archived_chats", chats != null && chats.length() > 0);
                    } catch (Exception e) {
                        Log.e(TAG, "Error building settings JSON", e);
                    }
                    return obj.toString();
                }

                @JavascriptInterface
                public void saveSettings(String jsonStr) {
                    try {
                        JSONObject obj = new JSONObject(jsonStr);
                        prefs().edit()
                                .putBoolean(PREF_USE_DRAWER_ASSISTANT,
                                        obj.optBoolean(PREF_USE_DRAWER_ASSISTANT, true))
                                .putBoolean(PREF_USE_DRAWER_SHARED,
                                        obj.optBoolean(PREF_USE_DRAWER_SHARED, true))
                                .putBoolean(PREF_AUTO_FOCUS_KEYBOARD,
                                        obj.optBoolean(PREF_AUTO_FOCUS_KEYBOARD, true))
                                .putBoolean(PREF_TRIGGER_VOICE_ASSISTANT,
                                        obj.optBoolean(PREF_TRIGGER_VOICE_ASSISTANT, true))
                                .putInt(PREF_TEXT_ZOOM, clampTextZoom(obj.optInt(PREF_TEXT_ZOOM, TEXT_ZOOM_DEFAULT)))
                                .putString(PREF_ASK_SUFFIX, obj.optString(PREF_ASK_SUFFIX, ""))
                                .putString(PREF_SHARED_DOC_SUFFIX, obj.optString(PREF_SHARED_DOC_SUFFIX, ""))
                                .apply();
                        runOnUiThread(() -> {
                            if (patchesDialog != null && patchesDialog.isShowing()) {
                                patchesDialog.dismiss();
                            }
                            Toast.makeText(MainActivity.this, "Patches saved", Toast.LENGTH_SHORT).show();
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Error saving settings", e);
                    }
                }

                @JavascriptInterface
                public void setTextZoom(int zoom) {
                    runOnUiThread(() -> applyTextZoom(zoom));
                }

                @JavascriptInterface
                public void openChatsViewer() {
                    runOnUiThread(() -> {
                        if (patchesDialog != null && patchesDialog.isShowing()) {
                            patchesDialog.dismiss();
                        }
                        showChatsViewerDialog();
                    });
                }

                @JavascriptInterface
                public void dismissSettings() {
                    runOnUiThread(() -> {
                        if (patchesDialog != null && patchesDialog.isShowing()) {
                            patchesDialog.dismiss();
                        }
                    });
                }
            }, "AndroidSettings");

            settingsView.loadUrl("file:///android_asset/settings.html");
            patchesDialog.setContentView(settingsView);
            patchesDialog.show();

            Window window = patchesDialog.getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            }
        });
    }

    /** Saved-chats viewer, also used as the offline fallback for a failed page load. */
    private void showChatsViewerDialog() {
        runOnUiThread(() -> {
            if (chatsViewerDialog != null && chatsViewerDialog.isShowing()) {
                chatsViewerDialog.dismiss();
            }
            chatsViewerDialog = new android.app.Dialog(MainActivity.this);
            chatsViewerDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            WebView viewer = new WebView(MainActivity.this);
            WebSettings ws = viewer.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);

            viewer.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public String getChatsJson() {
                    return archiveJson();
                }

                @JavascriptInterface
                public void dismissViewer() {
                    runOnUiThread(() -> {
                        if (chatsViewerDialog != null && chatsViewerDialog.isShowing()) {
                            chatsViewerDialog.dismiss();
                        }
                    });
                }

                @JavascriptInterface
                public void reloadGemini() {
                    runOnUiThread(() -> {
                        if (chatsViewerDialog != null && chatsViewerDialog.isShowing()) {
                            chatsViewerDialog.dismiss();
                        }
                        if (chatWebView != null) {
                            chatWebView.loadUrl(GEMINI_HOME);
                        }
                    });
                }

                @JavascriptInterface
                public void copyToClipboard(String text) {
                    MainActivity.this.copyToClipboard(text);
                }

                @JavascriptInterface
                public void processBlob(String base64Data, String mimetype, String contentDisposition, String currentUrl) {
                    MainActivity.this.processBlob(base64Data, mimetype, contentDisposition, currentUrl);
                }
            }, "AndroidChatsViewer");

            viewer.loadUrl("file:///android_asset/chats_viewer.html");
            chatsViewerDialog.setContentView(viewer);
            chatsViewerDialog.show();

            Window window = chatsViewerDialog.getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            }
        });
    }

    // ------------------------------------------------------------ chat archive

    private JSONObject archive() {
        try {
            String raw = prefs().getString(PREF_CHATS_ARCHIVE, null);
            if (raw != null && !raw.isEmpty()) {
                return new JSONObject(raw);
            }
        } catch (Exception e) {
            Log.w(TAG, "Archive unreadable, starting a new one", e);
        }
        JSONObject fresh = new JSONObject();
        try {
            fresh.put("chats", new JSONArray());
        } catch (Exception ignored) {
        }
        return fresh;
    }

    private String archiveJson() {
        return archive().toString();
    }

    /** Stores the text of an exported Gemini chat so it can be read offline. */
    private void appendToArchive(String title, String content) {
        if (content == null || content.trim().isEmpty()) return;
        try {
            JSONObject root = archive();
            JSONArray chats = root.optJSONArray("chats");
            if (chats == null) {
                chats = new JSONArray();
            }
            JSONObject entry = new JSONObject();
            entry.put("title", title == null || title.isEmpty() ? "Gemini chat" : title);
            entry.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date()));
            entry.put("timestamp", System.currentTimeMillis());
            entry.put("content", content.length() > ARCHIVE_MAX_CHARS_PER_CHAT
                    ? content.substring(0, ARCHIVE_MAX_CHARS_PER_CHAT)
                    : content);

            JSONArray trimmed = new JSONArray();
            trimmed.put(entry);
            for (int i = 0; i < chats.length() && trimmed.length() < ARCHIVE_MAX_ENTRIES; i++) {
                trimmed.put(chats.opt(i));
            }
            root.put("chats", trimmed);
            prefs().edit().putString(PREF_CHATS_ARCHIVE, root.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Could not archive chat", e);
        }
    }

    // --------------------------------------------------------------- downloads

    private void onDownloadRequested(String url, String userAgent, String contentDisposition,
            String mimetype, long contentLength) {
        if (url == null) return;

        if (url.startsWith("blob:")) {
            String escapedUrl = url.replace("'", "\\'");
            String escapedCd = contentDisposition != null ? contentDisposition.replace("'", "\\'") : "";
            String escapedMime = mimetype != null ? mimetype.replace("'", "\\'") : "";
            chatWebView.evaluateJavascript(
                    "(function() {" +
                            "  var url = '" + escapedUrl + "';" +
                            "  var blob = window.blobMap ? window.blobMap.get(url) : null;" +
                            "  if (blob) {" +
                            "    var reader = new FileReader();" +
                            "    reader.onloadend = function() {" +
                            "      Android.processBlob(reader.result, blob.type, '" + escapedCd + "', window.location.href);" +
                            "    };" +
                            "    reader.readAsDataURL(blob);" +
                            "  } else {" +
                            "    var xhr = new XMLHttpRequest();" +
                            "    xhr.open('GET', url, true);" +
                            "    xhr.responseType = 'blob';" +
                            "    xhr.onload = function() {" +
                            "      if (this.status == 200) {" +
                            "        var reader = new FileReader();" +
                            "        reader.readAsDataURL(this.response);" +
                            "        reader.onloadend = function() {" +
                            "          Android.processBlob(reader.result, '" + escapedMime + "', '" + escapedCd + "', window.location.href);" +
                            "        };" +
                            "      }" +
                            "    };" +
                            "    xhr.send();" +
                            "  }" +
                            "})();",
                    null);
            return;
        }

        if (url.startsWith("data:")) {
            processBlob(url, mimetype, contentDisposition, url);
            return;
        }

        if (checkDownloadPermissions()) {
            startStandardDownload(url, userAgent, contentDisposition, mimetype, contentLength);
        } else {
            isPendingBlob = false;
            pendingDownloadUrl = url;
            pendingDownloadUserAgent = userAgent;
            pendingDownloadContentDisposition = contentDisposition;
            pendingDownloadMimetype = mimetype;
            pendingDownloadContentLength = contentLength;
        }
    }

    /** Gemini chat exports default to .md, downloaded as "Gemini_Chat_yyyyMMdd_HHmmss.md". */
    private String formatGeminiFilename(String filename, String mimetype) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fallback = "Gemini_Chat_" + timestamp + ".md";

        if (filename == null || filename.isEmpty()) {
            return fallback;
        }

        String lower = filename.toLowerCase(Locale.US);
        if (lower.endsWith(".txt")) {
            filename = filename.substring(0, filename.length() - 4) + ".md";
        } else if (lower.endsWith(".text")) {
            filename = filename.substring(0, filename.length() - 5) + ".md";
        } else if (lower.endsWith(".bin")) {
            filename = filename.substring(0, filename.length() - 4) + ".md";
        }

        if (!filename.contains(".")) {
            filename += ".md";
        }

        int dotIndex = filename.lastIndexOf('.');
        String baseName = (dotIndex > 0) ? filename.substring(0, dotIndex).trim() : filename;
        if (baseName.equalsIgnoreCase("download") || baseName.equalsIgnoreCase("chat")
                || baseName.equalsIgnoreCase("export") || baseName.equalsIgnoreCase("gemini")
                || baseName.equalsIgnoreCase("gemini_chat")) {
            filename = fallback;
        }

        return filename;
    }

    private void saveBlobToFile(String base64Data, String mimetype, String contentDisposition, String currentUrl) {
        if (base64Data == null) return;
        if (base64Data.contains(",")) {
            base64Data = base64Data.split(",", 2)[1];
        }

        String filename = URLUtilCompat.getFilenameFromContentDisposition(contentDisposition);
        if (filename == null || filename.isEmpty()) {
            filename = URLUtilCompat.guessFileName(currentUrl, contentDisposition, mimetype);
        }
        filename = formatGeminiFilename(filename, mimetype);
        if (filename.endsWith(".md")) {
            mimetype = "text/markdown";
        }

        final String finalFilename = filename;
        final String finalMimetype = mimetype;
        final byte[] decoded;
        try {
            decoded = Base64.decode(base64Data, Base64.DEFAULT);
        } catch (Exception e) {
            Log.e(TAG, "Blob payload was not valid base64", e);
            return;
        }

        try {
            Uri fileUri = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimetype);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + File.separator + DOWNLOAD_DIR);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
                        if (outputStream != null) {
                            outputStream.write(decoded);
                            fileUri = uri;
                        }
                    }
                }
            } else {
                File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        DOWNLOAD_DIR);
                if (!path.exists()) {
                    path.mkdirs();
                }
                File file = new File(path, filename);
                try (FileOutputStream os = new FileOutputStream(file)) {
                    os.write(decoded);
                    fileUri = Uri.fromFile(file);
                }
                MediaScannerConnection.scanFile(this, new String[] { file.getAbsolutePath() },
                        new String[] { mimetype }, null);
            }

            if (fileUri != null) {
                final Uri finalUri = fileUri;
                // Text exports are kept locally so they stay readable offline.
                if (finalFilename.toLowerCase(Locale.US).endsWith(".md")
                        && decoded.length < ARCHIVE_MAX_CHARS_PER_CHAT) {
                    appendToArchive(finalFilename, new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
                }
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                            getString(R.string.download) + " " + finalFilename, Toast.LENGTH_SHORT).show();
                    showDownloadNotification(finalFilename, finalMimetype, finalUri);
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Blob download failed", e);
        }
    }

    private void startStandardDownload(String url, String userAgent, String contentDisposition,
            String mimetype, long contentLength) {
        Uri source = Uri.parse(url);
        DownloadManager.Request request = new DownloadManager.Request(source);
        request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        String filename = URLUtilCompat.getFilenameFromContentDisposition(contentDisposition);
        if (filename == null) {
            filename = URLUtilCompat.guessFileName(url, contentDisposition, mimetype);
        }
        filename = formatGeminiFilename(filename, mimetype);
        if (filename.endsWith(".md")) {
            mimetype = "text/markdown";
            request.setMimeType(mimetype);
        }

        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                DOWNLOAD_DIR + File.separator + filename);
        Toast.makeText(this, getString(R.string.download) + " " + filename, Toast.LENGTH_SHORT).show();
        DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (dm != null) {
            dm.enqueue(request);
        }
    }

    private boolean checkDownloadPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE },
                        DOWNLOAD_PERMISSION_REQUEST_CODE);
                return false;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS },
                        DOWNLOAD_PERMISSION_REQUEST_CODE);
                return false;
            }
        }
        return true;
    }

    private void showDownloadNotification(String filename, String mimeType, Uri uri) {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) return;

        String channelId = "gemini_downloads";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(channelId, "Downloads",
                    NotificationManager.IMPORTANCE_DEFAULT);
            notificationManager.createNotificationChannel(channel);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimeType != null ? mimeType : "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(filename)
                .setContentText("Download completed")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == DOWNLOAD_PERMISSION_REQUEST_CODE) {
            boolean granted = true;
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])
                        || Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])) {
                    granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                }
            }
            if (granted) {
                if (isPendingBlob && pendingBlobData != null) {
                    saveBlobToFile(pendingBlobData, pendingBlobMimetype, pendingBlobContentDisposition,
                            pendingBlobCurrentUrl);
                } else if (pendingDownloadUrl != null) {
                    startStandardDownload(pendingDownloadUrl, pendingDownloadUserAgent,
                            pendingDownloadContentDisposition, pendingDownloadMimetype,
                            pendingDownloadContentLength);
                }
            } else {
                Toast.makeText(this, R.string.download_permission_denied, Toast.LENGTH_SHORT).show();
            }
            clearPendingDownload();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show();
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                    mUploadMessage = null;
                }
            }
        }
    }

    private void clearPendingDownload() {
        pendingDownloadUrl = null;
        pendingDownloadUserAgent = null;
        pendingDownloadContentDisposition = null;
        pendingDownloadMimetype = null;
        pendingDownloadContentLength = 0;
        isPendingBlob = false;
        pendingBlobData = null;
        pendingBlobMimetype = null;
        pendingBlobContentDisposition = null;
        pendingBlobCurrentUrl = null;
    }

    // ------------------------------------------------------------------ banner

    /**
     * Transient red hint card (no buttons, not clickable), used e.g. after a file
     * share to tell the user to attach it in Gemini. It is a message, not a control,
     * and it removes itself - the app still has zero floating buttons.
     */
    private void showCustomBanner(String message) {
        runOnUiThread(() -> {
            View root = findViewById(android.R.id.content);
            if (!(root instanceof android.view.ViewGroup)) return;
            android.view.ViewGroup viewGroup = (android.view.ViewGroup) root;

            View oldBanner = viewGroup.findViewWithTag("attention_banner");
            if (oldBanner != null) {
                viewGroup.removeView(oldBanner);
            }

            android.widget.LinearLayout bannerCard = new android.widget.LinearLayout(this);
            bannerCard.setTag("attention_banner");
            bannerCard.setOrientation(android.widget.LinearLayout.VERTICAL);

            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            shape.setColor(getResources().getColor(R.color.bannerBackground, getTheme()));
            shape.setCornerRadius(12 * getResources().getDisplayMetrics().density);
            bannerCard.setBackground(shape);
            bannerCard.setElevation(16 * getResources().getDisplayMetrics().density);

            android.widget.TextView textView = new android.widget.TextView(this);
            textView.setText(message);
            textView.setTextColor(Color.WHITE);
            textView.setTextSize(16);
            textView.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            int padding = (int) (16 * getResources().getDisplayMetrics().density);
            textView.setPadding(padding, padding, padding, padding);
            textView.setGravity(android.view.Gravity.CENTER);
            bannerCard.addView(textView);

            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            int margin = (int) (16 * getResources().getDisplayMetrics().density);
            lp.setMargins(margin, margin + (int) (24 * getResources().getDisplayMetrics().density), margin, margin);
            lp.gravity = android.view.Gravity.TOP;

            bannerCard.setTranslationY(-300);
            viewGroup.addView(bannerCard, lp);

            bannerCard.animate()
                    .translationY(0)
                    .setDuration(400)
                    .setInterpolator(new android.view.animation.OvershootInterpolator())
                    .start();

            bannerCard.postDelayed(() -> bannerCard.animate()
                    .translationY(-400)
                    .setDuration(300)
                    .withEndAction(() -> viewGroup.removeView(bannerCard))
                    .start(), 6000);
        });
    }

    // ------------------------------------------------------------------ intents

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (chatWebView != null) {
            chatWebView.saveState(outState);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent, true);
    }

    private void handleIntent(Intent intent, boolean isResuming) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();
        Uri data = intent.getData();
        Log.d(TAG, "handleIntent: action=" + action + " type=" + type
                + " data=" + (data != null ? data.toString() : "null"));

        if (Intent.ACTION_VIEW.equals(action)) {
            if (data != null && isInternalHost(data.getHost())) {
                // "Ask Gemini" hands over the prompt as ?ask=... because Gemini has no
                // reliable ?q= deep link - the text is injected into the prompt box.
                String ask = data.getQueryParameter("ask");
                if (ask != null && !ask.isEmpty()) {
                    pendingSharedText = ask;
                    if (isGeminiUrl(chatWebView.getUrl())) {
                        injectPendingSharedText();
                    } else {
                        chatWebView.loadUrl(GEMINI_HOME);
                    }
                } else {
                    chatWebView.loadUrl(data.toString());
                }
            } else if (data != null) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, data));
                } catch (Exception e) {
                    Log.e(TAG, "No browser found for external link", e);
                }
                if (chatWebView.getUrl() == null || chatWebView.getUrl().isEmpty()
                        || chatWebView.getUrl().equals("about:blank")) {
                    chatWebView.loadUrl(GEMINI_HOME);
                }
            } else {
                chatWebView.loadUrl(GEMINI_HOME);
            }
            return;
        }

        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_PROCESS_TEXT.equals(action)) {
            String sharedText = null;

            if (type != null && (type.startsWith("image/") || "application/pdf".equals(type))) {
                Uri streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (streamUri != null) {
                    String ext = ".bin";
                    if (type.startsWith("image/")) {
                        ext = ".jpg";
                        if (type.contains("png")) ext = ".png";
                        else if (type.contains("webp")) ext = ".webp";
                        else if (type.contains("gif")) ext = ".gif";
                    } else if ("application/pdf".equals(type)) {
                        ext = ".pdf";
                    }
                    pendingSharedFileUri = saveUriToTempFile(streamUri, ext);
                }
            } else if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
                sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
                CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
                if (text != null) {
                    sharedText = text.toString();
                }
            }

            if (pendingSharedFileUri != null) {
                // Gemini has no "attach this URI" entry point: stage the file, tell the
                // user to tap the attach button, and let onShowFileChooser hand it over.
                showCustomBanner(getString(R.string.attach_shared_file));
                String docSuffix = prefs().getString(PREF_SHARED_DOC_SUFFIX, "");
                if (docSuffix != null && !docSuffix.trim().isEmpty()) {
                    pendingSharedText = docSuffix;
                }
                chatWebView.loadUrl(GEMINI_HOME);
            } else if (sharedText != null) {
                String suffix = prefs().getString(PREF_ASK_SUFFIX, "");
                if (suffix != null && !suffix.trim().isEmpty()) {
                    sharedText = sharedText + "\n\n" + suffix;
                }
                pendingSharedText = sharedText;
                if (isGeminiUrl(chatWebView.getUrl())) {
                    injectPendingSharedText();
                } else {
                    chatWebView.loadUrl(GEMINI_HOME);
                }
            } else {
                chatWebView.loadUrl(GEMINI_HOME);
            }
            return;
        }

        if (Intent.ACTION_ASSIST.equals(action)) {
            boolean autoFocusOnAssist = prefs().getBoolean(PREF_TRIGGER_VOICE_ASSISTANT, true);
            if (isGeminiUrl(chatWebView.getUrl())) {
                if (autoFocusOnAssist) {
                    safeEvaluateJavascript(chatWebView, AUTOFOCUS_JS);
                }
            } else {
                pendingAutoFocus = autoFocusOnAssist;
                chatWebView.loadUrl(GEMINI_HOME);
            }
            return;
        }

        if (Intent.ACTION_MAIN.equals(action) || action == null) {
            boolean autoFocus = prefs().getBoolean(PREF_AUTO_FOCUS_KEYBOARD, true);
            if (autoFocus) {
                if (isGeminiUrl(chatWebView.getUrl())) {
                    safeEvaluateJavascript(chatWebView, AUTOFOCUS_JS);
                } else {
                    pendingAutoFocus = true;
                    chatWebView.loadUrl(GEMINI_HOME);
                }
            } else if (chatWebView.getUrl() == null || chatWebView.getUrl().isEmpty()
                    || chatWebView.getUrl().equals("about:blank")) {
                chatWebView.loadUrl(GEMINI_HOME);
            }
            return;
        }

        if (chatWebView.getUrl() == null || chatWebView.getUrl().isEmpty()
                || chatWebView.getUrl().equals("about:blank")) {
            chatWebView.loadUrl(GEMINI_HOME);
        }
        if (isResuming) {
            Log.d(TAG, "onNewIntent with unrelated action, keeping current page");
        }
    }

    // -------------------------------------------------------------- JS injections

    /**
     * Generic blob patch. Gemini exports/downloads hand back blob: URLs whose payload
     * is unreachable from the download listener, so the Blob is kept in a map first.
     * (No duck.ai-specific selectors or paths here.)
     */
    private static final String BLOB_PATCH_JS = "(function() {" +
            "    if (window.geminiBlobPatchInjected) return;" +
            "    window.geminiBlobPatchInjected = true;" +
            "    window.blobMap = window.blobMap || new Map();" +
            "    var originalCreateObjectURL = URL.createObjectURL;" +
            "    URL.createObjectURL = function(b) {" +
            "        var u = originalCreateObjectURL.call(URL, b);" +
            "        try { if (b instanceof Blob) window.blobMap.set(u, b); } catch (e) {}" +
            "        return u;" +
            "    };" +
            "})();";

    /** Generic clipboard patch: Gemini's "copy" buttons go through the native clipboard. */
    private static final String CLIPBOARD_PATCH_JS = "(function() {" +
            "    if (window.geminiClipboardPatchInjected) return;" +
            "    window.geminiClipboardPatchInjected = true;" +
            "    if (navigator.clipboard) {" +
            "        navigator.clipboard.writeText = function(text) {" +
            "            return new Promise(function(resolve, reject) {" +
            "                try { Android.copyToClipboard(text); resolve(); }" +
            "                catch (e) { reject(e); }" +
            "            });" +
            "        };" +
            "    }" +
            "})();";

    /**
     * Input candidates in the Gemini web app, as ONE ready-to-use JavaScript string
     * literal (single selector list, so it can be dropped into querySelectorAll()).
     */
    private static final String GEMINI_INPUT_SELECTORS_JS =
            "\"rich-textarea [contenteditable='true'], " +
            "div[contenteditable='true'], " +
            "textarea, " +
            "[aria-label*='prompt' i], " +
            "[aria-label*='Ask Gemini' i], " +
            "[role='textbox']\"";

    /**
     * Focuses the Gemini prompt box: immediate attempt, then a 400 ms poll for ~10 s
     * plus a MutationObserver, because the app renders its composer asynchronously.
     */
    private static final String AUTOFOCUS_JS = "(function() {" +
            "  if (window.geminiAutofocusRunning) return;" +
            "  window.geminiAutofocusRunning = true;" +
            "  function isVisible(el) {" +
            "    if (!el || !el.getBoundingClientRect) return false;" +
            "    var r = el.getBoundingClientRect();" +
            "    return r.width > 0 && r.height > 0 && el.offsetParent !== null;" +
            "  }" +
            "  function findInput() {" +
            "    var els = document.querySelectorAll(" + GEMINI_INPUT_SELECTORS_JS + ");" +
            "    for (var i = 0; i < els.length; i++) {" +
            "      if (isVisible(els[i]) && !els[i].disabled && !els[i].readOnly) return els[i];" +
            "    }" +
            "    return null;" +
            "  }" +
            "  function focusInput() {" +
            "    var input = findInput();" +
            "    if (!input) return false;" +
            "    try { input.focus({ preventScroll: false }); } catch (e) { try { input.focus(); } catch (e2) {} }" +
            "    try { input.click(); } catch (e) {}" +
            "    try { if (window.Android && Android.showSoftKeyboard) Android.showSoftKeyboard(); } catch (e) {}" +
            "    return true;" +
            "  }" +
            "  function stop(observer, timer) {" +
            "    window.geminiAutofocusRunning = false;" +
            "    try { if (observer) observer.disconnect(); } catch (e) {}" +
            "    try { if (timer) clearInterval(timer); } catch (e) {}" +
            "  }" +
            "  var observer = null;" +
            "  var timer = null;" +
            "  if (focusInput()) { stop(null, null); return; }" +
            "  try {" +
            "    observer = new MutationObserver(function() {" +
            "      if (focusInput()) stop(observer, timer);" +
            "    });" +
            "    var root = document.body || document.documentElement;" +
            "    if (root) observer.observe(root, { childList: true, subtree: true });" +
            "  } catch (e) {}" +
            "  var attempts = 0;" +
            "  timer = setInterval(function() {" +
            "    attempts++;" +
            "    if (focusInput() || attempts >= 25) stop(observer, timer);" +
            "  }, 400);" +
            "})();";

    /**
     * Builds the "Ask Gemini"/share injection: writes the text into the Gemini prompt
     * box (React-safe setter + input/change events + focus) and retries with a
     * MutationObserver plus an interval until the composer exists.
     */
    private String buildInjectPromptJs(String text) {
        String quoted;
        try {
            quoted = JSONObject.quote(text);
        } catch (Exception e) {
            quoted = "\"\"";
        }
        return "(function() {" +
                "  var TEXT = " + quoted + ";" +
                "  if (window.geminiInjectRunning) return;" +
                "  window.geminiInjectRunning = true;" +
                "  function isVisible(el) {" +
                "    if (!el || !el.getBoundingClientRect) return false;" +
                "    var r = el.getBoundingClientRect();" +
                "    return r.width > 0 && r.height > 0 && el.offsetParent !== null;" +
                "  }" +
                "  function findInput() {" +
                "    var els = document.querySelectorAll(" + GEMINI_INPUT_SELECTORS_JS + ");" +
                "    for (var i = 0; i < els.length; i++) {" +
                "      if (isVisible(els[i]) && !els[i].disabled && !els[i].readOnly) return els[i];" +
                "    }" +
                "    return null;" +
                "  }" +
                "  function setValue(el) {" +
                "    var tag = (el.tagName || '').toUpperCase();" +
                "    try { el.focus(); } catch (e) {}" +
                "    if (tag === 'TEXTAREA' || tag === 'INPUT') {" +
                "      var proto = (tag === 'TEXTAREA') ? window.HTMLTextAreaElement.prototype" +
                "                                           : window.HTMLInputElement.prototype;" +
                "      var desc = Object.getOwnPropertyDescriptor(proto, 'value');" +
                "      if (desc && desc.set) { desc.set.call(el, TEXT); } else { el.value = TEXT; }" +
                "    } else {" +
                "      var inserted = false;" +
                "      try { inserted = document.execCommand('insertText', false, TEXT); } catch (e) {}" +
                "      if (!inserted) { el.textContent = TEXT; }" +
                "      if (!inserted) {" +
                "        try {" +
                "          var range = document.createRange();" +
                "          range.selectNodeContents(el);" +
                "          var sel = window.getSelection();" +
                "          sel.removeAllRanges();" +
                "          sel.addRange(range);" +
                "        } catch (e) {}" +
                "      }" +
                "    }" +
                "    try { el.dispatchEvent(new Event('input', { bubbles: true })); } catch (e) {}" +
                "    try { el.dispatchEvent(new Event('change', { bubbles: true })); } catch (e) {}" +
                "    try { el.focus(); } catch (e) {}" +
                "    try { el.scrollIntoView({ block: 'nearest' }); } catch (e) {}" +
                "    try { if (window.Android && Android.showSoftKeyboard) Android.showSoftKeyboard(); } catch (e) {}" +
                "    return true;" +
                "  }" +
                "  function tryInject() {" +
                "    var input = findInput();" +
                "    if (!input) return false;" +
                "    try { return setValue(input); } catch (e) { return false; }" +
                "  }" +
                "  function stop(observer, timer) {" +
                "    window.geminiInjectRunning = false;" +
                "    try { if (observer) observer.disconnect(); } catch (e) {}" +
                "    try { if (timer) clearInterval(timer); } catch (e) {}" +
                "  }" +
                "  var observer = null;" +
                "  var timer = null;" +
                "  if (tryInject()) { stop(null, null); return; }" +
                "  try {" +
                "    observer = new MutationObserver(function() {" +
                "      if (tryInject()) stop(observer, timer);" +
                "    });" +
                "    var root = document.body || document.documentElement;" +
                "    if (root) observer.observe(root, { childList: true, subtree: true });" +
                "  } catch (e) {}" +
                "  timer = setInterval(function() {" +
                "    if (tryInject()) stop(observer, timer);" +
                "  }, 500);" +
                "  setTimeout(function() { stop(observer, timer); }, 10000);" +
                "})();";
    }

    private void injectPendingSharedText() {
        if (pendingSharedText == null || pendingSharedText.isEmpty() || chatWebView == null) return;
        String text = pendingSharedText;
        pendingSharedText = null;
        pendingAutoFocus = false;
        safeEvaluateJavascript(chatWebView, buildInjectPromptJs(text));
    }

    /** Re-applies the generic patches after a navigation. */
    private void injectPatches(WebView view) {
        safeEvaluateJavascript(view, BLOB_PATCH_JS);
        safeEvaluateJavascript(view, CLIPBOARD_PATCH_JS);
    }

    // ------------------------------------------------------------------ clients

    private class MyWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri uri = Uri.parse(url);
            // Gemini and the whole Google account flow stay inside the app; every other
            // link (citations, help pages, external sites) goes to the system browser.
            if (isInternalHost(uri.getHost())) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception e) {
                Log.e(TAG, "Could not open link in browser", e);
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            if (progressBar != null) {
                progressBar.setVisibility(View.VISIBLE);
            }
            injectPatches(view);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
            injectPatches(view);

            // A shared prompt wins over a plain auto-focus (injection focuses too).
            if (pendingSharedText != null && !pendingSharedText.isEmpty()) {
                injectPendingSharedText();
            } else if (pendingAutoFocus || prefs().getBoolean(PREF_AUTO_FOCUS_KEYBOARD, true)) {
                pendingAutoFocus = false;
                safeEvaluateJavascript(view, AUTOFOCUS_JS);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame()
                    && isGeminiUrl(request.getUrl().toString())) {
                Log.d(TAG, "Main frame failed, showing the offline chats page");
                runOnUiThread(() -> view.loadUrl("file:///android_asset/chats_viewer.html"));
                return;
            }
            super.onReceivedError(view, request, error);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (isGeminiUrl(failingUrl)) {
                Log.d(TAG, "Main frame failed (legacy callback), showing the offline chats page");
                runOnUiThread(() -> view.loadUrl("file:///android_asset/chats_viewer.html"));
                return;
            }
            super.onReceivedError(view, errorCode, description, failingUrl);
        }
    }

    private class MyWebChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);
            if (newProgress > 5) {
                injectPatches(view);
            }
            if (newProgress == 100 && !isSafeMode) {
                if ((pendingSharedText == null || pendingSharedText.isEmpty())
                        && prefs().getBoolean(PREF_AUTO_FOCUS_KEYBOARD, true)) {
                    safeEvaluateJavascript(view, AUTOFOCUS_JS);
                }
            }
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
            Log.d(TAG, "JS console: " + consoleMessage.message() + " (line " + consoleMessage.lineNumber() + ")");
            return true;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            runOnUiThread(() -> {
                // Gemini's microphone button asks for audio; grant it (and video, if the
                // page asks for it) without a second web-level prompt.
                boolean wantsAudio = false;
                boolean wantsVideo = false;
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) wantsAudio = true;
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) wantsVideo = true;
                }
                if (wantsVideo && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[] { Manifest.permission.CAMERA }, CAMERA_PERMISSION_REQUEST_CODE);
                }
                if (wantsAudio) {
                    request.grant(new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE });
                } else if (wantsVideo) {
                    request.grant(new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE });
                } else {
                    request.deny();
                }
            });
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams) {
            // A shared image/PDF is consumed first, so "attach" immediately uploads it.
            if (pendingSharedFileUri != null) {
                filePathCallback.onReceiveValue(new Uri[] { pendingSharedFileUri });
                pendingSharedFileUri = null;
                return true;
            }
            if (mUploadMessage != null) {
                mUploadMessage.onReceiveValue(null);
            }
            mUploadMessage = filePathCallback;

            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle(R.string.select_option);
            builder.setItems(
                    new CharSequence[] { getString(R.string.select_camera), getString(R.string.select_file_manager) },
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (which == 0) {
                                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                    requestPermissions(new String[] { Manifest.permission.CAMERA },
                                            CAMERA_PERMISSION_REQUEST_CODE);
                                } else {
                                    openCamera();
                                }
                            } else {
                                openFileManager();
                            }
                        }
                    });
            builder.setOnCancelListener(dialog -> {
                if (mUploadMessage != null) {
                    mUploadMessage.onReceiveValue(null);
                    mUploadMessage = null;
                }
            });
            builder.show();
            return true;
        }
    }

    private void openCamera() {
        try {
            File photoFile = new File(getExternalCacheDir(),
                    "camera_photo_" + System.currentTimeMillis() + ".jpg");
            cameraImageUri = FileProvider.getUriForFile(this, FILEPROVIDER_AUTH, photoFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
            startActivityForResult(intent, CAMERA_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "Error opening camera", e);
            Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show();
            if (mUploadMessage != null) {
                mUploadMessage.onReceiveValue(null);
                mUploadMessage = null;
            }
        }
    }

    private void openFileManager() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(Intent.createChooser(i, getString(R.string.select_file_manager)), FILE_CHOOSER_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (mUploadMessage == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && intent != null && intent.getDataString() != null) {
                result = new Uri[] { Uri.parse(intent.getDataString()) };
            }
            mUploadMessage.onReceiveValue(result);
            mUploadMessage = null;
        } else if (requestCode == CAMERA_REQUEST_CODE) {
            if (mUploadMessage == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && cameraImageUri != null) {
                result = new Uri[] { cameraImageUri };
            }
            mUploadMessage.onReceiveValue(result);
            mUploadMessage = null;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
            if (chatWebView != null && chatWebView.canGoBack()) {
                chatWebView.goBack();
            } else {
                finish();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        // Privacy: drop the WebView HTTP cache on exit. Cookies are NEVER cleared, so
        // the Google login survives restarts.
        if (chatWebView != null) {
            chatWebView.clearCache(true);
            chatWebView.destroy();
        }
        super.onDestroy();
    }

    private Uri saveUriToTempFile(Uri uri, String extension) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) return null;
            File tempFile = new File(getCacheDir(), "shared_file_" + System.currentTimeMillis() + extension);
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                fos.flush();
            } finally {
                is.close();
            }
            return Uri.fromFile(tempFile);
        } catch (Exception e) {
            Log.e(TAG, "Error staging shared file", e);
            return null;
        }
    }
}
