"""End-to-end scan orchestrator (Search -> Deduplicate -> GraphQL Detail -> Rule Score -> Salary Probe -> SQLite)."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timezone
import hashlib
import html
import re
from typing import Any
from urllib.parse import urlparse

from .config import ConfigManager
from .models import ExtractedJDMetadata, JobStatus, NormalizedJob, ScannerConfig, ScanRunSummary
from .scoring import html_to_clean_text, score_job
from .sources import get_adapter
from .sources.seek import parse_seek_url_or_id
from .storage import JobStore


def _extract_url_domain(url: str) -> str:
    raw = (url or "").strip()
    if not raw:
        return ""
    if not re.match(r"^https?://", raw, re.I):
        raw = "https://" + raw
    try:
        host = (urlparse(raw).netloc or "").lower()
        host = host.split(":")[0]
        if host.startswith("www."):
            host = host[4:]
        return host
    except Exception:
        return ""


def parse_salary_text(raw: str) -> tuple[str, int | None, int | None]:
    """Parse human salary strings like '$85k - $105k' or '$90,000–$110,000' into label + annual min/max."""
    text = (raw or "").strip()
    if not text:
        return "", None, None

    range_m = re.search(
        r"(?:NZ\$|AU\$|\$|NZD|AUD)?\s*(\d{2,3}(?:,\d{3})*(?:\.\d+)?|\d+(?:\.\d+)?)\s*(k)?\s*(?:-|–|—|to)\s*(?:NZ\$|AU\$|\$|NZD|AUD)?\s*(\d{2,3}(?:,\d{3})*(?:\.\d+)?|\d+(?:\.\d+)?)\s*(k)?",
        text,
        re.I,
    )
    if range_m:
        v1 = float(range_m.group(1).replace(",", ""))
        v2 = float(range_m.group(3).replace(",", ""))
        k1 = bool(range_m.group(2))
        k2 = bool(range_m.group(4))
        if k1 or (not k1 and k2 and v1 < 1000):
            v1 *= 1000
        if k2 or (not k2 and k1 and v2 < 1000):
            v2 *= 1000
        smin, smax = int(round(min(v1, v2))), int(round(max(v1, v2)))
        if 25000 <= smin <= 600000 and 25000 <= smax <= 600000:
            return range_m.group(0).strip(), smin, smax
        return range_m.group(0).strip(), None, None

    single_m = re.search(
        r"(?:NZ\$|AU\$|\$|NZD|AUD)\s*(\d{2,3}(?:,\d{3})+|\d{2,3}\s*k)\b",
        text,
        re.I,
    )
    if single_m:
        num_str = single_m.group(1).lower().replace(",", "").strip()
        val = float(num_str[:-1]) * 1000 if num_str.endswith("k") else float(num_str)
        sval = int(round(val))
        if 25000 <= sval <= 600000:
            return single_m.group(0).strip(), sval, sval
        return single_m.group(0).strip(), None, None

    return "", None, None


def format_pasted_jd(raw_jd: str) -> tuple[str, str, list[str], str]:
    """Convert raw pasted JD (plain text or HTML) into (description_html, description_text, bullets, teaser)."""
    raw = (raw_jd or "").strip()
    if not raw:
        return "", "", [], ""

    looks_like_html = bool(re.search(r"<(p|div|ul|ol|li|br|h[1-6])\b", raw, re.I))
    if looks_like_html:
        cleaned_html = re.sub(r"<(script|style)[^>]*>.*?</\1>", "", raw, flags=re.I | re.S)
        description_text = html_to_clean_text(cleaned_html)
        description_html = cleaned_html
    else:
        lines = [ln.rstrip() for ln in raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")]
        html_parts: list[str] = []
        in_list = False
        clean_lines: list[str] = []
        bullet_re = re.compile(r"^\s*(?:[•\-\*·–▪‣◦]|\d+[.)])\s+(.+)$")

        for ln in lines:
            stripped = ln.strip()
            if not stripped:
                if in_list:
                    html_parts.append("</ul>")
                    in_list = False
                continue

            m_b = bullet_re.match(stripped)
            if m_b:
                item = m_b.group(1).strip()
                if not in_list:
                    html_parts.append("<ul>")
                    in_list = True
                html_parts.append(f"<li>{html.escape(item)}</li>")
                clean_lines.append(f"• {item}")
            else:
                if in_list:
                    html_parts.append("</ul>")
                    in_list = False
                is_heading = (
                    len(stripped) <= 64
                    and not stripped.endswith(".")
                    and (
                        stripped.endswith(":")
                        or re.match(
                            r"^(?:about\s+(?:the\s+role|us|you|the\s+company)|responsibilities|key\s+responsibilities|requirements|what\s+you(?:'ll|\s+will)\s+(?:do|bring)|who\s+you\s+are|skills|qualifications|benefits|why\s+join)\b",
                            stripped,
                            re.I,
                        )
                    )
                )
                if is_heading:
                    html_parts.append(f"<h3>{html.escape(stripped)}</h3>")
                else:
                    html_parts.append(f"<p>{html.escape(stripped)}</p>")
                clean_lines.append(stripped)

        if in_list:
            html_parts.append("</ul>")
        description_html = "\n".join(html_parts)
        description_text = "\n".join(clean_lines)

    bullets = [
        ln[2:].strip()
        for ln in description_text.splitlines()
        if ln.startswith("• ") and len(ln) > 10
    ][:5]
    teaser_src = " ".join(
        ln for ln in description_text.splitlines() if not ln.startswith("• ") and len(ln) > 20
    ) or description_text
    teaser = re.sub(r"\s+", " ", teaser_src).strip()[:240]
    return description_html, description_text, bullets, teaser


def _heuristic_extract_jd(description_text: str) -> dict[str, Any]:
    """Best-effort deterministic extraction of Title, Company, Location, Work Type, Salary."""
    lines = [ln.strip() for ln in description_text.splitlines() if ln.strip() and not ln.startswith("• ")]
    title = ""
    company = ""
    location = ""
    work_type = ""
    work_arrangement = ""

    if lines:
        first = lines[0]
        if " at " in first and len(first) <= 100:
            t, c = first.split(" at ", 1)
            title, company = t.strip(), c.strip()
        elif " - " in first and len(first) <= 100:
            parts = [p.strip() for p in first.split(" - ", 2)]
            if len(parts) >= 2:
                title, company = parts[0], parts[1]
                if len(parts) == 3:
                    location = parts[2]
        elif " @ " in first and len(first) <= 100:
            t, c = first.split(" @ ", 1)
            title, company = t.strip(), c.strip()
        else:
            title = first[:100]
            if len(lines) > 1 and len(lines[1]) <= 70 and not re.match(r"^(about|overview|summary|description|we\s+are)\b", lines[1], re.I):
                second = lines[1]
                if " · " in second:
                    segs = [s.strip() for s in second.split(" · ")]
                    company = segs[0]
                    if len(segs) > 1:
                        location = segs[1]
                else:
                    company = second

    head_blob = "\n".join(lines[:12])
    if not location:
        loc_m = re.search(
            r"\b(Auckland|Wellington|Christchurch|Hamilton|Tauranga|Dunedin|New Zealand|Sydney|Melbourne|Brisbane|Perth|Remote)\b[^\n,]{0,25}",
            head_blob,
            re.I,
        )
        if loc_m:
            location = loc_m.group(0).strip(" ,·|")

    full_low = description_text.lower()
    if re.search(r"\bfull[\s-]?time\b", full_low):
        work_type = "Full time"
    elif re.search(r"\bpart[\s-]?time\b", full_low):
        work_type = "Part time"
    elif re.search(r"\b(?:contract|fixed[\s-]?term|casual|temp)\b", full_low):
        work_type = "Contract/Temp"

    if re.search(r"\bhybrid\b", full_low):
        work_arrangement = "Hybrid"
    elif re.search(r"\bremote\b", full_low):
        work_arrangement = "Remote"
    elif re.search(r"\bon[\s-]?site\b", full_low):
        work_arrangement = "On-site"

    sal_label, sal_min, sal_max = parse_salary_text(description_text[:2000])
    return {
        "title": title,
        "company": company,
        "location": location,
        "work_type": work_type,
        "work_arrangement": work_arrangement,
        "salary_label": sal_label,
        "salary_est_min": sal_min,
        "salary_est_max": sal_max,
    }


@dataclass
class LiveScanState:
    is_running: bool = False
    stage: str = "idle"
    message: str = "Ready"
    queries_done: int = 0
    queries_total: int = 0
    fetched: int = 0
    new_jobs: int = 0
    details_done: int = 0
    salaries_done: int = 0
    last_summary: dict[str, Any] | None = None
    errors: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "is_running": self.is_running,
            "stage": self.stage,
            "message": self.message,
            "queries_done": self.queries_done,
            "queries_total": self.queries_total,
            "fetched": self.fetched,
            "new_jobs": self.new_jobs,
            "details_done": self.details_done,
            "salaries_done": self.salaries_done,
            "last_summary": self.last_summary,
            "errors": self.errors[-10:],
        }


class ScannerOrchestrator:
    def __init__(self, store: JobStore, config_mgr: ConfigManager) -> None:
        self.store = store
        self.config_mgr = config_mgr
        self.state = LiveScanState()
        self._lock = asyncio.Lock()

    async def run_scan(
        self,
        *,
        source_id: str | None = None,
        query_names: list[str] | None = None,
        max_auto_salary_probes: int = 8,
    ) -> ScanRunSummary:
        if self._lock.locked():
            raise RuntimeError("A scan is already in progress.")

        async with self._lock:
            config = self.config_mgr.load()
            board_id = source_id or config.default_board
            run_id = self.store.create_scan_run(board_id)
            summary = ScanRunSummary(
                run_id=run_id,
                started_at=datetime.now(timezone.utc),
                source=board_id,
            )

            enabled_queries = [
                q for q in config.queries
                if q.enabled and (not query_names or q.name in query_names)
            ]

            self.state = LiveScanState(
                is_running=True,
                stage="searching",
                message=f"Starting scan across {len(enabled_queries)} search profile(s)...",
                queries_total=len(enabled_queries),
            )

            adapter = get_adapter(board_id, request_delay_ms=config.request_delay_ms)
            try:
                # 1. Collect & merge search results by external_id within this batch
                batch: dict[str, NormalizedJob] = {}
                for idx, q in enumerate(enabled_queries, start=1):
                    self.state.message = f"[{idx}/{len(enabled_queries)}] Scanning '{q.name}'..."
                    try:
                        async for item in adapter.search(q):
                            summary.total_fetched += 1
                            self.state.fetched = summary.total_fetched
                            if item.external_id in batch:
                                existing_item = batch[item.external_id]
                                for mq in item.matched_queries:
                                    if mq not in existing_item.matched_queries:
                                        existing_item.matched_queries.append(mq)
                            else:
                                batch[item.external_id] = item
                        summary.queries_run += 1
                    except Exception as exc:
                        err = f"Query '{q.name}' failed: {exc}"
                        summary.errors.append(err)
                        self.state.errors.append(err)
                    self.state.queries_done = idx

                # 2. Enrich details for new listings (or existing ones missing description) & upsert
                self.state.stage = "enriching"
                probe_candidates: list[tuple[int, NormalizedJob, int]] = []

                items_list = list(batch.values())
                for i, job in enumerate(items_list, start=1):
                    db_existing = self.store.get_existing_job(job.source, job.external_id)
                    needs_detail = config.fetch_details_for_all_new and (
                        db_existing is None or not db_existing.get("description_text")
                    )

                    # Quick pre-check blocklist on company name before spending detail request
                    pre_score = score_job(job, config)
                    if needs_detail and not pre_score.blocked_reason:
                        self.state.message = f"Fetching job details ({i}/{len(items_list)}): {job.title[:45]}..."
                        try:
                            job = await adapter.enrich_detail(job)
                            summary.details_fetched += 1
                            self.state.details_done = summary.details_fetched
                        except Exception as exc:
                            err = f"Detail fetch failed for {job.external_id}: {exc}"
                            summary.errors.append(err)
                            self.state.errors.append(err)
                    elif db_existing and db_existing.get("description_text"):
                        # Carry existing description & salary forward for accurate scoring
                        job.description_html = db_existing.get("description_html") or ""
                        job.description_text = db_existing.get("description_text") or ""
                        job.is_link_out = bool(db_existing.get("is_link_out"))
                        if job.salary_est_min is None:
                            job.salary_est_min = db_existing.get("salary_est_min")
                            job.salary_est_max = db_existing.get("salary_est_max")

                    final_breakdown = score_job(job, config)
                    row_id, is_new = self.store.upsert_job(job, final_breakdown)
                    if is_new:
                        summary.new_jobs += 1
                        self.state.new_jobs = summary.new_jobs
                    else:
                        summary.updated_jobs += 1
                    if final_breakdown.blocked_reason:
                        summary.blocked_jobs += 1

                    # Queue top unprobed matches for automatic Seek hidden salary estimation
                    already_has_salary = (
                        job.salary_est_min is not None
                        or (db_existing and db_existing.get("salary_probed_at"))
                    )
                    if (
                        adapter.supports_salary_probe
                        and not final_breakdown.blocked_reason
                        and not already_has_salary
                        and final_breakdown.score >= config.probe_salary_above_score
                    ):
                        probe_candidates.append((row_id, job, final_breakdown.score))

                # 3. Probe hidden salary for top N new/unprobed matches
                if probe_candidates and max_auto_salary_probes > 0:
                    self.state.stage = "probing_salary"
                    probe_candidates.sort(key=lambda x: x[2], reverse=True)
                    top_to_probe = probe_candidates[:max_auto_salary_probes]
                    for idx, (row_id, job, _) in enumerate(top_to_probe, start=1):
                        self.state.message = (
                            f"Estimating hidden Seek salary ({idx}/{len(top_to_probe)}): {job.title[:40]}..."
                        )
                        try:
                            smin, smax = await adapter.probe_salary(job)
                            self.store.update_job_salary(row_id, smin, smax)
                            if smin is not None:
                                job.salary_est_min, job.salary_est_max = smin, smax
                                updated_bd = score_job(job, config)
                                self.store.update_job_score(row_id, updated_bd)
                                summary.salaries_probed += 1
                                self.state.salaries_done = summary.salaries_probed
                        except Exception as exc:
                            err = f"Salary probe failed for {job.external_id}: {exc}"
                            summary.errors.append(err)
                            self.state.errors.append(err)

                summary.finished_at = datetime.now(timezone.utc)
                self.store.finish_scan_run(summary)
                self.state.stage = "done"
                self.state.message = (
                    f"Scan complete — {summary.new_jobs} new, {summary.updated_jobs} updated, "
                    f"{summary.blocked_jobs} blocked."
                )
                self.state.last_summary = summary.model_dump(mode="json")
                return summary
            finally:
                self.state.is_running = False
                await adapter.aclose()

    async def probe_single_job_salary(self, job_id: int) -> dict[str, Any]:
        row = self.store.get_job(job_id)
        if not row:
            raise KeyError(f"Job {job_id} not found")
        if row.get("source") == "manual":
            raise ValueError("Hidden salary estimation is only supported for SEEK jobs (this job was added via Paste JD).")
        config = self.config_mgr.load()
        adapter = get_adapter(row["source"], request_delay_ms=config.request_delay_ms)
        if not adapter.supports_salary_probe:
            await adapter.aclose()
            raise ValueError(f"Hidden salary estimation is only supported for SEEK jobs (this job is '{row['source']}').")
        try:
            norm = self._row_to_normalized(row)
            smin, smax = await adapter.probe_salary(norm)
            self.store.update_job_salary(job_id, smin, smax)
            norm.salary_est_min, norm.salary_est_max = smin, smax
            bd = score_job(norm, config)
            self.store.update_job_score(job_id, bd)
            return self.store.get_job(job_id) or row
        finally:
            await adapter.aclose()

    async def enrich_single_job_detail(self, job_id: int) -> dict[str, Any]:
        row = self.store.get_job(job_id)
        if not row:
            raise KeyError(f"Job {job_id} not found")
        config = self.config_mgr.load()
        if row.get("source") == "manual":
            norm = self._row_to_normalized(row)
            bd = score_job(norm, config)
            self.store.update_job_score(job_id, bd)
            return self.store.get_job(job_id) or row
        adapter = get_adapter(row["source"], request_delay_ms=config.request_delay_ms)
        try:
            norm = self._row_to_normalized(row)
            norm = await adapter.enrich_detail(norm)
            bd = score_job(norm, config)
            self.store.upsert_job(norm, bd)
            return self.store.get_job(job_id) or row
        finally:
            await adapter.aclose()

    async def import_job_from_url(
        self,
        url_or_id: str,
        *,
        probe_salary: bool = True,
    ) -> dict[str, Any]:
        """Fetch a single SEEK job by URL/ID (even if not found by search queries), score it, and save to SQLite."""
        config = self.config_mgr.load()
        source_id, external_id = parse_seek_url_or_id(url_or_id, default_board=config.default_board)
        adapter = get_adapter(source_id, request_delay_ms=config.request_delay_ms)
        try:
            existing = self.store.get_existing_job(source_id, external_id)
            if existing:
                norm = self._row_to_normalized(existing)
                norm.url = f"{adapter.job_url_prefix}/{external_id}"
                if "Manual Link" not in norm.matched_queries:
                    norm.matched_queries.append("Manual Link")
            else:
                norm = NormalizedJob(
                    source=source_id,
                    external_id=external_id,
                    url=f"{adapter.job_url_prefix}/{external_id}",
                    title="",
                    company="",
                    matched_queries=["Manual Link"],
                )

            norm = await adapter.enrich_detail(norm)
            bd = score_job(norm, config)
            row_id, _ = self.store.upsert_job(norm, bd)

            if probe_salary and adapter.supports_salary_probe and not bd.blocked_reason and norm.salary_est_min is None:
                try:
                    smin, smax = await adapter.probe_salary(norm)
                    self.store.update_job_salary(row_id, smin, smax)
                    if smin is not None:
                        norm.salary_est_min, norm.salary_est_max = smin, smax
                        bd = score_job(norm, config)
                        self.store.update_job_score(row_id, bd)
                except Exception:
                    pass

            saved = self.store.get_job(row_id)
            if saved and not saved.get("blocked_reason") and saved.get("status") == JobStatus.DISCOVERED.value:
                saved = self.store.update_job_triage(row_id, status=JobStatus.SHORTLISTED.value)
            return saved or self.store.get_job(row_id)  # type: ignore[return-value]
        finally:
            await adapter.aclose()

    async def import_pasted_job(
        self,
        *,
        description_text: str,
        title: str = "",
        company: str = "",
        location: str = "",
        work_type: str = "",
        work_arrangement: str = "",
        salary_label: str = "",
        url: str = "",
        screening_questions: list[str] | None = None,
        use_ai_extract: bool = True,
        llm_provider: Any = None,
    ) -> dict[str, Any]:
        """Store a manually pasted non-SEEK JD as a full NormalizedJob, score it, and save to SQLite."""
        raw_jd = (description_text or "").strip()
        if len(raw_jd) < 20:
            raise ValueError("Please paste the job description text (at least a few lines).")

        config = self.config_mgr.load()
        desc_html, clean_text, bullets, teaser = format_pasted_jd(raw_jd)
        heur = _heuristic_extract_jd(clean_text)

        title_val = (title or "").strip()
        company_val = (company or "").strip()
        loc_val = (location or "").strip()
        wt_val = (work_type or "").strip()
        wa_val = (work_arrangement or "").strip()
        sal_val = (salary_label or "").strip()
        user_questions = [q.strip() for q in (screening_questions or []) if q and q.strip()]

        ai_meta: ExtractedJDMetadata | None = None
        needs_ai = not (title_val and company_val and loc_val and sal_val)
        if use_ai_extract and llm_provider is not None and needs_ai:
            try:
                ai_meta = await llm_provider.generate_structured(
                    system_prompt=(
                        "Extract structured metadata from the pasted job posting. "
                        "Only extract values explicitly present in the text; leave missing fields empty or null."
                    ),
                    user_prompt=f"URL: {url.strip() or 'N/A'}\n\nJOB POSTING TEXT:\n{clean_text[:8000]}",
                    response_schema=ExtractedJDMetadata,
                    temperature=0.1,
                )
            except Exception:
                ai_meta = None

        final_title = title_val or (ai_meta.title if ai_meta and ai_meta.title else "") or heur["title"] or "Untitled Role"
        final_company = company_val or (ai_meta.company if ai_meta and ai_meta.company else "") or heur["company"] or "Unknown Company"
        final_location = loc_val or (ai_meta.location if ai_meta and ai_meta.location else "") or heur["location"]
        final_work_type = wt_val or (ai_meta.work_type if ai_meta and ai_meta.work_type else "") or heur["work_type"]
        final_work_arr = wa_val or (ai_meta.work_arrangement if ai_meta and ai_meta.work_arrangement else "") or heur["work_arrangement"]

        sal_source = sal_val or (ai_meta.salary_label if ai_meta and ai_meta.salary_label else "") or heur["salary_label"]
        parsed_sal_label, sal_min, sal_max = parse_salary_text(sal_source)
        final_sal_label = sal_val or (ai_meta.salary_label if ai_meta and ai_meta.salary_label else "") or parsed_sal_label or heur["salary_label"]
        if sal_min is None and ai_meta and ai_meta.salary_est_min is not None:
            sal_min = ai_meta.salary_est_min
        if sal_max is None and ai_meta and ai_meta.salary_est_max is not None:
            sal_max = ai_meta.salary_est_max
        if sal_min is None and heur["salary_est_min"] is not None:
            sal_min = heur["salary_est_min"]
            sal_max = heur["salary_est_max"]

        final_bullets = bullets or (ai_meta.bullet_points[:5] if ai_meta else [])
        final_questions = user_questions or (ai_meta.screening_questions if ai_meta else [])

        clean_url = (url or "").strip()
        if clean_url and not re.match(r"^https?://", clean_url, re.I):
            clean_url = "https://" + clean_url
        domain = _extract_url_domain(clean_url)

        if clean_url:
            ext_key = clean_url.lower().rstrip("/")
        else:
            ext_key = f"{final_title.lower()}|{final_company.lower()}|{clean_text[:500].lower()}"
        external_id = "paste_" + hashlib.sha256(ext_key.encode("utf-8")).hexdigest()[:16]

        matched_queries = ["Manual JD"]
        if domain:
            matched_queries.append(domain)

        existing = self.store.get_existing_job("manual", external_id)
        now_iso = datetime.now(timezone.utc).isoformat()
        if existing:
            norm = self._row_to_normalized(existing)
            norm.url = clean_url or norm.url
            norm.title = final_title
            norm.company = final_company
            norm.location = final_location
            norm.work_type = final_work_type
            norm.work_arrangement = final_work_arr
            norm.teaser = teaser
            norm.bullet_points = final_bullets
            norm.description_html = desc_html
            norm.description_text = clean_text
            norm.salary_label = final_sal_label
            norm.salary_est_min = sal_min
            norm.salary_est_max = sal_max
            norm.is_link_out = True
            norm.screening_questions = final_questions
            for mq in matched_queries:
                if mq not in norm.matched_queries:
                    norm.matched_queries.append(mq)
        else:
            norm = NormalizedJob(
                source="manual",
                external_id=external_id,
                url=clean_url,
                title=final_title,
                company=final_company,
                location=final_location,
                work_type=final_work_type,
                work_arrangement=final_work_arr,
                teaser=teaser,
                bullet_points=final_bullets,
                description_html=desc_html,
                description_text=clean_text,
                salary_label=final_sal_label,
                salary_est_min=sal_min,
                salary_est_max=sal_max,
                posted_at=now_iso,
                is_link_out=True,
                screening_questions=final_questions,
                matched_queries=matched_queries,
                raw_search_json={"manual_paste": True, "origin_domain": domain},
            )

        bd = score_job(norm, config)
        row_id, _ = self.store.upsert_job(norm, bd)
        saved = self.store.get_job(row_id)
        if saved and not saved.get("blocked_reason") and saved.get("status") == JobStatus.DISCOVERED.value:
            saved = self.store.update_job_triage(row_id, status=JobStatus.SHORTLISTED.value)
        return saved or self.store.get_job(row_id)  # type: ignore[return-value]

    def rescore_all_jobs(self, config: ScannerConfig | None = None) -> int:
        cfg = config or self.config_mgr.load()
        rows = self.store.all_jobs_for_rescore()
        for row in rows:
            norm = self._row_to_normalized(row)
            bd = score_job(norm, cfg)
            self.store.update_job_score(row["id"], bd)
        return len(rows)

    async def check_and_prune_expired_jobs(self, max_non_pipeline_seek: int = 30) -> dict[str, Any]:
        """Check SEEK GraphQL availability for active pipeline jobs and recent non-pipeline jobs, then flag/prune expired ones."""
        candidates = self.store.list_jobs_for_expiry_check(max_non_pipeline_seek=max_non_pipeline_seek)
        seek_candidates = [
            c for c in candidates
            if c.get("source") in ("seek_nz", "seek_au") and not c.get("is_expired")
        ]

        live_results: list[dict[str, Any]] = []
        if seek_candidates:
            adapters: dict[str, Any] = {}
            sem = asyncio.Semaphore(8)
            try:
                for src in {c["source"] for c in seek_candidates}:
                    try:
                        adapters[src] = get_adapter(src, request_delay_ms=0)
                    except Exception:
                        pass

                async def _probe_one(row: dict[str, Any]) -> dict[str, Any] | None:
                    adapter = adapters.get(row["source"])
                    if not adapter or not hasattr(adapter, "check_availability"):
                        return None
                    async with sem:
                        try:
                            is_exp, exp_at = await asyncio.wait_for(
                                adapter.check_availability(str(row["external_id"])),
                                timeout=8.0,
                            )
                            return {
                                "id": row["id"],
                                "is_expired": is_exp,
                                "expires_at": exp_at,
                            }
                        except Exception:
                            return None

                probed = await asyncio.gather(*(_probe_one(c) for c in seek_candidates))
                live_results = [r for r in probed if r is not None]
            finally:
                for ad in adapters.values():
                    try:
                        await ad.aclose()
                    except Exception:
                        pass

        applied = self.store.apply_expiry_results(live_results=live_results)
        return {
            "availability_checked": len(live_results),
            **applied,
        }

    @staticmethod
    def _row_to_normalized(row: dict[str, Any]) -> NormalizedJob:
        return NormalizedJob(
            source=row["source"],
            external_id=row["external_id"],
            url=row["url"],
            title=row["title"],
            company=row["company"],
            advertiser_id=row.get("advertiser_id"),
            is_agency=bool(row.get("is_agency")),
            location=row.get("location") or "",
            work_type=row.get("work_type") or "",
            work_arrangement=row.get("work_arrangement") or "",
            classifications=row.get("classifications") or [],
            teaser=row.get("teaser") or "",
            bullet_points=row.get("bullet_points") or [],
            description_html=row.get("description_html") or "",
            description_text=row.get("description_text") or "",
            salary_label=row.get("salary_label") or "",
            salary_est_min=row.get("salary_est_min"),
            salary_est_max=row.get("salary_est_max"),
            posted_at=row.get("posted_at"),
            expires_at=row.get("expires_at"),
            is_link_out=bool(row.get("is_link_out")),
            is_expired=bool(row.get("is_expired")),
            screening_questions=row.get("screening_questions") or [],
            matched_queries=row.get("matched_queries") or [],
        )
