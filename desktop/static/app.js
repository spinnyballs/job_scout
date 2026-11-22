const state = {
  jobs: [],
  selectedId: null,
  statusTab: "all",
  sources: [],
  config: null,
  pollTimer: null,
  copilotSession: null,
  selectedBatchIds: new Set(),
  tailorQueue: {
    is_running: false,
    current_job_id: null,
    queued_ids: [],
    completed_ids: [],
    failed: [],
    total: 0,
    done_count: 0,
    batch_seq: 0,
    message: "",
    finished_at: null,
  },
  tailorPollTimer: null,
  _lastTailorDoneCount: -1,
  _lastNotifiedBatchSeq: 0,
  _dismissedTailorSeq: 0,
};

const $ = (id) => document.getElementById(id);

function escapeHtml(str) {
  return String(str ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function formatMoney(val) {
  if (!val) return "";
  return `$${Math.round(val / 1000)}k`;
}

function formatSalary(job) {
  if (job.salary_label) return job.salary_label;
  if (job.salary_est_min && job.salary_est_max) {
    return job.salary_est_min === job.salary_est_max
      ? `~${formatMoney(job.salary_est_min)} (est)`
      : `~${formatMoney(job.salary_est_min)}–${formatMoney(job.salary_est_max)} (est)`;
  }
  return "";
}

function formatRelDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const diffHrs = Math.round((Date.now() - d.getTime()) / 3600000);
  if (diffHrs < 1) return "just now";
  if (diffHrs < 24) return `${diffHrs}h ago`;
  const diffDays = Math.floor(diffHrs / 24);
  return `${diffDays}d ago`;
}

function daysSince(iso) {
  if (!iso) return 0;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return 0;
  return Math.max(0, Math.floor((Date.now() - d.getTime()) / 86400000));
}

async function api(url, opts = {}) {
  const res = await fetch(url, {
    headers: { "Content-Type": "application/json" },
    ...opts,
  });
  if (!res.ok) {
    const errText = await res.text();
    let msg = errText || `HTTP ${res.status}`;
    try {
      const parsed = JSON.parse(errText);
      if (parsed && parsed.detail) {
        msg = typeof parsed.detail === "string" ? parsed.detail : JSON.stringify(parsed.detail);
      }
    } catch (_) {}
    throw new Error(msg);
  }
  return res.json();
}

async function loadSources() {
  const data = await api("/api/sources");
  state.sources = data.sources || [];
  const select = $("board-select");
  const cfgSelect = $("cfg-default-board");
  select.innerHTML = "";
  cfgSelect.innerHTML = "";

  for (const s of state.sources) {
    const opt = document.createElement("option");
    opt.value = s.source_id;
    opt.textContent = s.is_ready ? s.display_name : `${s.display_name} (Coming Soon)`;
    opt.disabled = !s.is_ready;
    select.appendChild(opt);

    if (s.is_ready) {
      const opt2 = opt.cloneNode(true);
      cfgSelect.appendChild(opt2);
    }
  }
}

async function loadConfig() {
  state.config = await api("/api/config");
  $("board-select").value = state.config.default_board || "seek_nz";
  const srcObj = state.sources.find((s) => s.source_id === $("board-select").value);
  $("active-board-label").textContent = srcObj ? srcObj.display_name : "SEEK";

  const qFilter = $("query-filter");
  const currentVal = qFilter.value;
  qFilter.innerHTML = '<option value="">All search profiles</option>';
  for (const q of state.config.queries || []) {
    const opt = document.createElement("option");
    opt.value = q.name;
    opt.textContent = q.name;
    qFilter.appendChild(opt);
  }
  qFilter.value = currentVal;
}

async function loadStats() {
  const stats = await api("/api/stats");
  $("stat-total").textContent = stats.total ?? 0;
  $("stat-high").textContent = stats.high_fit ?? 0;
  const shortAndTailored = (stats.by_status?.shortlisted ?? 0) + (stats.by_status?.tailored ?? 0);
  $("stat-shortlisted").textContent = shortAndTailored;
  const appliedAndInt = (stats.by_status?.applied ?? 0) + (stats.by_status?.interview ?? 0);
  $("stat-applied").textContent = appliedAndInt;
  const closedCount = stats.no_reply_closed ?? ((stats.by_status?.no_reply ?? 0) + (stats.by_status?.rejected ?? 0));
  if ($("stat-closed")) $("stat-closed").textContent = closedCount;
  $("stat-blocked").textContent = stats.by_status?.blocked ?? 0;
  if ($("tab-closed-count")) {
    $("tab-closed-count").textContent = closedCount;
  }

  if (stats.last_run) {
    const r = stats.last_run;
    const finished = r.finished_at ? formatRelDate(r.finished_at) : "running";
    $("stat-lastrun").textContent = `${finished} · ${r.new_jobs} new / ${r.total_fetched} scanned (${r.source})`;
  } else {
    $("stat-lastrun").textContent = "Never";
  }
}

async function loadJobs() {
  const params = new URLSearchParams();
  if (state.statusTab !== "all") {
    params.set("status", state.statusTab);
  }
  if (state.statusTab === "blocked") params.set("include_blocked", "true");
  const minScore = parseInt($("minscore-filter").value, 10);
  const bypassScoreTabs = new Set(["blocked", "shortlisted", "tailored", "applied", "interview", "no_reply,rejected"]);
  if (minScore > 0 && !bypassScoreTabs.has(state.statusTab)) {
    params.set("min_score", String(minScore));
  }
  const search = $("search-input").value.trim();
  if (search) params.set("search", search);
  const qTag = $("query-filter").value;
  if (qTag) params.set("query_tag", qTag);
  params.set("sort", $("sort-select").value);

  const data = await api(`/api/jobs?${params.toString()}`);
  state.jobs = data.jobs || [];
  renderJobList();

  if (state.selectedId) {
    const stillVisible = state.jobs.find((j) => j.id === state.selectedId);
    if (!stillVisible && state.jobs.length > 0) {
      selectJob(state.jobs[0].id);
    } else if (!stillVisible) {
      state.selectedId = null;
      $("detail-pane").innerHTML = '<div class="empty-state">No jobs match the current filters.</div>';
    }
  } else if (state.jobs.length > 0) {
    selectJob(state.jobs[0].id);
  }
}

function scoreClass(job) {
  if (job.status === "blocked") return "score-blocked";
  if (job.score >= 60) return "score-high";
  if (job.score >= 35) return "score-mid";
  return "score-low";
}

function updateBatchBar() {
  const selectableVisible = state.jobs.filter((j) => j.status !== "blocked");
  const checkedCount = state.selectedBatchIds.size;
  const visibleChecked = selectableVisible.filter((j) => state.selectedBatchIds.has(j.id)).length;

  const countEl = $("batch-count");
  const queueBtn = $("btn-queue-selected");
  const clearBtn = $("btn-clear-selection");
  const selectAllChk = $("batch-select-visible");

  if (countEl) countEl.textContent = `${checkedCount} selected`;
  if (queueBtn) {
    queueBtn.disabled = checkedCount === 0;
    queueBtn.textContent = `Queue Tailor (${checkedCount})`;
  }
  if (clearBtn) clearBtn.classList.toggle("hidden", checkedCount === 0);
  if (selectAllChk) {
    selectAllChk.disabled = selectableVisible.length === 0;
    selectAllChk.checked = selectableVisible.length > 0 && visibleChecked === selectableVisible.length;
    selectAllChk.indeterminate = visibleChecked > 0 && visibleChecked < selectableVisible.length;
  }
}

function renderJobList() {
  const list = $("job-list");
  if (!state.jobs.length) {
    list.innerHTML = '<div class="empty-state">No jobs match the current filters. Try lowering the score filter or clicking <strong>Run Scan Now</strong>.</div>';
    updateBatchBar();
    return;
  }

  const tq = state.tailorQueue || {};
  const activeSet = new Set(tq.active_ids || (tq.current_job_id ? [tq.current_job_id] : []));
  const queuedSet = new Set(tq.queued_ids || []);

  list.innerHTML = state.jobs
    .map((job) => {
      const sal = formatSalary(job);
      const isChecked = state.selectedBatchIds.has(job.id);
      const isBlocked = job.status === "blocked";
      const isTailoringNow = tq.is_running && activeSet.has(job.id);
      const isQueued = tq.is_running && !isTailoringNow && queuedSet.has(job.id);

      const cls = [
        "job-card",
        job.id === state.selectedId ? "selected" : "",
        isChecked ? "batch-checked" : "",
        job.is_expired ? "is-expired" : "",
        `status-${job.status}`,
      ]
        .filter(Boolean)
        .join(" ");

      const flags = (job.score_breakdown?.flags || []).slice(0, 3);
      const appliedDays = job.status === "applied" && job.applied_at ? daysSince(job.applied_at) : 0;
      const appliedTagHtml =
        job.status === "applied"
          ? appliedDays >= 14
            ? `<span class="tag no-reply" title="Applied ${appliedDays} days ago with no response logged yet">Applied ${appliedDays}d · No reply?</span>`
            : '<span class="tag applied">Applied</span>'
          : "";
      return `
        <div class="${cls}" data-id="${job.id}">
          <div class="job-card-top">
            <div class="job-card-left">
              <input type="checkbox" class="job-check" data-id="${job.id}" ${isChecked ? "checked" : ""} ${isBlocked ? "disabled" : ""} title="Select for batch tailoring" />
              <div class="job-title">${escapeHtml(job.title)}</div>
            </div>
            <span class="score-pill ${scoreClass(job)}">${isBlocked ? "BLOCKED" : job.score}</span>
          </div>
          <div class="job-meta">
            <span class="job-company">${escapeHtml(job.company)}</span>
            <span>· ${escapeHtml(job.location || "NZ")}</span>
            ${job.posted_at ? `<span>· ${escapeHtml(formatRelDate(job.posted_at))}</span>` : ""}
          </div>
          <div class="job-tags">
            ${job.is_expired ? '<span class="tag expired" title="This listing is no longer active or has expired">Expired</span>' : ""}
            ${isTailoringNow ? '<span class="tag queue-active">Tailoring...</span>' : ""}
            ${isQueued ? '<span class="tag queue-pending">Queued</span>' : ""}
            ${job.source === "manual" ? '<span class="tag" style="background:rgba(167,139,250,0.16);color:#ddd6fe;border-color:rgba(167,139,250,0.4);">Pasted JD</span>' : ""}
            ${job.synced_from_phone ? '<span class="tag" style="background:rgba(56,189,248,0.18);color:#7dd3fc;">From Phone</span>' : ""}
            ${sal ? `<span class="tag salary">${escapeHtml(sal)}</span>` : ""}
            ${job.llm_fit_score != null ? `<span class="tag llm-score">AI ${job.llm_fit_score}/100</span>` : ""}
            ${job.has_tailored_cv ? '<span class="tag tailored">CV PDF Ready</span>' : ""}
            ${appliedTagHtml}
            ${job.status === "interview" ? '<span class="tag applied">Interview</span>' : ""}
            ${job.status === "no_reply" ? '<span class="tag no-reply">No Reply</span>' : ""}
            ${job.status === "rejected" ? '<span class="tag rejected">Not Selected</span>' : ""}
            ${job.status === "shortlisted" ? '<span class="tag status-shortlisted">Shortlisted</span>' : ""}
            ${flags.map((f) => `<span class="tag ${f.includes("yrs") || f.includes("Below") ? "flag-warn" : ""}">${escapeHtml(f)}</span>`).join("")}
          </div>
        </div>
      `;
    })
    .join("");

  list.querySelectorAll(".job-check").forEach((chk) => {
    chk.addEventListener("click", (e) => e.stopPropagation());
    chk.addEventListener("change", (e) => {
      const id = Number(chk.dataset.id);
      if (chk.checked) {
        state.selectedBatchIds.add(id);
      } else {
        state.selectedBatchIds.delete(id);
      }
      chk.closest(".job-card")?.classList.toggle("batch-checked", chk.checked);
      updateBatchBar();
    });
  });

  list.querySelectorAll(".job-card").forEach((el) => {
    el.addEventListener("click", () => selectJob(Number(el.dataset.id)));
  });

  updateBatchBar();
}

async function selectJob(jobId) {
  state.selectedId = jobId;
  document.querySelectorAll(".job-card").forEach((el) => {
    el.classList.toggle("selected", Number(el.dataset.id) === jobId);
  });

  const job = await api(`/api/jobs/${jobId}`);
  renderJobDetail(job);
}

function renderJobDetail(job) {
  const pane = $("detail-pane");
  const bd = job.score_breakdown || {};
  const isManual = job.source === "manual";
  const sal = formatSalary(job) || (isManual ? "Not specified" : "Not disclosed (click Probe Salary)");
  const posChips = (bd.matched_positive || []).map((p) => `<span class="chip-pos">+ ${escapeHtml(p)}</span>`).join("");
  const negChips = (bd.matched_negative || []).map((n) => `<span class="chip-neg">- ${escapeHtml(n)}</span>`).join("");
  const queries = (job.matched_queries || []).map((q) => `<span class="tag">${escapeHtml(q)}</span>`).join("");
  const questions = job.screening_questions || [];
  const openBtnHtml = job.url
    ? `<a class="btn btn-primary" href="${escapeHtml(job.url)}" target="_blank" rel="noopener">${isManual ? "Open Posting &nearr;" : "Open on SEEK &nearr;"}</a>`
    : "";

  pane.innerHTML = `
    <div class="detail-header">
      <div>
        <h2>${escapeHtml(job.title)}</h2>
        <div class="detail-sub">
          <strong>${escapeHtml(job.company)}</strong>
          ${job.location ? `<span>· ${escapeHtml(job.location)}</span>` : ""}
          ${job.work_type ? `<span>· ${escapeHtml(job.work_type)}</span>` : ""}
          ${job.work_arrangement ? `<span>· ${escapeHtml(job.work_arrangement)}</span>` : ""}
          <span>· <strong>Salary:</strong> ${escapeHtml(sal)}</span>
          ${job.posted_at ? `<span>· ${isManual ? "Added" : "Posted"} ${escapeHtml(formatRelDate(job.posted_at))}</span>` : ""}
        </div>
      </div>
      <span class="score-pill ${scoreClass(job)}" style="font-size:14px;padding:4px 12px;">
        ${job.status === "blocked" ? "BLOCKED" : `Fit ${job.score}/100`}
      </span>
    </div>

    <div class="detail-actions">
      <button class="btn btn-green" onclick="updateStatus(${job.id}, 'shortlisted')">Shortlist</button>
      <button class="btn btn-red" onclick="updateStatus(${job.id}, 'skipped')">Skip</button>
      <button class="btn btn-secondary" style="border-color:rgba(245,158,11,0.5);color:#fcd34d;" onclick="markJobApplied(${job.id}, 'no_reply')" title="Move to No Reply / Closed (no response received)">No Reply</button>
      <button class="btn btn-secondary" style="border-color:rgba(239,68,68,0.45);color:#fca5a5;" onclick="markJobApplied(${job.id}, 'rejected')" title="Move to No Reply / Closed (did not go through / rejected)">Not Selected</button>
      <button class="btn btn-secondary" onclick="updateStatus(${job.id}, 'discovered')">Reset</button>
      ${!isManual ? `<button class="btn btn-secondary" id="btn-probe-sal" onclick="probeSalary(${job.id})">Estimate Hidden Salary</button>` : ""}
      <button class="btn btn-secondary" onclick="blockCompany(${JSON.stringify(job.company).replaceAll('"', '&quot;')})">Block Company</button>
      ${openBtnHtml}
    </div>

    ${job.is_expired ? `<div class="questions-box" style="border-color:rgba(239,68,68,0.55);background:rgba(239,68,68,0.12)"><h4 style="color:#fca5a5">Listing Expired</h4><div>This job listing is no longer active on ${isManual ? "the job board" : "SEEK"}${job.expires_at ? ` (expired ${escapeHtml(formatRelDate(job.expires_at))})` : ""}. Kept in your pipeline because it is ${escapeHtml(job.status)}.</div></div>` : ""}
    ${job.blocked_reason ? `<div class="questions-box" style="border-color:rgba(239,68,68,0.5);background:rgba(239,68,68,0.1)"><h4>Employer Guardrail Triggered${job.status !== "blocked" ? " (Overridden — Hand-Selected)" : ""}</h4><div>${escapeHtml(job.blocked_reason)}</div></div>` : ""}

    <div class="breakdown-box">
      <h4>Match Breakdown &amp; Search Profiles</h4>
      <div class="chip-row">${posChips || '<span class="muted">No keyword hits</span>'}${negChips}</div>
      ${(bd.notes || []).length ? `<div style="font-size:12px;color:#cbd5e1;margin-bottom:6px">${bd.notes.map(escapeHtml).join(" · ")}</div>` : ""}
      <div class="chip-row">${queries}</div>
    </div>

    ${questions.length ? `
      <div class="questions-box">
        <h4>SEEK Screening Questions (${questions.length})</h4>
        <ul>${questions.map((q) => `<li>${escapeHtml(q)}</li>`).join("")}</ul>
      </div>
    ` : ""}

    ${renderAIPanel(job)}

    ${renderCopilotPanel(job)}

    <div class="notes-box">
      <input id="job-notes-input" type="text" placeholder="Personal notes (e.g., angle for tailored CV, hiring manager)..." value="${escapeHtml(job.user_notes || "")}" />
      <button class="btn btn-small btn-secondary" onclick="saveNotes(${job.id})">Save Note</button>
    </div>

    <div class="detail-body">
      ${job.description_html || `<p>${escapeHtml(job.teaser || "No detailed description fetched yet.")}</p>`}
    </div>
  `;
}

window.updateStatus = async (jobId, status) => {
  await api(`/api/jobs/${jobId}`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
  await Promise.all([loadStats(), loadJobs()]);
  if (state.selectedId === jobId) {
    const updated = await api(`/api/jobs/${jobId}`);
    renderJobDetail(updated);
  }
};

window.saveNotes = async (jobId) => {
  const user_notes = $("job-notes-input").value;
  await api(`/api/jobs/${jobId}`, {
    method: "PATCH",
    body: JSON.stringify({ user_notes }),
  });
};

window.blockCompany = async (companyName) => {
  if (!companyName || !confirm(`Add "${companyName}" to your Blocked Companies guardrail list?`)) return;
  const updated = structuredClone(state.config);
  if (!updated.blocked_companies.includes(companyName)) {
    updated.blocked_companies.push(companyName);
  }
  const res = await api("/api/config", {
    method: "PUT",
    body: JSON.stringify(updated),
  });
  state.config = res.config;
  await Promise.all([loadStats(), loadJobs()]);
};

window.probeSalary = async (jobId) => {
  const btn = $("btn-probe-sal");
  btn.disabled = true;
  btn.textContent = "Probing SEEK range...";
  try {
    const updated = await api(`/api/jobs/${jobId}/probe-salary`, { method: "POST" });
    renderJobDetail(updated);
    await loadJobs();
  } catch (e) {
    alert("Salary probe failed: " + e.message);
  } finally {
    if ($("btn-probe-sal")) {
      $("btn-probe-sal").disabled = false;
      $("btn-probe-sal").textContent = "Estimate Hidden Salary";
    }
  }
};

async function startScan() {
  const source_id = $("board-select").value;
  try {
    await api("/api/scan/start", {
      method: "POST",
      body: JSON.stringify({ source_id }),
    });
    pollScanProgress();
  } catch (e) {
    alert(e.message);
  }
}

function pollScanProgress() {
  if (state.pollTimer) clearInterval(state.pollTimer);
  $("btn-scan").disabled = true;
  $("scan-banner").classList.remove("hidden");

  const tick = async () => {
    const st = await api("/api/scan/status");
    $("scan-stage").textContent = st.stage.toUpperCase();
    $("scan-message").textContent = st.message;
    $("scan-counters").textContent =
      `Queries ${st.queries_done}/${st.queries_total} · Fetched ${st.fetched} · New ${st.new_jobs} · Details ${st.details_done} · Salaries ${st.salaries_done}`;

    if (!st.is_running) {
      clearInterval(state.pollTimer);
      state.pollTimer = null;
      $("btn-scan").disabled = false;
      setTimeout(() => $("scan-banner").classList.add("hidden"), 3500);
      await Promise.all([loadStats(), loadJobs()]);
    }
  };

  tick();
  state.pollTimer = setInterval(tick, 900);
}

/* Config Modal */
function openConfigModal() {
  if (!state.config) return;
  $("cfg-blocked-companies").value = (state.config.blocked_companies || []).join("\n");
  $("cfg-default-board").value = state.config.default_board || "seek_nz";
  $("cfg-probe-threshold").value = state.config.probe_salary_above_score ?? 48;
  renderQueriesEditor(state.config.queries || []);
  $("config-modal").classList.remove("hidden");
}

function renderQueriesEditor(queries) {
  const container = $("cfg-queries-list");
  container.innerHTML = "";
  queries.forEach((q, idx) => {
    const row = document.createElement("div");
    row.className = "query-row";
    row.dataset.index = idx;
    row.innerHTML = `
      <input type="text" class="q-name" value="${escapeHtml(q.name)}" placeholder="Profile name" />
      <input type="text" class="q-keywords" value="${escapeHtml(q.keywords)}" placeholder="Keywords (OR / quotes)" />
      <input type="text" class="q-where" value="${escapeHtml(q.where)}" placeholder="Where" />
      <input type="number" class="q-days" value="${q.date_range_days ?? 14}" title="Max age (days)" min="1" max="31" />
      <label style="display:flex;align-items:center;gap:4px;font-size:12px;"><input type="checkbox" class="q-enabled" ${q.enabled ? "checked" : ""} /> On</label>
      <button class="btn-icon" title="Remove" onclick="this.parentElement.remove()">&times;</button>
    `;
    container.appendChild(row);
  });
}

async function saveConfig() {
  const updated = structuredClone(state.config);
  updated.blocked_companies = $("cfg-blocked-companies")
    .value.split("\n")
    .map((s) => s.trim())
    .filter(Boolean);
  updated.default_board = $("cfg-default-board").value;
  updated.probe_salary_above_score = Number($("cfg-probe-threshold").value || 48);

  const rows = [...document.querySelectorAll("#cfg-queries-list .query-row")];
  updated.queries = rows.map((row, idx) => {
    const orig = state.config.queries[idx] || {};
    return {
      ...orig,
      name: row.querySelector(".q-name").value.trim() || `Query ${idx + 1}`,
      keywords: row.querySelector(".q-keywords").value.trim(),
      where: row.querySelector(".q-where").value.trim() || "All Auckland",
      site_key: updated.default_board === "seek_au" ? "AU-Main" : "NZ-Main",
      date_range_days: Number(row.querySelector(".q-days").value || 14),
      max_pages: orig.max_pages || 2,
      enabled: row.querySelector(".q-enabled").checked,
    };
  });

  const res = await api("/api/config", {
    method: "PUT",
    body: JSON.stringify(updated),
  });
  state.config = res.config;
  $("config-modal").classList.add("hidden");
  await Promise.all([loadConfig(), loadStats(), loadJobs()]);
}

window.closeModal = (id) => $(id).classList.add("hidden");

function renderAIPanel(job) {
  const fit = job.llm_fit;
  const pkg = job.tailored_package;

  let fitHtml = "";
  if (fit) {
    fitHtml = `
      <div style="margin-top:10px;font-size:13px;">
        <strong>Verdict:</strong> ${escapeHtml(fit.verdict)} (${fit.fit_score}/100) ·
        <strong>Angle:</strong> ${escapeHtml(fit.role_angle)}
        <p style="margin:6px 0 4px;color:#dbeafe;">${escapeHtml(fit.rationale)}</p>
      </div>
      <div class="fit-grid">
        <div class="fit-card">
          <h5>Matched Strengths</h5>
          <ul>${(fit.strengths || []).map((s) => `<li>${escapeHtml(s)}</li>`).join("")}</ul>
        </div>
        <div class="fit-card">
          <h5>Gaps &amp; Risks</h5>
          <ul>${(fit.gaps_and_risks || []).map((g) => `<li>${escapeHtml(g)}</li>`).join("")}</ul>
        </div>
      </div>
    `;
  }

  let pkgHtml = "";
  if (pkg) {
    const qAnswers = pkg.suggested_screening_answers || {};
    const qKeys = Object.keys(qAnswers);
    const audit = pkg.truth_audit_notes || [];
    pkgHtml = `
      <div class="artifacts-box">
        <h4>Tailored Application Package (${escapeHtml(pkg.model_used || "AI")})</h4>
        <div class="artifact-links">
          ${pkg.cv_pdf_url ? `<a class="btn btn-small btn-green" href="${escapeHtml(pkg.cv_pdf_url)}" target="_blank">Download Tailored CV (PDF) &nearr;</a>` : ""}
          ${pkg.cv_html_url ? `<a class="btn btn-small btn-secondary" href="${escapeHtml(pkg.cv_html_url)}" target="_blank">Preview CV (HTML)</a>` : ""}
          ${pkg.cover_letter_pdf_url ? `<a class="btn btn-small btn-green" href="${escapeHtml(pkg.cover_letter_pdf_url)}" target="_blank">Download Cover Letter (PDF) &nearr;</a>` : ""}
          ${pkg.cover_letter_html_url ? `<a class="btn btn-small btn-secondary" href="${escapeHtml(pkg.cover_letter_html_url)}" target="_blank">Preview Cover Letter</a>` : ""}
        </div>
        <div style="font-size:12px;color:#cbd5e1;margin-bottom:6px">
          <strong>CV Headline:</strong> ${escapeHtml(pkg.tailored_headline)}
        </div>
        <div class="cover-preview">${escapeHtml(pkg.cover_letter_body || "")}</div>
        ${qKeys.length ? `
          <div style="margin-top:10px">
            <strong style="font-size:12px;color:#fcd34d;">Drafted Screening Answers:</strong>
            <ul style="margin:4px 0 0;padding-left:18px;font-size:12.5px;">
              ${qKeys.map((k) => `<li><strong>${escapeHtml(k)}:</strong> ${escapeHtml(qAnswers[k])}</li>`).join("")}
            </ul>
          </div>
        ` : ""}
        ${audit.length ? `
          <div style="margin-top:8px;font-size:12px;color:#fca5a5;">
            <strong>Truth Guardrail Check (Not Claimed):</strong> ${audit.map(escapeHtml).join(" · ")}
          </div>
        ` : ""}
      </div>
    `;
  }

  const tq = state.tailorQueue || {};
  const activeIds = tq.active_ids || (tq.current_job_id ? [tq.current_job_id] : []);
  const isTailoringNow = tq.is_running && activeIds.includes(job.id);
  const isQueued = tq.is_running && !isTailoringNow && (tq.queued_ids || []).includes(job.id);

  return `
    <div class="ai-panel">
      <div class="ai-panel-head">
        <h4>Step 2 · AI Fit Evaluation &amp; CV / Cover Letter Tailor</h4>
        <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;">
          <button class="btn btn-small btn-secondary" id="btn-ai-fit" onclick="runAIFit(${job.id})">AI Fit Check</button>
          <button class="btn btn-small btn-primary" id="btn-ai-tailor" onclick="runTailor(${job.id})" ${isTailoringNow ? "disabled" : ""}>
            ${isTailoringNow ? "Tailoring in Queue..." : "Generate Tailored CV + Cover Letter"}
          </button>
          <button class="btn btn-small btn-secondary" id="btn-ai-queue-one" onclick="enqueueSingleTailor(${job.id})" ${(isTailoringNow || isQueued || job.status === "blocked") ? "disabled" : ""}>
            ${isTailoringNow ? "Tailoring..." : (isQueued ? "Queued" : "+ Queue Tailor")}
          </button>
          ${(job.status === "tailored" || job.cv_pdf_path) ? `<button class="btn btn-small btn-red" onclick="resetTailored(${job.id})">Reset &amp; Delete PDFs</button>` : ""}
        </div>
      </div>
      <div class="ai-controls">
        <input id="tailor-angle-input" type="text" placeholder="Optional positioning hint (e.g., highlight Altium flyback PCB + thermal fault-finding)..." />
      </div>
      ${fitHtml}
      ${pkgHtml}
    </div>
  `;
}

window.resetTailored = async (jobId) => {
  if (!confirm("Move this job back to Shortlisted and delete generated CV & Cover Letter PDFs?")) return;
  const res = await api(`/api/jobs/${jobId}/reset-tailored`, { method: "POST" });
  renderJobDetail(res.job);
  await Promise.all([loadStats(), loadJobs()]);
};

function renderCopilotPanel(job) {
  const pkg = job.tailored_package || {};
  const isApplied = ["applied", "interview", "no_reply", "rejected"].includes(job.status);
  const appliedWhen = job.applied_at ? `Applied ${formatRelDate(job.applied_at)}` : "Logged";
  let appliedInfo = "";
  if (job.status === "no_reply") {
    appliedInfo = `<span class="tag no-reply">No Reply · ${escapeHtml(appliedWhen)}</span>`;
  } else if (job.status === "rejected") {
    appliedInfo = `<span class="tag rejected">Not Selected / Closed · ${escapeHtml(appliedWhen)}</span>`;
  } else if (job.status === "interview") {
    appliedInfo = `<span class="tag applied">Interview · ${escapeHtml(appliedWhen)} (${escapeHtml(job.apply_method || "manual")})</span>`;
  } else if (job.applied_at) {
    appliedInfo = `<span class="tag applied">Applied ${escapeHtml(formatRelDate(job.applied_at))} (${escapeHtml(job.apply_method || "manual")})</span>`;
  }

  const applyLogs = Array.isArray(job.apply_log) ? job.apply_log : [];
  const historyHtml = applyLogs.length
    ? `<div class="apply-history"><strong>Application Timeline:</strong><ul>${applyLogs
        .slice(-5)
        .reverse()
        .map(
          (e) =>
            `<li><code>${escapeHtml(e.status || "")}</code> · ${escapeHtml(formatRelDate(e.at))}${e.note ? ` — ${escapeHtml(e.note)}` : ""}</li>`
        )
        .join("")}</ul></div>`
    : "";

  const cvHint = job.cv_pdf_path ? job.cv_pdf_path : "Fallback: CV_Generic.pdf (generate tailored CV above first for best match)";
  const lastSess = state.copilotSession && state.copilotSession.job_id === job.id ? state.copilotSession : null;

  return `
    <div class="copilot-box">
      <div class="ai-panel-head">
        <h4>Step 3A · Assisted-Apply Browser Copilot &amp; Application Ledger</h4>
        <div>${appliedInfo}</div>
      </div>
      <div class="copilot-actions" style="margin-top:8px;">
        <button class="btn btn-small btn-primary" id="btn-prep-apply" onclick="prepareApply(${job.id}, false)" ${!job.url ? "disabled title='No URL saved for this pasted job'" : ""}>
          ${!job.url ? "No Posting URL Saved" : (job.is_link_out ? "Open External ATS + Pre-Fill" : "Prepare SEEK Quick Apply (Stops Before Submit)")}
        </button>
        <button class="btn btn-small btn-green" onclick="markJobApplied(${job.id}, 'applied')">
          ${isApplied ? "Log Applied Update" : "Mark as Applied"}
        </button>
        <button class="btn btn-small btn-secondary" onclick="markJobApplied(${job.id}, 'interview')">Mark Interview</button>
        <button class="btn btn-small btn-secondary" style="border-color:rgba(245,158,11,0.5);color:#fcd34d;" onclick="markJobApplied(${job.id}, 'no_reply')">Mark No Reply</button>
        <button class="btn btn-small btn-red" onclick="markJobApplied(${job.id}, 'rejected')">Not Selected / Rejected</button>
        ${pkg.cover_letter_body ? `<button class="btn btn-small btn-secondary" onclick="copyCoverLetter(${job.id})">Copy Cover Letter</button>` : ""}
      </div>
      <div style="font-size:12px;color:#94a3b8;margin-top:7px;">
        <strong>CV to attach:</strong> <code>${escapeHtml(cvHint)}</code>
      </div>
      ${historyHtml}
      ${lastSess ? renderCopilotSessionLog(lastSess) : ""}
    </div>
  `;
}

function renderCopilotSessionLog(sess) {
  const filled = (sess.filled_fields || []).map((f) => `<li>${escapeHtml(f)}</li>`).join("");
  const unmatched = (sess.unmatched_questions || [])
    .map(
      (q) => `
      <li style="margin-top:4px;">
        <span style="color:#fcd34d;">${escapeHtml(q)}</span>
        <button class="btn btn-small btn-secondary" style="margin-left:6px;padding:2px 7px;font-size:11px;"
          onclick="teachQA(${JSON.stringify(q).replaceAll('"', '&quot;')})">+ Save Answer to Q&amp;A Memory</button>
      </li>
    `
    )
    .join("");

  return `
    <div class="copilot-log">
      <strong>${escapeHtml(sess.stage?.toUpperCase() || "COPILOT")}:</strong> ${escapeHtml(sess.message || "")}
      ${filled ? `<ul>${filled}</ul>` : ""}
      ${unmatched ? `<div style="margin-top:8px;color:#fcd34d;"><strong>Unmatched Screening Questions (teach once for future runs):</strong><ul>${unmatched}</ul></div>` : ""}
    </div>
  `;
}

window.teachQA = async (questionText) => {
  const ans = prompt(`Enter your canonical answer for:\n\n"${questionText}"`);
  if (!ans || !ans.trim()) return;
  const key = questionText
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "_")
    .replace(/^_|_$/g, "")
    .slice(0, 36);
  await api("/api/qa-bank", {
    method: "POST",
    body: JSON.stringify({
      canonical_key: key || `custom_${Date.now()}`,
      question_pattern: questionText,
      answer: ans.trim(),
      category: "general",
    }),
  });
  alert("Saved to Q&A Memory Bank! Next run will match this automatically.");
};

window.copyCoverLetter = async (jobId) => {
  const job = await api(`/api/jobs/${jobId}`);
  const text = job.tailored_package?.cover_letter_body || "";
  if (!text) return;
  await navigator.clipboard.writeText(text);
  alert("Tailored cover letter copied to clipboard!");
};

window.prepareApply = async (jobId, ignoreCooldown) => {
  const btn = $("btn-prep-apply");
  if (btn) {
    btn.disabled = true;
    btn.textContent = "Launching browser copilot...";
  }
  try {
    const stCheck = await api("/api/copilot/status");
    if (stCheck.isolated_desktop) {
      alert(
        "Job Scout needs to run from your interactive Windows desktop to display browser windows.\n\n" +
        "Double-click  d:\\maru\\fight the HR\\job_agent\\start_dashboard.bat  in File Explorer and refresh this page."
      );
      return;
    }
    const res = await api(`/api/jobs/${jobId}/prepare-apply`, {
      method: "POST",
      body: JSON.stringify({ ignore_cooldown: !!ignoreCooldown }),
    });
    if (res.cooldown_warning) {
      if (confirm(res.message + "\n\nClick OK to proceed anyway.")) {
        return await window.prepareApply(jobId, true);
      }
      return;
    }
    state.copilotSession = res.session;
    const job = await api(`/api/jobs/${jobId}`);
    renderJobDetail(job);
  } catch (e) {
    alert("Browser copilot error: " + e.message);
  } finally {
    if ($("btn-prep-apply")) {
      $("btn-prep-apply").disabled = false;
    }
  }
};

window.markJobApplied = async (jobId, targetStatus) => {
  const job = await api(`/api/jobs/${jobId}`);
  const method = job.is_link_out ? "external_ats" : "seek_quick_apply";
  await api(`/api/jobs/${jobId}/mark-applied`, {
    method: "POST",
    body: JSON.stringify({
      apply_method: method,
      status: targetStatus,
      note: `Marked ${targetStatus} via dashboard`,
    }),
  });
  await Promise.all([loadStats(), loadJobs()]);
  const updated = await api(`/api/jobs/${jobId}`);
  renderJobDetail(updated);
};

async function refreshCopilotBadge() {
  try {
    const st = await api("/api/copilot/status");
    const loginBtn = $("btn-seek-login");
    const logoutBtn = $("btn-seek-logout");
    const signedIn = st.last_session?.signed_in === true;
    if (signedIn) {
      loginBtn.textContent = "SEEK: Signed In ✓";
      loginBtn.style.borderColor = "rgba(34, 197, 94, 0.55)";
      logoutBtn.classList.remove("hidden");
    } else if (st.profile_initialized) {
      loginBtn.textContent = "SEEK Browser Login";
      loginBtn.style.borderColor = "";
      logoutBtn.classList.remove("hidden");
    } else {
      loginBtn.textContent = "SEEK Browser Login";
      loginBtn.style.borderColor = "";
      logoutBtn.classList.add("hidden");
    }
  } catch (_) {}
}

async function openSeekLoginWindow() {
  const btn = $("btn-seek-login");
  btn.disabled = true;
  btn.textContent = "Checking SEEK...";
  try {
    const stCheck = await api("/api/copilot/status");
    if (stCheck.isolated_desktop) {
      btn.textContent = "SEEK Browser Login";
      alert(
        "Job Scout is currently running inside an isolated background session, so Windows hides browser popups launched from it.\n\n" +
        "Fix (takes 5 seconds):\n" +
        "1. Close/ignore this background instance.\n" +
        "2. Double-click  d:\\maru\\fight the HR\\job_agent\\start_dashboard.bat  from File Explorer."
      );
      return;
    }
    const sess = await api("/api/copilot/login", { method: "POST" });
    if (sess.signed_in) {
      btn.textContent = "SEEK: Signed In ✓";
      btn.style.borderColor = "rgba(34, 197, 94, 0.55)";
      $("btn-seek-logout").classList.remove("hidden");
      alert("Verified: Signed into SEEK in this browser!");
    } else {
      btn.textContent = "SEEK: Check Sign-In";
      $("btn-seek-logout").classList.remove("hidden");
      alert(
        sess.has_dashboard_tab
          ? "Opened a SEEK login tab right next to this tab!\n\nSign in on that tab, then switch back here and click 'SEEK: Check Sign-In' to confirm."
          : "Opened SEEK login in the Copilot Chromium window!\n\nTip: To keep Dashboard and SEEK in the SAME browser window, use the Job Scout tab opened by start_dashboard.bat and close this old Chrome tab."
      );
    }
  } catch (e) {
    btn.textContent = "SEEK Browser Login";
    alert("Could not open SEEK tab: " + e.message);
  } finally {
    btn.disabled = false;
  }
}

async function logoutSeek() {
  if (!confirm("Log out of SEEK and clear the saved copilot browser session?")) return;
  const btn = $("btn-seek-logout");
  btn.disabled = true;
  try {
    await api("/api/copilot/logout", { method: "POST" });
    $("btn-seek-login").textContent = "SEEK Browser Login";
    $("btn-seek-login").style.borderColor = "";
    btn.classList.add("hidden");
    alert("Logged out of SEEK and cleared copilot cookies.");
  } catch (e) {
    alert("Log out error: " + e.message);
  } finally {
    btn.disabled = false;
  }
}

window.runAIFit = async (jobId) => {
  const btn = $("btn-ai-fit");
  btn.disabled = true;
  btn.textContent = "Analyzing with AI...";
  try {
    const res = await api(`/api/jobs/${jobId}/analyze-fit`, { method: "POST" });
    renderJobDetail(res.job);
    await loadJobs();
  } catch (e) {
    alert(e.message);
    if (e.message.includes("API key")) openAISettingsModal();
  } finally {
    if ($("btn-ai-fit")) {
      $("btn-ai-fit").disabled = false;
      $("btn-ai-fit").textContent = "AI Fit Check";
    }
  }
};

window.runTailor = async (jobId) => {
  const btn = $("btn-ai-tailor");
  const angle = ($("tailor-angle-input")?.value || "").trim();
  btn.disabled = true;
  btn.textContent = "Drafting CV & PDF...";
  try {
    const res = await api(`/api/jobs/${jobId}/tailor`, {
      method: "POST",
      body: JSON.stringify({ extra_angle: angle }),
    });
    renderJobDetail(res.job);
    await Promise.all([loadStats(), loadJobs()]);
  } catch (e) {
    alert(e.message);
    if (e.message.includes("API key")) openAISettingsModal();
  } finally {
    if ($("btn-ai-tailor")) {
      $("btn-ai-tailor").disabled = false;
      $("btn-ai-tailor").textContent = "Generate Tailored CV + Cover Letter";
    }
  }
};

/* Batch Tailoring Queue & Completion Notification */
const BASE_DOC_TITLE = document.title;

window.addEventListener("focus", () => {
  if (document.title !== BASE_DOC_TITLE) {
    document.title = BASE_DOC_TITLE;
  }
});

function playCompletionChime() {
  try {
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtx) return;
    const ctx = new AudioCtx();
    const now = ctx.currentTime;
    [523.25, 659.25, 783.99].forEach((freq, i) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = "sine";
      osc.frequency.setValueAtTime(freq, now + i * 0.11);
      gain.gain.setValueAtTime(0.001, now + i * 0.11);
      gain.gain.exponentialRampToValueAtTime(0.08, now + i * 0.11 + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.001, now + i * 0.11 + 0.24);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(now + i * 0.11);
      osc.stop(now + i * 0.11 + 0.25);
    });
  } catch (_) {}
}

