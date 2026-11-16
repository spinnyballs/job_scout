"""Verified Master Candidate Profile & Secret/API Key storage."""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from .models import CandidateProfile, ExperienceItem, ProjectItem, QABankEntry

CONFIG_DIR = Path(__file__).resolve().parent.parent / "config"
PROFILE_PATH = CONFIG_DIR / "candidate_profile.json"
SECRETS_PATH = CONFIG_DIR / "secrets.json"


def build_default_candidate_profile() -> CandidateProfile:
    """Starter placeholder profile — customize via Master Profile in the UI."""
    return CandidateProfile(
        full_name="Your Name",
        headline="Engineering Graduate | Hardware, Embedded & Software Systems",
        phone="+64 21 000 0000",
        email="you@example.com",
        location="Auckland, New Zealand",
        linkedin="linkedin.com/in/your-profile",
        work_rights="NZ Citizen / Permanent Resident (update as applicable)",
        languages="English",
        base_summary=(
            "Engineering graduate with hands-on experience designing, prototyping, testing, and documenting "
            "electronic hardware, embedded firmware, and automated test tooling."
        ),
        education=[
            {
                "degree": "Bachelor of Engineering (Honours)",
                "institution": "Your University",
                "dates": "2022–2025",
                "details": "Focus: Embedded systems, electronics, control systems, and software",
            }
        ],
        experiences=[
            ExperienceItem(
                id="exp_1",
                role="Test & Engineering Technician",
                company="Example Engineering Ltd",
                location="Auckland",
                dates="2025 – Present",
                tags=["testing", "calibration", "validation", "hardware", "documentation"],
                bullets=[
                    "Assemble, configure, and run functional and calibration verification tests on electro-mechanical systems.",
                    "Perform board-level fault diagnostics and maintain clear production test documentation.",
                ],
            ),
            ExperienceItem(
                id="exp_2",
                role="Electronics Repair Technician",
                company="Example Service Workshop",
                location="Auckland",
                dates="2022 – 2024",
                tags=["repair", "pcb", "soldering", "diagnostics"],
                bullets=[
                    "Diagnosed and repaired consumer and industrial electronics down to component level using multimeter, oscilloscope, and SMD rework.",
                ],
            ),
        ],
        projects=[
            ProjectItem(
                id="proj_1",
                name="Wi-Fi Power & Telemetry Monitor",
                tags=["embedded", "esp32", "c++", "iot", "telemetry"],
                bullets=[
                    "Built an ESP32-based power monitor sampling voltage/current sensors and streaming metrics to a local web dashboard.",
                ],
            ),
            ProjectItem(
                id="proj_2",
                name="Custom DC-DC Converter & PCB",
                tags=["power_electronics", "altium", "pcb", "control"],
                bullets=[
                    "Designed a multi-layer schematic and PCB layout in Altium Designer with closed-loop microcontroller regulation.",
                ],
            ),
        ],
        additional_projects=[
            "Automated test fixture controller",
        ],
        skills={
            "Hardware & Testing": [
                "Prototype bring-up & validation",
                "PCB-level fault diagnosis",
                "SMT/through-hole soldering & rework",
            ],
            "EDA & Simulation": [
                "Altium Designer",
                "LTspice",
            ],
            "Embedded & Software": [
                "C",
                "C++",
                "Python",
                "ESP32",
            ],
        },
        strict_truth_rules=[
            "NEVER invent degrees, employers, dates, certifications, licences, or software/hardware tools not in this profile.",
            "NEVER inflate experience durations or seniority.",
            "You MAY reorder experiences/projects and adjust wording to highlight relevant authentic experience.",
            "If the job requires a skill the candidate does not have, explicitly note the gap in truth_audit_notes instead of claiming it.",
        ],
    )


def default_qa_bank_entries() -> list[QABankEntry]:
    return [
        QABankEntry(
            canonical_key="work_rights_nz",
            question_pattern="right to work in New Zealand / citizenship / visa status",
            answer="I have unrestricted work rights in New Zealand (no sponsorship required).",
            category="work_rights",
            notes="Update with your exact visa or citizenship details.",
        ),
        QABankEntry(
            canonical_key="work_rights_au",
            question_pattern="right to work in Australia",
            answer="Update with your Australian work rights status.",
            category="work_rights",
        ),
        QABankEntry(
            canonical_key="drivers_licence",
            question_pattern="NZ driver's licence / full licence / own transport",
            answer="Yes, I hold a full clean driver's licence.",
            category="logistics",
            notes="Update if needed.",
        ),
        QABankEntry(
            canonical_key="notice_period",
            question_pattern="notice period / availability to start",
            answer="2–4 weeks notice.",
            category="logistics",
            notes="Verify against your employment agreement.",
        ),
        QABankEntry(
            canonical_key="salary_expectation_nz",
            question_pattern="salary expectation (annual NZD)",
            answer="$75,000 – $95,000 NZD base depending on scope and total package.",
            category="salary",
            notes="Adjust per role.",
        ),
        QABankEntry(
            canonical_key="police_clearance",
            question_pattern="criminal convictions / background & security clearance check",
            answer="Yes — clean record and eligible for standard background vetting.",
            category="work_rights",
        ),
    ]


class ProfileManager:
    def __init__(self, path: Path | str = PROFILE_PATH) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if not self.path.exists():
            self.save(build_default_candidate_profile())

    def load(self) -> CandidateProfile:
        return CandidateProfile.model_validate_json(self.path.read_text(encoding="utf-8"))

    def save(self, profile: CandidateProfile) -> CandidateProfile:
        self.path.write_text(profile.model_dump_json(indent=2), encoding="utf-8")
        return profile


class SecretsManager:
    """Stores API keys locally in config/secrets.json (outside main config, gitignored)."""

    def __init__(self, path: Path | str = SECRETS_PATH) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def _read_all(self) -> dict[str, str]:
        if not self.path.exists():
            return {}
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            return {str(k): str(v) for k, v in data.items() if v}
        except Exception:
            return {}

    def get_key(self, env_name: str) -> str:
        from_env = os.environ.get(env_name, "").strip()
        if from_env:
            return from_env
        return self._read_all().get(env_name, "").strip()

    def set_keys(self, updates: dict[str, str]) -> None:
        data = self._read_all()
        for k, v in updates.items():
            cleaned = (v or "").strip()
            if cleaned:
                data[k] = cleaned
            elif k in data and v == "":
                del data[k]
        self.path.write_text(json.dumps(data, indent=2), encoding="utf-8")

    def status(self, env_names: list[str]) -> dict[str, dict[str, Any]]:
        stored = self._read_all()
        out: dict[str, dict[str, Any]] = {}
        for name in env_names:
            val = os.environ.get(name, "").strip() or stored.get(name, "").strip()
            masked = f"...{val[-4:]}" if len(val) >= 6 else ("set" if val else "")
            out[name] = {
                "configured": bool(val),
                "source": "env" if os.environ.get(name, "").strip() else ("secrets.json" if stored.get(name) else "none"),
                "masked": masked,
            }
        return out
