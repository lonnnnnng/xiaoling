#!/usr/bin/env python3
"""long: 本地接收端公钥授权与签名请求入口；没有 HTTP listener，旧管理 CLI 不属于远程认证入口。"""

import argparse
import base64
import hashlib
import re
import sqlite3
import struct
import sys
import tempfile

import remote_channel_sender as sender

TTL_MILLIS = 60000
FUTURE_SKEW_MILLIS = 5000
MAX_AUTH_REQUESTS = 4096
MANIFEST_FIELDS = sender.PEER_FIELDS | {"authVersion", "senderFingerprintSha256", "endpointSha256", "receiverId",
                                       "publicKeySpkiBase64", "receiverFingerprintSha256"}
PROOF_FIELDS = sender.PEER_FIELDS | {"authVersion", "operation", "senderFingerprintSha256", "endpointSha256", "receiverId",
                                    "receiverFingerprintSha256", "issuedAtMillis", "expiresAtMillis", "nonce", "requestDigestSha256", "signature"}
PULL_FIELDS = sender.PEER_FIELDS | {"protocolVersion", "operation", "receiverId", "requestCursor"}
AUTH_TABLES = {"receiver_auth_metadata", "receiver_authorizations", "receiver_auth_requests"}
AUTH_SCHEMA = (
    "CREATE TABLE receiver_auth_metadata (id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL)",
    "CREATE TABLE receiver_authorizations (receiver TEXT PRIMARY KEY REFERENCES receivers(id), manifest_json TEXT NOT NULL, enabled INTEGER NOT NULL CHECK(enabled IN (0,1)))",
    "CREATE TABLE receiver_auth_requests (receiver TEXT NOT NULL REFERENCES receiver_authorizations(receiver), nonce TEXT NOT NULL, proof_digest TEXT NOT NULL, reference_json TEXT NOT NULL, PRIMARY KEY(receiver,nonce))",
)


def digest(payload):
    return hashlib.sha256(payload).hexdigest()


def hash_value(value):
    sender.require(type(value) is str and re.fullmatch(r"[0-9a-f]{64}", value) is not None, "AUTH_REJECTED")
    return value


def request_bytes(request):
    sender.require(type(request) is dict and type(request.get("protocolVersion")) is int and request["protocolVersion"] == 1,
                   "AUTH_REJECTED")
    operation = request.get("operation")
    sender.require(operation in ("pull", "ack") and set(request) == (PULL_FIELDS if operation == "pull" else sender.ACK_FIELDS),
                   "AUTH_REJECTED")
    for name in sender.PEER_FIELDS:
        sender.identity(request[name])
    sender.receiver_id(request["receiverId"])
    data = sender.field("xiaoling-receiver-request-v1") + struct.pack(">I", 1)
    data += b"".join(sender.field(request[name]) for name in ("channelId", "senderId", "keyId", "operation", "receiverId"))
    if operation == "pull":
        sender.cursor(request["requestCursor"])
        return data + sender.field(request["requestCursor"])
    sender.require(type(request["batchId"]) is str and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,199}", request["batchId"]) is not None,
                   "AUTH_REJECTED")
    hash_value(request["batchDigestSha256"])
    sender.cursor(request["nextCursor"])
    values = request["messageIds"]
    sender.require(type(values) is list and len(values) <= sender.MAX_BATCH_MESSAGES, "AUTH_REJECTED")
    for value in values:
        sender.identity(value)
    sender.require(len(set(values)) == len(values), "AUTH_REJECTED")
    data += b"".join(sender.field(request[name]) for name in ("batchId", "batchDigestSha256", "nextCursor"))
    return data + struct.pack(">I", len(values)) + b"".join(sender.field(value) for value in values)


def proof_bytes(proof):
    sender.require(type(proof) is dict and set(proof) == PROOF_FIELDS and type(proof["authVersion"]) is int and proof["authVersion"] == 1,
                   "AUTH_REJECTED")
    sender.require(proof["operation"] in ("pull", "ack"), "AUTH_REJECTED")
    for name in sender.PEER_FIELDS:
        sender.identity(proof[name])
    sender.receiver_id(proof["receiverId"])
    for name in ("senderFingerprintSha256", "endpointSha256", "receiverFingerprintSha256", "requestDigestSha256"):
        hash_value(proof[name])
    sender.require(type(proof["nonce"]) is str and re.fullmatch(r"[0-9a-f]{32}", proof["nonce"]) is not None, "AUTH_REJECTED")
    issued, expires = proof["issuedAtMillis"], proof["expiresAtMillis"]
    sender.require(type(issued) is int and type(expires) is int and 0 < issued < expires <= 9223372036854775807 and
                   expires - issued <= TTL_MILLIS, "AUTH_REJECTED")
    data = sender.field("xiaoling-receiver-auth-v1") + struct.pack(">I", 1)
    data += b"".join(sender.field(proof[name]) for name in ("channelId", "senderId", "keyId", "operation", "senderFingerprintSha256",
                        "endpointSha256", "receiverId", "receiverFingerprintSha256", "nonce", "requestDigestSha256"))
    return data + struct.pack(">qq", issued, expires)