function notifyTailorComplete(q) {
  const okCount = (q.completed_ids || []).length;
  const failCount = (q.failed || []).length;
  if (okCount === 0 && failCount === 0) return;

  playCompletionChime();

  const title = failCount > 0
    ? `Job Scout — Tailoring Finished (${okCount} OK, ${failCount} failed)`
    : `Job Scout — All ${okCount} CV & Cover Letters Ready!`;
  const body = failCount > 0
    ? `${okCount} package(s) generated. Failed: ${(q.failed || []).map((f) => `${f.title}: ${f.error}`).slice(0, 2).join("; ")}`
    : `Generated ${okCount} tailored CV & Cover Letter package(s) in the background.`;

  if (document.hidden) {
    document.title = `✓ (${okCount}) Done — ${BASE_DOC_TITLE}`;
  }

  try {
    if ("Notification" in window && Notification.permission === "granted") {
      const n = new Notification(title, { body });
      n.onclick = () => {
        window.focus();
        n.close();
      };
    }
  } catch (_) {}
}

function renderTailorQueueBanner(q) {
  const banner = $("tailor-queue-banner");
  const spinner = $("tq-spinner");
  const stage = $("tq-stage");
  const msg = $("tq-message");
  const counters = $("tq-counters");
  const cancelBtn = $("btn-tq-cancel");
  const dismissBtn = $("btn-tq-dismiss");
  if (!banner) return;

  const hasResult = (q.completed_ids || []).length + (q.failed || []).length > 0;
  if (!q.is_running && (!hasResult || state._dismissedTailorSeq === q.batch_seq)) {
    banner.classList.add("hidden");
    banner.classList.remove("banner-done", "banner-warn");
    return;
  }

  banner.classList.remove("hidden");
  if (q.is_running) {
    banner.classList.remove("banner-done", "banner-warn");
    spinner.classList.remove("hidden");
    cancelBtn.classList.remove("hidden");
    cancelBtn.disabled = !!q.cancel_requested;
    cancelBtn.textContent = q.cancel_requested ? "Stopping..." : "Stop Queue";
    dismissBtn.classList.add("hidden");
    stage.textContent = `Tailoring Batch (${q.done_count}/${q.total})`;
    msg.textContent = q.message || "Working...";
    const activeN = (q.active_ids || []).length;
    const waiting = (q.queued_ids || []).length;
    counters.textContent = `${activeN} running · ${(q.completed_ids || []).length} ready${waiting ? ` · ${waiting} queued` : ""}${(q.failed || []).length ? ` · ${q.failed.length} failed` : ""}`;
  } else {
    spinner.classList.add("hidden");
    cancelBtn.classList.add("hidden");
    dismissBtn.classList.remove("hidden");
    const failCount = (q.failed || []).length;
    const okCount = (q.completed_ids || []).length;
    banner.classList.toggle("banner-warn", failCount > 0);
    banner.classList.toggle("banner-done", failCount === 0);
    stage.textContent = failCount > 0 ? "Tailoring Batch Finished with Warnings" : "Tailoring Batch Complete";
    const errPreview = failCount > 0 ? ` (${q.failed[0].title}: ${q.failed[0].error})` : "";
    msg.textContent = `${q.message || "Done."}${errPreview}`;
    counters.textContent = `${okCount}/${q.total} completed${failCount ? ` · ${failCount} failed` : ""}`;
  }
}

