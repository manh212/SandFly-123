#!/usr/bin/env python3
"""Settings key contract check.

Walks the settings tree from the main screen (talkback/src/main/res/xml/preferences.xml),
following android:fragment entries into each target screen's XML, and collects every
preference key reachable from the main screen. The collected set is compared with
scripts/settings_key_contract_expected.txt:

  * a key in the expected list but no longer reachable fails the check -- this is how a
    settings item silently dropped by an upstream sync or a restructure gets caught;
  * a reachable key missing from the expected list is only reported, so newly added
    items stay visible; add them to the expected list in the same change.

Screens built entirely in code contribute only their entry key (their inner items are
not declared in XML). Fragment-to-XML mapping is derived by scanning fragment sources
for R.xml references, so it tracks code moves without a hand-maintained table.

Usage:
  python3 scripts/check_settings_key_contract.py            # verify
  python3 scripts/check_settings_key_contract.py --generate # rewrite the expected list
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXPECTED_PATH = os.path.join(ROOT, "scripts", "settings_key_contract_expected.txt")
MAIN_SCREEN = os.path.join(ROOT, "talkback", "src", "main", "res", "xml", "preferences.xml")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
SOURCE_DIRS = [
    os.path.join(ROOT, "talkback", "src", "main", "java"),
    os.path.join(ROOT, "utils", "src", "main", "java"),
]
RES_DIRS = [
    os.path.join(ROOT, "talkback", "src", "main", "res"),
    os.path.join(ROOT, "utils", "src", "main", "res"),
]


def load_string_values():
    values = {}
    for res_dir in RES_DIRS:
        values_dir = os.path.join(res_dir, "values")
        if not os.path.isdir(values_dir):
            continue
        for name in sorted(os.listdir(values_dir)):
            if not name.endswith(".xml"):
                continue
            try:
                root = ET.parse(os.path.join(values_dir, name)).getroot()
            except ET.ParseError:
                continue
            for element in root:
                if element.tag == "string" and element.get("name"):
                    values.setdefault(element.get("name"), "".join(element.itertext()))
    return values


def build_fragment_xml_map():
    """Maps fully qualified fragment class names to their preference XML file name."""
    mapping = {}
    for source_dir in SOURCE_DIRS:
        for dirpath, _dirnames, filenames in os.walk(source_dir):
            for filename in filenames:
                if not (filename.endswith(".java") or filename.endswith(".kt")):
                    continue
                path = os.path.join(dirpath, filename)
                with open(path, encoding="utf-8", errors="ignore") as handle:
                    text = handle.read()
                package = re.search(r"^\s*package\s+([\w.]+)", text, re.MULTILINE)
                if not package:
                    continue
                declarations = list(re.finditer(
                    r"^(?:(?:public|private|internal|abstract|open|final|data|sealed)\s+)*"
                    r"(?:class|object)\s+(\w+)",
                    text,
                    re.MULTILINE,
                ))
                for index, declaration in enumerate(declarations):
                    end = declarations[index + 1].start() if index + 1 < len(declarations) else len(text)
                    body = text[declaration.start():end]
                    xml_ref = re.search(r"R\.xml\.(\w+)", body)
                    if xml_ref:
                        mapping.setdefault(
                            package.group(1) + "." + declaration.group(1),
                            xml_ref.group(1) + ".xml",
                        )
    return mapping


def find_xml_file(xml_name):
    for res_dir in RES_DIRS:
        for variant in sorted(os.listdir(res_dir)):
            if variant == "xml" or variant.startswith("xml-"):
                candidate = os.path.join(res_dir, variant, xml_name)
                if os.path.isfile(candidate):
                    return candidate
    return None


def collect_keys(xml_path, string_values, fragment_xml_map, visited, keys):
    if xml_path in visited:
        return
    visited.add(xml_path)
    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError as error:
        print(f"warning: cannot parse {xml_path}: {error}", file=sys.stderr)
        return
    for element in root.iter():
        raw_key = element.get(ANDROID_NS + "key")
        if raw_key:
            if raw_key.startswith("@string/"):
                keys.add(string_values.get(raw_key[len("@string/"):], raw_key).strip())
            else:
                keys.add(raw_key.strip())
        fragment = element.get(ANDROID_NS + "fragment")
        if fragment and fragment in fragment_xml_map:
            target = find_xml_file(fragment_xml_map[fragment])
            if target:
                collect_keys(target, string_values, fragment_xml_map, visited, keys)


def reachable_keys():
    string_values = load_string_values()
    fragment_xml_map = build_fragment_xml_map()
    keys = set()
    collect_keys(MAIN_SCREEN, string_values, fragment_xml_map, set(), keys)
    return keys


# Keys of settings items the fork owner ordered deleted outright: the entry keys of
# the three removed features and the keys of the rows inside their screens, so a sync
# that brings back a single deleted row fails the check too, not just the whole screen.
FORBIDDEN_KEYS = {
    "pref_category_manage_focus_indicator_key",
    "pref_thick_border_key",
    "pref_border_color_category_key",
    "pref_border_color_key",
    "pref_speech_control_screen",
    "pref_speech_control",
    "pref_show_exit_watermark",
}


def main():
    keys = reachable_keys()
    resurrected = sorted(FORBIDDEN_KEYS & keys)
    if resurrected:
        for key in resurrected:
            print(f"DELETED item reachable again: {key}")
        print("settings key contract FAILED: a deleted settings item came back")
        return 1
    if "--generate" in sys.argv:
        with open(EXPECTED_PATH, "w", encoding="utf-8") as handle:
            handle.write(
                "# Preference keys reachable from the main settings screen.\n"
                "# Regenerate with: python3 scripts/check_settings_key_contract.py"
                " --generate\n"
            )
            for key in sorted(keys):
                handle.write(key + "\n")
        print(f"wrote {len(keys)} keys to {EXPECTED_PATH}")
        return 0
    expected = set()
    with open(EXPECTED_PATH, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line and not line.startswith("#"):
                expected.add(line)
    missing = sorted(expected - keys)
    extra = sorted(keys - expected)
    for key in extra:
        print(f"note: reachable but not in the contract list: {key}")
    if missing:
        for key in missing:
            print(f"MISSING from the settings tree: {key}")
        print(f"settings key contract FAILED: {len(missing)} key(s) unreachable")
        return 1
    print(f"settings key contract OK: {len(expected)} keys reachable from the main screen")
    return 0


if __name__ == "__main__":
    sys.exit(main())
