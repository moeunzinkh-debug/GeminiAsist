# Gemini — Android WebView (Java)

គម្រោង Android សម្រាប់បង្ហាញ **https://gemini.google.com/** ដោយគ្មាន address bar, tab, menu ឬ ActionBar។ នេះជាកម្មវិធី WebView មិនផ្លូវការ មិនមែនជា Google Gemini SDK/API client និងមិនត្រូវការ API key ទេ។

| ការកំណត់ | តម្លៃ |
| --- | --- |
| ឈ្មោះកម្មវិធី | Gemini |
| Application ID / namespace | `com.yourname.gemini` |
| ភាសា | Java 17 |
| Min SDK | 24 (Android 7.0) |
| Compile / Target SDK | 34 |
| Android Gradle Plugin | 8.5.2 |
| Gradle wrapper | 8.7 |

## មុខងារ

- **ពេញអេក្រង់**៖ WebView បំពេញអេក្រង់ និងលាក់ system bars; អ្នកប្រើអាចអូសដើម្បីបង្ហាញ system bars វិញបាន។
- **Pull-to-refresh**៖ អូសចុះនៅខាងលើទំព័រដើម្បី reload។ ពិនិត្យការរមូររបស់ WebView ដោយផ្ទាល់ ព្រោះ SwipeRefreshLayout មាន FrameLayout ជាកូនតែមួយ។
- **Progress bar**៖ បង្ហាញខាងលើពេលទំព័រកំពុងផ្ទុក។
- **Splash**៖ AndroidX SplashScreen សម្រាប់ពេលបើកកម្មវិធី និង loading overlay ខណៈទំព័រចាប់ផ្ដើមផ្ទុក។ Overlay លាក់ពេលទំព័របង្ហាញ ឬក្រោយ 15 វិនាទី ដើម្បីកុំឱ្យអ្នកប្រើជាប់នៅទីនោះ។
- **Upload**៖ ប្រើ Android file picker ជាមួយ MIME types និង single/multiple selection; មិនទាមទារ storage permission ទូទៅទេ។ សម្រាប់រូបភាព អាចជ្រើសកាមេរ៉ាបានតាម `FileProvider` ប្រសិនបើឧបករណ៍មាន camera app និងអ្នកប្រើអនុញ្ញាត។ បើបដិសេធ camera permission ក៏នៅតែជ្រើសឯកសារបាន។
- **Camera / microphone**៖ ស្នើសុំ Android runtime permissions ពេលត្រូវការ មិនស្នើសុំទាំងអស់នៅពេលបើកកម្មវិធីទេ។ សំណើ media ពី WebView អនុញ្ញាតតែ HTTPS origin `gemini.google.com` និងតែធនធានដែលអ្នកប្រើអនុញ្ញាត។
- **Back**៖ ត្រឡប់ទៅប្រវត្តិ WebView; បើគ្មានប្រវត្តិ នឹងបិទ Activity។
- **External links**៖ តំណ HTTP/HTTPS ក្រៅ Gemini បើកតាម Android default browser/URL handler។ `accounts.google.com` ជាករណីលើកលែងសម្រាប់ sign-in ក្នុង session ដូចគ្នា។ `target="_blank"` ប្រើការដឹកនាំរបស់ main frame មិនបង្កើត tab ថ្មីទេ។
- **Dark theme**៖ App shell និង splash តាម system light/dark mode។ ពណ៌គេហទំព័រអាស្រ័យលើ Gemini, `prefers-color-scheme` និងកំណែ Android System WebView; បើមាន Gemini theme setting អាចជ្រើស dark នៅក្នុងគេហទំព័រ។ API 33+ អនុញ្ញាត algorithmic darkening។
- **កំហុសបណ្ដាញ**៖ មានប៊ូតុង «Try again» និង «Open in browser» ជំនួស loading ដែលជាប់។

## កម្រិតសំខាន់ៗ

