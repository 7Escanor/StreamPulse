"""Generate local.properties from environment variables (CI) or keep existing sdk.dir (local)."""
import os
import sys
from typing import Optional

# Keys read by plugin build.gradle.kts files via local.properties
PLUGIN_KEYS = [
    "TMDB_API",
    "DUMP_API",
    "DUMP_KEY",
    "ZSHOW_API",
    "ANICHI_API",
    "KissKh",
    "KisskhSub",
    "SUPERSTREAM_THIRD_API",
    "SUPERSTREAM_FOURTH_API",
    "SUPERSTREAM_FIRST_API",
    "PROXYAPI",
    "KAISVA",
    "MOVIEBOX_SECRET_KEY_ALT",
    "MOVIEBOX_SECRET_KEY_DEFAULT",
    "KAIMEG",
    "KAIDEC",
    "KAIENC",
    "VideasyDEC",
    "YFXENC",
    "YFXDEC",
    "NuvFeb",
    "ANICHI_APP",
    "SIMKL_API",
    "MAL_API",
    "TMDB_TOKEN",
]


def read_existing_sdk_dir(path: str) -> Optional[str]:
    if not os.path.exists(path):
        return None
    with open(path, "r", encoding="utf-8-sig") as f:
        for line in f:
            line = line.strip()
            if line.startswith("sdk.dir="):
                return line.split("=", 1)[1]
    return None


def main() -> int:
    repo_root = os.path.abspath(os.path.dirname(__file__))
    out_path = os.path.join(repo_root, "local.properties")

    sdk_dir = (
        os.environ.get("ANDROID_SDK_ROOT")
        or os.environ.get("ANDROID_HOME")
        or read_existing_sdk_dir(out_path)
    )

    lines: list[str] = []
    if sdk_dir:
        lines.append(f"sdk.dir={sdk_dir.replace(chr(92), '/')}")

    for key in PLUGIN_KEYS:
        value = os.environ.get(key, "")
        lines.append(f"{key}={value}")

    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")

    print(f"Wrote {out_path} ({len(lines)} entries, sdk.dir={'set' if sdk_dir else 'missing'})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
