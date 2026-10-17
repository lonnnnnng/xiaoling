"""long: 离线签署声明 manifest，私钥由用户指定文件提供，只输出公钥与签名 envelope。"""
import argparse
import base64
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile

ALGORITHM = "SHA256withECDSA-P256-DER"
PERMISSIONS = {"READ_ONLY_CAPABILITIES", "NETWORK", "WORKSPACE", "TERMINAL", "MCP", "DEVICE_ACTION"}


def reject_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("manifest 含重复字段")
        result[key] = value
    return result


def canonical_bytes(manifest):
    required = {"id", "version", "name", "description", "publisher", "capabilities", "permissions", "executionMode", "source"}
    assert isinstance(manifest, dict) and required <= manifest.keys() <= required | {"apiVersion"}, "manifest 字段无效"
    api = manifest.get("apiVersion")
    if api is None:
        api = 1
    assert type(api) is int and api == 1, "仅支持 apiVersion=1"
    limits = {"id": 100, "version": 32, "name": 200, "description": 2000, "publisher": 200}
    for field, limit in limits.items():
        value = manifest[field]
        # long: 与 Kotlin 的 UTF-16 长度界限一致，并拒绝孤立代理项，不能通过空白填充逃过容量检查。
        assert isinstance(value, str) and value.strip() and len(value.encode("utf-16-be")) // 2 <= limit, field + " 长度无效"
    assert re.fullmatch(r"[a-z][a-z0-9._-]{0,99}", manifest["id"]), "ID 无效"
    assert re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", manifest["version"]), "版本必须是 x.y.z"
    assert all(int(value) <= 2147483647 for value in manifest["version"].split(".")), "版本段超出范围"
    caps, perms = manifest["capabilities"], manifest["permissions"]
    for values, count, length in ((caps, 64, 120), (perms, 6, 80)):
        assert isinstance(values, list) and len(values) <= count, "能力或权限数量无效"
        assert all(isinstance(value, str) and value.strip() and len(value.encode("utf-16-be")) // 2 <= length for value in values)
        assert len(set(values)) == len(values), "能力或权限重复"
    assert set(perms) <= PERMISSIONS, "未知权限"
    assert manifest["executionMode"] == "DECLARATIVE_MANIFEST_ONLY", "只支持声明式插件"
    source = manifest["source"]
    if source is not None:
        assert isinstance(source, dict) and source.keys() == {"url", "commit", "contentSha256"}, "来源字段无效"
        assert isinstance(source["url"], str) and source["url"].startswith("https://") and len(source["url"].encode("utf-16-be")) // 2 <= 2000
        assert isinstance(source["commit"], str) and re.fullmatch(r"[0-9a-f]{7,64}", source["commit"])
        assert isinstance(source["contentSha256"], str) and re.fullmatch(r"[0-9a-f]{64}", source["contentSha256"])
    output = bytearray()
    def field(value):
        data = value.encode("utf-8")
        output.extend(struct.pack(">I", len(data)))
        output.extend(data)
    field("XIAOLING_AGENT_PLUGIN_MANIFEST_V1")
    field(manifest["id"])
    field(manifest["version"])
    output.extend(struct.pack(">I", api))
    for name in ("name", "description", "publisher"):
        field(manifest[name])
    # long: Kotlin 按 UTF-16 排序，补充字符的排序不能直接使用 Python Unicode 码点顺序。
    for values in (caps, perms):
        output.extend(struct.pack(">I", len(values)))
        for value in sorted(values, key=lambda item: item.encode("utf-16-be")):
            field(value)
    field(manifest["executionMode"])
    output.append(1 if source is not None else 0)
    if source is not None:
        for name in ("url", "commit", "contentSha256"):
            field(source[name])
    return bytes(output)


def sign(manifest, private_key):
    payload = canonical_bytes(manifest)
    public_key = subprocess.run(["openssl", "pkey", "-in", str(private_key), "-pubout", "-outform", "DER"],
                                check=True, capture_output=True, timeout=30).stdout
    # long: 仅接受 named P-256 + 未压缩点的标准 SPKI，不把其他曲线或显式参数编码伪装成相同算法。
    assert len(public_key) == 91 and public_key.startswith(bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d03010703420004")), "密钥必须为 P-256 SPKI"
    with tempfile.TemporaryDirectory(prefix="xiaoling-plugin-sign-") as directory:
        path = Path(directory) / "payload.bin"
        path.write_bytes(payload)
        signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(private_key), str(path)],
                                   check=True, capture_output=True, timeout=30).stdout
    return {"envelopeVersion": 1, "algorithm": ALGORITHM, "manifest": dict(manifest, apiVersion=1),
            "publicKeySpkiBase64": base64.b64encode(public_key).decode("ascii"),
            "signatureBase64": base64.b64encode(signature).decode("ascii")}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--private-key", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    assert args.manifest.stat().st_size <= 16 * 1024, "manifest 不能超过 16 KiB"
    assert not args.output.exists(), "拒绝覆盖既有输出"
    manifest = json.loads(args.manifest.read_text(), object_pairs_hook=reject_duplicates)
    result = json.dumps(sign(manifest, args.private_key), ensure_ascii=False, separators=(",", ":"))
    assert len(result.encode("utf-8")) <= 24 * 1024, "签名 envelope 不能超过 24 KiB"
    with args.output.open("x") as output:
        output.write(result)
    print("已输出签名 envelope（仅含公钥），请在前台核对公钥指纹后安装")


if __name__ == "__main__":
    main()
