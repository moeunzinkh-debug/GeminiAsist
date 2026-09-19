package org.geminiassist.app;

import android.service.voice.VoiceInteractionService;

/**
 * Declares GeminiAssist as a digital assistant. The actual work happens in
 * {@link AssistantSession}, which launches {@link ChatActivity} with ACTION_ASSIST.
 */
public class AssistantService extends VoiceInteractionService {
}
