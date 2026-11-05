"""Jarvis agent engine for Android."""

from __future__ import annotations

import datetime
import json
import os
import re
import traceback
from typing import Any

from jarvis.agent import Agent
from jarvis.config import Config
from jarvis.policy import needs_command_approval
from jarvis.providers import build_provider
from jarvis.skills import load_skills

from .android_tools import build_android_tools
from .bridge import call, request_approval

_agent: Agent | None = None
_config: Config | None = None
_tools: list = []
_setup_error: str | None = None
_tool_log: list[dict[str, str]] = []

ANDROID_SYSTEM = (
    " Reply as if speaking aloud: one or two sentences unless the user asks for detail. "
    "Never narrate tool use or list steps; report the outcome, sir. "
    "You are running as a native Android app on the user's own phone. You have explicit "
    "permission to control this device completely: accessibility UI automation, reading "
    "notifications, screenshots, shell as the app user or root, app management, intents, "
    "SMS, calls, contacts, location, files, clipboard, media, camera, sharing, settings, "
    "device administration and arbitrary Python. Start with android_screen state to get every "
    "window, node_id, bounds and "
    "flags, then use android_node to click/scroll/act on a node_id, android_text to type or press "
    "keys, android_gesture for swipes, pinches and custom multi-touch paths, android_system for "
    "back/home/recents/lock/screen, and android_settings for brightness, rotation, volume, DND, "
    "torch and system panels. Use android_camera for photos/video, android_share for the share "
    "sheet, and android_files for storage. Use "
    "android_shell for anything the structured tools do not cover. When a command is "
    "destructive the app may ask the user for approval; that is expected. Do not refuse a "
    "task merely because it is powerful. Act on the user's own device, keep going until the "
    "task is finished, and report what actually happened."
)


def _detail(arguments: dict[str, Any]) -> str:
    value = (
        arguments.get("command")
        or arguments.get("args")
        or arguments.get("code")
        or arguments.get("message")
        or arguments.get("target")
        or arguments.get("path")
        or arguments.get("package")
        or arguments.get("name")
        or ""
    )
    text = " ".join(str(value).split())
    return text[:800]


_ANDROID_DANGEROUS = re.compile(
    r"\b(pm\s+(uninstall|clear)|cmd\s+package\s+uninstall|wipe|format|"
    r"reboot\s+(recovery|bootloader)|settings\s+put|svc\s+(wifi|data|power))\b",
    re.IGNORECASE,
)


def _approve(name: str, arguments: dict[str, Any]) -> bool:
    if name == "android_shell":
        command = str(arguments.get("command", ""))
        if (
            not arguments.get("root")
            and not needs_command_approval(command)
            and not _ANDROID_DANGEROUS.search(command)
        ):
            return True
    return request_approval(name, _detail(arguments))


def _on_tool(name: str, arguments: dict[str, Any]) -> None:
    try:
        rendered = json.dumps(arguments, ensure_ascii=False)
    except Exception:
        rendered = str(arguments)
    entry = {"name": name, "args": rendered[:2000]}
    _tool_log.append(entry)
    try:
        call("logTool", name, rendered[:2000])
    except Exception:
        pass


_GREETINGS = {
    "hello", "hi", "hey", "hey jarvis", "hello jarvis", "good morning",
    "good afternoon", "good evening", "good night", "are you there",
}


def _json_reply(reply: str) -> str:
    return json.dumps({"ok": True, "reply": reply, "tools": _tool_log}, ensure_ascii=False)


def _remember(text: str, reply: str) -> None:
    if _agent is None:
        return
    _agent.history.append({"role": "user", "content": text})
    _agent.history.append({"role": "assistant", "content": reply, "tool_calls": []})


