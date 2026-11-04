"""Selects bundled or downloaded Jarvis code and loads the Android engine."""

from __future__ import annotations

import importlib
import json
import os
import shutil
import sys
import traceback
from typing import Any

_PACKAGE_PATHS = list(sys.modules["jarvis_android"].__path__)
_engine: Any = None
_status: dict[str, Any] = {"ok": False, "source": "none"}


def _code_root(files_dir: str) -> str:
    return os.path.join(files_dir, "code")


def _read_json(path: str) -> dict[str, Any] | None:
    try:
        with open(path) as handle:
            value = json.load(handle)
        return value if isinstance(value, dict) else None
    except Exception:
        return None


def _candidates(files_dir: str) -> list[tuple[str | None, str, str]]:
    root = _code_root(files_dir)
    result: list[tuple[str | None, str, str]] = []
    pending = _read_json(os.path.join(root, "pending.json"))
    if pending and pending.get("dir") and os.path.isdir(str(pending["dir"])):
        result.append((str(pending["dir"]), str(pending.get("version") or "pending"), "pending"))
    current = _read_json(os.path.join(root, "current.json"))
    if current and current.get("dir") and os.path.isdir(str(current["dir"])):
        path = str(current["dir"])
        if all(path != item[0] for item in result):
            result.append((path, str(current.get("version") or "current"), "current"))
    result.append((None, "bundled", "bundled"))
    return result


def _purge() -> None:
    for name in list(sys.modules):
        if name == "jarvis" or name.startswith("jarvis."):
            sys.modules.pop(name, None)
        elif name in (
            "jarvis_android.engine",
            "jarvis_android.android_tools",
            "jarvis_android.bridge",
        ):
            sys.modules.pop(name, None)


def _activate(path: str | None) -> None:
    package = sys.modules.get("jarvis_android")
    if package is None:
        return
    if path:
        package.__path__ = [os.path.join(path, "jarvis_android")] + list(_PACKAGE_PATHS)
    else:
        package.__path__ = list(_PACKAGE_PATHS)


def _clear_code_paths(files_dir: str) -> None:
    prefix = _code_root(files_dir)
    sys.path[:] = [entry for entry in sys.path if not entry.startswith(prefix)]


def start(settings_json: str, files_dir: str) -> str:
    global _engine, _status
    settings = json.loads(settings_json or "{}")
    home = str(settings.get("home") or files_dir)
    os.environ["HOME"] = home
    os.environ["JARVIS_SKILLS_DIR"] = str(
        settings.get("skills_dir") or os.path.join(home, "skills")
    )
    _clear_code_paths(files_dir)
    _engine = None
    last_error = ""
    for path, version, kind in _candidates(files_dir):
        _purge()
        _activate(path)
        if path:
            sys.path.insert(0, path)
        try:
            engine = importlib.import_module("jarvis_android.engine")
            info = engine.start(settings) or {}
            _engine = engine
            _status = {
                "ok": True,
                "source": kind,
                "version": version,
                "dir": path or "bundled",
                **info,
            }
            if kind == "pending" and path:
                try:
                    _promote(files_dir, path, version)
                except Exception as exc:
                    _status["promote_error"] = f"{type(exc).__name__}: {exc}"
            return json.dumps(_status, ensure_ascii=False)
        except Exception as exc:
            last_error = f"{version}: {type(exc).__name__}: {exc}\n{traceback.format_exc()}"
            if path and sys.path and sys.path[0] == path:
                sys.path.pop(0)
            _activate(None)
            if kind == "pending":
                _discard(files_dir)
    _status = {"ok": False, "source": "none", "error": last_error[-4000:]}
    return json.dumps(_status, ensure_ascii=False)


def _promote(files_dir: str, path: str, version: str) -> None:
    root = _code_root(files_dir)
    current_path = os.path.join(root, "current.json")
    previous = _read_json(current_path)
    payload = {"dir": path, "version": version, "previous": previous}
    temporary = current_path + ".tmp"
    with open(temporary, "w") as handle:
        json.dump(payload, handle)
    os.replace(temporary, current_path)
    pending = os.path.join(root, "pending.json")
    try:
        os.unlink(pending)
    except OSError:
        pass


def _discard(files_dir: str) -> None:
    root = _code_root(files_dir)
    pending_path = os.path.join(root, "pending.json")
    pending = _read_json(pending_path)
    if pending and pending.get("dir"):
        shutil.rmtree(str(pending["dir"]), ignore_errors=True)
    try:
        os.unlink(pending_path)
    except OSError:
        pass


def _error(message: str) -> str:
    return json.dumps({"ok": False, "error": message, "tools": []}, ensure_ascii=False)


def chat(text: str) -> str:
    if _engine is None:
        return _error("Jarvis engine is not loaded.")
    try:
        return _engine.chat(text)
    except Exception as exc:
        return _error(f"{type(exc).__name__}: {exc}")


def reset() -> str:
    if _engine is None:
        return "Engine is not loaded."
    try:
        return _engine.reset()
    except Exception as exc:
        return f"Reset failed: {exc}"


def tools_json() -> str:
    if _engine is None:
        return "[]"
    try:
        return _engine.tools_json()
    except Exception:
        return "[]"


def status_json() -> str:
    return json.dumps(_status, ensure_ascii=False)


def self_test() -> str:
    if _engine is None:
        return _error("Engine is not loaded.")
    try:
        return _engine.self_test()
    except Exception as exc:
        return _error(f"{type(exc).__name__}: {exc}")