async function refreshSelectedDetailIfNeeded() {
  if (!state.selectedId) return;
  try {
    const job = await api(`/api/jobs/${state.selectedId}`);
    renderJobDetail(job);
  } catch (_) {}
}

async function pollTailorQueueOnce() {
  const q = await api("/api/tailor-queue/status");
  const prevDone = state._lastTailorDoneCount;
  const wasRunning = state.tailorQueue?.is_running;
  state.tailorQueue = q;
  renderTailorQueueBanner(q);

  if (prevDone !== q.done_count || wasRunning !== q.is_running) {
    state._lastTailorDoneCount = q.done_count;
    renderJobList();
    if (q.done_count > 0 || !q.is_running) {
      await Promise.all([loadStats(), loadJobs(), refreshSelectedDetailIfNeeded()]);
    }
  } else {
    renderJobList();
  }

  if (!q.is_running) {
    if (state.tailorPollTimer) {
      clearInterval(state.tailorPollTimer);
      state.tailorPollTimer = null;
    }
    if (q.batch_seq > state._lastNotifiedBatchSeq && ((q.completed_ids || []).length + (q.failed || []).length > 0)) {
      state._lastNotifiedBatchSeq = q.batch_seq;
      notifyTailorComplete(q);
    }
  }
}

function startTailorQueuePolling() {
  if (state.tailorPollTimer) return;
  state.tailorPollTimer = setInterval(async () => {
    try {
      await pollTailorQueueOnce();
    } catch (_) {}
  }, 1800);
}

