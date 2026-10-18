"""long: 失败门禁必须留下阶段/收尾证据，ADB 退出 0 不能掩盖设备上的 JUnit 失败。"""

import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

import verify_mcp_live_acceptance as live
from verify_android_live_acceptance import PHASES


class McpLiveAcceptanceAuditTest(unittest.TestCase):
    def test_generic_descriptor_keeps_usb_protocol_and_requires_its_own_evidence(self):
        spec = {"test": "public.Test", "method": "actual", "realArg": "stage292RealRun",
                "portArg": "stage292ConfigPort", "ready": "STAGE292_CONFIG_READY",
                "evidence": "STAGE292_EVIDENCE NATIVE ", "progress": "STAGE292_RUN"}
        for evidence, expected in (("STAGE292_EVIDENCE NATIVE public=true", True),
                                   ("STAGE292_EVIDENCE WORKSPACE public=true", False),
                                   ("STAGE286_EVIDENCE NATIVE public=true", False)):
            raw = "STAGE292_CONFIG_READY\n" + evidence + "\nOK (1 test)\n"
            process = SimpleNamespace(stdout=io.StringIO(raw), wait=lambda **_: 0, poll=lambda: 0)
            connection = MagicMock()
            connection.__enter__.return_value.recv.return_value = b"\x01"
            with tempfile.TemporaryDirectory() as folder, patch.object(live, "adb", side_effect=["34567", ""]), \
                    patch.object(live.subprocess, "Popen", return_value=process) as start, \
                    patch.object(live.socket, "create_connection", return_value=connection):
                result = live.phase("public", 19892, b"{}", (), Path(folder), descriptor=spec)
            self.assertEqual(expected, result["passed"])
            self.assertIn("public.Test#actual", start.call_args.args[0])
            self.assertIn("stage292ConfigPort", start.call_args.args[0])
            connection.__enter__.return_value.sendall.assert_called_once_with(b"\x00\x00\x00\x02{}")

    def test_matching_stage_but_wrong_api_or_device_action_is_rejected(self):
        for name, wrong in (("chat-stream", "STAGE292_EVIDENCE CHAT mode=CHAT_COMPLETIONS streaming=false marker=true"),
                            ("responses", "STAGE292_EVIDENCE CHAT mode=CHAT_COMPLETIONS streaming=false marker=true"),
                            ("attachment-image", "STAGE292_EVIDENCE ATTACHMENT type=text actualContent=true"),
                            ("device-tap", "STAGE292_EVIDENCE DEVICE_REBIND originalSelectionRestored=true\n"
                                           "STAGE292_EVIDENCE DEVICE action=type roomPassed=true")):
            with self.subTest(phase=name):
                result = live.instrumentation_verdict(0, True, [wrong + "\n", "OK (1 test)\n"], PHASES[name])
                self.assertFalse(result["passed"])
                self.assertTrue(result["missingEvidence"])

    def test_product_ui_requires_restore_after_all_business_evidence(self):
        records = [marker + "verified=true\n" for marker in PHASES["product-ui"]["requiredEvidence"]]
        self.assertTrue(live.instrumentation_verdict(0, True, records + ["OK (1 test)\n"], PHASES["product-ui"])["passed"])
        result = live.instrumentation_verdict(0, True, records[:-1] + ["OK (1 test)\n"], PHASES["product-ui"])
        self.assertFalse(result["passed"])
        self.assertEqual(["STAGE292_EVIDENCE PRODUCT_RESTORE "], result["missingEvidence"])

    def test_startup_and_cleanup_failures_keep_both_causes(self):
        with tempfile.TemporaryDirectory() as folder, \
                patch.object(live, "adb", side_effect=["34567", RuntimeError("device offline")]), \
                patch.object(live.subprocess, "Popen", side_effect=OSError("instrument startup failed")):
            result = live.phase("sse-success", 19886, b"{}", (), Path(folder))
        self.assertFalse(result["passed"])
        self.assertEqual("sse-success", result["phase"])
        self.assertIn("instrument startup failed", result["error"])
        self.assertIn("device offline", result["cleanupErrors"][0])

    def test_adb_exit_zero_does_not_mask_junit_failure(self):
        raw = "STAGE286_CONFIG_READY port=19886\nFAILURES!!!\nTests run: 1, Failures: 1\n"
        process = SimpleNamespace(stdout=io.StringIO(raw), wait=lambda **_: 0, poll=lambda: 0)
        connection = MagicMock()
        connection.__enter__.return_value.recv.return_value = b"\x01"
        with tempfile.TemporaryDirectory() as folder, patch.object(live, "adb", side_effect=["34567", ""]), \
                patch.object(live.subprocess, "Popen", return_value=process), \
                patch.object(live.socket, "create_connection", return_value=connection):
            result = live.phase("context7-transport", 19886, b"{}", (), Path(folder))
        self.assertEqual(0, result["returnCode"])
        self.assertTrue(result["configAcknowledged"])
        self.assertFalse(result["passed"])

    def test_unreadable_original_config_still_writes_failed_report(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "audit"
            config = Path(folder) / "missing-config.json"
            with patch("sys.argv", ["verify", "--phase", "sse-success", "--output", str(output),
                                    "--context7-config", str(config)]), \
                    patch.object(live, "load_inputs", return_value=(b"{}", (), "original-hash")), \
                    patch.object(live, "adb", side_effect=["begonia", "36"]), \
                    patch.object(live, "phase", return_value={"phase": "sse-success", "passed": True}):
                self.assertEqual(1, live.main())
            report = json.loads((output / "report.json").read_text())
        self.assertFalse(report["completed"])
        self.assertFalse(report["context7ConfigUnchanged"])
        self.assertEqual("原 Context7 配置无法回读", report["configAuditError"])
        self.assertEqual(1, len(report["phases"]))


if __name__ == "__main__":
    unittest.main()
