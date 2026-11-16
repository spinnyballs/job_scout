package com.jobscout.android.domain

import com.jobscout.android.model.CandidateProfile
import com.jobscout.android.model.JobRecord
import com.jobscout.android.model.LLMFitAnalysis
import com.jobscout.android.model.QABankEntry
import com.jobscout.android.model.TailoredApplicationPayload
import com.jobscout.android.network.LlmProvider
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

object TailorService {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private val FIT_SCHEMA_HINT = """
        {
          "fit_score": 0..100,
          "verdict": "Strong Apply | Worth Applying | Stretch | Skip",
          "role_angle": "string",
          "strengths": ["string"],
          "gaps_and_risks": ["string"],
          "recommended_projects": ["string"],
          "rationale": "string"
        }
    """.trimIndent()

    private val TAILOR_SCHEMA_HINT = """
        {
          "tailored_headline": "string",
          "tailored_summary": "string",
          "selected_experiences": [
            {"experience_id": "string", "role": "string", "company": "string", "location": "string", "dates": "string", "bullets": ["string"]}
          ],
          "selected_projects": [
            {"project_id": "string", "name": "string", "bullets": ["string"]}
          ],
          "tailored_skills": {"Category": "comma-separated items"},
          "cover_letter_subject": "string",
          "cover_letter_body": "string",
          "suggested_screening_answers": {"Exact question text": "Draft answer"},
          "truth_audit_notes": ["string"]
        }
    """.trimIndent()

    suspend fun analyzeFit(
        provider: LlmProvider,
        profile: CandidateProfile,
        job: JobRecord
    ): LLMFitAnalysis {
        val systemPrompt = "You are a candid, technically sharp Electrical & Electronics Engineering hiring advisor in New Zealand. " +
            "Assess candidate fit strictly against the provided Verified Candidate Profile. " +
            "Do NOT exaggerate or assume unstated experience. Note gaps honestly (e.g., if the job asks for 5+ yrs " +
            "commercial experience, EWRB licence, or specialized tools not in the candidate's profile)."

        val userPrompt = buildString {
            appendLine("### VERIFIED CANDIDATE PROFILE (JSON)")
            appendLine(json.encodeToString(profile))
            appendLine()
            appendLine("### TARGET JOB")
            appendLine("Title: ${job.title}")
            appendLine("Company: ${job.company}")
            appendLine("Location: ${job.location}")
            appendLine("Work Type: ${job.workType}")
            appendLine("Salary Label: ${job.salaryLabel.ifBlank { "Not disclosed" }}")
            appendLine("Estimated Band: ${job.salaryEstMin} - ${job.salaryEstMax}")
            appendLine()
            appendLine("Full Job Description:")
            appendLine(job.descriptionText.ifBlank { job.teaser })
        }

        return provider.generateStructured(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            schemaHintJson = FIT_SCHEMA_HINT,
            serializer = LLMFitAnalysis.serializer(),
            temperature = 0.2
        )
    }

    suspend fun generateTailoredPackage(
        provider: LlmProvider,
        profile: CandidateProfile,
        job: JobRecord,
        qaEntries: List<QABankEntry>,
        extraAngle: String = ""
    ): TailoredApplicationPayload {
        val rulesBlock = profile.strictTruthRules.joinToString("\n") { "- $it" }
        val systemPrompt = buildString {
            appendLine("You are crafting a precision-tailored engineering CV, cover letter, and screening-question draft for a New Zealand engineering role.")
            appendLine("STRICT ANTI-HALLUCINATION RULES:")
            appendLine(rulesBlock)
            appendLine("- Keep the CV crisp and tightly formatted so it fits cleanly onto ONE A4 page (select top 3 most relevant projects, keep bullets punchy).")
            append("- Cover letter must be crisp, human, specific to the employer's actual product/domain, and 220–320 words.")
        }

        val qaSummary = qaEntries.map {
            mapOf("key" to it.canonicalKey, "pattern" to it.questionPattern, "answer" to it.answer)
        }

        val userPrompt = buildString {
            appendLine("### VERIFIED CANDIDATE PROFILE")
            appendLine(json.encodeToString(profile))
            appendLine()
            appendLine("### CANONICAL SCREENING Q&A MEMORY BANK")
            appendLine(json.encodeToString(qaSummary))
            appendLine()
            appendLine("### TARGET JOB")
            appendLine("Title: ${job.title}")
            appendLine("Company: ${job.company}")
            appendLine("Location: ${job.location}")
            appendLine("Screening Questions on Form: ${json.encodeToString(job.screeningQuestions)}")
            appendLine("Candidate Guidance / Angle: ${extraAngle.ifBlank { "Emphasize best authentic overlap with this role." }}")
            appendLine()
            appendLine("Job Description:")
            appendLine(job.descriptionText.ifBlank { job.teaser })
        }

        return provider.generateStructured(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            schemaHintJson = TAILOR_SCHEMA_HINT,
            serializer = TailoredApplicationPayload.serializer(),
            temperature = 0.25
        )
    }

