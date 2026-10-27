import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[1]


def load_script(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


selector = load_script("select_ci_tests")
runner = load_script("run_ci_tests")
PREFIX = "talkback/src/main/java/com/google/android/accessibility/talkback/"
TEST_PREFIX = "talkback/src/test/java/com/google/android/accessibility/talkback/"


def source(package, name, test=False, references=""):
    body = "@Test fun works() {}" if test else ""
    return f"package com.google.android.accessibility.{package}\nclass {name} {{ {body} }}\n{references}\n"


def fixture():
    return {
        "settings.gradle": "include ':talkback'\ninclude ':utils'\ninclude ':whisper'\n",
        "talkback/build.gradle": "implementation project(':utils')\nimplementation project(':whisper')",
        "utils/build.gradle": "",
        "whisper/build.gradle": "",
        PREFIX + "directtouch/DirectTouchPolicy.kt": source("talkback.directtouch", "DirectTouchPolicy"),
        PREFIX + "directtouch/DirectTouchController.kt": source("talkback.directtouch", "DirectTouchController"),
        TEST_PREFIX + "directtouch/DirectTouchPolicyTest.kt": source("talkback.directtouch", "DirectTouchPolicyTest", True),
        TEST_PREFIX + "directtouch/DirectTouchControllerTest.kt": source("talkback.directtouch", "DirectTouchControllerTest", True),
        TEST_PREFIX + "actor/IntegrationTest.kt": source("talkback.actor", "IntegrationTest", True, "val policy = DirectTouchPolicy()"),
        TEST_PREFIX + "timer/TimerTest.kt": source("talkback.timer", "TimerTest", True),
        TEST_PREFIX + "notifications/NotificationsTest.kt": source("talkback.notifications", "NotificationsTest", True),
        TEST_PREFIX + "update/UpdaterTest.kt": source("talkback.update", "UpdaterTest", True),
        "utils/src/test/java/com/google/android/accessibility/utils/UtilityTest.kt": source("utils", "UtilityTest", True),
    }


class SelectionTests(unittest.TestCase):
    def setUp(self):
        self.files = fixture()

    def plan(self, *changed, previous=None):
        return selector.plan_tests(list(changed), self.files, previous)

    def classes(self, plan):
        return {name for command in plan["commands"] for name in command["classes"]}

    def test_documents_skip_application_validation(self):
        plan = self.plan("README.md", "AGENTS.md", "docs/ux-guidelines.md", "release-notes/1.md")
        self.assertFalse(plan["application"])
        self.assertEqual([], plan["commands"])

    def test_markdown_assets_are_not_mistaken_for_documentation(self):
        plan = self.plan("talkback/src/main/assets/prompt.md")
        self.assertTrue(plan["application"])
        self.assertEqual(["talkback"], [command["module"] for command in plan["commands"]])

    def test_feature_selects_group_and_external_referencing_tests(self):
        plan = self.plan(PREFIX + "directtouch/DirectTouchPolicy.kt")
        self.assertEqual(3, plan["test_classes"])
        self.assertTrue(any(name.endswith("IntegrationTest") for name in self.classes(plan)))
        self.assertFalse(any(name.endswith("UpdaterTest") for name in self.classes(plan)))
        self.assertEqual("classes", plan["commands"][0]["scope"])
        self.assertEqual(3, plan["commands"][0]["argv"].count("--tests"))

    def test_changed_tests_are_selected_without_other_features(self):
        plan = self.plan(TEST_PREFIX + "update/UpdaterTest.kt")
        self.assertEqual({"com.google.android.accessibility.talkback.update.UpdaterTest"}, self.classes(plan))

    def test_changed_test_helper_broadens_to_its_module(self):
        path = TEST_PREFIX + "directtouch/FakePreferences.kt"
        self.files[path] = source("talkback.directtouch", "FakePreferences")
        plan = self.plan(path)
        self.assertEqual("module", plan["commands"][0]["scope"])
        self.assertNotIn("--tests", plan["commands"][0]["argv"])

    def test_resources_broaden_only_to_the_owning_module(self):
        plan = self.plan("talkback/src/main/res/xml/preferences.xml")
        self.assertEqual(["talkback"], [command["module"] for command in plan["commands"]])
        self.assertEqual("module", plan["commands"][0]["scope"])

    def test_shared_utility_changes_include_dependent_modules(self):
        plan = self.plan("utils/src/main/java/com/google/android/accessibility/utils/Shared.java")
        self.assertEqual({"talkback", "utils"}, {command["module"] for command in plan["commands"]})
        self.assertTrue(all(command["scope"] == "module" for command in plan["commands"]))

    def test_native_changes_include_app_consumers(self):
        plan = self.plan("whisper/src/main/cpp/whisper_jni.cpp")
        self.assertEqual(["talkback"], [command["module"] for command in plan["commands"]])

    def test_shared_build_and_ci_changes_cover_all_modules(self):
        for path in ["shared.gradle", ".github/workflows/build.yml", "scripts/select_ci_tests.py"]:
            with self.subTest(path=path):
                plan = self.plan(path)
                self.assertEqual({"talkback", "utils"}, {command["module"] for command in plan["commands"]})

    def test_unknown_feature_broadens_to_module(self):
        path = PREFIX + "newfeature/NewFeature.kt"
        self.files[path] = source("talkback.newfeature", "NewFeature")
        plan = self.plan(path)
        self.assertEqual("module", plan["commands"][0]["scope"])

    def test_known_feature_without_tests_broadens_to_module(self):
        path = PREFIX + "backup/NewBackup.kt"
        self.files[path] = source("talkback.backup", "NewBackup")
        plan = self.plan(path)
        self.assertEqual("module", plan["commands"][0]["scope"])

    def test_deleted_source_uses_previous_package_and_symbols(self):
        path = PREFIX + "directtouch/DeletedPolicy.kt"
        old = source("talkback.directtouch", "DeletedPolicy")
        self.files[TEST_PREFIX + "actor/OldPolicyTest.kt"] = source("talkback.actor", "OldPolicyTest", True, "DeletedPolicy")
        plan = self.plan(path, previous={path: old})
        self.assertEqual(4, plan["test_classes"])

    def test_related_timer_and_notification_groups_run_together(self):
        path = PREFIX + "timer/Timer.kt"
        self.files[path] = source("talkback.timer", "Timer")
        plan = self.plan(path)
        self.assertEqual(2, plan["test_classes"])

    def test_multiple_changes_union_the_required_tests(self):
        plan = self.plan(PREFIX + "directtouch/DirectTouchPolicy.kt", TEST_PREFIX + "update/UpdaterTest.kt", "README.md")
        self.assertEqual(4, plan["test_classes"])

    def test_missing_baseline_and_release_validation_cannot_select_nothing(self):
        plan = selector.plan_tests([], self.files, force_all="Baseline unavailable")
        self.assertTrue(plan["application"])
        self.assertEqual(plan["available_test_classes"], plan["test_classes"])

    def test_no_discovered_tests_fails_instead_of_false_green(self):
        with self.assertRaises(ValueError):
            selector.plan_tests(["build.gradle"], {"talkback/build.gradle": ""})

    def test_module_without_known_consumers_falls_back_to_all_tests(self):
        self.files["unmapped/build.gradle"] = ""
        plan = self.plan("unmapped/src/main/cpp/new.cpp")
        self.assertEqual({"talkback", "utils"}, {command["module"] for command in plan["commands"]})

    def test_unknown_module_in_a_mixed_change_still_broadens(self):
        self.files["unmapped/build.gradle"] = ""
        plan = self.plan("unmapped/src/main/cpp/new.cpp", PREFIX + "directtouch/DirectTouchPolicy.kt")
        self.assertEqual({"talkback", "utils"}, {command["module"] for command in plan["commands"]})

    def test_custom_module_locations_follow_dependency_names(self):
        self.files["settings.gradle"] += "project(':materialflags').projectDir = new File('material/flags')"
        self.files["material/flags/build.gradle"] = ""
        self.files["talkback/build.gradle"] += "\nimplementation project(':materialflags')"
        plan = self.plan("material/flags/src/main/res/values/strings.xml")
        self.assertEqual(["talkback"], [command["module"] for command in plan["commands"]])
        self.files["material/flags/src/test/java/example/FlagTest.kt"] = source("flags", "FlagTest", True)
        plan = self.plan("material/flags/src/main/res/values/strings.xml")
        command = next(item for item in plan["commands"] if item["module"] == "material/flags")
        self.assertIn(":materialflags:testPhoneDebugUnitTest", command["argv"])


class BaselineTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "--initial-branch=master")
        self.git("config", "user.name", "CI test")
        self.git("config", "user.email", "ci@example.invalid")
        for path, text in fixture().items():
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text)
        self.commit("Baseline")
        self.base = self.git("rev-parse", "HEAD").strip()
        self.git("tag", "dev")
        self.published_name = "Development build 1 (" + self.base + ")"

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, text=True)

    def commit(self, message):
        self.git("add", ".")
        self.git("commit", "-m", message)

    def test_push_includes_unpublished_changes_before_latest_commit(self):
        path = PREFIX + "directtouch/DirectTouchPolicy.kt"
        target = self.root / path
        target.write_text(target.read_text() + "\nval updated = true\n")
        self.commit("Unpublished code change")
        (self.root / "README.md").write_text("Documentation after that change")
        self.commit("Documentation")
        plan = selector.create_plan(self.root, {}, "push", "refs/heads/master", self.published_name)
        self.assertEqual(self.base, plan["base"])
        self.assertTrue(plan["application"])
        self.assertIn(path, plan["changed"])

    def test_documents_after_published_baseline_skip_build(self):
        (self.root / "README.md").write_text("Documentation")
        self.commit("Documentation")
        self.assertFalse(selector.create_plan(self.root, {}, "push", "refs/heads/master", self.published_name)["application"])

    def test_missing_release_metadata_broadens_coverage(self):
        plan = selector.create_plan(self.root, {}, "push", "refs/heads/master")
        self.assertTrue(all(command["scope"] == "module" for command in plan["commands"]))

    def test_pull_request_uses_merge_base(self):
        event = {"pull_request": {"base": {"sha": self.base}}}
        base, reason = selector.comparison_base(self.root, event, "pull_request", "refs/pull/1/merge", self.published_name)
        self.assertEqual(self.base, base)
        self.assertIsNone(reason)

    def test_stable_tag_covers_all_modules(self):
        base, reason = selector.comparison_base(self.root, {}, "push", "refs/tags/v1.2", self.published_name)
        self.assertIsNone(base)
        self.assertIn("Release", reason)

    def test_moved_tag_does_not_hide_unpublished_changes(self):
        path = PREFIX + "directtouch/DirectTouchPolicy.kt"
        target = self.root / path
        target.write_text(target.read_text() + "\nval updated = true\n")
        self.commit("Code built but publication failed")
        self.git("tag", "-f", "dev")
        plan = selector.create_plan(self.root, {}, "push", "refs/heads/master", self.published_name)
        self.assertEqual(self.base, plan["base"])
        self.assertIn(path, plan["changed"])

    def test_renamed_sources_preserve_old_and_new_paths(self):
        old = PREFIX + "directtouch/DirectTouchPolicy.kt"
        new = PREFIX + "directtouch/RenamedPolicy.kt"
        self.git("mv", old, new)
        self.commit("Rename policy")
        plan = selector.create_plan(self.root, {}, "push", "refs/heads/master", self.published_name)
        self.assertIn(old, plan["changed"])
        self.assertIn(new, plan["changed"])