async function enqueueTailorJobs(jobIds, extraAngle = "") {
  if (!jobIds || !jobIds.length) return;
  try {
    if ("Notification" in window && Notification.permission === "default") {
      Notification.requestPermission().catch(() => {});
    }
  } catch (_) {}

  try {
    const res = await api("/api/tailor-queue/enqueue", {
      method: "POST",
      body: JSON.stringify({ job_ids: jobIds, extra_angle: extraAngle }),
    });
    if (res.added === 0) {
      alert("Selected job(s) are already queued, tailoring, or blocked.");
      return;
    }
    for (const id of jobIds) state.selectedBatchIds.delete(id);
    state.tailorQueue = res;
    state._lastTailorDoneCount = res.done_count;
    state._dismissedTailorSeq = 0;
    renderTailorQueueBanner(res);
    renderJobList();
    if (state.selectedId && jobIds.includes(state.selectedId)) {
      await refreshSelectedDetailIfNeeded();
    }
    startTailorQueuePolling();
  } catch (e) {
    alert(e.message);
    if (e.message.includes("API key")) openAISettingsModal();
  }
}

window.enqueueSingleTailor = async (jobId) => {
  const angle = ($("tailor-angle-input")?.value || "").trim();
  await enqueueTailorJobs([jobId], angle);
};

