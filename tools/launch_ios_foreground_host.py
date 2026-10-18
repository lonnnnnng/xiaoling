#!/usr/bin/env python3
"""long: 在指定Simulator显式注入本次授权测试配置；密钥只进入子进程环境，不写文件或命令参数。"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", required=True)
    parser.add_argument("--goal", default="列出工作区，读取验收.txt，再读取 example 页面，最后总结实际内容并准确引用文件标记。")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    devices = json.loads(subprocess.check_output(["xcrun", "simctl", "list", "devices", "-j"]))
    device = next(d for rows in devices["devices"].values() for d in rows if d["udid"] == args.device)
    assert device["isAvailable"] and device["name"] == "iPhone 16 Pro"
    if device["state"] == "Shutdown":
        subprocess.run(["xcrun", "simctl", "boot", args.device], check=True)
    subprocess.run(["xcrun", "simctl", "bootstatus", args.device, "-b"], check=True)
    app = root / "outputs/ios-foreground-host/XiaoLingForegroundHost.app"
    assert app.is_dir()
    subprocess.run(["xcrun", "simctl", "install", args.device, str(app)], check=True)
    source = (root / "AGENTS.md").read_text()
    fields = dict(re.findall(r"^\s*(baseurl|apikey|使用模型)\s*[:：]\s*(\S+)\s*$", source, re.MULTILINE))
    assert set(fields) == {"baseurl", "apikey", "使用模型"}
    marker = "ios-foreground-" + uuid.uuid4().hex
    env = os.environ.copy()
    values = {"XIAOLING_MODEL_ENDPOINT": fields["baseurl"].rstrip("/") + "/chat/completions", "XIAOLING_MODEL": fields["使用模型"],
              "XIAOLING_MODEL_KEY": fields["apikey"], "XIAOLING_GOAL": args.goal, "XIAOLING_MARKER": marker}
    env.update({"SIMCTL_CHILD_" + name: value for name, value in values.items()})
    result = subprocess.run(["xcrun", "simctl", "launch", "--terminate-running-process", args.device, "com.longdev.xiaoling.iosforegroundhost"],
                            env=env, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("Simulator宿主启动失败；输出未保存，避免外部错误反射凭据")
    print(json.dumps({"device": args.device, "model": fields["使用模型"], "marker": marker, "launched": True, "runStarted": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
