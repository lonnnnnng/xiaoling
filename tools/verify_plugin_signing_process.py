"""long: 三轮独立 Redmi 进程核对插件确认公钥、回滚与移除约束，失败保留隔离账本。"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import uuid
from verify_agent_context_process_recovery import read_result

SERIAL = "wsvwypiz7xwslvl7"
METHODS = ("seedConfirmedSignedUpgrade", "restoreKeyAndRollbackWithHighestVersionPreserved",
           "restoreRollbackThenVerifyUninstallAndReinstallBoundary")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    output = parser.parse_args().output.resolve()
    assert not output.exists(), "拒绝覆盖既有证据"
    output.mkdir(parents=True)
    trace = {"serial": SERIAL, "preferences": "stage288-plugin-process-" + str(uuid.uuid4()),
             "phases": [], "completed": False}

    def adb(*parts):
        result = subprocess.run(["adb", "-s", SERIAL, *parts], text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=120)
        assert result.returncode == 0, "ADB 失败"
        return result.stdout.strip()

    try:
        assert adb("get-state") == "device"
        assert adb("shell", "getprop", "ro.product.device") == "begonia"
        anchor = None
        for index, method in enumerate(METHODS):
            adb("shell", "am", "force-stop", "com.longdev.xiaoling")
            args = ["am", "instrument", "-w", "-r", "-e", "class",
                    "com.longdev.xiaoling.agent.AgentPluginSigningProcessInstrumentedTest#" + method,
                    "-e", "pluginPreferences", trace["preferences"],
                    "com.longdev.xiaoling.test/androidx.test.runner.AndroidJUnitRunner"]
            raw = adb("shell", shlex.join(args))
            (output / f"{index + 1}-{method}.log").write_text(raw)
            pid = read_result(raw, "pluginPid")
            def field(name):
                match = re.search(r"^INSTRUMENTATION_STATUS: " + name + r"=(\S+)$", raw, re.MULTILINE)
                assert match, "缺少 " + name
                return match.group(1)
            assert pid not in [phase["pid"] for phase in trace["phases"]], "复用旧进程"
            hashes = (field("pluginKeySha256"), field("pluginManifestSha256"))
            assert all(re.fullmatch(r"[0-9a-f]{64}", value) for value in hashes)
            if anchor is None:
                anchor = hashes
            assert hashes == anchor, "公钥或最高版本指纹漂移"
            trace["phases"].append({"method": method, "pid": pid, "keySha256": hashes[0], "manifestSha256": hashes[1]})
            if index == 2:
                assert field("pluginCleaned") == "true", "夹具未清理"
            print(f"插件独立进程通过：{method}; PID={pid}", flush=True)
        trace["completed"] = True
    finally:
        (output / "result.json").write_text(json.dumps(trace, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
