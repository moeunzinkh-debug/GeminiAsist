package org.geminiassist.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.net.URLEncoder;

/**
 * Transparent router for "Ask Gemini" shares.
 *
 * It never shows a UI: the incoming share is forwarded to {@link ChatActivity} and
 * this activity finishes immediately.
 *
 * Gemini has no reliable prompt URL parameter (no "?q="), so the shared text travels
 * as {@code https://gemini.google.com/?ask=<urlencoded>} and is injected into the
 * prompt box by {@link MainActivity#buildInjectPromptJs(String)} once the page is
 * ready. The configured "ask_gemini_suffix" is appended exactly once, in
 * {@link MainActivity#handleIntent(Intent, boolean)}.
 */
public class AskActivity extends Activity {
    private static final String TAG = "GeminiAssistAsk";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
        finish();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();

        // Shared image / PDF: forward the stream untouched, chat activity stages it.
        if (Intent.ACTION_SEND.equals(action) && type != null
                && (type.startsWith("image/") || "application/pdf".equals(type))) {
            Uri streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (streamUri != null) {
                Intent chatIntent = new Intent(this, ChatActivity.class);
                chatIntent.setAction(Intent.ACTION_SEND);
                chatIntent.setType(type);
                chatIntent.putExtra(Intent.EXTRA_STREAM, streamUri);
                chatIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(chatIntent);
                return;
            }
        }

        String sharedText = null;
        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (text != null) {
                sharedText = text.toString();
            }
        }

        if (sharedText == null || sharedText.isEmpty()) {
            Log.d(TAG, "Nothing to forward for action=" + action + " type=" + type);
            return;
        }

        String suffix = prefs().getString("ask_gemini_suffix", "");
        if (suffix != null && !suffix.trim().isEmpty()) {
            sharedText = sharedText + "\n\n" + suffix;
        }

        String encoded;
        try {
            encoded = URLEncoder.encode(sharedText, "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Could not encode shared text", e);
            encoded = sharedText;
        }

        Log.d(TAG, "Forwarding shared text to ChatActivity (" + sharedText.length() + " chars)");
        Intent chatIntent = new Intent(this, ChatActivity.class);
        chatIntent.setAction(Intent.ACTION_VIEW);
        // The ?ask= parameter is our own hand-off format: Gemini ignores it, the app
        // reads it and injects the prompt itself.
        chatIntent.setData(Uri.parse("https://gemini.google.com/?ask=" + encoded));
        chatIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(chatIntent);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("gemini_assist_prefs", MODE_PRIVATE);
    }
}
