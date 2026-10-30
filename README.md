# Meridian

Meridian is a screen reader for Android. This repository is a fork of [SandFly](https://github.com/aaron-gh/SandFly) by Aaron Graham-Hewitt, which is a fork of [Backtalk](https://github.com/trypsynth/backtalk), which is a fork of [Google's TalkBack](https://github.com/google/talkback). Many thanks to Aaron and to the Backtalk and TalkBack developers for the work this fork builds on. This fork is not affiliated with Google, Backtalk or the original SandFly project.

This fork runs on phones and tablets with Android 8 or later. There is no watch or TV version.

## Install

Download `meridian.apk` from the [latest release](https://github.com/manh212/SandFly-123/releases/latest) and open it on the phone. The first time, Android asks you to allow your browser or file manager to install apps.

Meridian installs as `io.github.aaron_gh.meridian`, next to Google's TalkBack and Backtalk. Each app has its own settings.

## Turn on Meridian

Go to **Settings** > **Accessibility**. Meridian is listed as **Meridian** and is off. Turn off any other screen reader first, then turn on Meridian.

## Updates

Meridian checks for a new release when it starts and about once a day. When there is one, it shows a notification with the list of changes. Tap the notification to download and install it. The first time, Android asks you to allow Meridian to install apps.

The update dialog has a **Save to Downloads** switch, off by default. Turn it on to keep a verified APK in the public Downloads folder after installation. With it off, the updater uses its cache and deletes the temporary APK as before. Android 8–9 asks for storage permission only when saving is enabled.

To check now, go to **Check for updates** in Meridian settings, which also shows the version and build number. To stop automatic checks, turn off **Automatically check for updates**.

There are stable releases and development builds:

*   Stable releases are on the [releases](https://github.com/manh212/SandFly-123/releases) page, named by version, such as Meridian 0.1.0.
*   Development builds are on the [dev release](https://github.com/manh212/SandFly-123/releases/tag/dev) page, which each change replaces. Turn on **Development builds** in Meridian settings to get them. Meridian then checks about once an hour, and offers whichever is newer, so a development build is offered the next stable release.

## Moving from the original Meridian

This fork has the same app id as Aaron's Meridian, `io.github.aaron_gh.meridian`, but is signed with a different key, so Android cannot install it over the original: installing it on top fails. To move:

1.  In the original Meridian, use **Export settings** if you want to keep your settings, and save the file somewhere safe.
2.  Uninstall the original Meridian. Its settings and custom labels are removed with it.
3.  Install this fork and turn it on, then use **Import settings** to bring your settings back.

On-device AI models are not copied either, so download them again.

If you use an accessibility shortcut, grant Meridian permission to change secure settings before you turn it on, and it moves the shortcut to itself: `adb shell pm grant io.github.aaron_gh.meridian android.permission.WRITE_SECURE_SETTINGS`. Otherwise, set the shortcut again for Meridian.

## Differences from TalkBack

Meridian speaks sooner after each swipe and screen change, and can play its sounds and speech through a low-latency audio path. It adds direct key input, a circle menu, Pause Meridian, direct touch for audio games, sound themes with control sounds in 3D, a vibration for every sound, a status gesture, voice profiles, settings for how notifications are read, its own notification list and quick settings panel, image and screen descriptions from several AI services or a model on the phone. It also changes some defaults and fixes problems in TalkBack. For the full list, see [Differences from TalkBack](differences.md).

Scripts written in Lua can change how Meridian works in one app or in every app. See [Writing Meridian scripts](docs/scripting.md).

## Permissions through Shizuku or adb

Two settings need permissions that Android only grants through adb:

*   **Touch and activation** > **Fix stuck touch exploration**, which is on by default, needs the DUMP permission to fix touch exploration when Android leaves a touch down.
*   **Sound and vibration** > **Speaker when away from your ear** needs permission to manage calls.

With [Shizuku](https://shizuku.rikka.app) installed and running, Meridian grants these itself: DUMP when Meridian starts, and the call permission when you turn the setting on. The first time, Shizuku asks whether to allow Meridian. Without Shizuku, each setting shows the adb command, and its **Copy command** action copies it.

## Image descriptions

**Describe image** and **Describe screen** can use an AI service with your own API key, or a model that runs on the phone. Google's source release does not include working Gemini support, so without one of these, **Describe image** only reads text in images, and **Describe screen** does not work. Meridian adds its own support for Describe screen, including follow-up questions.

### AI services

1.  Open Meridian settings, then **Automatic descriptions**, and turn on **Detailed image descriptions**.
2.  Under **AI services**, open the settings of a service: Gemini, Claude, OpenAI (ChatGPT), Mistral, Groq, OpenRouter, Ollama Cloud, or **Other service** for another service with an OpenAI-compatible API, whose address you enter.
3.  Choose **Get a key**, where the service has one, to open its website and create a key. Paste the key into the API key field, and choose **Save**.
4.  Choose a model from the list that the service gives for your key, or enter its name.
5.  If no other service is in use, Meridian uses this one as soon as it is set up. **AI service** in **Automatic descriptions** shows the service in use and lists the services that are set up, to switch between them. **None** turns AI descriptions off.

A service can't be used until it has an address, a key and a model. When you clear the key or model of the service in use, Meridian uses the next service that is set up, or **None**. Questions about an image or screen, spoken or typed, go to the service in use.

Images and screenshots that you describe are sent to the service. On Gemini's free tier, Google can use this data to improve its products. With Gemini, when the chosen model is busy or takes more than 15 seconds, Meridian asks Gemini Flash-Lite instead.

### On-device AI

If you do not want to use an API key, or you have hit the free tier limit, Meridian can describe images and screens with a Gemma 4 model that runs on your phone. Nothing is sent to Google or anyone else when you use it.

1.  Open Meridian settings, then **Automatic descriptions**, then **On-device AI**.
2.  Choose a model. **Gemma 4 E2B** is the one to start with: 2.6 GB, and it needs a phone with about 6 GB of memory. **Gemma 4 E4B** is 3.7 GB, gives better answers, and needs about 8 GB. The list also has other small vision models from the [LiteRT community](https://huggingface.co/litert-community), from 0.4 GB up, so that you can try them. Those are marked experimental: they are community conversions that the Meridian developers have not tried, and some may not work or may follow the screen description format badly. The list only shows models that your phone has enough memory for, plus any you already have. If your phone does not have enough memory for Gemma 4 E2B, Meridian picks the largest model that fits.
3.  Choose **Download model**. It downloads once from [Hugging Face](https://huggingface.co/litert-community) and carries on where it stopped if the connection drops. A notification shows the progress. Meridian checks the file against a known SHA-256 hash and deletes it if it does not match. Or choose **Use a model file from storage** to use a `.litertlm` file that you downloaded yourself, such as `gemma-4-E2B-it.litertlm` from `litert-community/gemma-4-E2B-it-litert-lm`. Meridian works out which model it is.
4.  Turn on **Use on-device AI**.

**Detailed image descriptions** in **Automatic descriptions** must also be on. While **Use on-device AI** is on, Describe image and Describe screen use the model on your phone instead of the AI service. Turn it off to go back to the AI service.

Answers take several seconds and use battery, more than the cloud on a mid-range phone. The model loads on the first request and unloads after two idle minutes to free memory. The model runs in its own process, so if the phone runs out of memory, Android stops the model and not Meridian, and Meridian says so. Before it loads a model, Meridian checks that the phone has at least as much free memory as the model's size, and if not, it says there is not enough free memory and does not load it. To test with a model that is too big for your phone, turn on **Ignore on-device AI memory limits** in **Advanced settings** > **Developer settings**. It lists every model, lets you choose any downloaded one, and skips the free memory check. If answers fail or the phone slows down, try the other model, or turn **Use the GPU** on or off. On-device AI needs a 64-bit ARM phone and adds about 22 MB to the app.

## Silent keyboard input

Under Typing > Typing method, select **Chạm trực tiếp, không phản hồi** to give only the keyboard direct touch without Meridian feedback. This requires Android 11 or later; existing typing methods and the default remain unchanged. The existing on-screen and physical keyboard echo choices include **Không đọc khi nhập**, which suppresses typing feedback, including deletion, independently for the on-screen and physical keyboard. Physical keyboard shortcuts and cursor navigation still work. See [Silent keyboard input](docs/silent-keyboard-input.md).

## Reverse swipe gestures

Under Navigation > Movement and window navigation, **Đảo ngược cử chỉ vuốt** exchanges up with left and down with right for one-finger swipes, including both segments of angle and reversal gestures. It is off by default, preserves saved commands, and leaves multi-finger gestures separate. Gesture assignment labels and action hints show the active directions. See [Reverse swipe gestures](docs/reverse-swipe-gestures.md).

## Extended two-finger gestures

On Android 13 or later, turn on **Cử chỉ hai ngón mở rộng, thử nghiệm** under Navigation > Touch and activation. The switch is off by default. It does not add a new page or a control to the Navigation landing screen. After enabling it, assign commands to the twelve new rows in the existing Two-finger group in Gestures. All twelve start unassigned.

Move both fingers together through two clear segments, then lift both fingers. The feature supports four reversals and eight right-angle combinations. Turning it off unregisters the optional touch callback and returns gesture detection to the existing framework path; assignments remain saved. While enabled, straight two-finger swipes use the existing scroll commands after recognition, so scrolling can feel different from direct dragging. Pause and direct touch suspend the optional recognizer.

The gesture action picker also includes the menu actions that previously had no visible assignment, including clipboard and speech histories. Context-dependent choices open their existing submenu and remain subject to the current focused item and screen-lock restrictions.

## Subtitle reading

On Android 11 or later, assign **Bật hoặc tắt đọc phụ đề** and **Tìm lại vùng phụ đề** to gestures, or use them in the menu. Configure the feature under Tools > **Đọc phụ đề bằng OCR**. Tesseract is the default; download its Vietnamese and English data there before starting, or select ML Kit for Latin text.

Choose automatic detection, the top, middle or bottom of the app window, the current accessibility focus, a custom percentage rectangle, or the full app window. Automatic detection keeps the selected region through gaps in the subtitles and only searches again when requested. Recognition does not move accessibility focus. Repeated text and overlapping lines are filtered. Choose whether a newer subtitle interrupts the previous subtitle or waits for it to finish; waiting keeps only the newest pending text.

Changing apps, turning the screen off or pausing Meridian stops the session completely. Returning to the video does not restart it. Screen capture restrictions, subtitle size, background motion and device speed affect recognition; the owner verifies results on a phone.

## Languages

The APK retains its existing English and Vietnamese resources. From now on, new or changed interface text is written in Vietnamese; an English translation is not required. Existing text outside the scope of a change does not need to be converted. Code identifiers, commit messages and documentation remain in English.

## Development workflow

The coding agent first clarifies requested features with the owner, one question
at a time. After explicit approval of the agreed behavior, the agent writes and
reviews code, pushes it to GitHub, follows CI and reports the result. GitHub
Actions handles repository checks, builds, relevant tests, lint, signing,
packaging and publication. The agent does not run builds, tests, lint or
packaging on the coding machine.

1. Read [CONTRIBUTING.md](CONTRIBUTING.md) before starting and follow [the interface guidelines](docs/ux-guidelines.md) for interface work.
2. Before implementing a requested feature, ask about its relevant behavior and interactions. Ask one question at a time and wait for each answer. Summarize the agreed behavior, scope, acceptance criteria and relevant tests, then wait for explicit approval before implementing. Read-only research may support the questions. Clarify material changes to the agreed scope before implementing them.
3. Write clear code without adding comments. Review the source and diff, then commit and push directly to `master`, including large reorganizations. Do not create feature branches or pull requests.
4. GitHub Actions [automatically selects related tests](docs/ci-testing.md), including affected shared behavior. Uncertain changes broaden to related modules; shared build or CI changes can require every test module. Review the selection summary and report the tests actually run and the limits of that coverage. Existing issues in `lint-baseline.xml` are allowed; new lint errors fail CI.
5. If a check or build fails, read its failure output before diagnosing it. Use the run's `gradle-log-tail` artifact, the pull request failure summary when applicable, and the full job log or annotations when needed. Fix the cause within the approved scope, push the correction and follow CI again until it succeeds. If required logs cannot be accessed, report that limitation instead of guessing or rerunning blindly.
6. For application changes on `master`, wait for both the build job and the publication job to succeed for the pushed commit. Confirm that the rolling [dev release](https://github.com/manh212/SandFly-123/releases/tag/dev) has been updated, then report the change and the CI and release links to the owner.
7. Documentation-only changes need content, link and diff review and verification of the published files on `master`. CI automatically skips application tests, builds and releases when only documentation has changed since the last published dev build. Do not add `[skip ci]`; check the selection job's result.
8. The owner tests application changes in the published dev build on a phone, including focus order and screen reader behavior.

Upstream changes are reviewed and synchronized only when the owner requests it.

### Releases and dependencies

Application changes deliver a dev build. Create a stable release only when the owner explicitly requests it. Stable release tags use `v<version>` and must match `meridianVersion` in `version.gradle`. Release notes belong in `release-notes/<version>.md`; their list items start with `* ` because the updater reads lines starting with `- ` as commits.

GitHub Actions signs published APKs with this fork's signing key stored in Actions secrets, so they update existing installations in place. The signing keystore and password are never committed. The dev build number counts commits since `buildNumberBase` in `version.gradle`; jumps between releases are normal.

Library and plugin versions are in `gradle/libs.versions.toml`.

### Diagnostics

Tools available in debug builds help investigate problems reported during device testing:

*   The `BacktalkStall` logcat tag logs each time the main thread is blocked for more than 100 ms, with the code that was running.
*   The `BacktalkGesture` logcat tag logs touch state changes and each gesture that Meridian detects.
*   Event processing has trace sections, which show in [Perfetto](https://perfetto.dev) system traces.

## History and license

Meridian began as Aaron's fork of Backtalk, Backtalk Aaron, and became a separate project when it had many changes that are not in Backtalk. The git history keeps every change from TalkBack, Backtalk and Meridian. Files from TalkBack and Backtalk are changed in Meridian. This fork of Meridian is maintained by manh212, and its own changes are in [Differences from TalkBack](differences.md) along with Meridian's.

Meridian is under the Apache License 2.0, as TalkBack and Backtalk are. See [LICENSE](LICENSE).

The emoji names are from the [Unicode CLDR](https://cldr.unicode.org/) and the Unicode emoji data, copyright Unicode, Inc., used under the Unicode License v3, which is in `utils/src/main/assets/emoji_names/LICENSE`.

Scripts run in [Lua 5.4](https://www.lua.org/), copyright Lua.org, PUC-Rio, through [LuaJava](https://github.com/gudzpoz/luajava), copyright gudzpoz, both used under the MIT License. The license texts are in `talkback/src/phone/assets/scripting/LICENSE-luajava`, which is in the APK.

Dictation recognizes speech on the phone with [whisper.cpp](https://github.com/ggml-org/whisper.cpp) 1.9.5, copyright the ggml authors, used under the MIT License, which is in `whisper/src/main/cpp/whisper.cpp/LICENSE`. Its source is in `whisper/src/main/cpp/whisper.cpp`, with only the CPU backend. The Whisper models are downloaded from [Hugging Face](https://huggingface.co/ggerganov/whisper.cpp) when the user chooses one; they are OpenAI's Whisper models, under the MIT License.