    private val AUDIT_SCHEMA_HINT = """
        {
          "overall_feedback": "2-3 sentence candid assessment of profile completeness",
          "missing_skills_from_projects": ["Tool or skill already evident in projects/experience but missing from skills list"],
          "market_gap_suggestions": ["Skill/tool/keyword frequently requested in target jobs that candidate should add ONLY if they actually have it"],
          "bullet_improvements": ["Specific suggestion to quantify or clarify a bullet"],
          "suggested_bio_tweak": "Optional sharper 3-4 sentence base summary/bio"
        }
    """.trimIndent()

    suspend fun auditProfile(
        provider: LlmProvider,
        profile: CandidateProfile,
        recentJobs: List<JobRecord>
    ): com.jobscout.android.model.ProfileAuditSuggestions {
        val systemPrompt = "You are a candid Electrical & Electronics Engineering career advisor in New Zealand. " +
            "Review the candidate's Master Profile (bio, experiences, projects, and skills) against their recent target jobs. " +
            "Identify skills/tools they already used in projects or work that are missing from their Skills section, " +
            "surface common target-job keywords they might have forgotten to list (only suggest adding if they genuinely have them), " +
            "and recommend specific bullet improvements."

        val jobSnippets = recentJobs.take(15).joinToString("\n") { j ->
            "- ${j.title} @ ${j.company} (Score ${j.score}): ${j.descriptionText.take(350).replace('\n', ' ')}"
        }

        val userPrompt = buildString {
            appendLine("### CANDIDATE MASTER PROFILE (JSON)")
            appendLine(json.encodeToString(profile))
            appendLine()
            if (jobSnippets.isNotBlank()) {
                appendLine("### RECENT TARGET JOBS ON SEEK")
                appendLine(jobSnippets)
            }
        }

        return provider.generateStructured(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            schemaHintJson = AUDIT_SCHEMA_HINT,
            serializer = com.jobscout.android.model.ProfileAuditSuggestions.serializer(),
            temperature = 0.25
        )
    }

    fun slugify(text: String): String =
        text.replace(Regex("[^a-zA-Z0-9]+"), "_").trim('_').take(48).ifEmpty { "role" }

