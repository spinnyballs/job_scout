"""Playwright Browser Assisted-Apply Copilot (SEEK Quick Apply + External ATS helper).

Safety model:
- Uses a persistent local Chrome/Edge profile directory (`data/browser_profile/`) so you sign into SEEK once normally.
- NEVER clicks the final Submit button automatically; pauses at the review step for human inspection.
- Reuses your Q&A Memory Bank + LLM drafted answers to fill screening forms, and surfaces unmatched questions.
"""
from __future__ import annotations

import asyncio
import logging
import re
import shutil
from pathlib import Path
from typing import Any

from playwright.async_api import BrowserContext, Page, Playwright, async_playwright

from .models import QABankEntry

logger = logging.getLogger("job_scout.copilot")
BROWSER_PROFILE_DIR = Path(__file__).resolve().parent.parent / "data" / "browser_profile"


def _find_installed_browser() -> str | None:
    for p in (
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    ):
        if Path(p).exists():
            return p
    return None


def _norm_q(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", (text or "").lower()).strip()


def match_answer_for_question(
    question_text: str,
    drafted_answers: dict[str, str],
    qa_entries: list[QABankEntry],
) -> tuple[str | None, str]:
    """Find best answer from job-specific LLM drafts or canonical Q&A Memory Bank."""
    q_norm = _norm_q(question_text)
    if not q_norm:
        return None, ""

    # 1. Exact or fuzzy match against job-specific AI-drafted answers
    for k, ans in drafted_answers.items():
        if not ans:
            continue
        k_norm = _norm_q(k)
        if k_norm == q_norm or k_norm in q_norm or q_norm in k_norm:
            return ans, "tailored_draft"

    # 2. Keyword overlap against Q&A Memory Bank (weighted by distinctive words)
    _STOP = {"the", "your", "you", "have", "what", "which", "following", "statements", "best", "describes", "are", "for", "with", "current", "new", "zealand"}
    q_words = {w for w in q_norm.split() if len(w) >= 3}
    q_sig = q_words - _STOP or q_words
    best_score = 0.0
    best_ans: str | None = None
    best_src = ""
    for entry in qa_entries:
        pat_norm = _norm_q(entry.question_pattern)
        pat_words = {w for w in pat_norm.split() if len(w) >= 3}
        if not pat_words:
            continue
        pat_sig = pat_words - _STOP or pat_words
        if not (q_sig & pat_sig):
            continue
        base_overlap = len(q_words & pat_words) / max(1, len(pat_words))
        sig_overlap = len(q_sig & pat_sig) / max(1, len(pat_sig))
        score = 0.2 * base_overlap + 0.8 * sig_overlap
        if "right to work" in q_norm and "australia" in q_norm and "australia" in pat_norm:
            score += 0.45
        if "right to work" in q_norm and "new zealand" in q_norm and "new zealand" in pat_norm:
            score += 0.45
        if score > best_score:
            best_score = score
            best_ans = entry.answer
            best_src = f"qa_bank:{entry.canonical_key}"

    if best_score >= 0.24 and best_ans:
        return best_ans, best_src
    return None, ""


class ApplyCopilotManager:
    """Manages a persistent Playwright browser window for assisted SEEK / ATS applications."""

    def __init__(self, user_data_dir: Path = BROWSER_PROFILE_DIR) -> None:
        self.user_data_dir = Path(user_data_dir)
        self.user_data_dir.mkdir(parents=True, exist_ok=True)
        self._pw: Playwright | None = None
        self._ctx: BrowserContext | None = None
        self._lock = asyncio.Lock()
        self.last_session: dict[str, Any] = {
            "active": False,
            "stage": "idle",
            "message": "Browser copilot not launched yet.",
            "job_id": None,
            "filled_fields": [],
            "unmatched_questions": [],
            "current_url": "",
        }

    @staticmethod
    def _is_context_alive(ctx: BrowserContext | None) -> bool:
        if ctx is None:
            return False
        try:
            browser = ctx.browser
            if browser is not None and not browser.is_connected():
                return False
            _ = ctx.pages
            return True
        except Exception:
            return False

    @staticmethod
    def _has_dashboard_tab(ctx: BrowserContext | None) -> bool:
        if ctx is None:
            return False
        try:
            return any(
                not p.is_closed() and ("127.0.0.1" in (p.url or "") or "localhost" in (p.url or ""))
                for p in ctx.pages
            )
        except Exception:
            return False

    async def _ensure_context(self) -> BrowserContext:
        if self._is_context_alive(self._ctx):
            return self._ctx  # type: ignore[return-value]
        self._ctx = None

        if self._pw is None:
            self._pw = await async_playwright().start()

        exe = _find_installed_browser()
        # Use Playwright's Chromium with isolated user_data_dir so it never collides with your everyday browser.
        launch_kwargs: dict[str, Any] = {
            "user_data_dir": str(self.user_data_dir),
            "headless": False,
            "args": [
                "--disable-blink-features=AutomationControlled",
                "--no-first-run",
                "--no-default-browser-check",
                "--hide-crash-restore-bubble",
                "--new-window",
                "--start-maximized",
            ],
            "no_viewport": True,
        }

        async def _launch(kwargs: dict[str, Any]) -> BrowserContext:
            assert self._pw is not None
            ctx = await self._pw.chromium.launch_persistent_context(**kwargs)

            def _on_close(_: Any = None) -> None:
                if self._ctx is ctx:
                    self._ctx = None

            ctx.on("close", _on_close)
            return ctx

        try:
            self._ctx = await _launch(launch_kwargs)
        except Exception as first_exc:
            logger.warning("Default Playwright Chromium launch failed (%s); retrying cleanly.", first_exc)
            # Restart Playwright driver if its transport died
            try:
                if self._pw is not None:
                    await self._pw.stop()
            except Exception:
                pass
            self._pw = await async_playwright().start()

            if exe:
                fallback_kwargs = dict(launch_kwargs)
                fallback_kwargs["executable_path"] = exe
                try:
                    self._ctx = await _launch(fallback_kwargs)
                    return self._ctx
                except Exception as second_exc:
                    logger.exception("Installed browser fallback launch also failed.")
                    raise RuntimeError(
                        f"Could not launch copilot browser ({first_exc}; fallback failed: {second_exc})"
                    ) from second_exc
            self._ctx = await _launch(launch_kwargs)
        return self._ctx

    async def open_dashboard_tab(self, url: str = "http://127.0.0.1:8765") -> None:
        """Open the Job Scout dashboard directly inside the Playwright copilot browser so everything lives in one window."""
        async with self._lock:
            ctx = await self._ensure_context()
            page = next((p for p in ctx.pages if not p.is_closed()), None) or await ctx.new_page()
            # Wait briefly so uvicorn is listening before navigating
            for _ in range(10):
                try:
                    await page.goto(url, wait_until="domcontentloaded", timeout=5000)
                    await page.bring_to_front()
                    return
                except Exception:
                    await asyncio.sleep(0.4)
            await page.goto(url, wait_until="domcontentloaded")
            await page.bring_to_front()

    @staticmethod
    def _is_isolated_desktop() -> bool:
        """Detect if server was launched inside a non-interactive Windows desktop (e.g., agent runner)."""
        try:
            import ctypes
            hdesk = ctypes.windll.user32.GetThreadDesktop(ctypes.windll.kernel32.GetCurrentThreadId())
            buf = ctypes.create_unicode_buffer(256)
            needed = ctypes.c_uint(0)
            ok = ctypes.windll.user32.GetUserObjectInformationW(hdesk, 2, buf, ctypes.sizeof(buf), ctypes.byref(needed))
            if ok and buf.value and buf.value.lower() != "default":
                return True
        except Exception:
            pass
        return False

    async def open_seek_login_page(self) -> dict[str, Any]:
        async with self._lock:
            ctx = await self._ensure_context()
            # Open SEEK in a dedicated tab inside the SAME browser window rather than overwriting the dashboard tab
            page = next(
                (
                    p
                    for p in ctx.pages
                    if not p.is_closed() and ("seek.com" in (p.url or "") or "seek.co.nz" in (p.url or ""))
                ),
                None,
            )
            if page is None:
                # Reuse an initial blank tab if one exists, otherwise open a new tab in the same browser window
                blank = next(
                    (p for p in ctx.pages if not p.is_closed() and p.url in ("", "about:blank")),
                    None,
                )
                page = blank if blank else await ctx.new_page()

            try:
                await page.goto("https://nz.seek.com/", wait_until="domcontentloaded", timeout=25000)
            except Exception:
                # Re-open a fresh tab if the reused page crashed or became stale mid-call
                page = await ctx.new_page()
                await page.goto("https://nz.seek.com/", wait_until="domcontentloaded", timeout=25000)

            await page.wait_for_timeout(1500)
            signed_in = await self._detect_seek_signed_in(page)
            if not signed_in:
                navigated = False
                try:
                    sign_in = page.locator('a[href*="/oauth/login"]:visible, a[data-automation="sign in"]:visible').first
                    if await sign_in.count() > 0:
                        await sign_in.click(timeout=5000)
                        await page.wait_for_load_state("domcontentloaded", timeout=15000)
                        navigated = True
                except Exception:
                    navigated = False
                if not navigated:
                    await page.goto("https://nz.seek.com/oauth/login", wait_until="domcontentloaded", timeout=25000)
                await page.wait_for_timeout(1200)
            await page.bring_to_front()
            in_same_window = self._has_dashboard_tab(ctx)
            self.last_session = {
                "active": True,
                "stage": "logged_in" if signed_in else "login",
                "signed_in": signed_in,
                "has_dashboard_tab": in_same_window,
                "message": (
                    "Verified: Signed into SEEK in this browser window."
                    if signed_in
                    else "Opened SEEK sign-in tab. Sign in there, then click 'SEEK: Check Sign-In' to confirm."
                ),
                "job_id": None,
                "filled_fields": [],
                "unmatched_questions": [],
                "current_url": page.url,
            }
            return self.last_session

    async def _detect_seek_signed_in(self, page: Page) -> bool:
        try:
            url = page.url.lower()
            if "login" in url or "auth0" in url or "oauth" in url:
                return False
            # 1. Check if any visible "Sign in" button/link exists on the page (mobile links are hidden, desktop is index ~4)
            sign_in_links = page.locator('a[href*="/oauth/login"]:visible, a[data-automation="sign in"]:visible, button[data-automation="sign in"]:visible')
            if await sign_in_links.count() > 0:
                return False

            # 2. Check for explicit signed-in user account menu or auth token cookie
            user_menu = page.locator('[data-automation="user-account-menu"]:visible, [data-automation="account-menu"]:visible').first
            if await user_menu.count() > 0:
                return True

            cookies = await page.context.cookies("https://nz.seek.com")
            cookie_names = {c.get("name", "") for c in cookies}
            # Note: JobseekerSessionId & hubble_temp_acq_session are anonymous tracking cookies, NOT login cookies!
            return any(k in cookie_names for k in ("auth0.is.authenticated", "SOL_AUTH", "access_token", "seek_auth")) or any(
                k.startswith("auth0") for k in cookie_names
            )
        except Exception:
            return False

    async def check_seek_session(self, verify_live: bool = False) -> dict[str, Any]:
        """Check if persistent browser profile exists and optionally inspect live SEEK cookies."""
        if not self._is_context_alive(self._ctx):
            self._ctx = None
        has_profile_files = any(self.user_data_dir.iterdir()) if self.user_data_dir.exists() else False
        signed_in = None
        if verify_live and self._ctx is not None:
            async with self._lock:
                if self._is_context_alive(self._ctx):
                    try:
                        page = next(
                            (
                                p
                                for p in self._ctx.pages
                                if not p.is_closed() and ("seek.com" in (p.url or "") or "seek.co.nz" in (p.url or ""))
                            ),
                            None,
                        )
                        if page is None:
                            page = await self._ctx.new_page()
                            await page.goto("https://nz.seek.com/", wait_until="domcontentloaded", timeout=20000)
                        signed_in = await self._detect_seek_signed_in(page)
                    except Exception:
                        signed_in = None
        return {
            "browser_running": self._is_context_alive(self._ctx),
            "has_dashboard_tab": self._has_dashboard_tab(self._ctx),
            "profile_initialized": has_profile_files,
            "signed_in": signed_in,
            "isolated_desktop": self._is_isolated_desktop(),
            "installed_browser": _find_installed_browser() or "playwright-chromium",
            "last_session": self.last_session,
        }

    async def logout_seek(self) -> dict[str, Any]:
        """Clear SEEK cookies/storage cleanly while keeping the dashboard browser window alive if open."""
        async with self._lock:
            if not self._is_context_alive(self._ctx):
                self._ctx = None

            if self._ctx is not None:
                try:
                    # Close any open SEEK/Auth0 tabs first
                    for p in list(self._ctx.pages):
                        url = p.url or ""
                        if not p.is_closed() and ("seek.com" in url or "seek.co.nz" in url or "auth0.com" in url):
                            await p.close()
                    await self._ctx.clear_cookies()
                    # Also purge localStorage/IndexedDB for SEEK/Auth0 origins so SPA tokens cannot auto-reauth
                    temp_page: Page | None = None
                    cdp_page = next((p for p in self._ctx.pages if not p.is_closed()), None)
                    if cdp_page is None:
                        temp_page = await self._ctx.new_page()
                        cdp_page = temp_page
                    try:
                        cdp = await self._ctx.new_cdp_session(cdp_page)
                        for origin in (
                            "https://nz.seek.com",
                            "https://www.seek.co.nz",
                            "https://seek.co.nz",
                            "https://login.seek.com",
                            "https://id.seek.com",
                            "https://www.seek.com.au",
                        ):
                            try:
                                await cdp.send(
                                    "Storage.clearDataForOrigin",
                                    {
                                        "origin": origin,
                                        "storageTypes": "cookies,local_storage,indexeddb,cache_storage,service_workers",
                                    },
                                )
                            except Exception:
                                pass
                        await cdp.detach()
                    finally:
                        if temp_page is not None and not temp_page.is_closed():
                            await temp_page.close()
                except Exception:
                    logger.exception("Non-fatal error while clearing SEEK session in active context")

                # Only wipe disk directory if no dashboard tab is open in this window
                if not self._has_dashboard_tab(self._ctx):
                    try:
                        await self._ctx.close()
                    except Exception:
                        pass
                    self._ctx = None

            if self._ctx is None and self.user_data_dir.exists():
                for child in list(self.user_data_dir.iterdir()):
                    try:
                        if child.is_dir():
                            shutil.rmtree(child, ignore_errors=True)
                        else:
                            child.unlink(missing_ok=True)
                    except Exception:
                        pass

            self.last_session = {
                "active": False,
                "stage": "logged_out",
                "signed_in": False,
                "has_dashboard_tab": self._has_dashboard_tab(self._ctx),
                "message": "Signed out of SEEK and cleared local copilot browser session.",
                "job_id": None,
                "filled_fields": [],
                "unmatched_questions": [],
                "current_url": "",
            }
            return self.last_session

    async def prepare_application(
        self,
        *,
        job: dict[str, Any],
        qa_entries: list[QABankEntry],
        cv_pdf_path: str | None,
        cover_letter_pdf_path: str | None,
        cover_letter_text: str,
        drafted_answers: dict[str, str],
    ) -> dict[str, Any]:
        """Open SEEK / external ATS page, pre-fill documents & screening Q&A, stop BEFORE Submit."""
        async with self._lock:
            ctx = await self._ensure_context()
            blank = next((p for p in ctx.pages if not p.is_closed() and p.url in ("", "about:blank")), None)
            page = blank if blank else await ctx.new_page()
            filled: list[str] = []
            unmatched: list[str] = []

            job_url = (job.get("url") or "").strip()
            if not job_url:
                self.last_session = {
                    "active": False,
                    "stage": "no_url",
                    "message": "No posting URL was saved for this pasted job. Open the application page in your browser and use the tailored CV/Cover Letter PDFs above.",
                    "job_id": job["id"],
                    "filled_fields": filled,
                    "unmatched_questions": unmatched,
                    "current_url": "",
                }
                return self.last_session

            # Use nz.seek.com so the job page shares the exact domain/cookies as SEEK Browser Login and avoids www.seek.co.nz challenges
            if "www.seek.co.nz/job/" in job_url:
                job_url = job_url.replace("www.seek.co.nz/job/", "nz.seek.com/job/")
            elif "seek.co.nz/job/" in job_url:
                job_url = job_url.replace("seek.co.nz/job/", "nz.seek.com/job/")

            logger.info("Copilot opening job %s: %s (cv_pdf=%s)", job.get("id"), job_url, cv_pdf_path)
            await page.goto(job_url, wait_until="domcontentloaded", timeout=25000)
            await page.bring_to_front()

            is_external = bool(job.get("is_link_out"))
            is_seek_url = "seek.co" in job_url.lower() or "seek.com" in job_url.lower()

            if is_seek_url:
                apply_btn = page.locator(
                    'a[data-automation="job-detail-apply"]:visible, '
                    'button[data-automation="job-detail-apply"]:visible, '
                    'a[data-testid="job-detail-apply"]:visible, '
                    'button[data-testid="job-detail-apply"]:visible'
                ).first
                try:
                    await apply_btn.wait_for(state="visible", timeout=6000)
                except Exception:
                    await page.wait_for_timeout(1200)

                if await apply_btn.count() > 0:
                    href = await apply_btn.get_attribute("href")
                    target_attr = await apply_btn.get_attribute("target")
                    if href and "seek.co" not in href and "seek.com" not in href and href.startswith("http"):
                        is_external = True

                if is_external or target_attr == "_blank":
                    try:
                        async with ctx.expect_page(timeout=5000) as popup_info:
                            await apply_btn.click()
                        popup = await popup_info.value
                        await popup.wait_for_load_state("domcontentloaded", timeout=15000)
                        page = popup
                        filled.append("Opened application page in new tab")
                    except Exception:
                        await page.wait_for_timeout(1800)
                        filled.append("Clicked Apply button")
                else:
                    await apply_btn.click()
                    try:
                        await page.wait_for_load_state("domcontentloaded", timeout=15000)
                    except Exception:
                        pass
                    await page.wait_for_timeout(1500)
                    filled.append("Opened SEEK Quick Apply form")

            current_url = page.url

            # Handle SEEK login wall gracefully if user isn't signed in yet
            if "login" in current_url.lower() or "auth0" in current_url.lower() or "oauth" in current_url.lower():
                self.last_session = {
                    "active": True,
                    "stage": "needs_login",
                    "message": "SEEK asked to sign in. Please sign in inside the browser window, then click 'Prepare SEEK Quick Apply' again.",
                    "job_id": job["id"],
                    "filled_fields": filled,
                    "unmatched_questions": unmatched,
                    "current_url": current_url,
                }
                return self.last_session

            # Run SEEK Quick Apply multi-step pre-filler (capped at 6 steps, NEVER clicks Submit)
            if "seek.co" in current_url or "seek.com" in current_url:
                await self._prefill_seek_apply_flow(
                    page=page,
                    cv_pdf_path=cv_pdf_path,
                    cover_letter_pdf_path=cover_letter_pdf_path,
                    cover_letter_text=cover_letter_text,
                    drafted_answers=drafted_answers,
                    qa_entries=qa_entries,
                    filled=filled,
                    unmatched=unmatched,
                )
                stage = "ready_for_review"
                msg = (
                    "Pre-filled SEEK application and paused BEFORE Submit. Review in browser and submit manually."
                    if filled
                    else "Opened SEEK apply flow. Complete any remaining fields and submit manually in browser."
                )
            else:
                await self._try_upload_resume_generic(page, cv_pdf_path, filled)
                stage = "external_ats_opened"
                msg = (
                    "Opened external company ATS portal. Use the copy-ready CV/Cover Letter & Q&A drawer in Job Scout to finish & submit."
                )

            self.last_session = {
                "active": True,
                "stage": stage,
                "message": msg,
                "job_id": job["id"],
                "filled_fields": filled,
                "unmatched_questions": unmatched,
                "current_url": page.url,
                "is_external": is_external,
            }
            logger.info("Copilot finished job %s: stage=%s filled=%s unmatched=%s", job.get("id"), stage, filled, unmatched)
            return self.last_session

    async def _try_upload_resume_generic(
        self, page: Page, cv_pdf_path: str | None, filled: list[str]
    ) -> None:
        if not cv_pdf_path or not Path(cv_pdf_path).exists():
            return
        file_inputs = page.locator('input[type="file"]')
        count = await file_inputs.count()
        if count > 0:
            try:
                await file_inputs.first.set_input_files(str(Path(cv_pdf_path).resolve()))
                filled.append(f"Attached CV PDF ({Path(cv_pdf_path).name})")
            except Exception as exc:
                logger.warning("Generic resume upload failed: %s", exc)

    async def _detect_seek_step(self, page: Page) -> str:
        """Identify current SEEK Quick Apply step: 'review', 'documents', 'questions', 'profile', or 'unknown'."""
        url = (page.url or "").lower()
        submit_btn = page.locator(
            'button[data-testid="submit-application"]:visible, '
            'button[data-automation="submit-application"]:visible, '
            'button:visible:has-text("Submit application")'
        )
        if await submit_btn.count() > 0 or "/review" in url:
            return "review"

        heading_txt = ""
        try:
            headings = page.locator("h1:visible, h2:visible")
            h_parts = []
            for i in range(min(4, await headings.count())):
                h_parts.append((await headings.nth(i).inner_text()).strip().lower())
            heading_txt = " | ".join(h_parts)
        except Exception:
            pass

        if "/documents" in url or "/role" in url or "choose documents" in heading_txt:
            return "documents"
        if "/questions" in url or "employer questions" in heading_txt or "answer employer" in heading_txt:
            return "questions"
        if "/profile" in url or "update your seek profile" in heading_txt or "seek profile" in heading_txt:
            return "profile"

        # DOM fallback detection
        has_doc_controls = await page.locator(
            'label:has-text("Resum"), label:has-text("cover letter"), '
            'legend:has-text("Resum"), legend:has-text("Cover letter"), '
            'input[type="file"]'
        ).count() > 0
        if has_doc_controls:
            return "documents"

        has_questions = await page.locator(
            '[data-testid^="question-"], [data-automation^="question-"], fieldset'
        ).count() > 0
        if has_questions:
            return "questions"

        return "unknown"

    async def _prefill_seek_apply_flow(
        self,
        *,
        page: Page,
        cv_pdf_path: str | None,
        cover_letter_pdf_path: str | None,
        cover_letter_text: str,
        drafted_answers: dict[str, str],
        qa_entries: list[QABankEntry],
        filled: list[str],
        unmatched: list[str],
    ) -> None:
        seen_steps: list[str] = []
        for step_idx in range(6):
            await page.wait_for_timeout(1200)
            step = await self._detect_seek_step(page)
            logger.info("SEEK Quick Apply step %s: %s (url=%s)", step_idx + 1, step, page.url)

            if step == "review":
                filled.append("Reached final Review screen (paused before Submit)")
                break

            unmatched_before = len(unmatched)
            if step == "documents":
                await self._fill_documents_step(
                    page, cv_pdf_path, cover_letter_pdf_path, cover_letter_text, filled
                )
            elif step == "questions":
                await self._fill_questions_step(page, drafted_answers, qa_entries, filled, unmatched)
            elif step == "profile":
                filled.append("Passed SEEK Profile step")
            else:
                # Unknown intermediate page — attempt documents if file/cover controls exist, else questions
                await self._fill_documents_step(
                    page, cv_pdf_path, cover_letter_pdf_path, cover_letter_text, filled
                )

            # Stop if new unmatched required screening questions were found on the Questions step
            if step == "questions" and len(unmatched) > unmatched_before:
                logger.info("Stopping auto-advance on questions step due to unmatched questions: %s", unmatched)
                break

            continue_btn = page.locator(
                'button[data-testid="continue-button"]:visible, '
                'button[data-automation="continue-button"]:visible, '
                'button[data-testid*="continue"]:visible, '
                'button:visible:has-text("Continue"), '
                'button:visible:has-text("Save and continue")'
            ).first
            if await continue_btn.count() == 0:
                break

            btn_text = (await continue_btn.inner_text()).strip().lower()
            if "submit" in btn_text:
                filled.append("Paused before final Submit button")
                break

            try:
                if not await continue_btn.is_enabled():
                    logger.info("Continue button disabled at step=%s", step)
                    break
            except Exception:
                pass

            prev_url = page.url
            prev_step = step
            seen_steps.append(f"{prev_url}#{prev_step}")
            try:
                await continue_btn.scroll_into_view_if_needed()
                await continue_btn.click()
            except Exception as exc:
                logger.warning("Failed clicking Continue on step=%s: %s", step, exc)
                break

            # Wait up to 8s for SEEK SPA to advance URL or step heading
            advanced = False
            for _ in range(16):
                await page.wait_for_timeout(500)
                new_step = await self._detect_seek_step(page)
                if page.url != prev_url or new_step != prev_step:
                    advanced = True
                    break
            if not advanced:
                logger.info("Continue click did not advance past %s (likely validation error on page)", prev_step)
                break

    async def _find_scope(self, page: Page, selectors: tuple[str, ...]):
        for sel in selectors:
            loc = page.locator(sel).first
            try:
                if await loc.count() > 0:
                    return loc
            except Exception:
                pass
        return page.locator("body")

    async def _fill_documents_step(
        self,
        page: Page,
        cv_pdf_path: str | None,
        cover_letter_pdf_path: str | None,
        cover_letter_text: str,
        filled: list[str],
    ) -> None:
        resume_scope = await self._find_scope(
            page,
            (
                'fieldset:has(legend:text-matches("resum", "i"))',
                'section:has(h2:text-matches("resum", "i"))',
                '[data-testid*="resume" i]',
                '[data-automation*="resume" i]',
                'div:has(> h3:text-matches("resum", "i"))',
            ),
        )

        # 1. Attach CV PDF
        if cv_pdf_path and Path(cv_pdf_path).exists():
            resolved_cv = str(Path(cv_pdf_path).resolve())
            cv_name = Path(cv_pdf_path).name
            uploaded_cv = False

            # Click "Upload a resumé" / "Upload a new resumé" radio/label inside resume section
            for sel in (
                'label:has-text("Upload a resumé")',
                'label:has-text("Upload a resume")',
                'label:has-text("Upload a new resumé")',
                'label:has-text("Upload a new resume")',
                'input[type="radio"][value*="upload" i]',
                '[data-testid*="upload-resume" i]',
                'button:has-text("Upload a resumé")',
                'button:has-text("Upload a resume")',
                'button:has-text("Change resumé")',
                'button:has-text("Change resume")',
            ):
                loc = resume_scope.locator(sel).first
                if await loc.count() > 0:
                    try:
                        await loc.scroll_into_view_if_needed()
                        await loc.click(force=True)
                        await page.wait_for_timeout(500)
                        break
                    except Exception:
                        pass

            # Prefer file input inside resume_scope; fallback to page-level resume-like file input
            cv_input = resume_scope.locator('input[type="file"]').first
            if await cv_input.count() == 0:
                cv_input = page.locator(
                    'input[type="file"][data-testid*="resume" i], '
                    'input[type="file"][id*="resume" i], '
                    'input[type="file"][name*="resume" i], '
                    'input[type="file"]'
                ).first

            try:
                await cv_input.wait_for(state="attached", timeout=3500)
                await cv_input.set_input_files(resolved_cv)
                # Wait up to 6s for SEEK upload indicator / filename to appear
                for _ in range(12):
                    await page.wait_for_timeout(500)
                    try:
                        body_txt = await resume_scope.inner_text()
                        if cv_name.lower() in body_txt.lower() or "remove" in body_txt.lower() or "uploaded" in body_txt.lower():
                            break
                    except Exception:
                        pass
                filled.append(f"Uploaded tailored CV: {cv_name}")
                uploaded_cv = True
            except Exception as exc:
                logger.warning("SEEK resume file upload failed (path=%s): %s", resolved_cv, exc)

            if not uploaded_cv:
                filled.append(f"CV file ready locally (attach manually if needed): {cv_name}")
        elif cv_pdf_path:
            logger.warning("Tailored CV path does not exist on disk: %s", cv_pdf_path)

        # 2. Cover Letter: prefer "Write a cover letter" textarea, else upload PDF
        cover_scope = await self._find_scope(
            page,
            (
                'fieldset:has(legend:text-matches("cover\\s*letter", "i"))',
                'section:has(h2:text-matches("cover\\s*letter", "i"))',
                '[data-testid*="cover" i]',
                '[data-automation*="cover" i]',
                'div:has(> h3:text-matches("cover\\s*letter", "i"))',
            ),
        )

        if cover_letter_text:
            write_cl = cover_scope.locator(
                'label:has-text("Write a cover letter"), '
                'input[type="radio"][value*="write" i]'
            ).first
            if await write_cl.count() == 0:
                write_cl = page.locator('label:has-text("Write a cover letter")').first
            if await write_cl.count() > 0:
                try:
                    await write_cl.scroll_into_view_if_needed()
                    await write_cl.click(force=True)
                    await page.wait_for_timeout(450)
                except Exception:
                    pass
            textarea = cover_scope.locator('textarea').first
            if await textarea.count() == 0:
                textarea = page.locator('textarea[name*="cover" i], textarea[data-testid*="cover" i], textarea:visible').first
            if await textarea.count() > 0:
                try:
                    await textarea.scroll_into_view_if_needed()
                    await textarea.fill(cover_letter_text)
                    filled.append("Filled cover letter text")
                except Exception as exc:
                    logger.warning("Failed filling cover letter textarea: %s", exc)
        elif cover_letter_pdf_path and Path(cover_letter_pdf_path).exists():
            upload_cl = cover_scope.locator(
                'label:has-text("Upload a cover letter"), input[type="radio"][value*="upload" i]'
            ).first
            if await upload_cl.count() == 0:
                upload_cl = page.locator('label:has-text("Upload a cover letter")').first
            if await upload_cl.count() > 0:
                try:
                    await upload_cl.click(force=True)
                    await page.wait_for_timeout(450)
                    cl_input = cover_scope.locator('input[type="file"]').first
                    if await cl_input.count() == 0:
                        file_inputs = page.locator('input[type="file"]')
                        if await file_inputs.count() >= 2:
                            cl_input = file_inputs.nth(1)
                    if await cl_input.count() > 0:
                        await cl_input.set_input_files(str(Path(cover_letter_pdf_path).resolve()))
                        await page.wait_for_timeout(1200)
                        filled.append(f"Uploaded cover letter PDF: {Path(cover_letter_pdf_path).name}")
                except Exception as exc:
                    logger.warning("Failed uploading cover letter PDF: %s", exc)

    @staticmethod
    def _score_option_match(opt_text: str, ans: str) -> float:
        opt_norm = _norm_q(opt_text)
        ans_norm = _norm_q(ans)
        if not opt_norm or not ans_norm:
            return 0.0
        if opt_norm == ans_norm:
            return 1.0
        if opt_norm in ans_norm or ans_norm in opt_norm:
            return 0.85

        ans_starts_yes = bool(re.match(r"^yes\b", ans_norm))
        ans_starts_no = bool(re.match(r"^no\b", ans_norm))
        if ans_starts_yes and re.match(r"^yes\b", opt_norm):
            return 0.8
        if ans_starts_no and re.match(r"^no\b", opt_norm):
            return 0.8

        if "citizen" in ans_norm and "citizen" in opt_norm:
            return 0.82
        if "permanent resident" in ans_norm and "permanent resident" in opt_norm:
            return 0.82
        if "full" in ans_norm and "licence" in ans_norm and ("full" in opt_norm or "yes" in opt_norm):
            return 0.78

        # Numeric match (e.g. "$80,000" or "2 years" or "4 weeks")
        ans_nums = re.findall(r"\d+(?:,\d+)*", ans)
        opt_nums = re.findall(r"\d+(?:,\d+)*", opt_text)
        if ans_nums and opt_nums:
            a_vals = [int(n.replace(",", "")) for n in ans_nums]
            o_vals = [int(n.replace(",", "")) for n in opt_nums]
            # Handle "$75k" style shorthand
            if "k" in opt_text.lower():
                o_vals = [v * 1000 if v < 1000 else v for v in o_vals]
            if len(a_vals) >= 2 and len(o_vals) == 1:
                lo, hi = min(a_vals[0], a_vals[1]), max(a_vals[0], a_vals[1])
                if lo <= o_vals[0] <= hi:
                    return 0.75
            if a_vals[0] == o_vals[0]:
                return 0.75

        opt_words = {w for w in opt_norm.split() if len(w) >= 3}
        ans_words = {w for w in ans_norm.split() if len(w) >= 3}
        if opt_words and ans_words:
            overlap = len(opt_words & ans_words) / len(opt_words)
            if overlap >= 0.6:
                return 0.55 + 0.2 * overlap
        return 0.0

    async def _fill_questions_step(
        self,
        page: Page,
        drafted_answers: dict[str, str],
        qa_entries: list[QABankEntry],
        filled: list[str],
        unmatched: list[str],
    ) -> None:
        fieldsets = page.locator('[data-testid^="question-"], [data-automation^="question-"], fieldset')
        count = await fieldsets.count()
        for i in range(count):
            fs = fieldsets.nth(i)
            try:
                if not await fs.is_visible():
                    continue
            except Exception:
                continue

            # Extract the actual question prompt (prioritize legend / headings / non-option prompt elements)
            q_text = ""
            for prompt_sel in ("legend", "h3", "h4", "[data-testid*='question']", "p", "label"):
                loc = fs.locator(prompt_sel).first
                if await loc.count() > 0:
                    cand = (await loc.inner_text()).strip()
                    if cand and len(cand) >= 6:
                        q_text = cand
                        break
            if not q_text:
                continue

            # Skip non-question document / profile sections if any appear as fieldsets
            q_low = q_text.lower()
            if any(skip in q_low for skip in ("resumé", "resume", "cover letter", "choose documents", "seek profile")):
                continue

            ans, src = match_answer_for_question(q_text, drafted_answers, qa_entries)
            if not ans:
                if q_text not in unmatched:
                    unmatched.append(q_text)
                continue

            # 1. Radio buttons
            radios = fs.locator('input[type="radio"]')
            r_count = await radios.count()
            if r_count > 0:
                best_lbl = None
                best_opt = ""
                best_score = 0.0
                for r_idx in range(r_count):
                    radio = radios.nth(r_idx)
                    rid = await radio.get_attribute("id")
                    lbl = fs.locator(f'label[for="{rid}"]').first if rid else radio.locator("xpath=ancestor::label[1]")
                    if await lbl.count() == 0:
                        lbl = radio.locator("..")
                    opt_text = (await lbl.inner_text()).strip() if await lbl.count() > 0 else ""
                    sc = self._score_option_match(opt_text, ans)
                    if sc > best_score:
                        best_score = sc
                        best_lbl = lbl
                        best_opt = opt_text
                if best_lbl is not None and best_score >= 0.55:
                    try:
                        await best_lbl.scroll_into_view_if_needed()
                        await best_lbl.click()
                        filled.append(f"Answered '{q_text[:38]}...' -> '{best_opt}' ({src})")
                    except Exception as exc:
                        logger.warning("Radio click failed for '%s': %s", q_text[:40], exc)
                elif q_text not in unmatched:
                    unmatched.append(q_text)
                continue

            # 2. Checkboxes (multi-select)
            checks = fs.locator('input[type="checkbox"]')
            c_count = await checks.count()
            if c_count > 0:
                clicked_any = False
                for c_idx in range(c_count):
                    chk = checks.nth(c_idx)
                    cid = await chk.get_attribute("id")
                    lbl = fs.locator(f'label[for="{cid}"]').first if cid else chk.locator("xpath=ancestor::label[1]")
                    if await lbl.count() == 0:
                        lbl = chk.locator("..")
                    opt_text = (await lbl.inner_text()).strip() if await lbl.count() > 0 else ""
                    if self._score_option_match(opt_text, ans) >= 0.55:
                        try:
                            if not await chk.is_checked():
                                await lbl.scroll_into_view_if_needed()
                                await lbl.click()
                            filled.append(f"Checked '{opt_text}' for '{q_text[:32]}...' ({src})")
                            clicked_any = True
                        except Exception:
                            pass
                if not clicked_any and q_text not in unmatched:
                    unmatched.append(q_text)
                continue

            # 3. Select dropdown
            sel = fs.locator("select").first
            if await sel.count() > 0:
                opts = sel.locator("option")
                best_val: str | None = None
                best_txt = ""
                best_score = 0.0
                for o_idx in range(await opts.count()):
                    opt = opts.nth(o_idx)
                    txt = (await opt.inner_text()).strip()
                    val = await opt.get_attribute("value")
                    if not txt or not val:
                        continue
                    sc = self._score_option_match(txt, ans)
                    if sc > best_score:
                        best_score = sc
                        best_val = val
                        best_txt = txt
                if best_val is not None and best_score >= 0.55:
                    try:
                        await sel.scroll_into_view_if_needed()
                        await sel.select_option(best_val)
                        filled.append(f"Selected '{best_txt}' for '{q_text[:35]}...'")
                    except Exception as exc:
                        logger.warning("Select option failed for '%s': %s", q_text[:40], exc)
                elif q_text not in unmatched:
                    unmatched.append(q_text)
                continue

            # 4. Number / Text / Textarea input
            text_inp = fs.locator('input[type="number"], input[type="text"], input[type="tel"], input:not([type]), textarea').first
            if await text_inp.count() > 0:
                inp_type = (await text_inp.get_attribute("type") or "").lower()
                fill_val = ans
                if inp_type == "number":
                    nums = re.findall(r"\d+(?:,\d+)*", ans)
                    if nums:
                        fill_val = nums[0].replace(",", "")
                    else:
                        if q_text not in unmatched:
                            unmatched.append(q_text)
                        continue
                try:
                    await text_inp.scroll_into_view_if_needed()
                    await text_inp.fill(fill_val)
                    filled.append(f"Filled '{q_text[:38]}...' ({src})")
                except Exception as exc:
                    logger.warning("Text fill failed for '%s': %s", q_text[:40], exc)

    async def aclose(self) -> None:
        if self._ctx:
            await self._ctx.close()
            self._ctx = None
        if self._pw:
            await self._pw.stop()
            self._pw = None
