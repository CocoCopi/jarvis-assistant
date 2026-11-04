import argparse
import json
import sys
from typing import Any

from . import __version__
from .agent import Agent
from .config import (
    DEFAULT_BASE_URLS,
    DEFAULT_MODELS,
    Config,
    api_key_from_env,
    normalize_provider,
)
from .providers import ProviderError, build_provider, list_models
from .policy import needs_command_approval
from .tools import TimerManager, build_tools

BANNER = r"""
   ____   _    ____  __     __ ___  ____
  |  _ \ / \  |  _ \ \ \   / /|_ _|/ ___|
  | |_) / _ \ | |_) | \ \ / /  | | \___ \
  |  __/ ___ \|  _ <   \ V /   | |  ___) |
  |_| /_/   \_\_| \_\   \_/   |___||____/
"""


def _apply_overrides(config: Config, args: argparse.Namespace) -> Config:
    if args.provider:
        config.provider = normalize_provider(args.provider)
        if args.model is None:
            config.model = DEFAULT_MODELS[config.provider]
        if args.base_url is None:
            config.base_url = DEFAULT_BASE_URLS[config.provider]
        if args.api_key is None:
            config.api_key = api_key_from_env(config.provider)

    overrides = {
        "model": args.model,
        "base_url": args.base_url,
        "api_key": args.api_key,
        "system": args.system,
        "temperature": args.temperature,
        "max_tokens": args.max_tokens,
        "max_steps": args.max_steps,
        "stt": args.stt,
        "tts": args.tts,
        "whisper_model": args.whisper_model,
        "language": args.language,
        "voice": args.voice_name,
        "input_device": args.input_device,
        "wake_word": args.wake_word,
    }
    for key, value in overrides.items():
        if value is not None:
            setattr(config, key, value)
    if args.unsafe:
        config.unsafe = True
    return config


def _make_tools(config: Config, speaker=None, timers: TimerManager | None = None):
    if config.extra.get("no_tools"):
        return []
    return build_tools(speaker=speaker, timers=timers)


def _print_tool(name: str, arguments: dict[str, Any]) -> None:
    try:
        rendered = json.dumps(arguments, ensure_ascii=False)
    except Exception:
        rendered = str(arguments)
    if len(rendered) > 500:
        rendered = rendered[:500] + "..."
    print(f"  [tool] {name} {rendered}", flush=True)


def _approve_text(name: str, arguments: dict[str, Any]) -> bool:
    if name == "run_command" and not needs_command_approval(
        str(arguments.get("command", ""))
    ):
        return True
    detail = (
        arguments.get("command")
        or arguments.get("args")
        or arguments.get("code")
        or arguments.get("target")
        or arguments.get("url")
        or arguments.get("path")
        or arguments.get("name")
        or ""
    )
    detail = " ".join(str(detail).split())
    if len(detail) > 300:
        detail = detail[:300] + "..."
    prompt = f"Allow '{name}'"
    if detail:
        prompt += f" ({detail})"
    prompt += "? [y/N] "
    try:
        answer = input(prompt).strip().lower()
    except EOFError:
        return False
    return answer in ("y", "yes")


def _build_agent(config: Config, tools, approver=None) -> Agent:
    provider = build_provider(config)
    return Agent(
        provider=provider,
        tools=tools,
        system=config.system,
        on_tool=_print_tool,
        approver=approver,
        max_steps=config.max_steps,
    )


def _explain_provider_error(config: Config, exc: Exception) -> str:
    message = f"Error: {exc}"
    text = str(exc)
    if config.provider == "nvidia" and any(
        marker in text for marker in ("HTTP 401", "HTTP 403", "HTTP 404", "HTTP 410")
    ):
        message += (
            "\nHint: model access varies by NVIDIA account. Run "
            "`python3 -m jarvis --provider nvidia models` to list usable model "
            "ids, and check that NVIDIA_API_KEY (or NGC_API_KEY) is valid."
        )
    return message


