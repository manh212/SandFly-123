import argparse
import json
import os
from pathlib import Path
import re
import subprocess


SOURCE = re.compile(r"^(.*?)/src/([^/]+)/(?:java|kotlin)/(.+)\.(?:java|kt)$")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
IDENTIFIER = re.compile(r"\b[A-Za-z_$][\w$]*\b")
FEATURE_ROOT = "com.google.android.accessibility.talkback."
FEATURES = {
    "actor.gemini": {"actor.gemini"},
    "backup": {"backup"},
    "collapse": {"collapse", "scripting"},
    "dictation": {"dictation"},
    "directtouch": {"directtouch"},
    "granularediting": {"granularediting"},
    "individualfeedback": {"individualfeedback", "soundthemes"},
    "keyboardecho": {"keyboardecho"},
    "labeling": {"labeling"},
    "migration": {"migration"},
    "notifications": {"notifications", "timer"},
    "quicksettings": {"quicksettings"},
    "report": {"report"},
    "scripting": {"scripting", "collapse"},
    "soundthemes": {"soundthemes", "individualfeedback"},
    "stucktouch": {"stucktouch"},
    "timer": {"timer", "notifications"},
    "update": {"update"},
}
PLANNER_FILES = {
    "scripts/select_ci_tests.py",
    "scripts/run_ci_tests.py",
    "scripts/tests/test_select_ci_tests.py",
    ".github/workflows/build.yml",
}


def git(root, *args):
    return subprocess.check_output(["git", *args], cwd=root).decode("utf-8")


def documentation(path):
    return (
        path.startswith(("docs/", "release-notes/", ".github/ISSUE_TEMPLATE/"))
        or ("/" not in path and Path(path).suffix.lower() == ".md")
        or path in {"LICENSE", "NOTICE", ".github/PULL_REQUEST_TEMPLATE.md"}
    )


def module_directories(files):
    return {
        str(Path(path).parent)
        for path in files
        if path.endswith("build.gradle") and path != "build.gradle"
    }


def module_names(files):
    names = {directory: ":" + directory.replace("/", ":") for directory in module_directories(files)}
    for name, directory in re.findall(
        r"project\(['\"](:[^'\"]+)['\"]\)\.projectDir\s*=\s*new File\(['\"]([^'\"]+)['\"]\)",
        files.get("settings.gradle", ""),
    ):
        names[directory] = name
    return names


def module_for(path, modules):
    return next(
        (module for module in sorted(modules, key=len, reverse=True)
         if path.startswith(module + "/")),
        None,
    )


def source_info(path, text):
    match = SOURCE.match(path)
    package = PACKAGE.search(text)
    if not match or not package:
        return None
    source_set = match.group(2)
    if source_set.startswith("androidTest"):
        return None
    return {
        "module": match.group(1),
        "package": package.group(1),
        "class": package.group(1) + "." + Path(path).stem,
        "test_source": source_set.startswith("test"),
        "test": source_set.startswith("test") and bool(re.search(r"@(?:org\.junit\.)?Test\b", text)),
        "tokens": set(IDENTIFIER.findall(text)),
        "symbols": {Path(path).stem} | set(re.findall(
            r"^(?:(?:public|private|internal|abstract|open|final|data|sealed|enum|annotation)\s+)*"
            r"(?:class|interface|object|record)\s+(\w+)", text, re.MULTILINE,
        )),
    }


def feature_for(info):
    if info["module"] != "talkback":
        return None
    package = info["package"]
    for feature in sorted(FEATURES, key=len, reverse=True):
        prefix = FEATURE_ROOT + feature
        if package == prefix or package.startswith(prefix + "."):
            return feature
    return None


def dependent_modules(module, modules, files):
    locations = {name: directory for directory, name in module_names(files).items()}
    affected = {module}
    while True:
        added = {
            directory for directory in modules
            if any(
                locations.get(name) in affected
                for name in re.findall(r"project\(['\"](:[^'\"]+)['\"]\)", files.get(directory + "/build.gradle", ""))
            )
        }
        if added <= affected:
            return affected
        affected |= added


