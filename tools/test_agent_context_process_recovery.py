"""long: 主机编排必须拒绝 ADB 退出零但 JUnit 失败、跳过或没有 PID 的结果。"""
import unittest
from verify_agent_context_process_recovery import read_result


class ResultTest(unittest.TestCase):
    def valid(self):
        return "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: contextRecoveryPid=123\nINSTRUMENTATION_STATUS_CODE: 2\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"

    def test_success_requires_real_pid(self):
        self.assertEqual(123, read_result(self.valid()))
        with self.assertRaises(AssertionError):
            read_result(self.valid().replace("contextRecoveryPid", "otherPid"))

    def test_fail_skip_zero_or_incomplete_cannot_pass(self):
        for code in (-1, -2, -3, -4):
            with self.assertRaises(AssertionError):
                read_result(self.valid().replace("STATUS_CODE: 0", "STATUS_CODE: " + str(code)))
        for text in (self.valid().replace("OK (1 test)", "OK (0 tests)"), self.valid().replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_FAILED")):
            with self.assertRaises(AssertionError):
                read_result(text)