def _run_text(config: Config, args: argparse.Namespace) -> int:
    timers = TimerManager()
    tools = _make_tools(
        config,
        speaker=lambda text: print(f"\nJarvis: {text}\n", flush=True),
        timers=timers,
    )
    agent = _build_agent(
        config,
        tools,
        approver=None if config.unsafe else _approve_text,
    )
    if config.unsafe:
        print(
            "WARNING: unsafe mode is on. Tools run without confirmation, "
            "including phone root shell, installs, shell, Python and power.",
            file=sys.stderr,
        )

    prompt = " ".join(args.prompt).strip()
    if prompt:
        try:
            response = agent.run(prompt)
        except ProviderError as exc:
            print(_explain_provider_error(config, exc), file=sys.stderr)
            return 1
        if response:
            print(f"Jarvis: {response}")
        timers.cancel_all()
        return 0

    print(BANNER)
    print(f"Jarvis {__version__} | {config.provider}:{config.model}")
    print("Type /help for commands, /exit to quit.\n")

    while True:
        try:
            user = input("You: ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not user:
            continue
        if user.startswith("/"):
            command = user.lower()
            if command in ("/exit", "/quit"):
                break
            if command == "/help":
                print(
                    "Commands:\n"
                    "  /help    show this help\n"
                    "  /tools   list available tools\n"
                    "  /clear   clear conversation history\n"
                    "  /version show version\n"
                    "  /exit    quit\n"
                    "Anything else is sent to Jarvis."
                )
                continue
            if command == "/tools":
                if not tools:
                    print("Tools are disabled.")
                for tool in tools:
                    flag = " (needs approval)" if tool.requires_approval else ""
                    print(f"  {tool.name}{flag}: {tool.description}")
                continue
            if command == "/clear":
                agent.reset()
                print("Conversation cleared.")
                continue
            if command == "/version":
                print(f"Jarvis {__version__}")
                continue
            print("Unknown command. Type /help.")
            continue

        try:
            response = agent.run(user)
        except ProviderError as exc:
            print(_explain_provider_error(config, exc), file=sys.stderr)
            continue
        except KeyboardInterrupt:
            print("\n[interrupted]")
            continue
        if response:
            print(f"\nJarvis: {response}\n")

    timers.cancel_all()
    print("Goodbye, sir.")
    return 0


def _run_voice(config: Config, args: argparse.Namespace) -> int:
    try:
        from .voice import Microphone, Speaker, Transcriber, VoiceAssistant
    except Exception as exc:
        print(f"Could not load the voice layer: {exc}", file=sys.stderr)
        return 1

    try:
        speaker = Speaker(config.tts, config.voice)
    except Exception as exc:
        print(f"Could not initialise speech output: {exc}", file=sys.stderr)
        return 1

    stt_key = config.api_key if config.provider == "openai" else None
    stt_base = config.base_url if config.provider == "openai" else "https://api.openai.com/v1"
    try:
        transcriber = Transcriber(
            backend=config.stt,
            model_size=config.whisper_model,
            language=config.language,
            api_key=stt_key,
            base_url=stt_base,
        )
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except Exception as exc:
        print(f"Could not initialise speech recognition: {exc}", file=sys.stderr)
        return 1

    timers = TimerManager(speaker.say)
    tools = _make_tools(config, speaker=speaker.say, timers=timers)
    mic = Microphone(config.input_device)
    agent = _build_agent(config, tools, approver=None)
    wake_word = "" if args.no_wake else config.wake_word
    assistant = VoiceAssistant(
        agent=agent,
        transcriber=transcriber,
        speaker=speaker,
        mic=mic,
        wake_word=wake_word,
    )
    agent.approver = None if config.unsafe else assistant.confirm
    if config.unsafe:
        print(
            "WARNING: unsafe mode is on. Spoken confirmations are disabled.",
            file=sys.stderr,
        )

    print(BANNER)
    print(f"Jarvis {__version__} | voice mode | {config.provider}:{config.model}")
    if wake_word:
        print(f"Say '{wake_word}' to wake me. Ctrl-C to stop.\n")
    else:
        print("Listening continuously. Ctrl-C to stop.\n")

    try:
        assistant.run()
    except Exception as exc:
        print(f"Voice mode failed: {exc}", file=sys.stderr)
        return 1
    return 0


def _run_models(config: Config) -> int:
    print(f"Models for {config.provider} at {config.base_url}:")
    try:
        models = list_models(config)
    except ProviderError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    except ValueError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 2
    if not models:
        print("  (no models returned)")
        return 1
    for model in models:
        print(f"  {model}")
    print("\nUse one with --model <id> or JARVIS_MODEL=<id>.")
    return 0


def _run_doctor(config: Config) -> int:
    import importlib.util

    print(f"Jarvis {__version__}")
    print(f"Python: {sys.version.split()[0]} ({sys.platform})")
    print(f"Provider: {config.provider}")
    print(f"Model: {config.model}")
    print(f"Base URL: {config.base_url}")
    print(f"API key: {'set' if config.api_key else 'not set'}")
    print(f"Tools: {0 if config.extra.get('no_tools') else len(build_tools())}")
    steps = "unlimited" if not config.max_steps else str(config.max_steps)
    print(f"Max steps per request: {steps}")

    try:
        from .skills import load_skills, skills_dir

        errors: list[str] = []
        loaded = load_skills(errors)
        print(f"Skills: {len(loaded)} tools from {skills_dir()}")
        for error in errors:
            print(f"  error: {error}")
    except Exception as exc:
        print(f"Skills: unavailable ({exc})")

    optional = [
        ("pyautogui", "desktop mouse/keyboard/screenshots"),
        ("pyperclip", "clipboard access"),
        ("PIL", "image support"),
    ]
    print("Optional integrations:")
    for module, label in optional:
        try:
            available = importlib.util.find_spec(module) is not None
        except Exception:
            available = False
        print(f"  {module}: {'yes' if available else 'no'} - {label}")

    try:
        from .phone import adb_path, device_summary

        if adb_path():
            print(f"Phone (adb): yes - {device_summary()}")
        else:
            print("Phone (adb): no - install adb to control an Android phone")
    except Exception as exc:
        print(f"Phone (adb): error - {exc}")

    try:
        from .voice import available_stt, available_tts, audio_available, list_devices
        from .voice.audio import _find_player

        ok, message = audio_available()
        print(f"Audio: {'ok' if ok else 'unavailable'} - {message}")
        if ok:
            devices = list_devices()
            print("Input devices:")
            for line in devices.splitlines():
                print(f"  {line}")
        print(f"STT backends: {', '.join(available_stt()) or 'none'}")
        print(f"TTS backends: {', '.join(available_tts()) or 'none'}")
        player = _find_player()
        print(f"Audio player: {player[0] if player else 'none'}")
    except Exception as exc:
        print(f"Voice layer: {exc}")

    print("\nTips:")
    print("  ./install.sh                              # create .venv and install extras")
    print("  ./jarvis.sh doctor                        # re-run diagnostics")
    print("  sudo apt install adb                      # Android phone control")
    print("  pip install pyautogui                    # optional GUI mouse/keyboard")
    print("  sudo apt install libportaudio2 ffmpeg    # Debian/Ubuntu")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="jarvis",
        description="Jarvis - a voice-first assistant with pluggable LLMs.",
    )
    parser.add_argument("prompt", nargs="*", help="One-shot prompt. Commands: voice, doctor, models, demo.")
    parser.add_argument("--voice", action="store_true", help="Start voice mode.")
    parser.add_argument("--provider", choices=["anthropic", "openai", "nvidia", "nim", "ollama"])
    parser.add_argument("--model")
    parser.add_argument("--base-url")
    parser.add_argument("--api-key")
    parser.add_argument("--system", help="Override the system prompt.")
    parser.add_argument("--temperature", type=float)
    parser.add_argument("--max-tokens", type=int)
    parser.add_argument("--max-steps", type=int, help="Tool-step limit per request (0 = unlimited, default 0).")
    parser.add_argument(
        "--unsafe",
        action="store_true",
        help="Auto-approve every tool, including shell, Python, desktop and skill creation.",
    )
    parser.add_argument("--no-tools", action="store_true", help="Disable all tools.")
    parser.add_argument("--stt", help="Speech-to-text backend: auto, faster-whisper, whisper, openai, google")
    parser.add_argument("--tts", help="Text-to-speech backend: auto, edge, piper, pyttsx3, say, espeak")
    parser.add_argument("--whisper-model", help="Whisper model size (default: base).")
    parser.add_argument("--language", help="Speech language code (default: en).")
    parser.add_argument("--voice-name", help="Edge TTS voice (default: en-GB-RyanNeural).")
    parser.add_argument("--input-device", help="Input device index or name.")
    parser.add_argument("--wake-word", help="Wake word (default: jarvis).")
    parser.add_argument("--no-wake", action="store_true", help="Listen without a wake word.")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        config = Config.from_env()
    except ValueError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 2
    _apply_overrides(config, args)
    config.extra["no_tools"] = args.no_tools

    words = [word.lower() for word in args.prompt]
    if words[:1] == ["voice"]:
        args.voice = True
        args.prompt = args.prompt[1:]
    if words[:1] == ["doctor"]:
        return _run_doctor(config)
    if words[:1] == ["models"]:
        return _run_models(config)
    if words[:1] == ["demo"]:
        from .demo import run_demo

        return run_demo()

    if args.voice:
        try:
            return _run_voice(config, args)
        except ValueError as exc:
            print(f"Error: {exc}", file=sys.stderr)
            return 2

    try:
        return _run_text(config, args)
    except ValueError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
