package com.jobscout.android.domain

import android.text.Html
import com.jobscout.android.model.NormalizedJob
import com.jobscout.android.model.ScannerConfig
import com.jobscout.android.model.ScoreBreakdown
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ScoringEngine {

    private val TAG_RE = Regex("<[^>]+>")
    private val WS_RE = Regex("\\s+")
    private val YEARS_RE = Regex(
        "\\b(?:minimum(?:\\s+of)?\\s+|at\\s+least\\s+)?(\\d{1,2})\\s*(?:\\+|plus)?\\s*(?:-|to)?\\s*(\\d{1,2})?\\s*years?(?:\\s*\\+\\s*)?(?:\\s+of)?\\s+(?:[\\w/-]+\\s+){0,3}?experience\\b",
        RegexOption.IGNORE_CASE
    )
    private val BLOCK_BREAK_RE = Regex("</(p|li|div|h[1-6]|br|tr)>", RegexOption.IGNORE_CASE)
    private val LI_OPEN_RE = Regex("<li[^>]*>", RegexOption.IGNORE_CASE)
    private val NON_ALNUM_RE = Regex("[^a-z0-9]+")
    private val CORP_SUFFIX_RE = Regex("\\b(ltd|limited|pty|inc|corp|corporation|nz|new zealand)\\b")

    fun htmlToCleanText(rawHtml: String?): String {
        if (rawHtml.isNullOrBlank()) return ""
        var text = BLOCK_BREAK_RE.replace(rawHtml, "\n")
        text = LI_OPEN_RE.replace(text, "• ")
        text = TAG_RE.replace(text, " ")
        text = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
        return text.lines()
            .map { WS_RE.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    fun computeDedupKey(title: String, company: String, location: String): String {
        fun norm(s: String): String {
            var x = s.lowercase().trim()
            x = CORP_SUFFIX_RE.replace(x, "")
            x = NON_ALNUM_RE.replace(x, " ")
            return WS_RE.replace(x, " ").trim()
        }
        val city = norm(location.split(",").lastOrNull() ?: "")
        val payload = "${norm(title)}|${norm(company)}|$city"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun wordMatch(term: String, haystack: String): Boolean {
        val lower = term.lowercase()
        val startsAlnum = lower.firstOrNull()?.let { it.isLetterOrDigit() } == true
        val endsAlnum = lower.lastOrNull()?.let { it.isLetterOrDigit() } == true
        return if (startsAlnum && endsAlnum) {
            val escaped = Regex.escape(lower)
            Regex("(?<![a-z0-9])$escaped(?![a-z0-9])").containsMatchIn(haystack)
        } else {
            haystack.contains(lower)
        }
    }

    private fun containsAny(terms: List<String>, text: String): List<String> {
        val lowered = text.lowercase()
        return terms.filter { it.isNotBlank() && wordMatch(it, lowered) }
    }

    fun scoreJob(job: NormalizedJob, config: ScannerConfig): ScoreBreakdown {
        val rules = config.scoring
        val companyLower = job.company.lowercase()
        val titleLower = job.title.lowercase()
        val bodyText = job.descriptionText.ifBlank { htmlToCleanText(job.descriptionHtml) }.ifBlank { job.teaser }
        val fullText = buildString {
            append(job.title).append('\n')
            append(job.company).append('\n')
            append(job.location).append('\n')
            append(job.bulletPoints.joinToString(" ")).append('\n')
            append(job.teaser).append('\n')
            append(bodyText)
        }.lowercase()

        // 1. Employer / Subsidiary Blocklist Check
        for (blocked in config.blockedCompanies) {
            val b = blocked.trim()
            if (b.isNotEmpty() && companyLower.contains(b.lowercase())) {
                return ScoreBreakdown(
                    score = -999,
                    blockedReason = "Blocked employer match: '$blocked' in company '${job.company}'"
                )
            }
        }
        for (kw in config.blockedKeywordsInCompany) {
            val k = kw.trim()
            if (k.isNotEmpty() && companyLower.contains(k.lowercase())) {
                return ScoreBreakdown(
                    score = -999,
                    blockedReason = "Blocked company keyword: '$kw' in '${job.company}'"
                )
            }
        }

        val isManualImport = job.matchedQueries.contains("Manual Link") || job.matchedQueries.contains("Manual JD")
        if ((job.isAgency || companyLower.isBlank() || companyLower == "private advertiser") && !isManualImport) {
            val bodyLower = bodyText.lowercase()
            for (blocked in config.blockedCompanies) {
                val b = blocked.trim().lowercase()
                if (b.length >= 5 && wordMatch(b, bodyLower)) {
                    return ScoreBreakdown(
                        score = -999,
                        blockedReason = "Blocked employer mentioned in agency description: '$blocked'"
                    )
                }
            }
        }

        var score = 20
        val matchedPos = mutableListOf<String>()
        val matchedNeg = mutableListOf<String>()
        val flags = mutableListOf<String>()
        val notes = mutableListOf<String>()

        // 2. Hard title & body exclusions
        val titleExcludes = containsAny(rules.hardExcludeTitleTerms, titleLower)
        val bodyExcludes = containsAny(rules.hardExcludeBodyTerms, bodyText.lowercase())
        val hardHits = titleExcludes.map { "title:$it" } + bodyExcludes.map { "body:$it" }
        if (titleExcludes.isNotEmpty()) {
            score -= 45 * titleExcludes.size
            notes.add("Excluded title term(s): ${titleExcludes.joinToString(", ")}")
        }
        if (bodyExcludes.isNotEmpty()) {
            score -= 30 * bodyExcludes.size
            notes.add("Excluded body term(s): ${bodyExcludes.joinToString(", ")}")
        }

        // 3. Positive keyword scoring
        var posPoints = 0
        for ((kw, weight) in rules.positiveKeywords) {
            if (wordMatch(kw, titleLower)) {
                val bonus = weight + max(3, weight / 2)
                posPoints += bonus
                matchedPos.add("$kw (title +$bonus)")
            } else if (wordMatch(kw, fullText)) {
                posPoints += weight
                matchedPos.add("$kw (+$weight)")
            }
        }
        score += min(70, posPoints)

        // 4. Negative keyword scoring
        for ((kw, penalty) in rules.negativeKeywords) {
            if (wordMatch(kw, titleLower)) {
                val p = abs(penalty) + 12
                score -= p
                matchedNeg.add("$kw (title -$p)")
            } else if (wordMatch(kw, fullText)) {
                val p = abs(penalty)
                score -= p
                matchedNeg.add("$kw (-$p)")
            }
        }

        // 5. Location boost
        val locLower = job.location.lowercase()
        for ((locKw, locPts) in rules.preferredLocations) {
            if (locLower.contains(locKw.lowercase())) {
                score += locPts
                val sign = if (locPts >= 0) "+$locPts" else "$locPts"
                notes.add("Location '$locKw' ($sign)")
                break
            }
        }

        // 6. Agency penalty
        if (job.isAgency) {
            score += config.recruitmentAgencyPenalty
            flags.add("Recruitment Agency")
        }

        // 7. Experience years heuristic
        val expYears = mutableListOf<Int>()
        for (m in YEARS_RE.findAll(bodyText)) {
            val y1 = m.groupValues[1].toIntOrNull() ?: continue
            val y2 = m.groupValues.getOrNull(2)?.toIntOrNull() ?: y1
            if (y1 <= 20) expYears.add(min(y1, y2))
        }
        if (expYears.isNotEmpty()) {
            val reqYears = expYears.max()
            if (reqYears > rules.maxExperienceYears) {
                val penalty = min(35, (reqYears - rules.maxExperienceYears) * 8)
                score -= penalty
                flags.add("$reqYears+ yrs exp")
                notes.add("Requests $reqYears+ yrs experience (-$penalty)")
            }
        }

        // 7b. Intern / internship penalty
        if (wordMatch("intern", titleLower) || wordMatch("internship", titleLower)) {
            score -= 22
            flags.add("Internship")
            notes.add("Internship title (-22)")
        }

        // 8. Salary floor check
        val salTop = job.salaryEstMax ?: job.salaryEstMin
        if (salTop != null && salTop < rules.minAcceptableSalary) {
            score -= 20
            flags.add("Below $${rules.minAcceptableSalary / 1000}k")
        }

        // 9. Helpful informational flags
        if (listOf("nz citizen", "citizenship", "security clearance").any { fullText.contains(it) }) {
            flags.add("NZ Citizen / Clearance Fit")
        }
        if (listOf("mandarin", "chinese", "bilingual").any { fullText.contains(it) }) {
            score += 10
            flags.add("Mandarin Bonus")
        }
        when {
            job.source == "manual" -> flags.add("Pasted JD")
            job.isLinkOut -> flags.add("External ATS")
            else -> flags.add("Seek Quick Apply")
        }

        return ScoreBreakdown(
            score = score.coerceIn(0, 100),
            matchedPositive = matchedPos,
            matchedNegative = matchedNeg,
            hardExcludeHits = hardHits,
            flags = flags,
            notes = notes
        )
    }
}
