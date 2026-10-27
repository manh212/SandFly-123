# Agent instructions

These instructions apply throughout this repository.

Before starting any task, read the current versions of:

1. [CONTRIBUTING.md](CONTRIBUTING.md) for the complete working agreements.
2. [README.md](README.md), especially Development workflow, for project context
   and delivery expectations.
3. [docs/ux-guidelines.md](docs/ux-guidelines.md) for interface and accessibility
   requirements.

Use the repository's current guidance instead of relying on earlier conversations.
`CONTRIBUTING.md` is the source of truth for the working agreements; this file is
the starting point for discovering them.

## Workflow essentials

- Write clear code without adding code comments. Keep code identifiers,
  documentation and commit messages in English. Write new or changed interface
  text in Vietnamese; unrelated existing text does not need to be converted.
- Review the source and diff, then push normal fixes and features directly to
  `master`. Only extremely large work uses a branch and pull request, with the
  owner's approval before creating them and before writing code. Never auto-merge
  a branch.
- Leave repository checks, builds, tests, lint, signing and packaging to GitHub
  Actions. Do not run them on the coding machine. The owner performs device
  testing; review focus order and accessibility through the source.
- Follow CI for the pushed commit. On failure, read the run's `gradle-log-tail`
  artifact, the pull request failure summary when applicable, and job logs or
  annotations as needed before diagnosing. Fix the cause, push again and follow
  CI until it succeeds. If required logs are inaccessible, report that limitation.
- For `master`, confirm both CI success and successful publication of the dev
  release for the pushed commit before reporting completion. Include the CI and
  dev release links. For branch work, share the CI preview APK for owner testing
  and merge approval.
- Create stable releases and review or synchronize upstream changes only when
  the owner explicitly requests them.