async function cancelTailorQueue() {
  const res = await api("/api/tailor-queue/cancel", { method: "POST" });
  state.tailorQueue = res;
  renderTailorQueueBanner(res);
}


/* AI Provider Settings Modal */
let _aiCustomPresets = [];
let _aiPendingNewKeys = {};

function renderCustomPresetsList() {
  const box = $("ai-custom-presets-list");
  if (!box) return;
  if (!_aiCustomPresets.length) {
    box.innerHTML = '<div style="font-size:12px;color:#93a0b8;">No custom presets yet.</div>';
    return;
  }
  box.innerHTML = _aiCustomPresets
    .map(
      (p, idx) => `
      <div class="qa-item" style="margin-bottom:6px;padding:8px;">
        <div class="qa-item-top">
          <div><strong>${escapeHtml(p.label)}</strong> <span class="tag">${escapeHtml(p.model)}</span></div>
          <button class="btn btn-small btn-red" type="button" onclick="removeCustomPreset(${idx})">Remove</button>
        </div>
        <div style="font-size:11.5px;color:#93a0b8;">${escapeHtml(p.base_url)} · Key: ${escapeHtml(p.api_key_env)}</div>
      </div>`
    )
    .join("");
}

window.removeCustomPreset = (idx) => {
  _aiCustomPresets.splice(idx, 1);
  renderCustomPresetsList();
};

