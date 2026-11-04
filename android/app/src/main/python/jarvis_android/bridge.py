"""Android bridge helpers for the Jarvis Python engine."""

from __future__ import annotations


def call(method: str, *args):
    try:
        from java import jclass

        bridge = jclass("com.jarvis.assistant.bridge.DeviceBridge")
        return getattr(bridge, method)(*args)
    except Exception as exc:
        return f"ERROR: {exc}"


def request_approval(name: str, detail: str) -> bool:
    try:
        from java import jclass

        bridge = jclass("com.jarvis.assistant.bridge.DeviceBridge")
        return bool(bridge.requestApproval(name, detail))
    except Exception:
        return False
