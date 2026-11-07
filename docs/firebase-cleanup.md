# Firebase data cleanup and bounded connections

## Scope

Meridian Admin exposes **Quản lý dữ liệu Firebase** to the verified owner only. The tool can:

- Estimate the number of support conversations, support logs, contacts, registration requests, helpers, and reserved forum records.
- Remove an individual diagnostic attachment, or attachments older than 7, 30, 60 or 90 days, or all attachments.
- Remove individual support conversations, conversations idle for 30, 90 or 180 days, or the complete support history, including diagnostic chunks.
- Remove support contacts, support helper entries, and pending enrollment requests with **no decision record**.
- Remove all records from the reserved forum namespaces, or all removable groups together.

Deletion is **not automatic**. Every destructive plan requires entering `XOA`. The interface shows a scope preview and reports progress and partial failures. A second attempt can clean up the remainder. Log sizes are estimated from attachment metadata, not from downloaded logs. This is not the Firebase billing/storage meter. A single Firebase dataset may exceed the safe response size for a broad listing; the application must report an error instead of claiming a complete count in that case.

## Protected data

The cleanup interface never offers removal of:

- `admins`: owner authorization;
- `devices`: private device license directory;
- `decisions`: the device's approved/rejected enrollment decision;
- `licenseEvents`: current signed license delivery state;
- `requests/<uid>` when `decisions/<uid>` exists: this request remains necessary for future decision updates.

These boundaries are independently enforced by `activation/firebase/database.rules.json`. The cleanup API has a second explicit deletion allowlist. Staff members with support access do **not** receive deletion authority.

Existing support messages are not changed when only an attachment is removed. The message may still refer to a deleted attachment; attempting to fetch it reports that the log was cleaned. Older inline diagnostic fields live inside the original message; these are removed by deleting the entire conversation, not by the attachment-only action. Downloaded copies outside Firebase cannot be recalled.

`forumCategories`, `forumTopics`, `forumPosts`, `forumProfiles` and `forumModerators` are reserved as **owner-only** cleanup paths. This change does not implement a public forum. An eventual forum implementation must add a validated public access policy and recheck its deletion dependencies.

## Deployment

Publishing an APK does not deploy RTDB rules. Before enabling cleanup on production devices, the owner must publish the complete new `activation/firebase/database.rules.json` in Firebase Console, keeping the real `admins/<uid>` owner entry unchanged. **Do not** delete the RTDB root or use test-mode rules. Production data is not changed by editing this source file.

## Connection policy

There is no continuously open Firebase SSE stream in the client notification paths. License recovery uses a short-lived GitHub HTTPS request when the accessibility service starts and every six hours while it runs; failed checks are retried with a fifteen-minute minimum interval. Support notifications are read from the 100 most recently active conversations in short REST polls approximately every fifteen minutes while the accessibility service runs. No Firebase Cloud Messaging, Cloud Functions, or Blaze upgrade is required for this mode. Android network state, battery restrictions and process lifetime may delay a check.

## License safety

`SFLIC3` already encodes the signed absolute `issuedAt` and `expiresAt` dates. A two-day license is checked against that absolute deadline locally; it is not extended by another seven-day allowance. A valid signed state fetched from GitHub with an HTTP server-date anchor refreshes the seven-day verification lease. A missing record, invalid signature, older revision, failed network call or untrusted server date **never** extends that lease.

After seven days without a verified server check, the entitlement is temporarily blocked. On a subsequent successful fetch, it automatically resumes if the latest signed policy is active and unexpired. A signed revoked state remains denied until a later valid signed revision explicitly grants access. The accessibility service stays registered during temporary offline suspension so it can retry and recover automatically; its main accessibility events, keys and gesture actions are restricted during suspension. Existing revoked or expired policy continues to follow the app's existing disable process. This behavior needs device testing with a usable fallback screen reader before release.

Security limitations: an unmodified Android app cannot provide tamperproof elapsed time across all reboots or on compromised/rooted devices without a secure external time source. Old APKs do not learn the new seven-day policy until upgraded. Online revocation cannot be delivered before a device reconnects; the seven-day limit bounds the offline use of updated, intact clients, subject to the clock and platform limitations noted here.

## Required validation before deployment

- Firebase emulator: owner deletion succeeds only for intended paths; ordinary users and support helpers cannot delete; protected license/admin records remain intact; requests with decisions stay protected.
- License tests: two-day expiration, signed revocation precedence, same-boot and post-reboot clock handling, exactly seven-day boundary, missing server Date, HTTP 404, untrusted/older signed record and return online.
- Client behavior on device: no SSE sockets remain, bounded polling and data consumption, notifications still work after reconnect, offline suspension blocks licensed access without permanently disabling the service, legitimate revalidation restores it.
- Admin: can list small and large log metadata sets without downloading chunks; delete one log, a time range, one conversation and all eligible groups; confirm partial deletes and safe retries; check accessibility labels, order and focus.
- Live Firebase Rules must be published separately by the owner only after review. GitHub Actions selects and runs applicable tests when the approved code is eventually pushed; do not execute local builds or tests contrary to repository policy.

## Forum cleanup

Forum cleanup now includes `forumNotices` and `forumBans` as well as the previously reserved forum groups. The administrator may delete individual topic content through **Quản trị diễn đàn** or all forum data through **Quản lý dữ liệu Firebase**. These operations must never delete `admins`, `devices`, `decisions`, `licenseEvents`, or requests with an existing decision. See [Community forum](forum.md) for recipient-only notification rules and account-identity limitations.
