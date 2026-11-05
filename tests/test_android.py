import json
import sys
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "android" / "app" / "src" / "main" / "python"))

from jarvis_android import android_tools, bootstrap  # noqa: E402


class AndroidToolTests(unittest.TestCase):
    def setUp(self):
        self.tools = {tool.name: tool for tool in android_tools.build_android_tools()}

    def test_expected_tools_are_registered(self):
        expected = {
            "device_info",
            "android_shell",
            "android_ui",
            "android_screen",
            "android_node",
            "android_gesture",
            "android_text",
            "android_screenshot",
            "android_notifications",
            "android_apps",
            "android_intent",
            "android_open",
            "android_files",
            "android_clipboard",
            "android_media",
            "android_sms",
            "android_call",
            "android_contacts",
            "android_location",
            "android_install_apk",
            "android_uninstall",
            "android_device_admin",
            "android_python",
            "android_speak",
            "android_toast",
            "android_update_source",
            "create_skill",
            "delete_skill",
            "list_skills",
            "web_search",
            "fetch_url",
            "http_request",
        }
        self.assertTrue(expected.issubset(self.tools))
        self.assertEqual(len(self.tools), len(android_tools.build_android_tools()))

    def test_dangerous_tools_require_approval(self):
        for name in (
            "android_shell",
            "android_sms",
            "android_install_apk",
            "android_uninstall",
            "android_device_admin",
            "android_python",
            "android_update_source",
            "create_skill",
        ):
            self.assertTrue(self.tools[name].requires_approval, name)
        for name in (
            "android_ui",
            "android_screen",
            "android_node",
            "android_gesture",
            "android_text",
            "android_screenshot",
            "android_files",
            "android_call",
        ):
            self.assertFalse(self.tools[name].requires_approval, name)

    def test_shell_passes_root_flag(self):
        with mock.patch.object(android_tools, "call", return_value="rooted") as call:
            self.assertEqual(android_tools.shell("id", True), "rooted")
        call.assert_called_once_with("shell", "id", True)

    def test_ui_dump_uses_bridge(self):
        with mock.patch.object(android_tools, "call", return_value="screen") as call:
            self.assertEqual(android_tools.ui("dump"), "screen")
        call.assert_called_once_with("uiDump")

    def test_screen_state_uses_bridge(self):
        with mock.patch.object(android_tools, "call", return_value="screen") as call:
            self.assertEqual(android_tools.screen_state(True, False), "screen")
        call.assert_called_once_with("screenState", True, False)

    def test_node_actions_use_node_ids(self):
        with mock.patch.object(android_tools, "call", return_value="clicked") as call:
            self.assertEqual(android_tools.node_action("click", "node_4"), "clicked")
        call.assert_called_once_with("clickNode", "node_4", False)
        with mock.patch.object(android_tools, "call", return_value="typed") as call:
            self.assertEqual(android_tools.text_input("append", "!", "node_7"), "typed")
        call.assert_called_once_with("typeTextEx", "!", "append", "node_7")

    def test_settings_and_camera_use_bridge(self):
        with mock.patch.object(android_tools, "call", return_value="ok") as call:
            android_tools.device_settings("brightness", 40, "")
        call.assert_called_once_with("deviceSettings", "brightness", 40, "")
        with mock.patch.object(android_tools, "call", return_value="ok") as call:
            android_tools.camera("photo", "/tmp/a.jpg", "", 0)
        call.assert_called_once_with("camera", "photo", "/tmp/a.jpg", "", 0)
        with mock.patch.object(android_tools, "call", return_value="ok") as call:
            android_tools.share("hello", "", "")
        call.assert_called_once_with("share", "hello", "", "")

    def test_extended_file_actions_use_bridge(self):
        with mock.patch.object(android_tools, "call", return_value="ok") as call:
            android_tools.files("replace", "/tmp/a", find="x", replace="y")
        call.assert_called_once_with("fileReplace", "/tmp/a", "x", "y", True)
        with mock.patch.object(android_tools, "call", return_value="ok") as call:
            android_tools.files("download", "/tmp/a", url="https://example.com/a")
        call.assert_called_once_with("downloadUrl", "https://example.com/a", "/tmp/a")

    def test_update_applies_through_bridge(self):
        with mock.patch.object(android_tools, "call", return_value="updated") as call:
            self.assertEqual(android_tools.update_source(), "updated")
        call.assert_called_once_with("applyCodeUpdate")

    def test_specs_are_json_serializable(self):
        payload = [tool.spec() for tool in android_tools.build_android_tools()]
        self.assertIn("android_shell", {item["name"] for item in payload})
        json.dumps(payload)


class BootstrapTests(unittest.TestCase):
    def test_status_is_json(self):
        status = json.loads(bootstrap.status_json())
        self.assertIn("source", status)

    def test_unloaded_engine_returns_clear_error(self):
        with mock.patch.object(bootstrap, "_engine", None):
            result = json.loads(bootstrap.chat("hello"))
        self.assertFalse(result["ok"])
        self.assertIn("not loaded", result["error"])


if __name__ == "__main__":
    unittest.main()
