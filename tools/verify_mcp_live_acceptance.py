#!/usr/bin/env python3
"""long: 在指定 Redmi 验收真实 MCP/模型；凭据仅经单次 USB socket 进入测试内存。"""

import argparse
import hashlib
import json
from pathlib import Path
import queue
import re
import socket
import struct
import subprocess
import threading
import time

SERIAL = "wsvwypiz7xwslvl7"
TEST = "com.longdev.xiaoling.agent.McpLiveAcceptanceInstrumentedTest"
RUNNER = "com.longdev.xiaoling.test/androidx.test.runner.AndroidJUnitRunner"
METHODS = {
    "context7-transport": "context7TransportUsesDefaultHttpsAndExplicitEnablement",
    "sse-success": "realProviderSseSuccessUsesProductionRunAndKeepsSettingsOwner",
    "sse-cancel": "realProviderSseCancellationSettlesRoomWithoutLateResultOrReplay",
    "sse-disabled": "realProviderDisabledSseToolIsAbsentFromActualPromptAndRemoteCalls",
    "context7-agent": "realProviderContext7RunDiscoversAndResolvesThroughProductionRegistry",
}


def load_inputs(root, context7_config, context7_auth):
    source = (root / "AGENTS.md").read_text(encoding="utf-8")
    fields = dict(re.findall(r"^\s*(baseurl|apikey|使用模型)\s*[:：]\s*(\S+)\s*$", source, re.MULTILINE))
    if set(fields) != {"baseurl", "apikey", "使用模型"}:
        raise ValueError("AGENTS.md 缺少本轮授权的测试 Provider 字段")
    source_bytes = context7_config.read_bytes()
    context7 = json.loads(source_bytes)["mcp"]["context7"]
    key = context7["headers"]["CONTEXT7_API_KEY"]
    if context7["url"] != "https://mcp.context7.com/mcp" or context7["enabled"] is not False or not key:
        raise ValueError("Context7 配置与本轮指定目标不符")
    # long: 匿名验收必须显式选择，认证失败不在同一次调用中降级，也不改变用户保存的 Token。
    payload = json.dumps({"provider": {"baseUrl": fields["baseurl"], "apiKey": fields["apikey"],
                                       "model": fields["使用模型"]},
                          "context7Key": "" if context7_auth == "anonymous" else key}).encode("utf-8")
    if len(payload) > 16_384:
        raise ValueError("测试配置超过 USB 输入上限")
    return payload, (fields["apikey"], key), hashlib.sha256(source_bytes).hexdigest()


def redact(value, secrets):
    for secret in secrets:
        value = value.replace(secret, "[REDACTED_SECRET]")
    # long: 即使第三方错误正文意外带出其他认证值，也不能让它进入主机验收日志。
    value = re.sub(r"\b(?:ctx7sk-|sk-)[A-Za-z0-9_-]+", "[REDACTED_SECRET]", value)
    return re.sub(r"(?i)(authorization\s*[:=]\s*bearer\s+)\S+", r"\1[REDACTED_SECRET]", value)


def adb(*arguments):
    result = subprocess.run(["adb", "-s", SERIAL, *arguments], capture_output=True, text=True, timeout=30)
    if result.returncode:
        raise RuntimeError("ADB 公开控制命令失败")
    return result.stdout.strip()


