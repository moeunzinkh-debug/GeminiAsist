package com.yourname.voicestudio;

import com.yourname.gemini.R;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
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
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Voice Studio tag — the SRT Smart Voice Studio (Hugging Face Space
 * {@code takumi0002-tts-kh.hf.space}) in a full-screen WebView.
 *
 * <p>Same shell as the other two tags, plus what a file-producing tool needs:
 * <ul>
 *   <li>file upload for the page's "open .srt/.txt" and audio inputs;</li>
 *   <li>real downloads — network downloads go through {@link DownloadManager},
 *       and the {@code blob:} URLs Gradio uses for its MP3/SRT exports are
 *       streamed through the {@code VoiceDownload} bridge (see
 *       {@link BlobDownloadSaver}) into the phone's Downloads folder;</li>
 *   <li>60% text size, re-applied on every page event because the WebView
 *       resets {@code textZoom} on some navigations.</li>
 * </ul>
 */
public class VoiceStudioMainActivity extends AppCompatActivity {
    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private View loadingSplash;
    private View errorPanel;
    private boolean pageFailed;
    private boolean filePickerActive;
    private boolean permissionPromptActive;
    private boolean destroyed;
    // True once the page (or one of its inner panes) has scrolled below the top.
    // Reported from JS because the native WebView scroll position stays 0 behind
    // Gradio's own scroll containers.
    private volatile boolean pageScrolledDown;
    // Mirror of webView.getUrl() that is safe to read from the WebView's
    // JavaBridge thread: webView.getUrl() itself may only be called from the
    // UI thread. The bridges use it to refuse calls coming from anywhere but
    // the Space (e.g. a frame that somehow loaded from another origin).
    private volatile String currentPageUrl;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingWebPermission;
    private final BlobDownloadSaver blobSaver = new BlobDownloadSaver(this);

    // Renderer-death bookkeeping. Rebuilding the WebView after a renderer dies
    // is the right thing to do, but a device that keeps killing the renderer
    // must not be pushed into an endless rebuild → crash → rebuild loop, so
    // only a few rebuilds are allowed per time window.
    private static final int MAX_RENDERER_DEATHS = 3;
    private static final long RENDERER_DEATH_WINDOW_MS = 30_000L;
    private int rendererDeaths;
    private long firstRendererDeathAt;
    // The current WebView's renderer is gone, so it can never paint again:
    // "Try again" has to build a new one instead of calling reload().
    private boolean rendererGone;

    // User-requested text size: 60%. The injected LAYOUT_FIX_JS clamps the page
    // to the viewport width and kills horizontal scroll so the smaller text
    // never drifts off the left edge. NOTE: the WebView resets textZoom back to
    // 100% on some navigations, so applyTextZoom() re-asserts this value on
    // every page event.
    private static final int WEB_TEXT_ZOOM = 60;

