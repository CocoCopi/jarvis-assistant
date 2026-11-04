import os
import shutil
import subprocess
import sys
from datetime import datetime
from typing import Any


def _pyautogui() -> Any:
    try:
        import pyautogui
    except ImportError as exc:
        raise RuntimeError(
            "Desktop control needs the optional 'pyautogui' package:\n"
            "  pip install pyautogui pillow\n"
            "On Linux also install python3-tk and a screenshot tool such as scrot."
        ) from exc
    try:
        pyautogui.FAILSAFE = True
    except Exception:
        pass
    return pyautogui


def _default_path(directory: str, prefix: str, suffix: str) -> str:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    return os.path.join(directory, f"{prefix}-{stamp}{suffix}")


def screenshot(path: str = "") -> str:
    directory = os.path.expanduser("~/.jarvis/screenshots")
    os.makedirs(directory, exist_ok=True)
    target = os.path.expanduser(path) if path else _default_path(directory, "shot", ".png")
    parent = os.path.dirname(os.path.abspath(target))
    if parent:
        os.makedirs(parent, exist_ok=True)

    try:
        pyautogui = _pyautogui()
        pyautogui.screenshot().save(target)
        return f"Screenshot saved to {target}."
    except Exception:
        pass

    commands: list[list[str]] = []
    if sys.platform == "darwin":
        commands.append(["screencapture", "-x", target])
    else:
        if os.environ.get("WAYLAND_DISPLAY") and shutil.which("grim"):
            commands.append(["grim", target])
        if shutil.which("scrot"):
            commands.append(["scrot", "-o", target])
        if shutil.which("gnome-screenshot"):
            commands.append(["gnome-screenshot", "-f", target])
        if shutil.which("import"):
            commands.append(["import", "-window", "root", target])

    for command in commands:
        try:
            subprocess.run(
                command,
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=20,
            )
            if os.path.exists(target):
                return f"Screenshot saved to {target}."
        except Exception:
            continue
    return "I could not take a screenshot. Install pyautogui or a screenshot tool."


def mouse_control(
    action: str,
    x: int | None = None,
    y: int | None = None,
    button: str = "left",
    clicks: int = 1,
    amount: int = 0,
) -> str:
    pyautogui = _pyautogui()
    action = action.lower()
    try:
        if action == "position":
            position = pyautogui.position()
            return f"The pointer is at {position.x}, {position.y}."
        if action == "move":
            if x is None or y is None:
                return "Move needs x and y coordinates."
            pyautogui.moveTo(int(x), int(y), duration=0.2)
            return f"Moved the pointer to {x}, {y}."
        if action in ("click", "double_click", "right_click", "middle_click"):
            if x is not None and y is not None:
                pyautogui.moveTo(int(x), int(y), duration=0.2)
            if action == "double_click":
                pyautogui.doubleClick(button=button)
            elif action == "right_click":
                pyautogui.rightClick()
            elif action == "middle_click":
                pyautogui.middleClick()
            else:
                pyautogui.click(button=button, clicks=max(1, int(clicks)))
            return f"Performed {action.replace('_', ' ')}."
        if action == "scroll":
            if x is not None and y is not None:
                pyautogui.moveTo(int(x), int(y), duration=0.2)
            pyautogui.scroll(int(amount))
            return f"Scrolled by {amount}."
    except Exception as exc:
        return f"Mouse action failed: {exc}"
    return f"Unknown mouse action: {action}"


def keyboard_control(action: str, text: str = "", keys: str = "") -> str:
    pyautogui = _pyautogui()
    action = action.lower()
    try:
        if action == "type":
            if not text:
                return "Nothing to type."
            pyautogui.write(text, interval=0.02)
            return f"Typed {len(text)} characters."
        if action == "press":
            if not keys:
                return "No key given."
            pyautogui.press(keys)
            return f"Pressed {keys}."
        if action == "hotkey":
            if not keys:
                return "No keys given."
            combination = [part.strip() for part in keys.split("+") if part.strip()]
            pyautogui.hotkey(*combination)
            return f"Pressed {'+'.join(combination)}."
    except Exception as exc:
        return f"Keyboard action failed: {exc}"
    return f"Unknown keyboard action: {action}"


