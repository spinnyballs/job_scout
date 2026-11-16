package com.jobscout.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class JobStatus(val value: String) {
    @SerialName("discovered") DISCOVERED("discovered"),
    @SerialName("shortlisted") SHORTLISTED("shortlisted"),
    @SerialName("skipped") SKIPPED("skipped"),
    @SerialName("blocked") BLOCKED("blocked"),
    @SerialName("tailored") TAILORED("tailored"),
    @SerialName("applied") APPLIED("applied");

    companion object {
        fun fromValue(v: String?): JobStatus =
            entries.firstOrNull { it.value == v } ?: DISCOVERED
    }
}

@Serializable
data class SearchQueryConfig(
    val name: String,
    val keywords: String,
    val where: String = "All Auckland",
    @SerialName("site_key") val siteKey: String = "NZ-Main",
    @SerialName("salary_min") val salaryMin: Int? = null,
    @SerialName("salary_max") val salaryMax: Int? = null,
    @SerialName("work_type") val workType: String? = null,
    @SerialName("date_range_days") val dateRangeDays: Int? = 31,
    @SerialName("max_pages") val maxPages: Int = 2,
    val enabled: Boolean = true,
    val tags: List<String> = emptyList()
)

@Serializable
data class ScoringRuleConfig(
    @SerialName("min_score_to_surface") val minScoreToSurface: Int = 25,
    @SerialName("positive_keywords") val positiveKeywords: Map<String, Int> = emptyMap(),
    @SerialName("negative_keywords") val negativeKeywords: Map<String, Int> = emptyMap(),
    @SerialName("hard_exclude_title_terms") val hardExcludeTitleTerms: List<String> = emptyList(),
    @SerialName("hard_exclude_body_terms") val hardExcludeBodyTerms: List<String> = emptyList(),
    @SerialName("preferred_locations") val preferredLocations: Map<String, Int> = emptyMap(),
    @SerialName("min_acceptable_salary") val minAcceptableSalary: Int = 65000,
    @SerialName("max_experience_years") val maxExperienceYears: Int = 4
)

@Serializable
data class CustomProviderPreset(
    val id: String,
    val label: String,
    @SerialName("base_url") val baseUrl: String,
    val model: String,
    @SerialName("api_key_name") val apiKeyName: String
)

@Serializable
data class LLMProviderConfig(
    @SerialName("active_provider") val activeProvider: String = "deepseek",
    @SerialName("gemini_model") val geminiModel: String = "gemini-2.5-flash",
    @SerialName("gemini_thinking_level") val geminiThinkingLevel: String = "default",
    @SerialName("gemini_api_key_env") val geminiApiKeyEnv: String = "GEMINI_API_KEY",
    @SerialName("deepseek_model") val deepseekModel: String = "deepseek-chat",
    @SerialName("deepseek_base_url") val deepseekBaseUrl: String = "https://api.deepseek.com/v1",
    @SerialName("deepseek_api_key_env") val deepseekApiKeyEnv: String = "DEEPSEEK_API_KEY",
    @SerialName("openai_base_url") val openaiBaseUrl: String = "https://api.openai.com/v1",
    @SerialName("openai_model") val openaiModel: String = "gpt-4o-mini",
    @SerialName("openai_api_key_env") val openaiApiKeyEnv: String = "OPENAI_API_KEY",
    @SerialName("anthropic_model") val anthropicModel: String = "claude-sonnet-4-5",
    @SerialName("anthropic_api_key_env") val anthropicApiKeyEnv: String = "ANTHROPIC_API_KEY",
    val temperature: Double = 0.25,
    @SerialName("custom_presets") val customPresets: List<CustomProviderPreset> = emptyList()
)

@Serializable
data class ScannerConfig(
    @SerialName("profile_name") val profileName: String = "My Job Profile",
    @SerialName("default_board") val defaultBoard: String = "seek_nz",
    @SerialName("blocked_companies") val blockedCompanies: List<String> = emptyList(),
    @SerialName("blocked_keywords_in_company") val blockedKeywordsInCompany: List<String> = emptyList(),
    @SerialName("recruitment_agency_penalty") val recruitmentAgencyPenalty: Int = -8,
    @SerialName("company_cooldown_days") val companyCooldownDays: Int = 14,
    @SerialName("probe_salary_above_score") val probeSalaryAboveScore: Int = 48,
    @SerialName("fetch_details_for_all_new") val fetchDetailsForAllNew: Boolean = true,
    @SerialName("request_delay_ms") val requestDelayMs: Int = 220,
    val queries: List<SearchQueryConfig> = emptyList(),
    val scoring: ScoringRuleConfig = ScoringRuleConfig(),
    val llm: LLMProviderConfig = LLMProviderConfig()
)

data class NormalizedJob(
    val source: String,
    val externalId: String,
    val url: String,
    var title: String,
    var company: String,
    var advertiserId: String? = null,
    var isAgency: Boolean = false,
    var location: String = "",
    var workType: String = "",
    var workArrangement: String = "",
    var classifications: List<String> = emptyList(),
    var teaser: String = "",
    var bulletPoints: List<String> = emptyList(),
    var descriptionHtml: String = "",
    var descriptionText: String = "",
    var salaryLabel: String = "",
    var salaryEstMin: Int? = null,
    var salaryEstMax: Int? = null,
    var postedAt: String? = null,
    var expiresAt: String? = null,
    var isLinkOut: Boolean = false,
    var isExpired: Boolean = false,
    var screeningQuestions: List<String> = emptyList(),
    var matchedQueries: MutableList<String> = mutableListOf()
)

