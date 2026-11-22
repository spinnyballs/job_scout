"""Seek (NZ & AU) job board adapter using v5 JSON search + GraphQL details + hidden salary probing."""
from __future__ import annotations

import asyncio
import re
import uuid
from typing import Any, AsyncIterator

import httpx

from ..models import NormalizedJob, SearchQueryConfig
from ..scoring import html_to_clean_text
from .base import JobBoardAdapter, register_adapter

_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/131.0.0.0 Safari/537.36"
)

_AGENCY_HINTS = (
    "recruitment",
    "recruiting",
    "recruiters",
    "talent",
    "staffing",
    "personnel",
    "hays",
    "randstad",
    "robert walters",
    "madison",
    "potentia",
    "stellar",
    "adecco",
    "hudson",
    "beyond recruitment",
    "accordant",
    "enterprise recruitment",
    "eighty4",
    "salt ",
    "frog recruitment",
    "kinetic recruitment",
    "one staff",
    "onestaff",
    "tradestaff",
    "awf",
)

_GQL_JOB_DETAILS_QUERY = """
query jobDetails($jobId: ID!, $jobDetailsViewedCorrelationId: String!, $sessionId: String!) {
  jobDetails(
    id: $jobId
    tracking: {channel: "WEB", jobDetailsViewedCorrelationId: $jobDetailsViewedCorrelationId, sessionId: $sessionId}
  ) {
    job {
      id
      title
      abstract
      content(platform: WEB)
      status
      isLinkOut
      listedAt { dateTimeUtc }
      expiresAt { dateTimeUtc }
      salary { label }
      workTypes { label }
      advertiser { id name }
      location { label }
      classifications { label(languageCode: "en") }
      products { questionnaire { questions } }
    }
  }
}
"""

_GQL_JOB_AVAILABILITY_QUERY = """
query jobDetails($jobId: ID!, $jobDetailsViewedCorrelationId: String!, $sessionId: String!) {
  jobDetails(
    id: $jobId
    tracking: {channel: "WEB", jobDetailsViewedCorrelationId: $jobDetailsViewedCorrelationId, sessionId: $sessionId}
  ) {
    job {
      id
      status
      expiresAt { dateTimeUtc }
    }
  }
}
"""

_SALARY_NUM_RE = re.compile(r"\$\s*(\d{2,3}(?:,\d{3})+|\d{2,3}(?:\.\d+)?\s*[kK]|\d{2,3})\b")


def parse_explicit_salary(label: str) -> tuple[int | None, int | None]:
    """Parse disclosed salary strings like '$85,000 - $105,000', '$90k-$110k', or '$42/hr'."""
    if not label:
        return (None, None)
    lower = label.lower()
    matches = _SALARY_NUM_RE.findall(label)
    if not matches:
        return (None, None)

    values: list[int] = []
    is_hourly = any(h in lower for h in ("per hour", "/hr", "p/h", "p.h", "hourly", "/hour"))
    for raw in matches[:2]:
        cleaned = raw.replace(",", "").strip().lower()
        if cleaned.endswith("k"):
            val = int(float(cleaned[:-1]) * 1000)
        else:
            num = float(cleaned)
            if is_hourly or num < 200:
                # Approximate full-time annual equivalent (2080 hrs/yr) if clearly hourly
                val = int(num * 2080) if is_hourly else int(num * 1000) if num >= 50 else None
            else:
                val = int(num)
        if val and 35000 <= val <= 450000:
            values.append(val)

    if not values:
        return (None, None)
    if len(values) == 1:
        return (values[0], values[0])
    return (min(values), max(values))


def _looks_like_agency(company: str) -> bool:
    c = (company or "").lower()
    return any(hint in c for hint in _AGENCY_HINTS)