class RunnerTests(unittest.TestCase):
    def test_rejects_empty_application_plan(self):
        with patch.object(runner.subprocess, "check_output", return_value="abc\n"):
            with self.assertRaises(ValueError):
                runner.run_plan({"head": "abc", "application": True, "commands": []}, Path("."))

    def test_stops_at_first_failed_gradle_command(self):
        plan = {"head": "abc", "application": True, "commands": [
            {"module": "talkback", "scope": "classes", "argv": ["./gradlew", ":talkback:testPhoneDebugUnitTest", "--tests", "example.Test"]},
            {"module": "utils", "scope": "module", "argv": ["./gradlew", ":utils:testPhoneDebugUnitTest"]},
        ]}
        with patch.object(runner.subprocess, "check_output", return_value="abc\n"), patch.object(
            runner.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "gradle")
        ) as execute:
            with self.assertRaises(subprocess.CalledProcessError):
                runner.run_plan(plan, Path("."))
            self.assertEqual(1, execute.call_count)
            self.assertTrue(execute.call_args.kwargs["check"])

    def test_rejects_plan_from_other_commit(self):
        with patch.object(runner.subprocess, "check_output", return_value="other\n"), patch.object(runner.subprocess, "run") as execute:
            with self.assertRaises(ValueError):
                runner.run_plan({"head": "old", "application": True, "commands": []}, Path("."))
            execute.assert_not_called()


class RepositorySelectionTests(unittest.TestCase):
    def test_existing_direct_touch_feature_does_not_select_the_entire_repository(self):
        files = selector.repository_files(SCRIPTS.parent)
        plan = selector.plan_tests([PREFIX + "directtouch/DirectTouchPolicy.kt"], files)
        self.assertGreater(plan["test_classes"], 0)
        self.assertLess(plan["test_classes"], plan["available_test_classes"])
        self.assertTrue(any(name.endswith("DirectTouchPolicyTest") for item in plan["commands"] for name in item["classes"]))
        print(f"Direct touch example: {plan['test_classes']} / {plan['available_test_classes']} test classes", flush=True)


if __name__ == "__main__":
    unittest.main()