def plan_tests(changed, files, previous=None, force_all=None):
    previous = previous or {}
    modules = module_directories(files) | module_directories(previous)
    names = module_names(files)
    sources = {
        path: info for path, text in files.items()
        if (info := source_info(path, text)) is not None
    }
    tests = {path: info for path, info in sources.items() if info["test"]}
    selected = set()
    whole_modules = set()
    reasons = []
    all_modules = {info["module"] for info in tests.values()}

    def broader_scope(module):
        affected = dependent_modules(module, modules, files)
        return affected if affected & all_modules else all_modules

    relevant = [path for path in changed if not documentation(path)]
    if force_all:
        whole_modules |= all_modules
        reasons.append(force_all)
    for path in relevant:
        module = module_for(path, modules)
        info = sources.get(path) or source_info(path, previous.get(path, ""))
        if path in PLANNER_FILES or module is None:
            whole_modules |= all_modules
            reasons.append(path + ": shared build or CI configuration; all test modules")
        elif info and info["test_source"]:
            if path in tests:
                selected.add(path)
                reasons.append(path + ": changed test class")
            else:
                whole_modules |= broader_scope(module)
                reasons.append(path + ": test helper or removed test; entire module")
        elif info and (feature := feature_for(info)):
            groups = FEATURES[feature]
            group_sources = [
                value for value in sources.values()
                if feature_for(value) in groups and not value["test_source"]
            ]
            symbols = info["symbols"] | set().union(*(value["symbols"] for value in group_sources))
            matches = {
                test_path for test_path, test in tests.items()
                if feature_for(test) in groups or test["tokens"] & symbols
            }
            if matches:
                selected |= matches
                reasons.append(path + ": feature groups " + ", ".join(sorted(groups)) + " and referencing tests")
            else:
                whole_modules |= broader_scope(module)
                reasons.append(path + ": no mapped tests; module and dependents")
        else:
            whole_modules |= broader_scope(module)
            reasons.append(path + ": shared, resource or unclassified change; module and dependents")
    whole_modules &= all_modules
    selected |= {path for path, info in tests.items() if info["module"] in whole_modules}
    commands = []
    for module in sorted({tests[path]["module"] for path in selected}):
        classes = sorted({tests[path]["class"] for path in selected if tests[path]["module"] == module})
        command = ["./gradlew", names[module] + ":testPhoneDebugUnitTest", "--console=plain"]
        if module not in whole_modules:
            for name in classes:
                command.extend(["--tests", name])
        commands.append({"module": module, "scope": "module" if module in whole_modules else "classes", "classes": classes, "argv": command})
    if (relevant or force_all) and not commands:
        raise ValueError("Application changes have no runnable test coverage; test discovery must be updated")
    return {
        "application": bool(relevant or force_all),
        "planner_tests": bool(set(changed) & PLANNER_FILES) or any(path.startswith("scripts/tests/") for path in changed),
        "changed": sorted(changed),
        "reasons": reasons or ["Documentation only; no application tests, build or release"],
        "commands": commands,
        "test_classes": sum(len(command["classes"]) for command in commands),
        "available_test_classes": len(tests),
    }


def comparison_base(root, event, event_name, ref, published_name=None):
    if ref.startswith("refs/tags/v"):
        return None, "Release validation covers all test modules"
    if event_name == "pull_request":
        target = event["pull_request"]["base"]["sha"]
        try:
            return git(root, "merge-base", target, "HEAD").strip(), None
        except subprocess.CalledProcessError:
            return None, "Pull request comparison is unavailable; all test modules"
    published = re.fullmatch(r"Development build \d+ \(([0-9a-f]{7,40})\)", (published_name or "").strip())
    if not published:
        return None, "Published dev metadata is unavailable; all test modules"
    try:
        base = git(root, "rev-parse", published.group(1) + "^{commit}").strip()
        subprocess.run(["git", "merge-base", "--is-ancestor", base, "HEAD"], cwd=root, check=True)
        return base, None
    except subprocess.CalledProcessError:
        return None, "Published dev baseline is unavailable or unrelated; all test modules"


def repository_files(root):
    paths = git(root, "ls-files", "-z").split("\0")
    return {
        path: (root / path).read_text(encoding="utf-8", errors="replace")
        for path in paths if path and (root / path).is_file()
        and (SOURCE.match(path) or path.endswith("build.gradle") or path == "settings.gradle")
    }


def create_plan(root, event, event_name, ref, published_name=None):
    base, force_all = comparison_base(root, event, event_name, ref, published_name)
    previous = {}
    if base:
        changed = [path for path in git(root, "diff", "--name-only", "--no-renames", "-z", base, "HEAD").split("\0") if path]
        for path in changed:
            if SOURCE.match(path):
                try:
                    previous[path] = git(root, "show", base + ":" + path)
                except subprocess.CalledProcessError:
                    pass
        if not changed and event_name == "workflow_dispatch":
            force_all = "Manual validation without a narrower change set; all test modules"
    else:
        changed = [path for path in git(root, "ls-files", "-z").split("\0") if path]
    plan = plan_tests(changed, repository_files(root), previous, force_all)
    plan["base"] = base
    plan["head"] = git(root, "rev-parse", "HEAD").strip()
    return plan


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="test-plan.json")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    event = json.loads(Path(event_path).read_text()) if event_path else {}
    published_path = os.environ.get("SANDFLY_DEV_RELEASE_NAME_FILE")
    published_name = Path(published_path).read_text() if published_path and Path(published_path).is_file() else None
    plan = create_plan(root, event, os.environ.get("GITHUB_EVENT_NAME", "workflow_dispatch"), os.environ.get("GITHUB_REF", ""), published_name)
    Path(args.output).write_text(json.dumps(plan, indent=2) + "\n")
    print(json.dumps(plan, indent=2))
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a") as handle:
            handle.write("application=" + str(plan["application"]).lower() + "\n")
            handle.write("planner_tests=" + str(plan["planner_tests"]).lower() + "\n")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary, "a") as handle:
            handle.write("## Test selection\n\n")
            handle.write(f"Selected {plan['test_classes']} of {plan['available_test_classes']} discovered test classes.\n\n")
            for reason in plan["reasons"]:
                handle.write("- " + reason + "\n")
            for command in plan["commands"]:
                handle.write("\n### " + command["module"] + " (" + command["scope"] + ")\n\n")
                for name in command["classes"]:
                    handle.write("- `" + name + "`\n")


if __name__ == "__main__":
    main()
