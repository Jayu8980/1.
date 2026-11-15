# TubePilot

A team of 12 AI agents that runs a YouTube channel end to end from an Android phone: research, scripts, voice,
video, thumbnails, SEO, compliance, QA, upload and scheduling, comment replies and analytics. Every action is logged,
and every video can wait for your tap before it goes out.

- **Android 16 (API 36) target, minSdk 26, arm64-v8a**, Kotlin + Jetpack Compose (Material 3)
- MVVM + Clean Architecture · Hilt · Room · DataStore · WorkManager · Coroutines/Flow · Retrofit/OkHttp · Coil · Media3
- Official APIs only: YouTube Data API v3 and YouTube Analytics API v2

Docs: [Architecture](docs/ARCHITECTURE.md) · [Build & signing](docs/BUILD.md) ·
[Google Cloud setup](docs/GOOGLE_CLOUD_SETUP.md) · [API keys](docs/API_KEYS.md) · [Writing skills](docs/SKILLS.md)

## Features

**Agent team**
- Channel Manager plans the weekly calendar from your posting frequency, resolves conflicts (banned topics, spacing,
  caps, duplicates) and starts productions ahead of each slot.
- Trend Researcher scores ideas by demand, competition and niche fit using your analytics, YouTube search and the
  competitor channels you list.
- Scriptwriter writes hooks, scene-by-scene scripts and CTAs in English, Hindi or Hinglish, in your channel voice.
- SEO Specialist writes A/B title variants, description, tags, hashtags, chapters and a comment to pin.
- Thumbnail Designer produces 2-3 variants (generated background + bold headline, brand colours, under 2 MB).
- Voice & Audio: system TTS (offline) or OpenAI/ElevenLabs voices, background music from your royalty-free folder
  ducked under the voice, normalised to -14 LUFS.
- Video Editor: your own B-roll or generated images, Ken Burns motion, fade transitions, burned-in captions plus an
  SRT, 9:16 and 16:9 renders, and Shorts cut from long-form videos — all rendered on the phone with Media3.
- Upload & Scheduler: resumable uploads that survive reboots, scheduled publishing, playlists, captions, thumbnail,
  AI-disclosure flag, quota-aware queuing.
- Community Manager: sorts comments (question, praise, feedback, spam, toxic, sensitive), drafts replies, hides spam
  if allowed, and flags sensitive comments for you.
- Analytics: CTR, retention, views and subscribers per video; weekly report; lessons written to memory; automatic
  48-hour title A/B rotation.
- Compliance & Safety: copyright, Community Guidelines, repetitive or mass-produced content, misleading metadata and
  AI disclosure. It can block publishing.
- QA: resolution, duration, audio/video sync, loudness, subtitle timing, thumbnail and metadata limits.

**Skills store** — 14 built-in skills (`trend_scan`, `keyword_research`, `hook_generator`, `script_writer`,
`shorts_cutter`, `subtitle_generator`, `thumbnail_maker`, `title_ab_test`, `comment_triage`, `reply_drafter`,
`analytics_report`, `repurpose_content`, `content_calendar`, `policy_check`), enabled or disabled per agent, plus
sandboxed imports from `.zip`/JSON. Agents pick skills by LLM tool calling, and every call is logged.

**Memory** — channel profile, style guide, per-video performance and lessons learned, injected into every agent.

**LLM layer** — Claude (official Anthropic SDK, default `claude-opus-5-5`), OpenAI, Gemini, or an optional on-device
MediaPipe model. Per-agent model choice, streaming, token and cost tracking, and a monthly budget cap that auto-pauses
the agents.

**Safety defaults** — Approval mode on, replies as drafts, kill switch, full audit log, Keystore-encrypted secrets,
HTTPS only, original or transformed content only.

## Screens

Onboarding & channel connect · Channel strategy · Dashboard (pipeline, today's tasks, quota, spend, kill switch) ·
Agent team + log viewer · Skills store · Content calendar · Approval inbox (video preview, thumbnail pick, metadata
edit, approve/changes/reject) · Comments inbox · Analytics & weekly report · Settings (keys, models, budget,
mode, quota table, providers, notifications) · Project detail · Audit log.

## Quick start

1. Set up Google Cloud and the Android OAuth client — [GOOGLE_CLOUD_SETUP.md](docs/GOOGLE_CLOUD_SETUP.md).
2. Build and install — [BUILD.md](docs/BUILD.md): `./gradlew :app:assembleRelease`.
3. Open TubePilot → add an LLM key → **Connect YouTube** → fill in the channel strategy.
4. Dashboard → **Plan now**, or **New video**. Approve scripts and videos from the Inbox.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| "Developer error" / status 10 on Connect YouTube | The Android OAuth client's package name or SHA-1 does not match the installed APK. Register the SHA-1 of the key that signed this APK (debug and release differ). |
| "Access blocked: app has not completed verification" | Add your account as a **Test user** on the consent screen, or publish/verify the consent screen. |
| Connected, then later "Reconnect YouTube" | Test-mode consent expires after 7 days, or access was revoked. Reconnect in Settings. |
| Uploads end up **private** | Unverified Cloud projects are locked to private until the YouTube API compliance audit passes. See section 5 of the setup guide. |
| Thumbnail not set | The channel is not verified for custom thumbnails (youtube.com/verify). The upload still succeeds. |
| Project shows "quota exhausted" | A daily bucket ran out. Work resumes automatically after midnight Pacific. Check your limits in Settings → YouTube quota. |
| "Monthly LLM budget reached" | Raise the cap in Settings → Budget, then **Resume agents**. |
| All agents paused | The kill switch is on (Dashboard card). |
| Hindi voice missing | Install Hindi voice data in Android Settings → System → Languages → Text-to-speech, or pick OpenAI/ElevenLabs TTS. |
| Render stops when the app is in the background | Android may refuse to start background foreground services. Open the app; the stage resumes and finished renders are reused. Keep the phone charging for long renders. |
| A video is **Blocked** | Compliance or QA found a blocker. Open it from the Inbox to see why, then fix and **Retry**, rewrite, or override with a written justification (logged). |
| Comments are never answered automatically | That's the default: replies are drafts. Enable "Auto-post comment replies" in Settings. Sensitive comments are never auto-answered. |
| On-device model fails to load | Use a MediaPipe LLM Inference `.task` model compatible with `tasks-genai` 0.10.27; large models need free RAM. |
| Build fails with HTTP 429 from Maven Central | Already mitigated by the Google mirror in `settings.gradle.kts`. Retry the build. |

## Responsible use

TubePilot only automates your own channel through the official APIs, within YouTube's Terms of Service, API Services
policies and Community Guidelines. It never scrapes YouTube, never re-uploads other people's videos, and marks
realistic synthetic content with YouTube's disclosure. You are responsible for what is published on your channel.
