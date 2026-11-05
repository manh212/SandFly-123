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

The attachment is exactly the UTF-8 output of the existing full diagnostics
export, without an additional content filter, trimming or silent truncation.
The file limit is 10 MB (10,485,760 UTF-8 bytes), not a character count. A larger
export is rejected with an explanation; the user can still use the existing
local export action. Full diagnostics can include spoken text, screen content,
identifiers, credentials appearing in log messages and other personal information.
The composer explains this before the user chooses to send and offers a paginated
preview. The existing export's own collection behavior remains unchanged.

A larger unfiltered export retains the available diagnostic history; it cannot
reconstruct an error that was never logged. For troubleshooting, enable debug
logging, reproduce the issue and prepare/send diagnostics promptly. This change
does not introduce automatic recording or uploading of new kinds of screen data.

Pressing Send persists the explicitly selected content in an AES-GCM encrypted
outbox protected by Android Keystore. Large attachments are encrypted in separate
private files, while preferences and saved activity state contain only file IDs.
It holds up to 20 pending messages. Abandoned temporary files older than one day
are cleaned up, while queued attachments and the active composer file are retained. Android
JobScheduler retries that content when a network becomes available, including
after a restart. A user can cancel a waiting item; an attempt already sending
cannot be cancelled. Cancelling a future retry does not recall data that the
server already accepted. Stable message IDs prevent duplicate messages after a
lost response. A failed attempt keeps the local content and displays its error.

Users see only their own conversations and may reply. While the Meridian
accessibility service is active, an authenticated Firebase REST event stream
receives changes to the current installation's inbox. New staff replies and
actual status transitions create high-importance Android notifications; tapping
one opens that conversation. Notification permission and network availability
remain required; the support screen links to Android notification settings when
the app or channel is blocked. The stream closes when the service stops and reconnects with
backoff after disconnection or authentication expiry. It is independent of
settings/support activity visibility and of the send queue.

The first snapshot establishes a baseline without announcing old history.
Encrypted per-installation markers suppress duplicate delivery after reconnect
or restart; a reconnect reports the newest unseen reply/status per conversation
among the 100 most recently active requests. Multiple changes during a disconnect
can be represented by their latest state rather than individual historical
notifications. Full messages remain in the conversation. Opening a notification
while composing asks before discarding the draft. Manual refresh remains available.

The active inbox excludes completed requests. **Toàn bộ lịch sử phản hồi** retains
all requests. Both lists sort by the latest reply, user message or status update,
newest first. Admin pages contain twenty conversations and scan past completed
requests so those cannot conceal older open requests. Numeric timestamp cursors
and explicit boundary-tie queries avoid losing requests with equal update times.
The user's installation index is loaded as metadata and paginated locally.
Each thread loads ten messages per page in chronological reading order.
New attachment contents are fetched only when opening or downloading that log;
loading message history does not load every attachment. Legacy inline attachments
from the previous 32,000-character format remain readable.

Clearing data or reinstalling creates a new anonymous Firebase identity and a new
inbox. Old conversations remain available to authorized administrators and
support staff. Support identity and the encrypted outbox are excluded from both
Android backup/device transfer and Meridian's settings import/export.

## Meridian Quản trị

The inbox and conversation screens show loading until fresh server reads finish.
Read operations run independently of licensing synchronization. Conversation
headers, state, message pages and contact metadata are fetched concurrently where
their dependencies allow it. Inbox state reads are restricted to bounded key
ranges for the fetched headers instead of downloading the entire state table.
Refresh requests for the same inbox are coalesced, closing a screen cancels its
own requests, and failures replace the loading text with an in-dialog retry
action. No previously loaded list is used as a substitute for a failed refresh.

