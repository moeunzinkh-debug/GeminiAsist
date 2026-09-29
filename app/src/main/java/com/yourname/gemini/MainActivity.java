package com.yourname.gemini;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private View loadingSplash;
    private View errorPanel;
    private boolean pageFailed;
    private boolean filePickerActive;
    private boolean permissionPromptActive;
    private boolean destroyed;
    private ValueCallback<Uri[]> fileCallback;
    private Intent pendingImagePicker;
    private PermissionRequest pendingWebPermission;
    private Uri cameraOutputUri;
    private File cameraOutputFile;
    // Page text size in percent of the system default (WebView default is 100).
    // Gemini's web layout rendered far too large on phones, so the app pins it to 60%.
    // This is the only value to change for bigger/smaller text.
    private static final int WEB_TEXT_ZOOM = 60;

    // Keep successful capture files until the WebView is destroyed: it may still read them.
    private final List<File> capturedFiles = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable revealPage = () -> loadingSplash.setVisibility(View.GONE);

    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), this::onFilePickerResult);

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                permissionPromptActive = false;
                Intent picker = pendingImagePicker;
                pendingImagePicker = null;
                if (!destroyed && picker != null && fileCallback != null) {
                    launchFilePicker(picker, granted);
                }
            });

    private final ActivityResultLauncher<String[]> webPermissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                permissionPromptActive = false;
                completeWebPermission();
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        hideSystemBars();

        webView = findViewById(R.id.web_view);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        progressBar = findViewById(R.id.page_progress);
        loadingSplash = findViewById(R.id.loading_splash);
        errorPanel = findViewById(R.id.error_panel);
        configureWebView();
        swipeRefresh.setColorSchemeResources(R.color.gemini_accent);
        // The direct child is a FrameLayout, so query the WebView, not that container.
        swipeRefresh.setOnChildScrollUpCallback((parent, child) -> webView.canScrollVertically(-1));
        swipeRefresh.setOnRefreshListener(this::reloadPage);
        findViewById(R.id.retry_button).setOnClickListener(view -> reloadPage());
        findViewById(R.id.browser_button).setOnClickListener(view -> openBrowser(UrlPolicy.HOME_URL));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack();
                else finish();
            }
        });

        // Bound the in-app splash: a slow connection must not trap the user behind it.
        handler.postDelayed(revealPage, 15000);
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(UrlPolicy.HOME_URL);
        } else {
            revealLoadingPage();
        }
    }

    @SuppressWarnings("deprecation") // Legacy settings explicitly requested for API 24 support.
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        // Shrink the page text; the layout still fits the screen width.
        settings.setTextZoom(WEB_TEXT_ZOOM);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        // Do not permit local files to access arbitrary origins or other local files.
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // target=_blank links use the main frame, then our URL policy routes them.
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(true);
        }
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.gemini_background));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        // No UA spoofing, JavaScript bridge, SSL bypass, or release WebView debugging.
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String url = request.getUrl().toString();
                if (UrlPolicy.isInternal(url)) return false;
                if (UrlPolicy.isWebLink(url)) openBrowser(url);
                return true; // Reject file:, intent:, data:, javascript:, etc.
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                cancelWebPermission();
                cancelFileSelection();
                // Fallback: this runs after a request starts and is NOT a network firewall.
                // Do not replay unexpected POST navigations as a GET in another browser.
                if (!UrlPolicy.isInternal(url)) {
                    view.stopLoading();
                    showPageError();
                    return;
                }
                pageFailed = false;
                errorPanel.setVisibility(View.GONE);
                progressBar.setProgress(0);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override public void onPageCommitVisible(WebView view, String url) {
                revealLoadingPage();
            }

            @Override public void onPageFinished(WebView view, String url) {
                finishLoading();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showPageError();
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                // Leave sign-in error pages visible so Google's explanation isn't hidden.
                if (request.isForMainFrame() && response.getStatusCode() >= 500) showPageError();
            }
            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler sslHandler,
                                           android.net.http.SslError error) {
                sslHandler.cancel(); // Never bypass a certificate failure.
                if (java.util.Objects.equals(error.getUrl(), view.getUrl())) showPageError();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
                progressBar.setVisibility(progress < 100 && !pageFailed ? View.VISIBLE : View.GONE);
                if (progress == 100) finishLoading();
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (!UrlPolicy.isGemini(view.getUrl()) || fileCallback != null || filePickerActive || permissionPromptActive) {
                    callback.onReceiveValue(null);
                    return true;
                }
                fileCallback = callback;
                prepareFilePicker(params);
                return true;
            }

            @Override public void onPermissionRequest(PermissionRequest request) {
                handleWebPermission(request);
            }

            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermission == request) pendingWebPermission = null;
            }

        });
    }

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show();
        }
    }

    private void handleWebPermission(PermissionRequest request) {
        if (destroyed || permissionPromptActive || pendingWebPermission != null || fileCallback != null || filePickerActive
                || !UrlPolicy.isGemini(request.getOrigin().toString()) || !UrlPolicy.isGemini(webView.getUrl())) {
            request.deny();
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String resource : request.getResources()) {
            String permission = androidPermissionFor(resource);
            if (permission == null) {
                request.deny(); // Never grant newly introduced/unknown WebView resources.
                return;
            }
            if (!hasPermission(permission) && !missing.contains(permission)) missing.add(permission);
        }
        pendingWebPermission = request;
        if (missing.isEmpty()) completeWebPermission();
        else {
            permissionPromptActive = true;
            webPermissions.launch(missing.toArray(new String[0]));
        }
    }

    private String androidPermissionFor(String resource) {
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) return Manifest.permission.CAMERA;
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) return Manifest.permission.RECORD_AUDIO;
        return null;
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void completeWebPermission() {
        PermissionRequest request = pendingWebPermission;
        pendingWebPermission = null;
        if (request == null) return;
        if (destroyed || !UrlPolicy.isGemini(webView.getUrl()) || !UrlPolicy.isGemini(request.getOrigin().toString())) {
            request.deny();
            return;
        }
        List<String> granted = new ArrayList<>();
        for (String resource : request.getResources()) {
            String permission = androidPermissionFor(resource);
            if (permission != null && hasPermission(permission)) granted.add(resource);
        }
        if (granted.isEmpty()) request.deny();
        else request.grant(granted.toArray(new String[0]));
        if (granted.size() < request.getResources().length) {
            Toast.makeText(this, R.string.media_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void cancelWebPermission() {
        if (pendingWebPermission != null) {
            pendingWebPermission.deny();
            pendingWebPermission = null;
        }
    }

    private void prepareFilePicker(WebChromeClient.FileChooserParams params) {
        Set<String> types = new LinkedHashSet<>();
        String[] accept = params.getAcceptTypes();
        if (accept != null) {
            for (String entry : accept) {
                if (entry == null) continue;
                for (String item : entry.split(",")) {
                    String type = item.trim().toLowerCase(java.util.Locale.ROOT);
                    if (type.startsWith(".")) {
                        type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.substring(1));
                    }
                    if (type != null && type.matches("[a-z0-9*.+-]+/[a-z0-9*.+-]+")) types.add(type);
                }
            }
        }
        Intent picker = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType(types.size() == 1 ? types.iterator().next() : "*/*");
        if (types.size() > 1) picker.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
        boolean images = types.isEmpty() || types.contains("*/*");
        for (String type : types) if (type.startsWith("image/")) images = true;
        if (images && !hasPermission(Manifest.permission.CAMERA)) {
            pendingImagePicker = picker;
            permissionPromptActive = true;
            cameraPermission.launch(Manifest.permission.CAMERA);
        } else launchFilePicker(picker, images);
    }

    private void launchFilePicker(Intent picker, boolean offerCamera) {
        Intent chooser = Intent.createChooser(picker, getString(R.string.choose_file));
        if (offerCamera && hasPermission(Manifest.permission.CAMERA)) {
            try {
                File directory = new File(getCacheDir(), "images");
                if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create image cache");
                cameraOutputFile = File.createTempFile("upload_", ".jpg", directory);
                cameraOutputUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", cameraOutputFile);
                Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
                camera.setClipData(ClipData.newRawUri("photo", cameraOutputUri));
                camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if (camera.resolveActivity(getPackageManager()) != null) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                } else discardPendingCapture();
            } catch (IOException | IllegalArgumentException | SecurityException ignored) {
                discardPendingCapture(); // File selection remains available without camera support.
            }
        }
        try {
            filePickerActive = true;
            filePicker.launch(chooser);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            filePickerActive = false;
            cancelFileSelection();
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    private void onFilePickerResult(ActivityResult result) {
        filePickerActive = false;
        if (fileCallback == null || destroyed) {
            discardPendingCapture();
            return;
        }
        List<Uri> selected = new ArrayList<>();
        if (result.getResultCode() == Activity.RESULT_OK) {
            Intent data = result.getData();
            if (data != null && data.getClipData() != null) {
                ClipData clips = data.getClipData();
                for (int i = 0; i < clips.getItemCount(); i++) addSelectedUri(selected, clips.getItemAt(i).getUri());
            } else if (data != null && data.getData() != null) {
                addSelectedUri(selected, data.getData());
            }
            if (selected.isEmpty() && cameraOutputFile != null && cameraOutputFile.length() > 0) {
                selected.add(cameraOutputUri);
            }
        }
        if (cameraOutputUri != null && selected.contains(cameraOutputUri)) {
            capturedFiles.add(cameraOutputFile);
            revokeUriPermission(cameraOutputUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            cameraOutputFile = null;
            cameraOutputUri = null;
        } else discardPendingCapture();
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        callback.onReceiveValue(selected.isEmpty() ? null : selected.toArray(new Uri[0]));
    }

    private void addSelectedUri(List<Uri> selected, Uri uri) {
        // Never pass arbitrary file:// paths or another private file from our provider to the web.
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) return;
        if ((getPackageName() + ".fileprovider").equals(uri.getAuthority()) && !uri.equals(cameraOutputUri)) return;
        selected.add(uri);
    }

    private void cancelFileSelection() {
        pendingImagePicker = null;
        if (fileCallback != null) {
            ValueCallback<Uri[]> callback = fileCallback;
            fileCallback = null;
            callback.onReceiveValue(null);
        }
        discardPendingCapture();
    }

    private void discardPendingCapture() {
        if (cameraOutputUri != null) {
            revokeUriPermission(cameraOutputUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
        if (cameraOutputFile != null) cameraOutputFile.delete();
        cameraOutputFile = null;
        cameraOutputUri = null;
    }

    private void reloadPage() {
        pageFailed = false;
        errorPanel.setVisibility(View.GONE);
        if (UrlPolicy.isInternal(webView.getUrl())) webView.reload();
        else webView.loadUrl(UrlPolicy.HOME_URL);
    }

    private void revealLoadingPage() {
        handler.removeCallbacks(revealPage);
        loadingSplash.setVisibility(View.GONE);
    }

    private void finishLoading() {
        revealLoadingPage();
        progressBar.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
    }

    private void showPageError() {
        pageFailed = true;
        finishLoading();
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        cancelWebPermission();
        cancelFileSelection();
        ((ViewGroup) webView.getParent()).removeView(webView);
        webView.stopLoading();
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        webView.destroy();
        for (File capture : capturedFiles) capture.delete();
        super.onDestroy();
    }
}
