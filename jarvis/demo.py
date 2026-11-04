import os
import shutil
import tempfile
from typing import Any

from .agent import Agent
from .providers.base import Completion, Provider, ToolCall
from .tools import build_tools

DEMO_SKILL = '''import random


def roll_dice(sides):
    sides = int(sides)
    if sides < 2:
        return "A die needs at least two sides."
    return f"Rolled a {sides}-sided die: {random.randint(1, sides)}."


TOOLS = [
    {
        "name": "roll_dice",
        "description": "Roll a die with the given number of sides.",
        "input_schema": {
            "type": "object",
            "properties": {"sides": {"type": "integer"}},
            "required": ["sides"],
            "additionalProperties": False,
        },
        "func": roll_dice,
    }
]
'''


class ScriptedProvider(Provider):
    def __init__(self, steps: list[Completion]) -> None:
        super().__init__("demo")
        self.steps = list(steps)
        self.index = 0

    def complete(self, messages, tools, system) -> Completion:
        if self.index >= len(self.steps):
            return Completion("Self-test finished, sir.")
        step = self.steps[self.index]
        self.index += 1
        return step


def _show_tool(name: str, arguments: dict[str, Any]) -> None:
    detail = arguments.get("command") or arguments.get("code") or arguments.get("name") or ""
    detail = " ".join(str(detail).split())
    if len(detail) > 120:
        detail = detail[:120] + "..."
    print(f"  [tool] {name} {detail}".rstrip(), flush=True)


def run_demo() -> int:
    directory = tempfile.mkdtemp(prefix="jarvis-demo-skills-")
    previous = os.environ.get("JARVIS_SKILLS_DIR")
    os.environ["JARVIS_SKILLS_DIR"] = directory

    tools = build_tools(speaker=lambda text: print(f"[timer] {text}", flush=True))
    steps = [
        Completion("", [ToolCall("d1", "get_time", {})]),
        Completion(
            "",
            [
                ToolCall(
                    "d2",
                    "run_python",
                    {"code": "total = sum(range(1, 11))\nprint(total)"},
                )
            ],
        ),
        Completion(
            "",
            [
                ToolCall(
                    "d3",
                    "create_skill",
                    {
                        "name": "dice",
                        "description": "Demo skill that rolls dice.",
                        "code": DEMO_SKILL,
                    },
                )
            ],
        ),
        Completion("", [ToolCall("d4", "roll_dice", {"sides": 20})]),
        Completion("", [ToolCall("d5", "system_status", {})]),
        Completion(
            "Self-test complete, sir. The clock, Python, my new dice skill, "
            "and the machine are all in order."
        ),
    ]
    agent = Agent(
        provider=ScriptedProvider(steps),
        tools=tools,
        system="Jarvis self-test.",
        on_tool=_show_tool,
        approver=None,
        max_steps=10,
    )

    print("JARVIS SELF-TEST")
    print("No API key needed: a scripted brain exercises the real tool pipeline.")
    print("Auto-approving tools for this demo.\n")
    try:
        response = agent.run(
            "Run a full self-test: check the time, run Python, create a dice "
            "skill, use it, and check system status."
        )
    except Exception as exc:
        print(f"Self-test failed: {exc}")
        return 1
    finally:
        if previous is None:
            os.environ.pop("JARVIS_SKILLS_DIR", None)
        else:
            os.environ["JARVIS_SKILLS_DIR"] = previous
        shutil.rmtree(directory, ignore_errors=True)

    print("\nTool results:")
    for message in agent.history:
        if message.get("role") != "tool":
            continue
        result = " ".join(str(message.get("content", "")).split())
        if len(result) > 200:
            result = result[:200] + "..."
        print(f"  [result] {message.get('name')}: {result}")

    print(f"\nJarvis: {response}")
    print(f"\nTools loaded during the run: {len(tools)} (includes the generated roll_dice skill)")
    return 0