async function openAISettingsModal() {
  const data = await api("/api/llm/settings");
  _aiCustomPresets = structuredClone(data.llm.custom_presets || []);
  _aiPendingNewKeys = {};

  const select = $("ai-provider-select");
  select.innerHTML = "";
  for (const p of data.providers) {
    const opt = document.createElement("option");
    opt.value = p.id;
    opt.textContent = p.name;
    select.appendChild(opt);
  }
  select.value = data.llm.active_provider || "gemini";
  $("ai-temperature").value = data.llm.temperature ?? 0.25;
  $("ai-gemini-model").value = data.llm.gemini_model || "gemini-2.5-flash";
  if ($("ai-gemini-thinking")) {
    $("ai-gemini-thinking").value = data.llm.gemini_thinking_level || "default";
  }
  $("ai-deepseek-model").value = data.llm.deepseek_model || "deepseek-chat";
  $("ai-openai-url").value = data.llm.openai_base_url || "http://127.0.0.1:11434/v1";
  $("ai-openai-model").value = data.llm.openai_model || "qwen2.5:14b";
  $("ai-anthropic-model").value = data.llm.anthropic_model || "claude-sonnet-4-5";
  $("ai-gemini-key").value = "";
  $("ai-deepseek-key").value = "";
  $("ai-openai-key").value = "";
  $("ai-anthropic-key").value = "";

  const ks = data.key_status || {};
  const setBadge = (elId, envName) => {
    const info = ks[envName] || {};
    const el = $(elId);
    el.textContent = info.configured ? `Configured (${info.source} ${info.masked})` : "Not set";
    el.style.color = info.configured ? "#86efac" : "#fcd34d";
  };
  setBadge("badge-gemini-key", data.llm.gemini_api_key_env);
  setBadge("badge-deepseek-key", data.llm.deepseek_api_key_env);
  setBadge("badge-openai-key", data.llm.openai_api_key_env);
  setBadge("badge-anthropic-key", data.llm.anthropic_api_key_env);
  renderCustomPresetsList();

  $("ai-modal").classList.remove("hidden");
}

async function saveAISettings() {
  const cur = await api("/api/llm/settings");
  const llm = {
    ...cur.llm,
    active_provider: $("ai-provider-select").value,
    temperature: Number($("ai-temperature").value || 0.25),
    gemini_model: $("ai-gemini-model").value.trim() || "gemini-2.5-flash",
    gemini_thinking_level: $("ai-gemini-thinking") ? $("ai-gemini-thinking").value : "default",
    deepseek_model: $("ai-deepseek-model").value.trim() || "deepseek-chat",
    openai_base_url: $("ai-openai-url").value.trim() || "http://127.0.0.1:11434/v1",
    openai_model: $("ai-openai-model").value.trim() || "qwen2.5:14b",
    anthropic_model: $("ai-anthropic-model").value.trim() || "claude-sonnet-4-5",
    custom_presets: _aiCustomPresets,
  };
  const api_keys = { ..._aiPendingNewKeys };
  if ($("ai-gemini-key").value.trim()) api_keys[llm.gemini_api_key_env] = $("ai-gemini-key").value.trim();
  if ($("ai-deepseek-key").value.trim()) api_keys[llm.deepseek_api_key_env] = $("ai-deepseek-key").value.trim();
  if ($("ai-openai-key").value.trim()) api_keys[llm.openai_api_key_env] = $("ai-openai-key").value.trim();
  if ($("ai-anthropic-key").value.trim()) api_keys[llm.anthropic_api_key_env] = $("ai-anthropic-key").value.trim();

  await api("/api/llm/settings", {
    method: "PUT",
    body: JSON.stringify({ llm, api_keys }),
  });
  closeModal("ai-modal");
}

/* Q&A Memory Bank Modal */
async function openQAModal() {
  await loadQAList();
  $("qa-modal").classList.remove("hidden");
}

async function loadQAList() {
  const data = await api("/api/qa-bank");
  const list = $("qa-list");
  list.innerHTML = (data.entries || [])
    .map(
      (e) => `
      <div class="qa-item">
        <div class="qa-item-top">
          <div>
            <strong>${escapeHtml(e.canonical_key)}</strong>
            <span class="tag">${escapeHtml(e.category)}</span>
          </div>
          <button class="btn btn-small btn-red" onclick="deleteQA(${e.id})">Delete</button>
        </div>
        <div style="font-size:12px;color:#93a0b8;margin-bottom:4px;">Pattern: ${escapeHtml(e.question_pattern)}</div>
        <div style="font-size:13px;">${escapeHtml(e.answer)}</div>
      </div>
    `
    )
    .join("");
}

window.deleteQA = async (id) => {
  await api(`/api/qa-bank/${id}`, { method: "DELETE" });
  await loadQAList();
};

async function saveNewQA() {
  const canonical_key = $("qa-new-key").value.trim();
  const question_pattern = $("qa-new-pattern").value.trim();
  const answer = $("qa-new-answer").value.trim();
  const category = $("qa-new-cat").value;
  if (!canonical_key || !question_pattern || !answer) {
    alert("Key, pattern, and answer are required.");
    return;
  }
  await api("/api/qa-bank", {
    method: "POST",
    body: JSON.stringify({ canonical_key, question_pattern, answer, category }),
  });
  $("qa-new-key").value = "";
  $("qa-new-pattern").value = "";
  $("qa-new-answer").value = "";
  await loadQAList();
}

/* Master Profile Modal (Structured Editor + AI Check) */
let _profileDraft = null;
let _profileActiveTab = "bio";

function syncProfileDraftFromDom() {
  if (!_profileDraft) return;
  if (_profileActiveTab === "raw") {
    try {
      _profileDraft = JSON.parse($("profile-json-editor").value);
    } catch (_) {}
    return;
  }
  _profileDraft.full_name = $("prof-full-name").value.trim();
  _profileDraft.headline = $("prof-headline").value.trim();
  _profileDraft.phone = $("prof-phone").value.trim();
  _profileDraft.email = $("prof-email").value.trim();
  _profileDraft.location = $("prof-location").value.trim();
  _profileDraft.linkedin = $("prof-linkedin").value.trim();
  _profileDraft.work_rights = $("prof-work-rights").value.trim();
  _profileDraft.languages = $("prof-languages").value.trim();
  _profileDraft.base_summary = $("prof-summary").value.trim();
}

function renderProfileBioFields() {
  const p = _profileDraft;
  $("prof-full-name").value = p.full_name || "";
  $("prof-headline").value = p.headline || "";
  $("prof-phone").value = p.phone || "";
  $("prof-email").value = p.email || "";
  $("prof-location").value = p.location || "";
  $("prof-linkedin").value = p.linkedin || "";
  $("prof-work-rights").value = p.work_rights || "";
  $("prof-languages").value = p.languages || "";
  $("prof-summary").value = p.base_summary || "";
}

function renderProfileProjects() {
  const box = $("prof-projects-list");
  const projects = _profileDraft.projects || [];
  box.innerHTML = projects
    .map(
      (proj, idx) => `
      <div class="ai-provider-card" style="padding:10px;">
        <div class="section-row" style="margin-bottom:6px;">
          <input type="text" value="${escapeHtml(proj.name || "")}" placeholder="Project Name" style="flex:1;margin-right:8px;"
            onchange="_profileDraft.projects[${idx}].name = this.value" />
          <button class="btn btn-small btn-red" type="button" onclick="_profileDraft.projects.splice(${idx},1);renderProfileProjects();">Remove</button>
        </div>
        <label class="field-label">Tools / Skills Tags (comma-separated)</label>
        <input type="text" value="${escapeHtml((proj.tags || []).join(", "))}" placeholder="STM32, Altium, Python, C++" style="width:100%;margin-bottom:6px;"
          onchange="_profileDraft.projects[${idx}].tags = this.value.split(',').map(s=>s.trim()).filter(Boolean)" />
        <label class="field-label">Achievement Bullets (one per line)</label>
        <textarea rows="3" style="width:100%;" onchange="_profileDraft.projects[${idx}].bullets = this.value.split('\\n').map(s=>s.trim()).filter(Boolean)">${escapeHtml((proj.bullets || []).join("\n"))}</textarea>
      </div>`
    )
    .join("");
}

function renderProfileSkills() {
  const box = $("prof-skills-list");
  const skills = _profileDraft.skills || {};
  box.innerHTML = Object.entries(skills)
    .map(
      ([cat, items]) => `
      <div class="ai-provider-card" style="padding:10px;">
        <div class="section-row" style="margin-bottom:6px;">
          <strong>${escapeHtml(cat)}</strong>
          <button class="btn btn-small btn-red" type="button" onclick="delete _profileDraft.skills[${JSON.stringify(cat).replaceAll('"', '&quot;')}];renderProfileSkills();">Delete</button>
        </div>
        <textarea rows="2" style="width:100%;" onchange="_profileDraft.skills[${JSON.stringify(cat).replaceAll('"', '&quot;')}] = this.value.split(',').map(s=>s.trim()).filter(Boolean)">${escapeHtml((items || []).join(", "))}</textarea>
      </div>`
    )
    .join("");
}

