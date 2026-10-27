# Automatic CI test selection

GitHub Actions runs a lightweight `plan` job before setting up Java or building
Android artifacts. `scripts/select_ci_tests.py` produces `test-plan.json`, the
`test-plan` artifact and the run's Test selection summary. The summary includes
the selected classes, module scopes and reasons for widening coverage.

## Comparison baseline

For a push to `master`, compare the checkout with the commit in the published
dev release's name. Read the release metadata rather than relying only on the
movable `dev` tag: publication moves that tag before uploading the APK and
updating the release name. If publication fails partway through, the next run
must still include the unpublished changes. Several commits pushed together,
failed builds and later documentation commits are therefore covered by the
same accumulated diff until publication succeeds.

For a pull request, use the merge base with its target commit. Stable release
tags require every test module. Missing or unrelated release metadata broadens
coverage to every test module. A manual run with no narrower change set also
validates every test module.

## Selection rules

| Change | Selected application tests |
| --- | --- |
| Root Markdown, `docs/`, release notes and issue templates only | None; skip the application build and publication too. |
| An existing unit test class | That class. |
| A test helper or removed test | Its module and dependent modules. |
| Source in a recognized feature package | Tests in its feature groups plus tests elsewhere that reference symbols from those groups. |
| Shared code, resources, manifests, native code or an unclassified feature | The owning module and modules that depend on it. |
| Shared build files, CI configuration or test-selection scripts | Every test module. |
| A module without discoverable test coverage or known tested consumers | Broaden to every test module. |

Recognized packages currently include AI services, backup, collapse, dictation,
direct touch, granular editing, individual feedback, keyboard echo, labels,
migration, notifications, quick settings, diagnostics, scripts, sound themes,
stuck-touch handling, timers and updates. Timer and notification tests run
together, as do scripts and collapse, and sound themes and individual feedback.
Unknown packages use the broader module scope until their test boundaries are
explicitly added to the selector.

The selector reads both current and removed source files. Renames contribute
both the old and new paths. Gradle project dependencies determine which modules
consume a changed library. Source selection includes both `test` and `testPhone`
unit-test source sets. Device tests remain the owner's responsibility.

## Execution and reporting

`scripts/run_ci_tests.py` checks that the downloaded plan belongs to the current
commit, then runs the planned module tasks with class filters where applicable.
Failures stop execution and block publication. Application changes cannot pass
with an empty test plan. Gradle's failure when a class filter matches no tests
is retained.

Application changes still run repository checks, the settings key contract,
APK assembly, signature verification and lint before dev publication. The
selector's own Python tests run in CI when its scripts or workflow change.
No builds, tests, lint or packaging run on the coding machine.

Documentation changes need no `[skip ci]` marker. The plan job confirms their
scope and skips application work automatically. A documentation commit after
an unpublished application change still validates and publishes that pending
application change.

## Limits and maintenance

This is a conservative package and source-reference heuristic, not a complete
runtime dependency analysis. Reflection, script dispatch and indirect behavior
can require additional integration groups. When introducing such a dependency,
extend the feature groups and their CI tests, or retain the module fallback.
Shared infrastructure and resource changes deliberately use wider coverage.

The class counts in the plan describe discovered classes, not executed test
methods. Use the actual test logs and reports when reporting test results.
A focused green run verifies its selected coverage; it does not claim the
unselected tests passed. Test compilation and dependency setup can still involve
more source files than the test classes executed, and build and lint time remain.

Selection failures and application failures retain the standing
`gradle-log-tail` artifact. Read it before diagnosing or fixing a failed run.
