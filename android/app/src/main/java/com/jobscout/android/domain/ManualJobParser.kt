package com.jobscout.android.domain

import java.net.URI
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class FormattedJd(
    val descriptionHtml: String,
    val descriptionText: String,
    val bulletPoints: List<String>,
    val teaser: String
)

data class HeuristicJdMeta(
    val title: String = "",
    val company: String = "",
    val location: String = "",
    val workType: String = "",
    val workArrangement: String = "",
    val salaryLabel: String = "",
    val salaryEstMin: Int? = null,
    val salaryEstMax: Int? = null
)

data class ParsedSalary(
    val label: String,
    val min: Int?,
    val max: Int?
)

object ManualJobParser {

    private val HTML_TAG_RE = Regex("<(p|div|ul|ol|li|br|h[1-6])\\b", RegexOption.IGNORE_CASE)
    private val SCRIPT_STYLE_RE = Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BULLET_LINE_RE = Regex("^\\s*(?:[•\\-*·–▪‣◦]|\\d+[.)])\\s+(.+)$")
    private val HEADING_WORD_RE = Regex(
        "^(?:about\\s+(?:the\\s+role|us|you|the\\s+company)|responsibilities|key\\s+responsibilities|requirements|what\\s+you(?:'ll|\\s+will)\\s+(?:do|bring)|who\\s+you\\s+are|skills|qualifications|benefits|why\\s+join)\\b",
        RegexOption.IGNORE_CASE
    )
    private val SKIP_SECOND_LINE_RE = Regex("^(about|overview|summary|description|we\\s+are)\\b", RegexOption.IGNORE_CASE)
    private val LOCATION_RE = Regex(
        "\\b(Auckland|Wellington|Christchurch|Hamilton|Tauranga|Dunedin|New Zealand|Sydney|Melbourne|Brisbane|Perth|Remote)\\b[^\\n,]{0,25}",
        RegexOption.IGNORE_CASE
    )
    private val SALARY_RANGE_RE = Regex(
        "(?:NZ\\$|AU\\$|\\$|NZD|AUD)?\\s*(\\d{2,3}(?:,\\d{3})*(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)\\s*(k)?\\s*(?:-|–|—|to)\\s*(?:NZ\\$|AU\\$|\\$|NZD|AUD)?\\s*(\\d{2,3}(?:,\\d{3})*(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)\\s*(k)?",
        RegexOption.IGNORE_CASE
    )
    private val SALARY_SINGLE_RE = Regex(
        "(?:NZ\\$|AU\\$|\\$|NZD|AUD)\\s*(\\d{2,3}(?:,\\d{3})+|\\d{2,3}\\s*k)\\b",
        RegexOption.IGNORE_CASE
    )
    private val WS_RE = Regex("\\s+")