    /**
     * Injected page fix (Voice Studio edition):
     * <ol>
     *   <li>clamp the layout to the screen width and kill horizontal scroll so
     *       60% text never disappears off the left edge;</li>
     *   <li>turn genuinely overflow-clipped rows (long SRT lines, wide tables)
     *       into touch scrollers that can actually be dragged;</li>
     *   <li>report the real scroll position through the AndroidScroll bridge so
     *       pull-to-refresh only triggers when there is nothing above;</li>
     *   <li>capture {@code blob:} downloads and stream them to the app so MP3
     *       and SRT exports land in the phone's Downloads folder;</li>
     *   <li>watchdog: a hidden 100px-font probe measures its rendered line
     *       height twice a second and asks the app to re-apply 60% whenever the
     *       WebView has silently reset textZoom to 100%.</li>
     * </ol>
     */
    private static final String LAYOUT_FIX_JS =
            "(function(){\n" +
            "  try{\n" +
            "    if(window.__voiceStudioFix){return;}\n" +
            "    window.__voiceStudioFix=true;\n" +
            "\n" +
            "    // 1. Layout clamp: pin the page to the screen width so 60% text never\n" +
            "    //    drifts off the left edge and the view can never be panned sideways.\n" +
            "    var css='html{overflow-x:hidden!important;max-width:100vw!important;}'\n" +
            "      +'body{overflow-x:hidden!important;box-sizing:border-box!important;max-width:100vw!important;overscroll-behavior-y:contain!important;margin:0!important;}'\n" +
            "      +'img,video,audio,canvas{max-width:100%!important;height:auto!important;}'\n" +
            "      +'textarea,input,select{max-width:100%!important;}'\n" +
            "      +'gradio-app,.gradio-container{max-width:100vw!important;}'\n" +
            "      +'.vs-hscroll{max-width:none!important;}';\n" +
            "    var vpWant='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover';\n" +
            "\n" +
            "    function fixLayout(){\n" +
            "      try{\n" +
            "        var el=document.getElementById('voice-studio-fix');\n" +
            "        if(!el){el=document.createElement('style');el.id='voice-studio-fix';(document.head||document.documentElement).appendChild(el);}\n" +
            "        if(el.textContent!==css){el.textContent=css;}\n" +
            "        var vp=document.querySelector('meta[name=viewport]');\n" +
            "        if(!vp){vp=document.createElement('meta');vp.name='viewport';(document.head||document.documentElement).appendChild(vp);}\n" +
            "        if(vp.getAttribute('content')!==vpWant){vp.setAttribute('content',vpWant);}\n" +
            "        var sx=window.scrollX||document.documentElement.scrollLeft||0;\n" +
            "        if(sx!==0){window.scrollTo(0,window.scrollY||document.documentElement.scrollTop||0);}\n" +
            "      }catch(e){}\n" +
            "    }\n" +
            "\n" +
            "    // 2. SRT editor rows / wide tables whose content really overflows but is\n" +
            "    //    clipped can never be dragged, so they become touch scrollers. Nothing\n" +
            "    //    is touched unless its content actually overflows.\n" +
            "    function fixHScroll(){\n" +
            "      try{\n" +
            "        var all=document.querySelectorAll('*');\n" +
            "        for(var i=0;i<all.length;i++){\n" +
            "          var el=all[i];\n" +
            "          if(el.__vsH){continue;}\n" +
            "          var ovx='';\n" +
            "          try{ovx=getComputedStyle(el).overflowX;}catch(e2){}\n" +
            "          if(ovx==='auto'||ovx==='scroll'){\n" +
            "            el.__vsH=1;\n" +
            "            el.classList.add('vs-hscroll');\n" +
            "            el.style.setProperty('-webkit-overflow-scrolling','touch');\n" +
            "          } else if((ovx==='hidden'||ovx==='clip')&&el.scrollWidth>el.clientWidth+1){\n" +
            "            el.__vsH=1;\n" +
            "            el.classList.add('vs-hscroll');\n" +
            "            el.style.setProperty('overflow-x','auto');\n" +
            "            el.style.setProperty('-webkit-overflow-scrolling','touch');\n" +
            "          }\n" +
            "        }\n" +
            "      }catch(e){}\n" +
            "    }\n" +
            "\n" +
            "    // 3. Pull-to-refresh only when there is nothing above to scroll to. Gradio\n" +
            "    //    scrolls inner panes instead of the WebView, so the native scroll APIs\n" +
            "    //    cannot see the real position.\n" +
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
            "\n" +
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
            "\n" +
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
            "\n" +
            "    // 4. Exports. Gradio hands MP3/SRT downloads to the browser as blob: URLs,\n" +
            "    //    which a WebView cannot save by itself, so every blob the page creates\n" +
            "    //    is remembered and streamed to the app (VoiceDownload bridge), which\n" +
            "    //    writes it into the phone's Downloads folder.\n" +
            "    var blobs={};\n" +
            "    var blobOrder=[];\n" +
            "    var sendBusy=false;\n" +
            "    var SLICE=262144;\n" +
            "\n" +
            "    var nativeCreate=window.URL&&URL.createObjectURL;\n" +
            "    if(nativeCreate&&!nativeCreate.__vsPatched){\n" +
            "      var patchedCreate=function(blob){\n" +
            "        var url=nativeCreate.call(URL,blob);\n" +
            "        try{\n" +
            "          blobs[url]=blob;\n" +
            "          blobOrder.push(url);\n" +
            "          while(blobOrder.length>8){delete blobs[blobOrder.shift()];}\n" +
            "        }catch(e){}\n" +
            "        return url;\n" +
            "      };\n" +
            "      patchedCreate.__vsPatched=true;\n" +
            "      URL.createObjectURL=patchedCreate;\n" +
            "    }\n" +
            "\n" +
            "    function send(blob,name){\n" +
            "      if(!blob||!window.VoiceDownload||sendBusy){return;}\n" +
            "      sendBusy=true;\n" +
            "      var id='d'+Date.now();\n" +
            "      var file=name||'voice-studio-file';\n" +
            "      try{\n" +
            "        if(window.VoiceDownload.begin(id,file,blob.type||'')!==true){sendBusy=false;return;}\n" +
            "      }catch(e){sendBusy=false;return;}\n" +
            "      if(!blob.size){\n" +
            "        sendBusy=false;\n" +
            "        try{window.VoiceDownload.finish(id);}catch(e2){}\n" +
            "        return;\n" +
            "      }\n" +
            "      var reader=new FileReader();\n" +
            "      var index=0;\n" +
            "      function step(){\n" +
            "        if(index>=blob.size){\n" +
            "          sendBusy=false;\n" +
            "          try{window.VoiceDownload.finish(id);}catch(e){}\n" +
            "          return;\n" +
            "        }\n" +
            "        var slice=blob.slice(index,index+SLICE);\n" +
            "        index+=SLICE;\n" +
            "        reader.onload=function(){\n" +
            "          var data=String(reader.result);\n" +
            "          var comma=data.indexOf(',');\n" +
            "          var payload=comma>=0?data.substring(comma+1):data;\n" +
            "          var ok=false;\n" +
            "          try{ok=window.VoiceDownload.append(id,payload)===true;}catch(e){ok=false;}\n" +
            "          if(!ok){\n" +
            "            sendBusy=false;\n" +
            "            try{window.VoiceDownload.fail(id);}catch(e2){}\n" +
            "            return;\n" +
            "          }\n" +
            "          step();\n" +
            "        };\n" +
            "        reader.onerror=function(){\n" +
            "          sendBusy=false;\n" +
            "          try{window.VoiceDownload.fail(id);}catch(e3){}\n" +
            "        };\n" +
            "        reader.readAsDataURL(slice);\n" +
            "      }\n" +
            "      step();\n" +
            "    }\n" +
            "\n" +
            "    function blobName(anchor){\n" +
            "      var name='';\n" +
            "      try{name=anchor.getAttribute('download')||'';}catch(e){}\n" +
            "      return name||'voice-studio-file';\n" +
            "    }\n" +
            "\n" +
            "    function takeBlob(url,name){\n" +
            "      try{\n" +
            "        if(blobs[url]){send(blobs[url],name);return true;}\n" +
            "        fetch(url).then(function(r){return r.blob();}).then(function(b){send(b,name);}).catch(function(){});\n" +
            "        return true;\n" +
            "      }catch(e){}\n" +
            "      return false;\n" +
            "    }\n" +
            "\n" +
            "    // Called by the app when a blob: navigation slips past the click hooks.\n" +
            "    window.__vsGrabBlob=function(url,name){return takeBlob(url,name);};\n" +
            "\n" +
            "    document.addEventListener('click',function(ev){\n" +
            "      try{\n" +
            "        var el=ev.target;\n" +
            "        while(el&&el.nodeType!==1){el=el.parentNode;}\n" +
            "        while(el&&el!==document&&el.tagName!=='A'){el=el.parentNode;}\n" +
            "        if(!el||el.tagName!=='A'){return;}\n" +
            "        var href=el.href||'';\n" +
            "        if(href.indexOf('blob:')!==0){return;}\n" +
            "        if(takeBlob(href,blobName(el))){\n" +
            "          ev.preventDefault();\n" +
            "          ev.stopPropagation();\n" +
            "        }\n" +
            "      }catch(e){}\n" +
            "    },true);\n" +
            "\n" +
            "    var nativeOpen=window.open;\n" +
            "    if(nativeOpen&&!nativeOpen.__vsPatched){\n" +
            "      var patchedOpen=function(url){\n" +
            "        if(typeof url==='string'&&url.indexOf('blob:')===0&&takeBlob(url,'voice-studio-file')){return null;}\n" +
            "        return nativeOpen.apply(window,arguments);\n" +
            "      };\n" +
            "      patchedOpen.__vsPatched=true;\n" +
            "      window.open=patchedOpen;\n" +
            "    }\n" +
            "\n" +
            "    // 5. Text-size watchdog: a hidden 100px probe measures its rendered line\n" +
            "    //    height twice a second. If the WebView reset textZoom (text back at\n" +
            "    //    ~100%) the app is told to re-apply the user's 60% immediately,\n" +
            "    //    whatever the reset timing was.\n" +
            "    try{\n" +
            "      var probe=document.createElement('div');\n" +
            "      probe.style.cssText='position:fixed!important;left:-9999px!important;top:0!important;visibility:hidden;font-size:100px;line-height:normal;white-space:nowrap;width:auto!important;max-width:none!important;padding:0!important;margin:0!important;border:0!important;';\n" +
            "      probe.textContent='vsx';\n" +
            "      (document.body||document.documentElement).appendChild(probe);\n" +
            "      setInterval(function(){\n" +
            "        try{\n" +
            "          var h=probe.getBoundingClientRect().height;\n" +
            "          if(h>85&&window.AndroidScroll&&window.AndroidScroll.requestTextZoomFix){window.AndroidScroll.requestTextZoomFix();}\n" +
            "        }catch(e4){}\n" +
            "      },500);\n" +
            "    }catch(e){}\n" +
            "\n" +
            "    fixLayout();fixHScroll();collectScrollers();report();\n" +
            "    try{new MutationObserver(fixLayout).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}\n" +
            "    window.addEventListener('scroll',report,true);\n" +
            "    window.addEventListener('resize',function(){fixLayout();fixHScroll();collectScrollers();report();});\n" +
            "    window.addEventListener('load',function(){fixLayout();fixHScroll();collectScrollers();report();});\n" +
            "    setInterval(function(){fixLayout();fixHScroll();collectScrollers();report();},2000);\n" +
            "  }catch(e){}\n" +
            "})();\n" +
            "";

