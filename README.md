# Meridian

Meridian is an Android screen reader maintained by manh212.

This fork runs on phones and tablets with Android 8 or later. There is no watch or TV version.

## Install

Download `meridian.apk` from the [latest release](https://github.com/manh212/SandFly-123/releases/latest) and open it on the phone. The first time, Android asks you to allow your browser or file manager to install apps.

Meridian installs as `io.github.aaron_gh.meridian` and keeps its own settings.

## Turn on Meridian

Go to **Settings** > **Accessibility**. Meridian is listed as **Meridian** and is off. Turn off any other screen reader first, then turn on Meridian.

## Updates

Meridian checks for a new release when it starts and about once a day. When there is one, it shows a notification with the list of changes. Tap the notification to download and install it. The first time, Android asks you to allow Meridian to install apps.

The update dialog has a **Save to Downloads** switch, off by default. Turn it on to keep a verified APK in the public Downloads folder after installation. With it off, the updater uses its cache and deletes the temporary APK as before. Android 8–9 asks for storage permission only when saving is enabled.

To check now, go to **Check for updates** in Meridian settings, which also shows the version and build number. To stop automatic checks, turn off **Automatically check for updates**.

There are stable releases and development builds:

*   Stable releases are on the [releases](https://github.com/manh212/SandFly-123/releases) page, named by version, such as Meridian 0.1.0.
*   Development builds are on the [dev release](https://github.com/manh212/SandFly-123/releases/tag/dev) page, which each change replaces. Turn on **Development builds** in Meridian settings to get them. Meridian then checks about once an hour, and offers whichever is newer, so a development build is offered the next stable release.

## Moving an older Meridian installation

An older installation may use the same Android app id, `io.github.aaron_gh.meridian`, but a different signing key. Android cannot install this build over it, so:

1.  In the older installation, use **Export settings** if you want to keep your settings, and save the file somewhere safe.
2.  Uninstall the older installation. Its settings and custom labels are removed with it.
3.  Install Meridian, turn it on, and use **Import settings** to bring your settings back.

On-device AI models are not copied, so download them again.

If you use an accessibility shortcut, grant Meridian permission to change secure settings before you turn it on, and it moves the shortcut to itself: `adb shell pm grant io.github.aaron_gh.meridian android.permission.WRITE_SECURE_SETTINGS`. Otherwise, set the shortcut again for Meridian.

## Meridian features

Meridian speaks sooner after each swipe and screen change, and can play its sounds and speech through a low-latency audio path. It includes direct key input, a circle menu, Pause Meridian, direct touch for audio games, sound themes with control sounds in 3D, a vibration for every sound, a status gesture, voice profiles, settings for how notifications are read, its own notification list and quick settings panel, image and screen descriptions from several AI services or a model on the phone. It also changes default settings and fixes problems inherited from earlier versions. For the full list, see [Meridian features](differences.md).

Scripts written in Lua can change how Meridian works in one app or in every app. See [Writing Meridian scripts](docs/scripting.md).

## Find settings and commands

The search field at the top of Meridian settings finds settings, gesture commands and keyboard shortcuts, including nested pages. Results appear while typing, with no minimum length or result limit. A partial word or a single letter matches anywhere in a name or setting summary; case and Vietnamese accents do not matter. Multiple words must all match. Selecting a result opens its settings page; commands open the gesture action picker. Going back preserves the query, and clearing it restores the main settings list. Gesture action search uses the same matching rules.

The **Speech volume** reading control changes speech gain by 10 percentage points, from 25% to 200%. It adjusts the active voice profile when one is selected. **Clear all text** can find an editable field on the current screen while accessibility focus is on another item, using the same target selection as voice input.

## Gesture profiles and desktop keyboards

Under Gestures, **Hồ sơ cử chỉ** creates named sets of assignments. A new profile inherits all existing assignments. Application and script assignments take priority; the profile applies when neither has an assignment. Every service restart selects **Mặc định** without deleting saved profiles. Profiles can be selected in settings, through assignable commands or through the reading control. Settings export includes the profiles but does not restore an active profile.

The NVDA, JAWS and Orca layouts now use arrows and Tab to navigate while browse mode is enabled. Entering a text field does not turn browse mode off. Press the selected modifier twice to toggle browse mode, or change that assignment in keyboard settings. Turning browse mode off passes the plain navigation keys to the application. A pass-next-key command also passes the next key or combination through. Old default assignments are updated while custom shortcuts and deliberately unassigned commands are retained.

Object navigation changes the object being reviewed without moving accessibility focus. Review commands read lines, words and characters independently. Separate commands focus, activate or hold the reviewed item. The review cursor follows normal focus changes. This review feature reads the accessibility tree and does not use screen OCR.

**Nhấn trực tiếp** uses Android's touch controller when it is available and falls back to the ordinary click action. The touch controller currently requires Android 13 or later and the optional rotation or extended gesture backend to be active. A script can request this activation for individual nodes with `touchToActivate: true` in its rule. Existing gesture assignments keep their command value.

**Gửi văn bản** uses the same editor selection as voice input. It sends immediately through a clearly identified nearby Send button, or a keyboard action positively identified as Send. It does not guess Enter, Search, Done or Next. Ambiguous or missing targets leave the text unsent.

## Wallpaper, sound themes and vibration

System and help settings include **Dùng hình nền đen Meridian**, which opens Android's live wallpaper chooser. Selecting it replaces the current wallpaper with black. The wallpaper does not start or enable the accessibility service or bypass device activation. Whether it helps a particular phone keep the process alive requires device testing.

The integration keeps the existing sound sets and does not add the proposed new sound theme, following the owner's later decision. Control type speech remains a global preference. Updated common vibration patterns apply across sound themes while retaining vibration switches, strength, hardware fallbacks and explicitly supplied theme patterns.

## Permissions through Shizuku or adb

Two settings need permissions that Android only grants through adb:

*   **Touch and activation** > **Fix stuck touch exploration**, which is on by default, needs the DUMP permission to fix touch exploration when Android leaves a touch down.
*   **Sound and vibration** > **Speaker when away from your ear** needs permission to manage calls.

With [Shizuku](https://shizuku.rikka.app) installed and running, Meridian grants these itself: DUMP when Meridian starts, and the call permission when you turn the setting on. The first time, Shizuku asks whether to allow Meridian. Without Shizuku, each setting shows the adb command, and its **Copy command** action copies it.

## Image descriptions

**Describe image** and **Describe screen** can use an AI service with your own API key, or a model that runs on the phone. Without an AI service or an on-device model, **Describe image** only reads text in images, and **Describe screen** is unavailable. Meridian supports Describe screen, including follow-up questions.

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

Hold two fingers still for about half a second, move both fingers together through two clear segments, then lift both fingers. The feature supports four reversals and eight right-angle combinations. Turning it off unregisters the optional touch callback and returns gesture detection to the existing framework path; assignments remain saved. While enabled, ordinary two-finger swipes use Android's native dragging for immediate scrolling. The existing gesture assignments remain stored, but native scroll passthrough takes priority until the two-finger hold activates the extended gestures. Pause and direct touch suspend the optional recognizer.

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

*   Debug builds log each time the main thread is blocked for more than 100 ms, with the code that was running.
*   Debug builds log touch state changes and each gesture that Meridian detects.
*   Event processing has trace sections, which show in [Perfetto](https://perfetto.dev) system traces.

## History and license

Meridian is maintained by manh212 and references source code from Google TalkBack, BackTalk and SandFly. This guide documents Meridian's features and behavior. Required copyright and license notices remain with the source code.

Meridian is licensed under the Apache License 2.0. See [LICENSE](LICENSE).

The emoji names are from the [Unicode CLDR](https://cldr.unicode.org/) and the Unicode emoji data, copyright Unicode, Inc., used under the Unicode License v3, which is in `utils/src/main/assets/emoji_names/LICENSE`.

Scripts run in [Lua 5.4](https://www.lua.org/), copyright Lua.org, PUC-Rio, through [LuaJava](https://github.com/gudzpoz/luajava), copyright gudzpoz, both used under the MIT License. The license texts are in `talkback/src/phone/assets/scripting/LICENSE-luajava`, which is in the APK.

Dictation recognizes speech on the phone with [whisper.cpp](https://github.com/ggml-org/whisper.cpp) 1.9.5, copyright the ggml authors, used under the MIT License, which is in `whisper/src/main/cpp/whisper.cpp/LICENSE`. Its source is in `whisper/src/main/cpp/whisper.cpp`, with only the CPU backend. The Whisper models are downloaded from [Hugging Face](https://huggingface.co/ggerganov/whisper.cpp) when the user chooses one; they are OpenAI's Whisper models, under the MIT License.
