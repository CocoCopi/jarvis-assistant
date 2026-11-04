import json
import os
import shutil
import subprocess
import sys
import threading
from datetime import datetime
from typing import Any, Callable
from urllib.parse import quote

from . import desktop, phone, skills, web
from .tool import Tool, schema

MEMORY_PATH = os.path.expanduser("~/.jarvis/memory.json")


class TimerManager:
    def __init__(self, speaker: Callable[[str], None] | None = None) -> None:
        self.speaker = speaker or (lambda text: print(f"\nJarvis: {text}\n", flush=True))
        self._timers: list[threading.Timer] = []
        self._lock = threading.Lock()

    def set(self, seconds: float, label: str) -> None:
        def fire() -> None:
            message = f"Sir, your {label} timer has finished."
            print(f"\n[Jarvis] {message}\n", flush=True)
            try:
                self.speaker(message)
            except Exception:
                pass

        timer = threading.Timer(seconds, fire)
        timer.daemon = True
        with self._lock:
            self._timers = [t for t in self._timers if t.is_alive()]
            self._timers.append(timer)
        timer.start()

    def cancel_all(self) -> None:
        with self._lock:
            for timer in self._timers:
                timer.cancel()
            self._timers.clear()


def _format_duration(seconds: float) -> str:
    seconds = int(seconds)
    if seconds < 60:
        return f"{seconds} second{'s' if seconds != 1 else ''}"
    minutes, secs = divmod(seconds, 60)
    if minutes < 60:
        return f"{minutes} minute{'s' if minutes != 1 else ''}" + (
            f" {secs} seconds" if secs else ""
        )
    hours, minutes = divmod(minutes, 60)
    return f"{hours} hour{'s' if hours != 1 else ''}" + (
        f" {minutes} minutes" if minutes else ""
    )


def get_time() -> str:
    now = datetime.now().astimezone()
    return now.strftime("It is %H:%M on %A, %d %B %Y.").replace(" 0", " ")


def get_weather(location: str = "") -> str:
    target = quote(location) if location else ""
    url = f"https://wttr.in/{target}?format=j1"
    try:
        from urllib.request import urlopen

        with urlopen(url, timeout=15) as response:
            data = json.loads(response.read().decode("utf-8", errors="replace"))
        current = data["current_condition"][0]
        desc = current["weatherDesc"][0]["value"]
        temp = current["temp_C"]
        feels = current["FeelsLikeC"]
        humidity = current["humidity"]
        wind = current["windspeedKmph"]
        place = location or data.get("nearest_area", [{}])[0].get("areaName", [{}])[0].get("value", "your area")
        return (
            f"In {place} it is {temp} degrees Celsius with {desc.lower()}. "
            f"Feels like {feels}, humidity {humidity} percent, wind {wind} kilometres per hour."
        )
    except Exception as exc:
        return f"Weather lookup failed: {exc}"


def system_status() -> str:
    parts = []
    battery = _battery_status()
    if battery:
        parts.append(battery)
    try:
        load = os.getloadavg()
        parts.append(f"Load average {load[0]:.2f}, {load[1]:.2f}, {load[2]:.2f}")
    except (AttributeError, OSError):
        pass
    try:
        with open("/proc/meminfo") as handle:
            info = dict(
                line.split(":", 1) for line in handle.read().splitlines() if ":" in line
            )
        total = int(info["MemTotal"].split()[0])
        available = int(info["MemAvailable"].split()[0])
        used_pct = round(100 * (total - available) / total)
        parts.append(f"Memory {used_pct} percent in use")
    except Exception:
        pass
    try:
        usage = shutil.disk_usage(os.path.expanduser("~"))
        used_pct = round(100 * usage.used / usage.total)
        parts.append(f"Disk {used_pct} percent full")
    except Exception:
        pass
    if not parts:
        return "I could not read system status on this machine."
    return "System status: " + ". ".join(parts) + "."


