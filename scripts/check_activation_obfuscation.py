import argparse
import base64
import json
import os
from pathlib import Path
import re
import struct
import zipfile


CLASS_MAPPING = re.compile(r"^(\S+) -> (\S+):$")


def verify_endpoints(apk, profile):
    packed = json.loads(apk.read("assets/meridian-service.json"))
    if set(packed) != {"seed", "payload"}:
        raise ValueError("The service profile must be encoded")
    seed = packed["seed"]
    if type(seed) is not int or not 0 <= seed <= 255:
        raise ValueError("Invalid service profile encoding")
    payload = packed["payload"]
    encoded = base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))
    decoded = bytes(value ^ ((seed + index * 31) & 255)
                    for index, value in enumerate(encoded))
    if json.loads(decoded) != profile:
        raise ValueError("The packaged service profile does not match its source")
    forbidden = {b"SandFly-123", b"pkg-7f3c9a2e",
                 b"https://api.github.com/repos/manh212/",
                 b"https://raw.githubusercontent.com/manh212/"}
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    if repository:
        forbidden.add(repository.encode("utf-8"))
    for field in ("databaseUrl", "apiKey"):
        if profile.get(field):
            forbidden.add(profile[field].encode("utf-8"))
    forbidden.update(value.decode("utf-8").encode("utf-16-le") for value in tuple(forbidden))
    for name in apk.namelist():
        if re.fullmatch(r"classes(?:\d+)?\.dex", name) or name in {
                "assets/meridian-service.json", "resources.arsc", "AndroidManifest.xml"}:
            data = apk.read(name)
            if any(value in data for value in forbidden):
                raise ValueError("A distribution endpoint remains in plaintext: " + name)


def dex_classes(data):
    if len(data) < 112 or not data.startswith(b"dex\n"):
        raise ValueError("Invalid DEX header")

    def number(offset):
        return struct.unpack_from("<I", data, offset)[0]

    strings_count, strings_offset = number(56), number(60)
    types_count, types_offset = number(64), number(68)
    classes_count, classes_offset = number(96), number(100)
    if (strings_offset + strings_count * 4 > len(data)
            or types_offset + types_count * 4 > len(data)
            or classes_offset + classes_count * 32 > len(data)):
        raise ValueError("Invalid DEX tables")
    result = set()
    for index in range(classes_count):
        type_index = number(classes_offset + index * 32)
        if type_index >= types_count:
            raise ValueError("Invalid DEX class index")
        string_index = number(types_offset + type_index * 4)
        if string_index >= strings_count:
            raise ValueError("Invalid DEX descriptor index")
        offset = number(strings_offset + string_index * 4)
        for _ in range(5):
            if offset >= len(data):
                raise ValueError("Invalid DEX string offset")
            value = data[offset]
            offset += 1
            if value < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.find(b"\0", offset)
        if end < 0:
            raise ValueError("Unterminated DEX descriptor")
        descriptor = data[offset:end].decode("utf-8")
        if not descriptor.startswith("L") or not descriptor.endswith(";"):
            raise ValueError("Invalid DEX class descriptor")
        result.add(descriptor[1:-1].replace("/", "."))
    return result


def verify(apk_path, mapping_path, namespace, entrypoints, members):
    prefixes = tuple(name + "." for name in namespace)
    mapping = Path(mapping_path).read_text(encoding="utf-8")
    if not mapping.strip() or "compiler: R8" not in mapping:
        raise ValueError("An R8 mapping is required")
    class_map = {}
    member_map = {}
    current_class = None
    for line in mapping.splitlines():
        match = CLASS_MAPPING.fullmatch(line)
        if match:
            current_class = match.group(1)
            class_map[current_class] = match.group(2)
        elif line.startswith("    ") and " -> " in line and current_class:
            original, target = line.strip().rsplit(" -> ", 1)
            original = re.sub(r"^\d+:\d+:", "", original)
            name = original.split("(", 1)[0].rsplit(" ", 1)[-1].rsplit(".", 1)[-1]
            member_map.setdefault((current_class, name), set()).add(target)
    defined = set()
    with zipfile.ZipFile(apk_path) as apk:
        profile = json.loads(Path("activation/assets/meridian-service.json").read_text(encoding="utf-8"))
        verify_endpoints(apk, profile)
        for name in apk.namelist():
            if re.fullmatch(r"classes(?:\d+)?\.dex", name):
                defined.update(dex_classes(apk.read(name)))
        if any(name.endswith("mapping.txt") for name in apk.namelist()):
            raise ValueError("The APK must not contain the R8 mapping")
    if not defined:
        raise ValueError("The APK has no DEX classes")
    if not set(entrypoints) <= defined:
        raise ValueError("An Android activation entry point is missing")
    protected = {name: target for name, target in class_map.items()
                 if name.startswith(prefixes) and name not in entrypoints}
    if not protected:
        raise ValueError("No protected activation code was found in the R8 mapping")
    exposed = {name for name in defined
               if name.startswith(prefixes) and name not in entrypoints}
    if exposed:
        raise ValueError("Activation implementation class names remain in the APK: "
                         + ", ".join(sorted(exposed)))
    for member in members:
        owner, name = member.rsplit("#", 1)
        if name in member_map.get((owner, name), set()):
            raise ValueError("An activation implementation member was not obfuscated: " + member)
    print(f"Verified {len(protected)} activation implementation classes were renamed or removed.")
    print("Android entry points are present; activation implementation member names are protected.")
    print("Distribution addresses and the service profile are encoded in the APK.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--mapping", required=True)
    parser.add_argument("--namespace", required=True, action="append")
    parser.add_argument("--entrypoint", action="append", default=[])
    parser.add_argument("--member", action="append", default=[])
    args = parser.parse_args()
    verify(args.apk, args.mapping, args.namespace, args.entrypoint, args.member)


if __name__ == "__main__":
    main()
