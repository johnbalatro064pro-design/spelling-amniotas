# Spelling Bee Coach — Android

This is a clean Android version designed around offline Vosk speech recognition.

## What it does
- Paste a word list, one word per line.
- Save the list on the phone.
- Practice one word at a time.
- The word is hidden until **Show Word**.
- Spell with the on-screen keyboard or microphone.
- **Speak Next Letter** listens for one letter and immediately reports what it heard.
- Wrong words are placed in a missed-word review list.
- Speech recognition is on-device after the model is downloaded.

## Free / privacy
No Azure, no paid API, and no API key. Vosk is an offline open-source speech-recognition toolkit. The first launch downloads the small English model from the official Vosk model site; after that the model is stored locally and recognition can run offline.

The small English model is about 40 MB according to the official Vosk model list.

## Build
Open this folder in Android Studio and let Gradle sync. Then:
Build > Build Bundle(s) / APK(s) > Build APK(s).

The project uses the official Vosk Android library from Maven Central and follows the official Android demo's approach.

## Important
I cannot produce a custom APK binary in this environment because an Android SDK/build toolchain is not installed here. This ZIP is the complete Android Studio project. The included GitHub Actions workflow can build the APK automatically if you upload the project to GitHub.
