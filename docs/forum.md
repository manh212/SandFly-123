# Community forum

Meridian has a text-only community forum using the existing Firebase Realtime Database and anonymous-auth installation identity. No Firebase SDK, Play Services, FCM, Cloud Functions, or long-running database stream is added.

## Deployment

1. Update both Meridian and Meridian Admin APKs from the same dev release.
2. Publish the complete current `activation/firebase/database.rules.json` in the existing Firebase project's Realtime Database Rules tab. CI checks emulator permissions but does **not** deploy these production rules.
3. Keep Firebase Anonymous Authentication enabled. Do not switch rules to public test mode.
4. Open **Diễn đàn cộng đồng** from Meridian's main settings screen. Set an independent public display name before posting. This name must not be imported from the private device enrollment directory.
5. In Meridian Admin, the owner can use **Quản trị diễn đàn** for moderation and **Quản lý dữ liệu Firebase** for a bulk delete. Moderation actions never grant licensing or support permissions.

## Features

- Five fixed categories: general discussion, questions, Lua sharing, feature suggestions, and usage guides.
- Authenticated users can read the topic list and create topics with a title and body. Lists use bounded REST key queries and are paginated. Older pages can be traversed without keeping a connection open.
- Each topic has paginated replies. A reply references a prior comment, and the reader can open the referenced comment. An explicit mention targets the selected comment author; a normal reply targets the author of the replied comment or the topic author.
- There is a private per-installation notification inbox. Android notifications are checked during the existing support REST polling cycle, about every 15 minutes while the accessibility service is active. Opening the forum's notification list also fetches them on demand. The first background snapshot establishes a baseline. No FCM or continuously connected listener is used.
- The owner can lock/unlock topics, remove posts or topics, deny posting for a UID, and lift that restriction. Banned members can still read public forum data. A topic is locked before its content is removed to prevent new replies during cleanup.
- Deleting a topic may leave old notification references to it. Opening them explains that the topic no longer exists. Bulk cleanup includes notifications and bans.

## Data layout and permissions

- `forumTopics/<id>`: immutable topic title, body, author UID and public name; the owner alone may change `locked` or delete.
- `forumPosts/<topic>/<id>`: immutable comment with its author UID, parent comment ID and optional mention UID; owner alone may delete.
- `forumProfiles/<uid>`: a user-controlled public display name unrelated to private registration details.
- `forumNotices/<uid>/<topic>_<post>`: recipient-only notification metadata, created by the authenticated comment author only if the referenced comment and recipient relationship validate.
- `forumBans/<uid>`: owner-controlled restriction on new topics and replies.
- `forumCategories` and `forumModerators` are legacy reserved cleanup namespaces; the initial five categories are constants in both applications.

There are no new `.read` grants on `requests`, `decisions`, `devices`, `admins`, `licenseEvents` or any `support*` namespace. Existing enrollment, support and license rules remain separate. Firebase rules enforce immutable member content and owner-only deletion even when callers bypass the client UI.

## Resource and security tradeoffs

This is a serverless text forum on Firebase Spark. Anonymous authentication is not a strong persistent person identity: clearing app data can create a new UID, meaning an old ban can be evaded. Stronger anti-spam or durable account bans require a trusted backend and a recoverable account identity. The UI applies length limits; rules validate content size, ownership, categories and references, but there is no server-side rate limiter.

Notifications are best-effort: the comment is published first, and the client then writes private recipient notices. A network failure between these operations can leave the comment without its notification. Notifications are not a replacement for manually refreshing the topic. No logs or private support messages are copied into forum notices. Text messages and forum content add database storage and download usage; watch Firebase Usage, especially on Spark.

## Validation

The Firebase emulator tests must verify topics, profiles, replies, parent references, mentions, notices, bans, locks, deletion and isolation from support and licensing. The owner must still test posting, commenting, Android notifications, focus order, screen-reader labels, long-topic paging, offline recovery and administrative moderation on actual devices after the updated production rules are deployed.
