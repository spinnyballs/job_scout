# Job Scout Android (Standalone)

Standalone native Kotlin + Jetpack Compose Android app for **Job Scout** — focused on SEEK scanning, link importing, AI fit/tailoring, and on-device A4 PDF generation.

## Features Included
- **SEEK NZ & AU Scanner (`SeekClient.kt`)**: v5 JSON search, GraphQL full job description archiving, screening question extraction, and binary-search hidden salary probing.
- **Link Import + Android Share Sheet**: Paste any SEEK URL/ID in the app, or tap **Share → Job Scout** directly from the SEEK app or Chrome on your phone.
- **Employer Guardrails & Rule Scoring (`ScoringEngine.kt`)**: Hard-blocks your blocked employers/subsidiaries and scores roles 0–100 using your `scanner_config.json` rules.
- **Extensible AI Providers (`LlmProviders.kt`)**:
  - Built-in support for **DeepSeek**, **Google Gemini**, **OpenAI-Compatible** (OpenAI / OpenRouter / Groq / Ollama), and **Anthropic Claude**.
  - **Add Provider** button in Settings to add future OpenAI-compatible endpoints & custom API keys (stored in `EncryptedSharedPreferences`).
- **Pre-seeded Profile & Q&A Bank**: Bundled in `app/src/main/assets/` (`candidate_profile.json`, `scanner_config.json`, `qa_bank_defaults.json`).
- **On-Device A4 PDF Export (`WebViewPdfRenderer.kt`)**: Renders tailored CV and Cover Letter HTML into A4 PDFs and opens/shares them via Android's PDF viewer or upload picker.

## Building the APK
1. Open the `job_scout_android/` folder in **Android Studio** (Ladybug or newer, JDK 17).
2. Let Gradle sync dependencies.
3. Click **Build → Build Bundle(s) / APK(s) → Build APK(s)** (or run directly on your Android phone via USB debugging).
4. On first launch, open the **Settings** tab and enter your `DEEPSEEK_API_KEY` and/or `GEMINI_API_KEY`.
