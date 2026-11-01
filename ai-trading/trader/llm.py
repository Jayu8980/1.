"""Claude access for the agents that reason over text (news sentiment and the final AI review).

The rest of the system is quantitative and works without it. Claude is used when ``llm.enabled``
is "on", or "auto" and credentials are present (ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN or an
``ant auth login`` profile).
"""
from __future__ import annotations

import json
import logging
import os
from pathlib import Path

log = logging.getLogger("trader.llm")

# Models that accept server-side refusal fallbacks (`fallbacks: "default"`).
_FALLBACK_MODELS = {"claude-opus-5-5", "claude-opus-5", "claude-fable-5-1", "claude-sonnet-5-5"}


def llm_available(setting: str) -> bool:
    if setting == "off":
        return False
    try:
        import anthropic  # noqa: F401
    except ImportError:
        if setting == "on":
            log.error("llm.enabled is 'on' but the anthropic package is missing: pip install anthropic")
        return False
    if setting == "on":
        return True
    env = ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_PROFILE")
    return any(os.environ.get(k) for k in env) or (Path.home() / ".config" / "anthropic").exists()


class ClaudeClient:
    def __init__(self, model: str = "claude-opus-5-5") -> None:
        import anthropic

        self._anthropic = anthropic
        self.client = anthropic.Anthropic()
        self.model = model

    def json_call(self, system: str, user: str, schema: dict, effort: str = "high",
                  max_tokens: int = 16000) -> dict | None:
        """One request whose answer must match ``schema``. Returns None when no answer is usable."""
        a = self._anthropic
        request = dict(
            model=self.model,
            max_tokens=max_tokens,
            system=system,
            messages=[{"role": "user", "content": user}],
            output_config={"effort": effort, "format": {"type": "json_schema", "schema": schema}},
        )
        try:
            if self.model in _FALLBACK_MODELS:
                # If a safety classifier declines, the API retries on a recommended fallback model.
                response = self.client.beta.messages.create(
                    **request, betas=["server-side-fallback-2026-07-01"], fallbacks="default")
            else:
                response = self.client.messages.create(**request)
        except a.AuthenticationError:
            log.error("Claude: authentication failed - check ANTHROPIC_API_KEY")
            return None
        except a.RateLimitError:
            log.warning("Claude: rate limited - skipping this call")
            return None
        except a.APIStatusError as exc:
            log.warning("Claude: API error %s: %s", exc.status_code, exc.message)
            return None
        except a.APIConnectionError:
            log.warning("Claude: network error")
            return None

        if response.stop_reason == "refusal":
            log.warning("Claude declined the request")
            return None
        if response.stop_reason == "max_tokens":
            log.warning("Claude's answer was cut off at max_tokens")
            return None
        text = next((b.text for b in response.content if b.type == "text"), None)
        if not text:
            return None
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            log.warning("Claude returned invalid JSON")
            return None


def to_jsonable(obj):
    """Convert numpy/pandas scalars and NaN so dossiers can be sent as JSON."""
    import math

    import numpy as np

    if isinstance(obj, dict):
        return {str(k): to_jsonable(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [to_jsonable(v) for v in obj]
    if isinstance(obj, np.generic):
        obj = obj.item()
    if isinstance(obj, float):
        return None if math.isnan(obj) or math.isinf(obj) else round(obj, 6)
    if isinstance(obj, (str, int, bool)) or obj is None:
        return obj
    return str(obj)
