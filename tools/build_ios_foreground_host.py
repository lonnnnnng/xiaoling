#!/usr/bin/env python3
"""long: 只构建本仓库的Simulator验收宿主，不读取凭据、不安装或发布。"""
import argparse
import json
import plistlib
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-framework-build", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.skip_framework_build:
        subprocess.run([str(root / "gradlew"), ":shared:linkDebugFrameworkIosSimulatorArm64"], cwd=root, check=True)
    sdk = subprocess.check_output(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"], text=True).strip()
    framework = root / "shared/build/bin/iosSimulatorArm64/debugFramework"
    assert (framework / "XiaoLingShared.framework/Headers/XiaoLingShared.h").is_file()
    app = root / "outputs/ios-foreground-host/XiaoLingForegroundHost.app"
    app.mkdir(parents=True, exist_ok=True)
    # long: 显式选择Simulator工具链与SDK；静态Kotlin平台缓存中的未用API须由dead_strip移除，不能给旧SDK伪造系统符号。
    subprocess.run(["xcrun", "--sdk", "iphonesimulator", "swiftc", "-parse-as-library", "-sdk", sdk, "-target", "arm64-apple-ios15.0-simulator", "-F", str(framework), "-Xlinker", "-dead_strip",
                    "-framework", "XiaoLingShared", "-framework", "UIKit", "-framework", "Foundation", "-lxml2",
                    str(root / "ios-host/ForegroundHost.swift"), "-o", str(app / "XiaoLingForegroundHost")], check=True)
    info = {"CFBundleIdentifier": "com.longdev.xiaoling.iosforegroundhost", "CFBundleExecutable": "XiaoLingForegroundHost",
            "CFBundleName": "小灵前台验收", "CFBundlePackageType": "APPL", "CFBundleVersion": "1", "CFBundleShortVersionString": "0.0.1",
            "MinimumOSVersion": "15.0", "LSRequiresIPhoneOS": True, "UIDeviceFamily": [1], "UILaunchScreen": {},
            "UISupportedInterfaceOrientations": ["UIInterfaceOrientationPortrait"]}
    (app / "Info.plist").write_bytes(plistlib.dumps(info))
    subprocess.run(["codesign", "--force", "--sign", "-", str(app)], check=True)
    print(json.dumps({"app": str(app), "bundleId": info["CFBundleIdentifier"], "sdk": sdk}, ensure_ascii=False))


if __name__ == "__main__":
    main()
