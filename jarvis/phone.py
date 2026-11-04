import os
import re
import shlex
import shutil
import subprocess
from datetime import datetime
from typing import Any

ADB_CANDIDATES = (
    "~/.local/bin/adb",
    "~/platform-tools/adb",
    "$ANDROID_HOME/platform-tools/adb",
    "$ANDROID_SDK_ROOT/platform-tools/adb",
)


def adb_path() -> str | None:
    override = os.environ.get("ADB")
    if override and os.path.exists(os.path.expanduser(override)):
        return os.path.expanduser(override)
    found = shutil.which("adb")
    if found:
        return found
    for candidate in ADB_CANDIDATES:
        path = os.path.expanduser(os.path.expandvars(candidate))
        if os.path.exists(path):
            return path
    return None


def _require_adb() -> str:
    path = adb_path()
    if not path:
        raise RuntimeError(
            "adb is not installed. Install Android platform-tools:\n"
            "  Debian/Ubuntu: sudo apt install adb\n"
            "  macOS: brew install android-platform-tools\n"
            "Then enable USB debugging on the phone and accept the RSA prompt."
        )
    return path


def _adb(
    args: list[str],
    serial: str | None = None,
    timeout: float = 60.0,
    binary: bool = False,
) -> Any:
    command = [_require_adb()]
    if serial:
        command.extend(["-s", serial])
    command.extend(args)
    try:
        result = subprocess.run(command, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(f"adb command timed out: {' '.join(args)}") from exc
    if binary:
        return result.stdout
    output = (result.stdout + result.stderr).decode("utf-8", errors="replace").strip()
    return result.returncode, output


def _shell(serial: str, command: str, timeout: float = 60.0) -> tuple[int, str]:
    return _adb(["shell", command], serial=serial, timeout=timeout)


def _pick_serial(serial: str = "") -> str:
    if serial:
        return serial
    _, output = _adb(["devices"])
    ready = []
    unauthorized = []
    for line in output.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            ready.append(parts[0])
        elif len(parts) >= 2 and parts[1] == "unauthorized":
            unauthorized.append(parts[0])
    if not ready and unauthorized:
        raise RuntimeError(
            "The phone is connected but unauthorized. Unlock it and accept the "
            "'Allow USB debugging' prompt, then try again."
        )
    if not ready:
        raise RuntimeError(
            "No Android device is connected. Enable Developer Options and USB "
            "debugging, plug in the phone, accept the RSA prompt. For wireless "
            "debugging use action=pair then action=connect."
        )
    if len(ready) > 1:
        raise RuntimeError(
            "Multiple devices are connected: " + ", ".join(ready) + ". Pass a serial."
        )
    return ready[0]


def _escape_shell(value: str) -> str:
    return "'" + value.replace("'", "'\\''") + "'"


def _default_screenshot() -> str:
    directory = os.path.expanduser("~/.jarvis/screenshots")
    os.makedirs(directory, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    return os.path.join(directory, f"phone-{stamp}.png")


def control(
    action: str,
    serial: str = "",
    x: int = 0,
    y: int = 0,
    x2: int = 0,
    y2: int = 0,
    duration: int = 300,
    text: str = "",
    key: str = "",
    target: str = "",
    path: str = "",
    host: str = "",
    port: int = 5555,
    code: str = "",
) -> str:
    action = action.lower()
    try:
        if action == "devices":
            _, output = _adb(["devices", "-l"])
            return output or "No devices listed."
        if action == "pair":
            if not host:
                return "A host/IP is required for pairing."
            _, output = _adb(["pair", f"{host}:{port}", code], timeout=60)
            return output or "Pairing command sent."
        if action == "connect":
            if not host:
                return "A host/IP is required."
            _, output = _adb(["connect", f"{host}:{port}"], timeout=60)
            return output or "Connect command sent."

        device = _pick_serial(serial)

        if action == "info":
            _, model = _shell(device, "getprop ro.product.model")
            _, maker = _shell(device, "getprop ro.product.manufacturer")
            _, release = _shell(device, "getprop ro.build.version.release")
            _, battery = _shell(
                device, "dumpsys battery | grep -E 'level:|status:'"
            )
            _, storage = _shell(device, "df -h /data | tail -n 1")
            _, address = _shell(
                device,
                "ip -o -4 addr show wlan0 2>/dev/null | awk '{print $4}'",
            )
            return (
                f"Phone: {maker} {model}, Android {release}. "
                f"Battery: {battery or 'unknown'}. "
                f"Storage: {storage or 'unknown'}. "
                f"Wi-Fi: {address or 'not connected'}."
            )
        if action == "tap":
            _, output = _shell(device, f"input tap {int(x)} {int(y)}")
            return output or f"Tapped {x}, {y}."
        if action == "swipe":
            _, output = _shell(
                device,
                f"input swipe {int(x)} {int(y)} {int(x2)} {int(y2)} {int(duration)}",
            )
            return output or f"Swiped from {x},{y} to {x2},{y2}."
        if action == "type":
            if not text:
                return "No text supplied."
            encoded = text.replace(" ", "%s").replace("'", "'\\''")
            _, output = _shell(device, f"input text '{encoded}'")
            return output or f"Typed {len(text)} characters on the phone."
        if action == "key":
            if not key:
                return "No key supplied."
            name = key.strip()
            if not name.isdigit() and not name.upper().startswith("KEYCODE_"):
                name = "KEYCODE_" + name.upper()
            _, output = _shell(device, f"input keyevent {name}")
            return output or f"Pressed {key}."
        if action == "open":
            if not target:
                return "Nothing to open."
            if "://" in target or target.startswith(("tel:", "sms:", "mailto:")):
                command = (
                    "am start -a android.intent.action.VIEW -d "
                    + _escape_shell(target)
                )
            else:
                command = (
                    "monkey -p " + _escape_shell(target)
                    + " -c android.intent.category.LAUNCHER 1"
                )
            _, output = _shell(device, command)
            return output or f"Opened {target}."
        if action == "screenshot":
            data = _adb(["exec-out", "screencap", "-p"], serial=device, binary=True)
            if not data.startswith(b"\x89PNG"):
                return "The phone returned no PNG screenshot."
            destination = os.path.expanduser(path) if path else _default_screenshot()
            parent = os.path.dirname(os.path.abspath(destination))
            if parent:
                os.makedirs(parent, exist_ok=True)
            with open(destination, "wb") as handle:
                handle.write(data)
            return f"Phone screenshot saved to {destination} ({len(data)} bytes)."
        if action == "apps":
            _, output = _shell(device, "pm list packages")
            packages = [
                line.split(":", 1)[-1].strip()
                for line in output.splitlines()
                if line.startswith("package:")
            ]
            if target:
                packages = [name for name in packages if target.lower() in name.lower()]
            packages.sort()
            if not packages:
                return f"No packages matched {target!r}."
            shown = packages[:200]
            suffix = "" if len(packages) <= 200 else f"\n... {len(packages) - 200} more"
            return "\n".join(shown) + suffix
        if action == "screen_text":
            _shell(device, "uiautomator dump /sdcard/jarvis_ui.xml")
            _, xml = _adb(
                ["exec-out", "cat", "/sdcard/jarvis_ui.xml"], serial=device
            )
            lines = []
            for node in re.findall(r"<node[^>]+>", xml):
                label = re.search(r'\btext="([^"]*)"', node)
                description = re.search(r'content-desc="([^"]*)"', node)
                bounds = re.search(
                    r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node
                )
                value = (label.group(1) if label else "") or (
                    description.group(1) if description else ""
                )
                value = value.strip()
                if not value or not bounds:
                    continue
                x1, y1, x2, y2 = (int(part) for part in bounds.groups())
                lines.append(f"{value} @ ({(x1 + x2) // 2}, {(y1 + y2) // 2})")
            if not lines:
                return "No visible text could be read from the phone screen."
            return "\n".join(lines[:150])
        if action == "push":
            local = os.path.expanduser(path)
            if not os.path.exists(local):
                return f"Local path does not exist: {local}"
            if not target:
                return "A remote destination path is required."
            _, output = _adb(["push", local, target], serial=device, timeout=300)
            return output or f"Pushed {local} to {target}."
        if action == "pull":
            if not target:
                return "A remote source path is required."
            local = os.path.expanduser(path) if path else os.path.basename(target.rstrip("/"))
            _, output = _adb(["pull", target, local], serial=device, timeout=300)
            return output or f"Pulled {target} to {local}."
    except Exception as exc:
        return f"Phone action failed: {exc}"
    return f"Unknown phone action: {action}"


def shell(command: str, serial: str = "") -> str:
    try:
        device = _pick_serial(serial)
        code, output = _shell(device, command, timeout=180)
    except Exception as exc:
        return f"Phone shell failed: {exc}"
    if not output:
        output = "(no output)"
    if len(output) > 6000:
        output = output[:6000] + "\n[truncated]"
    return f"Exit code {code}. {output}"


def adb(args: str, serial: str = "") -> str:
    try:
        device = _pick_serial(serial)
        parts = shlex.split(args)
        code, output = _adb(parts, serial=device, timeout=180)
    except Exception as exc:
        return f"adb command failed: {exc}"
    if not output:
        output = "(no output)"
    if len(output) > 6000:
        output = output[:6000] + "\n[truncated]"
    return f"Exit code {code}. {output}"


def install(apk_path: str, serial: str = "") -> str:
    local = os.path.expanduser(apk_path)
    if not os.path.exists(local):
        return f"APK not found: {local}"
    try:
        device = _pick_serial(serial)
        code, output = _adb(["install", "-r", local], serial=device, timeout=600)
    except Exception as exc:
        return f"Phone install failed: {exc}"
    return output or f"Install exit code {code}."


def uninstall(package: str, serial: str = "", keep_data: bool = False) -> str:
    if not package:
        return "A package name is required."
    try:
        device = _pick_serial(serial)
        args = ["uninstall"] + (["-k"] if keep_data else []) + [package]
        code, output = _adb(args, serial=device, timeout=300)
    except Exception as exc:
        return f"Phone uninstall failed: {exc}"
    return output or f"Uninstall exit code {code}."


def device_summary() -> str:
    path = adb_path()
    if not path:
        return "adb not installed"
    try:
        _, output = _adb(["devices"], timeout=15)
    except Exception as exc:
        return f"adb error: {exc}"
    ready = [
        line.split()[0]
        for line in output.splitlines()[1:]
        if len(line.split()) >= 2 and line.split()[1] == "device"
    ]
    if not ready:
        return "no devices connected"
    return f"{len(ready)} device(s): {', '.join(ready)}"
