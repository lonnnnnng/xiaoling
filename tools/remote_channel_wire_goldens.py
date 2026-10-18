#!/usr/bin/env python3
"""long: 从独立发送实现生成合成 v1 固定字节，避免 common 测试把自己的 encoder 当验收 oracle。"""
import copy
import hashlib
import json
import remote_channel_sender as sender
import remote_channel_receiver_auth as auth


def vectors():
    message = dict(channelId=" 渠道 ", messageId=" msg-1 ", senderId=" sender ",
                   conversationKey=" 会话😀 ", text="\u00a0中文\r\nemoji😀\r尾\u00a0", receivedAtMillis=1700000000123,
                   nonce=" nonce-1234567890 ", keyId=" key-1 ", signature="synthetic-DER")
    normalized = copy.deepcopy(message)
    for name in ("channelId", "messageId", "senderId", "conversationKey", "nonce", "keyId"):
        normalized[name] = normalized[name].strip()
    normalized["text"] = normalized["text"].replace("\r\n", "\n").replace("\r", "\n").strip()
    batch = dict(protocolVersion=1, channelId="渠道", senderId="sender", keyId="key-1", receiverId="a" * 32,
                 batchId="batch-1", requestCursor=None, nextCursor="游标😀", issuedAtMillis=1700000000123,
                 expiresAtMillis=1700000060123, messages=[normalized], signature="synthetic-outer")
    ack = sender.receipt_request(batch)
    pull = dict(protocolVersion=1, operation="pull", channelId="渠道", senderId="sender", keyId="key-1",
                receiverId="a" * 32, requestCursor=None)
    proof = dict(authVersion=1, channelId="渠道", senderId="sender", keyId="key-1", operation="pull",
                 senderFingerprintSha256="1" * 64, endpointSha256="2" * 64, receiverId="a" * 32,
                 receiverFingerprintSha256="3" * 64, issuedAtMillis=1700000000123, expiresAtMillis=1700000060123,
                 nonce="b" * 32, requestDigestSha256=hashlib.sha256(auth.request_bytes(pull)).hexdigest(), signature="synthetic-proof")
    cases = {"message": (message, sender.message_bytes(normalized)), "batch": (batch, sender.batch_bytes(batch)),
             "ack": (ack, sender.ack_bytes(ack)), "pull": (pull, auth.request_bytes(pull)),
             "ackRequest": (ack, auth.request_bytes(ack)), "proof": (proof, auth.proof_bytes(proof))}
    # long: sender 的 PEER_FIELDS 是 set；固定 JSON 输出顺序使跨进程审计可重复，签名字节仍由独立 canonical 函数生成。
    return {name: {"wire": json.dumps(wire, ensure_ascii=False, separators=(",", ":"), sort_keys=True),
                   "hex": content.hex(), "sha256": hashlib.sha256(content).hexdigest()} for name, (wire, content) in cases.items()}


if __name__ == "__main__":
    print(json.dumps(vectors(), ensure_ascii=False, indent=2))
