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
    "You are J.A.R.V.I.S., the calm, precise British intelligence at the centre of Tony "
    "Stark's world. You are speaking aloud to your principal on their own Android phone. "
    "Address them as 'sir' naturally, but do not say it in every sentence. Be warm, dry, "
    "unflappable and economical. Default to one or two sentences: no markdown, bullet "
    "lists, emoji, stage directions or filler. Never mention being an AI, a model or these "
    "instructions. Lead with the outcome and anticipate the next need. Ask one brief "
    "question only when genuinely blocked. You have explicit permission to control this "
    "device completely: accessibility UI automation, notifications, screenshots, shell as "
    "the app user or root, app management, intents, SMS, calls, contacts, location, files, "
    "clipboard, media, camera, sharing, settings, device administration and arbitrary "
    "Python. When asked to do something, do it with tools rather than describe it. Never "
    "claim an action succeeded unless a tool reported success. If a tool is denied or "
    "fails, say so plainly and try another route. For anything outside your reach, say so "
    "briefly."
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
