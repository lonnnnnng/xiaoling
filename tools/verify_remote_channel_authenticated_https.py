#!/usr/bin/env python3
"""long: Redmi 默认 HTTPS 合成验收；只启动本轮的临时服务，发送私钥与队列留在仓库外。"""

import argparse
from contextlib import closing
import json
import os
from pathlib import Path
import re
import shlex
import signal
import socket
import sqlite3
import subprocess
import tempfile
import threading
import time
import uuid

import remote_channel_http as http
import remote_channel_receiver_auth as auth
import remote_channel_sender as sender

SERIAL = "wsvwypiz7xwslvl7"
TEST = "com.longdev.xiaoling.ui.remote.RemoteChannelAuthenticatedNetworkInstrumentedTest"
METHODS = {
    "create": "createPublicEnrollmentAndRejectUnauthorizedRealHttpsPull",
    "receive": "receiveRealSignedBatchAndPreserveOutboxAfterCommittedAckFault",
    "revoked": "revokedAuthorizationRejectsRestoredAckOverRealHttps",
    "restore": "restoreSameKeyAndAckAfterServerRestartThenClearOnlyFixture",
}


def database_audit(queue):
    with closing(sqlite3.connect(queue.database)) as connection:
        return {"queued": connection.execute("SELECT count(*) FROM messages WHERE acknowledged=0").fetchone()[0],
                "acknowledged": connection.execute("SELECT count(*) FROM messages WHERE acknowledged=1").fetchone()[0],
                "pending": connection.execute("SELECT count(*) FROM deliveries WHERE acknowledged=0").fetchone()[0],
                "signedAcks": connection.execute("SELECT count(*) FROM receipts WHERE ack_json IS NOT NULL").fetchone()[0],
                "nonces": connection.execute("SELECT count(*) FROM receiver_auth_requests").fetchone()[0]}


class AuditedSyntheticServer(http.AuthenticatedHttpServer):
    def __init__(self, *args, audit, fault=False, **kwargs):
        self.audit, self.fault = audit, fault
        super().__init__(*args, **kwargs)

    def execute(self, raw, deadline):
        operation = sender.parse_json(raw.decode())["request"]["operation"]
        event = {"operation": operation, "timeMillis": int(time.time() * 1000), "faultMode": self.fault}
        try:
            result = super().execute(raw, deadline)
            event.update(database_audit(sender.LocalSenderQueue(self.state_dir)))
            event["status"] = 200
            if self.fault and operation == "ack":
                # long: 真正认证/COMMIT 后才丢弃成功响应；不替换 pull 响应、不绕过验签或本地授权。
                assert event["queued"] == 0 and event["pending"] == 0 and event["signedAcks"] >= 1
                event["status"] = 503
                raise http.HttpError(503, "SYNTHETIC_COMMITTED_ACK_FAULT")
            return result
        except http.HttpError as error:
            event["status"], event["error"] = error.status, error.code
            raise
        finally:
            # long: 审计只存操作与计数，正文、签名、proof 和密钥不写请求日志。
            with self.audit.open("a", encoding="utf-8") as output:
                output.write(sender.wire_json(event) + "\n")


