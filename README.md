# Job Scout — Automated Job Scanner, AI CV Tailor & Apply Copilot

A self-hosted desktop dashboard (**FastAPI + Playwright**) and companion **Android app** (Kotlin + Jetpack Compose) for scanning SEEK NZ/AU, archiving full job descriptions, probing hidden salary bands, tailoring A4 CVs & cover letters with LLMs, and streamlining applications.

> **Privacy Note:** This repository contains no personal profiles, API keys, job databases, or browser cookies. All personal settings are stored locally in gitignored files (`desktop/config/*.json`, `desktop/data/`, and encrypted Android preferences).

## Repository Structure

- [`desktop/`](desktop/) — Python FastAPI web dashboard, SEEK scanner, Paste JD importer (LinkedIn/Indeed/company sites), hidden salary binary-search prober, parallel AI CV/Cover Letter tailoring queue, Playwright SEEK Quick Apply copilot, and bidirectional Phone Wi-Fi Sync server with live SEEK expiry checks.
- [`android/`](android/) — Native Kotlin + Jetpack Compose Android companion app with SEEK link/share-sheet & Paste JD import, unified pipeline categories & swipe triage, resilient SSE-streaming LLM tailoring, on-device A4 PDF rendering (including zero-API-cost rebuild from saved text), Q&A memory, and bidirectional LAN Wi-Fi Sync with Desktop.

## Quick Start (Desktop — One-Click Auto Setup)

Requires **Python 3.10+**. Everything else (`.venv`, Python packages, Playwright Chromium, and starter config templates) is installed and initialized automatically on first launch:

- **Windows**: Double-click [`Start Job Scout.bat`](Start%20Job%20Scout.bat) (or [`desktop/start_dashboard.bat`](desktop/start_dashboard.bat)).
- **macOS**: Double-click [`start_job_scout.command`](start_job_scout.command) (or run `./desktop/start_dashboard.sh` in Terminal).
- **Linux**: Run `./desktop/start_dashboard.sh` (or `python3 desktop/launch.py`).

Subsequent launches skip setup automatically and open the dashboard at `http://127.0.0.1:8765` right away. Once open, configure your **Master Profile**, **Queries & Guardrails**, **Q&A Memory**, and **AI Providers** (Gemini / OpenAI-compatible / DeepSeek / Anthropic — API keys are stored locally in `desktop/config/secrets.json`).

<details>
<summary>Manual CLI setup (optional)</summary>

```bash
cd desktop
python -m venv .venv
# Windows: .venv\Scripts\activate
# macOS/Linux: source .venv/bin/activate
pip install -r requirements.txt
playwright install chromium
python cli.py serve --open
```

</details>

## Quick Start (Android)

1. Open `android/` in **Android Studio** (Ladybug or newer, JDK 17).
2. Customize `android/app/src/main/assets/` templates or sync your profile over LAN from the Desktop dashboard via **Phone Sync**.
3. Build and install on your Android device, then add your LLM API keys in **Settings**.