@Serializable
data class ScoreBreakdown(
    val score: Int,
    @SerialName("blocked_reason") val blockedReason: String? = null,
    @SerialName("matched_positive") val matchedPositive: List<String> = emptyList(),
    @SerialName("matched_negative") val matchedNegative: List<String> = emptyList(),
    @SerialName("hard_exclude_hits") val hardExcludeHits: List<String> = emptyList(),
    val flags: List<String> = emptyList(),
    val notes: List<String> = emptyList()
)

@Serializable
data class ExperienceItem(
    val id: String,
    val role: String,
    val company: String,
    val location: String,
    val dates: String,
    val tags: List<String> = emptyList(),
    val bullets: List<String> = emptyList()
)

@Serializable
data class ProjectItem(
    val id: String,
    val name: String,
    val tags: List<String> = emptyList(),
    val bullets: List<String> = emptyList()
)

@Serializable
data class CandidateProfile(
    @SerialName("full_name") val fullName: String,
    val headline: String,
    val phone: String,
    val email: String,
    val location: String,
    val linkedin: String,
    @SerialName("work_rights") val workRights: String,
    val languages: String,
    @SerialName("base_summary") val baseSummary: String,
    val education: List<Map<String, String>> = emptyList(),
    val experiences: List<ExperienceItem> = emptyList(),
    val projects: List<ProjectItem> = emptyList(),
    @SerialName("additional_projects") val additionalProjects: List<String> = emptyList(),
    val skills: Map<String, List<String>> = emptyMap(),
    @SerialName("strict_truth_rules") val strictTruthRules: List<String> = emptyList()
)

@Serializable
data class LLMFitAnalysis(
    @SerialName("fit_score") val fitScore: Int,
    val verdict: String,
    @SerialName("role_angle") val roleAngle: String,
    val strengths: List<String> = emptyList(),
    @SerialName("gaps_and_risks") val gapsAndRisks: List<String> = emptyList(),
    @SerialName("recommended_projects") val recommendedProjects: List<String> = emptyList(),
    val rationale: String = ""
)

@Serializable
data class TailoredExperienceBlock(
    @SerialName("experience_id") val experienceId: String,
    val role: String,
    val company: String,
    val location: String,
    val dates: String,
    val bullets: List<String> = emptyList()
)

@Serializable
data class TailoredProjectBlock(
    @SerialName("project_id") val projectId: String,
    val name: String,
    val bullets: List<String> = emptyList()
)

@Serializable
data class TailoredApplicationPayload(
    @SerialName("tailored_headline") val tailoredHeadline: String,
    @SerialName("tailored_summary") val tailoredSummary: String,
    @SerialName("selected_experiences") val selectedExperiences: List<TailoredExperienceBlock> = emptyList(),
    @SerialName("selected_projects") val selectedProjects: List<TailoredProjectBlock> = emptyList(),
    @SerialName("tailored_skills") val tailoredSkills: Map<String, String> = emptyMap(),
    @SerialName("cover_letter_subject") val coverLetterSubject: String = "",
    @SerialName("cover_letter_body") val coverLetterBody: String = "",
    @SerialName("suggested_screening_answers") val suggestedScreeningAnswers: Map<String, String> = emptyMap(),
    @SerialName("truth_audit_notes") val truthAuditNotes: List<String> = emptyList()
)

@Serializable
data class ProfileAuditSuggestions(
    @SerialName("overall_feedback") val overallFeedback: String = "",
    @SerialName("missing_skills_from_projects") val missingSkillsFromProjects: List<String> = emptyList(),
    @SerialName("market_gap_suggestions") val marketGapSuggestions: List<String> = emptyList(),
    @SerialName("bullet_improvements") val bulletImprovements: List<String> = emptyList(),
    @SerialName("suggested_bio_tweak") val suggestedBioTweak: String = ""
)

@Serializable
data class QABankEntry(
    val id: Long? = null,
    @SerialName("canonical_key") val canonicalKey: String,
    @SerialName("question_pattern") val questionPattern: String,
    val answer: String,
    val category: String = "general",
    val notes: String = "",
    @SerialName("updated_at") val updatedAt: String? = null
)

data class JobRecord(
    val id: Long,
    val source: String,
    val externalId: String,
    val dedupKey: String,
    val url: String,
    val title: String,
    val company: String,
    val advertiserId: String?,
    val isAgency: Boolean,
    val location: String,
    val workType: String,
    val workArrangement: String,
    val classifications: List<String>,
    val teaser: String,
    val bulletPoints: List<String>,
    val descriptionHtml: String,
    val descriptionText: String,
    val salaryLabel: String,
    val salaryEstMin: Int?,
    val salaryEstMax: Int?,
    val postedAt: String?,
    val expiresAt: String?,
    val isLinkOut: Boolean,
    val isExpired: Boolean,
    val screeningQuestions: List<String>,
    val matchedQueries: List<String>,
    val score: Int,
    val scoreBreakdown: ScoreBreakdown?,
    val blockedReason: String?,
    val status: JobStatus,
    val userNotes: String,
    val firstSeenAt: String,
    val lastSeenAt: String,
    val llmFit: LLMFitAnalysis? = null,
    val llmFitScore: Int? = null,
    val tailoredPackage: TailoredApplicationPayload? = null,
    val tailoredModelUsed: String? = null,
    val cvPdfPath: String? = null,
    val coverLetterPdfPath: String? = null
)

data class ScanProgress(
    val isRunning: Boolean = false,
    val stage: String = "idle",
    val message: String = "Ready",
    val queriesDone: Int = 0,
    val queriesTotal: Int = 0,
    val fetched: Int = 0,
    val newJobs: Int = 0,
    val detailsDone: Int = 0,
    val salariesDone: Int = 0,
    val errors: List<String> = emptyList()
)