    /**
     * Scroll state + text-size bridge. {@code @JavascriptInterface} methods run
     * on the WebView's JavaBridge thread, so UI work is posted to the UI thread.
     */
    private final Object scrollBridge = new Object() {
        @JavascriptInterface
        public void setCanScrollUp(final int canScrollUp) {
            runOnUiThread(() -> {
                if (destroyed || swipeRefresh == null) return;
                boolean scrolledDown = canScrollUp == 1;
                if (pageScrolledDown == scrolledDown) return;
                pageScrolledDown = scrolledDown;
                // Only allow pull-to-refresh when there is nothing above.
                // Otherwise leave the gesture to the page so the editor and the
                // SRT lists can scroll freely up and down.
                swipeRefresh.setEnabled(!scrolledDown);
            });
        }

        @JavascriptInterface
        public void requestTextZoomFix() {
            runOnUiThread(() -> applyTextZoom());
        }
    };

    /**
     * Export bridge. The page script slices a blob into 256 KiB pieces and
     * pushes each one here as base64 ({@code begin → append… → finish}); this
     * writes them into the phone's Downloads folder.
     */
    private final Object downloadBridge = new Object() {
        @JavascriptInterface
        public boolean begin(String id, String name, String mimeType) {
            if (!callerAllowed()) return false;
            // Android 7-9: ask for storage the first time the user exports
            // something, not while the app is still starting up.
            ensureLegacyStoragePermission();
            return blobSaver.open(name, mimeType, mayWritePublicDownloads());
        }

        @JavascriptInterface
        public boolean append(String id, String base64Slice) {
            if (!callerAllowed()) return false;
            return blobSaver.append(base64Slice);
        }

        @JavascriptInterface
        public void finish(String id) {
            final String location = blobSaver.finish();
            runOnUiThread(() -> {
                if (destroyed) return;
                Toast.makeText(VoiceStudioMainActivity.this,
                        location == null ? getString(R.string.download_failed)
                                : getString(R.string.download_saved, location),
                        Toast.LENGTH_LONG).show();
            });
        }

        @JavascriptInterface
        public void fail(String id) {
            blobSaver.abort();
            runOnUiThread(() -> {
                if (destroyed) return;
                Toast.makeText(VoiceStudioMainActivity.this, R.string.download_failed, Toast.LENGTH_LONG).show();
            });
        }
    };

