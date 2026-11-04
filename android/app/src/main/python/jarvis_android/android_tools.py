"""Native Android tools exposed to the Jarvis agent."""

from __future__ import annotations

import contextlib
import io
import json
import threading
from typing import Any

from jarvis.skills import delete_skill_file, list_skills, write_skill
from jarvis.tool import Tool, schema
from jarvis.tools import forget, get_time, get_weather, recall, remember
from jarvis import web

from .bridge import call


def _result(value: Any) -> str:
    if value is None:
        return "Done."
    return str(value)


def device_info() -> str:
    return _result(call("deviceInfo"))


def shell(command: str, root: bool = False, timeout: int = 120) -> str:
    return _result(call("shell", command, bool(root)))


def ui(action: str, text: str = "", x: int = 0, y: int = 0, x2: int = 0, y2: int = 0,
       duration: int = 300, key: str = "", exact: bool = False) -> str:
    action = action.lower()
    if action in ("dump", "read", "screen_text"):
        return _result(call("uiDump"))
    if action == "click":
        return _result(call("clickText", text, bool(exact)))
    if action == "tap":
        return _result(call("tap", int(x), int(y)))
    if action == "swipe":
        return _result(call("swipe", int(x), int(y), int(x2), int(y2), int(duration)))
    if action == "type":
        return _result(call("typeText", text))
    if action == "key":
        return _result(call("key", key))
    return f"Unknown UI action: {action}"


def screenshot(path: str = "") -> str:
    return _result(call("screenshot", path))


def notifications() -> str:
    return _result(call("notifications"))


def apps(action: str = "list", package: str = "", query: str = "") -> str:
    action = action.lower()
    if action == "list":
        return _result(call("apps", query))
    if action == "launch":
        return _result(call("launchApp", package))
    if action == "stop":
        return _result(call("stopApp", package))
    return f"Unknown apps action: {action}"


def send_intent(action: str, uri: str = "", package: str = "", extras: str = "") -> str:
    return _result(call("sendIntent", action, uri, package, extras))


def open_target(target: str) -> str:
    return _result(call("open", target))


def files(action: str, path: str, content: str = "", max_chars: int = 20000) -> str:
    action = action.lower()
    if action == "read":
        return _result(call("fileRead", path, int(max_chars)))
    if action == "write":
        return _result(call("fileWrite", path, content))
    if action == "list":
        return _result(call("fileList", path))
    if action == "delete":
        return _result(call("fileDelete", path))
    return f"Unknown files action: {action}"


def clipboard(action: str, text: str = "") -> str:
    action = action.lower()
    if action == "read":
        return _result(call("clipboardGet"))
    if action == "write":
        return _result(call("clipboardSet", text))
    return f"Unknown clipboard action: {action}"


def media(action: str) -> str:
    return _result(call("key", action))


def volume(level: int) -> str:
    return _result(call("key", "volume_up" if level > 0 else "volume_down"))


def send_sms(number: str, message: str) -> str:
    return _result(call("sendSms", number, message))


def call_number(number: str) -> str:
    return _result(call("call", number))


def contacts(query: str = "") -> str:
    return _result(call("contacts", query))


def location() -> str:
    return _result(call("location"))


def install_apk(apk_path: str) -> str:
    return _result(call("installApk", apk_path))


def uninstall(package: str) -> str:
    return _result(call("uninstall", package))


def device_admin(action: str) -> str:
    return _result(call("deviceAdmin", action))


def toast(message: str) -> str:
    return _result(call("toast", message))


def speak(text: str) -> str:
    return _result(call("speak", text))


def update_source() -> str:
    return _result(call("applyCodeUpdate"))


def android_python(code: str) -> str:
    buffer = io.StringIO()
    namespace: dict[str, Any] = {"__name__": "__jarvis_android__"}
    try:
        with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(buffer):
            exec(compile(code, "<jarvis-android>", "exec"), namespace)
        output = buffer.getvalue()
        return "Exit code 0. " + (output or "(no output)")
    except Exception as exc:
        output = buffer.getvalue()
        return f"Exit code 1. {output}\n{type(exc).__name__}: {exc}"


