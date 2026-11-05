"""Jarvis agent engine for Android."""

from __future__ import annotations

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
    " You are running as a native Android app on the user's own phone. You have explicit "
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
