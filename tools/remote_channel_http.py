#!/usr/bin/env python3
"""long: 固定 HTTPS 地址的有界 loopback 认证入口；TLS 和公网防护由外层代理承担。"""

import argparse
import hashlib
import ipaddress
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import threading
import time
from urllib.parse import urlsplit

import remote_channel_receiver_auth as auth
import remote_channel_sender as sender

MAX_HEADERS = 8192
MAX_HEADER_COUNT = 48
STATUS = {200: "OK", 400: "Bad Request", 403: "Forbidden", 404: "Not Found", 405: "Method Not Allowed",
          408: "Request Timeout", 409: "Conflict", 413: "Content Too Large", 415: "Unsupported Media Type",
          429: "Too Many Requests", 431: "Request Header Fields Too Large", 503: "Service Unavailable"}
CORE_ERRORS = {
    "INVALID_REQUEST": 400, "AUTH_REJECTED": 403, "AUTH_EXPIRED": 403,
    "AUTH_NONCE_CONFLICT": 409, "AUTH_REPLAY_COMPLETED": 409, "ACK_REQUIRED": 409,
    "CURSOR_MISMATCH": 409, "ACK_MISMATCH": 409,
    "AUTH_CAPACITY": 503, "RECEIPT_CAPACITY": 503, "MESSAGE_TOO_LARGE": 503,
    "AUTH_STATE_UNAVAILABLE": 503, "STATE_UNAVAILABLE": 503, "SIGNING_UNAVAILABLE": 503, "INVALID_CLOCK": 503,
}
HEADER_NAME = re.compile(rb"[!#$%&'*+.^_`|~0-9A-Za-z-]+")


