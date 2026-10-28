# Settings organization

The main screen has thirteen navigation entries. Updates stays first. Controls and
actions stay inside their related pages. Page depth is justified by the task,
not by an arbitrary limit on the number of levels.

| Main entry | Organization |
| --- | --- |
| Updates | Keep version checks, automatic checks and the dev channel together. |
| Speech | Put reading style, spoken item information, characters, punctuation, emoji and text formatting directly on this page. Keep engine controls and individual voice profiles as specialized editors. Remove the Verbosity page. |
| Navigation | Show topic links only: movement and window changes, touch activation, app-specific direct touch, volume keys and spoken navigation information. Controls live in these five specific pages. |
| Gestures and shortcuts | Keep gesture assignment and keyboard shortcut assignment separate because each contains a substantial, distinct action list. |
| Menus and reading controls | Open Meridian menu, navigation menu and reading control customization as siblings. Put the quick settings switch and panel customization here. |
| Sound and vibration | Keep output, volume, ducking, vibration and theme access together. Theme and individual feedback editors configure a selected sound or theme. |
| Notifications | Keep reading format, application filters, conditions, rules, schedules and history within notification settings. Select an existing voice profile here; manage global voice profiles under Speech. |
| Typing | Show keyboard echo, interruption behavior and typing method access directly. Keep dictation models and typing method details as specific editors. Remove the Keyboard echo and On-screen keyboard landing pages. |
| Descriptions and AI | Keep automatic description behavior, cloud providers and on-device models together. Provider credentials and individual model management remain dedicated editors. |
| Apps | Keep scripts and custom labels as app-specific customization. A script's own configurable schema remains controlled by that script. |
| Tools | Keep timer and offline translation configuration as separate tools. |
| Backup and reset | Group settings backup, restore, application reset and response-settings reset in a dedicated main entry. Application reset asks for confirmation before Android clears application data and stops Meridian. |
| System and help | Show setup, resume behavior, installation identity and developer access here. Remove the Advanced settings and Reduce delay landing pages. |

## Specific relocations

- Container positions, list counts, table coordinates and headers, window names
  and progress announcements belong to Navigation.
- Intonation belongs with text-to-speech engine and output controls.
- Keyboard interruption choices belong to Typing.
- Reading identifiers for unlabelled buttons belongs to Speech, under spoken item information.
- Text formatting announcement mode is a list dialog on the formatting page,
  rather than a separate screen for a single selection.
- Remove the Reduce delay screen. Low-latency audio belongs to Sound and
  vibration; always interrupting speech belongs to Speech; window delay and
  preparing items before scrolling belong to Navigation; keyboard caching and
  long-press timing belong to Typing.
- Preserve the response-settings reset action under Backup and reset. Its
  confirmation lists its scope, and unrelated settings are retained.
- Notification delay and queue handling belong to When to read; re-reading until
  the screen wakes belongs to Repeats; the duration limit belongs to Stop reading.

## State and visibility

Keep stored setting names and values unchanged. Reading presets still determine
their controlled options even though the controls are now distributed across
pages. Custom values must survive switching between presets. Shared settings
changed through reading controls or shortcuts must remain synchronized with the
visible page.

Keep device, API level, permission and feature filtering. XML keys are not a count
of visible settings: categories, aliases, dynamically created application and
profile lists, provider availability and hidden options affect that count.

CI checks the settings contract, builds, unit tests and lint. The owner verifies
the resulting dev build's screen reader focus and navigation on a phone.

## Source audit coverage

The follow-up source pass covers all thirteen main entries and the 40 XML screens
reachable through their explicit fragment links. This is a source inventory,
not a count of screens or rows visible on a particular phone. Runtime filtering,
alternative keyboard keymaps and dynamically generated editors also affect the
interface.

| Main entry XML | Linked XML screens reviewed |
| --- | --- |
| `updates_preferences` | `updates_preferences` |
| `settings_group_speech` | `screen_announcement_preferences`, `settings_group_speech`, `text_formatting_detail_preferences`, `text_to_speech_preferences` |
| `settings_group_navigation` | `navigation_feedback_preferences`, `navigation_preferences`, `settings_group_navigation`, `touch_activation_preferences`, `volume_key_preferences` |
| `settings_group_gestures_shortcuts` | `default_key_combo_preferences`, `gesture_preferences`, `settings_group_gestures_shortcuts` |
| `settings_group_menu_controls` | `context_menu_preferences`, `navigation_menu_preferences`, `selector_menu_preferences`, `settings_group_menu_controls` |
| `sound_and_vibration_preferences` | `sound_and_vibration_preferences` |
| `notification_preferences` | `notification_kinds_preferences`, `notification_preferences`, `notification_reading_preferences`, `notification_repeats_preferences`, `notification_schedules_preferences`, `notification_stop_preferences`, `notification_when_preferences` |
| `settings_group_typing` | `dictation_preferences`, `settings_group_typing`, `typing_method_preferences` |
| `settings_group_screen_info` | `automatic_descriptions_preferences`, `gemini_settings`, `settings_group_screen_info` |
| `settings_group_apps` | `settings_group_apps` |
| `settings_group_tools` | `offline_translation_preferences`, `settings_group_tools`, `timer_preferences` |
| `backup_reset_preferences` | `backup_reset_preferences` |
| `settings_group_system_help` | `developer_preferences`, `diagnostics_preferences`, `dump_events_preferences`, `settings_group_system_help` |

The only repeated row key in this static traversal is `pref_notification_access`
on When to read, Stop reading and Schedules. These are conditional permission
shortcuts for features that need notification access, rather than duplicate
stored choices.

Generated screens are assessed separately: voice profile lists and editors,
notification app filters, substitutions, rules and history, direct-touch app
lists, dictation models, sound themes and individual feedback, AI providers and
models, quick settings items, status ordering, scripts and custom labels. Their
object editors remain nested because they configure the selected profile, app,
rule, model, theme, script or label. Script-defined settings can supply their own
schema and are not fixed rows in the application's XML.

The relocated typing controls retain the engine and feature checks for keyboard
caching, and long-press timing is disabled for double-tap typing. Notification
numeric choices are initialized by the same shared settings implementation on
their new pages. CI regression tests cover these behaviors and reset scope.

The navigation landing page has no direct switches or lists. Its five destinations
are separate topics rather than a mixture of inline controls and deeper editors.
Direct touch uses its actual class in the `directtouch` package. CI verifies all
five destination classes and opens the direct-touch page, checks that backups and
reset actions stay together, verifies cancellation of application reset, and
checks the relocated unlabelled-button reading setting.