function hasSkillAnywhere(skill) {
  const target = skill.trim().toLowerCase();
  return Object.values(_profileDraft?.skills || {}).some((arr) =>
    (arr || []).some((s) => s.trim().toLowerCase() === target)
  );
}

function renderProfileAudit(audit) {
  const box = $("prof-audit-results");
  if (!audit) {
    box.innerHTML = "";
    return;
  }
  const missing = audit.missing_skills || [];
  const bullets = audit.project_bullet_ideas || [];
  const bios = audit.bio_suggestions || [];
  const kws = audit.keywords_to_add || [];
  box.innerHTML = `
    <div class="ai-provider-card">
      <div style="font-size:13px;margin-bottom:8px;">${escapeHtml(audit.summary || "")}</div>
      ${missing.length ? `
        <strong style="font-size:12px;">Missing / Unlisted Skills (click + Add):</strong>
        <div style="display:flex;flex-wrap:wrap;gap:6px;margin:6px 0 10px;">
          ${missing.map((s, i) => {
            const added = hasSkillAnywhere(s);
            return `<button class="btn btn-small ${added ? "btn-green" : "btn-secondary"}" ${added ? "disabled" : ""} onclick="addAuditSkill(${i}, ${JSON.stringify(s).replaceAll('"', '&quot;')})">${added ? `${escapeHtml(s)} ✓` : `+ ${escapeHtml(s)}`}</button>`;
          }).join("")}
        </div>` : ""}
      ${bullets.length ? `
        <strong style="font-size:12px;">Project Bullet Ideas:</strong>
        <ul style="font-size:12.5px;margin:6px 0 10px;padding-left:18px;">
          ${bullets.map((b) => `<li style="margin-bottom:4px;">${escapeHtml(b)} <button class="btn btn-small btn-secondary" style="padding:1px 6px;font-size:11px;" onclick="addAuditBullet(${JSON.stringify(b).replaceAll('"', '&quot;')})">+ Add to 1st Project</button></li>`).join("")}
        </ul>` : ""}
      ${bios.length ? `<strong style="font-size:12px;">Bio Tips:</strong><ul style="font-size:12.5px;margin:6px 0 10px;padding-left:18px;">${bios.map((x) => `<li>${escapeHtml(x)}</li>`).join("")}</ul>` : ""}
      ${kws.length ? `<div style="font-size:12px;color:#93c5fd;"><strong>Keywords:</strong> ${escapeHtml(kws.join(", "))}</div>` : ""}
    </div>`;
}

let _lastProfileAudit = null;
window.addAuditSkill = (idx, skill) => {
  if (!_profileDraft.skills) _profileDraft.skills = {};
  const cats = Object.keys(_profileDraft.skills);
  const firstCat = cats[0] || "Additional";
  if (!_profileDraft.skills[firstCat]) _profileDraft.skills[firstCat] = [];
  if (!hasSkillAnywhere(skill)) _profileDraft.skills[firstCat].push(skill);
  renderProfileSkills();
  renderProfileAudit(_lastProfileAudit);
  $("prof-save-status").textContent = `Added "${skill}" (click Save Master Profile)`;
};

window.addAuditBullet = (bullet) => {
  if (!_profileDraft.projects || !_profileDraft.projects.length) return;
  if (!_profileDraft.projects[0].bullets) _profileDraft.projects[0].bullets = [];
  if (!_profileDraft.projects[0].bullets.includes(bullet)) {
    _profileDraft.projects[0].bullets.push(bullet);
  }
  renderProfileProjects();
  $("prof-save-status").textContent = "Added bullet to 1st project (click Save Master Profile)";
};

function switchProfileSubtab(tab) {
  syncProfileDraftFromDom();
  _profileActiveTab = tab;
  document.querySelectorAll("#profile-subtabs .tab").forEach((t) => {
    t.classList.toggle("active", t.dataset.ptab === tab);
  });
  document.querySelectorAll(".ptab-panel").forEach((p) => p.classList.add("hidden"));
  $(`ptab-${tab}`).classList.remove("hidden");
  if (tab === "raw") {
    $("profile-json-editor").value = JSON.stringify(_profileDraft, null, 2);
  } else if (tab === "bio") {
    renderProfileBioFields();
  } else if (tab === "projects") {
    renderProfileProjects();
  } else if (tab === "skills") {
    renderProfileSkills();
  }
}

async function openProfileModal() {
  _profileDraft = await api("/api/profile");
  _profileActiveTab = "bio";
  $("prof-save-status").textContent = "";
  renderProfileBioFields();
  renderProfileProjects();
  renderProfileSkills();
  $("profile-json-editor").value = JSON.stringify(_profileDraft, null, 2);
  switchProfileSubtab("bio");
  $("profile-modal").classList.remove("hidden");
}

async function saveProfileModal() {
  try {
    syncProfileDraftFromDom();
    const saved = await api("/api/profile", {
      method: "PUT",
      body: JSON.stringify(_profileDraft),
    });
    _profileDraft = saved;
    $("profile-json-editor").value = JSON.stringify(saved, null, 2);
    $("prof-save-status").textContent = "Profile Saved ✓";
  } catch (e) {
    alert("Invalid JSON or schema error: " + e.message);
  }
}

/* Phone Wi-Fi Sync Modal */
async function openPhoneSyncModal() {
  $("sync-modal").classList.remove("hidden");
  $("sync-info-body").innerHTML = "Detecting local Wi-Fi IP...";
  try {
    const info = await api("/api/sync/info");
    const urls = info.sync_urls || [];
    $("sync-info-body").innerHTML = `
      <div style="margin-bottom:8px;"><strong>Desktop Sync URL(s):</strong></div>
      ${urls.length ? urls.map((u) => `
        <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px;">
          <code style="flex:1;padding:6px 8px;background:#0f172a;border-radius:6px;">${escapeHtml(u)}</code>
          <button class="btn btn-small btn-secondary" onclick="navigator.clipboard.writeText(${JSON.stringify(u).replaceAll('"', '&quot;')})">Copy</button>
        </div>`).join("") : '<div style="color:#fcd34d;">Could not detect LAN IP — check ipconfig / Wi-Fi connection.</div>'}
      <div style="font-size:12px;color:#93a0b8;margin-top:10px;">
        Active from phone: <strong>${info.synced_from_phone}</strong> · Total pipeline (Shortlisted → Closed): <strong>${info.pipeline_count ?? info.applied_count}</strong> · Applied+: <strong>${info.applied_count}</strong>
      </div>`;
  } catch (e) {
    $("sync-info-body").textContent = "Error: " + e.message;
  }
}

/* Add Job Modal (SEEK Link or Paste JD) */
let _importMode = "seek";

function switchImportMode(mode) {
  _importMode = mode === "paste" ? "paste" : "seek";
  $("import-tab-seek")?.classList.toggle("active", _importMode === "seek");
  $("import-tab-paste")?.classList.toggle("active", _importMode === "paste");
  $("import-panel-seek")?.classList.toggle("hidden", _importMode !== "seek");
  $("import-panel-paste")?.classList.toggle("hidden", _importMode !== "paste");
  if ($("add-job-modal-title")) {
    $("add-job-modal-title").textContent =
      _importMode === "paste" ? "Paste Non-SEEK Job Description" : "Add SEEK Job by Link or ID";
  }
  setTimeout(() => {
    (_importMode === "paste" ? $("paste-jd-text") : $("add-job-url"))?.focus();
  }, 40);
}
window.switchImportMode = switchImportMode;

function openAddJobModal(mode = "seek") {
  $("add-job-url").value = "";
  $("add-job-angle").value = "";
  if ($("paste-jd-text")) $("paste-jd-text").value = "";
  if ($("paste-jd-title")) $("paste-jd-title").value = "";
  if ($("paste-jd-company")) $("paste-jd-company").value = "";
  if ($("paste-jd-location")) $("paste-jd-location").value = "";
  if ($("paste-jd-salary")) $("paste-jd-salary").value = "";
  if ($("paste-jd-worktype")) $("paste-jd-worktype").value = "";
  if ($("paste-jd-url")) $("paste-jd-url").value = "";
  if ($("paste-jd-questions")) $("paste-jd-questions").value = "";
  $("add-job-status").classList.add("hidden");
  $("add-job-status").textContent = "";
  $("add-job-modal").classList.remove("hidden");
  switchImportMode(mode);
}
window.openAddJobModal = openAddJobModal;