> **Google sign-in ក្នុង WebView មិនអាចធានាបានទេ។** Google អាចបដិសេធ embedded browser ឬបង្ហាញ `disallowed_useragent`។ គម្រោងនេះមិនកែ User-Agent ដើម្បីបន្លំ Chrome និងមិនជៀសវាងការការពាររបស់ Google ឡើយ។ ប្រសិនបើត្រូវបានរារាំង សូមប្រើ browser ដែល Google គាំទ្រ ឬកម្មវិធី Gemini ផ្លូវការ។

- Cookie របស់ Chrome/browser ខាងក្រៅ **មិនចែករំលែក** ជាមួយ WebView ទេ។ ការចូលគណនីនៅ browser មិនមានន័យថា WebView បានចូលដោយស្វ័យប្រវត្តិទេ។
- Voice chat, upload និងមុខងាររបស់ Gemini អាស្រ័យលើគណនី តំបន់ កំណែ WebView និងការគាំទ្ររបស់គេហទំព័រ។ ការផ្ដល់ permissions មិនអាចធានាថាសេវាទាំងនេះដំណើរការបានគ្រប់ឧបករណ៍ទេ។
- Pull-to-refresh ពិនិត្យការរមូររបស់ WebView; គេហទំព័រដែលមាន nested scroll container អាចមានឥរិយាបថខុសគ្នា។ ត្រូវសាកល្បងលើឧបករណ៍ពិត។
- `shouldOverrideUrlLoading` មិនមែនជា network firewall និងមិនចាប់គ្រប់ POST/subresource request ទេ។ Fallback ឈប់ main-frame navigation ដែលមិនបានអនុញ្ញាត ប៉ុន្តែមិនផ្ញើ POST របស់វាទៅ browser ជា GET វិញទេ។
- Browser popup ដែលពឹងផ្អែកលើ `window.opener` អាចមិនដំណើរការដូច Chrome ពេញលេញ។
- ពេល Android សម្លាប់ process ឬបង្កើត Activity ឡើងវិញ file chooser/media request ដែលកំពុងរង់ចាំត្រូវបានបោះបង់; សូមចាប់ផ្ដើម upload ឬ voice ម្ដងទៀត។ ប្រវត្តិ WebView អាចស្ដារបាន ប៉ុន្តែ JavaScript/live conversation state មិនត្រូវបានធានាទេ។
- `targetSdk 34` ត្រូវបានរក្សាតាមសំណើនេះ។ មុនចេញផ្សាយលើ Google Play ត្រូវពិនិត្យលក្ខខណ្ឌ target API ចុងក្រោយ ហើយធ្វើបច្ចុប្បន្នភាពតាមតម្រូវការ។

## រចនាសម្ព័ន្ធ

```text
.
├── build.gradle
├── settings.gradle
├── gradle.properties
├── local.properties                 # template មានតែ comments
├── gradlew                          # executable, macOS/Linux
├── gradlew.bat                      # Windows
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties    # Gradle 8.7 + SHA-256
├── .github/workflows/build-apk.yml
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/yourname/gemini/
        │   │   ├── MainActivity.java
        │   │   └── UrlPolicy.java
        │   └── res/
        │       ├── layout/activity_main.xml
        │       ├── layout/splash_screen.xml
        │       ├── values/{colors,strings,themes}.xml
        │       ├── values-night/{colors,themes}.xml
        │       ├── drawable/ic_launcher_background.xml
        │       ├── drawable/ic_launcher_foreground.xml
        │       ├── drawable/splash_background.xml
        │       ├── mipmap-anydpi/ic_launcher.xml
        │       ├── mipmap-anydpi-v26/ic_launcher.xml
        │       └── xml/file_paths.xml
        └── test/java/com/yourname/gemini/UrlPolicyTest.java
```

## របៀបបើកក្នុង Android Studio

