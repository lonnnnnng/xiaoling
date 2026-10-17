"""long: 三轮独立 Redmi 进程核对整理 outbox、候选幂等与原事实，失败保留 UUID 夹具。"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import uuid
from verify_agent_context_process_recovery import SERIAL, read_result

METHODS = ("seedPendingOutboxAndUnchangedUserFact", "restorePendingOutboxWithoutRepeatingCandidateOrChangingFact", "verifyDecisionsAndRemoveOnlySuccessfulFixture")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    output = parser.parse_args().output.resolve()
    assert not output.exists(), "拒绝覆盖既有证据"
    output.mkdir(parents=True)
    trace = {"serial": SERIAL, "database": "stage287-maintenance-process-" + str(uuid.uuid4()) + ".db", "phases": [], "completed": False}

    def adb(*parts):
        result = subprocess.run(["adb", "-s", SERIAL, *parts], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        assert result.returncode == 0, "ADB 失败"
        return result.stdout.strip()

    try:
        assert adb("get-state") == "device"
        assert adb("shell", "getprop", "ro.product.device") == "begonia"
        manifest = None
        for index, method in enumerate(METHODS):
            adb("shell", "am", "force-stop", "com.longdev.xiaoling")
            parts = ["am", "instrument", "-w", "-r", "-e", "class", "com.longdev.xiaoling.storage.MemoryMaintenanceProcessInstrumentedTest#" + method,
                     "-e", "maintenanceDatabase", trace["database"]]
            if manifest is not None:
                parts += ["-e", "maintenanceManifest", json.dumps(manifest, separators=(",", ":"))]
            parts += ["com.longdev.xiaoling.test/androidx.test.runner.AndroidJUnitRunner"]
            raw = adb("shell", shlex.join(parts))
            (output / (str(index + 1) + "-" + method + ".log")).write_text(raw)
            pid = read_result(raw, "memoryMaintenancePid")
            assert pid not in [phase["pid"] for phase in trace["phases"]], "复用旧进程"
            trace["phases"].append({"method": method, "pid": pid})
            if index == 0:
                match = re.search(r"^INSTRUMENTATION_STATUS: memoryMaintenanceManifest=(\{.*\})$", raw, re.MULTILINE)
                assert match, "缺少持久摘要"
                manifest = json.loads(match[1])
                (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
            if index == 2:
                assert "INSTRUMENTATION_STATUS: memoryMaintenanceCleaned=true" in raw, "夹具未清理"
            print("整理独立进程通过：" + method + "; PID=" + str(pid), flush=True)
        trace["completed"] = True
    finally:
        (output / "result.json").write_text(json.dumps(trace, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