def validate_manifest(value, info):
    sender.require(type(value) is dict and set(value) == MANIFEST_FIELDS and type(value["authVersion"]) is int and value["authVersion"] == 1,
                   "AUTH_REJECTED")
    sender.require(all(value[name] == info[name] for name in sender.PEER_FIELDS) and
                   value["senderFingerprintSha256"] == info["fingerprintSha256"], "AUTH_REJECTED")
    sender.receiver_id(value["receiverId"])
    hash_value(value["endpointSha256"])
    hash_value(value["receiverFingerprintSha256"])
    raw = value["publicKeySpkiBase64"]
    sender.require(type(raw) is str and len(raw) <= 684, "AUTH_REJECTED")
    try:
        public = base64.b64decode(raw, validate=True)
        sender.require(80 <= len(public) <= 512 and base64.b64encode(public).decode("ascii") == raw, "AUTH_REJECTED")
        canonical = sender.openssl(["pkey", "-pubin", "-inform", "DER", "-pubout", "-outform", "DER"], public)
        description = sender.openssl(["pkey", "-pubin", "-inform", "DER", "-text", "-noout"], public)
        sender.require(canonical == public and b"ASN1 OID: prime256v1" in description and
                       digest(public) == value["receiverFingerprintSha256"], "AUTH_REJECTED")
    except (ValueError, sender.QueueError):
        raise sender.QueueError("AUTH_REJECTED") from None
    return public


def verify(public, payload, signature, directory):
    sender.require(type(signature) is str and re.fullmatch(r"[A-Za-z0-9_-]{1,128}", signature) is not None, "AUTH_REJECTED")
    try:
        raw = base64.b64decode(signature + "=" * (-len(signature) % 4), altchars=b"-_", validate=True)
        sender.require(64 <= len(raw) <= 80, "AUTH_REJECTED")
        with tempfile.NamedTemporaryFile(dir=directory) as key, tempfile.NamedTemporaryFile(dir=directory) as signed:
            key.write(public); key.flush()
            signed.write(raw); signed.flush()
            sender.openssl(["dgst", "-sha256", "-keyform", "DER", "-verify", key.name, "-signature", signed.name], payload)
    except (ValueError, sender.QueueError):
        raise sender.QueueError("AUTH_REJECTED") from None


