"""Copies the add-on SDK from a checkout of agent-friday-tv into android/addon-sdk.

  python scripts/sync_sdk.py ../agent-friday-tv
"""
import shutil
import sys
from pathlib import Path

src = Path(sys.argv[1]) / "android" / "addon-sdk"
dst = Path(__file__).resolve().parents[1] / "android" / "addon-sdk"
if not (src / "build.gradle").exists():
    sys.exit(f"No add-on SDK at {src}")
note = (dst / "SDK-SOURCE.txt").read_text(encoding="utf-8")
shutil.rmtree(dst)
shutil.copytree(src, dst, ignore=shutil.ignore_patterns("build", ".gradle"))
(dst / "SDK-SOURCE.txt").write_text(note.replace("\r\n", "\n"), encoding="utf-8", newline="\n")
print(f"SDK copied from {src}")
