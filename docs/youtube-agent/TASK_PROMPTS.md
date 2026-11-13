# VoxForge YouTube Agent: Task Prompts

These go in the **user message**, one per job, under the fixed [system prompt](SYSTEM_PROMPT.md). Fill `{{…}}` before sending. Each prompt says what to produce and what "done" means; the system prompt already covers style and rules.

---

## 1. Weekly idea generation

```
Today is {{date}}. Plan next week's videos for the channel.

Inputs:
- Last 28 days of analytics: {{analytics_json}}
- Content calendar (published and scheduled): {{calendar}}
- Open viewer requests and frequent comment questions: {{requests}}
- Features added to VoxForge since the last plan (from git log): {{changelog}}

Produce 8 video ideas, ranked. For each: working title, format, the viewer it serves,
the promise, why now (cite the input it comes from), effort (S/M/L) and the
VoxForge features it shows. Then recommend {{n_videos}} to make this week (at least one
Short) and say why. Don't repeat a topic published in the last 60 days unless the data
says it is worth a follow-up.
```

## 2. Full video package

```
Make a full video package for: "{{idea}}" (format: {{format}}, target length {{length}}).
Check every feature you mention with read_repo_file first. Use the package template.
Lyrics must be original. Save it with save_draft when done.
```

## 3. Song showcase: write the song

```
Write an original song to be sung in VoxForge for a showcase video.
Genre/mood: {{genre}}. Length: about {{seconds}} s. Singer: {{singer}}.
Give: tempo, key, time signature; structure (verse/chorus/etc.); lyrics split into
singable syllables one per note, with "-" for held vowels (VoxForge syntax); the lead
melody as note names with lengths in beats; suggested harmony (use the app's
Generate harmony interval names); expression settings per section.
Keep the melody inside the singer's comfortable range and avoid consonant clusters on
fast notes, since formant synthesis makes them hard to understand.
```

## 4. Metadata only (title, description, tags)

```
Write metadata for a finished video.
Transcript or summary: {{transcript}}
Chapters with timestamps: {{chapters}}
Give 3 title options (≤ 60 chars), a description (promise in the first 2 lines,
chapters, link to the app {{app_url}}, synthetic-vocal disclosure), up to 15 tags,
and a pinned comment that asks a specific question related to the video.
```

## 5. Thumbnail brief

```
Write 2 thumbnail concepts for "{{title}}". For each: the main image (what is on
screen in VoxForge or the visual metaphor), ≤ 4 words of overlay text, colour
palette, and why it would be clicked by {{audience}}. It must match what the video
actually shows.
```

## 6. Shorts from a long video

```
From this transcript with timestamps, pick up to 3 moments that work alone as a
vertical Short of 15–60 s: {{transcript}}
For each: start/end time, the hook line for the first 2 s, on-screen caption text,
and a title. Each must make sense without the long video.
```

## 7. Comment triage and replies

```
Here are new comments: {{comments_json}}
Sort each into: question / praise / feature request / bug report / spam / needs a person.
Draft replies for questions and praise (≤ 2 sentences, helpful, answer from the app's
real features). For feature requests, check docs/ACE_STUDIO_FEATURE_ANALYSIS.md and
say if it is already planned. Don't draft replies for bug reports or "needs a person";
summarise them instead. Submit replies with submit_for_review, never post directly.
```

## 8. Monthly performance review

```
Review the last {{days}} days: {{analytics_json}}
Report: the 3 best and 3 weakest videos by views and by average view percentage;
what the best ones have in common (topic, format, title pattern, length); where
viewers drop off; which traffic sources grew. Then give 5 concrete changes for next
month. Quote only numbers that are in the data.
```
