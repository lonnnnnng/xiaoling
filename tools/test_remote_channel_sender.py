"""long: 所有发送队列测试使用仓库外的合成数据和专属密钥，不启动网络服务。"""

from concurrent.futures import ThreadPoolExecutor
from contextlib import closing, contextmanager
import copy
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import remote_channel_sender as sender


class SenderQueueTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="xiaoling-sender-unit-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "state"
        self.now = 1_000_000
        self.queue = sender.LocalSenderQueue.initialize(self.root, "queue-test", "sender", "key-1")
        self.queue.clock = lambda: self.now
        self.receiver = "a" * 32
        self.other = "b" * 32
        self.queue.register(self.receiver)

    def enqueue(self, value="message-1", text="合成正文", receiver=None):
        return self.queue.enqueue(receiver or self.receiver, value, "conversation", text)

    def restart(self):
        return sender.LocalSenderQueue(self.root, lambda: self.now)

    @contextmanager
    def database(self):
        # long: sqlite 的事务上下文不会关闭连接；负例改账本后立即释放句柄，避免后续恢复测试依赖垃圾回收。
        with closing(sqlite3.connect(self.queue.database)) as connection, connection:
            yield connection

    def assert_error(self, code, operation):
        with self.assertRaises(sender.QueueError) as caught:
            operation()
        self.assertEqual(code, str(caught.exception))

    def test_keys_are_owner_only_and_initialization_never_overwrites_existing_state(self):
        unsupported = Path(self.temporary.name) / "unsupported-runtime"
        with patch.object(sender.sys, "version_info", (3, 11)):
            self.assert_error("PYTHON_3_12_REQUIRED", lambda: sender.LocalSenderQueue.initialize(unsupported, "test", "sender", "key-1"))
        self.assertFalse(unsupported.exists())
        self.assertEqual(0o700, self.root.stat().st_mode & 0o777)
        for name in ("sender-private.pem", "sender-public.der", "queue.sqlite3"):
            self.assertEqual(0o600, (self.root / name).stat().st_mode & 0o777)
        before = self.queue.info
        self.assert_error("STATE_ALREADY_EXISTS", lambda: sender.LocalSenderQueue.initialize(self.root, "other", "sender", "key-1"))
        self.assertEqual(before, self.restart().info)

    def test_in_repository_state_and_unknown_receiver_never_create_queue_records(self):
        path = Path(sender.__file__).resolve().parents[1] / "outputs" / "forbidden-sender-state"
        self.assert_error("STATE_MUST_BE_OUTSIDE_REPOSITORY", lambda: sender.LocalSenderQueue.initialize(path, "test", "sender", "key-1"))
        self.assertFalse(path.exists())
        before = self.queue.stats()
        self.assert_error("UNKNOWN_RECEIVER", lambda: self.queue.pull(self.other))
        self.assert_error("UNKNOWN_RECEIVER", lambda: self.enqueue(receiver=self.other))
        self.assertEqual(before, self.queue.stats())

    def test_duplicate_enqueue_is_idempotent_and_conflicting_body_or_receiver_is_rejected(self):
        self.queue.register(self.other)
        self.assertFalse(self.enqueue(text="  正文\r\n下一行  ")["duplicate"])
        self.assertTrue(self.restart().enqueue(self.receiver, "message-1", "conversation", "正文\n下一行")["duplicate"])
        self.assert_error("MESSAGE_ID_CONFLICT", lambda: self.enqueue(text="不同正文"))
        self.assert_error("MESSAGE_ID_CONFLICT", lambda: self.enqueue(receiver=self.other))
        self.assertEqual(1, self.queue.stats()["queuedMessages"])

    def test_lost_get_response_and_process_restart_return_identical_snapshot(self):
        self.enqueue()
        first = self.queue.pull(self.receiver)
        self.enqueue("message-2")
        self.assertEqual(first, self.restart().pull(self.receiver))
        self.assertEqual(["message-1"], [value["messageId"] for value in first["messages"]])
        self.assertEqual(1, self.queue.stats()["issuedReceipts"])

    def test_expiry_and_clock_rollback_refresh_identity_without_losing_old_receipt(self):
        self.enqueue()
        first = self.queue.pull(self.receiver)
        self.now += sender.BATCH_TTL_MILLIS
        renewed = self.restart().pull(self.receiver)
        self.assertNotEqual(first["batchId"], renewed["batchId"])
        self.assertEqual(first["nextCursor"], renewed["nextCursor"])
        self.assertEqual(first["messages"][0]["nonce"], renewed["messages"][0]["nonce"])
        self.assertEqual(first["messages"][0]["messageId"], renewed["messages"][0]["messageId"])
        self.now = 1
        rollback = self.restart().pull(self.receiver)
        self.assertEqual(1, rollback["issuedAtMillis"])
        reply = self.restart().acknowledge(sender.receipt_request(first))
        self.assertEqual(first["batchId"], reply["batchId"])
        self.assertEqual(0, self.queue.stats()["queuedMessages"])
        self.assertEqual(reply, self.restart().acknowledge(sender.receipt_request(first)))
        self.restart().acknowledge(sender.receipt_request(rollback))
        self.assertEqual(1, self.queue.stats()["acknowledgedMessages"])

    def test_lost_ack_response_is_cached_and_duplicate_ack_cannot_advance_new_delivery(self):
        self.enqueue()
        first = self.queue.pull(self.receiver)
        request = sender.receipt_request(first)
        response = self.queue.acknowledge(request)
        self.enqueue("message-2")
        second = self.queue.pull(self.receiver, first["nextCursor"])
        self.assertEqual(response, self.restart().acknowledge(request))
        self.assertEqual(second, self.restart().pull(self.receiver, first["nextCursor"]))
        self.assertEqual(1, self.queue.stats()["queuedMessages"])
        self.assertEqual(1, self.queue.stats()["pendingDeliveries"])

    def test_ack_isolated_by_receiver_digest_message_order_cursor_and_identity(self):
        self.queue.register(self.other)
        self.enqueue()
        self.enqueue("message-2")
        first = self.queue.pull(self.receiver)
        request = sender.receipt_request(first)
        before = self.queue.stats()
        changes = {"receiverId": self.other, "batchDigestSha256": "0" * 64, "messageIds": list(reversed(request["messageIds"])),
                   "nextCursor": "different", "channelId": "other", "batchId": "missing"}
        for name, value in changes.items():
            bad = {**request, name: value}
            self.assert_error("ACK_MISMATCH", lambda: self.queue.acknowledge(bad))
            self.assertEqual(before, self.queue.stats())
        self.assert_error("ACK_REQUIRED", lambda: self.queue.pull(self.receiver, first["nextCursor"]))
        self.assertEqual([], self.queue.pull(self.other)["messages"])

    def test_ack_logically_clears_body_but_preserves_idempotency_tombstone_and_signed_reply(self):
        self.enqueue(text="仅本测试独占正文")
        batch = self.queue.pull(self.receiver)
        request = sender.receipt_request(batch)
        response = self.queue.acknowledge(request)
        with self.database() as connection:
            self.assertEqual((None, None, 1), connection.execute("SELECT text,conversation,acknowledged FROM messages").fetchone())
            self.assertIsNone(connection.execute("SELECT batch_json FROM receipts").fetchone()[0])
        duplicate = self.restart().enqueue(self.receiver, "message-1", "conversation", "仅本测试独占正文")
        self.assertTrue(duplicate["duplicate"])
        self.assertTrue(duplicate["acknowledged"])
        self.assertEqual(response, self.restart().acknowledge(request))

    def test_empty_batch_and_fifo_batches_do_not_skip_queued_messages(self):
        empty = self.queue.pull(self.receiver)
        self.assertIsNone(empty["requestCursor"])
        self.assertIsNotNone(empty["nextCursor"])
        self.queue.acknowledge(sender.receipt_request(empty))
        for index in range(9):
            self.enqueue(f"message-{index}")
        first = self.queue.pull(self.receiver, empty["nextCursor"])
        self.assertEqual([f"message-{index}" for index in range(8)], [item["messageId"] for item in first["messages"]])
        self.queue.acknowledge(sender.receipt_request(first))
        second = self.queue.pull(self.receiver, first["nextCursor"])
        self.assertEqual(["message-8"], [item["messageId"] for item in second["messages"]])
        self.queue.acknowledge(sender.receipt_request(second))
        final = self.queue.pull(self.receiver, second["nextCursor"])
        self.assertEqual([], final["messages"])
        self.assertEqual(final["requestCursor"], final["nextCursor"])

    def test_wire_limit_packs_large_unicode_messages_and_rejects_untransmittable_text(self):
        self.enqueue("large-1", "汉" * 20000)
        self.enqueue("large-2", "文" * 20000)
        first = self.queue.pull(self.receiver)
        self.assertEqual(1, len(first["messages"]))
        self.assertLessEqual(len(sender.wire_json(first).encode("utf-8")), sender.MAX_WIRE_BYTES)
        self.queue.acknowledge(sender.receipt_request(first))
        second = self.queue.pull(self.receiver, first["nextCursor"])
        self.assertEqual("large-2", second["messages"][0]["messageId"])
        self.assert_error("MESSAGE_TOO_LARGE", lambda: self.enqueue("control", "\x01" * 15000))
        self.assert_error("INVALID_TEXT", lambda: self.enqueue("emoji", "😀" * 10001))

    def test_capacity_rejection_preserves_old_records_and_existing_ack_can_complete(self):
        self.enqueue()
        first = self.queue.pull(self.receiver)
        before = self.queue.stats()
        with patch.object(sender, "MAX_MESSAGES", 1):
            self.assert_error("MESSAGE_CAPACITY", lambda: self.enqueue("extra"))
            self.assertTrue(self.enqueue()["duplicate"])
        with patch.object(sender, "MAX_RECEIVERS", 1):
            self.assert_error("RECEIVER_CAPACITY", lambda: self.queue.register(self.other))
            self.queue.register(self.receiver)
        self.now += sender.BATCH_TTL_MILLIS
        with patch.object(sender, "MAX_RECEIPTS", 1):
            self.assert_error("RECEIPT_CAPACITY", lambda: self.queue.pull(self.receiver))
            self.assertEqual(before, self.queue.stats())
            self.queue.acknowledge(sender.receipt_request(first))
            completed = self.queue.stats()
            self.assert_error("RECEIPT_CAPACITY", lambda: self.queue.pull(self.receiver, first["nextCursor"]))
            self.assertEqual(completed, self.queue.stats())
        self.assertEqual(0, self.queue.stats()["queuedMessages"])

    def test_concurrent_pull_and_ack_share_one_committed_delivery(self):
        self.enqueue()
        with ThreadPoolExecutor(max_workers=2) as executor:
            batches = list(executor.map(lambda _: self.restart().pull(self.receiver), range(2)))
        self.assertEqual(batches[0], batches[1])
        with ThreadPoolExecutor(max_workers=2) as executor:
            replies = list(executor.map(lambda _: self.restart().acknowledge(sender.receipt_request(batches[0])), range(2)))
        self.assertEqual(replies[0], replies[1])
        self.assertEqual(1, self.queue.stats()["issuedReceipts"])
        self.assertEqual(1, self.queue.stats()["acknowledgedMessages"])

    def test_abandoned_process_transaction_recovers_original_cursor(self):
        self.enqueue()
        batch = self.queue.pull(self.receiver)
        source = "import os,sqlite3,sys; c=sqlite3.connect(sys.argv[1],isolation_level=None); c.execute('BEGIN IMMEDIATE'); c.execute(\"UPDATE receivers SET cursor='uncommitted'\"); os._exit(9)"
        result = subprocess.run([sys.executable, "-c", source, str(self.queue.database)], capture_output=True)
        self.assertEqual(9, result.returncode)
        self.assertEqual(batch, self.restart().pull(self.receiver))

    def test_signing_and_commit_failures_never_advance_sender_cursor(self):
        self.enqueue()
        batch = self.queue.pull(self.receiver)
        before = self.queue.stats()
        with patch.object(self.queue, "_sign", side_effect=sender.QueueError("SIGNING_UNAVAILABLE")):
            self.assert_error("SIGNING_UNAVAILABLE", lambda: self.queue.acknowledge(sender.receipt_request(batch)))
        self.assertEqual(before, self.queue.stats())
        real_open = self.queue._open
        class FailCommit:
            def __init__(self, connection): self.connection = connection
            def __getattr__(self, name): return getattr(self.connection, name)
            def execute(self, sql, *args):
                if sql == "COMMIT": raise sqlite3.OperationalError("injected commit failure")
                return self.connection.execute(sql, *args)
        with patch.object(self.queue, "_open", side_effect=lambda path: FailCommit(real_open(path))):
            self.assert_error("STATE_UNAVAILABLE", lambda: self.queue.acknowledge(sender.receipt_request(batch)))
        self.assertEqual(before, self.restart().stats())
        self.assertEqual(batch, self.restart().pull(self.receiver))

    def test_corrupted_snapshots_unknown_fields_or_signatures_are_frozen(self):
        self.enqueue()
        batch = self.queue.pull(self.receiver)
        for mutation in (lambda value: value.update(protocolVersion=True), lambda value: value.update(signature="A" * 96),
                         lambda value: value["messages"][0].update(extra="invalid")):
            changed = copy.deepcopy(batch)
            mutation(changed)
            with self.database() as connection:
                connection.execute("UPDATE receipts SET batch_json=?", (sender.wire_json(changed),))
            self.assert_error("STATE_UNAVAILABLE", lambda: self.restart().pull(self.receiver))
        self.assertEqual(1, self.queue.stats()["queuedMessages"])

    def test_invalid_ack_types_duplicate_json_fields_and_noncanonical_ids_are_rejected(self):
        self.enqueue()
        batch = self.queue.pull(self.receiver)
        request = sender.receipt_request(batch)
        for value in (True, "1", 1.0):
            self.assert_error("INVALID_REQUEST", lambda: self.queue.acknowledge({**request, "protocolVersion": value}))
        self.assert_error("INVALID_REQUEST", lambda: sender.parse_json('{"a":1,"a":2}'))
        self.assert_error("INVALID_REQUEST", lambda: sender.parse_json('{"a":NaN}'))
        self.assert_error("INVALID_REQUEST", lambda: self.queue.register("A" * 32))
        self.assert_error("INVALID_REQUEST", lambda: self.enqueue(" bad "))
        self.assert_error("ACK_REQUIRED", lambda: self.queue.pull(self.receiver, "wrong"))
        for value in (0, True, 1.0, 9223372036854775807):
            with patch.object(self.queue, "clock", return_value=value):
                self.assert_error("INVALID_CLOCK", lambda: self.queue.pull(self.receiver))

    def test_corrupted_receipt_delivery_or_cached_ack_cannot_confirm_or_advance(self):
        self.enqueue()
        batch = self.queue.pull(self.receiver)
        request = sender.receipt_request(batch)
        bad_request = {**request, "protocolVersion": True}
        with self.database() as connection:
            connection.execute("UPDATE receipts SET request_json=?", (sender.wire_json(bad_request),))
        self.assert_error("STATE_UNAVAILABLE", lambda: self.queue.acknowledge(request))
        with self.database() as connection:
            connection.execute("UPDATE receipts SET request_json=?", (sender.wire_json(request),))
            connection.execute("UPDATE deliveries SET next_cursor='corrupt'")
        self.assert_error("STATE_UNAVAILABLE", lambda: self.queue.acknowledge(request))
        bad_batch = {**batch, "signature": "A" * 96}
        with self.database() as connection:
            connection.execute("UPDATE deliveries SET next_cursor=?", (batch["nextCursor"],))
            connection.execute("UPDATE receipts SET batch_json=?", (sender.wire_json(bad_batch),))
        self.assert_error("STATE_UNAVAILABLE", lambda: self.queue.acknowledge(request))
        self.assertEqual(1, self.queue.stats()["queuedMessages"])
        with self.database() as connection:
            connection.execute("UPDATE receipts SET batch_json=?", (sender.wire_json(batch),))
        reply = self.queue.acknowledge(request)
        with self.database() as connection:
            connection.execute("UPDATE receipts SET ack_json=?", (sender.wire_json({**reply, "protocolVersion": True}),))
        self.assert_error("STATE_UNAVAILABLE", lambda: self.restart().acknowledge(request))
        self.assertEqual(1, self.queue.stats()["acknowledgedMessages"])

    def test_wrong_key_permissions_symlink_and_corrupt_database_never_reset_state(self):
        private = self.queue.private
        private.chmod(0o644)
        self.assert_error("INVALID_STATE_PERMISSIONS", self.restart)
        private.chmod(0o600)
        original = private.read_bytes()
        private.write_bytes(sender.openssl(["ecparam", "-name", "prime256v1", "-genkey", "-noout"]))
        self.assert_error("STATE_UNAVAILABLE", self.restart)
        private.write_bytes(original)
        alias = Path(self.temporary.name) / "linked-state"
        alias.symlink_to(self.root, target_is_directory=True)
        self.assert_error("INVALID_STATE_PATH", lambda: sender.LocalSenderQueue(alias))
        self.queue.database.write_bytes(b"corrupted-state")
        before = hashlib.sha256(self.queue.database.read_bytes()).hexdigest()
        self.assert_error("STATE_UNAVAILABLE", self.restart)
        self.assertEqual(before, hashlib.sha256(self.queue.database.read_bytes()).hexdigest())

    def test_separate_cli_processes_restore_the_same_snapshot_and_ack_reply(self):
        script = str(Path(sender.__file__).resolve())
        def command(*arguments, content=""):
            result = subprocess.run([sys.executable, script, "--state-dir", str(self.root), *arguments],
                                    input=content, text=True, capture_output=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            return json.loads(result.stdout)
        command("enqueue", "--receiver-id", self.receiver, "--message-id", "cli", "--conversation-key", "conversation", content="合成 CLI 正文")
        batch = command("pull", "--receiver-id", self.receiver)
        self.assertEqual(batch, command("pull", "--receiver-id", self.receiver))
        raw = sender.wire_json(sender.receipt_request(batch))
        self.assertEqual(command("ack", content=raw), command("ack", content=raw))


if __name__ == "__main__":
    unittest.main()