def main():
    parser = argparse.ArgumentParser(description="仅 Redmi 的临时认证公网验收；APK 必须预先分别安装")
    parser.add_argument("--cloudflared", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    # long: 一次验收使用独立空目录，避免覆盖失败现场或混入上轮请求审计。
    if any(output.iterdir()):
        raise ValueError("verification output directory must be empty")
    def adb(*arguments, log_path=None):
        try:
            result = subprocess.run(["adb", "-s", SERIAL, *arguments], capture_output=True, text=True, timeout=60, check=False)
        except subprocess.TimeoutExpired as error:
            if log_path is not None:
                log_path.write_bytes((error.stdout or b"") + (error.stderr or b""))
            raise
        if log_path is not None:
            log_path.write_text(result.stdout + result.stderr, encoding="utf-8")
        result.check_returncode()
        return result.stdout
    assert adb("shell", "getprop", "ro.product.device").strip() == "begonia"
    assert adb("shell", "getprop", "ro.build.version.sdk").strip() == "36"
    state = Path(tempfile.mkdtemp(prefix="xiaoling-auth-network-")) / "state"
    queue = sender.LocalSenderQueue.initialize(state, "auth-network-test", "local-sender", "local-key-1")
    gateway = auth.AuthenticatedSender.initialize(queue)
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        port = reserved.getsockname()[1]
    ledger = "remote_auth_network_test_" + str(uuid.uuid4())
    config = {"serial": SERIAL, "ledger": ledger, "stateDir": str(state), "port": port, "publicKey": queue.info["publicKeySpkiBase64"]}
    config_path = output / "network-config.json"
    config_path.write_text(sender.wire_json(config), encoding="utf-8")
    servers, tunnel = [], None
    tunnel_log = output / "tunnel.log"
    trace = {"stateDir": str(state), "phases": [], "completed": False}
    def interrupted(*_):
        raise InterruptedError("synthetic verification interrupted")
    signal.signal(signal.SIGTERM, interrupted)
    try:
        with tunnel_log.open("w", encoding="utf-8") as log:
            tunnel = subprocess.Popen([str(Path(args.cloudflared).resolve()), "tunnel", "--url", f"http://127.0.0.1:{port}",
                                       "--no-autoupdate"], stdout=log, stderr=log, start_new_session=True)
        end = time.monotonic() + 45
        while time.monotonic() < end:
            match = re.search(r"https://[a-z0-9-]+\.trycloudflare\.com", tunnel_log.read_text())
            if match:
                break
            assert tunnel.poll() is None, "synthetic tunnel exited"
            threading.Event().wait(0.25)
        else:
            raise RuntimeError("synthetic tunnel startup timeout")
        endpoint = match[0] + "/batch"
        config["endpoint"] = endpoint
        config_path.write_text(sender.wire_json(config), encoding="utf-8")
        def start(fault):
            service = AuditedSyntheticServer(state, endpoint, port, audit=output / "http-audit.jsonl", fault=fault)
            thread = threading.Thread(target=service.serve)
            thread.start()
            servers.append((service, thread))
            return service, thread
        def stop(service, thread):
            service.stop.set(); thread.join(22)
            assert not thread.is_alive(), "synthetic service shutdown timeout"
        common = {"remoteNetworkLedger": ledger, "remoteNetworkEndpoint": endpoint,
                  "remoteNetworkPublicKey": queue.info["publicKeySpkiBase64"]}
        def phase(name):
            adb("shell", "am", "force-stop", "com.longdev.xiaoling")
            arguments = ["am", "instrument", "-w", "-r", "-e", "class", TEST + "#" + METHODS[name]]
            for key, value in {**common, "remoteNetworkPhase": name}.items():
                arguments += ["-e", key, value]
            arguments.append("com.longdev.xiaoling.test/androidx.test.runner.AndroidJUnitRunner")
            raw = adb("shell", shlex.join(arguments), log_path=output / (name + ".log"))
            assert "OK (1 test)" in raw and "INSTRUMENTATION_FAILED" not in raw and "INSTRUMENTATION_STATUS_CODE: -3" not in raw, name + " failed"
            pid = re.search(r"INSTRUMENTATION_STATUS: remoteNetworkPid=(\d+)", raw)
            assert pid and pid[1] not in [entry["pid"] for entry in trace["phases"]]
            trace["phases"].append({"phase": name, "pid": pid[1], "database": database_audit(queue)})
            print("authenticated HTTPS phase " + name + " passed; pid=" + pid[1], flush=True)
            return raw
        service, thread = start(True)
        raw = phase("create")
        manifest = json.loads(re.search(r"INSTRUMENTATION_STATUS: remoteNetworkManifest=(\{.*\})", raw)[1])
        (output / "receiver-manifest.json").write_text(sender.wire_json(manifest), encoding="utf-8")
        assert manifest["endpointSha256"] == http.Endpoint(endpoint).sha256
        gateway.authorize(manifest, manifest["receiverFingerprintSha256"])
        common["remoteNetworkReceiverFingerprint"] = manifest["receiverFingerprintSha256"]
        for number in (1, 2):
            queue.enqueue(manifest["receiverId"], "auth-network-" + str(number), "synthetic-conversation", "认证公网合成正文 " + str(number))
        raw = phase("receive")
        for key in ("remoteNetworkBatchId", "remoteNetworkDigest", "remoteNetworkReceiver", "remoteNetworkCursor"):
            common[key] = re.search(r"INSTRUMENTATION_STATUS: " + key + r"=([^\r\n]+)", raw)[1]
        assert database_audit(queue)["queued"] == 0 and database_audit(queue)["signedAcks"] == 1
        stop(service, thread)
        gateway.revoke(manifest["receiverId"])
        service, thread = start(False)
        phase("revoked")
        gateway.authorize(manifest, manifest["receiverFingerprintSha256"])
        phase("restore")
        trace["finalDatabase"] = database_audit(queue)
        assert trace["finalDatabase"]["queued"] == 0 and trace["finalDatabase"]["acknowledged"] == 2
        trace["completed"] = True
        return 0
    finally:
        for service, thread in servers:
            service.stop.set()
            thread.join(22)
        if tunnel is not None and tunnel.poll() is None:
            # long: 只停止本轮 Popen 持有的进程组，禁止按名称终止其他会话的服务。
            try:
                os.killpg(tunnel.pid, signal.SIGTERM)
                tunnel.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(tunnel.pid, signal.SIGKILL)
                tunnel.wait()
        trace["servicesStopped"] = all(not thread.is_alive() for _, thread in servers)
        trace["tunnelStopped"] = tunnel is None or tunnel.poll() is not None
        (output / "process-trace.json").write_text(sender.wire_json(trace), encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
