# Gemini - Android WebView

![Tag](https://img.shields.io/badge/Tag-Gemini%20WebView-blue) ![Platform](https://img.shields.io/badge/Platform-Android-green) ![Language](https://img.shields.io/badge/Language-Java%2017-orange)

កម្មវិធី Android WebView សម្រាប់បើក **https://gemini.google.com/** ដោយគ្មាន address bar ពេញអេក្រង់។

> **Tag:** `Gemini WebView`

## វាអាចធ្វើអ្វីបាន?

- បើក Gemini AI ក្នុង App Android ដោយផ្ទាល់
- Chat ជាមួយ Gemini ដូចក្នុង Browser
- Upload រូបភាព/ឯកសារ និងថតរូបផ្ទាល់
- ប្រើ Microphone / Camera សម្រាប់ Voice chat
- Sign-in Google Account ក្នុង App

## មុខងារសំខាន់ៗ

- **ពេញអេក្រង់** - លាក់ system bars, WebView បំពេញអេក្រង់
- **អក្សរ 50%** - បង្ហាញអត្ថបទត្រឹម 50% ដោយមិនលិច/កាត់ផ្នែកខាងឆ្វេង
- **Scroll រលូន** - អូសមើលសារឡើងលើ/ចុះក្រោមបានធម្មតា មិនជាប់គាំង
- **Pull-to-refresh** - អូសចុះដើម្បី reload (តែពេលនៅកំពូលទំព័រ)
- **Upload File** - ជ្រើសឯកសារ/រូបភាពច្រើន, ថតរូបតាម Camera
- **Camera & Mic** - ស្នើសុំ permission ពេលត្រូវការ
- **Progress Bar** - បង្ហាញពេលផ្ទុកទំព័រ
- **Back Navigation** - ត្រឡប់ប្រវត្តិ WebView
- **External Links** - បើក link ក្រៅ Gemini ក្នុង Browser
- **Dark Theme** - តាម system light/dark
- **Error Handling** - ប៊ូតុង Try again / Open in browser

## បច្ចេកទេស

- Java 17, Min SDK 24 (Android 7.0+), Target SDK 34
- Android WebView, SwipeRefreshLayout, FileProvider
- មិនត្រូវការ API Key

## Build

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

---

# ChatGPT - Android WebView

![Tag](https://img.shields.io/badge/Tag-Chatgpt%20WebView-74AA9C) ![Platform](https://img.shields.io/badge/Platform-Android-green) ![Language](https://img.shields.io/badge/Language-Java%2017-orange)

កម្មវិធី WebView សម្រាប់បើក **https://chatgpt.com/** ដោយគ្មាន address bar ពេញអេក្រង់ — flavor ថ្មីក្នុងគម្រោងដដែល។

> **Tag:** `Chatgpt WebView`

## Flavor ពីរ — source តែមួយ, APK ពីរ

| Flavor | Tag | applicationId | កូដ |
|---|---|---|---|
| `gemini` | `Gemini WebView` | `com.yourname.gemini` | `src/main` (ដូចមុន 100%) |
| `chatgpt` | `Chatgpt WebView` | `com.yourname.chatgpt` | `src/chatgpt` + `src/main` |

កូដ Gemini ក្នុង `src/main` **មិនប៉ះ** — គ្រប់ការផ្លាស់ប្តូររបស់ ChatGPT នៅក្នុង `src/chatgpt` (source set ថ្មី):

- **Launcher ផ្សេង** - manifest overlay លែងបង្ហាញ icon Gemini ក្នុង build ChatGPT
- **ឈ្មោះ/ពណ៌/icon** - resource override (app name "ChatGPT", ពណ៌បៃតង #10A37F, icon chat bubble)
- **URL Policy ថ្មី** - `chatgpt.com` + sign-in chain (`*.openai.com`, Google/Apple SSO, Cloudflare check)
- **មុខងារដដែល** - អក្សរ 50%, scroll រលូន, pull-to-refresh, upload file, camera/mic, back navigation, error handling
- **ដំឡើងដាច់ដោយឡែក** - applicationId ខុសគ្នា → app ទាំងពីរនៅលើទូរស័ព្ទតែមួយ

## Build

```bash
./gradlew :app:assembleGeminiDebug    # tag Gemini WebView (ដូចមុន)
./gradlew :app:assembleChatgptDebug   # tag Chatgpt WebView
```

APK:

- Gemini: `app/build/outputs/apk/gemini/debug/app-gemini-debug.apk`
- ChatGPT: `app/build/outputs/apk/chatgpt/debug/app-chatgpt-debug.apk`