def _clipboard_command(write: bool) -> list[str] | None:
    if sys.platform == "darwin":
        return ["pbcopy"] if write else ["pbpaste"]
    if sys.platform.startswith("win"):
        script = (
            "Set-Clipboard -Value ([Console]::In.ReadToEnd())"
            if write
            else "Get-Clipboard"
        )
        return ["powershell", "-NoProfile", "-Command", script]
    if shutil.which("wl-copy") and write:
        return ["wl-copy"]
    if shutil.which("wl-paste") and not write:
        return ["wl-paste", "--no-newline"]
    if shutil.which("xclip"):
        return ["xclip", "-selection", "clipboard"] + (["-i"] if write else ["-o"])
    if shutil.which("xsel"):
        return ["xsel", "--clipboard"] + (["--input"] if write else ["--output"])
    return None


def clipboard(action: str, text: str = "") -> str:
    action = action.lower()
    if action == "write":
        try:
            import pyperclip

            pyperclip.copy(text)
            return "Copied to the clipboard."
        except Exception:
            pass
        command = _clipboard_command(write=True)
        if not command:
            return "No clipboard tool found. Install pyperclip or xclip."
        try:
            subprocess.run(command, input=text.encode(), check=True, timeout=5)
            return "Copied to the clipboard."
        except Exception as exc:
            return f"Clipboard write failed: {exc}"
    if action == "read":
        try:
            import pyperclip

            return pyperclip.paste() or "(clipboard is empty)"
        except Exception:
            pass
        command = _clipboard_command(write=False)
        if not command:
            return "No clipboard tool found. Install pyperclip or xclip."
        try:
            result = subprocess.run(command, capture_output=True, check=True, timeout=5)
            return result.stdout.decode(errors="replace") or "(clipboard is empty)"
        except Exception as exc:
            return f"Clipboard read failed: {exc}"
    return f"Unknown clipboard action: {action}"


def notify(title: str, message: str) -> str:
    if sys.platform == "darwin":
        script = (
            f'display notification {message!r} with title {title!r}'
        )
        command = ["osascript", "-e", script]
    elif sys.platform.startswith("win"):
        command = [
            "powershell",
            "-NoProfile",
            "-Command",
            f'[System.Windows.Forms.MessageBox]::Show({message!r}, {title!r})',
        ]
    else:
        command = ["notify-send", title, message]
    if not shutil.which(command[0]):
        return f"Notification tool {command[0]} is not installed."
    try:
        subprocess.run(
            command,
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=10,
        )
        return "Notification sent."
    except Exception as exc:
        return f"Notification failed: {exc}"


def power(action: str) -> str:
    action = action.lower()
    commands: dict[str, list[list[str]]] = {}

    if sys.platform == "darwin":
        commands = {
            "lock": [["pmset", "displaysleepnow"]],
            "sleep": [["pmset", "sleepnow"]],
            "restart": [["osascript", "-e", 'tell app "System Events" to restart']],
            "shutdown": [["osascript", "-e", 'tell app "System Events" to shut down']],
        }
    elif sys.platform.startswith("win"):
        commands = {
            "lock": [["rundll32.exe", "user32.dll,LockWorkStation"]],
            "sleep": [["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"]],
            "restart": [["shutdown", "/r", "/t", "0"]],
            "shutdown": [["shutdown", "/s", "/t", "0"]],
        }
    else:
        commands = {
            "lock": [
                ["loginctl", "lock-session"],
                ["xdg-screensaver", "lock"],
                ["gnome-screensaver-command", "-l"],
            ],
            "sleep": [["systemctl", "suspend"], ["loginctl", "suspend"]],
            "restart": [["systemctl", "reboot"]],
            "shutdown": [["systemctl", "poweroff"]],
        }

    if action not in commands:
        return f"Unknown power action: {action}. Use lock, sleep, restart or shutdown."

    for command in commands[action]:
        if not shutil.which(command[0]):
            continue
        try:
            subprocess.run(
                command,
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=10,
            )
            return f"Performed {action}."
        except Exception:
            continue
    return f"I could not {action} this machine with the available tools."
