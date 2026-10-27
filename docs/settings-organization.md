# Settings organization

The main screen has twelve navigation entries. Updates stays first. Controls and
actions stay inside their related pages. Page depth is justified by the task,
not by an arbitrary limit on the number of levels.

| Main entry | Organization |
| --- | --- |
| Updates | Keep version checks, automatic checks and the dev channel together. |
| Speech | Put reading style, spoken item information, characters, punctuation, emoji and text formatting directly on this page. Keep engine controls and individual voice profiles as specialized editors. Remove the Verbosity page. |
| Navigation | Show movement controls and spoken positional information directly. Keep touch activation, app-specific direct touch and volume key configuration as related editors. Remove the second Navigation landing page. |
| Gestures and shortcuts | Keep gesture assignment and keyboard shortcut assignment separate because each contains a substantial, distinct action list. |
| Menus and reading controls | Open SandFly menu, navigation menu and reading control customization as siblings. Put the quick settings switch and panel customization here. |
| Sound and vibration | Keep output, volume, ducking, vibration and theme access together. Theme and individual feedback editors configure a selected sound or theme. |
| Notifications | Keep reading format, application filters, conditions, rules, schedules and history within notification settings. Select an existing voice profile here; manage global voice profiles under Speech. |
| Typing | Show keyboard echo, interruption behavior and typing method access directly. Keep dictation models and typing method details as specific editors. Remove the Keyboard echo and On-screen keyboard landing pages. |
| Descriptions and AI | Keep automatic description behavior, cloud providers and on-device models together. Provider credentials and individual model management remain dedicated editors. |
| Apps | Keep scripts and custom labels as app-specific customization. A script's own configurable schema remains controlled by that script. |
| Tools | Keep timer and offline translation configuration as separate tools. |
| System and help | Show setup, backup, resume behavior, installation identity, performance and developer access here. Remove the Advanced settings landing page. |

## Specific relocations

- Container positions, list counts, table coordinates and headers, window names
  and progress announcements belong to Navigation.
- Intonation belongs with text-to-speech engine and output controls.
- Keyboard interruption choices belong to Typing.
- Spoken element identifiers belong to Developer settings.
- Text formatting announcement mode is a list dialog on the formatting page,
  rather than a separate screen for a single selection.
- Performance and latency configuration is accessed through System and help,
  rather than nested below touch activation.

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