1. ដំឡើង Android Studio ដែលគាំទ្រ AGP 8.5 និងជ្រើស **Open** ទៅថតគម្រោងនេះ។
2. កំណត់ **Gradle JDK = 17** ក្នុង Settings → Build Tools → Gradle។
3. ក្នុង SDK Manager ដំឡើង **Android SDK Platform 34**, **Build-Tools 34.0.0** និង Platform Tools។
4. `local.properties` ជា template។ Android Studio អាចបំពេញផ្លូវ SDK ឱ្យដោយស្វ័យប្រវត្តិ ឬកែជា៖

   ```properties
   # Linux ឧទាហរណ៍ — ត្រូវប្រើផ្លូវលើម៉ាស៊ីនរបស់អ្នក
   sdk.dir=/home/your-user/Android/Sdk
   # Windows អាចប្រើ forward slashes: C:/Users/your-user/AppData/Local/Android/Sdk
   ```

   កុំ commit ផ្លូវ SDK ផ្ទាល់ខ្លួនទៅ template នេះវិញ។ អាចរក្សា template មិនកែ និងប្រើ environment variable `ANDROID_HOME` ជំនួស។
5. Gradle Sync → ជ្រើស emulator/ទូរស័ព្ទ Android 7.0+ → Run។

## Build តាម command line

ត្រូវមាន **JDK 17**, Android SDK និងអ៊ីនធឺណិតសម្រាប់ទាញ Gradle/dependencies៖

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/Sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug :app:assembleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

នៅ Windows ប្រើ `gradlew.bat` ជំនួស `./gradlew`។

| លទ្ធផល | ទីតាំង |
| --- | --- |
| Debug (មាន debug signing, អាចដំឡើងបាន) | `app/build/outputs/apk/debug/app-debug.apk` |
| Release គ្មាន signing | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Release មាន signing | `app/build/outputs/apk/release/app-release.apk` |

**Unsigned release APK មិនអាចដំឡើងដោយផ្ទាល់បានទេ**; ត្រូវ sign មុន ឬប្រើ debug APK សម្រាប់សាកល្បង។

### ដំឡើងលើទូរស័ព្ទ (បើទូរស័ព្ទថា "មិនមានសុវត្ថិភាព")

Android បង្ហាញការព្រមានថា app "មិនមានសុវត្ថិភាព" សម្រាប់ APK ដែលដំឡើងក្រៅ Play Store — នេះជារឿងធម្មតា មិនមែនមានន័យថា APK ខូចទេ។ ការចុច **Install anyway / បន្តដំឡើង** គឺត្រឹមត្រូវ។ បើដំឡើងនៅតែមិនចេញ សូមពិនិត្យតាមតារាង៖

| អ្វីដែលឃើញ | មូលហេតុ | ដំណោះស្រាយ |
| --- | --- | --- |
| ដំឡើងមិនចេញ ពេលជ្រើសឯកសារ `.zip` | Artifact ពី GitHub ជា ZIP ដែលមាន APK នៅខាងក្នុង | ដោះ ZIP ចេញជាមុន រួចដំឡើងឯកសារ `.apk` ខាងក្នុង |
| "App not installed" ជាមួយ `gemini-release-UNSIGNED-...apk` | APK គ្មានហត្ថលេខា Android មិនអនុញ្ញាតឱ្យដំឡើងឡើយ | ប្រើ `gemini-debug-installable.apk` ឬកំណត់ signing secrets ទាំង 4 |
| "App not installed as package conflicts with an existing package" | App ចាស់ដំឡើងរួច ហើយ APK ថ្មីមាន **ហត្ថលេខាខុស** ពីចាស់ (debug key របស់ CI ប្ដូររាល់ run) | Uninstall app ចាស់ចេញ រួចដំឡើងម្ដងទៀត |
| Play Protect បិទ "Unsafe app blocked" | Play Protect មិនស្គាល់ app ក្រៅហាង | ចុច **Install anyway**; បើនៅតែបិទ សូមបិទ "Scan apps with Play Protect" បណ្ដោះអាសន្ន |
| ដំឡើងមិនចេញ ដោយគ្មានសារ | ទូរស័ព្ទចាស់ជាង API 24 | App ត្រូវការ Android 7.0 ឡើងទៅ |

