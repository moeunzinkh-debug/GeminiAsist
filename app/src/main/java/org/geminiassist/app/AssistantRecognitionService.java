package org.geminiassist.app;

import android.content.Intent;
import android.speech.RecognitionService;

/**
 * Deliberately empty recognition stub: it satisfies the voice-interaction service
 * declaration (the assistant role needs a recognition service), while in-app dictation
 * is handled by Gemini itself through {@code onPermissionRequest}.
 */
public class AssistantRecognitionService extends RecognitionService {

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback listener) {
    }

    @Override
    protected void onCancel(Callback listener) {
    }

    @Override
    protected void onStopListening(Callback listener) {
    }
}