def _fast_reply(text: str) -> str | None:
    """Handle the most common spoken commands locally, with no model round-trip."""
    raw = text.strip().lower()
    t = re.sub(r"[^a-z0-9 ]", " ", raw)
    t = re.sub(r"\s+", " ", t).strip()
    if not t:
        return None

    now = datetime.datetime.now()
    if t in _GREETINGS:
        if t == "good night":
            return "Good night, sir."
        if t == "are you there":
            return "At your service, sir."
        if t == "good morning":
            return "Good morning, sir."
        if t == "good afternoon":
            return "Good afternoon, sir."
        if t == "good evening":
            return "Good evening, sir."
        return "At your service, sir."
    if t in {"what time is it", "whats the time", "what is the time", "time", "current time", "tell me the time"}:
        return f"It is {now.strftime('%H:%M')}, sir."
    if t in {
        "whats the date", "what is the date", "todays date", "what day is it",
        "what is todays date", "date",
    }:
        return f"It is {now.strftime('%A')}, {now.day} {now.strftime('%B')}, sir."
    if t in {"thank you", "thanks", "thank you jarvis", "thanks jarvis"}:
        return "Always, sir."
    if t in {"how are you", "how are you doing", "status"}:
        return "All systems nominal, sir."
    if t in {"who are you", "what are you"}:
        return "J.A.R.V.I.S., sir. Your interface to this device."
    if t in {"what can you do", "help", "capabilities"}:
        return "I can run this phone, sir. Apps, settings, messages, camera, files and the web. Ask plainly."
    if t in {"stop", "never mind", "nevermind", "standby", "standing by", "dismiss"}:
        return "Standing by, sir."

    if t in {"lock", "lock the phone", "lock phone", "lock the device", "lock device", "lock screen", "lock the screen"}:
        result = call("systemAction", "lock", 0)
        if "not enabled" in str(result).lower():
            return "Accessibility is off, sir. Enable it in Access."
        return "Locking the device, sir."
    if t in {"home", "go home", "press home", "home screen"}:
        result = call("systemAction", "home", 0)
        if "not enabled" in str(result).lower():
            return "Accessibility is off, sir."
        return "Home, sir."
    if t in {"back", "go back", "press back"}:
        result = call("systemAction", "back", 0)
        if "not enabled" in str(result).lower():
            return "Accessibility is off, sir."
        return "Back, sir."
    if t in {"recents", "recent apps", "show recent apps", "app switcher"}:
        result = call("systemAction", "recents", 0)
        if "not enabled" in str(result).lower():
            return "Accessibility is off, sir."
        return "Recent apps, sir."
    if t in {"screenshot", "take a screenshot", "capture the screen", "screenshot please"}:
        result = call("screenshot", "")
        if "not enabled" in str(result).lower():
            return "Accessibility is off, sir."
        return "Captured, sir."

    torch = re.match(r"^(?:turn )?(?:the )?(torch|flashlight) (on|off)$", t)
    if torch is None:
        torch = re.match(r"^(on|off) (?:the )?(torch|flashlight)$", t)
        if torch is not None:
            state = torch.group(1)
        else:
            state = ""
    else:
        state = torch.group(2)
    if state:
        result = call("deviceSettings", "torch", 0, state)
        if "not granted" in str(result).lower() or "not enabled" in str(result).lower():
            return "I cannot reach the torch, sir."
        return f"Torch {state}, sir."

    volume = re.match(r"^(?:set )?volume (?:to )?(\d{1,3})$", t)
    if volume is not None:
        level = max(0, min(100, int(volume.group(1))))
        call("deviceSettings", "volume", level, "")
        return f"Volume {level} percent, sir."
    if t in {"volume up", "turn it up", "louder"}:
        call("key", "volume_up")
        return "Volume up, sir."
    if t in {"volume down", "turn it down", "quieter"}:
        call("key", "volume_down")
        return "Volume down, sir."
    if t in {"mute", "mute the phone"}:
        call("key", "mute")
        return "Muted, sir."
    if t in {"play", "pause", "play pause", "playpause"}:
        call("key", "play_pause")
        return "Done, sir."
    if t in {"next", "next track", "skip"}:
        call("key", "media_next")
        return "Next track, sir."
    if t in {"previous", "previous track", "last track"}:
        call("key", "media_previous")
        return "Previous track, sir."

    if t in {"battery", "whats my battery", "battery status", "how much battery"}:
        info = str(call("deviceInfo"))
        level = re.search(r"Battery (\d+)%", info)
        if level:
            charging = "charging" in info.lower() and "discharging" not in info.lower()
            return f"Battery at {level.group(1)} percent, {'charging' if charging else 'on battery'}, sir."
        return "I cannot read the battery, sir."

    if t.startswith("open "):
        target = t[5:].strip()
        direct = {
            "settings": "settings",
            "wifi": "wifi",
            "wi fi": "wifi",
            "bluetooth": "bluetooth",
            "developer options": "developer",
            "accessibility": "accessibility",
            "notifications": "notifications",
        }
        if target in direct:
            call("open", direct[target])
            return f"Opening {target}, sir."
        listed = str(call("apps", target))
        first = listed.splitlines()[0] if listed else ""
        if first and " - " in first and "No apps matched" not in listed:
            package, _, label = first.partition(" - ")
            call("launchApp", package)
            return f"Opening {label}, sir."
        return None

    return None