class AuthenticatedSender:
    def __init__(self, queue):
        self.queue = queue
        with queue._transaction() as connection:
            self._state(connection)

    def _metadata(self):
        return {"authVersion": 1, "senderFingerprintSha256": self.queue.info["fingerprintSha256"]}

    def _state(self, connection):
        tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        sender.require(AUTH_TABLES <= tables, "AUTH_STATE_UNAVAILABLE")
        rows = connection.execute("SELECT value FROM receiver_auth_metadata").fetchall()
        sender.require(len(rows) == 1, "AUTH_STATE_UNAVAILABLE")
        value = sender.stored_json(rows[0][0])
        sender.require(type(value) is dict and type(value.get("authVersion")) is int and value == self._metadata(), "AUTH_STATE_UNAVAILABLE")

    @classmethod
    def initialize(cls, queue):
        with queue._transaction() as connection:
            tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            # long: 认证扩展只能由本地管理员显式建立；部分缺失或已经存在的表都不能自动补成空授权账本。
            sender.require(not AUTH_TABLES & tables, "AUTH_ALREADY_EXISTS")
            for statement in AUTH_SCHEMA:
                connection.execute(statement)
            metadata = {"authVersion": 1, "senderFingerprintSha256": queue.info["fingerprintSha256"]}
            connection.execute("INSERT INTO receiver_auth_metadata VALUES (1,?)", (sender.wire_json(metadata),))
        return cls(queue)

    def authorize(self, manifest, trusted_fingerprint):
        validate_manifest(manifest, self.queue.info)
        sender.require(hash_value(trusted_fingerprint) == manifest["receiverFingerprintSha256"], "AUTH_FINGERPRINT_MISMATCH")
        with self.queue._transaction() as connection:
            self._state(connection)
            receiver = manifest["receiverId"]
            old = connection.execute("SELECT * FROM receiver_authorizations WHERE receiver=?", (receiver,)).fetchone()
            if old is not None:
                self._manifest(old)
                # long: 同一路由的公钥和来源不可替换；显式重新授权只恢复原绑定，旧 nonce 历史继续约束重放。
                sender.require(sender.stored_json(old["manifest_json"]) == manifest, "AUTH_BINDING_CONFLICT")
                connection.execute("UPDATE receiver_authorizations SET enabled=1 WHERE receiver=?", (receiver,))
            else:
                sender.require(connection.execute("SELECT count(*) FROM receiver_authorizations").fetchone()[0] < sender.MAX_RECEIVERS,
                               "RECEIVER_CAPACITY")
                if connection.execute("SELECT 1 FROM receivers WHERE id=?", (receiver,)).fetchone() is None:
                    sender.require(connection.execute("SELECT count(*) FROM receivers").fetchone()[0] < sender.MAX_RECEIVERS, "RECEIVER_CAPACITY")
                    connection.execute("INSERT INTO receivers VALUES (?,NULL,NULL)", (receiver,))
                connection.execute("INSERT INTO receiver_authorizations VALUES (?,?,1)", (receiver, sender.wire_json(manifest)))
        return {"receiverId": receiver, "receiverFingerprintSha256": trusted_fingerprint, "enabled": True}

    def _manifest(self, row):
        manifest = sender.stored_json(row["manifest_json"])
        try:
            public = validate_manifest(manifest, self.queue.info)
            sender.require(manifest["receiverId"] == row["receiver"], "AUTH_STATE_UNAVAILABLE")
            return manifest, public
        except sender.QueueError:
            raise sender.QueueError("AUTH_STATE_UNAVAILABLE") from None

    def revoke(self, receiver):
        sender.receiver_id(receiver)
        with self.queue._transaction() as connection:
            self._state(connection)
            row = connection.execute("SELECT * FROM receiver_authorizations WHERE receiver=?", (receiver,)).fetchone()
            sender.require(row is not None, "AUTH_REJECTED")
            self._manifest(row)
            # long: 撤销先提交禁用位，不删除排队消息、收据或已用 nonce；后续请求和缓存重试都必须重新读许可。
            connection.execute("UPDATE receiver_authorizations SET enabled=0 WHERE receiver=?", (receiver,))
        return {"receiverId": receiver, "enabled": False}

    def _fresh(self, proof):
        now = self.queue._now()
        sender.require(proof["issuedAtMillis"] <= now + FUTURE_SKEW_MILLIS and now < proof["expiresAtMillis"], "AUTH_EXPIRED")
        return now

    def execute(self, envelope, endpoint_sha256):
        hash_value(endpoint_sha256)
        sender.require(type(envelope) is dict and set(envelope) == {"request", "proof"}, "AUTH_REJECTED")
        request, proof = envelope["request"], envelope["proof"]
        request_digest = digest(request_bytes(request))
        content = proof_bytes(proof)
        self._fresh(proof)
        sender.require(all(proof[name] == request[name] for name in sender.PEER_FIELDS | {"operation", "receiverId"}) and
                       proof["requestDigestSha256"] == request_digest and proof["endpointSha256"] == endpoint_sha256, "AUTH_REJECTED")
        with self.queue._transaction() as connection:
            self._state(connection)
            row = connection.execute("SELECT * FROM receiver_authorizations WHERE receiver=?", (proof["receiverId"],)).fetchone()
            sender.require(row is not None and row["enabled"] == 1, "AUTH_REJECTED")
            manifest, public = self._manifest(row)
            sender.require(all(proof[name] == manifest[name] for name in MANIFEST_FIELDS - {"publicKeySpkiBase64"}), "AUTH_REJECTED")
            verify(public, content, proof["signature"], self.queue.root)
            # long: 等待写锁和公钥验签也会消耗时间；进入队列前重新检查有效期，过期请求不能借旧的 now 被接受。
            now = self._fresh(proof)
            proof_digest = digest(content)
            old = connection.execute("SELECT * FROM receiver_auth_requests WHERE receiver=? AND nonce=?", (proof["receiverId"], proof["nonce"])).fetchone()
            if old is not None:
                sender.require(old["proof_digest"] == proof_digest, "AUTH_NONCE_CONFLICT")
                response = self._retry(connection, request, sender.stored_json(old["reference_json"]))
                self._fresh(proof)
                return response
            sender.require(connection.execute("SELECT count(*) FROM receiver_auth_requests").fetchone()[0] < MAX_AUTH_REQUESTS, "AUTH_CAPACITY")
            # long: 验签、nonce 消费和原队列 GET/ACK 在同一事务；写入失败不会留下“请求已用但消息尚未提交”的间隙。
            response = self.queue._pull(connection, request["receiverId"], request["requestCursor"], now) if request["operation"] == "pull" else self.queue._acknowledge(connection, request)
            # long: 发送响应也要签名；若期间请求过期，回滚本轮投递/ACK 与 nonce，不能提交迟到的授权动作。
            self._fresh(proof)
            reference = sender.receipt_request(response) if request["operation"] == "pull" else request
            connection.execute("INSERT INTO receiver_auth_requests VALUES (?,?,?,?)",
                               (proof["receiverId"], proof["nonce"], proof_digest, sender.wire_json(reference)))
            return response

    def _retry(self, connection, request, reference):
        sender.require(type(reference) is dict and set(reference) == sender.ACK_FIELDS, "AUTH_STATE_UNAVAILABLE")
        try:
            request_bytes(reference)
            sender.require(reference["operation"] == "ack", "AUTH_STATE_UNAVAILABLE")
        except sender.QueueError:
            raise sender.QueueError("AUTH_STATE_UNAVAILABLE") from None
        row = connection.execute("SELECT * FROM receipts WHERE batch_id=?", (reference["batchId"],)).fetchone()
        sender.require(row is not None and sender.stored_json(row["request_json"]) == reference, "AUTH_STATE_UNAVAILABLE")
        if request["operation"] == "ack":
            sender.require(reference == request and row["ack_json"] is not None, "AUTH_STATE_UNAVAILABLE")
            return self.queue._acknowledge(connection, request)
        # long: nonce 账本只保存无正文收据引用；ACK 已逻辑删除快照后，不从另一份明文缓存复活旧 GET 正文。
        sender.require(row["batch_json"] is not None, "AUTH_REPLAY_COMPLETED")
        response = sender.stored_json(row["batch_json"])
        self.queue._validate_cached_batch(response)
        sender.require(all(response[name] == request[name] for name in sender.PEER_FIELDS | {"receiverId", "requestCursor"}) and
                       sender.receipt_request(response) == reference, "AUTH_STATE_UNAVAILABLE")
        self.queue._verify(sender.batch_bytes(response), response["signature"])
        return response


