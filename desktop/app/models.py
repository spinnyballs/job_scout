"""Domain models for the job scanner, LLM fit scoring, CV tailoring, and Q&A bank."""
from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, Field, field_validator


class JobStatus(str, Enum):
    DISCOVERED = "discovered"
    SHORTLISTED = "shortlisted"
    SKIPPED = "skipped"
    BLOCKED = "blocked"
    TAILORED = "tailored"
    APPLIED = "applied"
    INTERVIEW = "interview"
    NO_REPLY = "no_reply"
    REJECTED = "rejected"


class SearchQueryConfig(BaseModel):
    name: str
    keywords: str
    where: str = "Auckland"
    site_key: str = "NZ-Main"
    salary_min: int | None = None
    salary_max: int | None = None
    work_type: str | None = None  # e.g., "full-time", "contract-temp"
    date_range_days: int | None = 14
    max_pages: int = 3
    enabled: bool = True
    tags: list[str] = Field(default_factory=list)


class ScoringRuleConfig(BaseModel):
    min_score_to_surface: int = 20
    positive_keywords: dict[str, int] = Field(default_factory=dict)
    negative_keywords: dict[str, int] = Field(default_factory=dict)
    hard_exclude_title_terms: list[str] = Field(default_factory=list)
    hard_exclude_body_terms: list[str] = Field(default_factory=list)
    preferred_locations: dict[str, int] = Field(default_factory=dict)
    min_acceptable_salary: int = 65000
    max_experience_years: int = 4


class CustomProviderPreset(BaseModel):
    id: str
    label: str
    base_url: str
    model: str
    api_key_env: str


class LLMProviderConfig(BaseModel):
    """Multi-provider LLM config (Gemini first, extensible to DeepSeek/OpenAI-compatible/Anthropic/Custom)."""

    active_provider: str = "gemini"  # "gemini" | "deepseek" | "openai_compatible" | "anthropic" | "custom_*"
    gemini_model: str = "gemini-2.5-flash"
    gemini_api_key_env: str = "GEMINI_API_KEY"
    gemini_thinking_level: str = "default"  # "default" | "minimal" | "low" | "medium" | "high"
    deepseek_model: str = "deepseek-chat"
    deepseek_base_url: str = "https://api.deepseek.com/v1"
    deepseek_api_key_env: str = "DEEPSEEK_API_KEY"
    openai_base_url: str = "http://127.0.0.1:11434/v1"
    openai_model: str = "qwen2.5:14b"
    openai_api_key_env: str = "OPENAI_API_KEY"
    anthropic_model: str = "claude-sonnet-4-5"
    anthropic_api_key_env: str = "ANTHROPIC_API_KEY"
    temperature: float = 0.25
    custom_presets: list[CustomProviderPreset] = Field(default_factory=list)


class ScannerConfig(BaseModel):
    profile_name: str = "My Job Profile"
    default_board: str = "seek_nz"
    blocked_companies: list[str] = Field(default_factory=list)
    blocked_keywords_in_company: list[str] = Field(default_factory=list)
    recruitment_agency_penalty: int = -8
    company_cooldown_days: int = 14
    probe_salary_above_score: int = 40
    fetch_details_for_all_new: bool = True
    request_delay_ms: int = 250
    queries: list[SearchQueryConfig] = Field(default_factory=list)
    scoring: ScoringRuleConfig = Field(default_factory=ScoringRuleConfig)
    llm: LLMProviderConfig = Field(default_factory=LLMProviderConfig)


class NormalizedJob(BaseModel):
    """Board-agnostic job schema shared across Seek, LinkedIn, TradeMe, ATS adapters."""

    source: str
    external_id: str
    url: str
    title: str
    company: str
    advertiser_id: str | None = None
    is_agency: bool = False
    location: str = ""
    work_type: str = ""
    work_arrangement: str = ""
    classifications: list[str] = Field(default_factory=list)
    teaser: str = ""
    bullet_points: list[str] = Field(default_factory=list)
    description_html: str = ""
    description_text: str = ""
    salary_label: str = ""
    salary_est_min: int | None = None
    salary_est_max: int | None = None
    posted_at: str | None = None
    expires_at: str | None = None
    is_link_out: bool = False
    is_expired: bool = False
    screening_questions: list[str] = Field(default_factory=list)
    matched_queries: list[str] = Field(default_factory=list)
    raw_search_json: dict[str, Any] = Field(default_factory=dict)
    raw_detail_json: dict[str, Any] = Field(default_factory=dict)


