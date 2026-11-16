"""Configuration loader/saver seeded with default search profiles & guardrails."""
from __future__ import annotations

import json
from pathlib import Path

from .models import ScannerConfig, ScoringRuleConfig, SearchQueryConfig

DEFAULT_CONFIG_PATH = Path(__file__).resolve().parent.parent / "config" / "scanner_config.json"


def build_default_config() -> ScannerConfig:
    return ScannerConfig(
        profile_name="Default Profile — Engineering & Embedded",
        default_board="seek_nz",
        blocked_companies=[],
        blocked_keywords_in_company=[],
        recruitment_agency_penalty=-8,
        probe_salary_above_score=48,
        fetch_details_for_all_new=True,
        request_delay_ms=220,
        queries=[
            SearchQueryConfig(
                name="Electronics & Hardware (Auckland)",
                keywords="electronics engineer OR hardware engineer OR PCB",
                where="All Auckland",
                site_key="NZ-Main",
                date_range_days=14,
                max_pages=2,
                enabled=True,
                tags=["hardware", "auckland"],
            ),
            SearchQueryConfig(
                name="Embedded & Firmware (Auckland)",
                keywords="embedded engineer OR firmware engineer OR microcontroller",
                where="All Auckland",
                site_key="NZ-Main",
                date_range_days=14,
                max_pages=2,
                enabled=True,
                tags=["embedded", "auckland"],
            ),
            SearchQueryConfig(
                name="Instrumentation, Test & Validation (Auckland)",
                keywords='instrumentation OR "test engineer" OR "validation engineer" OR calibration',
                where="All Auckland",
                site_key="NZ-Main",
                date_range_days=14,
                max_pages=2,
                enabled=True,
                tags=["instrumentation", "test", "auckland"],
            ),
            SearchQueryConfig(
                name="Graduate / Junior Systems (Auckland)",
                keywords='"graduate electrical" OR "junior electronics" OR "systems engineer" OR mechatronics',
                where="All Auckland",
                site_key="NZ-Main",
                date_range_days=14,
                max_pages=2,
                enabled=True,
                tags=["graduate", "systems", "auckland"],
            ),
        ],
        scoring=ScoringRuleConfig(
            min_score_to_surface=25,
            min_acceptable_salary=65000,
            max_experience_years=4,
            positive_keywords={
                "electronics": 10,
                "embedded": 12,
                "firmware": 10,
                "hardware": 9,
                "pcb": 12,
                "altium": 12,
                "sensor": 10,
                "instrumentation": 11,
                "calibration": 10,
                "validation": 8,
                "prototype": 8,
                "fault finding": 9,
                "diagnostics": 7,
                "soldering": 8,
                "oscilloscope": 7,
                "microcontroller": 10,
                "c++": 8,
                "embedded c": 10,
                "python": 6,
                "esp32": 9,
                "test engineer": 10,
                "graduate": 9,
                "junior": 8,
                "linux": 5,
            },
            negative_keywords={
                "senior": 12,
                "lead": 12,
                "manager": 15,
                "ewrb": 35,
                "practicing licence": 35,
                "practising licence": 35,
                "registered electrician": 40,
                "sparky": 35,
                "heavy diesel": 35,
                ".net developer": 22,
                "frontend developer": 22,
                "salesforce": 25,
                "chartered engineer": 15,
            },
            hard_exclude_title_terms=[
                "principal",
                "director",
                "head of",
                "vice president",
                "registered electrician",
                "commercial electrician",
                "residential electrician",
                "diesel mechanic",
                "plumber",
                "hvac technician",
                "sales manager",
            ],
            hard_exclude_body_terms=[
                "must hold a current nz ewrb",
                "nz electrical registration required",
            ],
            preferred_locations={
                "auckland": 8,
                "wellington": 2,
                "christchurch": 2,
                "hamilton": 2,
            },
        ),
    )


class ConfigManager:
    def __init__(self, path: Path | str = DEFAULT_CONFIG_PATH) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if not self.path.exists():
            self.save(build_default_config())

    def load(self) -> ScannerConfig:
        raw = json.loads(self.path.read_text(encoding="utf-8"))
        return ScannerConfig.model_validate(raw)

    def save(self, config: ScannerConfig) -> ScannerConfig:
        self.path.write_text(config.model_dump_json(indent=2), encoding="utf-8")
        return config
