"""HTML/text cleaning and rule-based job scoring & guardrail filtering."""
from __future__ import annotations

import hashlib
import html
import re
from typing import Iterable

from .models import NormalizedJob, ScannerConfig, ScoreBreakdown

_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"\s+")
_YEARS_RE = re.compile(
    r"\b(?:minimum(?:\s+of)?\s+|at\s+least\s+)?(\d{1,2})\s*(?:\+|plus)?\s*(?:-|to)?\s*(\d{1,2})?\s*years?(?:\s*\+\s*)?(?:\s+of)?\s+(?:[\w/-]+\s+){0,3}?experience\b",
    re.I,
)


def html_to_clean_text(raw_html: str) -> str:
    if not raw_html:
        return ""
    text = re.sub(r"</(p|li|div|h[1-6]|br|tr)>", "\n", raw_html, flags=re.I)
    text = re.sub(r"<li[^>]*>", "• ", text, flags=re.I)
    text = _TAG_RE.sub(" ", text)
    text = html.unescape(text)
    lines = [_WS_RE.sub(" ", line).strip() for line in text.splitlines()]
    return "\n".join(line for line in lines if line)


def compute_dedup_key(title: str, company: str, location: str) -> str:
    """Cross-board canonical fingerprint based on normalized title + company + city."""
    def norm(s: str) -> str:
        s = s.lower().strip()
        s = re.sub(r"\b(ltd|limited|pty|inc|corp|corporation|nz|new zealand)\b", "", s)
        s = re.sub(r"[^a-z0-9]+", " ", s)
        return _WS_RE.sub(" ", s).strip()

    city = norm(location.split(",")[-1] if location else "")
    payload = f"{norm(title)}|{norm(company)}|{city}"
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:24]


def _word_match(term: str, haystack: str) -> bool:
    escaped = re.escape(term.lower())
    if re.match(r"^[a-z0-9]", term, re.I) and re.search(r"[a-z0-9]$", term, re.I):
        return re.search(rf"(?<![a-z0-9]){escaped}(?![a-z0-9])", haystack) is not None
    return term.lower() in haystack


def _contains_any(terms: Iterable[str], text: str) -> list[str]:
    lowered = text.lower()
    return [t for t in terms if t and _word_match(t, lowered)]


