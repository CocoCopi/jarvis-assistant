from typing import Any, Callable

from .providers import Completion, Provider
from .tools import Tool

Approver = Callable[[str, dict[str, Any]], bool]


class Agent:
    def __init__(
        self,
        provider: Provider,
        tools: list[Tool],
        system: str,
        on_text: Callable[[str], None] | None = None,
        on_tool: Callable[[str, dict[str, Any]], None] | None = None,
        approver: Approver | None = None,
        max_steps: int = 0,
    ) -> None:
        self.provider = provider
        self.tools = tools
        self.system = system
        self.on_text = on_text
        self.on_tool = on_tool
        self.approver = approver
        self.max_steps = max_steps
        self.history: list[dict[str, Any]] = []

    @property
    def tool_specs(self) -> list[dict]:
        return [tool.spec() for tool in self.tools]

    def reset(self) -> None:
        self.history.clear()

    def run(self, user_input: str) -> str:
        self.history.append({"role": "user", "content": user_input})
        last_text = ""
        steps = 0

        while not self.max_steps or steps < self.max_steps:
            steps += 1
            completion = self._complete()
            if completion.text:
                last_text = completion.text
                if self.on_text:
                    self.on_text(completion.text)

            calls = [
                {"id": call.id, "name": call.name, "arguments": call.arguments}
                for call in completion.tool_calls
            ]
            self.history.append(
                {"role": "assistant", "content": completion.text, "tool_calls": calls}
            )

            if not calls:
                return completion.text

            for call in calls:
                result = self._execute(call)
                self.history.append(
                    {
                        "role": "tool",
                        "tool_call_id": call["id"],
                        "name": call["name"],
                        "content": result,
                    }
                )

        return last_text or "I stopped after reaching my tool-step limit, sir."

    def _complete(self) -> Completion:
        return self.provider.complete(self.history, self.tool_specs, self.system)

    def _execute(self, call: dict[str, Any]) -> str:
        name = call["name"]
        arguments = call.get("arguments") or {}
        tool = next((tool for tool in self.tools if tool.name == name), None)
        if tool is None:
            return f"Unknown tool: {name}"
        if not isinstance(arguments, dict):
            return f"Invalid arguments for {name}: expected an object."

        if tool.requires_approval and self.approver is not None:
            if not self.approver(name, arguments):
                return "The user denied permission, so the action was not performed."

        if self.on_tool:
            self.on_tool(name, arguments)

        try:
            return str(tool.func(**arguments))
        except TypeError as exc:
            return f"Invalid arguments for {name}: {exc}"
        except Exception as exc:
            return f"Tool {name} failed: {exc}"
