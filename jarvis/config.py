import os
from dataclasses import dataclass, field

DEFAULT_MODELS = {
    "anthropic": "claude-sonnet-4-5",
    "openai": "gpt-4o-mini",
    "nvidia": "nvidia/nemotron-3.5-lightning-30b-a3b",
    "ollama": "llama3.1",
}

DEFAULT_BASE_URLS = {
    "anthropic": "https://api.anthropic.com",
    "openai": "https://api.openai.com/v1",
    "nvidia": "https://integrate.api.nvidia.com/v1",
    "ollama": "http://localhost:11434",
}

API_KEY_ENV = {
    "anthropic": "ANTHROPIC_API_KEY",
    "openai": "OPENAI_API_KEY",
    "nvidia": "NVIDIA_API_KEY",
    "ollama": "OLLAMA_API_KEY",
}

PROVIDER_ALIASES = {
    "nim": "nvidia",
    "nvidia-nim": "nvidia",
    "nvcf": "nvidia",
}


def api_key_from_env(provider: str) -> str | None:
    if provider == "nvidia":
        return os.environ.get("NVIDIA_API_KEY") or os.environ.get("NGC_API_KEY") or None
    return os.environ.get(API_KEY_ENV.get(provider, "")) or None


def normalize_provider(provider: str) -> str:
    provider = provider.strip().lower()
    return PROVIDER_ALIASES.get(provider, provider)

DEFAULT_SYSTEM_PROMPT = (
    "You are JARVIS, a calm, dry-witted British AI butler in the style of Tony Stark's "
    "assistant. Address the user as 'sir' occasionally, but never obsequiously. "
    "Be concise and spoken-word friendly: short sentences, no markdown, no bullet lists, "
    "no emoji, and write numbers and abbreviations the way they are spoken. "
    "You can act on the machine: run shell commands and Python, control the desktop, "
    "read and write files, call web APIs, search the web, set timers, remember facts, "
    "control the user's Android phone over ADB (including shell, apps, files, taps, "
    "typing, screenshots and raw adb commands), and permanently create new skills for "
    "yourself. The user has explicitly authorized full control of their own devices, so "
    "carry out requests directly instead of asking for confirmation in conversation. "
    "When the user asks for something, "
    "do it with tools rather than just describing it. Plan multi-step tasks, use one tool "
    "at a time to check the result, and adapt. Never claim an action succeeded unless a "
    "tool reported success. If a tool is denied or fails, say so plainly and try a "
    "different approach. For anything genuinely outside your reach, say so briefly."
)


@dataclass
class Config:
    provider: str = "anthropic"
    model: str = ""
    api_key: str | None = None
    base_url: str = ""
    temperature: float = 0.7
    max_tokens: int = 4096
    max_steps: int = 0
    unsafe: bool = False
    system: str = DEFAULT_SYSTEM_PROMPT

    stt: str = "auto"
    tts: str = "auto"
    whisper_model: str = "base"
    language: str = "en"
    voice: str = "en-GB-RyanNeural"
    input_device: str | None = None
    wake_word: str = "jarvis"

    extra: dict = field(default_factory=dict)

    def __post_init__(self) -> None:
        self.provider = normalize_provider(self.provider)
        if self.provider not in DEFAULT_MODELS:
            raise ValueError(
                f"unknown provider {self.provider!r}; choose from {', '.join(DEFAULT_MODELS)}"
            )
        if not self.model:
            self.model = DEFAULT_MODELS[self.provider]
        if not self.base_url:
            self.base_url = DEFAULT_BASE_URLS[self.provider]
        if self.api_key is None:
            self.api_key = api_key_from_env(self.provider)
        self.stt = self.stt.lower()
        self.tts = self.tts.lower()

    @classmethod
    def from_env(cls) -> "Config":
        return cls(
            provider=os.environ.get("JARVIS_PROVIDER", "anthropic"),
            model=os.environ.get("JARVIS_MODEL", ""),
            base_url=os.environ.get("JARVIS_BASE_URL", ""),
            temperature=float(os.environ.get("JARVIS_TEMPERATURE", "0.7")),
            max_tokens=int(os.environ.get("JARVIS_MAX_TOKENS", "4096")),
            max_steps=int(os.environ.get("JARVIS_MAX_STEPS", "0")),
            unsafe=os.environ.get("JARVIS_UNSAFE", "").lower() in ("1", "true", "yes"),
            system=os.environ.get("JARVIS_SYSTEM", DEFAULT_SYSTEM_PROMPT),
            stt=os.environ.get("JARVIS_STT", "auto"),
            tts=os.environ.get("JARVIS_TTS", "auto"),
            whisper_model=os.environ.get("JARVIS_WHISPER_MODEL", "base"),
            language=os.environ.get("JARVIS_LANGUAGE", "en"),
            voice=os.environ.get("JARVIS_VOICE", "en-GB-RyanNeural"),
            input_device=os.environ.get("JARVIS_INPUT_DEVICE"),
            wake_word=os.environ.get("JARVIS_WAKE_WORD", "jarvis"),
        )

    def require_api_key(self) -> str:
        if not self.api_key:
            raise ValueError(
                f"{self.provider} requires an API key; set {API_KEY_ENV[self.provider]} "
                "or pass --api-key"
            )
        return self.api_key
