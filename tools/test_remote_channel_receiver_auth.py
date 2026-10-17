"""long: 仓库外合成身份验证授权、精确请求签名、持久重试与撤销，不启动网络服务。"""

from concurrent.futures import ThreadPoolExecutor
from contextlib import closing, contextmanager
import copy
import base64
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import remote_channel_sender as sender
import remote_channel_receiver_auth as auth


class ReceiverAuthTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="xiaoling-receiver-auth-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "state"
        self.now = 1_000_000
        self.queue = sender.LocalSenderQueue.initialize(self.root, "auth-test", "sender", "key-1")
        self.queue.clock = lambda: self.now
        self.gateway = auth.AuthenticatedSender.initialize(self.queue)
        self.receiver = "a" * 32
        self.endpoint = "1" * 64
        self.key = Path(self.temporary.name) / "receiver.pem"
        self.key.write_bytes(sender.openssl(["ecparam", "-name", "prime256v1", "-genkey", "-noout"]))
        self.key.chmod(0o600)
        public = sender.openssl(["pkey", "-in", str(self.key), "-pubout", "-outform", "DER"])
        self.manifest = {**{name: self.queue.info[name] for name in sender.PEER_FIELDS}, "authVersion": 1,
                         "senderFingerprintSha256": self.queue.info["fingerprintSha256"], "endpointSha256": self.endpoint,
                         "receiverId": self.receiver, "publicKeySpkiBase64": base64.b64encode(public).decode(),
                         "receiverFingerprintSha256": auth.digest(public)}
        self.gateway.authorize(self.manifest, self.manifest["receiverFingerprintSha256"])
        self.queue.enqueue(self.receiver, "message-1", "conversation", "认证合成正文😀")

    @contextmanager
    def database(self):
        with closing(sqlite3.connect(self.queue.database)) as connection, connection:
            yield connection

    def count(self):
        with self.database() as connection:
            return connection.execute("SELECT count(*) FROM receiver_auth_requests").fetchone()[0]

    def request(self, cursor=None):
        return {**{name: self.queue.info[name] for name in sender.PEER_FIELDS}, "protocolVersion": 1,
                "operation": "pull", "receiverId": self.receiver, "requestCursor": cursor}

    def envelope(self, request=None, nonce="b" * 32, changes=None, key=None):
        request = self.request() if request is None else request
        proof = {name: value for name, value in self.manifest.items() if name != "publicKeySpkiBase64"}
        proof.update(operation=request["operation"], issuedAtMillis=self.now, expiresAtMillis=self.now + auth.TTL_MILLIS,
                     nonce=nonce, requestDigestSha256=auth.digest(auth.request_bytes(request)), signature="")
        proof.update(changes or {})
        signature = sender.openssl(["dgst", "-sha256", "-sign", str(key or self.key)], auth.proof_bytes(proof))
        proof["signature"] = base64.urlsafe_b64encode(signature).decode().rstrip("=")
        return {"request": request, "proof": proof}

    def error(self, code, operation):
        with self.assertRaises(sender.QueueError) as caught:
            operation()
        self.assertEqual(code, str(caught.exception))

    def test_extension_is_explicit_and_never_recreates_missing_or_partial_state(self):
        self.error("AUTH_ALREADY_EXISTS", lambda: auth.AuthenticatedSender.initialize(self.queue))
        with self.database() as connection:
            self.assertEqual(1, connection.execute("PRAGMA user_version").fetchone()[0])
            connection.execute("DROP TABLE receiver_auth_requests")
        self.error("AUTH_STATE_UNAVAILABLE", lambda: auth.AuthenticatedSender(self.queue))
        self.error("AUTH_ALREADY_EXISTS", lambda: auth.AuthenticatedSender.initialize(self.queue))
        self.assertEqual(1, self.queue.stats()["queuedMessages"])

    def test_authorization_requires_trusted_fingerprint_and_immutable_key_endpoint_binding(self):
        other = {**self.manifest, "receiverId": "c" * 32}
        self.error("AUTH_FINGERPRINT_MISMATCH", lambda: self.gateway.authorize(other, "0" * 64))
        self.assertEqual(1, self.queue.stats()["receivers"])
        self.gateway.authorize(self.manifest, self.manifest["receiverFingerprintSha256"])
        changed = {**self.manifest, "endpointSha256": "2" * 64}
        self.error("AUTH_BINDING_CONFLICT", lambda: self.gateway.authorize(changed, changed["receiverFingerprintSha256"]))
        new_key = Path(self.temporary.name) / "replacement.pem"
        new_key.write_bytes(sender.openssl(["ecparam", "-name", "prime256v1", "-genkey", "-noout"]))
        new_key.chmod(0o600)
        public = sender.openssl(["pkey", "-in", str(new_key), "-pubout", "-outform", "DER"])
        replacement = {**self.manifest, "publicKeySpkiBase64": base64.b64encode(public).decode(), "receiverFingerprintSha256": auth.digest(public)}
        self.error("AUTH_BINDING_CONFLICT", lambda: self.gateway.authorize(replacement, replacement["receiverFingerprintSha256"]))
        invalid = {**self.manifest, "senderFingerprintSha256": "3" * 64}
        self.error("AUTH_REJECTED", lambda: self.gateway.authorize(invalid, invalid["receiverFingerprintSha256"]))

    def test_unsigned_unknown_and_registered_but_unauthorized_receivers_never_reach_queue(self):
        self.queue.register("c" * 32)
        with patch.object(self.queue, "_pull", side_effect=AssertionError("must not read queue")):
            self.error("AUTH_REJECTED", lambda: self.gateway.execute(self.request(), self.endpoint))
            foreign = self.envelope(changes={"receiverId": "c" * 32})
            foreign["request"]["receiverId"] = "c" * 32
            foreign["proof"]["requestDigestSha256"] = auth.digest(auth.request_bytes(foreign["request"]))
            self.error("AUTH_REJECTED", lambda: self.gateway.execute(foreign, self.endpoint))
        self.assertEqual(0, self.count())

    def test_duplicate_get_and_ack_recover_from_new_process_and_keep_no_auth_body_copy(self):
        envelope = self.envelope()
        batch = self.gateway.execute(envelope, self.endpoint)
        restarted = auth.AuthenticatedSender(sender.LocalSenderQueue(self.root, lambda: self.now))
        self.assertEqual(batch, restarted.execute(envelope, self.endpoint))
        self.assertEqual(1, self.count())
        ack = self.envelope(sender.receipt_request(batch), "d" * 32)
        reply = restarted.execute(ack, self.endpoint)
        self.assertEqual(reply, self.gateway.execute(ack, self.endpoint))
        self.error("AUTH_REPLAY_COMPLETED", lambda: self.gateway.execute(envelope, self.endpoint))
        with self.database() as connection:
            self.assertIsNone(connection.execute("SELECT text FROM messages").fetchone()[0])
            self.assertIsNone(connection.execute("SELECT batch_json FROM receipts").fetchone()[0])
            for raw, in connection.execute("SELECT reference_json FROM receiver_auth_requests"):
                self.assertNotIn("认证合成正文", raw)
        self.assertEqual(2, self.count())

    def test_proof_binds_receiver_sender_endpoint_operation_cursor_and_ordered_ack_ids(self):
        signed = self.envelope()
        for name, value in {"channelId": "other", "senderId": "other", "keyId": "other", "senderFingerprintSha256": "0" * 64,
                            "endpointSha256": "0" * 64, "receiverFingerprintSha256": "0" * 64, "receiverId": "c" * 32,
                            "operation": "ack", "requestDigestSha256": "0" * 64}.items():
            changed = copy.deepcopy(signed)
            changed["proof"][name] = value
            self.error("AUTH_REJECTED", lambda: self.gateway.execute(changed, self.endpoint))
        changed = copy.deepcopy(signed)
        changed["request"]["requestCursor"] = "wrong"
        self.error("AUTH_REJECTED", lambda: self.gateway.execute(changed, self.endpoint))
        self.error("AUTH_REJECTED", lambda: self.gateway.execute(signed, "2" * 64))
        self.queue.enqueue(self.receiver, "message-2", "conversation", "第二条")
        batch = self.gateway.execute(signed, self.endpoint)
        ack = self.envelope(sender.receipt_request(batch), "d" * 32)
        ack["request"]["messageIds"].reverse()
        self.error("AUTH_REJECTED", lambda: self.gateway.execute(ack, self.endpoint))
        self.assertEqual(2, self.queue.stats()["queuedMessages"])

    def test_wrong_key_or_invalid_signature_is_rejected_without_consuming_nonce(self):
        key = Path(self.temporary.name) / "other.pem"
        key.write_bytes(sender.openssl(["ecparam", "-name", "prime256v1", "-genkey", "-noout"]))
        key.chmod(0o600)
        self.error("AUTH_REJECTED", lambda: self.gateway.execute(self.envelope(key=key), self.endpoint))
        envelope = self.envelope()
        envelope["proof"]["signature"] = "A" * 96
        self.error("AUTH_REJECTED", lambda: self.gateway.execute(envelope, self.endpoint))
        self.assertEqual(0, self.count())

    def test_same_nonce_cannot_change_request_or_time_and_old_ack_cannot_clear_new_delivery(self):
        signed = self.envelope()
        batch = self.gateway.execute(signed, self.endpoint)
        self.now += 1
        self.error("AUTH_NONCE_CONFLICT", lambda: self.gateway.execute(self.envelope(), self.endpoint))
        self.error("AUTH_NONCE_CONFLICT", lambda: self.gateway.execute(self.envelope(self.request("wrong")), self.endpoint))
        ack = self.envelope(sender.receipt_request(batch), "d" * 32)
        reply = self.gateway.execute(ack, self.endpoint)
        self.queue.enqueue(self.receiver, "message-2", "conversation", "后续正文")
        new = self.gateway.execute(self.envelope(self.request(batch["nextCursor"]), "e" * 32), self.endpoint)
        self.assertEqual(reply, self.gateway.execute(ack, self.endpoint))
        self.assertEqual(new, self.queue.pull(self.receiver, batch["nextCursor"]))
        self.assertEqual(1, self.queue.stats()["queuedMessages"])

    def test_expired_future_and_invalid_type_proofs_are_rejected_before_side_effects(self):
        envelope = self.envelope()
        self.now += auth.TTL_MILLIS
        self.error("AUTH_EXPIRED", lambda: self.gateway.execute(envelope, self.endpoint))
        future = self.envelope(changes={"issuedAtMillis": self.now + 5001, "expiresAtMillis": self.now + 65001})
        self.error("AUTH_EXPIRED", lambda: self.gateway.execute(future, self.endpoint))
        for name, value in [("authVersion", True), ("issuedAtMillis", 1.0), ("nonce", "B" * 32)]:
            bad = self.envelope()
            bad["proof"][name] = value
            self.error("AUTH_REJECTED", lambda: self.gateway.execute(bad, self.endpoint))
        self.assertEqual(0, self.count())

    def test_expiry_is_checked_again_after_signature_verification(self):
        envelope = self.envelope()
        real_verify = auth.verify
        def delayed(*args):
            real_verify(*args)
            self.now += auth.TTL_MILLIS
        with patch.object(auth, "verify", side_effect=delayed):
            self.error("AUTH_EXPIRED", lambda: self.gateway.execute(envelope, self.endpoint))
        self.assertEqual(0, self.count())
        self.assertEqual(0, self.queue.stats()["pendingDeliveries"])

    def test_revocation_blocks_fresh_requests_and_cached_retries_without_deleting_queue(self):
        signed = self.envelope()
        self.gateway.execute(signed, self.endpoint)
        self.gateway.revoke(self.receiver)
        self.error("AUTH_REJECTED", lambda: self.gateway.execute(signed, self.endpoint))
        self.error("AUTH_REJECTED", lambda: self.gateway.execute(self.envelope(nonce="d" * 32), self.endpoint))
        self.assertEqual(1, self.queue.stats()["queuedMessages"])
        self.gateway.authorize(self.manifest, self.manifest["receiverFingerprintSha256"])
        self.gateway.execute(signed, self.endpoint)
        self.assertEqual(1, self.count())

    def test_expiry_during_queue_response_signing_rolls_back_delivery_and_nonce(self):
        signed = self.envelope()
        real_pull = self.queue._pull
        def delayed(*args):
            response = real_pull(*args)
            self.now += auth.TTL_MILLIS
            return response
        with patch.object(self.queue, "_pull", side_effect=delayed):
            self.error("AUTH_EXPIRED", lambda: self.gateway.execute(signed, self.endpoint))
        self.assertEqual(0, self.count())
        self.assertEqual(0, self.queue.stats()["pendingDeliveries"])
        self.assertEqual(0, self.queue.stats()["issuedReceipts"])

    def test_concurrent_same_nonce_has_one_queue_commit_and_one_auth_record(self):
        envelope = self.envelope()
        with ThreadPoolExecutor(max_workers=2) as executor:
            values = list(executor.map(lambda _: auth.AuthenticatedSender(sender.LocalSenderQueue(self.root, lambda: self.now)).execute(envelope, self.endpoint), range(2)))
        self.assertEqual(values[0], values[1])
        self.assertEqual(1, self.count())
        self.assertEqual(1, self.queue.stats()["issuedReceipts"])

    def test_signing_and_commit_failures_roll_back_queue_and_nonce_together(self):
        signed = self.envelope()
        with patch.object(self.queue, "_sign", side_effect=sender.QueueError("SIGNING_UNAVAILABLE")):
            self.error("SIGNING_UNAVAILABLE", lambda: self.gateway.execute(signed, self.endpoint))
        real_open = self.queue._open
        class FailedCommit:
            def __init__(self, value): self.value = value
            def __getattr__(self, name): return getattr(self.value, name)
            def execute(self, sql, *args):
                if sql == "COMMIT": raise sqlite3.OperationalError("injected")
                return self.value.execute(sql, *args)
        with patch.object(self.queue, "_open", side_effect=lambda path: FailedCommit(real_open(path))):
            self.error("STATE_UNAVAILABLE", lambda: self.gateway.execute(signed, self.endpoint))
        self.assertEqual(0, self.count())
        self.assertEqual(0, self.queue.stats()["pendingDeliveries"])
        self.gateway.execute(signed, self.endpoint)

    def test_corrupt_metadata_authorization_and_cached_reference_are_frozen(self):
        with self.database() as connection:
            connection.execute("UPDATE receiver_auth_metadata SET value=?", (sender.wire_json({"authVersion": True, "senderFingerprintSha256": self.queue.info["fingerprintSha256"]}),))
        self.error("AUTH_STATE_UNAVAILABLE", lambda: self.gateway.execute(self.envelope(), self.endpoint))
        with self.database() as connection:
            connection.execute("UPDATE receiver_auth_metadata SET value=?", (sender.wire_json(self.gateway._metadata()),))
            connection.execute("UPDATE receiver_authorizations SET manifest_json='{}'")
        self.error("AUTH_STATE_UNAVAILABLE", lambda: self.gateway.execute(self.envelope(), self.endpoint))
        with self.database() as connection:
            connection.execute("UPDATE receiver_authorizations SET manifest_json=?", (sender.wire_json(self.manifest),))
        signed = self.envelope()
        self.gateway.execute(signed, self.endpoint)
        with self.database() as connection:
            connection.execute("UPDATE receiver_auth_requests SET reference_json='{}'")
        self.error("AUTH_STATE_UNAVAILABLE", lambda: self.gateway.execute(signed, self.endpoint))
        self.assertEqual(1, self.queue.stats()["queuedMessages"])

    def test_capacity_preserves_existing_records_and_does_not_accept_unsigned_fallback(self):
        signed = self.envelope()
        self.gateway.execute(signed, self.endpoint)
        with patch.object(auth, "MAX_AUTH_REQUESTS", 1):
            self.gateway.execute(signed, self.endpoint)
            self.error("AUTH_CAPACITY", lambda: self.gateway.execute(self.envelope(nonce="d" * 32), self.endpoint))
        self.assertEqual(1, self.count())
        self.assertEqual(1, self.queue.stats()["queuedMessages"])

    def test_independent_cli_processes_preserve_authenticated_reply_and_rejection(self):
        script = str(Path(auth.__file__).resolve())
        self.now = int(__import__('time').time() * 1000)
        signed = sender.wire_json(self.envelope())
        args = [sys.executable, script, "--state-dir", str(self.root), "execute", "--endpoint-sha256", self.endpoint]
        first = subprocess.run(args, input=signed, text=True, capture_output=True, timeout=30)
        second = subprocess.run(args, input=signed, text=True, capture_output=True, timeout=30)
        self.assertEqual(0, first.returncode, first.stderr)
        self.assertEqual(first.stdout, second.stdout)
        bad = subprocess.run(args, input='{"request":1,"proof":{}}', text=True, capture_output=True, timeout=30)
        self.assertEqual(2, bad.returncode)
        self.assertNotIn("认证合成正文", bad.stderr)
        self.error("INVALID_REQUEST", lambda: sender.parse_json('{"request":{},"request":{}}'))


if __name__ == "__main__":
    unittest.main()
