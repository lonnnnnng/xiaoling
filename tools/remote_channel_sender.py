#!/usr/bin/env python3
"""long: 本地管理的持久发送队列；不监听网络，远程入口须另行验证接收端身份。"""

import argparse
import base64
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import struct
import subprocess
import sys
import tempfile
import time
from urllib.parse import quote
import uuid

MAX_RECEIVERS = 32
MAX_MESSAGES = 1024
MAX_RECEIPTS = 4096
MAX_BATCH_MESSAGES = 8
MAX_WIRE_BYTES = 65536
BATCH_TTL_MILLIS = 60000
PEER_FIELDS = {"channelId", "senderId", "keyId"}
ACK_FIELDS = PEER_FIELDS | {"protocolVersion", "operation", "receiverId", "batchId", "batchDigestSha256", "nextCursor", "messageIds"}
BATCH_FIELDS = PEER_FIELDS | {"protocolVersion", "receiverId", "batchId", "requestCursor", "nextCursor", "issuedAtMillis", "expiresAtMillis", "messages", "signature"}
MESSAGE_FIELDS = PEER_FIELDS | {"messageId", "conversationKey", "text", "receivedAtMillis", "nonce", "signature"}
SCHEMA = (
    "CREATE TABLE metadata (id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL)",
    "CREATE TABLE receivers (id TEXT PRIMARY KEY, cursor TEXT, pending TEXT)",
    "CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, receiver TEXT NOT NULL REFERENCES receivers(id), conversation TEXT, text TEXT, content_hash TEXT NOT NULL, nonce TEXT UNIQUE NOT NULL, acknowledged INTEGER NOT NULL CHECK(acknowledged IN (0,1)))",
    "CREATE INDEX message_queue ON messages(receiver, acknowledged, seq)",
    "CREATE TABLE deliveries (id TEXT PRIMARY KEY, receiver TEXT NOT NULL REFERENCES receivers(id), request_cursor TEXT, next_cursor TEXT NOT NULL, message_ids TEXT NOT NULL, acknowledged INTEGER NOT NULL CHECK(acknowledged IN (0,1)))",
    "CREATE TABLE receipts (batch_id TEXT PRIMARY KEY, delivery TEXT NOT NULL REFERENCES deliveries(id), request_json TEXT NOT NULL, batch_json TEXT, ack_json TEXT)",
    "CREATE INDEX delivery_receipts ON receipts(delivery)",
)


class QueueError(Exception):
    """long: 只向调用者返回固定错误码，数据库异常不能携带正文或密钥材料进入日志。"""


def require(condition, code="INVALID_REQUEST"):
    if not condition:
        raise QueueError(code)


def utf16_length(value):
    return len(value.encode("utf-16-be")) // 2


def identity(value, maximum=200):
    require(type(value) is str and value and value == value.strip() and "\0" not in value)
    require(utf16_length(value) <= maximum)
    return value


def receiver_id(value):
    require(type(value) is str and re.fullmatch(r"[0-9a-f]{32}", value) is not None)
    return value


def cursor(value):
    if value is not None:
        identity(value, 1024)
    return value


