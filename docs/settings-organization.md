# Settings organization

The main settings screen has sixteen entries. Each stored choice has one owning
page. Related pages may link to that owner when a task naturally leads there;
they do not maintain a second copy of its controls.

## Main navigation

| Main entry | Owner and contents |
| --- | --- |
| Updates | Version, channel and update checks. |
| Giọng đọc | Speech engine, output options and voice profiles. |
| Nội dung đọc | Reading style, spoken item information, screen announcements and text reading options. |
| Điều hướng | Movement, touch activation, direct touch, volume keys and spoken navigation feedback. |
| Cử chỉ và phím tắt | Gesture and keyboard shortcut assignment. |
| Menu và bảng điều khiển | Meridian menus, selector groups, navigation menu and quick settings panel. |
| Âm thanh và rung | Sound, vibration, latency and sound themes. |
| Thông báo | Reading, apps, words, filters, conditions, rules, schedules, list behavior and history. |
| Nhập liệu | Keyboard echo, typing methods, interruption, keyboard timing and dictation. |
| Nhận dạng và AI | Image descriptions, on-device AI, screen recognition, subtitles and OCR. |
| Dịch | Translation providers and translation behavior. |
| Đồng hồ và bấm giờ | Spoken clock and stopwatch settings and history. |
| Giao việc cho Meridian | Task assistant and its AI/model choices. |
| Tập lệnh và nhãn | Scripts and custom labels. |
| Sao lưu và đặt lại | Settings backup and restore, direct-touch backup, and reset actions. |
| Hệ thống và trợ giúp | Initial setup, Android accessibility settings, help, resume behavior, installer identity and developer tools. |

## Ownership and duplicate rules

- **Speech and voice profiles:** The reading-content page owns what is spoken. The voice page owns speech output and profile editing. App-specific voice choices only select an existing profile; profile rename and deletion happen in the profile editor.
- **Navigation and shortcuts:** Navigation owns navigation behavior. The gestures page owns gesture assignments. Navigation links to direct touch for that task; the direct-touch backup route opens the same feature in backup-only mode.
- **Menus and selector groups:** Menu customization has one home. On supported devices the selector row opens the selector-group editor. Existing duplicate selector items migrate to the user's group when one exists, and subsequent saves reject the same item appearing in multiple groups.
- **Quick settings:** The panel screen owns both the panel switch and its item order. The old copy in Advanced settings is removed.
- **Notifications:** The notification landing screen is a topic index. Reading, app rules, word rules, filters, list composition, automation and timing options are each owned by their specific page. History actions open the app or blocked-word owner with a suggested change; history itself does not save those settings.
- **OCR and subtitles:** Screen recognition's OCR engine and the shared Tesseract data download have one OCR owner. Subtitles keep their own engine choice, recognition region and speech behavior because those options control subtitle scanning; they can link to the shared OCR page for the data download.
- **Backup and reset:** Export, import and reset actions are grouped together. Gesture and menu resets are invoked here, and keep their existing reset scopes.
- **Initial setup:** The setup entry is under System and help, separate from timer configuration.
- **AI integrations:** The obsolete Gemini voice-command integration is removed. Current provider/model settings and on-device AI remain available from the recognition and assistant tasks that use them.

## Relocated settings

- Container positions, list counts, table coordinates and headers, window names and progress announcements belong to Navigation.
- Text-to-speech engine and output controls belong to Giọng đọc; reading verbosity and content options belong to Nội dung đọc.
- Phonetic letter mode belongs to the reading-text options in Nội dung đọc; the Giọng đọc page owns speech output, engine and voice profiles.
- Keyboard interruption, echo, method and timing controls belong to Nhập liệu. Dictation model selection stays with dictation.
- Reading identifiers for unlabelled buttons belong to spoken item information.
- Text-formatting announcement mode is a choice on its formatting page rather than a separate one-option screen.
- Low-latency audio belongs to Âm thanh và rung; speech interruption belongs to Nội dung đọc; window delay and preparation before scrolling belong to Điều hướng; keyboard caching and long-press timing belong to Nhập liệu.
- Notification delay and queue handling belong to When to read; repeating belongs to Repeats; the duration limit belongs to Stop reading.

## Preserved behavior and data

Stored setting keys and values stay unchanged except where an old duplicate is explicitly migrated to its canonical key. Existing reading preset values remain intact when controls move between pages. Reset confirmation keeps its established scope and preserves unrelated settings. Selector migration keeps the user-group copy of any duplicate item and removes copies from other groups.

Runtime device, API level, permission and feature filters still determine which rows are visible. Dynamic lists and object editors—apps, notification rules, profiles, models, scripts, labels, menus and sound themes—remain within the feature that owns those objects. Related shortcuts are routes to the canonical page, not additional settings owners.

## Regression coverage

CI selects the relevant settings tests from the changed paths. Coverage checks main navigation and page destinations, the settings search index, selector settings, direct-touch backup/import behavior, subtitle options, and the exact response/reset scopes. GitHub Actions runs builds, selected tests and lint; the owner checks screen-reader focus and navigation on a phone using the published dev build.

## Call settings

The reading-content page owns **Cài đặt cuộc gọi**: incoming-call speech,
repetitions, final and periodic duration, a selected existing voice profile,
independent quiet hours and a call-specific Do Not Disturb choice. Initial setup
owns the related permission requests. See [Call and volume controls](call-and-volume-controls.md).