def score_job(job: NormalizedJob, config: ScannerConfig) -> ScoreBreakdown:
    rules = config.scoring
    company_lower = (job.company or "").lower()
    title_lower = (job.title or "").lower()
    body_text = job.description_text or html_to_clean_text(job.description_html) or job.teaser
    full_text = f"{job.title}\n{job.company}\n{job.location}\n{' '.join(job.bullet_points)}\n{job.teaser}\n{body_text}".lower()

    # 1. Employer / Subsidiary Blocklist Check (Critical safety guardrail)
    for blocked in config.blocked_companies:
        if blocked.strip() and blocked.strip().lower() in company_lower:
            return ScoreBreakdown(
                score=-999,
                blocked_reason=f"Blocked employer match: '{blocked}' in company '{job.company}'",
            )
    for kw in config.blocked_keywords_in_company:
        if kw.strip() and kw.strip().lower() in company_lower:
            return ScoreBreakdown(
                score=-999,
                blocked_reason=f"Blocked company keyword: '{kw}' in '{job.company}'",
            )
    # Also catch cases where a recruitment agency / anonymous listing mentions a blocked company in the job body
    is_manual_import = "Manual Link" in (job.matched_queries or [])
    if (job.is_agency or company_lower in ("", "private advertiser")) and not is_manual_import:
        for blocked in config.blocked_companies:
            b = blocked.strip().lower()
            if b and len(b) >= 5 and _word_match(b, body_text.lower()):
                return ScoreBreakdown(
                    score=-999,
                    blocked_reason=f"Blocked employer mentioned in agency description: '{blocked}'",
                )

    score = 20  # Baseline for matching search query
    matched_pos: list[str] = []
    matched_neg: list[str] = []
    flags: list[str] = []
    notes: list[str] = []

    # 2. Hard title & body exclusions (seniority/domain mismatches)
    title_excludes = _contains_any(rules.hard_exclude_title_terms, title_lower)
    body_excludes = _contains_any(rules.hard_exclude_body_terms, body_text.lower())
    hard_hits = [f"title:{t}" for t in title_excludes] + [f"body:{b}" for b in body_excludes]
    if title_excludes:
        score -= 45 * len(title_excludes)
        notes.append(f"Excluded title term(s): {', '.join(title_excludes)}")
    if body_excludes:
        score -= 30 * len(body_excludes)
        notes.append(f"Excluded body term(s): {', '.join(body_excludes)}")

    # 3. Positive keyword scoring (title hits count extra; capped so 100 means genuine top match)
    pos_points = 0
    for kw, weight in rules.positive_keywords.items():
        if _word_match(kw, title_lower):
            bonus = weight + max(3, weight // 2)
            pos_points += bonus
            matched_pos.append(f"{kw} (title +{bonus})")
        elif _word_match(kw, full_text):
            pos_points += weight
            matched_pos.append(f"{kw} (+{weight})")
    score += min(70, pos_points)

    # 4. Negative keyword scoring
    for kw, penalty in rules.negative_keywords.items():
        if _word_match(kw, title_lower):
            p = abs(penalty) + 12
            score -= p
            matched_neg.append(f"{kw} (title -{p})")
        elif _word_match(kw, full_text):
            p = abs(penalty)
            score -= p
            matched_neg.append(f"{kw} (-{p})")

    # 5. Location boost
    loc_lower = (job.location or "").lower()
    for loc_kw, loc_pts in rules.preferred_locations.items():
        if loc_kw.lower() in loc_lower:
            score += loc_pts
            notes.append(f"Location '{loc_kw}' ({loc_pts:+d})")
            break

    # 6. Agency penalty (direct employers preferred)
    if job.is_agency:
        score += config.recruitment_agency_penalty
        flags.append("Recruitment Agency")

    # 7. Experience years heuristic (detects "5+ years" or "minimum 5 years")
    exp_years_found: list[int] = []
    for m in _YEARS_RE.finditer(body_text):
        y1 = int(m.group(1))
        y2 = int(m.group(2)) if m.group(2) else y1
        if y1 <= 20:
            exp_years_found.append(min(y1, y2))
    if exp_years_found:
        req_years = max(exp_years_found)
        if req_years > rules.max_experience_years:
            penalty = min(35, (req_years - rules.max_experience_years) * 8)
            score -= penalty
            flags.append(f"{req_years}+ yrs exp")
            notes.append(f"Requests {req_years}+ yrs experience (-{penalty})")

    # 7b. Intern / student penalty if graduated & employed full-time
    if _word_match("intern", title_lower) or _word_match("internship", title_lower):
        score -= 22
        flags.append("Internship")
        notes.append("Internship title (-22)")

    # 8. Salary floor check (if known)
    sal_top = job.salary_est_max or job.salary_est_min
    if sal_top and sal_top < rules.min_acceptable_salary:
        score -= 20
        flags.append(f"Below ${rules.min_acceptable_salary // 1000}k")

    # 9. Helpful informational flags
    if any(w in full_text for w in ["nz citizen", "citizenship", "security clearance"]):
        flags.append("NZ Citizen / Clearance Fit")
    if any(w in full_text for w in ["mandarin", "chinese", "bilingual"]):
        score += 10
        flags.append("Mandarin Bonus")
    if job.is_link_out:
        flags.append("External ATS")
    else:
        flags.append("Seek Quick Apply")

    clamped = max(0, min(100, score))
    return ScoreBreakdown(
        score=clamped,
        matched_positive=matched_pos,
        matched_negative=matched_neg,
        hard_exclude_hits=hard_hits,
        flags=flags,
        notes=notes,
    )
