"""Grounded AI workflows: candidate fit evaluation, tailored CV/cover letter drafting, and PDF rendering."""
from __future__ import annotations

import asyncio
from datetime import datetime
import html
import json
import re
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from .llm.providers import LLMProvider
from .models import (
    CandidateProfile,
    LLMFitAnalysis,
    ProfileAuditSuggestions,
    QABankEntry,
    TailoredApplicationPayload,
)

ARTIFACTS_DIR = Path(__file__).resolve().parent.parent / "data" / "artifacts"


def _slugify(text: str) -> str:
    s = re.sub(r"[^a-zA-Z0-9]+", "_", text).strip("_")
    return s[:48] or "role"


def _find_chromium_binary() -> str | None:
    candidates = [
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    ]
    for path in candidates:
        if Path(path).exists():
            return path
    return None


def _bold_metrics(text: str) -> str:
    """Escape HTML safely while emphasizing key numbers or explicit phrases gently."""
    return html.escape(text)


_CLOSING_PREFIX_RE = re.compile(
    r"^(?:(?:kind|warm|best|many)\s+(?:regards|wishes)|regards|yours\s+(?:sincerely|faithfully|truly)|sincerely|respectfully|thanks(?:\s+again)?)[,!.]*",
    re.IGNORECASE,
)


def _clean_company_name(company: str) -> str:
    c = (company or "").strip()
    if len(c) > 4 and c.isupper():
        return c.title()
    return c


def _split_cover_letter_sections(body_text: str, full_name: str) -> tuple[str, list[str], str]:
    """Extract salutation, clean body paragraphs, and sign-off phrase without duplicating template elements."""
    raw = (body_text or "").replace("\r\n", "\n").strip()
    if not raw:
        return "Dear Hiring Manager,", [], "Kind regards,"

    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", raw) if p.strip()]
    salutation = "Dear Hiring Manager,"
    closing = "Kind regards,"

    # 1. Extract leading salutation (handles both standalone paragraph and "Dear X,\nFirst paragraph...")
    if paragraphs:
        first_lines = [ln.strip() for ln in paragraphs[0].split("\n") if ln.strip()]
        if first_lines and re.match(r"^(?:dear|hi|hello|to\s+(?:whom|the))\b.{0,80}$", first_lines[0], re.IGNORECASE):
            cand = first_lines[0].rstrip(",:.") + ","
            salutation = cand
            if len(first_lines) > 1:
                paragraphs[0] = " ".join(first_lines[1:])
            else:
                paragraphs.pop(0)

    # 2. Strip trailing sign-off blocks ("Kind regards,\nCandidate Name", "Kind regards, Candidate Name", etc.)
    name_norm = re.sub(r"\s+", " ", (full_name or "").strip()).lower()
    while paragraphs:
        tail_lines = [ln.strip() for ln in paragraphs[-1].split("\n") if ln.strip()]
        if not tail_lines:
            paragraphs.pop()
            continue

        # Work backwards through lines of the trailing paragraph
        changed = False
        while tail_lines:
            last = tail_lines[-1]
            last_norm = re.sub(r"[^a-z0-9 ]+", "", last.lower()).strip()
            if (
                last_norm == name_norm
                or last.lower() in ("[your name]", "[name]")
                or re.match(r"^\+?\d[\d\s\-()]{6,}$", last)
                or ("@" in last and len(last) < 60 and " " not in last)
            ):
                tail_lines.pop()
                changed = True
                continue

            m_close = _CLOSING_PREFIX_RE.match(last)
            if m_close:
                after = last[m_close.end():].strip(" ,.!-\t")
                after_norm = re.sub(r"[^a-z0-9 ]+", "", after.lower()).strip()
                if not after or after_norm == name_norm:
                    phrase = m_close.group(0).strip(" ,.!").capitalize() + ","
                    closing = phrase
                    tail_lines.pop()
                    changed = True
                    continue
            break

        if not tail_lines:
            paragraphs.pop()
            continue
        if changed:
            paragraphs[-1] = " ".join(tail_lines)
        break

    cleaned_paragraphs = [re.sub(r"\s*\n\s*", " ", p).strip() for p in paragraphs if p.strip()]
    return salutation, cleaned_paragraphs, closing