ជំហានណែនាំ៖

1. ទាញ artifact `gemini-debug-apk` ពី Actions → ជ្រើស run → ផ្នែក Artifacts។
2. ដោះ ZIP ចេញ ដើម្បីបានឯកសារ `gemini-debug-installable.apk`។
3. Settings → Apps → Special app access → Install unknown apps → អនុញ្ញាតឱ្យ browser ឬ file manager។
4. បើធ្លាប់ដំឡើង build ចាស់ → uninstall ចេញជាមុន។
5. ដំឡើង APK → បើ Play Protect បិទ → **Install anyway**។

APK debug សម្រាប់តែសាកល្បង (មាន `android:debuggable` និងត្រូវបានចុះហត្ថលេខាដោយ debug key)។ ហត្ថលេខារបស់ APK និង fingerprint របស់ debug key ត្រូវបានបង្ហាញក្នុង **run summary** និងក្នុងឯកសារ `apk-signing-report.txt` នៅក្នុង artifact។

**សំខាន់៖** CI បង្កើត debug key ថ្មីរាល់ run (បានផ្ទៀងផ្ទាត់៖ run 36588074159 → `90711642…e9b0`, run 36588402355 → `7e943d73…8376`) ដូច្នេះ APK debug ពី run ថ្មី **មិនអាចដំឡើងជាន់លើ** build ចាស់បានទេ បើមិន uninstall ចាស់ចេញជាមុន។ បើចង់ឱ្យហត្ថលេខានៅដដែល និងដំឡើងជាន់លើបានរាល់ដង សូមកំណត់ signing secrets ទាំង 4 (ខាងក្រោម) រួចទាញ `gemini-release-signed-installable.apk` ជំនួស។

### Optional release signing

Gradle ប្រើ environment variables ទាំង 4 ខាងក្រោម ប្រសិនបើមានទាំងអស់៖

- `GEMINI_KEYSTORE_PATH` — absolute path ទៅ keystore
- `GEMINI_KEYSTORE_PASSWORD`
- `GEMINI_KEY_ALIAS`
- `GEMINI_KEY_PASSWORD`

កុំដាក់ password ឬ keystore ក្នុង repository។ បើគ្មាន variables ទាំងអស់ នឹង build unsigned release ដោយមិនប្រើ debug key ជំនួស។

## GitHub Actions

`.github/workflows/build-apk.yml` ដំណើរការពេល **push ទៅ `main`**, ពេលបើក **pull request ទៅ `main`**, និងអាច **Run workflow** ដោយដៃ (`workflow_dispatch`)។ វា៖

1. Checkout → JDK 17 → ដំឡើង Android SDK 34 / Build-Tools 34.0.0 ដោយ `sdkmanager` ដែលមានស្រាប់លើ runner image (ANDROID_HOME ត្រូវបានកំណត់រួច)។ មិនប្រើ `android-actions/setup-android@v3` ទេ ព្រោះ default របស់វាគឺ `packages: tools platform-tools` ហើយ package `tools` ត្រូវបាន Google ដកចេញពី SDK repository — វាធ្វើឱ្យ `sdkmanager` exit 1 និង job ខូចមុនពេល Gradle ដំណើរការ (run 36585480375)។
2. រត់ unit tests និង Android Lint។
3. Build debug និង release APK។
4. Upload artifacts `gemini-debug-apk` និង `gemini-release-apk`។

សម្រាប់ signed release បង្កើត GitHub Actions secrets នៅ repository settings៖

| Secret | អត្ថន័យ |
| --- | --- |
| `GEMINI_KEYSTORE_BASE64` | keystore ដែលបាន encode ជា Base64 |
| `GEMINI_KEYSTORE_PASSWORD` | password របស់ keystore |
| `GEMINI_KEY_ALIAS` | alias របស់ signing key |
| `GEMINI_KEY_PASSWORD` | password របស់ key |

