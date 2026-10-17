#!/usr/bin/env python3
"""long: 只向临时 HTTPS 隧道提供合成签名收件夹具，不读取用户数据。"""

import base64
import argparse
import json
import pathlib
import struct
import subprocess
import time
import hashlib
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PARSER = argparse.ArgumentParser(description="仅用于真机 Remote Channel HTTPS 验收的合成签名消息服务")
PARSER.add_argument("--state-dir", required=True, help="仓库外的临时密钥目录")
PARSER.add_argument("--port", type=int, default=18081)
ARGS = PARSER.parse_args()
ROOT = pathlib.Path(ARGS.state_dir).expanduser().resolve()
PROJECT = pathlib.Path(__file__).resolve().parents[1]
if ROOT == PROJECT or PROJECT in ROOT.parents:
    PARSER.error("测试私钥目录必须位于项目仓库之外")
ROOT.mkdir(parents=True, exist_ok=True)
ROOT.chmod(0o700)
PRIVATE_KEY = ROOT / "fixture-private.pem"
PUBLIC_KEY = ROOT / "fixture-public.der"
STATE_LOCK = threading.Lock()
ACK_ATTEMPTS = {}
RECEIPTS = {}


def field(value):
    if value is None:
        return struct.pack(">i", -1)
    encoded = str(value).encode("utf-8")
    return struct.pack(">I", len(encoded)) + encoded


def sign(payload):
    # long: 各请求直接通过 stdin/stdout 签名，线程之间不共用输入或签名文件，避免批次和 ACK 串签。
    signed = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(PRIVATE_KEY)], input=payload, capture_output=True, check=True)
    return base64.urlsafe_b64encode(signed.stdout).decode("ascii").rstrip("=")


def message_bytes(value):
    fields = [value[key] for key in ("channelId", "messageId", "senderId", "conversationKey", "text", "receivedAtMillis", "nonce", "keyId")]
    return struct.pack(">I", len(fields)) + b"".join(field(value) for value in fields)


def prepare_keys():
    if not PRIVATE_KEY.exists():
        subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(PRIVATE_KEY)], check=True)
        PRIVATE_KEY.chmod(0o600)
    subprocess.run(["openssl", "pkey", "-in", str(PRIVATE_KEY), "-pubout", "-outform", "DER", "-out", str(PUBLIC_KEY)], check=True)


def signed_message(message_id="real-https-20261014-1"):
    # long: 每次读取重新签当前时间，避免隧道建立较慢时越过应用的五分钟签名窗口。
    now = int(time.time() * 1000)
    value = {
        "channelId": "real-device-test",
        "messageId": message_id,
        "senderId": "fixture-sender",
        "conversationKey": "fixture-conversation",
        "text": "真实 HTTPS 真机验收合成正文",
        "receivedAtMillis": now,
        "nonce": "fixture-unique-nonce-" + message_id,
        "keyId": "fixture-key-1",
    }
    value["signature"] = sign(message_bytes(value))
    return value


def batch_bytes(value):
    payload = field("xiaoling-batch-v1") + struct.pack(">I", 1)
    payload += b"".join(field(value[key]) for key in ("channelId", "senderId", "keyId", "receiverId", "batchId", "requestCursor", "nextCursor"))
    payload += struct.pack(">qqI", value["issuedAtMillis"], value["expiresAtMillis"], len(value["messages"]))
    for message in value["messages"]:
        content = message_bytes(message)
        payload += struct.pack(">I", len(content)) + content + field(message["signature"])
    return payload


def signed_batch(receiver_id, cursor):
    now = int(time.time() * 1000)
    messages = [signed_message("batch-message-1"), signed_message("batch-message-2")] if cursor is None else []
    value = {
        "protocolVersion": 1, "channelId": "real-device-test", "senderId": "fixture-sender", "keyId": "fixture-key-1",
        "receiverId": receiver_id, "batchId": "batch-" + uuid.uuid4().hex, "requestCursor": cursor,
        "nextCursor": "cursor-1", "issuedAtMillis": now, "expiresAtMillis": now + 60000, "messages": messages,
    }
    payload = batch_bytes(value)
    value["signature"] = sign(payload)
    with STATE_LOCK:
        RECEIPTS[(receiver_id, value["batchId"])] = {
            "batchDigestSha256": hashlib.sha256(payload).hexdigest(), "nextCursor": value["nextCursor"],
            "messageIds": [message["messageId"] for message in messages],
        }
    return value


