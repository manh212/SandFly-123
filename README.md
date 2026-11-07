# Package

Android application source and build configuration.

## Install

Download `meridian.apk` from the [development release](https://github.com/manh212/pkg-7f3c9a2e/releases/tag/dev), install it, then enable the application in Android accessibility settings. Android 8 or later is required. Update existing installations in place.

## Documentation

- [Application notes](differences.md)
- [Device administration](docs/device-license-management.md)
- [Support setup](docs/support-feedback.md)
- [Firebase data cleanup and connection policy](docs/firebase-cleanup.md)
- [Scripts](docs/scripting.md)

## Development

Read [CONTRIBUTING.md](CONTRIBUTING.md), [agent instructions](AGENTS.md) and [interface guidelines](docs/ux-guidelines.md). They define approval, source review, direct master publication and CI requirements. GitHub Actions handles checks, relevant tests, builds, signing and dev publication. Device testing is performed by the owner.

## License and attribution

Meridian is maintained by manh212 and references source code from Google TalkBack, BackTalk and SandFly. This guide documents Meridian's features and behavior. Required copyright and license notices remain with the source code.

Meridian is licensed under the Apache License 2.0. See [LICENSE](LICENSE).

The emoji names are from the [Unicode CLDR](https://cldr.unicode.org/) and the Unicode emoji data, copyright Unicode, Inc., used under the Unicode License v3, which is in `utils/src/main/assets/emoji_names/LICENSE`.

Scripts run in [Lua 5.4](https://www.lua.org/), copyright Lua.org, PUC-Rio, through [LuaJava](https://github.com/gudzpoz/luajava), copyright gudzpoz, both used under the MIT License. The license texts are in `talkback/src/phone/assets/scripting/LICENSE-luajava`, which is in the APK.

Dictation recognizes speech on the phone with [whisper.cpp](https://github.com/ggml-org/whisper.cpp) 1.9.5, copyright the ggml authors, used under the MIT License, which is in `whisper/src/main/cpp/whisper.cpp/LICENSE`. Its source is in `whisper/src/main/cpp/whisper.cpp`, with only the CPU backend. The Whisper models are downloaded from [Hugging Face](https://huggingface.co/ggerganov/whisper.cpp) when the user chooses one; they are OpenAI's Whisper models, under the MIT License.

