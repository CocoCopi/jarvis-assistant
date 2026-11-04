import json
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
            if role == "tool":
                out.append(
                    {
                        "role": "tool",
                        "tool_call_id": message["tool_call_id"],
                        "content": message["content"],
                    }
                )
            else:
                out.append({"role": "user", "content": message["content"]})
        elif role == "assistant":
            entry: dict[str, Any] = {
                "role": "assistant",
                "content": message.get("content") or None,
            }
            calls = message.get("tool_calls", [])
            if calls:
                entry["tool_calls"] = [
                    {
                        "id": call["id"],
                        "type": "function",
                        "function": {
                            "name": call["name"],
                            "arguments": json.dumps(call.get("arguments") or {}),
                        },
                    }
                    for call in calls
                ]
            out.append(entry)
    return out


class OpenAIProvider(Provider):
    def complete(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]],
        system: str,
    ) -> Completion:
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": _to_messages(messages, system),
            "temperature": self.temperature,
            "max_tokens": self.max_tokens,
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
        data = post_json(f"{self.base_url}/chat/completions", payload, headers=headers)

        try:
            message = data["choices"][0]["message"]
        except (KeyError, IndexError) as exc:
            raise ValueError(f"unexpected openai response: {data}") from exc

        calls: list[ToolCall] = []
        for call in message.get("tool_calls") or []:
            function = call.get("function", {})
            try:
                arguments = json.loads(function.get("arguments") or "{}")
            except json.JSONDecodeError:
                arguments = {}
            calls.append(
                ToolCall(call.get("id", ""), function.get("name", ""), arguments)
            )
        return Completion(
            text=message.get("content") or "", tool_calls=calls, raw=data
        )