async function submitImportJob(autoTailor) {
  const extra_angle = $("add-job-angle").value.trim();
  const btnOnly = $("btn-submit-import-only");
  const btnTailor = $("btn-submit-import-tailor");
  const statusEl = $("add-job-status");

  let endpoint = "/api/jobs/import-url";
  let payload;

  if (_importMode === "paste") {
    const description_text = $("paste-jd-text").value.trim();
    if (description_text.length < 20) {
      alert("Please paste the job description text first.");
      return;
    }
    const workRaw = ($("paste-jd-worktype")?.value || "").trim();
    const questionsRaw = ($("paste-jd-questions")?.value || "")
      .split(/\n+/)
      .map((s) => s.trim())
      .filter(Boolean);
    endpoint = "/api/jobs/import-paste";
    payload = {
      description_text,
      title: ($("paste-jd-title")?.value || "").trim(),
      company: ($("paste-jd-company")?.value || "").trim(),
      location: ($("paste-jd-location")?.value || "").trim(),
      salary_label: ($("paste-jd-salary")?.value || "").trim(),
      work_type: workRaw,
      url: ($("paste-jd-url")?.value || "").trim(),
      screening_questions: questionsRaw,
      use_ai_extract: $("paste-jd-ai-extract") ? $("paste-jd-ai-extract").checked : true,
      auto_tailor: !!autoTailor,
      extra_angle,
    };
    statusEl.textContent = autoTailor
      ? "Parsing JD, scoring fit, and generating tailored CV + Cover Letter PDFs..."
      : "Parsing JD details and scoring against your profile...";
  } else {
    const url = $("add-job-url").value.trim();
    const probe_salary = $("add-job-probe").checked;
    if (!url) {
      alert("Please paste a SEEK job URL or numeric job ID.");
      return;
    }
    payload = {
      url,
      probe_salary,
      auto_tailor: !!autoTailor,
      extra_angle,
    };
    statusEl.textContent = autoTailor
      ? "Fetching SEEK job details, probing salary, and generating tailored CV + Cover Letter PDFs..."
      : "Fetching SEEK job details and estimating hidden salary...";
  }

  btnOnly.disabled = true;
  btnTailor.disabled = true;
  statusEl.classList.remove("hidden");

  try {
    const res = await api(endpoint, {
      method: "POST",
      body: JSON.stringify(payload),
    });
    const job = res.job;
    closeModal("add-job-modal");

    // Ensure current filters do not hide the newly imported job
    const minScore = parseInt($("minscore-filter").value, 10);
    if (job.score < minScore) {
      $("minscore-filter").value = "0";
    }
    if ($("search-input").value.trim()) {
      $("search-input").value = "";
    }
    if ($("query-filter").value) {
      $("query-filter").value = "";
    }
    if (state.statusTab !== "all" && state.statusTab !== job.status) {
      document.querySelectorAll("#status-tabs .tab").forEach((t) => {
        t.classList.toggle("active", t.dataset.status === "all");
      });
      state.statusTab = "all";
    }

    state.selectedId = job.id;
    await Promise.all([loadStats(), loadJobs()]);
    await selectJob(job.id);

    if (res.tailor_error) {
      alert(`Job imported ("${job.title}"), but AI tailoring reported:\n\n${res.tailor_error}`);
      if (res.tailor_error.includes("API key")) openAISettingsModal();
    }
  } catch (e) {
    alert("Could not import job: " + e.message);
  } finally {
    btnOnly.disabled = false;
    btnTailor.disabled = false;
    statusEl.classList.add("hidden");
  }
}
window.submitImportJob = submitImportJob;

/* Events */
document.querySelectorAll("#status-tabs .tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll("#status-tabs .tab").forEach((t) => t.classList.remove("active"));
    tab.classList.add("active");
    state.statusTab = tab.dataset.status;
    loadJobs();
  });
});

$("btn-scan").addEventListener("click", startScan);
$("btn-seek-login").addEventListener("click", openSeekLoginWindow);
$("btn-seek-logout").addEventListener("click", logoutSeek);
$("btn-phone-sync").addEventListener("click", openPhoneSyncModal);
$("btn-config").addEventListener("click", openConfigModal);
$("btn-ai-settings").addEventListener("click", openAISettingsModal);
$("btn-save-ai").addEventListener("click", saveAISettings);
$("btn-add-custom-preset").addEventListener("click", () => {
  const label = $("ai-new-preset-label").value.trim();
  const base_url = $("ai-new-preset-url").value.trim();
  const model = $("ai-new-preset-model").value.trim();
  const keyVal = $("ai-new-preset-key").value.trim();
  if (!label || !base_url || !model) {
    alert("Label, Base URL, and Model are required.");
    return;
  }
  const cleanId = "custom_" + label.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "");
  const api_key_env = cleanId.toUpperCase() + "_API_KEY";
  _aiCustomPresets = _aiCustomPresets.filter((p) => p.id !== cleanId);
  _aiCustomPresets.push({ id: cleanId, label, base_url, model, api_key_env });
  if (keyVal) _aiPendingNewKeys[api_key_env] = keyVal;

  const select = $("ai-provider-select");
  if (![...select.options].some((o) => o.value === cleanId)) {
    const opt = document.createElement("option");
    opt.value = cleanId;
    opt.textContent = `${label} (${model})`;
    select.appendChild(opt);
  }
  select.value = cleanId;
  $("ai-new-preset-label").value = "";
  $("ai-new-preset-url").value = "";
  $("ai-new-preset-model").value = "";
  $("ai-new-preset-key").value = "";
  renderCustomPresetsList();
});
$("btn-qa").addEventListener("click", openQAModal);
$("btn-add-qa").addEventListener("click", saveNewQA);
$("btn-profile").addEventListener("click", openProfileModal);
$("btn-save-profile").addEventListener("click", saveProfileModal);
document.querySelectorAll("#profile-subtabs .tab").forEach((t) => {
  t.addEventListener("click", () => switchProfileSubtab(t.dataset.ptab));
});
$("btn-add-project").addEventListener("click", () => {
  if (!_profileDraft.projects) _profileDraft.projects = [];
  _profileDraft.projects.push({ id: `proj_${Date.now()}`, name: "New Project", tags: [], bullets: [] });
  renderProfileProjects();
});
$("btn-add-skill-cat").addEventListener("click", () => {
  const cat = prompt("New skill category name (e.g. Embedded / Protocols):");
  if (!cat || !cat.trim()) return;
  if (!_profileDraft.skills) _profileDraft.skills = {};
  if (!_profileDraft.skills[cat.trim()]) _profileDraft.skills[cat.trim()] = [];
  renderProfileSkills();
});
$("btn-run-profile-audit").addEventListener("click", async () => {
  const btn = $("btn-run-profile-audit");
  btn.disabled = true;
  btn.textContent = "Checking Profile with AI...";
  try {
    syncProfileDraftFromDom();
    await api("/api/profile", { method: "PUT", body: JSON.stringify(_profileDraft) });
    _lastProfileAudit = await api("/api/profile/audit", { method: "POST" });
    renderProfileAudit(_lastProfileAudit);
  } catch (e) {
    alert("AI Profile Check failed: " + e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = "Check Profile with AI";
  }
});
$("btn-close-config").addEventListener("click", () => $("config-modal").classList.add("hidden"));
$("btn-cancel-config").addEventListener("click", () => $("config-modal").classList.add("hidden"));
$("btn-save-config").addEventListener("click", saveConfig);
$("btn-add-query").addEventListener("click", () => {
  const container = $("cfg-queries-list");
  const row = document.createElement("div");
  row.className = "query-row";
  row.innerHTML = `
    <input type="text" class="q-name" value="New Search" placeholder="Profile name" />
    <input type="text" class="q-keywords" value="" placeholder="Keywords" />
    <input type="text" class="q-where" value="All Auckland" placeholder="Where" />
    <input type="number" class="q-days" value="14" min="1" max="31" />
    <label style="display:flex;align-items:center;gap:4px;font-size:12px;"><input type="checkbox" class="q-enabled" checked /> On</label>
    <button class="btn-icon" title="Remove" onclick="this.parentElement.remove()">&times;</button>
  `;
  container.appendChild(row);
});

$("search-input").addEventListener("input", () => loadJobs());
$("query-filter").addEventListener("change", () => loadJobs());
$("minscore-filter").addEventListener("change", () => loadJobs());
$("sort-select").addEventListener("change", () => loadJobs());
$("board-select").addEventListener("change", () => {
  const srcObj = state.sources.find((s) => s.source_id === $("board-select").value);
  $("active-board-label").textContent = srcObj ? srcObj.display_name : "SEEK";
});

$("batch-select-visible").addEventListener("change", (e) => {
  const checked = e.target.checked;
  for (const job of state.jobs) {
    if (job.status === "blocked") continue;
    if (checked) state.selectedBatchIds.add(job.id);
    else state.selectedBatchIds.delete(job.id);
  }
  renderJobList();
});

$("btn-select-untailored").addEventListener("click", () => {
  const skipStatuses = new Set(["tailored", "applied", "interview", "no_reply", "rejected", "skipped"]);
  for (const job of state.jobs) {
    if (job.status === "blocked") continue;
    if (!job.has_tailored_cv && !skipStatuses.has(job.status)) {
      state.selectedBatchIds.add(job.id);
    }
  }
  renderJobList();
});

$("btn-clear-selection").addEventListener("click", () => {
  state.selectedBatchIds.clear();
  renderJobList();
});

$("btn-queue-selected").addEventListener("click", async () => {
  const ids = Array.from(state.selectedBatchIds);
  if (!ids.length) return;
  await enqueueTailorJobs(ids);
});

$("btn-tq-cancel").addEventListener("click", cancelTailorQueue);
$("btn-tq-dismiss").addEventListener("click", () => {
  state._dismissedTailorSeq = state.tailorQueue?.batch_seq || 0;
  renderTailorQueueBanner(state.tailorQueue);
});

(async function init() {
  await loadSources();
  await loadConfig();
  await Promise.all([loadStats(), loadJobs(), refreshCopilotBadge()]);
  const [scanSt, tqSt] = await Promise.all([
    api("/api/scan/status"),
    api("/api/tailor-queue/status").catch(() => null),
  ]);
  if (scanSt.is_running) pollScanProgress();
  if (tqSt) {
    state.tailorQueue = tqSt;
    state._lastTailorDoneCount = tqSt.done_count;
    state._lastNotifiedBatchSeq = tqSt.is_running ? 0 : tqSt.batch_seq;
    renderTailorQueueBanner(tqSt);
    renderJobList();
    if (tqSt.is_running) startTailorQueuePolling();
  }
})();