    fun normalizeUrl(url: String): String {
        val raw = url.trim()
        if (raw.isBlank()) return ""
        return if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
            raw
        } else {
            "https://$raw"
        }
    }

    fun extractUrlDomain(url: String): String {
        val normalized = normalizeUrl(url)
        if (normalized.isBlank()) return ""
        return runCatching {
            val host = (URI(normalized).host ?: "").lowercase()
            if (host.startsWith("www.")) host.removePrefix("www.") else host
        }.getOrDefault("")
    }

    fun parseSalaryText(raw: String): ParsedSalary {
        val text = raw.trim()
        if (text.isBlank()) return ParsedSalary("", null, null)

        val rangeMatch = SALARY_RANGE_RE.find(text)
        if (rangeMatch != null) {
            var v1 = rangeMatch.groupValues[1].replace(",", "").toDoubleOrNull()
            var v2 = rangeMatch.groupValues[3].replace(",", "").toDoubleOrNull()
            val k1 = rangeMatch.groupValues[2].isNotEmpty()
            val k2 = rangeMatch.groupValues[4].isNotEmpty()
            if (v1 != null && v2 != null) {
                if (k1 || (!k1 && k2 && v1 < 1000.0)) v1 *= 1000.0
                if (k2 || (!k2 && k1 && v2 < 1000.0)) v2 *= 1000.0
                val sMin = min(v1, v2).roundToInt()
                val sMax = max(v1, v2).roundToInt()
                val label = rangeMatch.value.trim()
                return if (sMin in 25000..600000 && sMax in 25000..600000) {
                    ParsedSalary(label, sMin, sMax)
                } else {
                    ParsedSalary(label, null, null)
                }
            }
        }

        val singleMatch = SALARY_SINGLE_RE.find(text)
        if (singleMatch != null) {
            val numStr = singleMatch.groupValues[1].lowercase().replace(",", "").trim()
            val value = if (numStr.endsWith("k")) {
                numStr.dropLast(1).trim().toDoubleOrNull()?.times(1000.0)
            } else {
                numStr.toDoubleOrNull()
            }
            val label = singleMatch.value.trim()
            if (value != null) {
                val sVal = value.roundToInt()
                return if (sVal in 25000..600000) {
                    ParsedSalary(label, sVal, sVal)
                } else {
                    ParsedSalary(label, null, null)
                }
            }
        }

        return ParsedSalary("", null, null)
    }

    private fun escHtml(s: String): String = buildString(s.length) {
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

    fun formatPastedJd(rawJd: String): FormattedJd {
        val raw = rawJd.trim()
        if (raw.isBlank()) return FormattedJd("", "", emptyList(), "")

        val descriptionHtml: String
        val descriptionText: String

        if (HTML_TAG_RE.containsMatchIn(raw)) {
            val cleaned = SCRIPT_STYLE_RE.replace(raw, "")
            descriptionText = ScoringEngine.htmlToCleanText(cleaned)
            descriptionHtml = cleaned
        } else {
            val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n").map { it.trimEnd() }
            val htmlParts = mutableListOf<String>()
            val cleanLines = mutableListOf<String>()
            var inList = false

            for (ln in lines) {
                val stripped = ln.trim()
                if (stripped.isEmpty()) {
                    if (inList) {
                        htmlParts.add("</ul>")
                        inList = false
                    }
                    continue
                }
                val bulletMatch = BULLET_LINE_RE.matchEntire(stripped)
                if (bulletMatch != null) {
                    val item = bulletMatch.groupValues[1].trim()
                    if (!inList) {
                        htmlParts.add("<ul>")
                        inList = true
                    }
                    htmlParts.add("<li>${escHtml(item)}</li>")
                    cleanLines.add("• $item")
                } else {
                    if (inList) {
                        htmlParts.add("</ul>")
                        inList = false
                    }
                    val isHeading = stripped.length <= 64 &&
                        !stripped.endsWith(".") &&
                        (stripped.endsWith(":") || HEADING_WORD_RE.containsMatchIn(stripped))
                    if (isHeading) {
                        htmlParts.add("<h3>${escHtml(stripped)}</h3>")
                    } else {
                        htmlParts.add("<p>${escHtml(stripped)}</p>")
                    }
                    cleanLines.add(stripped)
                }
            }
            if (inList) htmlParts.add("</ul>")
            descriptionHtml = htmlParts.joinToString("\n")
            descriptionText = cleanLines.joinToString("\n")
        }

        val bullets = descriptionText.lines()
            .filter { it.startsWith("• ") && it.length > 10 }
            .map { it.removePrefix("• ").trim() }
            .take(5)

        val teaserSrc = descriptionText.lines()
            .filter { !it.startsWith("• ") && it.length > 20 }
            .joinToString(" ")
            .ifBlank { descriptionText }
        val teaser = WS_RE.replace(teaserSrc, " ").trim().take(240)

        return FormattedJd(
            descriptionHtml = descriptionHtml,
            descriptionText = descriptionText,
            bulletPoints = bullets,
            teaser = teaser
        )
    }

    fun heuristicExtractJd(descriptionText: String): HeuristicJdMeta {
        val lines = descriptionText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("• ") }

        var title = ""
        var company = ""
        var location = ""
        var workType = ""
        var workArrangement = ""

        if (lines.isNotEmpty()) {
            val first = lines[0]
            when {
                " at " in first && first.length <= 100 -> {
                    val idx = first.indexOf(" at ")
                    title = first.substring(0, idx).trim()
                    company = first.substring(idx + 4).trim()
                }
                " - " in first && first.length <= 100 -> {
                    val parts = first.split(" - ", limit = 3).map { it.trim() }
                    if (parts.size >= 2) {
                        title = parts[0]
                        company = parts[1]
                        if (parts.size == 3) location = parts[2]
                    }
                }
                " @ " in first && first.length <= 100 -> {
                    val idx = first.indexOf(" @ ")
                    title = first.substring(0, idx).trim()
                    company = first.substring(idx + 3).trim()
                }
                else -> {
                    title = first.take(100)
                    if (lines.size > 1 && lines[1].length <= 70 && !SKIP_SECOND_LINE_RE.containsMatchIn(lines[1])) {
                        val second = lines[1]
                        if (" · " in second) {
                            val segs = second.split(" · ").map { it.trim() }
                            company = segs.getOrElse(0) { "" }
                            if (segs.size > 1) location = segs[1]
                        } else {
                            company = second
                        }
                    }
                }
            }
        }

        if (location.isBlank()) {
            val headBlob = lines.take(12).joinToString("\n")
            val m = LOCATION_RE.find(headBlob)
            if (m != null) {
                location = m.value.trim(' ', ',', '·', '|')
            }
        }

        val fullLow = descriptionText.lowercase()
        workType = when {
            Regex("\\bfull[\\s-]?time\\b").containsMatchIn(fullLow) -> "Full time"
            Regex("\\bpart[\\s-]?time\\b").containsMatchIn(fullLow) -> "Part time"
            Regex("\\b(?:contract|fixed[\\s-]?term|casual|temp)\\b").containsMatchIn(fullLow) -> "Contract/Temp"
            else -> ""
        }

        workArrangement = when {
            Regex("\\bhybrid\\b").containsMatchIn(fullLow) -> "Hybrid"
            Regex("\\bremote\\b").containsMatchIn(fullLow) -> "Remote"
            Regex("\\bon[\\s-]?site\\b").containsMatchIn(fullLow) -> "On-site"
            else -> ""
        }

        val parsedSal = parseSalaryText(descriptionText.take(2000))
        return HeuristicJdMeta(
            title = title,
            company = company,
            location = location,
            workType = workType,
            workArrangement = workArrangement,
            salaryLabel = parsedSal.label,
            salaryEstMin = parsedSal.min,
            salaryEstMax = parsedSal.max
        )
    }

    fun computePasteExternalId(cleanUrl: String, title: String, company: String, cleanText: String): String {
        val extKey = if (cleanUrl.isNotBlank()) {
            cleanUrl.lowercase().trimEnd('/')
        } else {
            "${title.lowercase()}|${company.lowercase()}|${cleanText.take(500).lowercase()}"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(extKey.toByteArray(Charsets.UTF_8))
        val hex16 = digest.joinToString("") { "%02x".format(it) }.take(16)
        return "paste_$hex16"
    }
}
