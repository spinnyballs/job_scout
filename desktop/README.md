# Job Scout — Desktop Dashboard & Apply Copilot

FastAPI web dashboard built around a board-agnostic adapter interface with **SEEK New Zealand (`seek_nz`)** and **SEEK Australia (`seek_au`)** active out of the box, plus plugin slots ready for Trade Me Jobs, LinkedIn, and direct ATS portals.

## Quick Start

1. Create a virtual environment and install dependencies:

```powershell
cd desktop
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt
playwright install chromium
```

2. (Optional) Copy starter templates or let the app create them on first launch:

```powershell
copy config\candidate_profile.example.json config\candidate_profile.json
copy config\scanner_config.example.json config\scanner_config.json
```

3. Double-click **`start_dashboard.bat`**, or run from PowerShell:

```powershell
.\.venv\Scripts\python.exe cli.py serve --open
```

Dashboard URL: `http://127.0.0.1:8765`

To run a headless scan from CLI (e.g. for Task Scheduler):

```powershell
.\.venv\Scripts\python.exe cli.py scan --source seek_nz --probes 8
```

## Key Features
- **SEEK v5 Search + GraphQL Archiver (`app/sources/seek.py`):** Pulls search listings, archives full HTML + clean-text job descriptions locally, detects **Quick Apply vs. External ATS**, and extracts **employer pre-screening questions**.
- **Hidden Salary Estimator:** Binary-searches SEEK's hidden `salaryrange` filter to uncover employer-entered min/max salary bands even when hidden publicly.
- **Employer Guardrails (`config/scanner_config.json`):** Hard-blocks any current/former employers you configure across company names and job descriptions.
- **AI Fit Analysis & Parallel Tailoring Queue (`app/tailor.py`):** Scores role fit and generates tailored A4 PDF CVs and cover letters in parallel using Gemini, DeepSeek, Anthropic, or any OpenAI-compatible API.
- **SEEK Quick Apply Copilot (`app/apply_copilot.py`):** Attaches tailored PDFs, inserts your cover letter, fills screening questions from Q&A Memory, and stops before final submission so you review every application.
- **Status Tracking:** Track `Discovered`, `Shortlisted`, `Tailored`, `Applied`, `Interview`, and **`No Reply / Closed`**, plus LAN **Phone Sync** with the Android app.
