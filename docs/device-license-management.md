# Device licensing and private enrollment

## Deployment

Install both APKs from the same dev release. Keep the existing Meridian Admin application data and hardware signing key. Updating the APK preserves the key. Uninstalling the admin application or erasing its data can permanently destroy its signing identity; Firebase and GitHub cannot restore a hardware private key.

The service is disabled until `activation/assets/meridian-service.json` contains the Firebase public web API key and the exact Realtime Database HTTPS root URL. Do not add service account credentials, passwords, GitHub tokens or private keys to this file. Both applications use the same Firebase project and the same public configuration. This REST integration does not require Google Play services or a Firebase Android SDK dependency.

1. Create a Firebase project on Spark. Enable Anonymous authentication under Authentication, Sign-in method. Create Realtime Database in locked mode.
2. Register the Android packages `io.github.aaron_gh.meridian` and `com.sandfly.offlinelicenser.v2`. Their downloaded `google-services.json` files identify the public API key and project. Copy the database URL from Realtime Database; do not use a Firestore URL.
3. Configure the public service profile and publish both updated APKs. Deploy the exact contents of `activation/firebase/database.rules.json` through Realtime Database, Rules, Publish. Never switch to public test-mode rules.
4. In Meridian Admin, open the registration service setup and copy the phone's UID. In Firebase Console, Realtime Database, Data, create `admins`, then a Boolean child whose key is that UID and whose value is `true`. Only the owner console can assign this role; no client, including the admin application, may write `admins`.
5. Return to the admin application, check administrator access and synchronize. If moving to another admin phone, provisioning a UID alone does not transfer the original hardware signing key.

There is no Cloud Function, billing account, web dashboard or additional login form in this flow. The database rules enforce private access. A public Firebase API key is not an administrator credential. If restricting the API key, allow the Firebase Authentication REST endpoints used by Identity Toolkit and Secure Token; do not require website referrers for these Android clients.

Rules tests run against a demo database emulator in GitHub Actions before publishing the admin APK. Passing emulator tests does not deploy production rules. The owner must publish the rules above and provision the admin UID.

For live license changes, publish the updated complete rules file before using
the new builds. The new `licenseEvents/<device-hash>` path contains only the
device request, signed policy and server update time. Authenticated clients can
read a known device path but cannot list the directory or write policies.
Names, descriptions and Firebase identities remain in the owner-only directory.
The admin batches private records, enrollment decisions and license events into
one atomic multi-path update. If production still has the older rules, it keeps
publishing private records and decisions, reports that the rules need updating,
and leaves event publication pending for the next synchronization.

## User registration

Open Meridian settings, Updates, the Meridian activation entry. Enter both full name and description and submit the request. The application sends it directly; the user does not visit a website or copy a request to the owner. The fields become immutable after submission. Reopening this page checks the decision automatically. GitHub recovery also works independently of this page once an approved license is published.

In Meridian Admin, open pending requests, review the name, description and device request, then approve or reject. Approval opens package, license type and duration selection and requires system authentication to sign. A trial must have a positive duration; standard licenses may use zero days for no expiry. The signed result is saved locally first, then uploaded to GitHub, then recorded privately and delivered to the request's Firebase UID. Failed uploads remain queued. Rejection does not revoke an existing device license.

Device identity is the SFREQ2 hash. Equal display names do not merge devices. The user-entered name and description are unverified labels. Reinstalling Meridian produces a new anonymous Firebase UID, but the same Android user, application signing identity and Android ID normally produce the same device request. The application can recover the signed record from GitHub without an old Firebase session. Factory resets, another Android profile and signing identity changes can require a new license.

## Device actions and packages

Select a device in the admin list to see its request, hash, private name and description, signed license, state, package, feature snapshot, issue date, expiry and both upload states. Pending local changes remain visible and clearly marked after a fresh directory read. Private records can be downloaded from Firebase by the authorized admin using the original issuer key.

- Suspend denies use when the updated client receives the signed state. Resume preserves the original issue date, features and expiry, including an already elapsed expiry.
- Revoke denies use and offers no resume action. Explicitly issue a new license to permit the device again.
- Reissue, renew or change package creates a new signed revision. The selected duration starts when that revision is prepared for signing.
- Create or edit named package templates by selecting AI, OCR, translation, voice input and Lua scripting. A valid issued package always includes basic screen reading. Templates are stored encrypted on the admin phone; template edits affect future issuance only. Existing feature snapshots are immutable until explicitly reissued for that device.
- Runtime checks cover AI text, image and screen requests, ML Kit and Tesseract caption OCR, live subtitle OCR, script screenshot OCR, translation including automatic translation, voice input and Lua loading, dispatch and host calls. A compound action needs each involved group; for example AI translation needs translation and AI, and script screenshot OCR needs scripting and OCR.

