# Contributing to this fork

This repository is a development fork of [SandFly](https://github.com/aaron-gh/SandFly),
the open-source Android screen reader. The rules below govern all work in this fork.
They were set by the fork's owner; change them by editing this file, not by making
exceptions.

## Ground rules

1. **Language.** Code identifiers, commit messages and documentation remain in
   English. From now on, write new or changed user interface text in Vietnamese;
   an English translation is not required. Existing interface text outside the
   change's scope does not need to be converted.
2. **Comments.** Do not add comments to code. Write clear, self-explanatory code.
3. **Where changes go.** Edit upstream code directly when a feature needs it.
   There is no requirement to isolate this fork's changes into separate files or
   modules. In exchange, upstream syncs may need manual conflict resolution.
4. **Definition of done.** Write and review the code, push it, and monitor GitHub
   Actions for that commit. GitHub Actions runs the repository checks, builds,
   relevant unit tests, lint, signing and packaging. Do not run builds, tests,
   lint or packaging on the coding machine. If CI fails, read the failure output,
   fix the cause and push again until it succeeds. Issues already recorded in
   `lint-baseline.xml` are allowed. For `master`, completion also requires the
   dev release to be published successfully for application changes. Changes
   limited to documentation follow the exception under Test scope. Then report
   the result to the owner.
5. **Branches.** All work, including large reorganizations, is committed and
   pushed directly to `master`. Do not create feature branches or pull requests.
   If CI fails, read the failure output and fix forward on `master` until the
   build and dev publication succeed.
6. **Interface work.** Any change that adds or alters user interface must also
   follow [docs/ux-guidelines.md](docs/ux-guidelines.md).

## Feature clarification and approval

Before implementing a feature requested by the owner, investigate the existing
code as needed to ask informed questions about how the feature should work.
Ask one question at a time and wait for the owner's answer before asking the
next. Clarify the relevant triggers, user actions, inputs, outputs, defaults,
settings, interactions with existing features, stored data, failure behavior,
accessibility and acceptance criteria. Cover what applies to the feature rather
than asking irrelevant questions from a fixed checklist.

Once the answers are clear, summarize the intended behavior, implementation scope
and relevant test coverage for review. Wait for the owner's explicit agreement
before writing or changing implementation code. A feature request starts this
clarification process; it is not approval of details that have not been agreed.
Silence is not agreement. If a later discovery materially changes the agreed
behavior or scope, clarify that change and obtain approval before implementing it.
Routine implementation decisions and CI fixes within the approved behavior do
not require repeated approval.

## Test scope

- Run only tests related to the requested feature or change. Include regression
  coverage for shared behavior affected by the change; leave unrelated tests out.
- Choose the specific test classes, methods or module tasks from the affected
  code and agreed acceptance criteria. Do not run the entire test suite by
  default or use an unfiltered unit-test task merely for convenience.
- Broaden coverage only when a newly identified dependency, failure or shared
  change makes additional tests relevant. Explain the reason and report which
  tests ran and what they do not cover. A focused green run is not a full-suite
  result.
- Keep automated execution in GitHub Actions. Do not run tests, builds, lint or
  packaging on the coding machine. App builds, signing and publication still
  follow the existing delivery rules for application changes.
- Before pushing application changes, ensure the CI invocation selects the
  intended tests. If the workflow still forces a full suite, arrange scoped CI
  execution first; do not silently ignore this policy or skip relevant tests
  just to obtain a green result.
- Documentation-only edits do not require application tests, an APK build or a
  dev release. Review their content, links and diff, push them to `master` and
  verify the published files. Use `[skip ci]` on a documentation-only commit
  when the current workflow would otherwise run unrelated application checks.

## Working agreements

- After the required feature approval, work directly on `master` for every fix
  and feature. Review the code and diff before pushing, then monitor CI and dev
  publication for application changes.
  When a `master` build fails, read its `gradle-log-tail` artifact before
  diagnosing, and fix forward; the dev channel stays paused until `master` is
  green again.
- GitHub Actions runs `scripts/pre-push-check.sh` and
  `scripts/check_settings_key_contract.py` for pushes to `master` and pull
  requests unless CI is skipped for a documentation-only change. These checks
  are not required on the coding machine. Fix failures reported by CI and push
  the correction.
- Reading the failure log is automatic, never optional, on every failed
  build — on a branch or on `master`. Before diagnosing or changing
  anything, read the run's full error output: the automatic failure
  summary comment on the pull request, the failed run's own
  `gradle-log-tail` artifact, and the run's log or annotations when the
  tail does not establish the cause. Never guess at a cause while a log
  exists unread, and never re-run blind. If a needed log is genuinely
  unreachable from where you work, say exactly that in your handoff
  instead of speculating. Never add temporary hooks or workflow steps to
  extract logs; the artifact is the standing route.
- When removing a string resource, delete it from every locale file (all
  `values-*` directories), or the `ExtraTranslation` lint fails. New or changed
  interface strings only need Vietnamese. Keep default resources valid and
  satisfy the Vietnamese coverage check without requiring English wording;
  record missing translations for other languages in `lint-baseline.xml`.
- When counting or auditing settings rows, reconcile the XML with the runtime
  `TalkBackPreferenceFilter`: some rows are hidden at runtime, so XML alone
  gives the wrong count.
- After any scripted bulk edit, inspect the actual diff and confirm it contains
  only the intended change. If a script misfires, rebuild the file from the
  original and re-apply the change with exact-match replacements.
- After a failed or interrupted merge attempt, verify the pull request state
  and the head of `master` before retrying.

## Syncing with upstream

- Review upstream changes and sync only when the owner requests it. Do not
  initiate upstream syncs automatically. When requested, prefer small steps
  rather than one large jump.
- After every sync, verify that `.github/workflows/build.yml` still carries this
  fork's own `SANDFLY_CERT_SHA256` value, and that the Actions secrets
  `SANDFLY_KEYSTORE_BASE64` and `SANDFLY_KEY_PASSWORD` are still set.
- The signing keystore and its password are never committed to this repository.

## Builds and releases

- Pushing application changes to `master` builds the app in GitHub Actions and
  updates the rolling `dev` pre-release. Tagging `v<version>` creates a stable release.
- A successful application task delivers the dev build. Create a stable release
  only when the owner explicitly requests it.
- The dev build number is a cumulative counter and can jump between releases;
  a jump is normal, not a missing build.
- Builds are signed with this fork's own signing key (stored as Actions secrets),
  so published builds update in place on a device.