def ack_bytes(value):
    return field("xiaoling-ack-v1") + struct.pack(">I", 1) + b"".join(field(value[key]) for key in
        ("channelId", "senderId", "keyId", "receiverId", "batchId", "batchDigestSha256", "nextCursor"))


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path in ("/batch", "/batch-ack-once"):
            receiver = self.headers.get("X-Xiaoling-Receiver", "")
            if len(receiver) != 32 or any(c not in "0123456789abcdef" for c in receiver):
                self.send_error(400)
                return
            raw_cursor = self.headers.get("X-Xiaoling-Cursor")
            cursor = base64.urlsafe_b64decode(raw_cursor + "=" * (-len(raw_cursor) % 4)).decode("utf-8") if raw_cursor else None
            self.send_json(signed_batch(receiver, cursor))
            return
        if self.path == "/redirect":
            self.send_response(302)
            self.send_header("Location", "/next")
            self.end_headers()
            return
        if self.path == "/next":
            body = json.dumps(signed_message(), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        elif self.path == "/bad-signature":
            value = signed_message()
            value["text"] = "被篡改的正文"
            body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        elif self.path == "/oversized":
            body = json.dumps({"padding": "x" * 70000}).encode("utf-8")
        else:
            self.send_error(404)
            return
        self.send_json_bytes(body)

    def do_POST(self):
        # long: ACK 只核对合成批次收据并返回签名确认，不删除发送队列，不操作任何真实账户或业务数据。
        if self.path not in ("/batch", "/batch-ack-once"):
            self.send_error(404)
            return
        size = int(self.headers.get("Content-Length", "0"))
        if size <= 0 or size > 65536:
            self.send_error(400)
            return
        value = json.loads(self.rfile.read(size))
        expected_fields = {"protocolVersion", "operation", "channelId", "senderId", "keyId", "receiverId", "batchId", "batchDigestSha256", "nextCursor", "messageIds"}
        if set(value) != expected_fields or value["operation"] != "ack" or value["protocolVersion"] != 1:
            self.send_error(400)
            return
        if (value["channelId"], value["senderId"], value["keyId"]) != ("real-device-test", "fixture-sender", "fixture-key-1"):
            self.send_error(400)
            return
        identity = (value["receiverId"], value["batchId"])
        with STATE_LOCK:
            expected = RECEIPTS.get(identity)
            if expected is None or any(value[key] != expected[key] for key in expected):
                self.send_error(409)
                return
            # long: 每个接收端只注入一次临时故障，恢复后的空批次应能正常确认，避免无限制造新故障。
            fault_queue = (self.path, value["receiverId"])
            ACK_ATTEMPTS[fault_queue] = ACK_ATTEMPTS.get(fault_queue, 0) + 1
            first = ACK_ATTEMPTS[fault_queue] == 1
        if self.path == "/batch-ack-once" and first:
            self.send_error(503)
            return
        reply = {key: item for key, item in value.items() if key != "messageIds"}
        reply["operation"] = "acknowledged"
        reply["signature"] = sign(ack_bytes(reply))
        self.send_json(reply)

    def send_json(self, value):
        self.send_json_bytes(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))

    def send_json_bytes(self, body):
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        # long: 只记录路径与状态，不记录签名消息正文或任何私钥材料。
        super().log_message(format, *args)


if __name__ == "__main__":
    prepare_keys()
    print("fixture public key base64=" + base64.b64encode(PUBLIC_KEY.read_bytes()).decode("ascii"), flush=True)
    print("fixture ready on 127.0.0.1:" + str(ARGS.port), flush=True)
    ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler).serve_forever()
