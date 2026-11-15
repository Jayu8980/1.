# Writing and importing skills

Import skills in **More → Skills store → ⤒**. Two formats are accepted (max 512 KB):

1. **ZIP** containing `skill.json` and the prompt file it names (default `prompt.md`), at any folder depth.
2. **Single JSON** file: the manifest plus a `"prompt"` string field.

## skill.json

```json
{
  "id": "community_post_writer",
  "name": "Community Post Writer",
  "version": "1.0.0",
  "description": "Writes a poll or question for the Community tab about a video.",
  "inputs": {
    "type": "object",
    "properties": { "topic": { "type": "string" }, "style": { "type": "string" } },
    "required": ["topic"]
  },
  "outputs": {
    "type": "object",
    "properties": { "post": { "type": "string" }, "pollOptions": { "type": "array", "items": { "type": "string" } } },
    "required": ["post"]
  },
  "permissions": ["LLM", "MEMORY_READ"],
  "agents": ["COMMUNITY_MANAGER", "ORCHESTRATOR"],
  "defaultEnabled": true,
  "executor": null,
  "promptFile": "prompt.md",
  "author": "you"
}
```

Rules enforced by the importer (`SkillManifestParser`):

- `id` matches `^[a-z][a-z0-9_]{2,47}$` and is not a built-in id.
- `inputs` and `outputs` are JSON Schemas with `"type": "object"` and `properties`. `inputs` is also what the model
  sees as the tool schema, and calls are validated against `required` and top-level types.
- `agents` lists at least one of: `ORCHESTRATOR, TREND_RESEARCHER, SCRIPTWRITER, SEO_SPECIALIST, THUMBNAIL_DESIGNER,
  VOICE_AUDIO, VIDEO_EDITOR, UPLOAD_SCHEDULER, COMMUNITY_MANAGER, ANALYTICS, COMPLIANCE, QA`.
- **Sandbox:** `executor` must be `null` or `"prompt"`, and `permissions` may only contain `LLM` and `MEMORY_READ`.
  Imported skills therefore cannot upload, post, moderate or read files.

## Prompt template

`{{name}}` placeholders are replaced before the call:

| Variable | Value |
|---|---|
| `{{input}}` | the whole input JSON (pretty-printed) |
| `{{input.field.sub}}` | one input value |
| `{{channel}}`, `{{style}}` | channel profile and style guide JSON |
| `{{lessons}}` | active lessons learned |
| `{{niche}}`, `{{audience}}`, `{{tone}}`, `{{banned_topics}}` | profile fields |

The runtime adds a system prompt with safety rules and your `outputs` schema, asks for JSON only, and retries once
with a repair prompt if the answer is not valid JSON. Every call appears in the agent's **Skill calls** tab and in
the audit log.

## Example prompt.md

```markdown
Write a YouTube Community tab post for a channel about {{niche}} (audience: {{audience}}, tone: {{tone}}).
Topic: {{input.topic}}. Style: {{input.style}}.
Make it a question or a 2-4 option poll that invites replies. Max 300 characters. No links.
```
