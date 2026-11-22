"""Zero-dependency one-click bootstrapper for Job Scout Desktop.

Run directly with any Python 3.10+ interpreter (or via start_dashboard.bat / start_dashboard.sh):
  1. Creates desktop/.venv if missing.
  2. Installs/updates Python dependencies from requirements.txt (cached via SHA-256 stamp).
  3. Installs Playwright Chromium on first run (cached via stamp).
  4. Seeds config/candidate_profile.json & config/scanner_config.json from *.example.json if absent.
  5. Starts the Job Scout dashboard and opens it automatically.
"""
from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import sys
from pathlib import Path

DESKTOP_DIR = Path(__file__).resolve().parent
VENV_DIR = DESKTOP_DIR / ".venv"
REQ_FILE = DESKTOP_DIR / "requirements.txt"
REQ_STAMP = VENV_DIR / ".requirements.sha256"
PW_STAMP = VENV_DIR / ".playwright_chromium.ok"
CONFIG_DIR = DESKTOP_DIR / "config"


def _venv_python() -> Path:
    if os.name == "nt":
        return VENV_DIR / "Scripts" / "python.exe"
    return VENV_DIR / "bin" / "python"


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _run(cmd: list[str], label: str) -> None:
    print(f"[Job Scout Setup] {label}...")
    result = subprocess.run(cmd, cwd=str(DESKTOP_DIR))
    if result.returncode != 0:
        raise SystemExit(
            f"\n[!] Setup step failed ({label}) with exit code {result.returncode}.\n"
            f"    Command: {' '.join(cmd)}"
        )


def ensure_venv() -> Path:
    vpy = _venv_python()
    if vpy.exists():
        return vpy
    print("[Job Scout Setup] First run detected — creating virtual environment (.venv)...")
    _run([sys.executable, "-m", "venv", str(VENV_DIR)], "Creating .venv")
    if not vpy.exists():
        raise SystemExit(f"[!] Virtual environment created, but Python binary not found at {vpy}")
    return vpy


def ensure_requirements(vpy: Path) -> None:
    if not REQ_FILE.exists():
        return
    expected_hash = _file_sha256(REQ_FILE)
    current_hash = REQ_STAMP.read_text(encoding="utf-8").strip() if REQ_STAMP.exists() else ""
    if current_hash == expected_hash:
        return
    _run([str(vpy), "-m", "pip", "install", "--upgrade", "pip"], "Upgrading pip")
    _run([str(vpy), "-m", "pip", "install", "-r", str(REQ_FILE)], "Installing Python packages")
    REQ_STAMP.write_text(expected_hash, encoding="utf-8")


def ensure_playwright_chromium(vpy: Path) -> None:
    if PW_STAMP.exists():
        return
    print("[Job Scout Setup] Installing Playwright Chromium browser (one-time download)...")
    result = subprocess.run([str(vpy), "-m", "playwright", "install", "chromium"], cwd=str(DESKTOP_DIR))
    if result.returncode != 0:
        print(
            "\n[!] Warning: Playwright Chromium install did not finish cleanly.\n"
            "    The dashboard will still start, and you can retry later with:\n"
            f"    {vpy} -m playwright install chromium\n"
        )
        return
    PW_STAMP.write_text("ok\n", encoding="utf-8")


def ensure_starter_configs() -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    pairs = [
        ("candidate_profile.example.json", "candidate_profile.json"),
        ("scanner_config.example.json", "scanner_config.json"),
    ]
    for src_name, dst_name in pairs:
        src = CONFIG_DIR / src_name
        dst = CONFIG_DIR / dst_name
        if src.exists() and not dst.exists():
            shutil.copy2(src, dst)
            print(f"[Job Scout Setup] Initialized config/{dst_name} from template.")


def main() -> None:
    if sys.version_info < (3, 10):
        raise SystemExit(
            f"[!] Python 3.10+ is required (found {sys.version_info.major}.{sys.version_info.minor}).\n"
            "    Download Python from https://www.python.org/downloads/"
        )

    skip_playwright = "--skip-playwright" in sys.argv
    forward_args = [a for a in sys.argv[1:] if a != "--skip-playwright"]

    vpy = ensure_venv()
    ensure_requirements(vpy)
    if not skip_playwright:
        ensure_playwright_chromium(vpy)
    ensure_starter_configs()

    cli_args = forward_args if forward_args else ["serve", "--open"]
    print("[Job Scout] Launching dashboard...")
    raise SystemExit(subprocess.call([str(vpy), str(DESKTOP_DIR / "cli.py"), *cli_args], cwd=str(DESKTOP_DIR)))


if __name__ == "__main__":
    main()