    private final Handler handler = new Handler(Looper.getMainLooper());

    // Periodically re-asserts the user's text size (see onResume).
    private final Runnable textZoomKeepAlive = new Runnable() {
        @Override public void run() {
            if (destroyed || webView == null) return;
            applyTextZoom();
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable revealPage = () -> loadingSplash.setVisibility(View.GONE);

    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), this::onFilePickerResult);

    private final ActivityResultLauncher<String[]> webPermissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                permissionPromptActive = false;
                completeWebPermission();
            });

    // Android 7-9 only: lets exported files land in the public Downloads folder
    // instead of the app's own directory. Android 10+ writes through MediaStore
    // and never asks for it (see BlobDownloadSaver).
    private final ActivityResultLauncher<String> legacyStoragePermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> permissionPromptActive = false);

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
        // Gradio scrolls inner panes instead of the WebView, so webView.getScrollY()
        // always looks like "top" and SwipeRefreshLayout would steal every
        // downward drag. The page reports its real scroll position through the
        // AndroidScroll bridge, and pull-to-refresh is only allowed at the top.
        swipeRefresh.setOnChildScrollUpCallback((parent, child) ->
                pageScrolledDown || webView.getScrollY() > 0 || webView.canScrollVertically(-1));
        swipeRefresh.setOnRefreshListener(this::reloadPage);
        findViewById(R.id.retry_button).setOnClickListener(view -> reloadPage());
        findViewById(R.id.browser_button).setOnClickListener(view -> openBrowser(VoiceStudioUrlPolicy.HOME_URL));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) webView.goBack();
                else finish();
            }
        });

        handler.postDelayed(revealPage, 15000);
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(VoiceStudioUrlPolicy.HOME_URL);
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
        // Scroll fix
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(true);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setScrollbarFadingEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.setNestedScrollingEnabled(true);
        }
        // Pin the scale to exactly 100% so the page renders at device width and
        // cannot zoom-shift content off the left edge at 60% text size.
        webView.setInitialScale(100);
        webView.addJavascriptInterface(scrollBridge, "AndroidScroll");
        webView.addJavascriptInterface(downloadBridge, "VoiceDownload");
        // Network downloads (files the page links directly) keep working through
        // the system downloader; blob: exports are handled by the page script.
        webView.setDownloadListener(this::onDownloadStart);
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.gemini_background));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String url = request.getUrl().toString();
                // A blob: navigation (download link that slipped past the page
                // hooks) cannot be fetched from Java — ask the page to stream it.
                if (url.startsWith("blob:")) {
                    grabBlob(url, null);
                    return true;
                }
                if (VoiceStudioUrlPolicy.isInternal(url)) return false;
                if (VoiceStudioUrlPolicy.isWebLink(url)) openBrowser(url);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                currentPageUrl = url;
                cancelWebPermission();
                cancelFileSelection();
                if (!VoiceStudioUrlPolicy.isInternal(url)) {
                    view.stopLoading();
                    showPageError();
                    return;
                }
                pageFailed = false;
                // New navigation: re-assert the user's text size.
                applyTextZoom();
                // A new page starts at the top until JS proves otherwise.
                pageScrolledDown = false;
                swipeRefresh.setEnabled(true);
                errorPanel.setVisibility(View.GONE);
                progressBar.setProgress(0);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override public void onPageCommitVisible(WebView view, String url) {
                currentPageUrl = url;
                revealLoadingPage();
                applyTextZoom();
                injectLayoutFix();
            }

            @Override public void onPageFinished(WebView view, String url) {
                currentPageUrl = url;
                finishLoading();
                applyTextZoom();
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

            /**
             * The renderer died (a Gradio page is heavy, and a phone with
             * little free memory kills the renderer without warning).
             *
             * <p>Returning {@code false} — the default — tells Android to kill
             * the whole app process, which is what made Voice Studio look like
             * it "opened and immediately jumped back out": the window appeared,
             * the renderer died, and the process was torn down with no dialog
             * and no error. Handling it keeps the app alive: the WebView is
             * rebuilt and the Space loads again.
             *
             * <p>Only exists on Android 8+ (API 26). On older devices the
             * method is simply never called.
             */
            @Override
            @RequiresApi(Build.VERSION_CODES.O)
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (destroyed) return true;
                if (view != null && view != webView) return true;
                rendererGone = true;
                handler.removeCallbacksAndMessages(null);
                // Too many deaths in a row: stop rebuilding and let the user
                // decide — the error panel's "Try again" resets the counter.
                long now = System.currentTimeMillis();
                if (now - firstRendererDeathAt > RENDERER_DEATH_WINDOW_MS) {
                    firstRendererDeathAt = now;
                    rendererDeaths = 0;
                }
                if (++rendererDeaths > MAX_RENDERER_DEATHS) {
                    showPageError();
                    return true;
                }
                pageFailed = false;
                errorPanel.setVisibility(View.GONE);
                currentPageUrl = null;
                pageScrolledDown = false;
                recreateWebView();
                if (webView != null) webView.loadUrl(VoiceStudioUrlPolicy.HOME_URL);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                applyTextZoom();
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
                if (!VoiceStudioUrlPolicy.isVoiceStudio(view.getUrl()) || fileCallback != null
                        || filePickerActive || permissionPromptActive) {
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

    /** Saves a network download (no blobs here) through the system downloader. */
    private void onDownloadStart(String url, String userAgent, String contentDisposition,
                                 String mimeType, long contentLength) {
        if (url == null || destroyed) return;
        if (url.startsWith("blob:")) {
            grabBlob(url, null);
            return;
        }
        if (!VoiceStudioUrlPolicy.isWebLink(url)) return;
        if (!VoiceStudioUrlPolicy.isVoiceStudio(url)) {
            // A file offered by some other site: let the browser own it.
            openBrowser(url);
            return;
        }
        String fileName = BlobDownloadSaver.safeName(URLUtil.guessFileName(url, contentDisposition, mimeType));
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null) request.setMimeType(mimeType);
            request.setTitle(fileName);
            request.setDescription(getString(R.string.download_description));
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) request.addRequestHeader("Cookie", cookie);
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
            manager.enqueue(request);
            Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_LONG).show();
        } catch (RuntimeException ignored) {
            openBrowser(url);
        }
    }

    /** Asks the page to stream a blob: URL to the DownloadBridge. */
    private void grabBlob(String blobUrl, String suggestedName) {
        if (webView == null || destroyed) return;
        String script = "window.__vsGrabBlob && window.__vsGrabBlob(" + jsQuote(blobUrl) + ","
                + (suggestedName == null ? "null" : jsQuote(suggestedName)) + ")";
        try {
            webView.evaluateJavascript(script, null);
        } catch (Exception ignored) {
        }
    }

    /** Single-quoted JavaScript string literal. */
    private static String jsQuote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('\'');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '\'': out.append("\\'"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\u2028': out.append("\\u2028"); break;
                case '\u2029': out.append("\\u2029"); break;
                default: out.append(c);
            }
        }
        return out.append('\'').toString();
    }

    private void injectLayoutFix() {
        if (webView == null || destroyed) return;
        try {
            webView.evaluateJavascript(LAYOUT_FIX_JS, null);
        } catch (Exception ignored) {
        }
    }

    /** Re-asserts the user's text size; idempotent and cheap. */
    private void applyTextZoom() {
        if (webView == null || destroyed) return;
        try {
            webView.getSettings().setTextZoom(WEB_TEXT_ZOOM);
        } catch (Exception ignored) {
        }
    }

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show();
        }
    }

    private void handleWebPermission(PermissionRequest request) {
        if (destroyed || permissionPromptActive || pendingWebPermission != null || fileCallback != null
                || filePickerActive || !VoiceStudioUrlPolicy.isVoiceStudio(request.getOrigin().toString())
                || !VoiceStudioUrlPolicy.isVoiceStudio(webView.getUrl())) {
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
        if (destroyed || !VoiceStudioUrlPolicy.isVoiceStudio(webView.getUrl())
                || !VoiceStudioUrlPolicy.isVoiceStudio(request.getOrigin().toString())) {
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
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
        try {
            filePickerActive = true;
            filePicker.launch(Intent.createChooser(picker, getString(R.string.choose_file)));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            filePickerActive = false;
            cancelFileSelection();
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    private void onFilePickerResult(ActivityResult result) {
        filePickerActive = false;
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        if (callback == null || destroyed) return;
        List<Uri> selected = new ArrayList<>();
        if (result.getResultCode() == Activity.RESULT_OK) {
            Intent data = result.getData();
            if (data != null && data.getClipData() != null) {
                ClipData clips = data.getClipData();
                for (int i = 0; i < clips.getItemCount(); i++) addSelectedUri(selected, clips.getItemAt(i).getUri());
            } else if (data != null && data.getData() != null) {
                addSelectedUri(selected, data.getData());
            }
        }
        callback.onReceiveValue(selected.isEmpty() ? null : selected.toArray(new Uri[0]));
    }

    private void addSelectedUri(List<Uri> selected, Uri uri) {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) return;
        if ((getPackageName() + ".fileprovider").equals(uri.getAuthority())) return;
        selected.add(uri);
    }

    private void cancelFileSelection() {
        if (fileCallback != null) {
            ValueCallback<Uri[]> callback = fileCallback;
            fileCallback = null;
            callback.onReceiveValue(null);
        }
    }

    /** True only while the loaded page is the Space itself. */
    private boolean callerAllowed() {
        return VoiceStudioUrlPolicy.isVoiceStudio(currentPageUrl);
    }

    /** True when the app may write straight into the public Downloads folder. */
    private boolean mayWritePublicDownloads() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                || hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    /** API 24-28: ask once so exports can land in the public Downloads folder. */
    @SuppressWarnings("deprecation")
    private void requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return;
        if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) return;
        if (permissionPromptActive) return;
        permissionPromptActive = true;
        try {
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        } catch (RuntimeException | LinkageError e) {
            // A storage prompt is a convenience, never a reason to fail an
            // export (BlobDownloadSaver falls back to the app's own folder).
            permissionPromptActive = false;
        }
    }

    /**
     * Called from the {@code VoiceDownload} bridge (WebView thread) on Android
     * 7-9, where writing to the public Downloads folder needs a runtime
     * permission. Deliberately not asked during start-up: a permission dialog
     * over the splash screen looks like the app leaving, and it blocked the
     * microphone and file pickers while it was up. The very first export can
     * still land in the app's own folder — the toast reports where.
     */
    private void ensureLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return;
        if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) return;
        runOnUiThread(this::requestLegacyStoragePermission);
    }

    private void reloadPage() {
        pageFailed = false;
        errorPanel.setVisibility(View.GONE);
        rendererDeaths = 0;
        firstRendererDeathAt = 0L;
        if (webView == null) return;
        if (rendererGone) {
            // A WebView whose renderer died cannot load anything: replace it.
            recreateWebView();
        }
        if (webView == null) return;
        if (!rendererGone && VoiceStudioUrlPolicy.isInternal(webView.getUrl())) webView.reload();
        else webView.loadUrl(VoiceStudioUrlPolicy.HOME_URL);
    }

    /**
     * Replaces the current WebView with a fresh one in the same place in the
     * layout. Used when the render process dies: a WebView whose renderer is
     * gone can never paint again, but the app itself is still perfectly alive.
     */
    private void recreateWebView() {
        WebView dead = webView;
        ViewParent parent = dead == null ? null : dead.getParent();
        if (!(parent instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) parent;
        int index = group.indexOfChild(dead);
        group.removeView(dead);
        try {
            dead.stopLoading();
            dead.setWebChromeClient(null);
            dead.setWebViewClient(null);
            dead.destroy();
        } catch (RuntimeException ignored) {
            // The renderer is already gone; teardown errors do not matter here.
        }
        WebView fresh = new WebView(this);
        fresh.setId(R.id.web_view);
        fresh.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        group.addView(fresh, index >= 0 ? index : 0);
        webView = fresh;
        rendererGone = false;
        configureWebView();
        // Re-show the loading overlay so the user sees the page coming back
        // instead of an empty white screen.
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        loadingSplash.setVisibility(View.VISIBLE);
        handler.postDelayed(revealPage, 15000);
    }

    /** Tears the WebView down without ever throwing (see {@link #onDestroy()}). */
    private void destroyWebView() {
        if (webView == null) return;
        try {
            ViewParent parent = webView.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(webView);
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        } catch (RuntimeException ignored) {
            // Never let teardown crash on the way out.
        }
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

    /**
     * Full-screen. The decor view is not attached to the window yet while
     * {@link #onCreate(Bundle)} is running, and on some Android versions
     * {@link WindowInsetsControllerCompat} then resolves to a null controller
     * and throws. Hiding the bars is cosmetic, so it waits until the view is
     * really attached and never lets an exception escape.
     */
    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        if (decor == null || !decor.isAttachedToWindow()) return;
        try {
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), decor);
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } catch (RuntimeException ignored) {
            // Losing full-screen is nothing next to losing the app.
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Posted so the decor view is attached by the time the bars are hidden
        // (onWindowFocusChanged can arrive before the first layout pass).
        if (hasFocus) getWindow().getDecorView().post(this::hideSystemBars);
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        // Keep-alive: re-assert the user's text size once per second while
        // resumed. Some WebView builds reset textZoom *after* the page callbacks
        // have already fired, which showed the studio at 100% right after a
        // reload. setTextZoom is change-detected inside the WebView, so this is
        // a no-op unless something actually reset it.
        handler.removeCallbacks(textZoomKeepAlive);
        handler.postDelayed(textZoomKeepAlive, 1000);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(textZoomKeepAlive);
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
        // Deliberately NOT aborting an in-flight export here: picking an .srt
        // file to upload pauses this activity too, and a backgrounded app can
        // simply finish its download on the way back. Partial files are cleaned
        // up in onDestroy (abort) and by MediaStore for pending rows.
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        cancelWebPermission();
        cancelFileSelection();
        blobSaver.abort();
        destroyWebView();
        super.onDestroy();
    }
}
