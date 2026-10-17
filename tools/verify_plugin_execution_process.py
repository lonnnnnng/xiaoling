"""long: 仅指定 Redmi 的 UUID 隔离账本，三进程证明旧插件许可失效并允许新显式选择。"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import uuid
from verify_agent_context_process_recovery import read_result

SERIAL = "wsvwypiz7xwslvl7"
METHODS = ("seedVerifiedPluginBeforeProcessExit", "restartRejectsOldForegroundAndConvergesWithoutNativeResume",
           "newExplicitSelectionRunsAndPreservesOldTerminalEvidence")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    output = parser.parse_args().output.resolve()
    assert not output.exists(), "拒绝覆盖既有证据"
    output.mkdir(parents=True)
    trace = {"serial": SERIAL, "uuid": str(uuid.uuid4()), "phases": [], "completed": False}

    def adb(*parts):
        result = subprocess.run(["adb", "-s", SERIAL, *parts], text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120)
        assert result.returncode == 0, "ADB 失败"
        return result.stdout.strip()

    try:
        assert adb("get-state") == "device"
        assert adb("shell", "getprop", "ro.product.device") == "begonia"
        for index, method in enumerate(METHODS):
            adb("shell", "am", "force-stop", "com.longdev.xiaoling")
            args = ["am", "instrument", "-w", "-r", "-e", "class",
                    "com.longdev.xiaoling.agent.AgentPluginExecutionProcessInstrumentedTest#" + method,
                    "-e", "pluginExecutionUuid", trace["uuid"],
                    "com.longdev.xiaoling.test/androidx.test.runner.AndroidJUnitRunner"]
            raw = adb("shell", shlex.join(args))
            (output / f"{index + 1}-{method}.log").write_text(raw)
            pid = read_result(raw, "pluginExecutionPid")
            assert pid not in [phase["pid"] for phase in trace["phases"]], "复用旧进程"
            trace["phases"].append({"method": method, "pid": pid})
            if index == 2:
                assert re.search(r"^INSTRUMENTATION_STATUS: pluginExecutionCleaned=true$", raw, re.MULTILINE), "夹具未清理"
            print(f"插件执行独立进程通过：{method}; PID={pid}", flush=True)
        trace["completed"] = True
    finally:
        (output / "result.json").write_text(json.dumps(trace, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
