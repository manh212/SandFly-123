# Feedback, manual diagnostics and support roles

Meridian settings has a main navigation entry named **Nhật ký và báo lỗi**.
It contains the existing debug logging switch, local diagnostics export, copy and
delete actions, plus feedback composition, **Gửi nhật ký mới nhất**, the support
inbox and the pending outbox. These diagnostics controls no longer live in Help.
The registration screen also opens the support inbox. A valid license is not
required: new, expired, suspended and revoked installations can contact support.

## Sending and replying

Users enter a subject and feedback or bug description. Reproduction steps are
optional. The app includes its version, Android version and the stable hashed
device request, so the administrator can relate the report to a license without
publishing the person's name on GitHub. The display name is remembered locally.

Attaching diagnostics is manual. The attachment switch starts off for ordinary
feedback. **Gửi nhật ký mới nhất** opens a composer with attachment selected;
it still requires the user to press **Gửi**. Users can preview the attachment.
There are no automatic crash uploads or background log collection for support.

The attachment is a filtered diagnostic snapshot, at most 32,000 characters.
Only selected device/app metadata, stack frames and numeric or Boolean diagnostic
fields survive the allowlist. Arbitrary spoken text, screen content and lines
matching credential, email or URL patterns are omitted. The existing local full
export and clipboard actions retain their original behavior and may contain
personal information. Review any file before sharing it separately.

Pressing Send persists the explicitly selected content in an AES-GCM encrypted
outbox protected by Android Keystore. It holds up to 20 pending messages. Android
JobScheduler retries that content when a network becomes available, including
after a restart. A user can cancel a waiting item; an attempt already sending
cannot be cancelled. Cancelling a future retry does not recall data that the
server already accepted. Stable message IDs prevent duplicate messages after a
lost response. A failed attempt keeps the local content and displays its error.

Users see only their own conversations and may reply. Use **Tải tin nhắn mới
nhất** to retrieve replies; there is no push notification service. Each thread
loads ten messages per page and each inbox page loads twenty conversations.
The administrator can browse recent activity and the complete paginated history.

Clearing data or reinstalling creates a new anonymous Firebase identity and a new
inbox. Old conversations remain available to authorized administrators and
support staff. Support identity and the encrypted outbox are excluded from both
Android backup/device transfer and Meridian's settings import/export.

## Meridian Quản trị

Open **Hộp thư phản hồi**, select a conversation and use **Trả lời phản hồi**.
Received attachments have **Xem nhật ký** and **Tải nhật ký về máy** actions.
Download opens Android's document picker. Choose Downloads or another destination
and save `Meridian-log-<message-id>.txt`. The app writes the received UTF-8 content
to that chosen document without requesting broad storage access. A pending save
is encrypted, survives activity recreation, expires after ten minutes and is
cleared after cancellation, success or a failed write. Retry the download from
the conversation if necessary.

The internal triage states are **Mới nhận**, **Đang xử lý**, **Cần thêm thông
tin** and **Đã giải quyết**. A conversation without a state record is new.
These states are stored separately from user-readable messages. Firebase denies
users access to the state records, including their own. Two-way messaging remains
available regardless of triage state.

The owner opens **Người hỗ trợ**, adds the other phone's Firebase UID and a
display name, and enables support access. The other person installs Meridian
Quản trị, copies **UID điện thoại này**, then checks access after the owner grants
it. Helpers can read and reply to all conversations, download attached logs and
change internal triage states. They cannot grant staff access, issue/revoke
licenses, read the license directory or become administrators. Disable their
support access to stop further Firebase reads and writes; copies already read or
downloaded cannot be recalled. The owner-only licensing controls stay hidden on
a helper's phone, and Firebase enforces the license data boundary independently.

## Last contact

Meridian records a server timestamp on opening its settings/registration/support
UI, submitting registration or sending feedback. Opening events are debounced
for one minute. There is no periodic presence heartbeat. The administrator sees
the latest available timestamp in a report and in device details. Device details
can match the same stable request across old and new installation inboxes. The
timestamp is only evidence of the last accepted contact, not proof that the
phone is currently online, connected to a particular network or running Meridian.

## Firebase deployment and limits

Publish the complete updated [Realtime Database rules](../activation/firebase/database.rules.json)
in the existing project's **Realtime Database > Rules** tab. Keep the JSON keys
in English, including `rules`, `admins` and `supportMessages`; browser translation
must not modify the code. Publishing rules does not delete the existing database
or the Boolean owner entry under `admins/<uid>`. No signing key, admin token or
service-account credential is needed in either APK.

The new groups are `supportIndex`, `supportUsers`, `supportMessages`,
`supportStates`, `supportStaff` and `supportContacts`. Anonymous authentication
must remain enabled. Existing enrollment and license rules retain their access
boundaries. Deploy these rules before using the new support features; the old
rules correctly reject the new paths.

This uses Realtime Database text only, without Cloud Functions, Firebase Storage
or FCM. Firebase quotas, availability and anonymous-auth limits still apply.
There is no trusted-server rate limiter or automatic abuse moderation. An app
signature check and obfuscation do not make a public client unpatchable. Reports
and saved files are retained until the owner removes them through Firebase or
the file manager; a downloaded file is outside the app's encrypted storage.

## Validation

GitHub Actions tests Firebase access rules for customer inbox isolation, hidden
triage, message immutability, attachment limits, contact access and helper
revocation without licensing privileges. Shared Java tests cover identity
concurrency, query validation, filtered diagnostics and idempotent retry after a
lost response. Admin tests cover UTF-8 downloads, pending-save recreation,
cancellation, expiry and storage failure. Settings tests cover diagnostics search
placement and exclusion of support data from settings backup. R8 verification
continues to cover the activation/shared and admin namespaces.

The owner must still test Android's document picker, offline retry/cancellation,
TalkBack focus order and real-device messaging after publishing the database
rules. Emulator/unit tests do not establish production Firebase configuration or
device behavior.