Keystore ត្រូវបាន decode ក្នុង runner temporary directory និងលុបនៅចុង workflow។ បើមិនកំណត់ secrets ទាំង 4 នឹងទទួល unsigned release។ Workflow មិនចែកចាយ APK ឬចេញ release ទៅសាធារណៈដោយស្វ័យប្រវត្តិទេ។

## សុវត្ថិភាព និង WebView settings

`MainActivity` មាន settings ទាំងអស់ដែលបានស្នើ៖ JavaScript, DOM storage, database, media playback without gesture, overview/wide viewport, បិទ zoom controls/support zoom, អនុញ្ញាត file/content access។

បន្ថែម៖ បិទ cross-origin access ពី local file URLs, មិនអនុញ្ញាត mixed content/cleartext HTTP ក្នុង app, cancel TLS errors, មិនប្រើ JavaScript bridge, និងមិនបើក WebView debugging។ `UrlPolicy` ផ្ទៀងផ្ទាត់ HTTPS host/port ពិតប្រាកដ មិនប្រើ `contains("google.com")` ទេ។ Origin sign-in **មិន** ទទួល camera/microphone permission។ បើមិនត្រូវការ `setAllowFileAccess(true)` អាចបិទវាសម្រាប់ hardening បន្ថែម។

App មិន backup ទិន្នន័យគណនីទេ។ Cookies/local storage រក្សាក្នុង app sandbox; Clear storage ឬ uninstall ដើម្បីលុប session។ រូបដែលថតសម្រាប់ upload រក្សាក្នុង cache រហូត Activity បិទ; បើ process ត្រូវបានសម្លាប់ភ្លាមៗ អាចមាន cache សល់រហូតប្រព័ន្ធសម្អាត ឬអ្នកប្រើ clear cache។

## ការផ្ទៀងផ្ទាត់

បានពិនិត្យក្នុង workspace៖ syntax Java ដោយ parser, XML, YAML workflow, resource references និង SHA-256 របស់ Gradle wrapper ទល់នឹង upstream។

Build APK ជោគជ័យលើ GitHub Actions រួចហើយ (run 36586666184, commit `4e4ccc1`)៖ steps ទាំងអស់រួមទាំង unit tests, Lint, assembleDebug និង assembleRelease ជោគជ័យ ហើយ artifacts `gemini-debug-apk` (~2.8 MB) និង `gemini-release-apk` (~2.2 MB, unsigned ពេលគ្មាន signing secrets) ត្រូវបាន upload។

**ផ្នែកដែលមិនអាចផ្ទៀងផ្ទាត់ក្នុង workspace បានទេ**៖ workspace នេះគ្មាន Android SDK និងគ្មាន network egress ទៅ dl.google.com/services.gradle.org ដូច្នេះការបញ្ជាក់ចុងក្រោយគឺមកពី CI។ ការសាកល្បងលើឧបករណ៍ពិត (install APK, camera/mic upload) នៅតែជាជំហានរបស់អ្នកប្រើ។

សូមសាកល្បងលើ API 24 និង API 34៖

- Cold launch → splash → ទំព័រ Gemini → progress បាត់។
- Scroll, pull-to-refresh និង keyboard/IME ក្នុង fullscreen។
- Back history, external link និង `target="_blank"`។
- ជ្រើសរូប/ឯកសារច្រើន, ថតរូប, cancel chooser, បដិសេធ/អនុញ្ញាត camera permission។
- Microphone/video request, deny/permanent deny, រួចបើក permissions ក្នុង Android Settings។
- Airplane mode → error → reconnect → Try again។
- Light/dark mode, rotate device, background/foreground និង process recreation។
- Google sign-in និង voice/upload ជាមួយគណនីសាកល្បង (អាចត្រូវបាន Google រារាំង)។
