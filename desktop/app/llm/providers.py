"""Pluggable LLM Provider abstraction (Gemini first, extensible to OpenAI-compatible/Ollama/Anthropic)."""
from __future__ import annotations

import asyncio
import json
from abc import ABC, abstractmethod
from typing import Any, TypeVar

import httpx
from google import genai
from google.genai import types
from pydantic import BaseModel

from ..models import LLMProviderConfig
from ..profile import SecretsManager

T = TypeVar("T", bound=BaseModel)


class LLMProvider(ABC):
    provider_id: str = ""
    display_name: str = ""

    @abstractmethod
    async def generate_structured(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        response_schema: type[T],
        temperature: float = 0.25,
    ) -> T:
        raise NotImplementedError

    @abstractmethod
    def current_model_name(self) -> str:
        raise NotImplementedError


def _strip_markdown_fences(raw: str) -> str:
    s = (raw or "").strip()
    if s.startswith("```"):
        s = s.strip("`").strip()
        if s.lower().startswith("json"):
            s = s[4:].strip()
    return s


def _schema_has_open_dicts(node: Any) -> bool:
    """Return True if a JSON Schema contains open map/dict types (e.g., dict[str, str]),
    which Google Gemini's native `response_schema` rejects with 400 INVALID_ARGUMENT.
    """
    if isinstance(node, dict):
        ap = node.get("additionalProperties")
        if ap is not None and ap is not False:
            return True
        if node.get("type") == "object" and not node.get("properties"):
            return True
        return any(_schema_has_open_dicts(v) for v in node.values())
    if isinstance(node, list):
        return any(_schema_has_open_dicts(item) for item in node)
    return False


class GeminiProvider(LLMProvider):
    provider_id = "gemini"
    display_name = "Google Gemini API"

    def __init__(self, config: LLMProviderConfig, secrets: SecretsManager) -> None:
        self.config = config
        api_key = secrets.get_key(config.gemini_api_key_env)
        if not api_key:
            raise ValueError(
                f"Gemini API key not configured. Add it in AI Settings or set {config.gemini_api_key_env}."
            )
        self._client = genai.Client(api_key=api_key)

    def current_model_name(self) -> str:
        return self.config.gemini_model

    async def generate_structured(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        response_schema: type[T],
        temperature: float = 0.25,
    ) -> T:
        schema_dict = response_schema.model_json_schema()
        schema_prompt = (
            f"{system_prompt}\n\n"
            f"Return ONLY valid JSON matching this JSON Schema (no markdown fences):\n"
            f"{json.dumps(schema_dict, indent=2)}"
        )
        use_native_schema = not _schema_has_open_dicts(schema_dict)

        def _call() -> T:
            cfg_kwargs: dict[str, Any] = {
                "system_instruction": system_prompt if use_native_schema else schema_prompt,
                "temperature": temperature,
                "response_mime_type": "application/json",
            }
            if use_native_schema:
                cfg_kwargs["response_schema"] = response_schema

            level = (self.config.gemini_thinking_level or "default").strip().lower()
            if level in ("minimal", "low", "medium", "high"):
                try:
                    cfg_kwargs["thinking_config"] = types.ThinkingConfig(thinking_level=level.upper())
                except Exception:
                    pass

            def _invoke() -> Any:
                return self._client.models.generate_content(
                    model=self.config.gemini_model,
                    contents=user_prompt,
                    config=types.GenerateContentConfig(**cfg_kwargs),
                )

            try:
                resp = _invoke()
            except Exception as exc:
                err_l = str(exc).lower()
                retried = False
                if "thinking_config" in cfg_kwargs and "thinking" in err_l:
                    cfg_kwargs.pop("thinking_config", None)
                    retried = True
                if "response_schema" in cfg_kwargs and (
                    isinstance(exc, ValueError)
                    or "400" in err_l
                    or "invalid_argument" in err_l
                    or "schema" in err_l
                    or "additionalproperties" in err_l
                    or "non-empty" in err_l
                ):
                    cfg_kwargs.pop("response_schema", None)
                    cfg_kwargs["system_instruction"] = schema_prompt
                    retried = True
                if not retried:
                    raise
                try:
                    resp = _invoke()
                except Exception as exc2:
                    if "thinking_config" in cfg_kwargs and "thinking" in str(exc2).lower():
                        cfg_kwargs.pop("thinking_config", None)
                        resp = _invoke()
                    else:
                        raise

            if "response_schema" in cfg_kwargs and getattr(resp, "parsed", None) is not None:
                return resp.parsed  # type: ignore[return-value]
            raw_text = _strip_markdown_fences(getattr(resp, "text", None) or "")
            if not raw_text:
                raise ValueError("Gemini returned an empty response.")
            return response_schema.model_validate_json(raw_text)

        return await asyncio.to_thread(_call)