class ScoreBreakdown(BaseModel):
    score: int
    blocked_reason: str | None = None
    matched_positive: list[str] = Field(default_factory=list)
    matched_negative: list[str] = Field(default_factory=list)
    hard_exclude_hits: list[str] = Field(default_factory=list)
    flags: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class ScanRunSummary(BaseModel):
    run_id: int
    started_at: datetime
    finished_at: datetime | None = None
    source: str
    queries_run: int = 0
    total_fetched: int = 0
    new_jobs: int = 0
    updated_jobs: int = 0
    blocked_jobs: int = 0
    details_fetched: int = 0
    salaries_probed: int = 0
    errors: list[str] = Field(default_factory=list)


# --- Step 2 Models: Master Candidate Profile, LLM Fit, Tailored CV & Cover Letter, Q&A Bank ---


class ExperienceItem(BaseModel):
    id: str
    role: str
    company: str
    location: str
    dates: str
    tags: list[str] = Field(default_factory=list)
    bullets: list[str] = Field(default_factory=list)


class ProjectItem(BaseModel):
    id: str
    name: str
    tags: list[str] = Field(default_factory=list)
    bullets: list[str] = Field(default_factory=list)


class CandidateProfile(BaseModel):
    """Verified Ground-Truth Profile — AI can select/rephrase from this, never invent."""

    full_name: str
    headline: str
    phone: str
    email: str
    location: str
    linkedin: str
    work_rights: str
    languages: str
    base_summary: str
    education: list[dict[str, str]] = Field(default_factory=list)
    experiences: list[ExperienceItem] = Field(default_factory=list)
    projects: list[ProjectItem] = Field(default_factory=list)
    additional_projects: list[str] = Field(default_factory=list)
    skills: dict[str, list[str]] = Field(default_factory=dict)
    strict_truth_rules: list[str] = Field(default_factory=list)


class LLMFitAnalysis(BaseModel):
    fit_score: int = Field(ge=0, le=100, description="Realistic fit 0-100 based strictly on verified profile.")
    verdict: str = Field(description="One of: Strong Apply, Worth Applying, Stretch, Skip")
    role_angle: str = Field(description="Best positioning angle, e.g. 'Hardware Test & Validation' or 'Embedded Firmware'.")
    strengths: list[str] = Field(description="3-5 concrete evidence-backed strengths from candidate profile.")
    gaps_and_risks: list[str] = Field(description="Missing must-haves, seniority gaps, or clearance/license mismatches.")
    recommended_projects: list[str] = Field(description="IDs or names of projects most relevant to emphasize.")
    rationale: str = Field(description="2-3 sentence candid hiring-manager assessment.")


class TailoredExperienceBlock(BaseModel):
    experience_id: str = ""
    role: str = ""
    company: str = ""
    location: str = ""
    dates: str = ""
    bullets: list[str] = Field(default_factory=list)

    @field_validator("experience_id", "role", "company", "location", "dates", mode="before")
    @classmethod
    def _coerce_str(cls, v: Any) -> str:
        return "" if v is None else str(v)

    @field_validator("bullets", mode="before")
    @classmethod
    def _coerce_bullets(cls, v: Any) -> list[str]:
        if v is None:
            return []
        if isinstance(v, list):
            return [str(x) for x in v if x is not None]
        return [str(v)]


class TailoredProjectBlock(BaseModel):
    project_id: str = ""
    name: str = ""
    bullets: list[str] = Field(default_factory=list)

    @field_validator("project_id", "name", mode="before")
    @classmethod
    def _coerce_str(cls, v: Any) -> str:
        return "" if v is None else str(v)

    @field_validator("bullets", mode="before")
    @classmethod
    def _coerce_bullets(cls, v: Any) -> list[str]:
        if v is None:
            return []
        if isinstance(v, list):
            return [str(x) for x in v if x is not None]
        return [str(v)]


