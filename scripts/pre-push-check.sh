#!/usr/bin/env bash
# Pre-push checks for this fork. Run before pushing a large change; the script
# exits non-zero if any check finds a problem. The XML analysis uses python3,
# which is available on the dev machines and on GitHub's runners.
set -u
cd "$(dirname "$0")/.." || exit 2
fail=0

# (a) Vietnamese coverage, compared by string NAME: names present in a default
# values/strings.xml (and not marked translatable="false") must also exist in
# the matching values-vi/strings.xml.
echo "== (a) Vietnamese string coverage (by name) =="
python3 - <<'PYEOF'
import glob, sys
import xml.etree.ElementTree as ET

def names(path):
    result = {}
    for el in ET.parse(path).getroot():
        if el.tag in ('string', 'string-array', 'plurals') and el.get('name'):
            result[el.get('name')] = el.get('translatable') != 'false'
    return result

problems = 0
for default in sorted(glob.glob('**/values/strings.xml', recursive=True)):
    if '/build/' in default or default.startswith('.git'):
        continue
    vi = default.replace('/values/', '/values-vi/')
    try:
        vi_names = names(vi)
    except FileNotFoundError:
        print(f'  {default}: no Vietnamese file {vi}')
        problems += 1
        continue
    for name, translatable in sorted(names(default).items()):
        if translatable and name not in vi_names:
            print(f'  {default}: "{name}" has no Vietnamese translation')
            problems += 1
print(f'  {problems} problem(s)')
sys.exit(1 if problems else 0)
PYEOF
if [ $? -eq 0 ]; then echo "(a) PASS"; else echo "(a) FAIL"; fail=1; fi

# (b) Dangling @string references: a @string/... referenced from a module's res
# must be defined in the values of that module or of one of its project
# dependencies (resources merge across library dependencies). A few names are
# provided by external AAR libraries and are listed explicitly.
echo "== (b) Dangling @string references =="
python3 - <<'PYEOF'
import glob, os, re, sys
import xml.etree.ElementTree as ET

EXTERNAL = {'app_id', 'appbar_scrolling_view_behavior',
            'searchview_clear_text_content_description'}

settings = open('settings.gradle', encoding='utf-8').read()
dirs = {}
for m in re.finditer(r"include\s+\(?\s*['\"]([^'\"]+)['\"]", settings):
    name = m.group(1).lstrip(':')
    dirs[name] = name
for m in re.finditer(r"project\(\s*['\"]([^'\"]+)['\"]\s*\)\.projectDir\s*=\s*new File\('([^']+)'\)", settings):
    dirs[m.group(1).lstrip(':')] = m.group(2)

def project_refs(gradle_path):
    try:
        text = open(gradle_path, encoding='utf-8').read()
    except FileNotFoundError:
        return set()
    return {m.group(1).lstrip(':') for m in
            re.finditer(r"project\(\s*['\"]([^'\"]+)['\"]", text)}

def defined_names(module_dir):
    result = set()
    for xml in glob.glob(os.path.join(module_dir, 'src', '**', 'res', 'values*', '*.xml'), recursive=True):
        try:
            root = ET.parse(xml).getroot()
        except Exception:
            continue
        for el in root.iter():
            if el.tag in ('string', 'string-array', 'plurals') and el.get('name'):
                result.add(el.get('name'))
    return result

ref_re = re.compile(r'@string/([A-Za-z0-9_]+)')
problems = 0
for name, module_dir in sorted(dirs.items()):
    deps, queue = set(), [name]
    while queue:
        current = queue.pop()
        for dep in project_refs(os.path.join(dirs.get(current, current), 'build.gradle')):
            if dep in dirs and dep not in deps:
                deps.add(dep)
                queue.append(dep)
    defined = set()
    for dep in [name] + sorted(deps):
        defined |= defined_names(dirs[dep])
    refs = {}
    for xml in glob.glob(os.path.join(module_dir, 'src', '**', 'res', '**', '*.xml'), recursive=True):
        try:
            text = open(xml, encoding='utf-8').read()
        except Exception:
            continue
        for m in ref_re.finditer(text):
            refs.setdefault(m.group(1), xml)
    for ref in sorted(refs):
        if ref not in defined and ref not in EXTERNAL:
            print(f'  {name}: @string/{ref} referenced from {refs[ref]} is not defined')
            problems += 1
print(f'  {problems} problem(s)')
sys.exit(1 if problems else 0)
PYEOF
if [ $? -eq 0 ]; then echo "(b) PASS"; else echo "(b) FAIL"; fail=1; fi

# (c) Gradle consistency: every project(':...') referenced by a *.gradle file
# must be included in settings.gradle.
echo "== (c) Gradle project consistency =="
python3 - <<'PYEOF'
import glob, re, sys

settings = open('settings.gradle', encoding='utf-8').read()
includes = {m.group(1).lstrip(':') for m in
            re.finditer(r"include\s+\(?\s*['\"]([^'\"]+)['\"]", settings)}
problems = 0
for gradle in sorted(glob.glob('**/*.gradle', recursive=True)):
    if '/build/' in gradle:
        continue
    for m in re.finditer(r"project\(\s*['\"]([^'\"]+)['\"]",
                         open(gradle, encoding='utf-8').read()):
        if m.group(1).lstrip(':') not in includes:
            print(f'  {gradle}: project(\'{m.group(1)}\') is not included in settings.gradle')
            problems += 1
print(f'  {problems} problem(s)')
sys.exit(1 if problems else 0)
PYEOF
if [ $? -eq 0 ]; then echo "(c) PASS"; else echo "(c) FAIL"; fail=1; fi

# (d) Removal leftovers: no temporary CI reporting in the workflow, no braille
# tree, and no src/wear directory anywhere.
echo "== (d) No temporary reporting, braille tree or wear sources =="
problems=0
if grep -q 'TEMP:' .github/workflows/build.yml; then
    echo "  .github/workflows/build.yml still contains a 'TEMP:' step"
    problems=$((problems + 1))
fi
if [ -e braille ]; then
    echo "  the braille/ tree still exists"
    problems=$((problems + 1))
fi
wear_dirs=$(find . -path ./.git -prune -o -type d -path '*/src/wear' -print)
if [ -n "$wear_dirs" ]; then
    echo "  wear source directories remain:"
    echo "$wear_dirs" | sed 's/^/    /'
    problems=$((problems + 1))
fi
echo "  $problems problem(s)"
if [ "$problems" -eq 0 ]; then echo "(d) PASS"; else echo "(d) FAIL"; fail=1; fi

if [ "$fail" -eq 0 ]; then
    echo "ALL CHECKS PASSED"
else
    echo "CHECKS FAILED"
fi
exit "$fail"
