"""long: 用真实 loopback socket 和独立核心进程验 HTTP 边界、持久重试及有界资源。"""

import base64
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
import copy
import json
from pathlib import Path
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import uuid

import remote_channel_receiver_auth as auth
import remote_channel_sender as sender
import remote_channel_http as http


class AuthenticatedHttpTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="xiaoling-auth-http-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "state"
        self.queue = sender.LocalSenderQueue.initialize(self.root, "http-test", "sender", "key-1")
        self.gateway = auth.AuthenticatedSender.initialize(self.queue)
        self.endpoint = "https://receiver.example/batch"
        self.receiver = "a" * 32
        self.key = Path(self.temp.name) / "receiver.pem"
        self.key.write_bytes(sender.openssl(["ecparam", "-name", "prime256v1", "-genkey", "-noout"]))
        self.key.chmod(0o600)
        public = sender.openssl(["pkey", "-in", str(self.key), "-pubout", "-outform", "DER"])
        self.manifest = {**{name: self.queue.info[name] for name in sender.PEER_FIELDS}, "authVersion": 1,
                         "senderFingerprintSha256": self.queue.info["fingerprintSha256"],
                         "endpointSha256": http.Endpoint(self.endpoint).sha256, "receiverId": self.receiver,
                         "publicKeySpkiBase64": base64.b64encode(public).decode(), "receiverFingerprintSha256": auth.digest(public)}
        self.gateway.authorize(self.manifest, self.manifest["receiverFingerprintSha256"])
        self.queue.enqueue(self.receiver, "message-1", "conversation", "认证 HTTP 合成正文😀")
        self.servers = []
        self.addCleanup(self.close_servers)
        self.server = self.start()

    def start(self, server_type=http.AuthenticatedHttpServer, **limits):
        server = server_type(self.root, self.endpoint, **limits)
        thread = threading.Thread(target=server.serve)
        thread.start()
        self.servers.append((server, thread))
        return server

    def close_servers(self):
        for server, thread in self.servers:
            server.stop.set()
        for server, thread in self.servers:
            thread.join(22)
            self.assertFalse(thread.is_alive())

    def envelope(self, request=None, nonce=None, **changes):
        if request is None:
            request = {**{name: self.queue.info[name] for name in sender.PEER_FIELDS}, "protocolVersion": 1,
                       "operation": "pull", "receiverId": self.receiver, "requestCursor": None}
        now = int(time.time() * 1000)
        proof = {name: value for name, value in self.manifest.items() if name != "publicKeySpkiBase64"}
        proof.update(operation=request["operation"], issuedAtMillis=now, expiresAtMillis=now + 60000,
                     nonce=nonce or uuid.uuid4().hex, requestDigestSha256=auth.digest(auth.request_bytes(request)), signature="")
        proof.update(changes)
        proof["signature"] = base64.urlsafe_b64encode(sender.openssl(["dgst", "-sha256", "-sign", str(self.key)],
                                                                   auth.proof_bytes(proof))).decode().rstrip("=")
        return {"request": request, "proof": proof}

    def wire(self, body, *, method=b"POST", target=b"/batch", headers=None):
        if isinstance(body, dict):
            body = sender.wire_json(body).encode()
        values = [(b"Host", b"receiver.example"), (b"Content-Length", str(len(body)).encode()),
                  (b"Content-Type", b"application/json"), (b"X-Xiaoling-Receiver-Auth", b"1")]
        return method + b" " + target + b" HTTP/1.1\r\n" + b"\r\n".join(
            name + b": " + value for name, value in (values if headers is None else headers)) + b"\r\n\r\n" + body

    def exchange(self, wire, server=None):
        with socket.create_connection(("127.0.0.1", (server or self.server).port), timeout=3) as connection:
            connection.sendall(wire)
            try:
                connection.shutdown(socket.SHUT_WR)
            except OSError:
                pass
            data = bytearray()
            try:
                while part := connection.recv(8192):
                    data.extend(part)
            except ConnectionResetError:
                pass
        head, body = bytes(data).split(b"\r\n\r\n", 1)
        status = int(head.split(b" ")[1])
        self.assertIn(b"Cache-Control: no-store", head)
        self.assertIn(b"Connection: close", head)
        self.assertIn(b"Content-Length: " + str(len(body)).encode(), head)
        return status, json.loads(body)

    def nonce_count(self):
        with closing(sqlite3.connect(self.queue.database)) as connection:
            return connection.execute("SELECT count(*) FROM receiver_auth_requests").fetchone()[0]

    def test_signed_pull_ack_and_service_restart_keep_same_receipt(self):
        envelope = self.envelope()
        status, batch = self.exchange(self.wire(envelope))
        self.assertEqual(200, status)
        self.assertEqual("认证 HTTP 合成正文😀", batch["messages"][0]["text"])
        restarted = self.start()
        self.assertEqual((200, batch), self.exchange(self.wire(envelope), restarted))
        ack = self.envelope(sender.receipt_request(batch))
        status, reply = self.exchange(self.wire(ack), restarted)
        self.assertEqual(200, status)
        self.assertEqual((200, reply), self.exchange(self.wire(ack)))
        self.assertEqual((409, {"error": "AUTH_REPLAY_COMPLETED"}), self.exchange(self.wire(envelope)))
        self.assertEqual(2, self.nonce_count())

    def test_committed_ack_response_disconnect_recovers_without_body_resurrection(self):
        _, batch = self.exchange(self.wire(self.envelope()))
        ack = self.envelope(sender.receipt_request(batch))
        # long: 模拟核心已提交之后的 socket 写失败；失败不能让已提交 ACK 被误报成回滚。
        with patch.object(self.server, "_response", side_effect=OSError("synthetic disconnect")):
            with socket.create_connection(("127.0.0.1", self.server.port), timeout=3) as connection:
                connection.sendall(self.wire(ack))
                self.assertEqual(b"", connection.recv(8192))
        self.assertEqual(0, self.queue.stats()["queuedMessages"])
        status, _ = self.exchange(self.wire(self.envelope(sender.receipt_request(batch))), self.start())
        self.assertEqual(200, status)
        with closing(sqlite3.connect(self.queue.database)) as connection:
            self.assertIsNone(connection.execute("SELECT batch_json FROM receipts").fetchone()[0])
            self.assertIsNone(connection.execute("SELECT text FROM messages").fetchone()[0])

    def test_method_path_query_and_legacy_routes_never_consume_nonce(self):
        body = self.envelope()
        for method, target, status in [(b"GET", b"/batch", 405), (b"POST", b"/batch?x=1", 404),
                (b"POST", b"https://receiver.example/batch", 404), (b"POST", b"/authorize", 404),
                (b"POST", b"/batch/", 404), (b"POST", b"/%62atch", 404)]:
            with self.subTest(method=method, target=target):
                self.assertEqual(status, self.exchange(self.wire(body, method=method, target=target))[0])
        self.assertEqual(403, self.exchange(self.wire(body["request"]))[0])
        self.assertEqual(0, self.nonce_count())

    def test_duplicate_headers_ambiguous_lengths_and_old_routing_headers_are_rejected(self):
        body = sender.wire_json(self.envelope()).encode()
        valid = [(b"Host", b"receiver.example"), (b"Content-Length", str(len(body)).encode()),
                 (b"Content-Type", b"application/json"), (b"X-Xiaoling-Receiver-Auth", b"1")]
        for extra in [[valid[0]], [valid[1]], [valid[3]], [(b"Content-Length", b"0")],
                [(b"Transfer-Encoding", b"chunked")], [(b"Expect", b"100-continue")],
                [(b"Content-Encoding", b"gzip")], [(b"X-Xiaoling-Receiver", self.receiver.encode())],
                [(b"X-Xiaoling-Cursor", b"cursor")], [(b"Host ", b"receiver.example")]]:
            with self.subTest(extra=extra):
                self.assertEqual(400, self.exchange(self.wire(body, headers=valid + extra))[0])
        for length in [b"1, 1", b"+1", b"-1", b"", b"1e3"]:
            self.assertEqual(400, self.exchange(self.wire(body, headers=[valid[0], (b"Content-Length", length), *valid[2:]]))[0])
        self.assertEqual(0, self.nonce_count())

    def test_host_forwarded_endpoint_and_auth_marker_cannot_replace_startup_binding(self):
        raw = sender.wire_json(self.envelope(endpointSha256="0" * 64)).encode()
        self.assertEqual(403, self.exchange(self.wire(raw))[0])
        wire = self.wire(self.envelope()).replace(b"Host: receiver.example", b"Host: other.example\r\nForwarded: host=receiver.example;proto=https")
        self.assertEqual(400, self.exchange(wire)[0])
        for marker in [b"", b"0", b"1, 1"]:
            self.assertEqual(403, self.exchange(self.wire(self.envelope()).replace(b"Receiver-Auth: 1", b"Receiver-Auth: " + marker))[0])
        self.assertEqual(0, self.nonce_count())

    def test_revocation_expiry_conflict_and_fixed_error_body(self):
        envelope = self.envelope()
        self.assertEqual(200, self.exchange(self.wire(envelope))[0])
        changed = self.envelope(nonce=envelope["proof"]["nonce"], issuedAtMillis=envelope["proof"]["issuedAtMillis"] + 1,
                                expiresAtMillis=envelope["proof"]["expiresAtMillis"] + 1)
        self.assertEqual((409, {"error": "AUTH_NONCE_CONFLICT"}), self.exchange(self.wire(changed)))
        self.gateway.revoke(self.receiver)
        self.assertEqual((403, {"error": "AUTH_REJECTED"}), self.exchange(self.wire(envelope)))
        self.gateway.authorize(self.manifest, self.manifest["receiverFingerprintSha256"])
        now = int(time.time() * 1000)
        expired = self.envelope(issuedAtMillis=now - 60000, expiresAtMillis=now - 1)
        self.assertEqual((403, {"error": "AUTH_EXPIRED"}), self.exchange(self.wire(expired)))

    def test_invalid_utf8_duplicate_keys_surrogate_nested_json_and_extra_fields(self):
        valid = self.envelope()
        surrogate = copy.deepcopy(valid)
        surrogate["request"]["requestCursor"] = "\ud800"
        for raw in [b"\xff", b'{"request":{},"request":{},"proof":{}}', b'{"request":NaN,"proof":{}}',
                    json.dumps(surrogate).encode(), b"[" * 1100 + b"]" * 1100, b"{} trailing",
                    sender.wire_json({**valid, "extra": 1}).encode()]:
            with self.subTest(raw=raw[:40]):
                status, result = self.exchange(self.wire(raw))
                self.assertIn(status, (400, 403))
                self.assertEqual({"error"}, set(result))
        self.assertEqual(0, self.nonce_count())

    def test_header_body_limits_short_read_and_folded_headers(self):
        raw = sender.wire_json(self.envelope()).encode()
        self.assertEqual(413, self.exchange(self.wire(raw).replace(b"Content-Length: " + str(len(raw)).encode(), b"Content-Length: 65537"))[0])
        self.assertEqual(431, self.exchange(self.wire(raw).replace(b"Host: receiver.example", b"Host: receiver.example\r\nX-Pad: " + b"a" * 8192))[0])
        self.assertEqual(431, self.exchange(self.wire(raw).replace(b"Host: receiver.example", b"Host: receiver.example\r\n" + b"\r\n".join(b"X-" + str(n).encode() + b": a" for n in range(49))))[0])
        self.assertEqual(400, self.exchange(self.wire(raw)[:-1])[0])
        self.assertEqual(400, self.exchange(self.wire(raw).replace(b"Host: receiver.example", b"Host: receiver.example\r\n folded"))[0])
        self.assertEqual(415, self.exchange(self.wire(raw).replace(b"application/json", b"text/plain"))[0])
        self.assertEqual(0, self.nonce_count())

    def test_absolute_slow_header_and_body_deadlines_release_connection_capacity(self):
        server = self.start(max_connections=1, read_seconds=0.2, request_seconds=0.7)
        for fragment in [b"POST /batch HTTP/1.1\r\n", self.wire(b"ab")[:-2]]:
            with socket.create_connection(("127.0.0.1", server.port), timeout=2) as connection:
                connection.sendall(fragment)
                time.sleep(0.06)
                connection.sendall(b"a")
                started = time.monotonic()
                data = connection.recv(8192)
                self.assertIn(b"408 Request Timeout", data)
                self.assertLess(time.monotonic() - started, 0.4)
        self.assertEqual(200, self.exchange(self.wire(self.envelope()), server)[0])

    def test_connection_and_global_rate_limits_are_bounded(self):
        server = self.start(max_connections=1, read_seconds=0.4, request_seconds=1)
        with socket.create_connection(("127.0.0.1", server.port), timeout=2) as slow:
            slow.sendall(b"POST ")
            time.sleep(0.05)
            self.assertEqual((503, {"error": "CONNECTION_CAPACITY"}), self.exchange(self.wire(self.envelope()), server))
        limited = self.start(rate_capacity=1, rate_per_second=0.01)
        self.assertEqual(200, self.exchange(self.wire(self.envelope()), limited)[0])
        self.assertEqual((429, {"error": "RATE_LIMITED"}), self.exchange(self.wire(self.envelope()), limited))

    def test_concurrent_network_nonce_retry_and_exact_body_limit(self):
        raw = sender.wire_json(self.envelope()).encode()
        with ThreadPoolExecutor(max_workers=3) as executor:
            responses = list(executor.map(lambda _: self.exchange(self.wire(raw)), range(3)))
        self.assertTrue(all(result == responses[0] for result in responses))
        self.assertEqual(200, responses[0][0]); self.assertEqual(1, self.nonce_count())
        padded = raw + b" " * (sender.MAX_WIRE_BYTES - len(raw))
        self.assertEqual(responses[0], self.exchange(self.wire(padded)))

    def test_total_core_processing_deadline_kills_child_and_releases_sqlite_lock(self):
        real_popen = subprocess.Popen
        lock_flag = Path(self.temp.name) / "grandchild-locked"
        def locked_process(*args, **kwargs):
            child = "import sqlite3,time,sys,pathlib; c=sqlite3.connect(sys.argv[1]); c.execute('BEGIN IMMEDIATE'); pathlib.Path(sys.argv[2]).write_text('locked'); time.sleep(5)"
            parent = "import subprocess,sys; subprocess.run([sys.executable,'-c',sys.argv[1],sys.argv[2],sys.argv[3]])"
            return real_popen([sys.executable, "-c", parent, child, str(self.queue.database), str(lock_flag)], **kwargs)
        started = time.monotonic()
        with patch.object(http.subprocess, "Popen", side_effect=locked_process):
            with self.assertRaises(http.HttpError) as caught:
                http.execute_core(self.root, self.manifest["endpointSha256"], b"{}", time.monotonic() + 0.35)
        self.assertEqual("PROCESSING_TIMEOUT", caught.exception.code)
        self.assertTrue(lock_flag.exists())
        self.assertLess(time.monotonic() - started, 1)
        self.assertEqual(200, self.exchange(self.wire(self.envelope()))[0])

    def test_startup_rejects_noncanonical_endpoint_and_missing_auth_state(self):
        for endpoint in ["http://receiver.example/batch", "https://Receiver.example/batch", "https://receiver.example:443/batch",
                "https://receiver.example/batch?", "https://receiver.example/batch#x", "https://user@receiver.example/batch",
                "https://127.0.0.1/batch", "https://receiver.example/%62atch", "https://receiver.example/../batch"]:
            with self.subTest(endpoint=endpoint), self.assertRaises((http.HttpError, ValueError)):
                http.Endpoint(endpoint)
        state = sender.LocalSenderQueue.initialize(Path(self.temp.name) / "no-auth", "test", "sender", "key")
        with self.assertRaises(sender.QueueError) as caught:
            http.AuthenticatedHttpServer(state.root, self.endpoint)
        self.assertEqual("AUTH_STATE_UNAVAILABLE", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
