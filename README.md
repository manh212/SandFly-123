# SandFly

SandFly is a screen reader for Android. This repository is a fork of [SandFly](https://github.com/aaron-gh/SandFly) by Aaron Graham-Hewitt, which is a fork of [Backtalk](https://github.com/trypsynth/backtalk), which is a fork of [Google's TalkBack](https://github.com/google/talkback). Many thanks to Aaron and to the Backtalk and TalkBack developers for the work this fork builds on. This fork is not affiliated with Google, Backtalk or the original SandFly project.

This fork runs on phones and tablets with Android 8 or later. There is no watch or TV version.

## Install

Download `sandfly.apk` from the [latest release](https://github.com/manh212/SandFly-123/releases/latest) and open it on the phone. The first time, Android asks you to allow your browser or file manager to install apps.

SandFly installs as `io.github.aaron_gh.sandfly`, next to Google's TalkBack and Backtalk. Each app has its own settings.

## Turn on SandFly

Go to **Settings** > **Accessibility**. SandFly is listed as **SandFly** and is off. Turn off any other screen reader first, then turn on SandFly.

## Updates

SandFly checks for a new release when it starts and about once a day. When there is one, it shows a notification with the list of changes. Tap the notification to download and install it. The first time, Android asks you to allow SandFly to install apps.

To check now, go to **Check for updates** in SandFly settings, which also shows the version and build number. To stop automatic checks, turn off **Automatically check for updates**.

There are stable releases and development builds:

*   Stable releases are on the [releases](https://github.com/manh212/SandFly-123/releases) page, named by version, such as SandFly 0.1.0.
*   Development builds are on the [dev release](https://github.com/manh212/SandFly-123/releases/tag/dev) page, which each change replaces. Turn on **Development builds** in SandFly settings to get them. SandFly then checks about once an hour, and offers whichever is newer, so a development build is offered the next stable release.

## Moving from the original SandFly

This fork has the same app id as Aaron's SandFly, `io.github.aaron_gh.sandfly`, but is signed with a different key, so Android cannot install it over the original: installing it on top fails. To move:

1.  In the original SandFly, use **Export settings** if you want to keep your settings, and save the file somewhere safe.
2.  Uninstall the original SandFly. Its settings and custom labels are removed with it.
3.  Install this fork and turn it on, then use **Import settings** to bring your settings back.

On-device AI models are not copied either, so download them again.

If you use an accessibility shortcut, grant SandFly permission to change secure settings before you turn it on, and it moves the shortcut to itself: `adb shell pm grant io.github.aaron_gh.sandfly android.permission.WRITE_SECURE_SETTINGS`. Otherwise, set the shortcut again for SandFly.

## Differences from TalkBack

SandFly speaks sooner after each swipe and screen change, and can play its sounds and speech through a low-latency audio path. It adds direct key input, a circle menu, Pause SandFly, direct touch for audio games, sound themes with control sounds in 3D, a vibration for every sound, a status gesture, voice profiles, settings for how notifications are read, its own notification list and quick settings panel, image and screen descriptions from several AI services or a model on the phone. It also changes some defaults and fixes problems in TalkBack. For the full list, see [Differences from TalkBack](differences.md).

Scripts written in Lua can change how SandFly works in one app or in every app. See [Writing SandFly scripts](docs/scripting.md).

## Permissions through Shizuku or adb

Two settings need permissions that Android only grants through adb:

*   **Touch and activation** > **Fix stuck touch exploration**, which is on by default, needs the DUMP permission to fix touch exploration when Android leaves a touch down.
*   **Sound and vibration** > **Speaker when away from your ear** needs permission to manage calls.

With [Shizuku](https://shizuku.rikka.app) installed and running, SandFly grants these itself: DUMP when SandFly starts, and the call permission when you turn the setting on. The first time, Shizuku asks whether to allow SandFly. Without Shizuku, each setting shows the adb command, and its **Copy command** action copies it.

## Image descriptions

**Describe image** and **Describe screen** can use an AI service with your own API key, or a model that runs on the phone. Google's source release does not include working Gemini support, so without one of these, **Describe image** only reads text in images, and **Describe screen** does not work. SandFly adds its own support for Describe screen, including follow-up questions.

### AI services

1.  Open SandFly settings, then **Automatic descriptions**, and turn on **Detailed image descriptions**.
2.  Under **AI services**, open the settings of a service: Gemini, Claude, OpenAI (ChatGPT), Mistral, Groq, OpenRouter, Ollama Cloud, or **Other service** for another service with an OpenAI-compatible API, whose address you enter.
3.  Choose **Get a key**, where the service has one, to open its website and create a key. Paste the key into the API key field, and choose **Save**.
4.  Choose a model from the list that the service gives for your key, or enter its name.
5.  If no other service is in use, SandFly uses this one as soon as it is set up. **AI service** in **Automatic descriptions** shows the service in use and lists the services that are set up, to switch between them. **None** turns AI descriptions off.

A service can't be used until it has an address, a key and a model. When you clear the key or model of the service in use, SandFly uses the next service that is set up, or **None**. Questions about an image or screen, spoken or typed, go to the service in use.

Images and screenshots that you describe are sent to the service. On Gemini's free tier, Google can use this data to improve its products. With Gemini, when the chosen model is busy or takes more than 15 seconds, SandFly asks Gemini Flash-Lite instead.

### On-device AI

If you do not want to use an API key, or you have hit the free tier limit, SandFly can describe images and screens with a Gemma 4 model that runs on your phone. Nothing is sent to Google or anyone else when you use it.

1.  Open SandFly settings, then **Automatic descriptions**, then **On-device AI**.
2.  Choose a model. **Gemma 4 E2B** is the one to start with: 2.6 GB, and it needs a phone with about 6 GB of memory. **Gemma 4 E4B** is 3.7 GB, gives better answers, and needs about 8 GB. The list also has other small vision models from the [LiteRT community](https://huggingface.co/litert-community), from 0.4 GB up, so that you can try them. Those are marked experimental: they are community conversions that the SandFly developers have not tried, and some may not work or may follow the screen description format badly. The list only shows models that your phone has enough memory for, plus any you already have. If your phone does not have enough memory for Gemma 4 E2B, SandFly picks the largest model that fits.
3.  Choose **Download model**. It downloads once from [Hugging Face](https://huggingface.co/litert-community) and carries on where it stopped if the connection drops. A notification shows the progress. SandFly checks the file against a known SHA-256 hash and deletes it if it does not match. Or choose **Use a model file from storage** to use a `.litertlm` file that you downloaded yourself, such as `gemma-4-E2B-it.litertlm` from `litert-community/gemma-4-E2B-it-litert-lm`. SandFly works out which model it is.
4.  Turn on **Use on-device AI**.

**Detailed image descriptions** in **Automatic descriptions** must also be on. While **Use on-device AI** is on, Describe image and Describe screen use the model on your phone instead of the AI service. Turn it off to go back to the AI service.

Answers take several seconds and use battery, more than the cloud on a mid-range phone. The model loads on the first request and unloads after two idle minutes to free memory. The model runs in its own process, so if the phone runs out of memory, Android stops the model and not SandFly, and SandFly says so. Before it loads a model, SandFly checks that the phone has at least as much free memory as the model's size, and if not, it says there is not enough free memory and does not load it. To test with a model that is too big for your phone, turn on **Ignore on-device AI memory limits** in **Advanced settings** > **Developer settings**. It lists every model, lets you choose any downloaded one, and skips the free memory check. If answers fail or the phone slows down, try the other model, or turn **Use the GPU** on or off. On-device AI needs a 64-bit ARM phone and adds about 22 MB to the app.

## Languages

This fork's APK is in English and Vietnamese only. Its own strings are translated into both, and strings that come from TalkBack use Google's translations into those two languages.

## Build

You need JDK 25, the Android SDK with platform 37, and NDK 27.3.13750724. The Gradle wrapper downloads the correct Gradle version.

### Linux or macOS

Set `ANDROID_SDK` to your SDK path, then run `./build.sh`. This builds the phone APK, and keeps anything else that's in `local.properties`, such as a Gemini API key.

### Windows

Set `ANDROID_HOME` to your SDK path, then run:

```
.\gradlew.bat assemblePhoneDebug
```

`.\deploy.ps1` builds the APK and installs it with adb. This script needs PowerShell 7. To install the last build without building again, use `-SkipBuild`.

### Signing

Android only installs an update that is signed with the same key as the installed app. Builds from GitHub are signed with this fork's key. A build you make yourself is signed with your own debug key, so it can't update a build from GitHub, or be updated by one. Uninstall one before installing the other.

### A built-in Gemini key

If you build SandFly yourself, you can also build a key into the APK instead. Add `gemini.api.key=YOUR_KEY` to `local.properties` in the project folder, then build and install SandFly again. A key entered in settings takes the place of the built-in key. Git ignores `local.properties`, so your key is not committed, but do not share an APK that contains your key. To use a different model, add `gemini.model=MODEL_NAME`. The default is `gemini-flash-latest`.

### Dependencies

Library and plugin versions are in `gradle/libs.versions.toml`. GitHub Actions builds each pull request.

### Debug tools

Debug builds include tools to find lag:

*   The `BacktalkStall` logcat tag logs each time the main thread is blocked for more than 100 ms, with the code that was running.
*   The `BacktalkGesture` logcat tag logs touch state changes and each gesture that SandFly detects.
*   Event processing has trace sections, which show in [Perfetto](https://perfetto.dev) system traces.

### Making a release

1.  Set `sandflyVersion` in `version.gradle` to the new version, such as `0.2.0`, write the release notes in `release-notes/0.2.0.md`, and push to master. List items in the notes start with `* `: the in-app updater reads lines that start with `- ` as commits.
2.  Tag the commit `v0.2.0` and push the tag. GitHub Actions builds it and creates the release, with the changes since the last release.

The build number is the number of commits since `buildNumberBase` in `version.gradle`. The version code is 100000000 plus the build number: Gboard leaves typing to the screen reader only when its version code is at least 60105832.

## History and license

SandFly began as Aaron's fork of Backtalk, Backtalk Aaron, and became a separate project when it had many changes that are not in Backtalk. The git history keeps every change from TalkBack, Backtalk and SandFly. Files from TalkBack and Backtalk are changed in SandFly. This fork of SandFly is maintained by manh212, and its own changes are in [Differences from TalkBack](differences.md) along with SandFly's.

SandFly is under the Apache License 2.0, as TalkBack and Backtalk are. See [LICENSE](LICENSE).

The emoji names are from the [Unicode CLDR](https://cldr.unicode.org/) and the Unicode emoji data, copyright Unicode, Inc., used under the Unicode License v3, which is in `utils/src/main/assets/emoji_names/LICENSE`.

Scripts run in [Lua 5.4](https://www.lua.org/), copyright Lua.org, PUC-Rio, through [LuaJava](https://github.com/gudzpoz/luajava), copyright gudzpoz, both used under the MIT License. The license texts are in `talkback/src/phone/assets/scripting/LICENSE-luajava`, which is in the APK.

Dictation recognizes speech on the phone with [whisper.cpp](https://github.com/ggml-org/whisper.cpp) 1.9.5, copyright the ggml authors, used under the MIT License, which is in `whisper/src/main/cpp/whisper.cpp/LICENSE`. Its source is in `whisper/src/main/cpp/whisper.cpp`, with only the CPU backend. The Whisper models are downloaded from [Hugging Face](https://huggingface.co/ggerganov/whisper.cpp) when the user chooses one; they are OpenAI's Whisper models, under the MIT License.