def _battery_status() -> str | None:
    base = "/sys/class/power_supply"
    if not os.path.isdir(base):
        return None
    try:
        names = os.listdir(base)
    except OSError:
        return None
    for name in names:
        if not name.lower().startswith("bat"):
            continue
        try:
            with open(os.path.join(base, name, "capacity")) as handle:
                capacity = handle.read().strip()
            with open(os.path.join(base, name, "status")) as handle:
                status = handle.read().strip()
            return f"Battery {capacity} percent, {status.lower()}"
        except OSError:
            continue
    return None


def _load_memory() -> dict[str, str]:
    try:
        with open(MEMORY_PATH) as handle:
            data = json.load(handle)
        return data if isinstance(data, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def _save_memory(memory: dict[str, str]) -> None:
    os.makedirs(os.path.dirname(MEMORY_PATH), exist_ok=True)
    with open(MEMORY_PATH, "w") as handle:
        json.dump(memory, handle, indent=2, sort_keys=True)


def remember(key: str, value: str) -> str:
    memory = _load_memory()
    memory[key.lower().strip()] = value.strip()
    _save_memory(memory)
    return f"Noted, sir. I will remember that {key} is {value}."


def recall(key: str = "") -> str:
    memory = _load_memory()
    if not key:
        if not memory:
            return "I have no stored notes."
        return "I remember: " + "; ".join(f"{k} is {v}" for k, v in memory.items()) + "."
    value = memory.get(key.lower().strip())
    if value is None:
        return f"I have nothing stored under {key}."
    return f"{key} is {value}."


def forget(key: str) -> str:
    memory = _load_memory()
    if memory.pop(key.lower().strip(), None) is None:
        return f"I had nothing stored under {key}."
    _save_memory(memory)
    return f"Forgotten: {key}."


def open_app(target: str) -> str:
    import shlex

    parts = shlex.split(target)
    if "://" not in target and parts and shutil.which(parts[0]):
        command = parts
    elif sys.platform == "darwin":
        command = ["open", target] if "://" in target else ["open", "-a", target]
    elif sys.platform.startswith("win"):
        command = ["cmd", "/c", "start", "", target]
    else:
        command = ["xdg-open", target]
    try:
        subprocess.Popen(
            command,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL,
            start_new_session=True,
        )
        return f"Opening {target}."
    except FileNotFoundError:
        return f"I cannot open {target}; the launcher is not installed."
    except Exception as exc:
        return f"I could not open {target}: {exc}"


def set_volume(level: int) -> str:
    level = max(0, min(100, int(level)))
    commands: list[list[str]] = []
    if sys.platform == "darwin":
        commands.append(["osascript", "-e", f"set volume output volume {level}"])
    else:
        commands.append(["pactl", "set-sink-volume", "@DEFAULT_SINK@", f"{level}%"])
        commands.append(["amixer", "-q", "sset", "Master", f"{level}%"])
    for command in commands:
        if shutil.which(command[0]):
            try:
                subprocess.run(command, check=True, capture_output=True, timeout=5)
                return f"Volume set to {level} percent."
            except Exception:
                continue
    return "I could not change the volume on this machine."


def read_file(path: str, max_bytes: int = 20000) -> str:
    path = os.path.expanduser(path)
    try:
        with open(path, "r", errors="replace") as handle:
            content = handle.read(max_bytes + 1)
    except OSError as exc:
        return f"Could not read {path}: {exc}"
    if len(content) > max_bytes:
        content = content[:max_bytes] + "\n[truncated]"
    return content


def write_file(path: str, content: str) -> str:
    path = os.path.expanduser(path)
    try:
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        with open(path, "w") as handle:
            handle.write(content)
    except OSError as exc:
        return f"Could not write {path}: {exc}"
    return f"Wrote {len(content)} characters to {path}."


def _run_process(command: Any, timeout: int, shell: bool = False) -> str:
    try:
        result = subprocess.run(
            command,
            shell=shell,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        return f"Command timed out after {timeout} seconds."
    except Exception as exc:
        return f"Command failed: {exc}"
    output = (result.stdout + result.stderr).strip()
    if len(output) > 6000:
        output = output[:6000] + "\n[truncated]"
    if not output:
        output = "(no output)"
    return f"Exit code {result.returncode}. {output}"


def run_command(command: str, timeout: int = 30) -> str:
    return _run_process(command, int(timeout), shell=True)


def run_python(code: str, timeout: int = 30) -> str:
    return _run_process([sys.executable, "-c", code], int(timeout))


def list_directory(path: str = ".") -> str:
    path = os.path.expanduser(path)
    try:
        entries = sorted(os.listdir(path))
    except OSError as exc:
        return f"Could not list {path}: {exc}"
    if not entries:
        return f"{path} is empty."
    lines = []
    for name in entries[:200]:
        full = os.path.join(path, name)
        lines.append(name + ("/" if os.path.isdir(full) else ""))
    suffix = "" if len(entries) <= 200 else f"\n... and {len(entries) - 200} more"
    return "\n".join(lines) + suffix


def build_tools(
    speaker: Callable[[str], None] | None = None,
    timers: TimerManager | None = None,
) -> list[Tool]:
    timers = timers or TimerManager(speaker)
    tools: list[Tool] = []

    def create_skill_tool(name: str, description: str, code: str) -> str:
        try:
            path, new_tools = skills.write_skill(name, description, code)
        except Exception as exc:
            return f"Skill was not created: {exc}"
        existing = {tool.name for tool in tools}
        added = []
        for tool in new_tools:
            if tool.name in existing:
                continue
            tools.append(tool)
            existing.add(tool.name)
            added.append(tool.name)
        if not added:
            return f"Skill saved to {path}, but it defined no new tools."
        return (
            f"Skill saved to {path}. New tools are available immediately: "
            + ", ".join(added)
            + "."
        )

    builtins: list[Tool] = [
        Tool("get_time", "Get the current local date and time.", schema({}), get_time),
        Tool(
            "get_weather",
            "Get current weather for a location. Leave location empty for the current area.",
            schema({"location": {"type": "string", "description": "City or place name"}}),
            get_weather,
        ),
        Tool(
            "system_status",
            "Report battery, CPU load, memory and disk usage of this machine.",
            schema({}),
            system_status,
        ),
        Tool(
            "set_timer",
            "Set a timer that audibly announces when it finishes.",
            schema(
                {
                    "seconds": {"type": "integer", "description": "Duration in seconds"},
                    "label": {"type": "string", "description": "What the timer is for"},
                },
                ["seconds", "label"],
            ),
            lambda seconds, label: (
                timers.set(float(seconds), label)
                or f"Timer set for {_format_duration(float(seconds))}."
            ),
        ),
        Tool(
            "remember",
            "Store a fact or note in long-term memory.",
            schema(
                {
                    "key": {"type": "string", "description": "Short name for the fact"},
                    "value": {"type": "string", "description": "The fact to remember"},
                },
                ["key", "value"],
            ),
            remember,
        ),
        Tool(
            "recall",
            "Recall a stored fact, or list everything remembered when key is empty.",
            schema({"key": {"type": "string", "description": "Fact name; empty to list all"}}),
            recall,
        ),
        Tool(
            "forget",
            "Delete a stored fact from memory.",
            schema({"key": {"type": "string"}}, ["key"]),
            forget,
        ),
        Tool(
            "open_app",
            "Open an application, file, folder or URL with the system launcher.",
            schema({"target": {"type": "string"}}, ["target"]),
            open_app,
        ),
        Tool(
            "set_volume",
            "Set the system output volume from 0 to 100.",
            schema({"level": {"type": "integer", "minimum": 0, "maximum": 100}}, ["level"]),
            set_volume,
        ),
        Tool(
            "read_file",
            "Read a text file from disk.",
            schema({"path": {"type": "string"}}, ["path"]),
            read_file,
        ),
        Tool(
            "write_file",
            "Write text to a file, creating it if needed.",
            schema(
                {"path": {"type": "string"}, "content": {"type": "string"}},
                ["path", "content"],
            ),
            write_file,
        ),
        Tool(
            "list_directory",
            "List the contents of a directory.",
            schema({"path": {"type": "string"}}),
            list_directory,
        ),
        Tool(
            "run_command",
            "Run any shell command on this machine and return its output. Use this to control the operating system, install packages, manage services, and automate anything.",
            schema(
                {
                    "command": {"type": "string"},
                    "timeout": {"type": "integer", "minimum": 1, "maximum": 600},
                },
                ["command"],
            ),
            run_command,
            requires_approval=True,
        ),
        Tool(
            "run_python",
            "Execute arbitrary Python code on this machine and return its output. Use this for computation, data processing, scripting, APIs and anything the other tools cannot do.",
            schema(
                {
                    "code": {"type": "string", "description": "Python source to execute"},
                    "timeout": {"type": "integer", "minimum": 1, "maximum": 600},
                },
                ["code"],
            ),
            run_python,
            requires_approval=True,
        ),
        Tool(
            "web_search",
            "Search the web with DuckDuckGo and return titles, URLs and snippets.",
            schema(
                {
                    "query": {"type": "string"},
                    "max_results": {"type": "integer", "minimum": 1, "maximum": 10},
                },
                ["query"],
            ),
            web.web_search,
        ),
        Tool(
            "fetch_url",
            "Fetch a web page or API endpoint and return its readable text.",
            schema(
                {
                    "url": {"type": "string"},
                    "max_chars": {"type": "integer", "minimum": 100, "maximum": 50000},
                },
                ["url"],
            ),
            web.fetch_url,
        ),
        Tool(
            "http_request",
            "Make an HTTP request with any method, headers and body. Use this to call web APIs.",
            schema(
                {
                    "method": {"type": "string", "description": "GET, POST, PUT, PATCH, DELETE..."},
                    "url": {"type": "string"},
                    "headers": {"type": "string", "description": "Optional JSON object of headers"},
                    "body": {"type": "string", "description": "Optional request body"},
                    "timeout": {"type": "integer", "minimum": 1, "maximum": 300},
                },
                ["url"],
            ),
            web.http_request,
        ),
        Tool(
            "screenshot",
            "Capture the screen to an image file and return its path.",
            schema({"path": {"type": "string", "description": "Optional output path"}}),
            desktop.screenshot,
        ),
        Tool(
            "mouse_control",
            "Control the mouse: move, click, double click, right click, middle click, scroll, or read the pointer position.",
            schema(
                {
                    "action": {
                        "type": "string",
                        "enum": [
                            "position", "move", "click", "double_click",
                            "right_click", "middle_click", "scroll",
                        ],
                    },
                    "x": {"type": "integer"},
                    "y": {"type": "integer"},
                    "button": {"type": "string", "enum": ["left", "right", "middle"]},
                    "clicks": {"type": "integer", "minimum": 1, "maximum": 5},
                    "amount": {"type": "integer", "description": "Scroll amount; positive is up"},
                },
                ["action"],
            ),
            desktop.mouse_control,
        ),
        Tool(
            "keyboard_control",
            "Control the keyboard: type text, press a key, or press a hotkey such as ctrl+s.",
            schema(
                {
                    "action": {"type": "string", "enum": ["type", "press", "hotkey"]},
                    "text": {"type": "string", "description": "Text to type"},
                    "keys": {"type": "string", "description": "Key or hotkey, e.g. enter or ctrl+shift+t"},
                },
                ["action"],
            ),
            desktop.keyboard_control,
        ),
        Tool(
            "clipboard",
            "Read from or write to the system clipboard.",
            schema(
                {
                    "action": {"type": "string", "enum": ["read", "write"]},
                    "text": {"type": "string", "description": "Text to copy when writing"},
                },
                ["action"],
            ),
            desktop.clipboard,
        ),
        Tool(
            "notify",
            "Show a desktop notification.",
            schema(
                {"title": {"type": "string"}, "message": {"type": "string"}},
                ["title", "message"],
            ),
            desktop.notify,
        ),
        Tool(
            "power",
            "Control machine power: lock, sleep, restart or shutdown.",
            schema(
                {
                    "action": {
                        "type": "string",
                        "enum": ["lock", "sleep", "restart", "shutdown"],
                    }
                },
                ["action"],
            ),
            desktop.power,
            requires_approval=True,
        ),
        Tool(
            "create_skill",
            "Permanently extend your own abilities by writing a Python skill file. The `code` must define a module-level TOOLS list. Each entry is a dict with: name (str), description (str), input_schema (JSON Schema object), func (callable), and optional requires_approval (bool). New tools become callable immediately.",
            schema(
                {
                    "name": {"type": "string", "description": "Skill file name"},
                    "description": {"type": "string", "description": "What the skill does"},
                    "code": {"type": "string", "description": "Python source defining TOOLS"},
                },
                ["name", "description", "code"],
            ),
            create_skill_tool,
            requires_approval=True,
        ),
        Tool(
            "delete_skill",
            "Delete a skill file from the skills directory.",
            schema({"name": {"type": "string"}}, ["name"]),
            skills.delete_skill_file,
        ),
        Tool(
            "list_skills",
            "List installed skills and their tools.",
            schema({}),
            skills.list_skills,
        ),
        Tool(
            "phone_control",
            "Control a connected Android phone over ADB. Actions: devices, info, tap, swipe, type, key, open, screenshot, apps, screen_text, push, pull, pair, connect. Use screen_text to read the visible UI with tap coordinates, then tap them. key accepts names such as back, home, recents, enter, power, volume_up. open accepts a package id or URL. apps takes an optional filter in target. push/pull use path as the local path and target as the remote path.",
            schema(
                {
                    "action": {
                        "type": "string",
                        "enum": [
                            "devices", "info", "tap", "swipe", "type", "key",
                            "open", "screenshot", "apps", "screen_text",
                            "push", "pull", "pair", "connect",
                        ],
                    },
                    "serial": {"type": "string", "description": "Device serial; optional with one device"},
                    "x": {"type": "integer"},
                    "y": {"type": "integer"},
                    "x2": {"type": "integer"},
                    "y2": {"type": "integer"},
                    "duration": {"type": "integer", "description": "Swipe duration in ms"},
                    "text": {"type": "string", "description": "Text to type"},
                    "key": {"type": "string", "description": "Key name or keycode"},
                    "target": {"type": "string", "description": "App/URL to open, package filter, or remote path"},
                    "path": {"type": "string", "description": "Local path for push/pull or screenshot output"},
                    "host": {"type": "string", "description": "Phone IP for wireless pair/connect"},
                    "port": {"type": "integer", "description": "Wireless debugging port"},
                    "code": {"type": "string", "description": "Wireless pairing code"},
                },
                ["action"],
            ),
            phone.control,
        ),
        Tool(
            "phone_shell",
            "Run any shell command inside the connected Android phone over ADB. This is unrestricted control of the phone: files, settings, apps, intents, content providers, UI automation, and on rooted devices su. Use it for anything the other phone tools do not cover.",
            schema(
                {
                    "command": {"type": "string"},
                    "serial": {"type": "string"},
                },
                ["command"],
            ),
            phone.shell,
            requires_approval=True,
        ),
        Tool(
            "phone_adb",
            "Run a raw adb command against the phone, such as root, remount, reboot, bootloader, forward, reverse, backup, restore, or bugreport. Pass only the arguments after adb.",
            schema(
                {
                    "args": {"type": "string", "description": "Arguments passed to adb"},
                    "serial": {"type": "string"},
                },
                ["args"],
            ),
            phone.adb,
            requires_approval=True,
        ),
        Tool(
            "phone_install",
            "Install or update an APK on the connected Android phone.",
            schema(
                {
                    "apk_path": {"type": "string"},
                    "serial": {"type": "string"},
                },
                ["apk_path"],
            ),
            phone.install,
            requires_approval=True,
        ),
        Tool(
            "phone_uninstall",
            "Uninstall an app from the connected Android phone. Set keep_data to preserve app data.",
            schema(
                {
                    "package": {"type": "string"},
                    "serial": {"type": "string"},
                    "keep_data": {"type": "boolean"},
                },
                ["package"],
            ),
            phone.uninstall,
            requires_approval=True,
        ),
    ]

    tools.extend(builtins)
    existing = {tool.name for tool in tools}
    for tool in skills.load_skills():
        if tool.name not in existing:
            tools.append(tool)
            existing.add(tool.name)
    return tools
