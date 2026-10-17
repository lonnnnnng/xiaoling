"""long: 验证离线签名输入拒绝边界及 Kotlin UTF-16 集合排序。"""
import copy
import json
import unittest
from plugin_manifest_signer import canonical_bytes, reject_duplicates


class PluginManifestSignerTest(unittest.TestCase):
    def manifest(self):
        return {"id": "demo", "version": "1.0.0", "apiVersion": 1, "name": "演示", "description": "声明",
                "publisher": "long", "capabilities": [], "permissions": [],
                "executionMode": "DECLARATIVE_MANIFEST_ONLY", "source": None}

    def test_duplicate_fields_including_escaped_names_are_rejected(self):
        with self.assertRaises(ValueError):
            json.loads('{"version":1,"ver\\u0073ion":2}', object_pairs_hook=reject_duplicates)

    def test_unknown_fields_noninteger_api_and_oversized_original_string_rejected(self):
        for extra in ({"unexpected": True}, {"apiVersion": 1.0}, {"apiVersion": True}, {"name": " " * 200 + "x"}):
            with self.assertRaises(AssertionError):
                canonical_bytes(dict(self.manifest(), **extra))

    def test_permissions_modes_and_duplicate_capabilities_fail_closed(self):
        for extra in ({"permissions": ["UNKNOWN"]}, {"executionMode": "EXTERNAL_CODE"},
                      {"capabilities": ["app.current_time", "app.current_time"]}):
            with self.assertRaises(AssertionError):
                canonical_bytes(dict(self.manifest(), **extra))

    def test_supplementary_unicode_order_matches_utf16_and_null_source_is_bound(self):
        self.assertEqual(canonical_bytes(dict(self.manifest(), apiVersion=None)), canonical_bytes(self.manifest()))
        manifest = dict(self.manifest(), capabilities=["\ue000", "\U00010000"])
        payload = canonical_bytes(manifest)
        self.assertLess(payload.index("\U00010000".encode()), payload.index("\ue000".encode()))
        changed = copy.deepcopy(manifest)
        changed["source"] = {"url": "https://example.com/plugin", "commit": "a" * 40, "contentSha256": "b" * 64}
        self.assertNotEqual(payload, canonical_bytes(changed))


if __name__ == "__main__":
    unittest.main()
