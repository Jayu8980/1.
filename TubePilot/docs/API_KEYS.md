# API key setup

All keys are entered in **Settings → API keys** (or during onboarding). They are encrypted with AES-256-GCM using a
non-exportable key in the Android Keystore, excluded from backups, and sent only to their own provider over HTTPS.

| Key | Needed for | Where to get it |
|---|---|---|
| Anthropic API key | Claude models (default: `claude-opus-5-5` for every agent) | <https://platform.claude.com/> → API keys |
| OpenAI API key | Optional: GPT models, `gpt-image-1` images, OpenAI TTS | <https://platform.openai.com/api-keys> |
| Google Gemini API key | Optional: Gemini models, Imagen images | <https://aistudio.google.com/apikey> |
| ElevenLabs API key + voice ID | Optional: ElevenLabs multilingual voices | <https://elevenlabs.io/> → Profile → API keys; Voice Lab for the voice ID |

You need **at least one LLM key**. Everything else has an offline fallback:

- **Voice:** Android system text-to-speech (install Hindi voice data in Android Settings → Text-to-speech for Hindi).
- **Visuals:** on-device procedural backgrounds in your brand colours, or your own B-roll folder.
- **LLM fallback:** an optional on-device MediaPipe model (Settings → Models → "Choose MediaPipe model file"), for
  example a Gemma `.task` bundle downloaded from Kaggle or Hugging Face. It runs offline on the CPU/GPU and is used
  when the cloud is unreachable.

## Choosing models per agent

Settings → Models lets you pick a provider and model for each agent and **Test** the connection. The model list comes
from `llm/ModelCatalog.kt`; any model ID can be typed in. Prices are used for the budget cap; Anthropic prices are
the published list prices, others are estimates you can override in code (`priceOverrides`).

Claude requests use a cached system prompt (role prompt + memory), explicit effort (higher for scripts, medium
elsewhere) and server-side refusal fallback, which you can switch off in Settings.

## Budget

Set **Settings → Budget → Monthly cap**. When the month's recorded spend reaches it, every cloud call is refused,
agents pause, and the dashboard shows a banner. Raise the cap and tap **Resume agents**.