Open **Hộp thư phản hồi**, select a conversation and use **Trả lời phản hồi**.
Received attachments have **Xem nhật ký** and **Tải nhật ký về máy** actions.
Download opens Android's document picker before fetching a remote attachment.
Choose Downloads or another destination and save `Meridian-log-<message-id>.txt`.
The app then downloads and writes the received UTF-8 content to that chosen
document without requesting broad storage access. The attachment row shows the
current operation, and a foreground dialog reports success, cancellation or
failure instead of updating only the page behind the conversation. The pending
save stores compact remote metadata or the small legacy inline log in ordinary
app-private preferences and survives activity recreation. The save path does
not create an encrypted temporary log or decrypt the downloaded contents.
Remote logs download at most four chunks concurrently, accept logs up to 10 MB,
verify the final size and checksum, then write the plain
UTF-8 bytes to the selected document. Progress distinguishes connecting,
percentage received and writing the file. Network transfer has a 90-second
overall deadline; a separate two-minute save deadline also covers a stalled
document provider and restores the action without accepting late callbacks.
Failed saves attempt to remove the unfinished document. Pending selections
expire after ten minutes and are cleared after cancellation, success or failure;
an old operation cannot clear a newer selection. Retry from the conversation
if necessary. These changes do not alter license-key or account storage.

The internal triage states are **Mới nhận**, **Đang xử lý**, **Cần thêm thông
tin** and **Đã hoàn tất**. A conversation without a state record is new.
Internal state records remain staff-only. A validated public projection in the
owner's `supportUsers` entry contains only the status, its timestamp and the latest
staff reply ID/time. Staff identities and internal author fields are not exposed.
Changing state refreshes open admin inbox/history dialogs immediately. Completed
requests reappear in the inbox when staff change them to another status. Two-way
messaging remains available regardless of status.

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
`supportStates`, `supportStaff`, `supportContacts`, `supportAttachments` and
`supportLogChunks`. Anonymous authentication
must remain enabled. Existing enrollment and license rules retain their access
boundaries. Deploy these rules before using the new support features; the old
rules correctly reject the new paths.

Attachment metadata records a SHA-256 digest, byte size and part count. Uploads
use immutable Base64 chunks of at most 512 KiB of original data and resume without
rewriting acknowledged chunks. The client publishes the message only after
uploading every part. Readers verify the decoded sizes and digest before exposing
the completed local file. Rules restrict attachment reads to the conversation's
owner and active support staff, deny bulk reads of chunk directories, prevent
replacement and require all declared chunks before accepting an attached message.
The 10 MB file limit is measured before Base64 encoding; transport/storage usage
is larger. See [Firebase's database limits](https://firebase.google.com/docs/database/usage/limits).
Publish the complete updated rules even if the previous feedback rules were
already installed, and update both apps to handle the new attachment format.

Publish the updated rules before installing both updated APKs: clients now need
validated staff writes to the public inbox projection, its `lastAt` index and
staff-only aggregate reads of `supportStates`. The existing owner entry, messages
and license data remain intact. No backend credentials are embedded or required
for REST streaming. The APK publication workflow does not deploy production
Firebase rules; the owner must publish `activation/firebase/database.rules.json`
in the Firebase console.

This uses Realtime Database text and authenticated event streaming, without Cloud
Functions, Firebase Storage or FCM. Firebase quotas, availability and anonymous-auth limits still apply.
There is no trusted-server rate limiter or automatic abuse moderation. An app
signature check and obfuscation do not make a public client unpatchable. Reports
and saved files are retained until the owner removes them through Firebase or
the file manager; a downloaded file is outside the app's encrypted storage.

## Validation

GitHub Actions tests Firebase access rules for customer inbox isolation, hidden
triage, message immutability, attachment limits, contact access and helper
revocation without licensing privileges. Shared Java tests cover identity
concurrency, query validation, exact 10 MB UTF-8 round trips, oversize rejection,
chunk retry after a lost response, corruption detection, encrypted file integrity
and cleanup that preserves queued attachments. Admin tests cover UTF-8 downloads, pending-save recreation,
cancellation, expiry and storage failure. Settings tests cover diagnostics search
placement and exclusion of support data from settings backup. R8 verification
continues to cover the activation/shared and admin namespaces.

The owner must still test Android's document picker, offline retry/cancellation,
TalkBack focus order and real-device messaging after publishing the database
rules. Emulator/unit tests do not establish production Firebase configuration or
device behavior.

Additional CI coverage verifies streaming event framing, authentication expiry,
notification deduplication/reconnection, direct notification navigation, status
projection isolation, reply-marker validation, latest-activity ordering, filtering
past completed pages and pagination across equal timestamps. On-device testing
still needs both updated apps and the deployed rules, including screen-off
connectivity, notification permission, offline reconnection and service stop.