def phase(name, port, payload, secrets, output, context7_auth="bearer"):
    host_port = None
    command = ["adb", "-s", SERIAL, "shell", "am", "instrument", "-w", "-r", "-e", "class",
               TEST + "#" + METHODS[name], "-e", "stage286RealRun", "true",
               "-e", "stage286ConfigPort", str(port), "-e", "stage286Context7Auth", context7_auth, RUNNER]
    process = None
    started = time.monotonic()
    records = []
    sent = False
    result = {"phase": name, "passed": False, "configAcknowledged": False, "returnCode": None}
    try:
        host_port = int(adb("forward", "tcp:0", f"tcp:{port}"))
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding="utf-8", errors="replace", bufsize=1)
        lines = queue.Queue()

        def consume():
            try:
                for line in process.stdout:
                    lines.put(redact(line, secrets))
            finally:
                lines.put(None)

        threading.Thread(target=consume, daemon=True).start()
        # long: 按 CONFIG_READY 再发送，避免设备尚未监听时丢失输入；凭据不经过 argv、共享文件或 APK。
        with (output / (name + ".log")).open("x", encoding="utf-8") as log:
            while True:
                remaining = 270 - (time.monotonic() - started)
                if remaining <= 0:
                    raise TimeoutError("单项真实验收超过 270 秒")
                try:
                    line = lines.get(timeout=min(remaining, 1))
                except queue.Empty:
                    continue
                if line is None:
                    break
                records.append(line)
                log.write(line)
                log.flush()
                if "STAGE286_CONFIG_READY" in line and not sent:
                    with socket.create_connection(("127.0.0.1", host_port), timeout=10) as connection:
                        connection.sendall(struct.pack("!I", len(payload)) + payload)
                        if connection.recv(1) != b"\x01":
                            raise RuntimeError("USB 配置输入未收到确认")
                    sent = True
                if "STAGE286_RUN" in line or "STAGE286_EVIDENCE" in line:
                    print(line.rstrip(), flush=True)
        code = process.wait(timeout=10)
        raw = "".join(records)
        evidence = [line.strip() for line in records if "STAGE286_EVIDENCE" in line]
        passed = (code == 0 and sent and "OK (1 test)" in raw and evidence and
                  not any(marker in raw for marker in ("INSTRUMENTATION_FAILED", "FAILURES!!!",
                                                       "INSTRUMENTATION_STATUS_CODE: -3", "AssumptionViolatedException")))
        result.update(passed=bool(passed), returnCode=code)
    except Exception as error:
        # long: socket、启动和超时故障也属于当前阶段，必须保留已收到的证据而不能只写顶层异常。
        result["error"] = redact(f"{type(error).__name__}: {error}", secrets)
    finally:
        cleanup_errors = []
        if process is not None:
            try:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
                process.stdout.close()
            except Exception as error:
                cleanup_errors.append(redact(f"process: {type(error).__name__}: {error}", secrets))
        # long: 只撤销本轮创建的转发，不能使用 forward --remove-all 影响其他设备工作。
        if host_port is not None:
            try:
                adb("forward", "--remove", f"tcp:{host_port}")
            except Exception as error:
                cleanup_errors.append(redact(f"forward: {type(error).__name__}: {error}", secrets))
        result.update(configAcknowledged=sent, elapsedSeconds=round(time.monotonic() - started, 2),
                      evidence=[line.strip() for line in records if "STAGE286_EVIDENCE" in line])
        if cleanup_errors:
            result.update(passed=False, cleanupErrors=cleanup_errors)
    print(f"{name}: {'PASS' if result['passed'] else 'FAIL'} ({result['elapsedSeconds']}s)", flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description="真实 Context7 与 Provider 联合验收；APK 需预先覆盖安装")
    parser.add_argument("--phase", choices=METHODS, action="append")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--context7-config", type=Path, default=Path.home() / ".config/opencode/opencode.json")
    parser.add_argument("--context7-auth", choices=("bearer", "anonymous"), default="bearer")
    parser.add_argument("--device-port", type=int, default=19886)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("验收输出目录必须为空，避免覆盖失败证据")
    if not 1024 <= args.device_port <= 65535:
        raise ValueError("设备端口无效")
    payload, secrets, config_hash = load_inputs(root, args.context7_config, args.context7_auth)
    report = {"serial": SERIAL, "context7Url": "https://mcp.context7.com/mcp", "context7Auth": args.context7_auth, "phases": [],
              "context7ConfigSha256Before": config_hash, "completed": False}
    exit_code = 1
    try:
        if adb("shell", "getprop", "ro.product.device") != "begonia":
            raise RuntimeError("验收设备不是指定 Redmi begonia")
        if adb("shell", "getprop", "ro.build.version.sdk") != "36":
            raise RuntimeError("验收设备 SDK 与基线不符")
        for name in args.phase or METHODS:
            result = phase(name, args.device_port, payload, secrets, output, args.context7_auth)
            report["phases"].append(result)
            if not result["passed"]:
                break
        else:
            report["completed"] = True
            exit_code = 0
    except Exception as error:
        report["error"] = redact(f"{type(error).__name__}: {error}", secrets)
        print(report["error"], flush=True)
    finally:
        try:
            current_hash = hashlib.sha256(args.context7_config.read_bytes()).hexdigest()
            report["context7ConfigSha256After"] = current_hash
            report["context7ConfigUnchanged"] = current_hash == config_hash
        except Exception:
            report["context7ConfigUnchanged"] = False
            report["configAuditError"] = "原 Context7 配置无法回读"
        if not report["context7ConfigUnchanged"]:
            report["completed"], exit_code = False, 1
        # long: 正式配置变化或回读失败时仍先落报告，再以失败退出，保留最关键的异常现场。
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
