import json
from pathlib import Path
import subprocess
import sys


def run_plan(plan, root):
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    if plan["head"] != head:
        raise ValueError("The test plan belongs to a different commit")
    if plan["application"] and not plan["commands"]:
        raise ValueError("Application validation cannot omit all tests")
    for command in plan["commands"]:
        print("Running " + command["module"] + " tests (" + command["scope"] + ")", flush=True)
        subprocess.run(command["argv"], cwd=root, check=True)


if __name__ == "__main__":
    run_plan(json.loads(Path(sys.argv[1]).read_text()), Path(__file__).resolve().parent.parent)
