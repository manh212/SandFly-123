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

## User registration

Open Meridian settings, Updates, the Meridian activation entry. Enter both full name and description and submit the request. The application sends it directly; the user does not visit a website or copy a request to the owner. The fields become immutable after submission. Reopening this page checks the decision automatically. GitHub recovery also works independently of this page once an approved license is published.

In Meridian Admin, open pending requests, review the name, description and device request, then approve or reject. Approval opens package, license type and duration selection and requires system authentication to sign. A trial must have a positive duration; standard licenses may use zero days for no expiry. The signed result is saved locally first, then uploaded to GitHub, then recorded privately and delivered to the request's Firebase UID. Failed uploads remain queued. Rejection does not revoke an existing device license.

Device identity is the SFREQ2 hash. Equal display names do not merge devices. The user-entered name and description are unverified labels. Reinstalling Meridian produces a new anonymous Firebase UID, but the same Android user, application signing identity and Android ID normally produce the same device request. The application can recover the signed record from GitHub without an old Firebase session. Factory resets, another Android profile and signing identity changes can require a new license.

## Device actions and packages

Select a device in the admin list to see its request, hash, private name and description, signed license, state, package, feature snapshot, issue date, expiry and both upload states. Existing local SFREQ2 records remain visible. Private records can be downloaded from Firebase by the authorized admin using the original issuer key.

- Suspend denies use when the updated client receives the signed state. Resume preserves the original issue date, features and expiry, including an already elapsed expiry.
- Revoke denies use and offers no resume action. Explicitly issue a new license to permit the device again.
- Reissue, renew or change package creates a new signed revision. The selected duration starts when that revision is prepared for signing.
- Create or edit named package templates by selecting AI, OCR, translation, voice input and Lua scripting. A valid issued package always includes basic screen reading. Templates are stored encrypted on the admin phone; template edits affect future issuance only. Existing feature snapshots are immutable until explicitly reissued for that device.
- Runtime checks cover AI text, image and screen requests, ML Kit and Tesseract caption OCR, live subtitle OCR, script screenshot OCR, translation including automatic translation, voice input and Lua loading, dispatch and host calls. A compound action needs each involved group; for example AI translation needs translation and AI, and script screenshot OCR needs scripting and OCR.

An outstanding change whose revision is older than GitHub cannot overwrite the newer record. Open device details, fetch the latest GitHub state and reopen the details before signing a fresh change. Unsynced older changes are replaced only by a verified newer signed revision during this explicit reconciliation.

## Compatibility and offline limits

SFLIC1 and SFLIC2 licenses already stored on a device retain full rights and no expiry. Merely synchronizing the admin application does not migrate or restrict these licenses. SFLIC1 still depends on its original installation key. New policies use SFLIC3 and must not include a perpetual legacy fallback.

An updated client checks GitHub while its accessibility service is running and when activation or settings opens. Offline use has no renewal deadline. The last verified active license works until its own expiry. Cached suspend and revoke states remain denied offline; missing, malformed, stale or unavailable remote data cannot restore an older grant. Initial manual activation checks current remote state to prevent using a pasted older grant after clearing app data. A signed record alone does not give a client permission to change policy or approve requests.

Limited licenses use HTTPS server time, elapsed realtime within a boot and a persisted time floor. A significant backward clock change across a reboot requires correcting device time or reconnecting for a trusted time anchor. These checks do not provide a tamper-proof offline clock on a rooted or patched device. Fully offline clients cannot receive a new revoke command. Older APKs do not implement the new policy checks and must be updated to honor them. R8 renames the activation implementation in both APKs and CI verifies the resulting mappings and DEX; it cannot make client code impossible to patch.

Names and descriptions exist only in encrypted admin storage and access-controlled Firebase. Public GitHub catalog records contain the device hash and signed policy, including package label and feature rights. Service credentials for GitHub and the issuer's private key remain exclusively on the admin phone. Anonymous client authentication cannot grant itself administrator access, read other users' requests, edit a submitted request or issue an approval. Authentication sign-up and database quotas still apply; strong abuse prevention beyond provider quotas would require an additional trusted service or attestation design.
