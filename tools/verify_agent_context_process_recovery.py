"""long: 用显式 UUID 数据库验三轮独立进程；失败保留夹具和日志，不用退出码代替 JUnit 结论。"""
from pathlib import Path
import argparse
import json
import re
import shlex
import subprocess
import uuid

SERIAL = "wsvwypiz7xwslvl7"
TEST = "com.longdev.xiaoling.storage.AgentContextProcessRecoveryInstrumentedTest"
METHODS = ("seedDurableBoundaries", "restoreUsingProductionRecoveryWithoutToolOrModelReplay", "verifySettledEvidenceAndCleanOnlyFixture")


def read_result(raw, pid_field="contextRecoveryPid"):
    codes = [int(value) for value in re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)$", raw, re.MULTILINE)]
    assert codes.count(0) == 1 and all(code in (0, 1, 2) for code in codes), "JUnit 失败或跳过"
    assert "OK (1 test)" in raw and "INSTRUMENTATION_FAILED" not in raw and "INSTRUMENTATION_CODE: -1" in raw, "JUnit 未成功完成"
    pid = re.search(r"^INSTRUMENTATION_STATUS: " + re.escape(pid_field) + r"=(\d+)$", raw, re.MULTILINE)
    assert pid, "缺少实际进程 PID"
    return int(pid[1])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    assert not output.exists(), "输出目录已存在，拒绝覆盖证据"
    output.mkdir(parents=True)
    trace = {"serial": SERIAL, "database": "stage287-recovery-" + str(uuid.uuid4()) + ".db", "phases": [], "completed": False}

    def adb(*parts, log=None):
        result = subprocess.run(["adb", "-s", SERIAL, *parts], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        if log:
            log.write_text(result.stdout)
        assert result.returncode == 0, "ADB 失败，查看保留日志"
        return result.stdout.strip()

    try:
        assert adb("get-state") == "device"
        assert adb("shell", "getprop", "ro.product.device") == "begonia"
        trace["sdk"] = adb("shell", "getprop", "ro.build.version.sdk")
        manifest = None
        for index, method in enumerate(METHODS):
            # long: 只停止指定应用进程，不卸载/清数据；独立 PID 是受控重启证据，不冒充自然 LMK。
            adb("shell", "am", "force-stop", "com.longdev.xiaoling")
            parts = ["am", "instrument", "-w", "-r", "-e", "class", TEST + "#" + method, "-e", "contextRecoveryDatabase", trace["database"]]
            if manifest is not None:
                parts += ["-e", "contextRecoveryManifest", json.dumps(manifest, separators=(",", ":"))]
            parts += ["com.longdev.xiaoling.test/androidx.test.runner.AndroidJUnitRunner"]
            raw = adb("shell", shlex.join(parts), log=output / (str(index + 1) + "-" + method + ".log"))
            pid = read_result(raw)
            assert pid not in [phase["pid"] for phase in trace["phases"]], "独立轮次复用了 PID"
            trace["phases"].append({"method": method, "pid": pid})
            if index == 0:
                match = re.search(r"^INSTRUMENTATION_STATUS: contextRecoveryManifest=(\{.*\})$", raw, re.MULTILINE)
                assert match, "缺少持久证据摘要"
                manifest = json.loads(match[1])
                (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
            if index == 2:
                assert "INSTRUMENTATION_STATUS: contextRecoveryCleaned=true" in raw, "夹具未完成清理"
            print("独立进程验收通过: " + method + "; PID=" + str(pid), flush=True)
        trace["completed"] = True
    finally:
        (output / "result.json").write_text(json.dumps(trace, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