class HttpError(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code


def require(condition, status=400, code="INVALID_HTTP"):
    if not condition:
        raise HttpError(status, code)


class Endpoint:
    def __init__(self, url):
        # long: 只接受可逐字绑定的窄格式，避免 Python 与手机 URL 规范化差异变成另一条授权地址。
        require(type(url) is str and len(url) <= 2048 and url.isascii(), 400, "INVALID_ENDPOINT")
        parsed = urlsplit(url)
        host = parsed.hostname or ""
        require(re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*", host) is not None and "." in host and
                all(re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", part) for part in host.split(".")), 400, "INVALID_ENDPOINT")
        try:
            ipaddress.ip_address(host)
        except ValueError:
            pass
        else:
            raise HttpError(400, "INVALID_ENDPOINT")
        require(parsed.scheme == "https" and parsed.netloc == host and
                re.fullmatch(r"/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]*", parsed.path) is not None and
                url == "https://" + host + parsed.path, 400, "INVALID_ENDPOINT")
        self.url, self.host, self.path = url, host, parsed.path
        self.sha256 = hashlib.sha256(url.encode("utf-8")).hexdigest()


def remaining(deadline):
    value = deadline - time.monotonic()
    if value <= 0:
        raise HttpError(408, "REQUEST_TIMEOUT")
    return value


def execute_core(state_dir, endpoint_hash, raw, deadline):
    command = [sys.executable, str(Path(auth.__file__).resolve()), "--state-dir", str(state_dir),
               "execute", "--endpoint-sha256", endpoint_hash]
    # long: 核心中的多个 SQLite/OpenSSL 超时不是整请求上限；独立进程使累计处理时间也可终止。
    with subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          start_new_session=True) as process:
        try:
            output, error = process.communicate(raw, timeout=remaining(deadline))
        except (subprocess.TimeoutExpired, HttpError):
            # long: 同时终止当前 OpenSSL 子进程并回收主进程；超时可能发生在 COMMIT 后，不能宣称回滚。
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.communicate()
            raise HttpError(503, "PROCESSING_TIMEOUT") from None
        if process.returncode != 0:
            match = re.fullmatch(rb"receiver auth error: ([A-Z_]+)\n", error)
            code = match[1].decode("ascii") if match else "SERVICE_UNAVAILABLE"
            if code not in CORE_ERRORS:
                code = "SERVICE_UNAVAILABLE"
            raise HttpError(CORE_ERRORS.get(code, 503), code)
        require(0 < len(output) <= sender.MAX_WIRE_BYTES + 1 and not error, 503, "SERVICE_UNAVAILABLE")
        value = sender.parse_json(output.decode("utf-8").strip())
        encoded = sender.wire_json(value).encode("utf-8")
        require(len(encoded) <= sender.MAX_WIRE_BYTES, 503, "SERVICE_UNAVAILABLE")
        return encoded


class AuthenticatedHttpServer:
    def __init__(self, state_dir, endpoint, port=0, *, max_connections=4, rate_capacity=60, rate_per_second=1,
                 request_seconds=15, read_seconds=5):
        require(type(port) is int and 0 <= port <= 65535, 400, "INVALID_PORT")
        require(1 <= max_connections <= 16 and 1 <= rate_capacity <= 120 and 0 < rate_per_second <= 10 and
                0 < read_seconds <= request_seconds <= 20, 400, "INVALID_LIMIT")
        self.endpoint = Endpoint(endpoint)
        self.state_dir = sender.secure_path(state_dir)
        # long: 启动只校验既有队列与授权扩展，缺状态时拒绝监听，绝不自动创建空账本或替换发送私钥。
        auth.AuthenticatedSender(sender.LocalSenderQueue(self.state_dir))
        self.request_seconds, self.read_seconds = request_seconds, read_seconds
        self.capacity, self.rate = rate_capacity, rate_per_second
        self.tokens, self.refilled = float(rate_capacity), time.monotonic()
        self.slots = threading.BoundedSemaphore(max_connections)
        self.stop = threading.Event()
        self.lock = threading.Lock()
        self.threads = set()
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.socket.bind(("127.0.0.1", port))
            self.socket.listen(16)
            self.socket.settimeout(0.2)
        except BaseException:
            self.socket.close()
            raise
        self.port = self.socket.getsockname()[1]

    def _admit(self):
        now = time.monotonic()
        self.tokens = min(self.capacity, self.tokens + (now - self.refilled) * self.rate)
        self.refilled = now
        if self.tokens < 1:
            return False
        self.tokens -= 1
        return True

    def serve(self):
        try:
            while not self.stop.is_set():
                try:
                    connection, _ = self.socket.accept()
                except socket.timeout:
                    continue
                # long: 使用全局令牌而非代理声称的客户端 IP；慢连接也计数，不建立无限等待队列。
                if not self._admit():
                    self._reject(connection, 429, "RATE_LIMITED")
                    continue
                if not self.slots.acquire(blocking=False):
                    self._reject(connection, 503, "CONNECTION_CAPACITY")
                    continue
                thread = threading.Thread(target=self._handle, args=(connection,), daemon=True)
                with self.lock:
                    self.threads.add(thread)
                thread.start()
        finally:
            self.socket.close()
            with self.lock:
                threads = tuple(self.threads)
            for thread in threads:
                thread.join()

    def _reject(self, connection, status, code):
        with connection:
            try:
                # long: 忙碌路径不再占用线程或等待对端读取；尽力交付固定错误后立即断开。
                connection.setblocking(False)
                connection.send(self._response(status, sender.wire_json({"error": code}).encode()))
            except OSError:
                pass

    @staticmethod
    def _response(status, body):
        headers = (f"HTTP/1.1 {status} {STATUS[status]}\r\nContent-Type: application/json; charset=utf-8\r\n"
                   f"Content-Length: {len(body)}\r\nCache-Control: no-store\r\nConnection: close\r\n"
                   "X-Content-Type-Options: nosniff\r\n\r\n")
        return headers.encode("ascii") + body

    def _recv(self, connection, count, deadline):
        connection.settimeout(remaining(deadline))
        try:
            data = connection.recv(count)
        except socket.timeout:
            raise HttpError(408, "REQUEST_TIMEOUT") from None
        require(data, 400, "INCOMPLETE_REQUEST")
        return data

    def _request(self, connection, deadline):
        header_deadline = min(deadline, time.monotonic() + self.read_seconds)
        data = bytearray()
        while b"\r\n\r\n" not in data:
            data.extend(self._recv(connection, min(1024, MAX_HEADERS + 1 - len(data)), header_deadline))
            require(len(data) <= MAX_HEADERS or b"\r\n\r\n" in data, 431, "HEADERS_TOO_LARGE")
        head, body = bytes(data).split(b"\r\n\r\n", 1)
        require(len(head) + 4 <= MAX_HEADERS, 431, "HEADERS_TOO_LARGE")
        lines = head.split(b"\r\n")
        parts = lines[0].split(b" ")
        require(len(parts) == 3 and parts[2] == b"HTTP/1.1" and
                HEADER_NAME.fullmatch(parts[0]) is not None)
        require(parts[0] == b"POST", 405, "METHOD_REJECTED")
        require(parts[1] == self.endpoint.path.encode("ascii"), 404, "ENDPOINT_REJECTED")
        require(len(lines) - 1 <= MAX_HEADER_COUNT, 431, "HEADERS_TOO_LARGE")
        headers = {}
        for line in lines[1:]:
            name, separator, value = line.partition(b":")
            require(separator and HEADER_NAME.fullmatch(name) is not None and
                    all(byte == 9 or 32 <= byte <= 126 for byte in value))
            name = name.lower()
            # long: 拒绝所有重复头，比仅检查关键头更窄；折行、冒号前空白、CL 合并值都不能重新解释。
            require(name not in headers)
            headers[name] = value.strip(b" \t")
        require(headers.get(b"host") == self.endpoint.host.encode("ascii"), 400, "HOST_REJECTED")
        require(b"transfer-encoding" not in headers and b"expect" not in headers and
                b"content-encoding" not in headers and b"x-xiaoling-receiver" not in headers and
                b"x-xiaoling-cursor" not in headers)
        require(headers.get(b"x-xiaoling-receiver-auth") == b"1", 403, "AUTH_REJECTED")
        require(headers.get(b"content-type", b"").lower() in
                (b"application/json", b"application/json; charset=utf-8"), 415, "JSON_REQUIRED")
        length = headers.get(b"content-length", b"")
        require(re.fullmatch(rb"[0-9]{1,6}", length) is not None)
        count = int(length)
        require(count <= sender.MAX_WIRE_BYTES, 413, "BODY_TOO_LARGE")
        require(count > 0 and len(body) <= count)
        body_deadline = min(deadline, time.monotonic() + self.read_seconds)
        while len(body) < count:
            body += self._recv(connection, min(8192, count - len(body)), body_deadline)
        try:
            value = sender.parse_json(body.decode("utf-8"))
            # long: 转为规范 UTF-8 还会拒绝 JSON 转义的孤立 surrogate；错误只返回固定码，不回显输入。
            normalized = sender.wire_json(value).encode("utf-8")
            require(type(value) is dict and set(value) == {"request", "proof"}, 403, "AUTH_REJECTED")
            auth.request_bytes(value["request"])
            auth.proof_bytes(value["proof"])
        except (sender.QueueError, UnicodeError, ValueError, KeyError, TypeError, RecursionError):
            raise HttpError(400, "INVALID_JSON") from None
        return normalized

    def execute(self, raw, deadline):
        return execute_core(self.state_dir, self.endpoint.sha256, raw, deadline)

    def _handle(self, connection):
        deadline = time.monotonic() + self.request_seconds
        with connection:
            try:
                raw = self._request(connection, deadline)
                body = self.execute(raw, deadline)
                status = 200
            except HttpError as error:
                status, body = error.status, sender.wire_json({"error": error.code}).encode("utf-8")
            except (OSError, sender.QueueError, ValueError, UnicodeError, RecursionError):
                status, body = 503, b'{"error":"SERVICE_UNAVAILABLE"}'
            try:
                connection.settimeout(remaining(deadline))
                connection.sendall(self._response(status, body))
            except (OSError, HttpError):
                pass
            finally:
                self.slots.release()
                with self.lock:
                    self.threads.discard(threading.current_thread())


def main():
    parser = argparse.ArgumentParser(description="loopback 认证 POST 服务；外层必须提供固定 HTTPS 地址与 TLS")
    parser.add_argument("--state-dir", required=True)
    parser.add_argument("--endpoint", required=True, help="与手机登记清单相同的完整 HTTPS 地址")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    try:
        server = AuthenticatedHttpServer(args.state_dir, args.endpoint, args.port)
        for signum in (signal.SIGINT, signal.SIGTERM):
            signal.signal(signum, lambda *_: server.stop.set())
        print(f"authenticated HTTP ready: 127.0.0.1:{server.port}", flush=True)
        server.serve()
        return 0
    except (HttpError, sender.QueueError, OSError, ValueError):
        print("authenticated HTTP error: STARTUP_REJECTED", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