def main():
    parser = argparse.ArgumentParser(description="本地接收端认证核心；不监听 HTTP，不授权 Agent 执行")
    parser.add_argument("--state-dir", required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("init")
    authorize = commands.add_parser("authorize", help="登记清单从 stdin 读取，指纹须经独立渠道核对")
    authorize.add_argument("--fingerprint", required=True)
    revoke = commands.add_parser("revoke")
    revoke.add_argument("--receiver-id", required=True)
    execute = commands.add_parser("execute", help="签名请求从 stdin 读取；地址摘要来自可信适配配置")
    execute.add_argument("--endpoint-sha256", required=True)
    args = parser.parse_args()
    try:
        queue = sender.LocalSenderQueue(args.state_dir)
        if args.command == "init":
            result = AuthenticatedSender.initialize(queue)._metadata()
        else:
            gateway = AuthenticatedSender(queue)
            if args.command == "revoke":
                result = gateway.revoke(args.receiver_id)
            else:
                raw = sys.stdin.buffer.read(sender.MAX_WIRE_BYTES + 1)
                sender.require(len(raw) <= sender.MAX_WIRE_BYTES, "AUTH_REJECTED")
                value = sender.parse_json(raw.decode("utf-8"))
                result = gateway.authorize(value, args.fingerprint) if args.command == "authorize" else gateway.execute(value, args.endpoint_sha256)
        sys.stdout.write(sender.wire_json(result) + "\n")
        sys.stdout.flush()
        return 0
    except sender.QueueError as error:
        print("receiver auth error: " + str(error), file=sys.stderr)
    except BrokenPipeError:
        return 1
    except (OSError, sqlite3.Error, UnicodeError, ValueError, KeyError, TypeError):
        print("receiver auth error: AUTH_STATE_UNAVAILABLE", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