def wire_json(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def parse_json(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result)
            result[key] = value
        return result
    try:
        require(len(raw.encode("utf-8")) <= MAX_WIRE_BYTES)
        return json.loads(raw, object_pairs_hook=unique, parse_constant=lambda _: require(False))
    except (ValueError, UnicodeError, TypeError):
        raise QueueError("INVALID_REQUEST") from None


def stored_json(raw):
    try:
        return parse_json(raw)
    except QueueError:
        raise QueueError("STATE_UNAVAILABLE") from None


def field(value):
    if value is None:
        return struct.pack(">i", -1)
    data = str(value).encode("utf-8")
    return struct.pack(">I", len(data)) + data


def message_bytes(value):
    names = ("channelId", "messageId", "senderId", "conversationKey", "text", "receivedAtMillis", "nonce", "keyId")
    return struct.pack(">I", 8) + b"".join(field(value[name]) for name in names)


def batch_bytes(value):
    data = field("xiaoling-batch-v1") + struct.pack(">I", 1)
    data += b"".join(field(value[name]) for name in ("channelId", "senderId", "keyId", "receiverId", "batchId", "requestCursor", "nextCursor"))
    data += struct.pack(">qqI", value["issuedAtMillis"], value["expiresAtMillis"], len(value["messages"]))
    for message in value["messages"]:
        body = message_bytes(message)
        data += struct.pack(">I", len(body)) + body + field(message["signature"])
    return data


def ack_bytes(value):
    return field("xiaoling-ack-v1") + struct.pack(">I", 1) + b"".join(field(value[name]) for name in
        ("channelId", "senderId", "keyId", "receiverId", "batchId", "batchDigestSha256", "nextCursor"))


def receipt_request(batch):
    return {**{name: batch[name] for name in PEER_FIELDS}, "protocolVersion": 1, "operation": "ack",
            "receiverId": batch["receiverId"], "batchId": batch["batchId"],
            "batchDigestSha256": hashlib.sha256(batch_bytes(batch)).hexdigest(), "nextCursor": batch["nextCursor"],
            "messageIds": [message["messageId"] for message in batch["messages"]]}


def openssl(arguments, payload=None):
    try:
        result = subprocess.run(["openssl", *arguments], input=payload, capture_output=True, timeout=10, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise QueueError("SIGNING_UNAVAILABLE") from None
    require(result.returncode == 0, "SIGNING_UNAVAILABLE")
    return result.stdout


def secure_path(raw):
    path = Path(raw).expanduser()
    require(not path.is_symlink(), "INVALID_STATE_PATH")
    root = path.resolve()
    project = Path(__file__).resolve().parents[1]
    require(root != project and project not in root.parents, "STATE_MUST_BE_OUTSIDE_REPOSITORY")
    return root


def owner_only(path, directory=False):
    info = path.lstat()
    require(info.st_uid == os.getuid() and not info.st_mode & 0o077 and
            (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode) and info.st_nlink == 1),
            "INVALID_STATE_PERMISSIONS")


class LocalSenderQueue:
    def __init__(self, state_dir, clock=None):
        require(sys.version_info >= (3, 12), "PYTHON_3_12_REQUIRED")
        self.root = secure_path(state_dir)
        self.private = self.root / "sender-private.pem"
        self.public = self.root / "sender-public.der"
        self.database = self.root / "queue.sqlite3"
        self.clock = clock or (lambda: int(time.time() * 1000))
        self._validate_files()
        try:
            with self._transaction() as connection:
                require(connection.execute("PRAGMA user_version").fetchone()[0] == 1, "STATE_UNAVAILABLE")
                rows = connection.execute("SELECT value FROM metadata").fetchall()
                require(len(rows) == 1, "STATE_UNAVAILABLE")
                self.info = stored_json(rows[0][0])
            require(type(self.info) is dict and set(self.info) == PEER_FIELDS | {"schemaVersion", "publicKeySpkiBase64", "fingerprintSha256"}, "STATE_UNAVAILABLE")
            require(type(self.info["schemaVersion"]) is int and self.info["schemaVersion"] == 1, "STATE_UNAVAILABLE")
            self._validate_peer(self.info)
            # long: 发送公钥版本绑定已有队列；真实私钥导出的 SPKI 必须仍相同，不能换钥后假装继续确认旧收据。
            public = openssl(["pkey", "-in", str(self.private), "-pubout", "-outform", "DER"])
            description = openssl(["pkey", "-pubin", "-inform", "DER", "-text", "-noout"], public)
            require(b"ASN1 OID: prime256v1" in description and public == self.public.read_bytes(), "STATE_UNAVAILABLE")
            require(base64.b64encode(public).decode("ascii") == self.info["publicKeySpkiBase64"] and
                    hashlib.sha256(public).hexdigest() == self.info["fingerprintSha256"], "STATE_UNAVAILABLE")
        except (QueueError, sqlite3.Error, OSError, ValueError, KeyError, TypeError):
            raise QueueError("STATE_UNAVAILABLE") from None

    @staticmethod
    def _validate_peer(peer):
        identity(peer["channelId"])
        identity(peer["senderId"])
        require(type(peer["keyId"]) is str and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", peer["keyId"]) is not None)

    @classmethod
    def initialize(cls, state_dir, channel_id, sender_id, key_id):
        require(sys.version_info >= (3, 12), "PYTHON_3_12_REQUIRED")
        peer = {"channelId": channel_id, "senderId": sender_id, "keyId": key_id}
        cls._validate_peer(peer)
        root = secure_path(state_dir)
        require(not root.exists(), "STATE_ALREADY_EXISTS")
        root.mkdir(mode=0o700)
        # long: 初始化只接受新目录，失败留下的密钥或账本不自动覆盖；已经投递的身份不能被静默重新生成。
        private = root / "sender-private.pem"
        with private.open("xb") as target:
            os.chmod(private, 0o600)
            target.write(openssl(["ecparam", "-name", "prime256v1", "-genkey", "-noout"]))
            target.flush()
            os.fsync(target.fileno())
        public = openssl(["pkey", "-in", str(private), "-pubout", "-outform", "DER"])
        descriptor = os.open(root / "sender-public.der", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as target:
            target.write(public)
            target.flush()
            os.fsync(target.fileno())
        info = {**peer, "schemaVersion": 1, "publicKeySpkiBase64": base64.b64encode(public).decode("ascii"),
                "fingerprintSha256": hashlib.sha256(public).hexdigest()}
        descriptor = os.open(root / "queue.sqlite3", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.close(descriptor)
        connection = cls._open(root / "queue.sqlite3")
        try:
            connection.execute("BEGIN IMMEDIATE")
            for statement in SCHEMA:
                connection.execute(statement)
            connection.execute("INSERT INTO metadata VALUES (1, ?)", (wire_json(info),))
            connection.execute("PRAGMA user_version=1")
            connection.execute("COMMIT")
        finally:
            connection.close()
        return cls(root)

    def _validate_files(self):
        try:
            owner_only(self.root, directory=True)
            for path in (self.private, self.public, self.database):
                owner_only(path)
        except OSError:
            raise QueueError("STATE_UNAVAILABLE") from None

    @staticmethod
    def _open(path):
        # long: mode=rw 禁止因文件丢失而创建空库；EXTRA 同步适用于 DELETE 日志，事务提交后才允许交付响应。
        connection = sqlite3.connect("file:" + quote(str(path), safe="/") + "?mode=rw", uri=True,
                                     autocommit=sqlite3.LEGACY_TRANSACTION_CONTROL, isolation_level=None, timeout=10)
        connection.row_factory = sqlite3.Row
        try:
            connection.execute("PRAGMA foreign_keys=ON")
            connection.execute("PRAGMA synchronous=EXTRA")
            connection.execute("PRAGMA fullfsync=ON")
            require(connection.execute("PRAGMA journal_mode=DELETE").fetchone()[0] == "delete", "STATE_UNAVAILABLE")
            require(connection.execute("PRAGMA quick_check").fetchone()[0] == "ok", "STATE_UNAVAILABLE")
        except BaseException:
            connection.close()
            raise
        return connection

    @contextmanager
    def _transaction(self):
        self._validate_files()
        try:
            connection = self._open(self.database)
        except (sqlite3.Error, OSError):
            raise QueueError("STATE_UNAVAILABLE") from None
        try:
            # long: 多个管理进程也必须串行选择在途批次与提交 ACK，避免同一游标分叉或重复推进队列。
            connection.execute("BEGIN IMMEDIATE")
            yield connection
            connection.execute("COMMIT")
        except BaseException as error:
            if connection.in_transaction:
                try:
                    connection.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
            if isinstance(error, (sqlite3.Error, OSError, ValueError, KeyError, TypeError)):
                raise QueueError("STATE_UNAVAILABLE") from None
            raise
        finally:
            connection.close()

    def _now(self):
        value = self.clock()
        require(type(value) is int and 0 < value <= 9223372036854775807 - BATCH_TTL_MILLIS, "INVALID_CLOCK")
        return value

    def _sign(self, payload):
        signature = openssl(["dgst", "-sha256", "-sign", str(self.private)], payload)
        require(64 <= len(signature) <= 80, "SIGNING_UNAVAILABLE")
        return base64.urlsafe_b64encode(signature).decode("ascii").rstrip("=")

    def _verify(self, payload, signature):
        require(type(signature) is str and re.fullmatch(r"[A-Za-z0-9_-]{1,128}", signature) is not None, "STATE_UNAVAILABLE")
        raw = base64.b64decode(signature + "=" * (-len(signature) % 4), altchars=b"-_", validate=True)
        require(64 <= len(raw) <= 80, "STATE_UNAVAILABLE")
        with tempfile.NamedTemporaryFile(dir=self.root, prefix="verify-", suffix=".der") as target:
            target.write(raw)
            target.flush()
            try:
                openssl(["dgst", "-sha256", "-keyform", "DER", "-verify", str(self.public), "-signature", target.name], payload)
            except QueueError:
                raise QueueError("STATE_UNAVAILABLE") from None

    def _receiver(self, connection, value):
        row = connection.execute("SELECT * FROM receivers WHERE id=?", (receiver_id(value),)).fetchone()
        require(row is not None, "UNKNOWN_RECEIVER")
        return row

    def register(self, value):
        receiver_id(value)
        with self._transaction() as connection:
            if connection.execute("SELECT 1 FROM receivers WHERE id=?", (value,)).fetchone() is None:
                require(connection.execute("SELECT count(*) FROM receivers").fetchone()[0] < MAX_RECEIVERS, "RECEIVER_CAPACITY")
                connection.execute("INSERT INTO receivers VALUES (?, NULL, NULL)", (value,))
        return {"receiverId": value}

    def enqueue(self, receiver, message_id, conversation, text):
        receiver_id(receiver)
        identity(message_id)
        conversation = identity(conversation)
        require(type(text) is str)
        text = text.replace("\r\n", "\n").replace("\r", "\n").strip()
        require(text and utf16_length(text) <= 20000, "INVALID_TEXT")
        content_hash = hashlib.sha256(wire_json({"conversationKey": conversation, "text": text}).encode("utf-8")).hexdigest()
        with self._transaction() as connection:
            self._receiver(connection, receiver)
            old = connection.execute("SELECT * FROM messages WHERE id=?", (message_id,)).fetchone()
            if old is not None:
                # long: 已 ACK 的身份也保留原内容摘要；重复入队返回原状态，同 ID 换正文或接收端不能形成新消息。
                require(old["receiver"] == receiver and old["content_hash"] == content_hash, "MESSAGE_ID_CONFLICT")
                return {"messageId": message_id, "duplicate": True, "acknowledged": bool(old["acknowledged"])}
            require(connection.execute("SELECT count(*) FROM messages").fetchone()[0] < MAX_MESSAGES, "MESSAGE_CAPACITY")
            nonce = uuid.uuid4().hex
            now = self._now()
            # long: 入队按最长本机游标、时间和签名预留报文容量，不能让首批可发送的正文在后续游标下永久卡队。
            wire_time = 9223372036854775807 - BATCH_TTL_MILLIS
            envelope = self._envelope({"id": message_id, "conversation": conversation, "text": text, "nonce": nonce}, wire_time, signed=False)
            probe = self._batch(receiver, "cursor-" + "0" * 32, "cursor-" + "1" * 32, wire_time, [envelope], signed=False)
            require(len(wire_json(probe).encode("utf-8")) <= MAX_WIRE_BYTES, "MESSAGE_TOO_LARGE")
            connection.execute("INSERT INTO messages(id,receiver,conversation,text,content_hash,nonce,acknowledged) VALUES (?,?,?,?,?,?,0)",
                               (message_id, receiver, conversation, text, content_hash, nonce))
        return {"messageId": message_id, "duplicate": False, "acknowledged": False}

    def _envelope(self, row, now, signed=True):
        value = {**{name: self.info[name] for name in PEER_FIELDS}, "messageId": row["id"],
                 "conversationKey": row["conversation"], "text": row["text"], "receivedAtMillis": now, "nonce": row["nonce"]}
        value["signature"] = self._sign(message_bytes(value)) if signed else "x" * 128
        return value

    def _batch(self, receiver, request_cursor, next_cursor, now, messages, signed=True):
        value = {**{name: self.info[name] for name in PEER_FIELDS}, "protocolVersion": 1, "receiverId": receiver,
                 "batchId": "batch-" + uuid.uuid4().hex, "requestCursor": request_cursor, "nextCursor": next_cursor,
                 "issuedAtMillis": now, "expiresAtMillis": now + BATCH_TTL_MILLIS, "messages": messages}
        value["signature"] = self._sign(batch_bytes(value)) if signed else "x" * 128
        return value

    def _validate_cached_batch(self, batch):
        require(type(batch) is dict and set(batch) == BATCH_FIELDS and type(batch["protocolVersion"]) is int and
                batch["protocolVersion"] == 1, "STATE_UNAVAILABLE")
        require(all(batch[name] == self.info[name] for name in PEER_FIELDS), "STATE_UNAVAILABLE")
        receiver_id(batch["receiverId"])
        identity(batch["batchId"])
        cursor(batch["requestCursor"])
        cursor(batch["nextCursor"])
        require(batch["nextCursor"] is not None and type(batch["issuedAtMillis"]) is int and type(batch["expiresAtMillis"]) is int and
                0 < batch["issuedAtMillis"] < batch["expiresAtMillis"] <= 9223372036854775807 and
                batch["expiresAtMillis"] - batch["issuedAtMillis"] <= 300000, "STATE_UNAVAILABLE")
        require(type(batch["messages"]) is list and len(batch["messages"]) <= MAX_BATCH_MESSAGES, "STATE_UNAVAILABLE")
        ids, nonces = set(), set()
        for message in batch["messages"]:
            require(type(message) is dict and set(message) == MESSAGE_FIELDS and
                    all(message[name] == self.info[name] for name in PEER_FIELDS), "STATE_UNAVAILABLE")
            identity(message["messageId"])
            identity(message["conversationKey"])
            require(type(message["text"]) is str and message["text"] and utf16_length(message["text"]) <= 20000 and
                    message["text"] == message["text"].replace("\r\n", "\n").replace("\r", "\n").strip(), "STATE_UNAVAILABLE")
            require(type(message["receivedAtMillis"]) is int and 0 < message["receivedAtMillis"] <= 9223372036854775807 and
                    type(message["nonce"]) is str and 16 <= utf16_length(message["nonce"]) <= 256 and
                    message["nonce"] == message["nonce"].strip() and type(message["signature"]) is str, "STATE_UNAVAILABLE")
            require(message["messageId"] not in ids and message["nonce"] not in nonces, "STATE_UNAVAILABLE")
            ids.add(message["messageId"])
            nonces.add(message["nonce"])
        require(not batch["messages"] or batch["nextCursor"] != batch["requestCursor"], "STATE_UNAVAILABLE")

    def pull(self, receiver, request_cursor=None):
        receiver_id(receiver)
        cursor(request_cursor)
        now = self._now()
        with self._transaction() as connection:
            route = self._receiver(connection, receiver)
            if route["pending"] is not None:
                delivery = connection.execute("SELECT * FROM deliveries WHERE id=?", (route["pending"],)).fetchone()
                require(delivery is not None and delivery["receiver"] == receiver and not delivery["acknowledged"], "STATE_UNAVAILABLE")
                require(route["cursor"] == delivery["request_cursor"], "STATE_UNAVAILABLE")
                require(request_cursor == delivery["request_cursor"], "ACK_REQUIRED")
                latest = connection.execute("SELECT * FROM receipts WHERE delivery=? ORDER BY rowid DESC LIMIT 1", (delivery["id"],)).fetchone()
                require(latest is not None and latest["batch_json"] is not None, "STATE_UNAVAILABLE")
                batch = stored_json(latest["batch_json"])
                self._validate_cached_batch(batch)
                expected = stored_json(latest["request_json"])
                require(receipt_request(batch) == expected and batch["batchId"] == latest["batch_id"] and
                        batch["requestCursor"] == delivery["request_cursor"] and batch["nextCursor"] == delivery["next_cursor"] and
                        expected["messageIds"] == stored_json(delivery["message_ids"]), "STATE_UNAVAILABLE")
                self._verify(batch_bytes(batch), batch["signature"])
                if batch["issuedAtMillis"] <= now + 30000 and batch["issuedAtMillis"] >= now - 300000 and now < batch["expiresAtMillis"]:
                    return batch
                # long: 重签只更新时间与签名，原消息 ID、nonce、逻辑游标和全部已发收据保留，迟到的旧 ACK 仍可匹配。
                rows = [connection.execute("SELECT * FROM messages WHERE id=? AND receiver=? AND acknowledged=0", (value, receiver)).fetchone()
                        for value in stored_json(delivery["message_ids"])]
                require(all(row is not None and row["text"] is not None for row in rows), "STATE_UNAVAILABLE")
                messages = [self._envelope(row, now) for row in rows]
            else:
                require(request_cursor == route["cursor"], "CURSOR_MISMATCH")
                # long: 首次空队列也提供非空游标供协议绑定 ACK，后续空批次保留游标，不伪造新正文或跳过排队消息。
                next_cursor = request_cursor or "cursor-" + uuid.uuid4().hex
                messages = []
                rows = connection.execute("SELECT * FROM messages WHERE receiver=? AND acknowledged=0 ORDER BY seq LIMIT ?", (receiver, MAX_BATCH_MESSAGES)).fetchall()
                if rows:
                    next_cursor = "cursor-" + uuid.uuid4().hex
                for row in rows:
                    # long: 按排队顺序在报文边界截批，不能跳过较大的中间正文，也不能把新正文补入已签发的快照。
                    candidate = messages + [self._envelope(row, now)]
                    probe = self._batch(receiver, request_cursor, next_cursor, now, candidate, signed=False)
                    if len(wire_json(probe).encode("utf-8")) > MAX_WIRE_BYTES:
                        break
                    messages = candidate
                require(not rows or messages, "MESSAGE_TOO_LARGE")
                delivery = {"id": uuid.uuid4().hex, "receiver": receiver, "request_cursor": request_cursor, "next_cursor": next_cursor}
                connection.execute("INSERT INTO deliveries VALUES (?,?,?,?,?,0)", (delivery["id"], receiver, request_cursor, next_cursor,
                                    wire_json([message["messageId"] for message in messages])))
                connection.execute("UPDATE receivers SET pending=? WHERE id=?", (delivery["id"], receiver))
            require(connection.execute("SELECT count(*) FROM receipts").fetchone()[0] < MAX_RECEIPTS, "RECEIPT_CAPACITY")
            batch = self._batch(receiver, delivery["request_cursor"], delivery["next_cursor"], now, messages)
            raw = wire_json(batch)
            require(len(raw.encode("utf-8")) <= MAX_WIRE_BYTES, "MESSAGE_TOO_LARGE")
            connection.execute("INSERT INTO receipts VALUES (?,?,?,?,NULL)",
                               (batch["batchId"], delivery["id"], wire_json(receipt_request(batch)), raw))
            return batch

    def acknowledge(self, request):
        # long: 本地命令由状态目录属主控制；收据匹配只确认已签发快照，未来网络入口调用前仍必须认证接收端。
        require(type(request) is dict and set(request) == ACK_FIELDS and type(request["protocolVersion"]) is int and
                request["protocolVersion"] == 1 and request["operation"] == "ack")
        receiver_id(request["receiverId"])
        require(type(request["messageIds"]) is list and len(request["messageIds"]) <= MAX_BATCH_MESSAGES and
                all(type(value) is str for value in request["messageIds"]))
        with self._transaction() as connection:
            self._receiver(connection, request["receiverId"])
            row = connection.execute("SELECT * FROM receipts WHERE batch_id=?", (identity(request["batchId"]),)).fetchone()
            require(row is not None, "ACK_MISMATCH")
            expected = stored_json(row["request_json"])
            require(type(expected) is dict and set(expected) == ACK_FIELDS and type(expected["protocolVersion"]) is int and
                    expected["protocolVersion"] == 1 and expected["operation"] == "ack" and expected["batchId"] == row["batch_id"] and
                    all(expected[name] == self.info[name] for name in PEER_FIELDS), "STATE_UNAVAILABLE")
            require(expected == request, "ACK_MISMATCH")
            delivery = connection.execute("SELECT * FROM deliveries WHERE id=?", (row["delivery"],)).fetchone()
            require(delivery is not None and delivery["receiver"] == request["receiverId"] and delivery["next_cursor"] == request["nextCursor"] and
                    stored_json(delivery["message_ids"]) == request["messageIds"], "STATE_UNAVAILABLE")
            expected_reply = {name: value for name, value in request.items() if name != "messageIds"}
            expected_reply["operation"] = "acknowledged"
            if row["ack_json"] is not None:
                reply = stored_json(row["ack_json"])
                require(type(reply) is dict and set(reply) == set(expected_reply) | {"signature"} and
                        type(reply["protocolVersion"]) is int and
                        all(reply[name] == value for name, value in expected_reply.items()), "STATE_UNAVAILABLE")
                self._verify(ack_bytes(reply), reply["signature"])
                return reply
            if not delivery["acknowledged"]:
                # long: 首次 ACK 还要核对磁盘快照的原签名和 digest；收据、逻辑投递或缓存损坏都不能推进队列。
                batch = stored_json(row["batch_json"])
                self._validate_cached_batch(batch)
                require(receipt_request(batch) == request and batch["requestCursor"] == delivery["request_cursor"], "STATE_UNAVAILABLE")
                self._verify(batch_bytes(batch), batch["signature"])
            reply = {**expected_reply, "signature": self._sign(ack_bytes(expected_reply))}
            if not delivery["acknowledged"]:
                route = self._receiver(connection, request["receiverId"])
                require(route["pending"] == delivery["id"] and route["cursor"] == delivery["request_cursor"], "STATE_UNAVAILABLE")
                # long: 只有精确匹配已提交收据的 ACK 才推进该接收队列；正文逻辑清除与游标同次提交，重复 ACK 不动后来批次。
                connection.execute("UPDATE receivers SET cursor=?,pending=NULL WHERE id=?", (delivery["next_cursor"], delivery["receiver"]))
                connection.execute("UPDATE deliveries SET acknowledged=1 WHERE id=?", (delivery["id"],))
                for value in stored_json(delivery["message_ids"]):
                    changed = connection.execute("UPDATE messages SET acknowledged=1,text=NULL,conversation=NULL WHERE id=? AND receiver=? AND acknowledged=0",
                                                 (value, delivery["receiver"])).rowcount
                    require(changed == 1, "STATE_UNAVAILABLE")
                connection.execute("UPDATE receipts SET batch_json=NULL WHERE delivery=?", (delivery["id"],))
            connection.execute("UPDATE receipts SET ack_json=? WHERE batch_id=?", (wire_json(reply), request["batchId"]))
            return reply

    def stats(self):
        with self._transaction() as connection:
            return {"receivers": connection.execute("SELECT count(*) FROM receivers").fetchone()[0],
                    "queuedMessages": connection.execute("SELECT count(*) FROM messages WHERE acknowledged=0").fetchone()[0],
                    "acknowledgedMessages": connection.execute("SELECT count(*) FROM messages WHERE acknowledged=1").fetchone()[0],
                    "pendingDeliveries": connection.execute("SELECT count(*) FROM deliveries WHERE acknowledged=0").fetchone()[0],
                    "issuedReceipts": connection.execute("SELECT count(*) FROM receipts").fetchone()[0]}


def main():
    parser = argparse.ArgumentParser(description="本地持久 Remote Channel 发送队列；不提供 HTTP 服务或远程接收端认证")
    parser.add_argument("--state-dir", required=True, help="仓库外、当前用户独占的新目录或已有队列目录")
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init")
    for name in ("channel-id", "sender-id", "key-id"):
        init.add_argument("--" + name, required=True)
    register = commands.add_parser("register")
    register.add_argument("--receiver-id", required=True)
    enqueue = commands.add_parser("enqueue", help="正文从标准输入读取")
    for name in ("receiver-id", "message-id", "conversation-key"):
        enqueue.add_argument("--" + name, required=True)
    pull = commands.add_parser("pull")
    pull.add_argument("--receiver-id", required=True)
    pull.add_argument("--cursor")
    commands.add_parser("ack", help="完整 ACK JSON 从标准输入读取")
    commands.add_parser("public-info")
    commands.add_parser("stats")
    args = parser.parse_args()
    try:
        queue = LocalSenderQueue.initialize(args.state_dir, args.channel_id, args.sender_id, args.key_id) if args.command == "init" else LocalSenderQueue(args.state_dir)
        if args.command in ("init", "public-info"):
            result = queue.info
        elif args.command == "register":
            result = queue.register(args.receiver_id)
        elif args.command == "enqueue":
            raw = sys.stdin.buffer.read(MAX_WIRE_BYTES + 1)
            require(len(raw) <= MAX_WIRE_BYTES)
            result = queue.enqueue(args.receiver_id, args.message_id, args.conversation_key, raw.decode("utf-8"))
        elif args.command == "pull":
            result = queue.pull(args.receiver_id, args.cursor)
        elif args.command == "ack":
            raw = sys.stdin.buffer.read(MAX_WIRE_BYTES + 1)
            require(len(raw) <= MAX_WIRE_BYTES)
            result = queue.acknowledge(parse_json(raw.decode("utf-8")))
        else:
            result = queue.stats()
        sys.stdout.write(wire_json(result) + "\n")
        sys.stdout.flush()
        return 0
    except QueueError as error:
        print("sender error: " + str(error), file=sys.stderr)
    except BrokenPipeError:
        return 1
    except (OSError, sqlite3.Error, UnicodeError, ValueError, KeyError, TypeError):
        print("sender error: STATE_UNAVAILABLE", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
