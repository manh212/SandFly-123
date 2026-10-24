# Contributing to this fork

This repository is a development fork of [SandFly](https://github.com/aaron-gh/SandFly),
the open-source Android screen reader. The rules below govern all work in this fork.
They were set by the fork's owner; change them by editing this file, not by making
exceptions.

## Ground rules

1. **Language.** Everything in this repository is in English — code, comments,
   commit messages and documentation — matching upstream.
2. **Comments.** Keep comments in code to an absolute minimum. Code must be clear
   enough to read on its own. Add a comment only when it is truly unavoidable.
3. **Where changes go.** Edit upstream code directly when a feature needs it.
   There is no requirement to isolate this fork's changes into separate files or
   modules. In exchange, upstream syncs may need manual conflict resolution.
4. **Definition of done.** A change is done when the build succeeds and it
   introduces no new lint errors. Issues already recorded in `lint-baseline.xml`
   are allowed.
5. **Branches.** All feature work — fixes and features of every size — goes
   through a branch and a pull request, created at the very start of the
   work, before any code is written, with the owner approving the creation
   up front. Only extremely large work — replacing or removing an entire
   subsystem, completely changing an architecture, or adding a major new
   subsystem — is never auto-merged; everything else merges as soon as the
   build is green. Direct pushes to `master` are only for non-feature work:
   documentation and policy edits, and emergency reverts that restore a
   green `master`.
6. **Interface work.** Any change that adds or alters user interface must also
   follow [docs/ux-guidelines.md](docs/ux-guidelines.md).

## Working agreements

- Every fix and feature, whatever its size, goes on a branch with a pull
  request. Create the branch and its pull request at the very start of the
  work, before any code is written, and the owner approves the creation at
  that point — never after the code is done. A failed pull request build
  posts a readable failure summary comment naming the failing tests, while
  a failed `master` build leaves only artifacts, so the branch is also the
  diagnostic route.
- Direct pushes to `master` are only for non-feature work: documentation
  and policy edits (this file included), and emergency reverts that
  restore a green `master`.
- One feature gets exactly one branch. Keep fixing on that same branch until
  it is accepted; never open a second branch for the same feature.
- Merging follows the size classification. Extremely large work — replacing
  or removing an entire subsystem, completely changing an architecture, or
  adding a major new subsystem — is never auto-merged: when the branch
  build is green, share the preview APK (the build job's `apks` artifact)
  with the owner, who tests it on a device and approves the merge. Every
  other branch merges as soon as its build is green, without asking. On a
  borderline case, the owner is the final judge of the classification.
- Before pushing a large change, run `scripts/pre-push-check.sh` and fix what
  it reports.
- When a pull request build fails, read the automatic failure summary comment
  on the pull request before diagnosing further.
- When a build on `master` fails, read the Gradle log tail from the failed
  run's own `gradle-log-tail` artifact before diagnosing further. Never add
  temporary hooks or workflow steps to extract logs; the artifact is the
  standing route.
- When removing a string resource, delete it from every locale file (all
  `values-*` directories), or the `ExtraTranslation` lint fails. New strings
  only need English and Vietnamese; record the other languages in
  `lint-baseline.xml`.
- When counting or auditing settings rows, reconcile the XML with the runtime
  `TalkBackPreferenceFilter`: some rows are hidden at runtime, so XML alone
  gives the wrong count.
- After any scripted bulk edit, inspect the actual diff and confirm it contains
  only the intended change. If a script misfires, rebuild the file from the
  original and re-apply the change with exact-match replacements.
- After a failed or interrupted merge attempt, verify the pull request state
  and the head of `master` before retrying.

## Syncing with upstream

- Sync upstream regularly, in small steps rather than one large jump.
- After every sync, verify that `.github/workflows/build.yml` still carries this
  fork's own `SANDFLY_CERT_SHA256` value, and that the Actions secrets
  `SANDFLY_KEYSTORE_BASE64` and `SANDFLY_KEY_PASSWORD` are still set.
- The signing keystore and its password are never committed to this repository.

## Builds and releases

- Pushing to `master` builds the app in GitHub Actions and updates the rolling
  `dev` pre-release. Tagging `v<version>` creates a stable release.
- The dev build number is a cumulative counter and can jump between releases;
  a jump is normal, not a missing build.
- Builds are signed with this fork's own signing key (stored as Actions secrets),
  so published builds update in place on a device.
