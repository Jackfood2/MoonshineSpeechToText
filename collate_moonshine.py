"""Moonshine Collator — packs all .kt sources + key Android files into one
UTF-8 text file on the Desktop for expert review."""
import os
import sys
from pathlib import Path
import zipfile
import shutil

PROJECT_ROOT = Path(r"C:\Peter\MoonshineAndroid")
OUTPUT_NAME = "collated_moonshine.txt"

INCLUDE_SUFFIXES = {".kt", ".java"}
INCLUDE_RES_SUFFIXES = {".xml"}


def get_local_desktop() -> Path:
    candidates = []
    user_profile = os.environ.get("USERPROFILE")
    if user_profile:
        candidates.append(Path(user_profile) / "Desktop")
    candidates.append(Path.home() / "Desktop")
    username = os.environ.get("USERNAME")
    if username:
        candidates.append(Path(f"C:/Users/{username}/Desktop"))
    for target in candidates:
        try:
            target.mkdir(parents=True, exist_ok=True)
            probe = target / ".write_test.tmp"
            probe.touch()
            probe.unlink()
            return target
        except Exception:
            continue
    return Path.cwd()


def read_file_safe(filepath: Path) -> tuple:
    for enc in ("utf-8", "utf-8-sig", "latin-1", "cp1252"):
        try:
            return True, filepath.read_text(encoding=enc)
        except (UnicodeDecodeError, LookupError):
            continue
        except PermissionError:
            return False, f"[PERMISSION DENIED: '{filepath.name}' is locked.]"
        except Exception as err:
            return False, f"[ERROR reading '{filepath.name}': {err}]"
    try:
        return True, filepath.read_bytes().decode("utf-8", errors="replace")
    except Exception as err:
        return False, f"[FAILED TO READ '{filepath.name}': {err}]"


def format_entry(label: str, content: str) -> str:
    header = f"\n{'=' * 70}\nFILE: {label}\n{'=' * 70}\n\n"
    return f"{header}{content.rstrip()}\n\n"


def discover_files(root: Path):
    """Return list of (label, path) for every source + key Android file."""
    files = []

    src = root / "app" / "src" / "main" / "java"
    if src.is_dir():
        for p in sorted(src.rglob("*")):
            if p.is_file() and p.suffix in INCLUDE_SUFFIXES:
                files.append((p.relative_to(root).as_posix(), p))

    manifest = root / "app" / "src" / "main" / "AndroidManifest.xml"
    if manifest.is_file():
        files.append(("android manifest", manifest))

    res = root / "app" / "src" / "main" / "res"
    if res.is_dir():
        for p in sorted(res.rglob("*.xml")):
            if p.is_file():
                files.append(("res/" + p.relative_to(res).as_posix(), p))

    extras = [
        ("build.gradle (project)", root / "build.gradle"),
        ("build.gradle (app)", root / "app" / "build.gradle"),
        ("settings.gradle", root / "settings.gradle"),
        ("gradle.properties", root / "gradle.properties"),
        ("gradle/wrapper/gradle-wrapper.properties", root / "gradle" / "wrapper" / "gradle-wrapper.properties"),
    ]
    for label, path in extras:
        if path.is_file():
            files.append((label, path))

    return files


def main() -> int:
    print("=" * 70)
    print("Moonshine Collator")
    print("=" * 70)

    files = discover_files(PROJECT_ROOT)
    if not files:
        print(f"\nNo Android/Kotlin files found under:\n{PROJECT_ROOT}")
        return 1

    success = 0
    failed = []
    total_chars = 0
    out_file = get_local_desktop() / OUTPUT_NAME

    print(f"\nCollating {len(files)} files into:\n{out_file}")
    with open(out_file, "w", encoding="utf-8") as fh:
        for label, path in files:
            ok, content = read_file_safe(path)
            if ok:
                entry = format_entry(label, content)
                fh.write(entry)
                total_chars += len(entry)
                success += 1
            else:
                failed.append((label, content))

    print(f"\nDone: {success}/{len(files)} files collated")
    print(f"Total characters: {total_chars:,}")
    print(f"Saved to: {out_file}")

    if failed:
        print("\nWARNINGS (skipped):")
        for label, msg in failed:
            print(f"  - {label}: {msg}")

    return 0


if __name__ == "__main__":
    try:
        code = main()
    except Exception as exc:
        print(f"\nERROR: {exc}")
        code = 1
    print("\nPress Enter to close...")
    input()
    sys.exit(code)