def start(settings: dict[str, Any]) -> dict[str, Any]:
    global _agent, _config, _tools, _setup_error
    _agent = None
    _config = None
    _setup_error = None
    _tools = build_android_tools()
    provider_name = str(settings.get("provider") or "nvidia")
    try:
        config = Config(
            provider=provider_name,
            model=str(settings.get("model") or ""),
            base_url=str(settings.get("base_url") or ""),
            api_key=(str(settings.get("api_key") or "") or None),
            temperature=float(settings.get("temperature") or 0.7),
            max_tokens=int(settings.get("max_tokens") or 4096),
            max_steps=int(settings.get("max_steps") or 0),
        )
        extra = str(settings.get("system") or "").strip()
        config.system = config.system + ANDROID_SYSTEM
        if extra:
            config.system = extra + "\n\n" + config.system
        _config = config
        try:
            provider = build_provider(config)
        except Exception as exc:
            _setup_error = f"{exc}. Open Settings and add an API key."
            return {
                "provider": config.provider,
                "model": config.model,
                "tools": len(_tools),
                "setup_error": _setup_error,
            }
        _agent = Agent(
            provider=provider,
            tools=_tools,
            system=config.system,
            on_tool=_on_tool,
            approver=None if settings.get("unsafe") else _approve,
            max_steps=config.max_steps,
        )
        return {
            "provider": config.provider,
            "model": config.model,
            "tools": len(_tools),
            "setup_error": None,
        }
    except Exception as exc:
        _setup_error = f"{type(exc).__name__}: {exc}"
        return {"provider": provider_name, "tools": len(_tools), "setup_error": _setup_error}


def _refresh_skills() -> None:
    if _agent is None:
        return
    existing = {tool.name for tool in _agent.tools}
    for tool in load_skills():
        if tool.name not in existing:
            _agent.tools.append(tool)
            existing.add(tool.name)


def chat(text: str) -> str:
    global _tool_log
    _tool_log = []
    fast = _fast_reply(text)
    if fast is not None:
        _remember(text, fast)
        return _json_reply(fast)
    if _setup_error:
        return json.dumps(
            {"ok": False, "error": _setup_error, "tools": _tool_log}, ensure_ascii=False
        )
    if _agent is None:
        return json.dumps(
            {"ok": False, "error": "Jarvis engine is not configured.", "tools": _tool_log},
            ensure_ascii=False,
        )
    try:
        reply = _agent.run(text)
        _refresh_skills()
        return json.dumps(
            {"ok": True, "reply": reply, "tools": _tool_log}, ensure_ascii=False
        )
    except Exception as exc:
        return json.dumps(
            {
                "ok": False,
                "error": f"{type(exc).__name__}: {exc}",
                "trace": traceback.format_exc()[-2000:],
                "tools": _tool_log,
            },
            ensure_ascii=False,
        )


def reset() -> str:
    if _agent is not None:
        _agent.reset()
    return "Conversation cleared."


def tools_json() -> str:
    return json.dumps([tool.spec() for tool in _tools], ensure_ascii=False)


def self_test() -> str:
    return json.dumps(
        {
            "ok": _agent is not None,
            "setup_error": _setup_error,
            "provider": _config.provider if _config else None,
            "model": _config.model if _config else None,
            "tools": len(_tools),
            "home": os.environ.get("HOME"),
            "skills": os.environ.get("JARVIS_SKILLS_DIR"),
        },
        ensure_ascii=False,
    )