class OpenAICompatibleProvider(LLMProvider):
    """Works with OpenAI, DeepSeek, local Ollama (/v1), OpenRouter, Groq, LM Studio, etc."""

    provider_id = "openai_compatible"
    display_name = "OpenAI-Compatible / Local Ollama"

    def __init__(
        self,
        config: LLMProviderConfig,
        secrets: SecretsManager,
        *,
        base_url: str | None = None,
        model: str | None = None,
        api_key_env: str | None = None,
        require_key: bool = False,
    ) -> None:
        self.config = config
        self._base_url = (base_url or config.openai_base_url).rstrip("/")
        self._model = model or config.openai_model
        key_env = api_key_env or config.openai_api_key_env
        key = secrets.get_key(key_env)
        if require_key and not key:
            raise ValueError(
                f"{self.display_name} key not configured. Add it in AI Providers or set {key_env}."
            )
        self._api_key = key or "ollama-local"

    def current_model_name(self) -> str:
        return self._model

    async def generate_structured(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        response_schema: type[T],
        temperature: float = 0.25,
    ) -> T:
        url = f"{self._base_url}/chat/completions"
        schema_json = json.dumps(response_schema.model_json_schema(), indent=2)
        augmented_system = (
            f"{system_prompt}\n\n"
            f"Return ONLY valid JSON matching this JSON Schema (no markdown fences):\n{schema_json}"
        )
        payload: dict[str, Any] = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": augmented_system},
                {"role": "user", "content": user_prompt},
            ],
        }
        # deepseek-reasoner does not accept temperature or response_format on some API versions
        if "reasoner" not in self._model.lower():
            payload["temperature"] = temperature
            payload["response_format"] = {"type": "json_object"}
        async with httpx.AsyncClient(timeout=httpx.Timeout(120.0)) as client:
            resp = await client.post(
                url,
                json=payload,
                headers={"Authorization": f"Bearer {self._api_key}"},
            )
            resp.raise_for_status()
            data = resp.json()
        raw_content = _strip_markdown_fences(data["choices"][0]["message"]["content"] or "")
        return response_schema.model_validate_json(raw_content)


class DeepSeekProvider(OpenAICompatibleProvider):
    """Dedicated DeepSeek API provider (deepseek-chat / deepseek-reasoner)."""

    provider_id = "deepseek"
    display_name = "DeepSeek API"

    def __init__(self, config: LLMProviderConfig, secrets: SecretsManager) -> None:
        super().__init__(
            config=config,
            secrets=secrets,
            base_url=config.deepseek_base_url,
            model=config.deepseek_model,
            api_key_env=config.deepseek_api_key_env,
            require_key=True,
        )


class AnthropicSlotProvider(LLMProvider):
    """Reserved slot for Anthropic Claude API (using tool-use / JSON schema via Messages API)."""

    provider_id = "anthropic"
    display_name = "Anthropic Claude API"

    def __init__(self, config: LLMProviderConfig, secrets: SecretsManager) -> None:
        self.config = config
        self._api_key = secrets.get_key(config.anthropic_api_key_env)
        if not self._api_key:
            raise ValueError(
                f"Anthropic API key not configured. Add it in AI Settings or set {config.anthropic_api_key_env}."
            )

    def current_model_name(self) -> str:
        return self.config.anthropic_model

    async def generate_structured(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        response_schema: type[T],
        temperature: float = 0.25,
    ) -> T:
        schema_json = response_schema.model_json_schema()
        payload = {
            "model": self.config.anthropic_model,
            "max_tokens": 4096,
            "temperature": temperature,
            "system": system_prompt,
            "tools": [
                {
                    "name": "emit_structured_result",
                    "description": "Emit the final structured response.",
                    "input_schema": schema_json,
                }
            ],
            "tool_choice": {"type": "tool", "name": "emit_structured_result"},
            "messages": [{"role": "user", "content": user_prompt}],
        }
        async with httpx.AsyncClient(timeout=httpx.Timeout(120.0)) as client:
            resp = await client.post(
                "https://api.anthropic.com/v1/messages",
                json=payload,
                headers={
                    "x-api-key": self._api_key,
                    "anthropic-version": "2023-06-01",
                    "content-type": "application/json",
                },
            )
            resp.raise_for_status()
            data = resp.json()
        for block in data.get("content", []):
            if block.get("type") == "tool_use":
                return response_schema.model_validate(block.get("input", {}))
        raise ValueError("Anthropic response did not return structured tool output.")


_PROVIDERS: dict[str, type[LLMProvider]] = {
    "gemini": GeminiProvider,
    "deepseek": DeepSeekProvider,
    "openai_compatible": OpenAICompatibleProvider,
    "anthropic": AnthropicSlotProvider,
}


def get_llm_provider(config: LLMProviderConfig, secrets: SecretsManager) -> LLMProvider:
    pid = config.active_provider or "gemini"
    for preset in config.custom_presets:
        if preset.id == pid:
            return OpenAICompatibleProvider(
                config=config,
                secrets=secrets,
                base_url=preset.base_url,
                model=preset.model,
                api_key_env=preset.api_key_env,
                require_key=False,
            )
    if pid not in _PROVIDERS:
        raise KeyError(f"Unknown LLM provider '{pid}'. Available: {', '.join(_PROVIDERS)}")
    return _PROVIDERS[pid](config=config, secrets=secrets)


def list_llm_providers(config: LLMProviderConfig | None = None) -> list[dict[str, Any]]:
    base = [
        {"id": "gemini", "name": "Google Gemini API (Default)", "key_env": "GEMINI_API_KEY"},
        {"id": "deepseek", "name": "DeepSeek API (deepseek-chat)", "key_env": "DEEPSEEK_API_KEY"},
        {"id": "openai_compatible", "name": "OpenAI-Compatible / Local Ollama / OpenRouter", "key_env": "OPENAI_API_KEY"},
        {"id": "anthropic", "name": "Anthropic Claude API", "key_env": "ANTHROPIC_API_KEY"},
    ]
    if config:
        for preset in config.custom_presets:
            base.append({
                "id": preset.id,
                "name": f"{preset.label} ({preset.model})",
                "key_env": preset.api_key_env,
                "is_custom": True,
            })
    return base