def render_cv_html(profile: CandidateProfile, tailored: TailoredApplicationPayload) -> str:
    """Render an ATS-clean, single/two-page engineering CV with crisp typography."""
    exp_html = []
    for exp in tailored.selected_experiences:
        bullets = "\n".join(f"<li>{_bold_metrics(b)}</li>" for b in exp.bullets)
        exp_html.append(
            f"""
            <div class="entry">
              <div class="entry-head">
                <div><strong>{html.escape(exp.role)}</strong> — {html.escape(exp.company)}, {html.escape(exp.location)}</div>
                <div class="dates">{html.escape(exp.dates)}</div>
              </div>
              <ul>{bullets}</ul>
            </div>
            """
        )

    proj_html = []
    for proj in tailored.selected_projects:
        bullets = "\n".join(f"<li>{_bold_metrics(b)}</li>" for b in proj.bullets)
        proj_html.append(
            f"""
            <div class="entry compact">
              <div class="proj-title"><strong>{html.escape(proj.name)}</strong></div>
              <ul>{bullets}</ul>
            </div>
            """
        )

    edu_html = []
    for ed in profile.education:
        edu_html.append(
            f"""
            <div class="entry-head">
              <div><strong>{html.escape(ed.get('degree', ''))}</strong> — {html.escape(ed.get('institution', ''))}</div>
              <div class="dates">{html.escape(ed.get('dates', ''))}</div>
            </div>
            <div class="subline">{html.escape(ed.get('details', ''))}</div>
            """
        )

    skills_html = "\n".join(
        f"<tr><td class='sk-cat'>{html.escape(cat)}:</td><td>{html.escape(val)}</td></tr>"
        for cat, val in tailored.tailored_skills.items()
        if val.strip()
    )

    extra_proj = ""
    if profile.additional_projects:
        extra_proj = (
            f"<div class='subline'><strong>Additional Projects:</strong> "
            f"{html.escape(' · '.join(profile.additional_projects))}</div>"
        )

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<title>{html.escape(profile.full_name)} - CV</title>
<style>
  @page {{ size: A4; margin: 10mm 13mm 10mm 13mm; }}
  body {{
    font-family: "Segoe UI", Calibri, Arial, sans-serif;
    color: #1e2430;
    font-size: 9pt;
    line-height: 1.3;
    margin: 0;
  }}
  .header {{ border-bottom: 1.5px solid #2563eb; padding-bottom: 5px; margin-bottom: 7px; }}
  h1 {{ margin: 0; font-size: 16.5pt; letter-spacing: 0.4px; color: #0f172a; }}
  .headline {{ font-size: 9.6pt; font-weight: 600; color: #1d4ed8; margin: 1px 0 3px; }}
  .contact {{ font-size: 8.4pt; color: #334155; line-height: 1.35; }}
  h2 {{
    font-size: 9.4pt;
    text-transform: uppercase;
    letter-spacing: 0.7px;
    color: #0f172a;
    border-bottom: 1px solid #cbd5e1;
    padding-bottom: 2px;
    margin: 8px 0 4px;
    break-after: avoid;
  }}
  p.summary {{ margin: 0 0 4px; }}
  .entry {{ margin-bottom: 5px; break-inside: avoid; }}
  .entry.compact {{ margin-bottom: 4px; break-inside: avoid; }}
  .entry-head {{ display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }}
  .dates {{ font-size: 8.4pt; color: #475569; white-space: nowrap; font-style: italic; }}
  .proj-title {{ margin-bottom: 1px; }}
  .subline {{ font-size: 8.6pt; color: #334155; margin-top: 2px; }}
  ul {{ margin: 1.5px 0 0; padding-left: 15px; }}
  li {{ margin-bottom: 1.5px; }}
  table.skills {{ width: 100%; border-collapse: collapse; font-size: 8.8pt; break-inside: avoid; }}
  table.skills td {{ padding: 1px 4px 1px 0; vertical-align: top; }}
  td.sk-cat {{ width: 155px; font-weight: 600; color: #0f172a; white-space: nowrap; }}
</style>
</head>
<body>
  <div class="header">
    <h1>{html.escape(profile.full_name.upper())}</h1>
    <div class="headline">{html.escape(tailored.tailored_headline)}</div>
    <div class="contact">
      {html.escape(profile.phone)} · {html.escape(profile.email)} · {html.escape(profile.location)} · {html.escape(profile.linkedin)}<br/>
      <strong>Work Rights:</strong> {html.escape(profile.work_rights)} | <strong>Languages:</strong> {html.escape(profile.languages)}
    </div>
  </div>

  <h2>Profile</h2>
  <p class="summary">{html.escape(tailored.tailored_summary)}</p>

  <h2>Work Experience</h2>
  {''.join(exp_html)}

  <h2>Education</h2>
  {''.join(edu_html)}

  <h2>Key Engineering Projects</h2>
  {''.join(proj_html)}
  {extra_proj}

  <h2>Technical Skills</h2>
  <table class="skills">{skills_html}</table>
</body>
</html>
"""


def render_cover_letter_html(
    profile: CandidateProfile,
    tailored: TailoredApplicationPayload,
    *,
    job_title: str,
    company: str,
) -> str:
    salutation, paragraphs, closing = _split_cover_letter_sections(
        tailored.cover_letter_body,
        profile.full_name,
    )
    body_html = "\n".join(f"<p>{html.escape(p)}</p>" for p in paragraphs)

    subj = (tailored.cover_letter_subject or "").strip()
    if not subj:
        subj = f"Application: {job_title} — {_clean_company_name(company)}"
    elif not re.match(r"^(?:application|re)\b", subj, re.IGNORECASE):
        subj = f"Re: {subj}"

    subtitle = (tailored.tailored_headline or profile.headline or "").strip()
    today = datetime.now()
    date_str = f"{today.day} {today.strftime('%B %Y')}"

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<title>{html.escape(profile.full_name)} - Cover Letter</title>
<style>
  @page {{ size: A4; margin: 18mm 20mm 18mm 20mm; }}
  body {{
    font-family: "Segoe UI", Calibri, Arial, sans-serif;
    color: #1e2430;
    font-size: 10pt;
    line-height: 1.5;
    margin: 0;
  }}
  .header {{ border-bottom: 1.5px solid #2563eb; padding-bottom: 6px; margin-bottom: 16px; }}
  h1 {{ margin: 0; font-size: 16.5pt; letter-spacing: 0.4px; color: #0f172a; }}
  .headline {{ font-size: 9.6pt; font-weight: 600; color: #1d4ed8; margin: 1px 0 3px; }}
  .contact {{ font-size: 8.6pt; color: #334155; }}
  .date {{ font-size: 9.4pt; color: #475569; margin-bottom: 10px; }}
  .subject {{ font-weight: 700; color: #0f172a; margin-bottom: 14px; font-size: 10.4pt; }}
  .salutation {{ margin-bottom: 11px; font-weight: 600; color: #0f172a; }}
  p {{ margin: 0 0 11px; }}
  .signoff {{ margin-top: 16px; }}
  .sig-name {{ margin-top: 12px; font-weight: 700; color: #0f172a; }}
</style>
</head>
<body>
  <div class="header">
    <h1>{html.escape(profile.full_name.upper())}</h1>
    {f'<div class="headline">{html.escape(subtitle)}</div>' if subtitle else ''}
    <div class="contact">
      {html.escape(profile.phone)} · {html.escape(profile.email)} · {html.escape(profile.location)} · {html.escape(profile.linkedin)}
    </div>
  </div>
  <div class="date">{html.escape(date_str)}</div>
  <div class="subject">{html.escape(subj)}</div>
  <div class="salutation">{html.escape(salutation)}</div>
  {body_html}
  <div class="signoff">
    <div>{html.escape(closing)}</div>
    <div class="sig-name">{html.escape(profile.full_name)}</div>
  </div>
</body>
</html>
"""


async def render_html_to_pdf(html_path: Path, pdf_path: Path) -> bool:
    """Use installed Microsoft Edge or Google Chrome headless to print crisp A4 PDFs."""
    browser = _find_chromium_binary()
    if not browser:
        return False

    uri = html_path.resolve().as_uri()

    def _run() -> int:
        with tempfile.TemporaryDirectory(prefix="js_pdf_") as tmp_profile:
            cmd = [
                browser,
                "--headless",
                "--disable-gpu",
                "--no-sandbox",
                "--no-first-run",
                "--no-default-browser-check",
                f"--user-data-dir={tmp_profile}",
                "--no-pdf-header-footer",
                f"--print-to-pdf={pdf_path.resolve()}",
                uri,
            ]
            proc = subprocess.run(cmd, capture_output=True, text=True, timeout=45)
            return proc.returncode

    code = await asyncio.to_thread(_run)
    return code == 0 and pdf_path.exists() and pdf_path.stat().st_size > 1000


class ApplicationTailorService:
    def __init__(self, artifacts_root: Path = ARTIFACTS_DIR) -> None:
        self.artifacts_root = artifacts_root
        self.artifacts_root.mkdir(parents=True, exist_ok=True)

    async def analyze_fit(
        self,
        provider: LLMProvider,
        profile: CandidateProfile,
        job: dict[str, Any],
    ) -> LLMFitAnalysis:
        system_prompt = (
            "You are a candid, technically sharp Electrical & Electronics Engineering hiring advisor in New Zealand. "
            "Assess candidate fit strictly against the provided Verified Candidate Profile. "
            "Do NOT exaggerate or assume unstated experience. Note gaps honestly (e.g., if the job asks for 5+ yrs "
            "commercial experience, EWRB licence, or specialized tools not in the candidate's profile)."
        )
        user_prompt = (
            f"### VERIFIED CANDIDATE PROFILE (JSON)\n{profile.model_dump_json(indent=2)}\n\n"
            f"### TARGET JOB\n"
            f"Title: {job.get('title')}\n"
            f"Company: {job.get('company')}\n"
            f"Location: {job.get('location')}\n"
            f"Work Type: {job.get('work_type')}\n"
            f"Salary Label: {job.get('salary_label') or 'Not disclosed'}\n"
            f"Estimated Band: {job.get('salary_est_min')} - {job.get('salary_est_max')}\n\n"
            f"Full Job Description:\n{job.get('description_text') or job.get('teaser')}\n"
        )
        return await provider.generate_structured(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            response_schema=LLMFitAnalysis,
            temperature=0.2,
        )

    async def generate_tailored_package(
        self,
        provider: LLMProvider,
        profile: CandidateProfile,
        job: dict[str, Any],
        qa_entries: list[QABankEntry],
        extra_angle: str = "",
    ) -> dict[str, Any]:
        system_prompt = (
            "You are crafting a precision-tailored engineering CV, cover letter, and screening-question draft "
            "for a New Zealand engineering role.\n"
            "STRICT ANTI-HALLUCINATION RULES:\n"
            + "\n".join(f"- {r}" for r in profile.strict_truth_rules)
            + "\n- Keep the CV crisp and tightly formatted so it fits cleanly onto ONE A4 page (select top 3 most relevant projects, keep bullets punchy).\n"
            "- Cover letter must be crisp, human, specific to the employer's actual product/domain, and 220–320 words."
        )

        qa_summary = [
            {"key": q.canonical_key, "pattern": q.question_pattern, "answer": q.answer}
            for q in qa_entries
        ]
        questions = job.get("screening_questions") or []

        user_prompt = (
            f"### VERIFIED CANDIDATE PROFILE\n{profile.model_dump_json(indent=2)}\n\n"
            f"### CANONICAL SCREENING Q&A MEMORY BANK\n{json.dumps(qa_summary, indent=2)}\n\n"
            f"### TARGET JOB\n"
            f"Title: {job.get('title')}\n"
            f"Company: {job.get('company')}\n"
            f"Location: {job.get('location')}\n"
            f"Screening Questions on Form: {json.dumps(questions)}\n"
            f"Candidate Guidance / Angle: {extra_angle or 'Emphasize best authentic overlap with this role.'}\n\n"
            f"Job Description:\n{job.get('description_text') or job.get('teaser')}\n"
        )

        payload = await provider.generate_structured(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            response_schema=TailoredApplicationPayload,
            temperature=0.25,
        )

        # Render HTML & PDF artifacts into data/artifacts/<job_id>_<company>_<title>/
        return await self.render_artifacts_from_payload(
            profile=profile,
            payload=payload,
            job=job,
            model_used=provider.current_model_name(),
        )

    async def render_artifacts_from_payload(
        self,
        *,
        profile: CandidateProfile,
        payload: TailoredApplicationPayload,
        job: dict[str, Any],
        model_used: str = "",
    ) -> dict[str, Any]:
        """Render CV and Cover Letter HTML/PDF from an already-generated TailoredApplicationPayload."""
        folder_name = f"job_{job['id']}_{_slugify(job.get('company', ''))}_{_slugify(job.get('title', ''))}"
        out_dir = self.artifacts_root / folder_name
        out_dir.mkdir(parents=True, exist_ok=True)

        cv_html = render_cv_html(profile, payload)
        cl_html = render_cover_letter_html(
            profile,
            payload,
            job_title=job.get("title", ""),
            company=job.get("company", ""),
        )

        base_name = f"{_slugify(profile.full_name)}_{_slugify(job.get('company', ''))}_{_slugify(job.get('title', ''))}"
        cv_html_path = out_dir / f"{base_name}_CV.html"
        cv_pdf_path = out_dir / f"{base_name}_CV.pdf"
        cl_html_path = out_dir / f"{base_name}_Cover_Letter.html"
        cl_pdf_path = out_dir / f"{base_name}_Cover_Letter.pdf"
        meta_path = out_dir / "tailored_package.json"

        cv_html_path.write_text(cv_html, encoding="utf-8")
        cl_html_path.write_text(cl_html, encoding="utf-8")
        meta_path.write_text(payload.model_dump_json(indent=2), encoding="utf-8")

        cv_pdf_ok, cl_pdf_ok = await asyncio.gather(
            render_html_to_pdf(cv_html_path, cv_pdf_path),
            render_html_to_pdf(cl_html_path, cl_pdf_path),
        )

        return {
            "payload": payload.model_dump(),
            "artifact_dir": str(out_dir),
            "cv_html_path": str(cv_html_path),
            "cv_pdf_path": str(cv_pdf_path) if cv_pdf_ok else None,
            "cover_letter_html_path": str(cl_html_path),
            "cover_letter_pdf_path": str(cl_pdf_path) if cl_pdf_ok else None,
            "model_used": model_used,
        }

    async def audit_profile(
        self,
        provider: LLMProvider,
        profile: CandidateProfile,
        recent_jobs: list[dict[str, Any]],
    ) -> ProfileAuditSuggestions:
        system_prompt = (
            "You are a candid technical career coach reviewing an Electrical/Electronics & Embedded Engineer's "
            "master profile against recent target job postings in New Zealand. "
            "Identify concrete skills, tools, keywords, or project details they likely already have or should "
            "highlight more clearly, without inventing fake experience."
        )
        jobs_snippet = "\n".join(
            f"- {j.get('title')} @ {j.get('company')}: {(j.get('teaser') or j.get('description_text') or '')[:250]}"
            for j in recent_jobs[:15]
        )
        user_prompt = (
            f"### CANDIDATE PROFILE\n{profile.model_dump_json(indent=2)}\n\n"
            f"### RECENT TARGET JOBS\n{jobs_snippet or '(No jobs scanned yet — review against general NZ EE/Embedded/Test roles)'}\n"
        )
        return await provider.generate_structured(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            response_schema=ProfileAuditSuggestions,
            temperature=0.3,
        )
