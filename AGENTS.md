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

- Before implementing any requested feature, clarify its related behavior with
  the owner. Ask one question at a time, wait for each answer, and continue until
  the behavior, scope, edge cases and acceptance criteria are clear. Summarize
  the agreed behavior and wait for explicit approval before implementation.
  Read-only investigation may support these questions. Do not treat silence or
  the initial feature request as approval of an unconfirmed design.
- Run only tests relevant to the approved feature or change, including affected
  shared behavior. Do not run the full suite automatically. Select and report
  the specific CI tests and their scope; do not run tests on the coding machine.
  GitHub Actions selects the tests automatically using
  `scripts/select_ci_tests.py`. Review the run's test-selection summary and
  artifact. Uncertain changes automatically broaden to the affected modules;
  shared build or CI changes may require every test module.
- Write clear code without adding code comments. Keep code identifiers,
  documentation and commit messages in English. Write new or changed interface
  text in Vietnamese; unrelated existing text does not need to be converted.
- Review the source and diff, then commit and push all fixes and features directly
  to `master`, including large changes. Do not create feature branches or pull
  requests.
- Leave repository checks, builds, tests, lint, signing and packaging to GitHub
  Actions. Do not run them on the coding machine. The owner performs device
  testing; review focus order and accessibility through the source.
- Follow CI for the pushed commit. On failure, read the run's `gradle-log-tail`
  artifact, the pull request failure summary when applicable, and job logs or
  annotations as needed before diagnosing. Fix the cause, push again and follow
  CI until it succeeds. If required logs are inaccessible, report that limitation.
- For application changes on `master`, confirm both CI success and successful
  publication of the dev release for the pushed commit before reporting
  completion. Include the CI and dev release links. The owner tests the
  published dev build on a phone.
- Documentation-only changes need source and diff review, then confirmation
  that the updated files are on `master` and CI selected no application work.
  Do not add `[skip ci]`; the selection job skips the application tests, build
  and release automatically when no unpublished application changes remain.
- Follow [docs/ci-testing.md](docs/ci-testing.md) for automatic test selection,
  comparison baselines, broadening rules and coverage limitations.
- Create stable releases and review or synchronize upstream changes only when
  the owner explicitly requests them.
