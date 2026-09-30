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
import android.webkit.JavascriptInterface;
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
    // True once the page (or its inner chat scroller) has scrolled below the
    // top. Reported from JS because the native WebView scroll position stays 0.
    private volatile boolean pageScrolledDown;
    private ValueCallback<Uri[]> fileCallback;
    private Intent pendingImagePicker;
    private PermissionRequest pendingWebPermission;
    private Uri cameraOutputUri;
    private File cameraOutputFile;
    // User-requested text size: 50%. The injected LAYOUT_FIX_JS clamps the page
    // to the viewport width and kills horizontal scroll so the smaller text
    // never drifts off the left edge of the screen.
    private static final int WEB_TEXT_ZOOM = 50;

    // Injected page fix:
    //  1. Clamp the layout to the viewport width and kill horizontal scroll so
    //     text never disappears off the left edge at 50% text size.
    //  2. Report whether the page (or its inner chat scroller) is below the
    //     top through the AndroidScroll bridge, so pull-to-refresh only
    //     triggers when there is nothing above to scroll to. Gemini scrolls an
    //     inner div, not the WebView, so the native scroll APIs cannot see it.
    //  3. "+"-row only: a strip whose content really overflows horizontally
    //     but is clipped (overflow-x hidden/clip) can never be dragged, so it
    //     is turned into a touch scroller and freed from the max-width clamp.
    //     Elements whose content fits are never touched, so nothing outside
    //     that row is affected.
    private static final String LAYOUT_FIX_JS =
            "(function(){\n" +
            "  try{\n" +
            "    if(window.__geminiFix) return;\n" +
            "    window.__geminiFix=true;\n" +
            "    var css='html{overflow-x:hidden!important;max-width:100vw!important;} body{overflow-x:hidden!important;padding-left:12px!important;padding-right:12px!important;box-sizing:border-box!important;max-width:100vw!important;overscroll-behavior-y:contain!important;margin:0!important;} *{box-sizing:border-box!important;max-width:100%!important;word-wrap:break-word!important;overflow-wrap:break-word!important;} img,video{max-width:100%!important;height:auto!important;} main,[role=main],.chat-history{max-width:100vw!important;overflow-x:hidden!important;} .gax-hscroll,.gax-hscroll *{max-width:none!important;} .gax-round{zoom:.8;}';\n" +
            "    var vpWant='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover';\n" +
            "    function fixLayout(){\n" +
            "      try{\n" +
            "        var el=document.getElementById('gemini-fix');\n" +
            "        if(!el){el=document.createElement('style');el.id='gemini-fix';(document.head||document.documentElement).appendChild(el);}\n" +
            "        if(el.textContent!==css){el.textContent=css;}\n" +
            "        var vp=document.querySelector('meta[name=viewport]');\n" +
            "        if(!vp){vp=document.createElement('meta');vp.name='viewport';(document.head||document.documentElement).appendChild(vp);}\n" +
            "        if(vp.getAttribute('content')!==vpWant){vp.setAttribute('content',vpWant);}\n" +
            "        var sx=window.scrollX||document.documentElement.scrollLeft||0;\n" +
            "        if(sx!==0){window.scrollTo(0,window.scrollY||document.documentElement.scrollTop||0);}\n" +
            "      }catch(e){}\n" +
            "    }\n" +
            "    var scrollers=[];\n" +
            "    function collectScrollers(){\n" +
            "      try{\n" +
            "        scrollers=[];\n" +
            "        var all=document.querySelectorAll('*');\n" +
            "        for(var i=0;i<all.length;i++){\n" +
            "          var n=all[i];\n" +
            "          if(n.scrollHeight>n.clientHeight+1){\n" +
            "            var ov='';\n" +
            "            try{ov=getComputedStyle(n).overflowY;}catch(e2){}\n" +
            "            if(ov==='auto'||ov==='scroll'){scrollers.push(n);}\n" +
            "          }\n" +
            "        }\n" +
            "      }catch(e){}\n" +
            "    }\n" +
            "    function fixHScroll(){\n" +
            "      try{\n" +
            "        var all=document.querySelectorAll('*');\n" +
            "        for(var i=0;i<all.length;i++){\n" +
            "          var el=all[i];\n" +
            "          if(el.__gaxH){continue;}\n" +
            "          var ovx='';\n" +
            "          try{ovx=getComputedStyle(el).overflowX;}catch(e2){}\n" +
            "          if(ovx==='auto'||ovx==='scroll'){\n" +
            "            el.__gaxH=1;\n" +
            "            el.classList.add('gax-hscroll');\n" +
            "            el.style.setProperty('-webkit-overflow-scrolling','touch');\n" +
            "            try{\n" +
            "              var probe=el.querySelector('button')||el.firstElementChild;\n" +
            "              if(probe){\n" +
            "                var t=probe.firstElementChild||probe;\n" +
            "                var br=getComputedStyle(t).borderRadius||'';\n" +
            "                var pxx=parseFloat(br)||0;\n" +
            "                if(br.indexOf('%')>-1||pxx>=24){el.classList.add('gax-round');}\n" +
            "              }\n" +
            "            }catch(e3){}\n" +
            "          } else if((ovx==='hidden'||ovx==='clip') && el.scrollWidth>el.clientWidth+1){\n" +
            "            el.__gaxH=1;\n" +
            "            el.classList.add('gax-hscroll');\n" +
            "            el.style.setProperty('overflow-x','auto');\n" +
            "            el.style.setProperty('-webkit-overflow-scrolling','touch');\n" +
            "            try{\n" +
            "              var probe2=el.querySelector('button')||el.firstElementChild;\n" +
            "              if(probe2){\n" +
            "                var t2=probe2.firstElementChild||probe2;\n" +
            "                var br2=getComputedStyle(t2).borderRadius||'';\n" +
            "                if(br2.indexOf('%')>-1||(parseFloat(br2)||0)>=24){el.classList.add('gax-round');}\n" +
            "              }\n" +
            "            }catch(e4){}\n" +
            "          }\n" +
            "        }\n" +
            "      }catch(e){}\n" +
            "    }\n" +
            "    function canScrollUp(){\n" +
            "      try{\n" +
            "        if((window.scrollY||document.documentElement.scrollTop||0)>1){return true;}\n" +
            "        for(var i=0;i<scrollers.length;i++){\n" +
            "          var n=scrollers[i];\n" +
            "          if(n&&n.parentNode&&n.scrollTop>1&&n.scrollHeight>n.clientHeight+1){return true;}\n" +
            "        }\n" +
            "      }catch(e){}\n" +
            "      return false;\n" +
            "    }\n" +
            "    var lastState=-1;\n" +
            "    var queued=false;\n" +
            "    function report(){\n" +
            "      if(queued){return;}\n" +
            "      queued=true;\n" +
            "      var run=function(){\n" +
            "        queued=false;\n" +
            "        try{\n" +
            "          var up=canScrollUp()?1:0;\n" +
            "          if(up!==lastState){lastState=up;if(window.AndroidScroll){window.AndroidScroll.setCanScrollUp(up);}}\n" +
            "        }catch(e){}\n" +
            "      };\n" +
            "      if(window.requestAnimationFrame){window.requestAnimationFrame(run);}else{setTimeout(run,32);}\n" +
            "    }\n" +
            "    fixLayout();fixHScroll();collectScrollers();report();\n" +
            "    try{new MutationObserver(fixLayout).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}\n" +
            "    window.addEventListener('scroll',report,true);\n" +
            "    window.addEventListener('resize',function(){fixLayout();fixHScroll();collectScrollers();report();});\n" +
            "    window.addEventListener('load',function(){fixHScroll();collectScrollers();report();});\n" +
            "    setInterval(function(){fixLayout();fixHScroll();collectScrollers();report();},2000);\n" +
            "  }catch(e){}\n" +
            "})();";

    // Bridge used by LAYOUT_FIX_JS to tell us whether the page can still
    // scroll up. @JavascriptInterface methods run on the WebView thread, so
    // hop to the UI thread before touching SwipeRefreshLayout.
    private final Object scrollBridge = new Object() {
        @JavascriptInterface
        public void setCanScrollUp(final int canScrollUp) {
            runOnUiThread(() -> {
                if (destroyed || swipeRefresh == null) return;
                boolean scrolledDown = canScrollUp == 1;
                if (pageScrolledDown == scrolledDown) return;
                pageScrolledDown = scrolledDown;
                // Only allow pull-to-refresh when there is nothing above.
                // Otherwise leave the gesture to the page so the chat can
                // scroll freely up and down.
                swipeRefresh.setEnabled(!scrolledDown);
            });
        }
    };

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
        swipeRefresh.setDistanceToTriggerSync(350);
        // Scroll fix: Gemini scrolls an inner div (the chat history), not the
        // WebView itself, so webView.getScrollY()/canScrollVertically() always
        // look like "top" and SwipeRefreshLayout steals every downward drag,
        // leaving the chat impossible to scroll. The page now reports its real
        // scroll position through the AndroidScroll JS bridge
        // (pageScrolledDown), and pull-to-refresh is only allowed when
        // nothing is above.
        swipeRefresh.setOnChildScrollUpCallback((parent, child) ->
                pageScrolledDown || webView.getScrollY() > 0 || webView.canScrollVertically(-1));
        swipeRefresh.setOnRefreshListener(this::reloadPage);
        findViewById(R.id.retry_button).setOnClickListener(view -> reloadPage());
        findViewById(R.id.browser_button).setOnClickListener(view -> openBrowser(UrlPolicy.HOME_URL));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack();
                else finish();
            }
        });

        handler.postDelayed(revealPage, 15000);
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(UrlPolicy.HOME_URL);
        } else {
            revealLoadingPage();
        }
    }

    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // FIX for bug 1: was true causing overview zoom out and left cut
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setTextZoom(WEB_TEXT_ZOOM);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(true);
        }
        // FIX for scroll bug
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(true);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setScrollbarFadingEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.setNestedScrollingEnabled(true);
        }
        // Pin the scale to exactly 100% so the page renders at device width
        // and cannot zoom-shift content off the left edge at 50% text size.
        webView.setInitialScale(100);
        // Scroll-state bridge for the injected layout fix (see scrollBridge).
        webView.addJavascriptInterface(scrollBridge, "AndroidScroll");
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.gemini_background));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String url = request.getUrl().toString();
                if (UrlPolicy.isInternal(url)) return false;
                if (UrlPolicy.isWebLink(url)) openBrowser(url);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                cancelWebPermission();
                cancelFileSelection();
                if (!UrlPolicy.isInternal(url)) {
                    view.stopLoading();
                    showPageError();
                    return;
                }
                pageFailed = false;
                // A new page starts at the top until JS proves otherwise.
                pageScrolledDown = false;
                swipeRefresh.setEnabled(true);
                errorPanel.setVisibility(View.GONE);
                progressBar.setProgress(0);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override public void onPageCommitVisible(WebView view, String url) {
                revealLoadingPage();
                injectLayoutFix();
            }

            @Override public void onPageFinished(WebView view, String url) {
                finishLoading();
                injectLayoutFix();
                handler.postDelayed(() -> injectLayoutFix(), 500);
                handler.postDelayed(() -> injectLayoutFix(), 1500);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showPageError();
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 500) showPageError();
            }
            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler sslHandler,
                                           android.net.http.SslError error) {
                sslHandler.cancel();
                if (java.util.Objects.equals(error.getUrl(), view.getUrl())) showPageError();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
                progressBar.setVisibility(progress < 100 && !pageFailed ? View.VISIBLE : View.GONE);
                if (progress == 100) {
                    finishLoading();
                    injectLayoutFix();
                }
                if (progress > 30 && progress < 90) {
                    injectLayoutFix();
                }
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

    private void injectLayoutFix() {
        if (webView == null || destroyed) return;
        try {
            webView.evaluateJavascript(LAYOUT_FIX_JS, null);
        } catch (Exception ignored) {}
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
                request.deny();
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
                discardPendingCapture();
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
