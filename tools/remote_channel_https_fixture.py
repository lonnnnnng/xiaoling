#!/usr/bin/env python3
"""long: 只向临时 HTTPS 隧道提供合成签名收件夹具，不读取用户数据。"""

import base64
import argparse
import json
import pathlib
import struct
import subprocess
import time
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
SIGN_INPUT = ROOT / "sign-input.bin"
SIGNATURE = ROOT / "signature.der"


def prepare_keys():
    if not PRIVATE_KEY.exists():
        subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(PRIVATE_KEY)], check=True)
        PRIVATE_KEY.chmod(0o600)
    subprocess.run(["openssl", "pkey", "-in", str(PRIVATE_KEY), "-pubout", "-outform", "DER", "-out", str(PUBLIC_KEY)], check=True)


def signed_message():
    # long: 每次读取重新签当前时间，避免隧道建立较慢时越过应用的五分钟签名窗口。
    now = int(time.time() * 1000)
    value = {
        "channelId": "real-device-test",
        "messageId": "real-https-20261014-1",
        "senderId": "fixture-sender",
        "conversationKey": "fixture-conversation",
        "text": "真实 HTTPS 真机验收合成正文",
        "receivedAtMillis": now,
        "nonce": "real-https-20261014-unique-nonce",
        "keyId": "fixture-key-1",
    }
    fields = [value[key] for key in (
        "channelId", "messageId", "senderId", "conversationKey", "text",
        "receivedAtMillis", "nonce", "keyId",
    )]
    payload = struct.pack(">I", len(fields))
    for field in fields:
        encoded = str(field).encode("utf-8")
        payload += struct.pack(">I", len(encoded)) + encoded
    SIGN_INPUT.write_bytes(payload)
    subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(PRIVATE_KEY), "-out", str(SIGNATURE), str(SIGN_INPUT)], check=True)
    value["signature"] = base64.urlsafe_b64encode(SIGNATURE.read_bytes()).decode("ascii").rstrip("=")
    return value


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
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
