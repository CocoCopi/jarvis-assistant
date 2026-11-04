import uuid
from typing import Any

from .base import Completion, Provider, ToolCall, post_json


def _to_messages(
    messages: list[dict[str, Any]], system: str
) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    if system:
        out.append({"role": "system", "content": system})
    for message in messages:
        role = message["role"]
        if role in ("user", "tool"):
            entry: dict[str, Any] = {"role": role, "content": message["content"]}
            if role == "tool" and message.get("name"):
                entry["tool_name"] = message["name"]
            out.append(entry)
        elif role == "assistant":
            entry = {"role": "assistant", "content": message.get("content") or ""}
            calls = message.get("tool_calls", [])
            if calls:
                entry["tool_calls"] = [
                    {
                        "function": {
                            "name": call["name"],
                            "arguments": call.get("arguments") or {},
                        }
                    }
                    for call in calls
                ]
            out.append(entry)
    return out


class OllamaProvider(Provider):
    def complete(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        system: str,
    ) -> Completion:
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": _to_messages(messages, system),
            "stream": False,
            "options": {"temperature": self.temperature},
        }
        if tools:
            payload["tools"] = [
                {
                    "type": "function",
                    "function": {
                        "name": tool["name"],
                        "description": tool["description"],
                        "parameters": tool["input_schema"],
                    },
                }
                for tool in tools
            ]
        headers = {}
        if self.api_key:
            headers["authorization"] = f"Bearer {self.api_key}"
        data = post_json(f"{self.base_url}/api/chat", payload, headers=headers)

        message = data.get("message") or {}
        calls: list[ToolCall] = []
        for call in message.get("tool_calls") or []:
            function = call.get("function", {})
            arguments = function.get("arguments") or {}
            if isinstance(arguments, str):
                import json

                try:
                    arguments = json.loads(arguments)
                except json.JSONDecodeError:
                    arguments = {}
            calls.append(
                ToolCall(
                    call.get("id") or f"call_{uuid.uuid4().hex[:12]}",
                    function.get("name", ""),
                    arguments,
                )
            )
        return Completion(
            text=message.get("content") or "", tool_calls=calls, raw=data
        )
