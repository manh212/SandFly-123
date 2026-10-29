# Selected upstream changes, 2026-10-29

Source: aaron-gh/SandFly at e79d0fe2d6d685a9a9104d21b176744caeeaf265.
The owner selected these changes after reviewing the changes since 0.1.8.

## Included

- Editable order and membership of the Meridian menu and reading controls.
  The fork's own menu entries are included in the editable list.
- Optional arrow-key navigation for physical keyboards, initially off, with
  configurable single-key and two-key bindings. The existing gesture and
  keyboard settings remain in use.
- On-device translation, Google Cloud Translation, DeepL, Microsoft Translator,
  LibreTranslate and the existing AI service. Source and target language choices
  from the fork's previous translation page keep their preference keys.
- Automatic language detection, automatic speech translation, notification
  translation in its configured voice, and on-device language model management.
  Automatic translation and notification translation are initially off.
- Shared dictation for text entry, image and screen questions, and voice commands.
  Dictation can stop after a configurable silence interval, initially off.
  Questions and voice commands stop after a speech pause without requiring
  another gesture. The fork's asynchronous audio capture shutdown is retained.
- Sound mode reading control; notification-list reconnection after an update;
  supplemental descriptions on older Android releases; complete web-post text;
  punctuation normalization for spoken commands; Android backup disabled.
  The fork's case-insensitive API-key filtering is retained.

## Excluded or deferred

- No unified Commands settings page and no automatic new-features page.
- No SandFly Pro module, server integration or subscription features.
- No restoration of removed braille, watch, handwriting, reporting or settings
  features; no replacement of the fork's settings layout or release workflow.
- Dependency updates are deferred to a separate change.

The fork's copy actions, favorites, clipboard history, recording focus guard,
speech amplification and navigation latency changes are retained.

Per the owner's instruction, this change is reviewed as source and pushed
without running local checks, tests, builds, lint or packaging, and without
monitoring GitHub Actions. Imported regression tests are left for CI execution.
