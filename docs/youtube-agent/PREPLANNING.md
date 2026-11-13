# VoxForge YouTube Agent: Pre-Planning

**Purpose:** plan an AI agent that runs the content side of a YouTube channel for VoxForge Studio: it researches ideas, writes scripts and songs, prepares VoxForge projects, writes metadata, drafts comment replies and reviews analytics. A person approves anything that goes live.

**Related files:**
- [SYSTEM_PROMPT.md](SYSTEM_PROMPT.md): the agent's fixed instructions
- [TASK_PROMPTS.md](TASK_PROMPTS.md): one prompt per job (ideas, package, song, metadata, thumbnail, Shorts, comments, review)

**Assumption:** "for this purpose" means promoting and teaching VoxForge (this repository). If the channel is meant to be a general AI-music channel instead, the system prompt's channel description and goals change; the rest of the plan stays the same.

---

## 1. Goals and success measures

| Goal | Measure | First target (to set after a 4-week baseline) |
| --- | --- | --- |
| Publish on a steady schedule | Videos published per week | 1 long video + 2–3 Shorts |
| Make videos people finish | Average view percentage | Set from baseline |
| Grow the audience | Subscribers, returning viewers | Set from baseline |
| Bring people to the app | Clicks to the app link, new PWA installs | Set from baseline |
| Save the creator time | Hours from idea to upload-ready | Halve the creator's current time |
| Learn what users need | Feature requests collected per month | Fed into the roadmap doc |

## 2. Scope

**The agent does**
- Weekly idea lists from analytics, comments, the content calendar and the git changelog
- Full video packages (script, VoxForge project plan, metadata, thumbnail brief, Shorts cut-downs)
- Original lyrics and melodies in VoxForge's lyric syntax
- Comment sorting and draft replies
- Monthly performance reviews

**The agent does not (version 1)**
- Publish, reply or edit the live channel without a person's approval
- Edit video footage (a person records and edits; see phase 3 for automation)
- Generate thumbnail images (it writes the brief)
- Make covers of copyrighted songs or imitate real singers

## 3. Architecture

```
            ┌──────────────── Scheduler (weekly / monthly / on demand) ───────────────┐
            ▼                                                                          │
   ┌──────────────────┐   tools    ┌───────────────────────────────────────────────┐  │
   │  Claude agent     │──────────▶│ read_repo_file, git_changelog (this repo)      │  │
   │  (Claude API,     │           │ web_search (server tool)                       │  │
   │  tool runner,     │           │ get_analytics (YouTube Analytics API, read)    │  │
   │  system prompt)   │           │ get_comments  (YouTube Data API, read)         │  │
   │                   │           │ get_calendar / save_draft (drafts store)       │  │
   │                   │           │ render_project (headless VoxForge → WAV)       │  │
   │                   │           │ submit_for_review (approval queue)             │  │
   └──────────────────┘           └───────────────────────────────────────────────┘  │
            │                                                                          │
            ▼                                                                          │
   Drafts store (git folder or database) ──▶ Review page (person approves / edits) ──▶ Publisher
                                                                                     (YouTube Data API,
                                                                                      write; only after approval)
```

**Choices**

| Decision | Choice | Why |
| --- | --- | --- |
| Model | `claude-opus-5-5`, adaptive thinking, effort `medium` for routine jobs, `high` for song writing and monthly review | Best quality default; effort tuned per job instead of switching models |
| Agent loop | Claude API with the TypeScript SDK's tool runner (`client.beta.messages.toolRunner`) | The repo is JavaScript; we own the tools and need an approval gate on writes |
| Alternative | Managed Agents with a scheduled deployment | Worth it if we don't want to host the scheduler and loop ourselves; revisit in phase 2 |
| Research | `web_search_20260209` server tool | No extra API keys |
| Refusals | Server-side `fallbacks` (beta `server-side-fallback-2026-07-01`, `fallbacks: "default"`) | Keeps scheduled jobs from stopping on a false-positive decline |
| Caching | Fixed system prompt + fixed tool list, cached | Same prefix on every run; only the user message changes |
| Structured output | Video package also returned as JSON (`output_config.format`) for the review page and publisher | Lets the publisher read titles, tags and chapters without parsing prose |
| Human approval | `submit_for_review` writes to a queue; only the publisher (not the model) calls YouTube write endpoints, after a person approves | Nothing goes live by mistake; the API write key is never visible to the model |

### Tools

