# Bundled default settings

`talkback/src/main/assets/initial-settings.json` is the single bundled settings
profile. Its settings match the supplied SandFly settings export; personal data,
histories, account details, script state and device bookkeeping are excluded.

On the first main-process start with the replacement logic, apply every bundled
setting to both fresh and existing installations. This deliberately replaces old
values even when the previous default snapshot matched the bundle. Keep settings
outside the profile, including local API keys, histories and updater state.
Record `bundled_defaults_applied` in the device-protected migration state only
after the settings have been committed successfully. Remove the legacy
`initial_settings_applied` and `initial_settings_snapshot` entries.

Later starts return without applying the profile. User edits, removed settings
and manually imported profiles therefore survive process restarts and app
updates. Editing the bundled JSON alone does not reset existing installations.
Clearing application data removes the marker and applies the profile again.

Move legacy credential-protected main preferences only when no device-protected
main preferences file exists and the user is unlocked, before applying defaults.
The service must not move an older file over current settings when it reconnects.

`SettingsBackupTest` covers all bundled values, fresh installs, replacement of
existing settings with and without legacy markers, repeated starts, a later
manual import, preservation of personal state, a stale credential file and retry
after a failed settings commit. GitHub Actions selects and executes the relevant
tests; device verification is performed by the owner using the published dev build.
