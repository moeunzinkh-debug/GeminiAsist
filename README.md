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
- **Pull-to-refresh** - អូសចុះដើម្បី reload
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
