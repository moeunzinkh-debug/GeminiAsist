package org.geminiassist.app;

import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

/**
 * Assistant session: shows the Gemini drawer (or the full screen, depending on the
 * "use_drawer_assistant" patch) instead of a voice UI.
 */
public class AssistantSession extends VoiceInteractionSession {

    public AssistantSession(Context context) {
        super(context);
    }

    @Override
    public void onHandleAssist(Bundle data, AssistStructure structure, AssistContent content) {
        super.onHandleAssist(data, structure, content);
        // The screen context could be forwarded to Gemini here; for now the session
        // only opens the assistant UI and focuses the prompt box.
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);

        Intent intent = new Intent(getContext(), ChatActivity.class);
        intent.setAction(Intent.ACTION_ASSIST);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        startAssistantActivity(intent);
        finish();
    }
}
