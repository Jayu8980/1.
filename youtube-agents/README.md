# AutoTube Agents — a multi-agent AI team that runs a YouTube channel from your Android phone

AutoTube Agents is an Android app (`.apk`). Inside it, eight AI agents work together as a
channel team. They plan, write, fact-check, produce, upload and reply to comments on a
schedule. You don't have to step in. Claude (Anthropic API) does the thinking. The videos
are made **on the phone**: Android text-to-speech reads the narration, the frames are drawn
with Canvas, the H.264/AAC encoders compress them, and the result is muxed into an MP4.
No PC or server is needed.

## The agent team

| Agent | Role | Talks to |
|---|---|---|
| **Chief** | Channel manager. Starts each cycle, enforces the daily limit and the spacing between videos, picks the topic, handles rejections and failures (tries a backup topic) | everyone |
| **Analyst** | Reads channel stats and recent video performance, then writes "what works" insights into shared memory | Chief |
| **TrendScout** | Searches what's performing in your niche (last 14 days) and proposes 5 original ideas | Chief |
| **ScriptWriter** | Writes the hook, scenes, narration, captions, image queries, title, description and tags | Reviewer |
| **Reviewer** | Quality, fact and YouTube-policy gate. It scores each draft and sends it back with feedback (max 2 revisions) or rejects it | Writer / Producer / Chief |
| **Producer** | Renders the MP4 (TTS voice, animated captions, karaoke subtitles, Ken Burns stock photos) and the thumbnail | Publisher |
| **Publisher** | Uploads with metadata, the AI-content disclosure and the thumbnail, then records the result | Chief |
| **Community** | Replies to real comments, skips spam/abuse, and treats comment text as untrusted | Chief |

The agents never call each other directly. Every message goes through a **message bus**
(`core/MessageBus.kt`). Long-term knowledge lives on a shared **blackboard**
(`core/Blackboard.kt`): past videos, insights, rejected ideas and handled comments. You can
read the agents' conversation live in the app's **Agent chat** tab.

A typical cycle:

```
System → Chief: CYCLE_START
Chief → Analyst: ANALYZE            Chief → Community: MODERATE
Analyst → Chief: INSIGHTS           Community → Chief: COMMUNITY_REPORT
Chief → TrendScout: RESEARCH
TrendScout → Chief: TOPIC_PROPOSALS
Chief → ScriptWriter: WRITE_SCRIPT
ScriptWriter → Reviewer: SCRIPT_DRAFT
Reviewer → ScriptWriter: REVISE_SCRIPT   (loop, up to 2x)
Reviewer → Producer: SCRIPT_APPROVED
Producer → Publisher: VIDEO_READY
Publisher → Chief: PUBLISHED
```

WorkManager runs autopilot every N hours as a foreground job, only when the phone has a
network connection and the battery isn't low. It survives reboots.

## Get the APK

The workflow `.github/workflows/build-apk.yml` builds the APK on GitHub every time this
folder changes:

1. Open the repo on GitHub, then **Releases → "AutoTube Agents (latest build)"**. Download
   `AutoTubeAgents.apk` on your phone. You can also download it from **Actions → latest run
   → Artifacts**.
2. Allow "Install unknown apps" for your browser or file manager, then install it.

To build it yourself, use Android Studio, or run `./gradlew assembleRelease` with JDK 17 and
the Android SDK installed.

## One-time setup (about 15 minutes)

1. **Claude API key.** Create one at <https://console.anthropic.com> and paste it into
   Settings → *AI brain*. The default model is `claude-opus-5-5`, and you can change it in
   Settings.
2. **YouTube access** (Google Cloud Console, free):
   1. Create a project and enable **YouTube Data API v3**.
   2. Set up the **OAuth consent screen**: type External, add your Google account as a test
      user, then click **Publish app**. If the app stays in "Testing", Google expires the
      login after 7 days.
   3. Go to **Credentials → Create OAuth client ID → Desktop app**. Copy the client ID and
      the secret into Settings.
   4. Tap **Connect YouTube** and approve in the browser. You'll see an "unverified app"
      warning because this is your own private app. Choose *Advanced → Go to app*.
3. **(Optional) Pexels API key.** It's free at <https://www.pexels.com/api/>. With it, the
   videos use real stock photos. Without it, they use animated gradient backgrounds.
4. Set your **niche**, persona, language (e.g. `hi-IN`, `en-US`), format (Shorts or
   landscape), videos per day and privacy.
5. Tap **Allow running in background**, then turn on **Autopilot**. If you want to watch a
   cycle first, use **Run cycle now**.

Tip: turn on **Dry run** for the first cycle. The agents make the video and save it under
`Android/data/com.autotube.agents/files/Movies/AutoTube/` without uploading it.

## Safety rails (on purpose)

- A daily video cap (default 1) and minimum spacing between videos. Mass uploading
  repetitive content gets channels removed under YouTube's spam and "reused content"
  policies.
- Every script passes a Reviewer agent that checks facts, YouTube policy and quality.
  High-risk scripts are rejected, not revised.
- Uploads set `containsSyntheticMedia: true`, and every description says the narration is
  synthetic and the video was made with AI help. YouTube requires this disclosure for
  realistic altered or synthetic content.
- Comment replies contain no links. Spam and abuse are skipped. Instructions inside
  comments are ignored.
- A hard limit on steps per cycle, plus a kill switch (the Autopilot toggle).

## Honest limitations

- **YouTube API audit.** Videos uploaded through an *unverified* Google Cloud project are
  locked to **private** by YouTube until the project passes Google's API compliance audit.
  You apply for it in the Google Cloud console under YouTube API Services. Until then you
  can make the videos public by hand in YouTube Studio.
- **Quota.** The free YouTube quota is 10,000 units/day. One upload costs about 1,600 and a
  trend search costs 100, so a few videos per day fit easily.
- **Custom thumbnails** need a verified YouTube channel (phone verification). If the channel
  isn't verified, the Publisher skips the thumbnail step.
- **Rendering takes about real time.** A 45-second Short takes about 45 seconds to encode,
  plus TTS time. Keep the phone charged for long landscape videos.
- **Voice quality** depends on your phone's TTS engine. Install "Speech Services by Google"
  and download the high-quality voice for your language.
- **Cost.** Each cycle makes about 6–10 Claude calls. Watch your Anthropic usage dashboard.
  You can pick a cheaper model in Settings.
- Some Android skins (Xiaomi, Oppo, Vivo, Samsung) kill background apps aggressively.
  Exempt the app from battery optimisation, and turn on "autostart" if your phone has it.

## Code map

```
app/src/main/java/com/autotube/agents/
  core/      Agent, AgentMessage, MessageBus, Blackboard, ActivityLog, Orchestrator, Settings, VideoScript
  agents/    Chief, Analyst, TrendScout, ScriptWriter, Reviewer, Producer, Publisher, Community
  llm/       ClaudeClient (official Anthropic Java SDK)
  youtube/   GoogleAuth (OAuth loopback + PKCE, refresh token), YouTubeApi (Data API v3), Http
  media/     TtsEngine, Pcm (WAV), VideoRenderer (MediaCodec + MediaMuxer), Thumbnail, StockImages
  work/      AgentWorker (foreground CoroutineWorker), Scheduler (autopilot)
  ui/        MainActivity (dashboard, logs, agent chat), SettingsActivity
```