def set_timer(seconds: int, label: str) -> str:
    def fire() -> None:
        call("speak", f"Sir, your {label} timer has finished.")

    timer = threading.Timer(float(seconds), fire)
    timer.daemon = True
    timer.start()
    return f"Timer set for {seconds} seconds."


def create_skill(name: str, description: str, code: str) -> str:
    path, tools = write_skill(name, description, code)
    return (
        f"Skill saved to {path}. New tools are available immediately: "
        + ", ".join(tool.name for tool in tools)
        + "."
    )


def _tool(name: str, description: str, properties: dict, func, required=None, approval=False) -> Tool:
    return Tool(
        name=name,
        description=description,
        input_schema=schema(properties, required or []),
        func=func,
        requires_approval=approval,
    )


def build_android_tools() -> list[Tool]:
    tools: list[Tool] = [
        _tool("get_time", "Get the current local date and time.", {}, get_time),
        _tool(
            "get_weather",
            "Get current weather for a location. Leave location empty for the current area.",
            {"location": {"type": "string"}},
            get_weather,
        ),
        _tool(
            "device_info",
            "Report the phone model, Android version, battery, storage, screen size, IP address and root status.",
            {},
            device_info,
        ),
        _tool(
            "android_shell",
            "Run a shell command on this Android phone. With root=true it runs su -c, giving unrestricted system access on a rooted device. Without root it runs as this app's user.",
            {"command": {"type": "string"}, "root": {"type": "boolean"}},
            shell,
            ["command"],
            approval=True,
        ),
        _tool(
            "android_ui",
            "Read and control the visible Android screen through the accessibility service. Actions: dump (read every label with tap coordinates), click (text; exact optional), tap (x,y), swipe (x,y,x2,y2,duration), type (text), key (back, home, recents, notifications, quicksettings, enter, delete, lock). Use dump first, then tap the coordinates it reports.",
            {
                "action": {"type": "string", "enum": ["dump", "click", "tap", "swipe", "type", "key"]},
                "text": {"type": "string"},
                "x": {"type": "integer"},
                "y": {"type": "integer"},
                "x2": {"type": "integer"},
                "y2": {"type": "integer"},
                "duration": {"type": "integer"},
                "key": {"type": "string"},
                "exact": {"type": "boolean"},
            },
            ui,
            ["action"],
        ),
        _tool(
            "android_screenshot",
            "Capture the current phone screen to a PNG and return the path.",
            {"path": {"type": "string"}},
            screenshot,
        ),
        _tool(
            "android_notifications",
            "Read the active notifications with app package, title, text and time.",
            {},
            notifications,
        ),
        _tool(
            "android_apps",
            "List installed apps, launch an app by package name, or stop it. Use query to filter the list.",
            {
                "action": {"type": "string", "enum": ["list", "launch", "stop"]},
                "package": {"type": "string"},
                "query": {"type": "string"},
            },
            apps,
            ["action"],
        ),
        _tool(
            "android_intent",
            "Send an arbitrary Android intent with action, data URI, target package and JSON string extras.",
            {
                "action": {"type": "string"},
                "uri": {"type": "string"},
                "package": {"type": "string"},
                "extras": {"type": "string"},
            },
            send_intent,
            ["action"],
        ),
        _tool(
            "android_open",
            "Open a URL, app package, file URI or a settings page (settings, wifi, bluetooth, developer, accessibility, notifications).",
            {"target": {"type": "string"}},
            open_target,
            ["target"],
        ),
        _tool(
            "android_files",
            "Read, write, list or delete files on the phone. Use absolute paths or ~ for the app external files directory. Grant all-files access for shared storage.",
            {
                "action": {"type": "string", "enum": ["read", "write", "list", "delete"]},
                "path": {"type": "string"},
                "content": {"type": "string"},
                "max_chars": {"type": "integer"},
            },
            files,
            ["action", "path"],
        ),
        _tool(
            "android_clipboard",
            "Read or write the phone clipboard.",
            {"action": {"type": "string", "enum": ["read", "write"]}, "text": {"type": "string"}},
            clipboard,
            ["action"],
        ),
        _tool(
            "android_media",
            "Control media playback: play_pause, next, previous, stop, volume_up, volume_down, mute.",
            {"action": {"type": "string"}},
            media,
            ["action"],
        ),
        _tool(
            "android_sms",
            "Send an SMS from the phone.",
            {"number": {"type": "string"}, "message": {"type": "string"}},
            send_sms,
            ["number", "message"],
            approval=True,
        ),
        _tool(
            "android_call",
            "Place a phone call, or open the dialer when call permission is missing.",
            {"number": {"type": "string"}},
            call_number,
            ["number"],
        ),
        _tool(
            "android_contacts",
            "Search the phone contacts by name or number. Empty query lists recent contacts.",
            {"query": {"type": "string"}},
            contacts,
        ),
        _tool(
            "android_location",
            "Get the last known GPS/network location.",
            {},
            location,
        ),
        _tool(
            "android_install_apk",
            "Install or update an APK on the phone by path.",
            {"apk_path": {"type": "string"}},
            install_apk,
            ["apk_path"],
            approval=True,
        ),
        _tool(
            "android_uninstall",
            "Uninstall an app by package name.",
            {"package": {"type": "string"}},
            uninstall,
            ["package"],
            approval=True,
        ),
        _tool(
            "android_device_admin",
            "Device-admin actions: status, lock, camera_off, camera_on, wipe. Wipe requires device-owner mode.",
            {"action": {"type": "string", "enum": ["status", "lock", "camera_off", "camera_on", "wipe"]}},
            device_admin,
            ["action"],
            approval=True,
        ),
        _tool(
            "android_python",
            "Execute arbitrary Python inside the Jarvis app process and return printed output. Use this for computation, APIs and anything the other tools cannot do.",
            {"code": {"type": "string"}},
            android_python,
            ["code"],
            approval=True,
        ),
        _tool(
            "android_speak",
            "Speak text aloud through the phone's text-to-speech voice.",
            {"text": {"type": "string"}},
            speak,
            ["text"],
        ),
        _tool(
            "android_toast",
            "Show a toast message on the phone.",
            {"message": {"type": "string"}},
            toast,
            ["message"],
        ),
        _tool(
            "set_timer",
            "Set a timer that speaks when it finishes.",
            {"seconds": {"type": "integer"}, "label": {"type": "string"}},
            set_timer,
            ["seconds", "label"],
        ),
        _tool(
            "remember",
            "Store a fact in long-term memory.",
            {"key": {"type": "string"}, "value": {"type": "string"}},
            remember,
            ["key", "value"],
        ),
        _tool(
            "recall",
            "Recall a stored fact, or list everything when key is empty.",
            {"key": {"type": "string"}},
            recall,
        ),
        _tool(
            "forget",
            "Delete a stored fact.",
            {"key": {"type": "string"}},
            forget,
            ["key"],
        ),
        _tool(
            "web_search",
            "Search the web and return titles, URLs and snippets.",
            {"query": {"type": "string"}, "max_results": {"type": "integer"}},
            web.web_search,
            ["query"],
        ),
        _tool(
            "fetch_url",
            "Fetch a web page or API endpoint and return readable text.",
            {"url": {"type": "string"}, "max_chars": {"type": "integer"}},
            web.fetch_url,
            ["url"],
        ),
        _tool(
            "http_request",
            "Make a custom HTTP request with method, headers and body.",
            {
                "method": {"type": "string"},
                "url": {"type": "string"},
                "headers": {"type": "string"},
                "body": {"type": "string"},
                "timeout": {"type": "integer"},
            },
            web.http_request,
            ["url"],
        ),
        _tool(
            "create_skill",
            "Permanently extend Jarvis by writing a Python skill to the skills directory. The code must define TOOLS as a list of dicts with name, description, input_schema, func and optional requires_approval. Skills can call the Android bridge through jarvis_android.bridge.call.",
            {"name": {"type": "string"}, "description": {"type": "string"}, "code": {"type": "string"}},
            create_skill,
            ["name", "description", "code"],
            approval=True,
        ),
        _tool(
            "delete_skill",
            "Delete a skill file.",
            {"name": {"type": "string"}},
            delete_skill_file,
            ["name"],
        ),
        _tool("list_skills", "List installed skills and their tools.", {}, list_skills),
        _tool(
            "android_update_source",
            "Download the newest Jarvis Python code from GitHub. The app applies it immediately without reinstalling.",
            {},
            update_source,
            approval=True,
        ),
    ]
    return tools
