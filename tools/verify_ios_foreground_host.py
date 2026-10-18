#!/usr/bin/env python3
"""long: 独立回读公开UIKit验收事件，核对实际结果、终态及生命周期，不读取模型凭据。"""
import argparse
import hashlib
import json
from pathlib import Path


def verify(rows, marker):
    runs = {}
    previous_sequence = 0
    previous_row = None
    for row in rows:
        if "run_id" not in row:
            previous_row = row
            continue
        assert row["sequence"] > previous_sequence
        previous_sequence = row["sequence"]
        events = runs.setdefault(row["run_id"], [])
        if not events:
            # long: 每个Run必须来自真实前台按钮记录，空历史起步，不能通过旧Run或激活通知自动续跑。
            assert previous_row["action"] == "TAP_RUN" and previous_row["application_state"] == 0
            assert row["reason"] == "STARTED" and row["status"] == "RUNNING"
            assert row["tools"] == row["frames"] == [] and row["model_requests"] == 0
        else:
            assert events[-1]["status"] == "RUNNING", "终态后出现迟到事件"
            old = events[-1]
            assert row["tools"][:len(old["tools"])] == old["tools"]
            assert row["frames"][:len(old["frames"])] == old["frames"]
            assert row["model_requests"] >= old["model_requests"]
        assert len(row["tools"]) == len(row["frames"]) <= 8
        call_ids = [frame["toolCallId"] for frame in row["frames"]]
        assert len(set(call_ids)) == len(call_ids)
        for tool, frame in zip(row["tools"], row["frames"]):
            assert tool in {"workspace.list", "workspace.read_file", "browser.list_pages", "browser.read_page"}
            assert frame["schemaVersion"] == 1
            result = frame["result"]
            assert result["verified"] is result["verificationEvidence"] is result["executionReceipt"] is None
            evidence = result["readableEvidence"]
            if evidence:
                assert evidence["toolCallId"] == frame["toolCallId"]
                assert "browserAction" not in evidence or evidence["browserAction"] is None
                assert tool == "browser.read_page" and evidence["kind"] == "BROWSER_PAGE"
                assert evidence["sourceRef"] == "https://example.com/"
                assert hashlib.sha256(result["content"].encode()).hexdigest() == evidence["contentHash"]
        if row["reason"] == "USER_CANCELLED":
            assert previous_row["action"] == "TAP_CANCEL" and row["status"] == "CANCELLED"
        if row["reason"] == "FOREGROUND_REVOKED":
            assert previous_row["lifecycle"] == "RESIGN_ACTIVE" and row["status"] == "CANCELLED"
        events.append(row)
        previous_row = row
    terminals = [events[-1] for events in runs.values()]
    assert all(row["status"] != "RUNNING" for row in terminals)
    completed = [row for row in terminals if row["status"] == "COMPLETED"]
    assert len(completed) >= 2
    all_ids = []
    for row in completed:
        assert {"workspace.list", "workspace.read_file", "browser.read_page"} <= set(row["tools"])
        assert marker in row["summary"] and "Example Domain" in row["summary"]
        for tool, frame in zip(row["tools"], row["frames"]):
            assert frame["result"]["success"] is True
            all_ids.append(frame["toolCallId"])
            content = json.loads(frame["result"]["content"])
            if tool == "workspace.read_file":
                assert content["path"] == "验收.txt" and marker in content["text"] and not content["truncated"]
            if tool == "browser.read_page":
                assert content["mode"] == "STATIC_TEXT" and content["page_id"] == "example"
                assert content["title"] == "Example Domain" and "documentation examples" in content["text"]
    assert len(all_ids) == len(set(all_ids)), "不同Run复用了调用身份"
    for reason in ["USER_CANCELLED", "FOREGROUND_REVOKED"]:
        cancelled = [row for row in terminals if row["reason"] == reason]
        assert cancelled and all(not row["summary"] for row in cancelled)
        assert all(row["model_requests"] > len(row["tools"]) for row in cancelled), "未覆盖模型请求等待期间撤销"
    background = next(i for i, row in enumerate(rows) if row.get("lifecycle") == "BACKGROUND")
    active = next(i for i in range(background + 1, len(rows)) if rows[i].get("lifecycle") == "ACTIVE")
    explicit = next(i for i in range(active + 1, len(rows)) if rows[i].get("action") == "TAP_RUN")
    assert not any("run_id" in row for row in rows[background + 1:explicit]), "激活后自动续跑"
    return {"runs": len(runs), "completed": len(completed), "terminalReasons": [row["reason"] for row in terminals],
            "runIds": list(runs), "actualFileMarkerMatched": True, "browserContentHashMatched": True,
            "uniqueCallBindings": True, "noEventsAfterTerminal": True, "noAutomaticResume": True,
            "cancelDuringModelRequest": True, "realUIKitLifecycleRecorded": True,
            "evidenceScope": "公开宿主事件与可见UI联合；不证明Provider远端已停止计算或POST未提交"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--events", type=Path, required=True)
    parser.add_argument("--marker", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.events.read_text().splitlines()]
    result = verify(rows, args.marker)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