class TailoredApplicationPayload(BaseModel):
    """Structured output returned by LLM for CV + Cover Letter + Screening Answers."""

    tailored_headline: str = Field(
        default="",
        description="Sharp 1-line subtitle aligned to target role, factual to candidate.",
    )
    tailored_summary: str = Field(
        default="",
        description="3-4 line professional profile grounded strictly in verified facts.",
    )
    selected_experiences: list[TailoredExperienceBlock] = Field(default_factory=list)
    selected_projects: list[TailoredProjectBlock] = Field(default_factory=list)
    tailored_skills: dict[str, str] = Field(
        default_factory=dict,
        description="4 concise skill category lines ordered by relevance to the job, each value a comma-separated string.",
    )
    cover_letter_subject: str = ""
    cover_letter_body: str = Field(
        default="",
        description="Concise 3-4 paragraph cover letter without placeholder brackets except date/hiring manager if unknown.",
    )
    suggested_screening_answers: dict[str, str] = Field(
        default_factory=dict,
        description="Draft answers keyed by exact screening question text.",
    )
    truth_audit_notes: list[str] = Field(
        default_factory=list,
        description="Self-check listing any job requirements the candidate does NOT have (so we never claim them).",
    )

    @field_validator("tailored_headline", "tailored_summary", "cover_letter_subject", "cover_letter_body", mode="before")
    @classmethod
    def _coerce_str_fields(cls, v: Any) -> str:
        return "" if v is None else str(v)

    @field_validator("selected_experiences", "selected_projects", "truth_audit_notes", mode="before")
    @classmethod
    def _coerce_lists(cls, v: Any) -> list[Any]:
        return [] if v is None else v

    @field_validator("tailored_skills", "suggested_screening_answers", mode="before")
    @classmethod
    def _coerce_str_dict(cls, v: Any) -> dict[str, str]:
        if not v:
            return {}
        if isinstance(v, dict):
            out: dict[str, str] = {}
            for k, val in v.items():
                if val is None:
                    out[str(k)] = ""
                elif isinstance(val, list):
                    out[str(k)] = ", ".join(str(x) for x in val if x is not None)
                else:
                    out[str(k)] = str(val)
            return out
        return {}


class QABankEntry(BaseModel):
    id: int | None = None
    canonical_key: str
    question_pattern: str
    answer: str
    category: str = "general"  # work_rights, logistics, salary, technical, general
    notes: str = ""
    updated_at: str | None = None


class ProfileAuditSuggestions(BaseModel):
    summary: str = ""
    missing_skills: list[str] = Field(default_factory=list)
    project_bullet_ideas: list[str] = Field(default_factory=list)
    bio_suggestions: list[str] = Field(default_factory=list)
    keywords_to_add: list[str] = Field(default_factory=list)


class PhoneSyncRequest(BaseModel):
    profile: CandidateProfile | None = None
    overwrite_profile: bool = True
    jobs: list[dict[str, Any]] = Field(default_factory=list)


class ExtractedJDMetadata(BaseModel):
    """Structured fields extracted from raw pasted JD text."""

    title: str = ""
    company: str = ""
    location: str = ""
    work_type: str = ""
    work_arrangement: str = ""
    salary_label: str = ""
    salary_est_min: int | None = None
    salary_est_max: int | None = None
    bullet_points: list[str] = Field(default_factory=list)
    screening_questions: list[str] = Field(default_factory=list)

    @field_validator("title", "company", "location", "work_type", "work_arrangement", "salary_label", mode="before")
    @classmethod
    def _coerce_str(cls, v: Any) -> str:
        return "" if v is None else str(v).strip()

    @field_validator("bullet_points", "screening_questions", mode="before")
    @classmethod
    def _coerce_list(cls, v: Any) -> list[str]:
        if not v:
            return []
        if isinstance(v, list):
            return [str(x).strip() for x in v if x is not None and str(x).strip()]
        return [str(v).strip()]