    private fun esc(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    fun renderCvHtml(profile: CandidateProfile, tailored: TailoredApplicationPayload): String {
        val expHtml = tailored.selectedExperiences.joinToString("") { exp ->
            val bullets = exp.bullets.joinToString("\n") { "<li>${esc(it)}</li>" }
            """
            <div class="entry">
              <div class="entry-head">
                <div><strong>${esc(exp.role)}</strong> — ${esc(exp.company)}, ${esc(exp.location)}</div>
                <div class="dates">${esc(exp.dates)}</div>
              </div>
              <ul>$bullets</ul>
            </div>
            """.trimIndent()
        }

        val projHtml = tailored.selectedProjects.joinToString("") { proj ->
            val bullets = proj.bullets.joinToString("\n") { "<li>${esc(it)}</li>" }
            """
            <div class="entry compact">
              <div class="proj-title"><strong>${esc(proj.name)}</strong></div>
              <ul>$bullets</ul>
            </div>
            """.trimIndent()
        }

        val eduHtml = profile.education.joinToString("") { ed ->
            """
            <div class="entry-head">
              <div><strong>${esc(ed["degree"] ?: "")}</strong> — ${esc(ed["institution"] ?: "")}</div>
              <div class="dates">${esc(ed["dates"] ?: "")}</div>
            </div>
            <div class="subline">${esc(ed["details"] ?: "")}</div>
            """.trimIndent()
        }

        val skillsHtml = tailored.tailoredSkills.entries
            .filter { it.value.isNotBlank() }
            .joinToString("\n") { (cat, value) ->
                "<tr><td class='sk-cat'>${esc(cat)}:</td><td>${esc(value)}</td></tr>"
            }

        val extraProj = if (profile.additionalProjects.isNotEmpty()) {
            "<div class='subline'><strong>Additional Projects:</strong> ${esc(profile.additionalProjects.joinToString(" · "))}</div>"
        } else ""

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<title>${esc(profile.fullName)} - CV</title>
<style>
  @page { size: A4; margin: 10mm 13mm 10mm 13mm; }
  body {
    font-family: "Segoe UI", Roboto, Arial, sans-serif;
    color: #1e2430;
    font-size: 9pt;
    line-height: 1.3;
    margin: 0;
  }
  .header { border-bottom: 1.5px solid #2563eb; padding-bottom: 5px; margin-bottom: 7px; }
  h1 { margin: 0; font-size: 16.5pt; letter-spacing: 0.4px; color: #0f172a; }
  .headline { font-size: 9.6pt; font-weight: 600; color: #1d4ed8; margin: 1px 0 3px; }
  .contact { font-size: 8.4pt; color: #334155; line-height: 1.35; }
  h2 {
    font-size: 9.4pt;
    text-transform: uppercase;
    letter-spacing: 0.7px;
    color: #0f172a;
    border-bottom: 1px solid #cbd5e1;
    padding-bottom: 2px;
    margin: 8px 0 4px;
    break-after: avoid;
  }
  p.summary { margin: 0 0 4px; }
  .entry { margin-bottom: 5px; break-inside: avoid; }
  .entry.compact { margin-bottom: 4px; break-inside: avoid; }
  .entry-head { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
  .dates { font-size: 8.4pt; color: #475569; white-space: nowrap; font-style: italic; }
  .proj-title { margin-bottom: 1px; }
  .subline { font-size: 8.6pt; color: #334155; margin-top: 2px; }
  ul { margin: 1.5px 0 0; padding-left: 15px; }
  li { margin-bottom: 1.5px; }
  table.skills { width: 100%; border-collapse: collapse; font-size: 8.8pt; break-inside: avoid; }
  table.skills td { padding: 1px 4px 1px 0; vertical-align: top; }
  td.sk-cat { width: 155px; font-weight: 600; color: #0f172a; white-space: nowrap; }
</style>
</head>
<body>
  <div class="header">
    <h1>${esc(profile.fullName.uppercase())}</h1>
    <div class="headline">${esc(tailored.tailoredHeadline)}</div>
    <div class="contact">
      ${esc(profile.phone)} · ${esc(profile.email)} · ${esc(profile.location)} · ${esc(profile.linkedin)}<br/>
      <strong>Work Rights:</strong> ${esc(profile.workRights)} | <strong>Languages:</strong> ${esc(profile.languages)}
    </div>
  </div>

  <h2>Profile</h2>
  <p class="summary">${esc(tailored.tailoredSummary)}</p>

  <h2>Work Experience</h2>
  $expHtml

  <h2>Education</h2>
  $eduHtml

  <h2>Key Engineering Projects</h2>
  $projHtml
  $extraProj

  <h2>Technical Skills</h2>
  <table class="skills">$skillsHtml</table>
</body>
</html>"""
    }

    private val CLOSING_PREFIX_RE = Regex(
        "^(?:(?:kind|warm|best|many)\\s+(?:regards|wishes)|regards|yours\\s+(?:sincerely|faithfully|truly)|sincerely|respectfully|thanks(?:\\s+again)?)[,!.]*",
        RegexOption.IGNORE_CASE
    )

    private fun splitCoverLetterSections(bodyText: String, fullName: String): Triple<String, List<String>, String> {
        val raw = bodyText.replace("\r\n", "\n").trim()
        if (raw.isEmpty()) return Triple("Dear Hiring Manager,", emptyList(), "Kind regards,")

        val paragraphs = raw.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        var salutation = "Dear Hiring Manager,"
        var closing = "Kind regards,"

        if (paragraphs.isNotEmpty()) {
            val firstLines = paragraphs[0].split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (firstLines.isNotEmpty() && Regex("^(?:dear|hi|hello|to\\s+(?:whom|the))\\b.{0,80}$", RegexOption.IGNORE_CASE).matches(firstLines[0])) {
                salutation = firstLines[0].trimEnd(',', ':', '.') + ","
                if (firstLines.size > 1) {
                    paragraphs[0] = firstLines.drop(1).joinToString(" ")
                } else {
                    paragraphs.removeAt(0)
                }
            }
        }

        val nameNorm = fullName.trim().replace(Regex("\\s+"), " ").lowercase()
        while (paragraphs.isNotEmpty()) {
            val tailLines = paragraphs.last().split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (tailLines.isEmpty()) {
                paragraphs.removeAt(paragraphs.lastIndex)
                continue
            }
            var changed = false
            while (tailLines.isNotEmpty()) {
                val last = tailLines.last()
                val lastNorm = last.lowercase().replace(Regex("[^a-z0-9 ]+"), "").trim()
                if (lastNorm == nameNorm || last.lowercase() in listOf("[your name]", "[name]") ||
                    Regex("^\\+?\\d[\\d\\s\\-()]{6,}$").matches(last) || ("@" in last && last.length < 60 && " " !in last)
                ) {
                    tailLines.removeAt(tailLines.lastIndex)
                    changed = true
                    continue
                }
                val mClose = CLOSING_PREFIX_RE.find(last)
                if (mClose != null && mClose.range.first == 0) {
                    val after = last.substring(mClose.range.last + 1).trim(' ', ',', '.', '!', '-', '\t')
                    val afterNorm = after.lowercase().replace(Regex("[^a-z0-9 ]+"), "").trim()
                    if (after.isEmpty() || afterNorm == nameNorm) {
                        closing = mClose.value.trim(' ', ',', '.', '!').replaceFirstChar { it.uppercaseChar() } + ","
                        tailLines.removeAt(tailLines.lastIndex)
                        changed = true
                        continue
                    }
                }
                break
            }
            if (tailLines.isEmpty()) {
                paragraphs.removeAt(paragraphs.lastIndex)
                continue
            }
            if (changed) paragraphs[paragraphs.lastIndex] = tailLines.joinToString(" ")
            break
        }

        val cleaned = paragraphs.map { it.replace(Regex("\\s*\\n\\s*"), " ").trim() }.filter { it.isNotEmpty() }
        return Triple(salutation, cleaned, closing)
    }

    fun renderCoverLetterHtml(
        profile: CandidateProfile,
        tailored: TailoredApplicationPayload,
        jobTitle: String,
        company: String
    ): String {
        val (salutation, paragraphs, closing) = splitCoverLetterSections(tailored.coverLetterBody, profile.fullName)
        val bodyHtml = paragraphs.joinToString("\n") { "<p>${esc(it)}</p>" }
        val cleanCompany = if (company.length > 4 && company == company.uppercase()) {
            company.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
        } else company.trim()

        var subj = tailored.coverLetterSubject.trim()
        subj = when {
            subj.isEmpty() -> "Application: $jobTitle — $cleanCompany"
            !Regex("^(?:application|re)\\b", RegexOption.IGNORE_CASE).containsMatchIn(subj) -> "Re: $subj"
            else -> subj
        }
        val subtitle = tailored.tailoredHeadline.ifBlank { profile.headline }.trim()
        val today = LocalDate.now()
        val monthName = today.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val dateStr = "${today.dayOfMonth} $monthName ${today.year}"

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<title>${esc(profile.fullName)} - Cover Letter</title>
<style>
  @page { size: A4; margin: 18mm 20mm 18mm 20mm; }
  body {
    font-family: "Segoe UI", Roboto, Arial, sans-serif;
    color: #1e2430;
    font-size: 10pt;
    line-height: 1.5;
    margin: 0;
  }
  .header { border-bottom: 1.5px solid #2563eb; padding-bottom: 6px; margin-bottom: 16px; }
  h1 { margin: 0; font-size: 16.5pt; letter-spacing: 0.4px; color: #0f172a; }
  .headline { font-size: 9.6pt; font-weight: 600; color: #1d4ed8; margin: 1px 0 3px; }
  .contact { font-size: 8.6pt; color: #334155; }
  .date { font-size: 9.4pt; color: #475569; margin-bottom: 10px; }
  .subject { font-weight: 700; color: #0f172a; margin-bottom: 14px; font-size: 10.4pt; }
  .salutation { margin-bottom: 11px; font-weight: 600; color: #0f172a; }
  p { margin: 0 0 11px; }
  .signoff { margin-top: 16px; }
  .sig-name { margin-top: 12px; font-weight: 700; color: #0f172a; }
</style>
</head>
<body>
  <div class="header">
    <h1>${esc(profile.fullName.uppercase())}</h1>
    ${if (subtitle.isNotBlank()) "<div class=\"headline\">${esc(subtitle)}</div>" else ""}
    <div class="contact">
      ${esc(profile.phone)} · ${esc(profile.email)} · ${esc(profile.location)} · ${esc(profile.linkedin)}
    </div>
  </div>
  <div class="date">${esc(dateStr)}</div>
  <div class="subject">${esc(subj)}</div>
  <div class="salutation">${esc(salutation)}</div>
  $bodyHtml
  <div class="signoff">
    <div>${esc(closing)}</div>
    <div class="sig-name">${esc(profile.fullName)}</div>
  </div>
</body>
</html>"""
    }
}
