#!/usr/bin/env python3
"""long: 指定 Redmi 的真实模型功能验收，复用单次 USB 输入、脱敏和失败证据保留。"""

import argparse
import json
from pathlib import Path

from verify_mcp_live_acceptance import SERIAL, adb, load_provider, phase, redact


def descriptor(test, method, stage=292, evidence=None, arguments=None, required_evidence=None):
    if not evidence:
        raise ValueError("每项验收必须指定业务证据标识")
    return {"test": "com.longdev.xiaoling." + test, "method": method,
            "realArg": f"stage{stage}RealRun", "portArg": f"stage{stage}ConfigPort",
            "ready": f"STAGE{stage}_CONFIG_READY", "evidence": f"STAGE{stage}_{evidence}",
            "requiredEvidence": [f"STAGE{stage}_{marker}" for marker in required_evidence or (evidence,)],
            "progress": f"STAGE{stage}_RUN", "arguments": arguments or {}}


TEST = "agent.AndroidLiveAcceptanceInstrumentedTest"
PHASES = {}
for api, mode in (("chat", "CHAT_COMPLETIONS"), ("responses", "RESPONSES")):
    for streaming in (False, True):
        PHASES[api + ("-stream" if streaming else "")] = descriptor(TEST,
            "ordinaryAndStreamingChatUseRealProviderAndConfirmedCompletion",
            evidence=f"EVIDENCE CHAT mode={mode} streaming={str(streaming).lower()} ",
            arguments={"stage292ApiMode": mode, "stage292Streaming": str(streaming).lower()})
PHASES["chat-cancel"] = descriptor(TEST, "streamingCancellationStopsLocalDeliveryWithoutCompletedResult", evidence="EVIDENCE CHAT_CANCEL ")
for attachment in ("text", "image"):
    PHASES["attachment-" + attachment] = descriptor(TEST, "responsesReadsPublicTextAndSyntheticImageAttachments",
        evidence=f"EVIDENCE ATTACHMENT type={attachment} ",
        arguments={"stage292Attachment": attachment})
PHASES["native"] = descriptor(TEST, "realPlannerReadsFiveNativeStateToolsWithRoomContext", evidence="EVIDENCE NATIVE ")
PHASES["workspace"] = descriptor(TEST, "realPlannerWritesReadsAndExecutesBoundedWorkspaceWithTranscript", evidence="EVIDENCE WORKSPACE ")
PHASES["browser"] = descriptor(TEST, "realPlannerReadsPublicBrowserDomAndNavigatesOneApprovedLink", evidence="EVIDENCE BROWSER ")
PHASES["memory-knowledge"] = descriptor(TEST, "realPlannerRunsIndependentMemoryNoteKnowledgeGoalsAndPreservesSnapshotsOnReopen", evidence="EVIDENCE MEMORY_KNOWLEDGE ")
PHASES["compression"] = descriptor(TEST, "realPlannerUsesDurableExtractiveCompressionAfterTwoLargeNativeReads", evidence="EVIDENCE COMPRESSION ")
PHASES["genui"] = descriptor("genui.GenUiLiveAcceptanceInstrumentedTest", "realModelCardClickWaitsForVisibleApprovalAndPersistsNativeResult", evidence="EVIDENCE GENUI ")
PHASES["plugin"] = descriptor("agent.AgentPluginProductionInstrumentedTest",
    "explicitRealProviderUsesSignedSelectionAndNativeTimeLedger", 288, "LIVE")
PHASES["children"] = descriptor("agent.AgentChildBatchInstrumentedTest",
    "explicitRealProviderRunsTwoNativeChildrenUnderActualForeground", 289, "LIVE")
PHASES["product-ui"] = descriptor("agent.AndroidProductLiveInstrumentedTest", "actualViewModelSendsChatsAndApprovesRealModelGenUiCard",
    evidence="EVIDENCE PRODUCT_", required_evidence=("EVIDENCE PRODUCT_SCOPE ",
        "EVIDENCE PRODUCT_CHAT mode=CHAT_COMPLETIONS streaming=false ", "EVIDENCE PRODUCT_CHAT mode=RESPONSES streaming=true ",
        "EVIDENCE PRODUCT_GENUI ", "EVIDENCE PRODUCT_RESTORE "))
for action in ("tap", "type", "scroll"):
    PHASES["device-" + action] = descriptor("agent.AndroidDeviceLiveInstrumentedTest", "realPlannerObservesAndActsOnlyOnPublicNativeProbe",
        evidence=f"EVIDENCE DEVICE action={action} ",
        required_evidence=("EVIDENCE DEVICE_REBIND ", f"EVIDENCE DEVICE action={action} "),
        arguments={"stage292DeviceAction": action})

# long: 人工浮层必须同时留下当前动作的业务证据与恢复回执，旧受控 ApprovalGate 的成功不能满足本专项。
for action, decision in (("tap", "approve"), ("type", "approve"), ("tap", "deny"), ("tap", "window-change")):
    PHASES[f"overlay-{action}-{decision}"] = descriptor("agent.AndroidDeviceApprovalLiveInstrumentedTest",
        "realModelUsesProductionDeviceApprovalOverlayOnPublicProbe", stage=293, evidence="EVIDENCE OVERLAY",
        required_evidence=(f"EVIDENCE OVERLAY action={action} decision={decision} ", "EVIDENCE OVERLAY_RESTORE "),
        arguments={"stage293Action": action, "stage293Decision": decision})


def main():
    parser = argparse.ArgumentParser(description="真实 Android 功能验收；APK 需先覆盖安装，不修改正式 Provider")
    parser.add_argument("--phase", choices=PHASES, action="append")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--device-port", type=int, default=19892)
    args = parser.parse_args()
    if not 1024 <= args.device_port <= 65535:
        raise ValueError("设备端口无效")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("验收输出目录必须为空，不能覆盖历史失败")
    provider = load_provider(Path(__file__).resolve().parent.parent)
    payload = json.dumps(provider).encode("utf-8")
    if len(payload) > 16_384:
        raise ValueError("USB 配置超过上限")
    secrets = (provider["apiKey"],)
    report = {"serial": SERIAL, "model": provider["model"], "phases": [], "completed": False}
    try:
        if adb("shell", "getprop", "ro.product.device") != "begonia" or adb("shell", "getprop", "ro.build.version.sdk") != "36":
            raise RuntimeError("设备与指定 Redmi/SDK 基线不符")
        # long: 单项失败不取消独立功能验收；每项都保留真实终态，最终整轮仍按失败退出。
        for name in args.phase or PHASES:
            report["phases"].append(phase(name, args.device_port, payload, secrets, output, descriptor=PHASES[name]))
        report["completed"] = all(item["passed"] for item in report["phases"])
    except Exception as error:
        report["error"] = redact(f"{type(error).__name__}: {error}", secrets)
    finally:
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if report["completed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
