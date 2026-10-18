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

    def test_overlay_rejects_other_decisions_and_missing_restore_even_with_junit_success(self):
        for name in ("overlay-tap-approve", "overlay-type-approve", "overlay-tap-deny", "overlay-tap-window-change"):
            spec = PHASES[name]
            business, restore = spec["requiredEvidence"]
            records = [business + "verified=true\n", restore + "verified=true\n", "OK (1 test)\n"]
            with self.subTest(phase=name):
                self.assertTrue(live.instrumentation_verdict(0, True, records, spec)["passed"])
                # long: 同一生产浮层的其他决定与 JUnit 成功都不能替代本项动作；恢复缺失也必须留下失败记录。
                wrong_decision = "deny" if "decision=approve " in business else "approve"
                wrong = business.split("decision=", 1)[0] + f"decision={wrong_decision} verified=true\n"
                for incomplete in ([wrong, records[1], records[2]], [records[0], records[2]]):
                    result = live.instrumentation_verdict(0, True, incomplete, spec)
                    self.assertFalse(result["passed"])
                    self.assertTrue(result["missingEvidence"])

    def test_workflow_requires_matching_case_scope_restore_and_ack(self):
        for name in ("workflow-create-readonly", "workflow-device-approve", "workflow-device-deny"):
            spec = PHASES[name]
            records = [marker + "verified=true\n" for marker in spec["requiredEvidence"]] + ["OK (1 test)\n"]
            with self.subTest(phase=name):
                self.assertTrue(live.instrumentation_verdict(0, True, records, spec)["passed"])
                # long: 预置设备定义、编辑器创建、错误决定和恢复缺失不能互相替代；缺 USB ACK 时也不能采用旧日志成功。
                wrong = records.copy()
                other = "device-deny" if name != "workflow-device-deny" else "device-approve"
                wrong[1] = f"STAGE294_EVIDENCE WORKFLOW case={other} verified=true\n"
                for incomplete in (wrong, records[1:], records[:2] + records[3:]):
                    self.assertFalse(live.instrumentation_verdict(0, True, incomplete, spec)["passed"])
                self.assertFalse(live.instrumentation_verdict(0, False, records, spec)["passed"])

    def test_settings_search_requires_business_recreate_restore_and_ack(self):
        spec = PHASES["workflow-settings-search"]
        records = [marker + "verified=true\n" for marker in spec["requiredEvidence"]] + ["OK (1 test)\n"]
        self.assertTrue(live.instrumentation_verdict(0, True, records, spec)["passed"])
        # long: 打开设置、旧阶段回执和 JUnit 都不能替代搜索输入/目标判定或重建证据；缺任意一段或 USB ACK 必须失败。
        for index in range(len(spec["requiredEvidence"])):
            self.assertFalse(live.instrumentation_verdict(0, True, records[:index] + records[index + 1:], spec)["passed"])
        for original, changed in (("goalStatus=VERIFIED", "goalStatus=PARTIAL"),
                                  ("exactReadBack=true", "exactReadBack=false"),
                                  ("privacyPassed=true", "privacyPassed=false"),
                                  ("aliasStateRestored=true", "aliasStateRestored=false")):
            self.assertFalse(live.instrumentation_verdict(0, True, [line.replace(original, changed) for line in records], spec)["passed"])
        self.assertFalse(live.instrumentation_verdict(0, False, records, spec)["passed"])

    def test_settings_scroll_requires_its_own_direction_approval_and_restore_evidence(self):
        spec = PHASES["workflow-settings-scroll"]
        records = [marker + "verified=true\n" for marker in spec["requiredEvidence"]] + ["OK (1 test)\n"]
        self.assertTrue(live.instrumentation_verdict(0, True, records, spec)["passed"])
        # long: 同阶段少任意回执、方向错误、滚动人工审批或旧搜索成功都必须失败，不能只认 JUnit 成功和模型完成。
        for index in range(len(spec["requiredEvidence"])):
            self.assertFalse(live.instrumentation_verdict(0, True, records[:index] + records[index + 1:], spec)["passed"])
        for original, changed in (("direction=up", "direction=down"),
                                  ("executorVerified=true", "executorVerified=false"),
                                  ("noSwipeApproval=true", "noSwipeApproval=false"),
                                  ("snapshotContentChanged=true", "snapshotContentChanged=false"),
                                  ("actionSummaryOnly=true", "actionSummaryOnly=false"),
                                  ("visibleSwipeAction=true", "visibleSwipeAction=false")):
            self.assertFalse(live.instrumentation_verdict(0, True, [line.replace(original, changed) for line in records], spec)["passed"])
        search_records = [marker + "verified=true\n" for marker in PHASES["workflow-settings-search"]["requiredEvidence"]] + ["OK (1 test)\n"]
        self.assertFalse(live.instrumentation_verdict(0, True, search_records, spec)["passed"])
        self.assertFalse(live.instrumentation_verdict(0, False, records, spec)["passed"])

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