An outstanding change whose revision is older than GitHub cannot overwrite the newer record. Open device details, fetch the latest GitHub state and reopen the details before signing a fresh change. Imports replace an older pending policy only with a verified newer signed revision.

## Compatibility and offline limits

Existing SFLIC1 and SFLIC2 licenses remain unexpired in their own token format,
but updated clients require a successful online verification within seven days.
Legacy SFLIC1 devices without a newer recoverable catalog record may need the
owner to reissue an SFLIC3 license before updating the client. New policies use
SFLIC3 and must not include a perpetual legacy fallback.

Meridian checks the signed catalog through short-lived HTTPS requests at service
startup and approximately every six hours while the service runs. Each response
must contain a valid signature, correct device identity, nondecreasing revision
and server-time anchor before extending the offline verification window. An
accepted SFLIC3 certificate carries the signed absolute issue and expiry times.
A two-day grant expires after the signed two-day deadline even if offline.

A missing, malformed, stale or unavailable remote record cannot renew the
verification window or override a previously verified revoke. Network or
validation failures retry after a fifteen-minute minimum interval. The client
also checks whenever the activation form requests an explicit refresh. The
Firebase enrollment response can anchor the first successful verification of
an approved, signed policy.

The maximum offline verification window is seven days. When this window expires,
license-dependent access is suspended while the accessibility service stays
registered for eventual reconnection and automatic revalidation. The signed
expiry may deny access sooner. A revoked policy remains denied even after
reconnecting; only a newly signed grant with a later revision can restore it.
No SSE connection is maintained for licensing or support notifications.

## Administrator responsiveness

Device and support lists wait for fresh server reads before displaying records.
Failed refreshes show an error and retry action rather than cached lists. Device
reads and support reads use bounded executors separate from write synchronization;
independent detail queries run concurrently. Inbox status queries cover the keys
in each page, cap range reads and fetch any truncated boundary records explicitly.
Timestamp-tie pagination and scanning past completed requests remain intact.

Firebase clients reuse an unexpired authenticated session within the same app
and configuration. Permission checks still occur on server requests. Expired
authentication retries once; permission failures remain failures. GitHub
installation tokens are reused conservatively within their lifetime. Idle
synchronization avoids unnecessary remote work, profile verification is shared
within one publication batch, and imported device records are validated before
one encrypted storage write. Concurrent signing and imports cannot overwrite a
newer signed revision. Local pending changes remain explicitly pending until
their corresponding remote writes succeed.

Limited licenses use HTTPS server time, elapsed realtime within a boot and a persisted time floor. A significant backward clock change across a reboot requires correcting device time or reconnecting for a trusted time anchor. These checks do not provide a tamper-proof offline clock on a rooted or patched device. Fully offline clients cannot receive a new revoke command. Older APKs do not implement the new policy checks and must be updated to honor them. R8 renames the activation implementation in both APKs and CI verifies the resulting mappings and DEX; it cannot make client code impossible to patch.

Names and descriptions exist only in encrypted admin storage and access-controlled Firebase. Public GitHub catalog records contain the device hash and signed policy, including package label and feature rights. Service credentials for GitHub and the issuer's private key remain exclusively on the admin phone. Anonymous client authentication cannot grant itself administrator access, read other users' requests, edit a submitted request or issue an approval. Authentication sign-up and database quotas still apply; strong abuse prevention beyond provider quotas would require an additional trusted service or attestation design.

## Offline verification lease

Updated clients use short-lived HTTPS checks at startup and approximately every six hours while the accessibility service runs. A valid signed state and trustworthy server time refresh a verification lease of **seven days**. Missing data, signature failures and network errors do not refresh it. A client suspended after the lease expires automatically unlocks after it verifies an active, unexpired policy online; a revoked policy does not unlock. An explicitly signed `expiresAt` always takes precedence over the seven-day lease.

See [Firebase data cleanup and bounded connections](firebase-cleanup.md) for the new notification and connection policy. Offline clocks across reboot, modified APKs and rooted devices cannot provide an absolute cryptographic guarantee without an external trusted time source.
