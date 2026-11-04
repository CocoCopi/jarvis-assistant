from typing import Any

from ..config import Config
from .base import Completion, Provider, ProviderError, ToolCall, get_json

__all__ = [
    "Completion",
    "Provider",
    "ProviderError",
    "ToolCall",
    "build_provider",
    "list_models",
]


def build_provider(config: Config) -> Provider:
    if config.provider == "anthropic":
        from .anthropic import AnthropicProvider

        return AnthropicProvider(
            model=config.model,
            api_key=config.require_api_key(),
            base_url=config.base_url,
            temperature=config.temperature,
            max_tokens=config.max_tokens,
        )
    if config.provider == "nvidia":
        from .nvidia import NvidiaProvider

        if not config.api_key and "integrate.api.nvidia.com" in config.base_url:
            raise ValueError(
                "NVIDIA NIM needs an API key. Set NVIDIA_API_KEY (or NGC_API_KEY) "
                "from https://build.nvidia.com, or point --base-url at a self-hosted "
                "NIM such as http://localhost:8000/v1"
            )
        return NvidiaProvider(
            model=config.model,
            api_key=config.api_key,
            base_url=config.base_url,
            temperature=config.temperature,
            max_tokens=config.max_tokens,
        )
    if config.provider == "openai":
        from .openai import OpenAIProvider

        return OpenAIProvider(
            model=config.model,
            api_key=config.api_key,
            base_url=config.base_url,
            temperature=config.temperature,
            max_tokens=config.max_tokens,
        )
    if config.provider == "ollama":
        from .ollama import OllamaProvider

        return OllamaProvider(
            model=config.model,
            api_key=config.api_key,
            base_url=config.base_url,
            temperature=config.temperature,
            max_tokens=config.max_tokens,
        )
    raise ValueError(f"unknown provider {config.provider!r}")


def list_models(config: Config) -> list[str]:
    base = config.base_url.rstrip("/")
    if config.provider == "ollama":
        data = get_json(f"{base}/api/tags")
        return [
            item.get("name", "")
            for item in data.get("models", [])
            if item.get("name")
        ]

    headers: dict[str, str] = {}
    if config.provider == "anthropic":
        if not config.api_key:
            raise ValueError("Anthropic model listing requires ANTHROPIC_API_KEY")
        headers = {
            "x-api-key": config.api_key,
            "anthropic-version": "2023-06-01",
        }
        url = f"{base}/v1/models"
    else:
        if config.api_key:
            headers["authorization"] = f"Bearer {config.api_key}"
        url = f"{base}/models"

    data: dict[str, Any] = get_json(url, headers=headers)
    items = data.get("data") or data.get("models") or []
    models: list[str] = []
    for item in items:
        if isinstance(item, str):
            models.append(item)
        elif isinstance(item, dict):
            name = item.get("id") or item.get("name")
            if name:
                models.append(str(name))
    return models
