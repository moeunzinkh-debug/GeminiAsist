# GeminiAssist

**GeminiAssist** is a lightweight, privacy-focused Android WebView wrapper for
[gemini.google.com](https://gemini.google.com/).

It follows the same architecture as
[duckAssist](https://github.com/diekaiju/duckAssist) (a fork of
[gptAssist](https://github.com/woheller69/gptAssist), GPLv3), with **all**
duck.ai-specific logic replaced by Gemini logic.

- Package / applicationId: `org.geminiassist.app`
- Version: 1.0.0 (versionCode 1)
- License: GPLv3 (see [LICENSE](LICENSE))

## Key features

- 🎯 **Top bar, zero overlays**: the toolbar (app icon + title + **Patches** button)
  sits *above* the WebView in normal layout flow. There is **no** floating button,
  **no** FAB and no button injected into the web page - the only overlay that ever
  appears is the auto-dismissing red hint banner after a file share.
- ⚙️ **Patches** (settings) page: drawer usage per trigger type, prompt-box focus,
  text size (Small 85 / Normal 100 / Large 115 / XL 130, applied instantly),
  prompt suffixes for shared text and shared files, and the saved-chats viewer.
- 💬 **Ask Gemini**: select text in any app and choose **Ask Gemini**, or share
  text / images / PDFs. Gemini has no reliable prompt URL parameter, so shared text
  is injected into the prompt box with JavaScript once the composer exists.
- 🧲 **Bottom drawer**: shares and assistant triggers open in a 92%-height bottom
  card with slide-up animation, drag-to-dismiss, and tap-outside-to-dismiss.
- 🎙️ **Digital assistant**: set **GeminiAssist** as the default assistant app.
- 🌐 **Smart link handling**: `gemini.google.com` and the Google account hosts
  (`accounts.google.com`, `myaccount.google.com`) stay in-app so the login flow can
  complete; every other link opens in the system browser.
- 🛡️ **Privacy**: cookies are never cleared (the Google login must persist), while
  the WebView HTTP cache is dropped on exit.
- 🔐 **Login fix**: the WebView user agent has its `wv` marker stripped and falls
  back to a full Chrome-mobile UA, because Google refuses to serve sign-in to a bare
  WebView user agent ("This browser or app may not be secure").

## Build

```bash
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The GitHub Actions workflow in `.github/workflows/build.yml` builds the same APK on
every push and pull request and uploads it as an artifact.

## Layout of the project

```
app/src/main/java/org/geminiassist/app/
  MainActivity.java                  WebView host, top bar, Patches dialog, downloads
  ChatActivity.java                  bottom-drawer variant (assistant / shares)
  AskActivity.java                   transparent router for SEND / PROCESS_TEXT
  AssistantService.java              VoiceInteractionService declaration
  AssistantSession(+Service).java    assistant session that opens the drawer
  AssistantRecognitionService.java   recognition stub required by the assistant role
app/src/main/assets/
  settings.html                      the "⚙ Patches" page
  chats_viewer.html                  saved chats + offline fallback page
tools/icon-source/gemini_assist_icon.svg   original launcher icon source
tools/make-launcher-icons.sh               regenerates the legacy PNG launcher icons
```