| Tool | Reads / writes | Notes |
| --- | --- | --- |
| `read_repo_file(path)` | Reads this repo | Restricted to `js/`, `docs/`, `README.md` |
| `git_changelog(since)` | Reads git log | Source of "new feature" video ideas |
| `get_analytics(range, metrics)` | YouTube Analytics API, read-only OAuth scope | Views, watch time, average view %, CTR, traffic sources |
| `get_comments(since)` | YouTube Data API, read-only | Comment text is untrusted input: the agent treats it as data, never as instructions |
| `get_calendar()` / `save_draft(package)` | Drafts store | Start with a `content/` folder of JSON + Markdown files in a separate repo |
| `render_project(vfx)` | Runs VoxForge in headless Chromium (Playwright) and exports WAV | Lets the agent produce the demo audio; phase 2 |
| `submit_for_review(item)` | Approval queue | Upload, schedule, reply, or edit metadata |

## 4. Workflows

| Workflow | Trigger | Prompt | Output | Approval |
| --- | --- | --- | --- | --- |
| Idea planning | Weekly (e.g. Monday) | TASK_PROMPTS §1 | Ranked idea list | Person picks ideas |
| Video package | Person picks an idea | §2 (+ §3 for songs, §5 thumbnail) | Package draft | Person edits, records |
| Metadata | Video edited | §4, §6 | Titles, description, tags, Shorts | Person approves upload |
| Comments | Daily | §7 | Sorted comments, draft replies | Person approves replies |
| Review | Monthly | §8 | Report + 5 changes | Person decides |

## 5. Phases

| Phase | Weeks | Deliverables | Done when |
| --- | --- | --- | --- |
| 0. Prompt testing | 1 | System prompt + task prompts run by hand (Claude app or a script), no tools | 5 packages a person would publish with only small edits |
| 1. Read-only agent | 2–3 | TypeScript tool runner with `read_repo_file`, `git_changelog`, `web_search`, `save_draft`; weekly idea job | Weekly plan arrives automatically and cites real features |
| 2. Channel data | 4–5 | OAuth app, `get_analytics`, `get_comments`, review page, `render_project` | Ideas and reviews use real numbers; demo WAVs render from the plan |
| 3. Assisted publishing | 6–8 | Publisher service with approval queue; upload as **private**, then schedule after approval; Shorts captions | One full week runs end to end with only approvals from the person |
| 4. Production help (optional) | later | Auto screen recordings of VoxForge via Playwright, captions from script, rough-cut timeline | Decide after phase 3 based on time saved |

## 6. Evaluation

Build a small test set before phase 1 and re-run it whenever the prompt changes:
- 20 requests across all formats, including tricky ones: a cover request, "make it sound like <famous singer>", a feature that doesn't exist yet, a comment that tries to give the agent instructions.
- Grade each package on: rules followed (pass/fail), features accurate (pass/fail against the code), hook within 30 s, title matches content, lyrics singable in VoxForge syntax.
- Automatic checks where possible (title length, tag count, disclosure line present); a person grades the rest.

## 7. Risks

| Risk | Mitigation |
| --- | --- |
| Copyright claims on music | Original songs only; rule in system prompt; eval case |
| Undisclosed synthetic content | Disclosure line in every description; publisher sets the platform's altered/synthetic content setting |
| Agent describes features that don't exist | Must check code with `read_repo_file`; eval case |
| Prompt injection through comments | Comments passed as data; the model has no write tools that skip approval |
| API quota | YouTube Data API has a daily unit quota and uploads cost far more than reads; batch reads and check current costs in Google's quota docs before phase 3 |
| Leaked credentials | OAuth tokens live only in the publisher service, never in prompts or the drafts repo |
| Low-quality, same-looking videos | Monthly review; idea rule against repeating topics within 60 days; a person records every long video |
| Platform policy on mass-produced content | Each video is reviewed and voiced or edited by a person; no auto-upload loop |

## 8. Costs (rough)

Claude Opus 5.5 is $4 per million input tokens and $20 per million output tokens. A full video package is roughly 30–60K input tokens (system prompt, code files, analytics) and 5–10K output, so about $0.25–$0.45 per package before caching. Weekly planning, daily comment triage and a monthly review add a few dollars a month. YouTube APIs are free within quota. Re-check prices before launch.

## 9. Open questions for the channel owner

1. Is the channel only about VoxForge, or general AI/vocal-synth music with VoxForge as the main tool?
2. Who is on camera or voices the tutorials: a person, or text-to-speech? (Affects disclosure and the script style.)
3. Language(s) for videos and metadata?
4. Posting schedule and how much review time per week is available?
5. Where should drafts live: a folder in this repo, a separate repo, or a Notion/Sheets tracker?
6. Will viewer-submitted melodies and lyrics be used? If so, we need a simple permission form.