def parse_seek_url_or_id(raw_input: str, default_board: str = "seek_nz") -> tuple[str, str]:
    """Extract (source_id, external_job_id) from any SEEK NZ/AU job URL or numeric ID."""
    text = (raw_input or "").strip()
    if not text:
        raise ValueError("Please paste a SEEK job URL or numeric job ID.")

    lower = text.lower()
    if "seek.com.au" in lower or "au.seek.com" in lower:
        board = "seek_au"
    elif "seek.co.nz" in lower or "nz.seek.com" in lower:
        board = "seek_nz"
    else:
        board = default_board if default_board in ("seek_nz", "seek_au") else "seek_nz"

    for pat in (
        r"/job/(\d{6,12})\b",
        r"[?&]jobId=(\d{6,12})\b",
        r"^(\d{6,12})$",
        r"\b(\d{7,10})\b",
    ):
        m = re.search(pat, text, re.IGNORECASE)
        if m:
            return board, m.group(1)

    raise ValueError(
        "Could not find a SEEK job ID in that link. Paste a URL like https://www.seek.co.nz/job/12345678 or a numeric ID."
    )


class _BaseSeekAdapter(JobBoardAdapter):
    base_search_url: str = ""
    graphql_url: str = ""
    job_url_prefix: str = ""
    default_site_key: str = ""
    country_code: str = ""
    supports_salary_probe: bool = True
    is_ready: bool = True

    def __init__(self, request_delay_ms: int = 250) -> None:
        super().__init__(request_delay_ms=request_delay_ms)
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(15.0),
            follow_redirects=True,
            headers={
                "User-Agent": _USER_AGENT,
                "Accept": "application/json",
            },
        )
        self._session_id = str(uuid.uuid4())

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _polite_sleep(self) -> None:
        if self.request_delay_ms > 0:
            await asyncio.sleep(self.request_delay_ms / 1000.0)

    async def search(self, query: SearchQueryConfig) -> AsyncIterator[NormalizedJob]:
        site_key = query.site_key or self.default_site_key
        for page in range(1, max(1, query.max_pages) + 1):
            params: dict[str, Any] = {
                "siteKey": site_key,
                "keywords": query.keywords,
                "page": page,
                "pageSize": 22,
                "locale": f"en-{self.country_code}",
            }
            if query.where:
                params["where"] = query.where
            if query.date_range_days:
                params["daterange"] = query.date_range_days
            if query.work_type:
                params["worktype"] = query.work_type
            if query.salary_min or query.salary_max:
                lo = query.salary_min or 0
                hi = query.salary_max or 999999
                params["salaryrange"] = f"{lo}-{hi}"
                params["salarytype"] = "annual"

            resp = await self._client.get(self.base_search_url, params=params)
            resp.raise_for_status()
            payload = resp.json()
            items: list[dict[str, Any]] = payload.get("data") or []
            if not items:
                break

            for raw in items:
                yield self._parse_search_item(raw, query.name)

            total_count = int(payload.get("totalCount") or 0)
            if page * 22 >= total_count:
                break
            await self._polite_sleep()

    def _parse_search_item(self, raw: dict[str, Any], query_name: str) -> NormalizedJob:
        ext_id = str(raw.get("id"))
        advertiser = raw.get("advertiser") or {}
        employer = raw.get("employer") or {}
        company = (
            raw.get("companyName")
            or employer.get("name")
            or advertiser.get("description")
            or "Private Advertiser"
        )
        adv_id = str(advertiser.get("id")) if advertiser.get("id") else None

        locations = raw.get("locations") or []
        loc_label = ", ".join(loc.get("label", "") for loc in locations if loc.get("label"))

        classifications: list[str] = []
        for c in raw.get("classifications") or []:
            main_c = (c.get("classification") or {}).get("description", "")
            sub_c = (c.get("subclassification") or {}).get("description", "")
            if sub_c and main_c:
                classifications.append(f"{sub_c} ({main_c})")
            elif sub_c or main_c:
                classifications.append(sub_c or main_c)

        work_types = raw.get("workTypes") or []
        work_type_str = ", ".join(work_types) if isinstance(work_types, list) else str(work_types)

        work_arr_data = (raw.get("workArrangements") or {}).get("data") or []
        work_arr = ", ".join(
            (w.get("label") or {}).get("text", "")
            for w in work_arr_data
            if (w.get("label") or {}).get("text")
        )

        salary_label = (raw.get("salaryLabel") or "").strip()
        sal_min, sal_max = parse_explicit_salary(salary_label)

        return NormalizedJob(
            source=self.source_id,
            external_id=ext_id,
            url=f"{self.job_url_prefix}/{ext_id}",
            title=(raw.get("title") or "").strip(),
            company=company.strip(),
            advertiser_id=adv_id,
            is_agency=_looks_like_agency(company),
            location=loc_label,
            work_type=work_type_str,
            work_arrangement=work_arr,
            classifications=classifications,
            teaser=(raw.get("teaser") or "").strip(),
            bullet_points=[b for b in (raw.get("bulletPoints") or []) if b],
            salary_label=salary_label,
            salary_est_min=sal_min,
            salary_est_max=sal_max,
            posted_at=raw.get("listingDate"),
            matched_queries=[query_name],
            raw_search_json=raw,
        )

    async def enrich_detail(self, job: NormalizedJob) -> NormalizedJob:
        await self._polite_sleep()
        gql_body = {
            "query": _GQL_JOB_DETAILS_QUERY,
            "variables": {
                "jobId": job.external_id,
                "jobDetailsViewedCorrelationId": str(uuid.uuid4()),
                "sessionId": self._session_id,
            },
        }
        headers = {
            "Content-Type": "application/json",
            "seek-request-brand": "seek",
            "seek-request-country": self.country_code,
        }
        resp = await self._client.post(self.graphql_url, json=gql_body, headers=headers)
        resp.raise_for_status()
        data = resp.json()
        detail = ((data.get("data") or {}).get("jobDetails") or {}).get("job")
        if not detail:
            if not job.title:
                raise ValueError(f"SEEK job {job.external_id} was not found or is no longer active.")
            job.is_expired = True
            return job

        if detail.get("title") and not job.title:
            job.title = str(detail["title"]).strip()

        adv = detail.get("advertiser") or {}
        if adv.get("name") and not job.company:
            job.company = str(adv["name"]).strip()
            job.is_agency = _looks_like_agency(job.company)
        if adv.get("id") and not job.advertiser_id:
            job.advertiser_id = str(adv["id"])

        loc_label = ((detail.get("location") or {}).get("label") or "").strip()
        if loc_label and not job.location:
            job.location = loc_label

        wt_label = ((detail.get("workTypes") or {}).get("label") or "").strip()
        if wt_label and not job.work_type:
            job.work_type = wt_label

        if not job.classifications:
            cls_list = [
                str(c.get("label")).strip()
                for c in (detail.get("classifications") or [])
                if isinstance(c, dict) and c.get("label")
            ]
            if cls_list:
                job.classifications = cls_list

        abstract = (detail.get("abstract") or "").strip()
        if abstract and not job.teaser:
            job.teaser = abstract

        html_body = detail.get("content") or ""
        job.description_html = html_body
        job.description_text = html_to_clean_text(html_body)
        job.is_link_out = bool(detail.get("isLinkOut"))
        job.is_expired = (detail.get("status") or "").lower() not in ("active", "")

        listed_utc = (detail.get("listedAt") or {}).get("dateTimeUtc")
        if listed_utc:
            job.posted_at = listed_utc
        expires_utc = (detail.get("expiresAt") or {}).get("dateTimeUtc")
        if expires_utc:
            job.expires_at = expires_utc

        gql_sal = ((detail.get("salary") or {}).get("label") or "").strip()
        if gql_sal and not job.salary_label:
            job.salary_label = gql_sal
            smin, smax = parse_explicit_salary(gql_sal)
            if smin:
                job.salary_est_min, job.salary_est_max = smin, smax

        questions = (((detail.get("products") or {}).get("questionnaire") or {}).get("questions")) or []
        if isinstance(questions, list):
            job.screening_questions = [str(q) for q in questions if q]

        job.raw_detail_json = detail
        return job

    async def check_availability(self, external_id: str) -> tuple[bool, str | None]:
        """Lightweight GraphQL check returning (is_expired, expires_at_utc)."""
        gql_body = {
            "query": _GQL_JOB_AVAILABILITY_QUERY,
            "variables": {
                "jobId": str(external_id),
                "jobDetailsViewedCorrelationId": str(uuid.uuid4()),
                "sessionId": self._session_id,
            },
        }
        headers = {
            "Content-Type": "application/json",
            "seek-request-brand": "seek",
            "seek-request-country": self.country_code,
        }
        resp = await self._client.post(self.graphql_url, json=gql_body, headers=headers)
        resp.raise_for_status()
        data = resp.json()
        detail = ((data.get("data") or {}).get("jobDetails") or {}).get("job")
        if not detail:
            return (True, None)
        status_str = (detail.get("status") or "").strip().lower()
        expires_utc = ((detail.get("expiresAt") or {}).get("dateTimeUtc") or "").strip() or None
        is_exp = status_str not in ("active", "")
        return (is_exp, expires_utc)

    async def _job_in_salary_band(self, job: NormalizedJob, low: int, high: int) -> bool:
        await asyncio.sleep(0.12)  # Gentle pacing during binary search
        # Strip punctuation that can confuse Seek phrase matching
        clean_title = re.sub(r"[^\w\s&+/-]", " ", job.title).strip()
        params: dict[str, Any] = {
            "siteKey": self.default_site_key,
            "keywords": f'"{clean_title}"',
            "salaryrange": f"{low}-{high}",
            "salarytype": "annual",
            "pageSize": 50,
        }
        if job.advertiser_id:
            params["advertiserid"] = job.advertiser_id
        elif job.location:
            params["where"] = job.location.split(",")[-1].strip()

        resp = await self._client.get(self.base_search_url, params=params)
        resp.raise_for_status()
        items = resp.json().get("data") or []
        return any(str(item.get("id")) == str(job.external_id) for item in items)

    async def probe_salary(self, job: NormalizedJob) -> tuple[int | None, int | None]:
        """Binary-search Seek's hidden employer-entered salary range to ~$2k precision."""
        # Confirm job is findable via keyword+advertiser filter first
        if not await self._job_in_salary_band(job, 0, 999999):
            return (job.salary_est_min, job.salary_est_max)

        # 1. Find minimum salary: smallest HIGH where [0, HIGH] still includes the job
        lo, hi = 45000, 260000
        if await self._job_in_salary_band(job, 0, lo):
            est_min = lo
        else:
            while hi - lo > 2500:
                mid = ((lo + hi) // 2 // 1000) * 1000
                if await self._job_in_salary_band(job, 0, mid):
                    hi = mid
                else:
                    lo = mid
            est_min = round(hi / 1000) * 1000

        # 2. Find maximum salary: largest LOW where [LOW, 999999] still includes the job
        lo2, hi2 = est_min, min(est_min + 120000, 320000)
        if await self._job_in_salary_band(job, hi2, 999999):
            est_max = hi2
        else:
            while hi2 - lo2 > 2500:
                mid = ((lo2 + hi2) // 2 // 1000) * 1000
                if await self._job_in_salary_band(job, mid, 999999):
                    lo2 = mid
                else:
                    hi2 = mid
            est_max = round(lo2 / 1000) * 1000

        return (est_min, max(est_min, est_max))


@register_adapter
class SeekNZAdapter(_BaseSeekAdapter):
    source_id = "seek_nz"
    display_name = "SEEK New Zealand"
    region = "NZ"
    base_search_url = "https://www.seek.co.nz/api/jobsearch/v5/search"
    graphql_url = "https://nz.seek.com/graphql"
    job_url_prefix = "https://nz.seek.com/job"
    default_site_key = "NZ-Main"
    country_code = "NZ"


@register_adapter
class SeekAUAdapter(_BaseSeekAdapter):
    source_id = "seek_au"
    display_name = "SEEK Australia"
    region = "AU"
    base_search_url = "https://www.seek.com.au/api/jobsearch/v5/search"
    graphql_url = "https://www.seek.com.au/graphql"
    job_url_prefix = "https://www.seek.com.au/job"
    default_site_key = "AU-Main"
    country_code = "AU"
