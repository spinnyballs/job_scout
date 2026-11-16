"""SQLite persistence layer for discovered jobs, scan runs, and future pipeline stages."""
from __future__ import annotations

import json
import re
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator

from .models import JobStatus, NormalizedJob, QABankEntry, ScanRunSummary, ScoreBreakdown
from .profile import default_qa_bank_entries
from .scoring import compute_dedup_key

DEFAULT_DB_PATH = Path(__file__).resolve().parent.parent / "data" / "jobs.db"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class JobStore:
    def __init__(self, db_path: Path | str = DEFAULT_DB_PATH) -> None:
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_schema()

    @contextmanager
    def _conn(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.db_path, timeout=30.0)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA busy_timeout=15000")
        conn.execute("PRAGMA foreign_keys=ON")
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _init_schema(self) -> None:
        with self._conn() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source TEXT NOT NULL,
                    external_id TEXT NOT NULL,
                    dedup_key TEXT NOT NULL,
                    url TEXT NOT NULL,
                    title TEXT NOT NULL,
                    company TEXT NOT NULL,
                    advertiser_id TEXT,
                    is_agency INTEGER NOT NULL DEFAULT 0,
                    location TEXT DEFAULT '',
                    work_type TEXT DEFAULT '',
                    work_arrangement TEXT DEFAULT '',
                    classifications_json TEXT DEFAULT '[]',
                    teaser TEXT DEFAULT '',
                    bullet_points_json TEXT DEFAULT '[]',
                    description_html TEXT DEFAULT '',
                    description_text TEXT DEFAULT '',
                    salary_label TEXT DEFAULT '',
                    salary_est_min INTEGER,
                    salary_est_max INTEGER,
                    posted_at TEXT,
                    expires_at TEXT,
                    is_link_out INTEGER NOT NULL DEFAULT 0,
                    is_expired INTEGER NOT NULL DEFAULT 0,
                    screening_questions_json TEXT DEFAULT '[]',
                    matched_queries_json TEXT DEFAULT '[]',
                    score INTEGER NOT NULL DEFAULT 0,
                    score_breakdown_json TEXT DEFAULT '{}',
                    blocked_reason TEXT,
                    status TEXT NOT NULL DEFAULT 'discovered',
                    user_notes TEXT DEFAULT '',
                    raw_search_json TEXT DEFAULT '{}',
                    raw_detail_json TEXT DEFAULT '{}',
                    first_seen_at TEXT NOT NULL,
                    last_seen_at TEXT NOT NULL,
                    detail_fetched_at TEXT,
                    salary_probed_at TEXT,
                    UNIQUE(source, external_id)
                );

                CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);
                CREATE INDEX IF NOT EXISTS idx_jobs_score ON jobs(score DESC);
                CREATE INDEX IF NOT EXISTS idx_jobs_dedup ON jobs(dedup_key);
                CREATE INDEX IF NOT EXISTS idx_jobs_posted ON jobs(posted_at DESC);

                CREATE TABLE IF NOT EXISTS scan_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    started_at TEXT NOT NULL,
                    finished_at TEXT,
                    source TEXT NOT NULL,
                    queries_run INTEGER NOT NULL DEFAULT 0,
                    total_fetched INTEGER NOT NULL DEFAULT 0,
                    new_jobs INTEGER NOT NULL DEFAULT 0,
                    updated_jobs INTEGER NOT NULL DEFAULT 0,
                    blocked_jobs INTEGER NOT NULL DEFAULT 0,
                    details_fetched INTEGER NOT NULL DEFAULT 0,
                    salaries_probed INTEGER NOT NULL DEFAULT 0,
                    errors_json TEXT DEFAULT '[]'
                );

                CREATE TABLE IF NOT EXISTS qa_bank (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    canonical_key TEXT NOT NULL UNIQUE,
                    question_pattern TEXT NOT NULL,
                    answer TEXT NOT NULL,
                    category TEXT NOT NULL DEFAULT 'general',
                    notes TEXT DEFAULT '',
                    updated_at TEXT NOT NULL
                );
                """
            )
            self._ensure_column(conn, "jobs", "llm_fit_json", "TEXT DEFAULT ''")
            self._ensure_column(conn, "jobs", "llm_fit_score", "INTEGER")
            self._ensure_column(conn, "jobs", "llm_analyzed_at", "TEXT")
            self._ensure_column(conn, "jobs", "tailored_package_json", "TEXT DEFAULT ''")
            self._ensure_column(conn, "jobs", "cv_pdf_path", "TEXT DEFAULT ''")
            self._ensure_column(conn, "jobs", "cover_letter_pdf_path", "TEXT DEFAULT ''")
            self._ensure_column(conn, "jobs", "tailored_at", "TEXT")
            self._ensure_column(conn, "jobs", "applied_at", "TEXT")
            self._ensure_column(conn, "jobs", "apply_method", "TEXT DEFAULT ''")
            self._ensure_column(conn, "jobs", "apply_log_json", "TEXT DEFAULT '[]'")
            self._ensure_column(conn, "jobs", "synced_from_phone", "INTEGER NOT NULL DEFAULT 0")
            self._ensure_column(conn, "jobs", "synced_at", "TEXT")

            cnt = conn.execute("SELECT COUNT(*) FROM qa_bank").fetchone()[0]
            if cnt == 0:
                now = _utc_now()
                for entry in default_qa_bank_entries():
                    conn.execute(
                        """
                        INSERT INTO qa_bank (canonical_key, question_pattern, answer, category, notes, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        (entry.canonical_key, entry.question_pattern, entry.answer, entry.category, entry.notes, now),
                    )

    @staticmethod
    def _ensure_column(conn: sqlite3.Connection, table: str, col: str, ddl: str) -> None:
        cols = {r["name"] for r in conn.execute(f"PRAGMA table_info({table})").fetchall()}
        if col not in cols:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN {col} {ddl}")

    def create_scan_run(self, source: str) -> int:
        now = _utc_now()
        with self._conn() as conn:
            cur = conn.execute(
                "INSERT INTO scan_runs (started_at, source) VALUES (?, ?)",
                (now, source),
            )
            return int(cur.lastrowid)

    def finish_scan_run(self, summary: ScanRunSummary) -> None:
        with self._conn() as conn:
            conn.execute(
                """
                UPDATE scan_runs
                SET finished_at = ?, queries_run = ?, total_fetched = ?,
                    new_jobs = ?, updated_jobs = ?, blocked_jobs = ?,
                    details_fetched = ?, salaries_probed = ?, errors_json = ?
                WHERE id = ?
                """,
                (
                    (summary.finished_at or datetime.now(timezone.utc)).isoformat(),
                    summary.queries_run,
                    summary.total_fetched,
                    summary.new_jobs,
                    summary.updated_jobs,
                    summary.blocked_jobs,
                    summary.details_fetched,
                    summary.salaries_probed,
                    json.dumps(summary.errors),
                    summary.run_id,
                ),
            )

    def get_existing_job(self, source: str, external_id: str) -> dict[str, Any] | None:
        with self._conn() as conn:
            row = conn.execute(
                "SELECT * FROM jobs WHERE source = ? AND external_id = ?",
                (source, external_id),
            ).fetchone()
            return dict(row) if row else None

    def upsert_job(self, job: NormalizedJob, breakdown: ScoreBreakdown) -> tuple[int, bool]:
        """Insert or update a job. Returns (job_row_id, is_new)."""
        now = _utc_now()
        dedup_key = compute_dedup_key(job.title, job.company, job.location)
        existing = self.get_existing_job(job.source, job.external_id)

        if existing is None:
            status = JobStatus.BLOCKED.value if breakdown.blocked_reason else JobStatus.DISCOVERED.value
            with self._conn() as conn:
                cur = conn.execute(
                    """
                    INSERT INTO jobs (
                        source, external_id, dedup_key, url, title, company, advertiser_id,
                        is_agency, location, work_type, work_arrangement, classifications_json,
                        teaser, bullet_points_json, description_html, description_text,
                        salary_label, salary_est_min, salary_est_max, posted_at, expires_at,
                        is_link_out, is_expired, screening_questions_json, matched_queries_json,
                        score, score_breakdown_json, blocked_reason, status,
                        raw_search_json, raw_detail_json, first_seen_at, last_seen_at,
                        detail_fetched_at, salary_probed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        job.source,
                        job.external_id,
                        dedup_key,
                        job.url,
                        job.title,
                        job.company,
                        job.advertiser_id,
                        int(job.is_agency),
                        job.location,
                        job.work_type,
                        job.work_arrangement,
                        json.dumps(job.classifications),
                        job.teaser,
                        json.dumps(job.bullet_points),
                        job.description_html,
                        job.description_text,
                        job.salary_label,
                        job.salary_est_min,
                        job.salary_est_max,
                        job.posted_at,
                        job.expires_at,
                        int(job.is_link_out),
                        int(job.is_expired),
                        json.dumps(job.screening_questions),
                        json.dumps(sorted(set(job.matched_queries))),
                        breakdown.score,
                        breakdown.model_dump_json(),
                        breakdown.blocked_reason,
                        status,
                        json.dumps(job.raw_search_json),
                        json.dumps(job.raw_detail_json),
                        now,
                        now,
                        now if job.description_html or job.description_text else None,
                        now if (job.salary_est_min is not None or job.salary_est_max is not None) else None,
                    ),
                )
                return int(cur.lastrowid), True

        # Merge matched queries and preserve user-triaged status unless newly blocked
        old_queries = json.loads(existing.get("matched_queries_json") or "[]")
        merged_queries = sorted(set(old_queries) | set(job.matched_queries))

        new_status = existing["status"]
        if breakdown.blocked_reason:
            new_status = JobStatus.BLOCKED.value

        desc_html = job.description_html or existing.get("description_html", "")
        desc_text = job.description_text or existing.get("description_text", "")
        sal_min = job.salary_est_min if job.salary_est_min is not None else existing.get("salary_est_min")
        sal_max = job.salary_est_max if job.salary_est_max is not None else existing.get("salary_est_max")
        detail_ts = now if (job.description_html or job.description_text) else existing.get("detail_fetched_at")
        sal_ts = now if (job.salary_est_min is not None or job.salary_est_max is not None) else existing.get("salary_probed_at")

        with self._conn() as conn:
            conn.execute(
                """
                UPDATE jobs SET
                    dedup_key = ?, url = ?, title = ?, company = ?, advertiser_id = COALESCE(?, advertiser_id),
                    is_agency = ?, location = ?, work_type = COALESCE(NULLIF(?, ''), work_type),
                    work_arrangement = COALESCE(NULLIF(?, ''), work_arrangement),
                    classifications_json = ?, teaser = COALESCE(NULLIF(?, ''), teaser),
                    bullet_points_json = ?, description_html = ?, description_text = ?,
                    salary_label = COALESCE(NULLIF(?, ''), salary_label),
                    salary_est_min = ?, salary_est_max = ?,
                    posted_at = COALESCE(?, posted_at), expires_at = COALESCE(?, expires_at),
                    is_link_out = ?, is_expired = ?, screening_questions_json = ?,
                    matched_queries_json = ?, score = ?, score_breakdown_json = ?,
                    blocked_reason = ?, status = ?,
                    raw_search_json = ?, raw_detail_json = CASE WHEN ? != '{}' THEN ? ELSE raw_detail_json END,
                    last_seen_at = ?, detail_fetched_at = ?, salary_probed_at = ?
                WHERE id = ?
                """,
                (
                    dedup_key,
                    job.url,
                    job.title,
                    job.company,
                    job.advertiser_id,
                    int(job.is_agency),
                    job.location,
                    job.work_type,
                    job.work_arrangement,
                    json.dumps(job.classifications),
                    job.teaser,
                    json.dumps(job.bullet_points),
                    desc_html,
                    desc_text,
                    job.salary_label,
                    sal_min,
                    sal_max,
                    job.posted_at,
                    job.expires_at,
                    int(job.is_link_out),
                    int(job.is_expired),
                    json.dumps(job.screening_questions),
                    json.dumps(merged_queries),
                    breakdown.score,
                    breakdown.model_dump_json(),
                    breakdown.blocked_reason,
                    new_status,
                    json.dumps(job.raw_search_json),
                    json.dumps(job.raw_detail_json),
                    json.dumps(job.raw_detail_json),
                    now,
                    detail_ts,
                    sal_ts,
                    existing["id"],
                ),
            )
            return int(existing["id"]), False

    def list_jobs(
        self,
        *,
        status: str | None = None,
        min_score: int | None = None,
        search: str | None = None,
        query_tag: str | None = None,
        include_blocked: bool = False,
        synced_from_phone: bool = False,
        sort: str = "score",
        limit: int = 200,
    ) -> list[dict[str, Any]]:
        clauses: list[str] = []
        params: list[Any] = []

        if synced_from_phone:
            clauses.append("synced_from_phone = 1")
            if not include_blocked:
                clauses.append("status != 'blocked'")
        elif status and status != "all":
            statuses = [s.strip() for s in status.split(",") if s.strip()]
            if len(statuses) == 1:
                clauses.append("status = ?")
                params.append(statuses[0])
            elif statuses:
                placeholders = ", ".join("?" for _ in statuses)
                clauses.append(f"status IN ({placeholders})")
                params.extend(statuses)
        elif not include_blocked:
            clauses.append("status NOT IN ('blocked', 'skipped', 'no_reply', 'rejected')")

        if min_score is not None:
            clauses.append("score >= ?")
            params.append(min_score)

        if search:
            clauses.append("(title LIKE ? OR company LIKE ? OR location LIKE ? OR description_text LIKE ?)")
            like = f"%{search}%"
            params.extend([like, like, like, like])

        if query_tag:
            clauses.append("matched_queries_json LIKE ?")
            params.append(f'%"{query_tag}"%')

        where_sql = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        order_sql = {
            "score": "score DESC, posted_at DESC, id DESC",
            "newest": "posted_at DESC, score DESC, id DESC",
            "salary": "COALESCE(salary_est_max, salary_est_min, 0) DESC, score DESC",
        }.get(sort, "score DESC, posted_at DESC")

        with self._conn() as conn:
            rows = conn.execute(
                f"""
                SELECT id, source, external_id, dedup_key, url, title, company, advertiser_id,
                       is_agency, location, work_type, work_arrangement, classifications_json,
                       teaser, bullet_points_json, salary_label, salary_est_min, salary_est_max,
                       posted_at, expires_at, is_link_out, is_expired, screening_questions_json,
                       matched_queries_json, score, score_breakdown_json, blocked_reason,
                       status, user_notes, first_seen_at, last_seen_at, detail_fetched_at,
                       salary_probed_at, llm_fit_score, llm_analyzed_at, tailored_at, applied_at, apply_method,
                       synced_from_phone, synced_at,
                       CASE WHEN length(cv_pdf_path) > 0 THEN 1 ELSE 0 END AS has_tailored_cv,
                       CASE WHEN length(description_text) > 0 THEN 1 ELSE 0 END AS has_detail
                FROM jobs
                {where_sql}
                ORDER BY {order_sql}
                LIMIT ?
                """,
                [*params, limit],
            ).fetchall()
        return [self._hydrate_row(dict(r)) for r in rows]

    def get_job(self, job_id: int) -> dict[str, Any] | None:
        with self._conn() as conn:
            row = conn.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()
            return self._hydrate_row(dict(row)) if row else None

    def update_job_triage(self, job_id: int, *, status: str | None = None, user_notes: str | None = None) -> dict[str, Any] | None:
        updates: list[str] = []
        params: list[Any] = []
        if status is not None:
            updates.append("status = ?")
            params.append(status)
        if user_notes is not None:
            updates.append("user_notes = ?")
            params.append(user_notes)
        if not updates:
            return self.get_job(job_id)
        params.append(job_id)
        with self._conn() as conn:
            conn.execute(f"UPDATE jobs SET {', '.join(updates)} WHERE id = ?", params)
        return self.get_job(job_id)

    def update_job_salary(self, job_id: int, sal_min: int | None, sal_max: int | None) -> None:
        with self._conn() as conn:
            conn.execute(
                "UPDATE jobs SET salary_est_min = ?, salary_est_max = ?, salary_probed_at = ? WHERE id = ?",
                (sal_min, sal_max, _utc_now(), job_id),
            )

    def update_job_score(self, job_id: int, breakdown: ScoreBreakdown) -> None:
        with self._conn() as conn:
            if breakdown.blocked_reason:
                conn.execute(
                    "UPDATE jobs SET score = ?, score_breakdown_json = ?, blocked_reason = ?, status = 'blocked' WHERE id = ?",
                    (breakdown.score, breakdown.model_dump_json(), breakdown.blocked_reason, job_id),
                )
            else:
                conn.execute(
                    "UPDATE jobs SET score = ?, score_breakdown_json = ?, blocked_reason = NULL, status = CASE WHEN status = 'blocked' THEN 'discovered' ELSE status END WHERE id = ?",
                    (breakdown.score, breakdown.model_dump_json(), job_id),
                )

    def get_stats(self) -> dict[str, Any]:
        with self._conn() as conn:
            counts = conn.execute(
                """
                SELECT status, COUNT(*) AS cnt
                FROM jobs
                GROUP BY status
                """
            ).fetchall()
            status_map = {r["status"]: r["cnt"] for r in counts}
            total = sum(status_map.values())
            high_fit = conn.execute(
                "SELECT COUNT(*) AS cnt FROM jobs WHERE score >= 50 AND status NOT IN ('blocked', 'skipped', 'no_reply', 'rejected')"
            ).fetchone()["cnt"]
            synced_from_phone = conn.execute(
                "SELECT COUNT(*) AS cnt FROM jobs WHERE synced_from_phone = 1 AND status NOT IN ('blocked', 'skipped')"
            ).fetchone()["cnt"]
            last_run = conn.execute(
                "SELECT * FROM scan_runs ORDER BY id DESC LIMIT 1"
            ).fetchone()
            recent_runs = conn.execute(
                "SELECT * FROM scan_runs ORDER BY id DESC LIMIT 10"
            ).fetchall()
        return {
            "total": total,
            "high_fit": high_fit,
            "synced_from_phone": synced_from_phone,
            "no_reply_closed": (status_map.get("no_reply", 0) + status_map.get("rejected", 0)),
            "by_status": status_map,
            "last_run": self._hydrate_run(dict(last_run)) if last_run else None,
            "recent_runs": [self._hydrate_run(dict(r)) for r in recent_runs],
        }

    def clear_tailored_package(self, job_id: int) -> dict[str, Any] | None:
        with self._conn() as conn:
            conn.execute(
                """
                UPDATE jobs SET
                    tailored_package_json = '',
                    cv_pdf_path = '',
                    cover_letter_pdf_path = '',
                    tailored_at = NULL,
                    status = CASE WHEN status = 'tailored' THEN 'shortlisted' ELSE status END
                WHERE id = ?
                """,
                (job_id,),
            )
        return self.get_job(job_id)

    def get_applied_keys(self) -> list[dict[str, str]]:
        with self._conn() as conn:
            rows = conn.execute(
                "SELECT source, external_id, status FROM jobs WHERE status IN ('applied', 'interview', 'no_reply', 'rejected')"
            ).fetchall()
        return [{"source": r["source"], "external_id": r["external_id"], "status": r["status"]} for r in rows]

    def sync_phone_jobs(self, incoming_jobs: list[dict[str, Any]]) -> dict[str, Any]:
        """Merge shortlisted/tailored/applied jobs from phone without PDFs, checking desktop applied status."""
        now = _utc_now()
        upserted = 0
        marked_applied_on_desktop = 0
        applied_updates: list[dict[str, str]] = []
        applied_statuses = ("applied", "interview", "no_reply", "rejected")

        with self._conn() as conn:
            for item in incoming_jobs:
                source = (item.get("source") or "seek_nz").strip()
                ext_id = str(item.get("external_id") or "").strip()
                if not ext_id:
                    continue
                inc_status = (item.get("status") or "shortlisted").strip().lower()
                existing = conn.execute(
                    "SELECT id, status, llm_fit_json, llm_fit_score, tailored_package_json, salary_est_min, salary_est_max FROM jobs WHERE source = ? AND external_id = ?",
                    (source, ext_id),
                ).fetchone()

                if existing:
                    cur_status = (existing["status"] or "").lower()
                    if cur_status in applied_statuses:
                        applied_updates.append({"source": source, "external_id": ext_id, "status": cur_status})
                        continue
                    if inc_status in applied_statuses:
                        conn.execute(
                            "UPDATE jobs SET status = ?, applied_at = COALESCE(applied_at, ?), synced_from_phone = 1, synced_at = ? WHERE id = ?",
                            (inc_status, now, now, existing["id"]),
                        )
                        marked_applied_on_desktop += 1
                    else:
                        new_status = inc_status if cur_status in ("discovered", "skipped") else cur_status
                        llm_fit_json = json.dumps(item["llm_fit"]) if item.get("llm_fit") and not existing["llm_fit_json"] else existing["llm_fit_json"]
                        llm_fit_score = item["llm_fit"].get("fit_score") if item.get("llm_fit") and existing["llm_fit_score"] is None else existing["llm_fit_score"]
                        tailored_json = json.dumps(item["tailored_package"]) if item.get("tailored_package") and not existing["tailored_package_json"] else existing["tailored_package_json"]
                        sal_min = existing["salary_est_min"] if existing["salary_est_min"] is not None else item.get("salary_est_min")
                        sal_max = existing["salary_est_max"] if existing["salary_est_max"] is not None else item.get("salary_est_max")
                        conn.execute(
                            """
                            UPDATE jobs SET
                                status = ?,
                                synced_from_phone = 1,
                                synced_at = ?,
                                llm_fit_json = ?,
                                llm_fit_score = ?,
                                tailored_package_json = ?,
                                salary_est_min = ?,
                                salary_est_max = ?,
                                user_notes = COALESCE(NULLIF(?, ''), user_notes)
                            WHERE id = ?
                            """,
                            (
                                new_status,
                                now,
                                llm_fit_json or "",
                                llm_fit_score,
                                tailored_json or "",
                                sal_min,
                                sal_max,
                                item.get("user_notes") or "",
                                existing["id"],
                            ),
                        )
                        upserted += 1
                else:
                    title = item.get("title") or ""
                    company = item.get("company") or ""
                    location = item.get("location") or ""
                    dedup_key = compute_dedup_key(title, company, location)
                    llm_fit = item.get("llm_fit")
                    tailored_pkg = item.get("tailored_package")
                    score_bd = item.get("score_breakdown") or {}
                    conn.execute(
                        """
                        INSERT INTO jobs (
                            source, external_id, dedup_key, url, title, company,
                            location, work_type, work_arrangement, classifications_json,
                            teaser, bullet_points_json, description_html, description_text,
                            salary_label, salary_est_min, salary_est_max,
                            posted_at, expires_at, is_link_out, is_expired,
                            screening_questions_json, matched_queries_json,
                            score, score_breakdown_json, status, user_notes,
                            first_seen_at, last_seen_at,
                            llm_fit_json, llm_fit_score, tailored_package_json,
                            applied_at, synced_from_phone, synced_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                        """,
                        (
                            source,
                            ext_id,
                            dedup_key,
                            item.get("url") or "",
                            title,
                            company,
                            item.get("location") or "",
                            item.get("work_type") or "",
                            item.get("work_arrangement") or "",
                            json.dumps(item.get("classifications") or []),
                            item.get("teaser") or "",
                            json.dumps(item.get("bullet_points") or []),
                            item.get("description_html") or "",
                            item.get("description_text") or "",
                            item.get("salary_label") or "",
                            item.get("salary_est_min"),
                            item.get("salary_est_max"),
                            item.get("posted_at"),
                            item.get("expires_at"),
                            int(bool(item.get("is_link_out", False))),
                            int(bool(item.get("is_expired", False))),
                            json.dumps(item.get("screening_questions") or []),
                            json.dumps(item.get("matched_queries") or []),
                            int(item.get("score") or 0),
                            json.dumps(score_bd),
                            inc_status,
                            item.get("user_notes") or "",
                            now,
                            now,
                            json.dumps(llm_fit) if llm_fit else "",
                            llm_fit.get("fit_score") if isinstance(llm_fit, dict) else None,
                            json.dumps(tailored_pkg) if tailored_pkg else "",
                            now if inc_status in applied_statuses else None,
                            now,
                        ),
                    )
                    if inc_status in applied_statuses:
                        marked_applied_on_desktop += 1
                    else:
                        upserted += 1

        return {
            "upserted": upserted,
            "marked_applied_on_desktop": marked_applied_on_desktop,
            "applied_updates": applied_updates,
            "all_applied_keys": self.get_applied_keys(),
        }

    def all_jobs_for_rescore(self) -> list[dict[str, Any]]:
        with self._conn() as conn:
            rows = conn.execute("SELECT * FROM jobs").fetchall()
            return [self._hydrate_row(dict(r)) for r in rows]

    def save_llm_fit(self, job_id: int, fit_dict: dict[str, Any], fit_score: int) -> dict[str, Any] | None:
        with self._conn() as conn:
            conn.execute(
                "UPDATE jobs SET llm_fit_json = ?, llm_fit_score = ?, llm_analyzed_at = ? WHERE id = ?",
                (json.dumps(fit_dict), fit_score, _utc_now(), job_id),
            )
        return self.get_job(job_id)

    def save_tailored_package(
        self,
        job_id: int,
        package_dict: dict[str, Any],
        cv_pdf_path: str | None,
        cover_letter_pdf_path: str | None,
    ) -> dict[str, Any] | None:
        with self._conn() as conn:
            conn.execute(
                """
                UPDATE jobs SET
                    tailored_package_json = ?,
                    cv_pdf_path = ?,
                    cover_letter_pdf_path = ?,
                    tailored_at = ?,
                    status = CASE WHEN status IN ('discovered', 'shortlisted') THEN 'tailored' ELSE status END
                WHERE id = ?
                """,
                (
                    json.dumps(package_dict),
                    cv_pdf_path or "",
                    cover_letter_pdf_path or "",
                    _utc_now(),
                    job_id,
                ),
            )
        return self.get_job(job_id)

    def mark_applied(
        self,
        job_id: int,
        *,
        apply_method: str,
        status: str = "applied",
        log_note: str = "",
    ) -> dict[str, Any] | None:
        now = _utc_now()
        with self._conn() as conn:
            row = conn.execute("SELECT apply_log_json FROM jobs WHERE id = ?", (job_id,)).fetchone()
            logs: list[dict[str, str]] = []
            if row and row["apply_log_json"]:
                try:
                    logs = json.loads(row["apply_log_json"])
                except json.JSONDecodeError:
                    logs = []
            logs.append({"at": now, "status": status, "method": apply_method, "note": log_note})
            conn.execute(
                """
                UPDATE jobs SET
                    status = ?,
                    applied_at = COALESCE(applied_at, ?),
                    apply_method = COALESCE(NULLIF(?, ''), apply_method),
                    apply_log_json = ?
                WHERE id = ?
                """,
                (status, now, apply_method, json.dumps(logs), job_id),
            )
        return self.get_job(job_id)

    def recent_company_applications(self, company: str, cooldown_days: int = 14) -> list[dict[str, Any]]:
        """Check if user already applied to this company within cooldown window."""
        if not company:
            return []
        norm = re.sub(r"[^a-z0-9]+", " ", company.lower()).strip()
        if not norm:
            return []
        with self._conn() as conn:
            rows = conn.execute(
                """
                SELECT id, title, company, status, applied_at, url
                FROM jobs
                WHERE status IN ('applied', 'interview', 'no_reply', 'rejected')
                  AND applied_at IS NOT NULL
                  AND datetime(applied_at) >= datetime('now', ?)
                """,
                (f"-{int(cooldown_days)} days",),
            ).fetchall()
        hits = []
        for r in rows:
            c_norm = re.sub(r"[^a-z0-9]+", " ", (r["company"] or "").lower()).strip()
            if c_norm and (c_norm == norm or c_norm in norm or norm in c_norm):
                hits.append(dict(r))
        return hits

    def list_qa_entries(self) -> list[QABankEntry]:
        with self._conn() as conn:
            rows = conn.execute("SELECT * FROM qa_bank ORDER BY category, canonical_key").fetchall()
        return [QABankEntry(**dict(r)) for r in rows]

    def upsert_qa_entry(self, entry: QABankEntry) -> QABankEntry:
        now = _utc_now()
        with self._conn() as conn:
            if entry.id:
                conn.execute(
                    """
                    UPDATE qa_bank SET canonical_key = ?, question_pattern = ?, answer = ?, category = ?, notes = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (entry.canonical_key, entry.question_pattern, entry.answer, entry.category, entry.notes, now, entry.id),
                )
                row_id = entry.id
            else:
                conn.execute(
                    """
                    INSERT INTO qa_bank (canonical_key, question_pattern, answer, category, notes, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(canonical_key) DO UPDATE SET
                        question_pattern = excluded.question_pattern,
                        answer = excluded.answer,
                        category = excluded.category,
                        notes = excluded.notes,
                        updated_at = excluded.updated_at
                    """,
                    (entry.canonical_key, entry.question_pattern, entry.answer, entry.category, entry.notes, now),
                )
                row_id = conn.execute(
                    "SELECT id FROM qa_bank WHERE canonical_key = ?", (entry.canonical_key,)
                ).fetchone()["id"]
            row = conn.execute("SELECT * FROM qa_bank WHERE id = ?", (row_id,)).fetchone()
            return QABankEntry(**dict(row))

    def delete_qa_entry(self, entry_id: int) -> None:
        with self._conn() as conn:
            conn.execute("DELETE FROM qa_bank WHERE id = ?", (entry_id,))

    @staticmethod
    def _hydrate_row(row: dict[str, Any]) -> dict[str, Any]:
        for key, target in [
            ("classifications_json", "classifications"),
            ("bullet_points_json", "bullet_points"),
            ("screening_questions_json", "screening_questions"),
            ("matched_queries_json", "matched_queries"),
            ("score_breakdown_json", "score_breakdown"),
            ("llm_fit_json", "llm_fit"),
            ("tailored_package_json", "tailored_package"),
            ("apply_log_json", "apply_log"),
        ]:
            raw = row.pop(key, None)
            if raw:
                try:
                    row[target] = json.loads(raw)
                except json.JSONDecodeError:
                    row[target] = [] if target not in ("score_breakdown", "llm_fit", "tailored_package") else None
            elif target in ("llm_fit", "tailored_package"):
                row[target] = None
        row.pop("raw_search_json", None)
        row.pop("raw_detail_json", None)
        return row

    @staticmethod
    def _hydrate_run(row: dict[str, Any]) -> dict[str, Any]:
        raw_err = row.pop("errors_json", "[]")
        try:
            row["errors"] = json.loads(raw_err)
        except json.JSONDecodeError:
            row["errors"] = []
        return row
