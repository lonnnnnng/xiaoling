package com.longdev.xiaoling.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSnapshotPolicyTest {
    @Test
    fun passwordOtpAndSensitiveNumbersNeverEnterSnapshotOrReceiveReferences() {
        val result = DeviceSnapshotPolicy().build(
            window = rawWindow(
                children = listOf(
                    rawNode(text = "继续", clickable = true, path = listOf(0)),
                    rawNode(text = "secret-password", password = true, editable = true, path = listOf(1)),
                    rawNode(text = "123456", hint = "短信验证码", editable = true, path = listOf(2)),
                    rawNode(text = "13800138000", editable = true, path = listOf(3)),
                    rawNode(text = "sk-abcdefghijklmnop123456", path = listOf(4)),
                    rawNode(text = "138-0013-8000", path = listOf(5)),
                    rawNode(text = "6222 0202 0000 1234", path = listOf(6)),
                    rawNode(text = "not-declared-secret", hint = "登录密码", editable = true, path = listOf(7)),
                ),
            ),
            snapshotId = "snapshot-safe",
            nowMillis = 1_000L,
        ) as DeviceSnapshotAssessment.Available

        val serialized = DeviceSnapshotCodec.encode(result.snapshot)
        assertTrue(serialized.contains("继续"))
        assertFalse(serialized.contains("secret-password"))
        assertFalse(serialized.contains("123456"))
        assertFalse(serialized.contains("13800138000"))
        assertFalse(serialized.contains("sk-abcdefghijklmnop123456"))
        assertFalse(serialized.contains("138-0013-8000"))
        assertFalse(serialized.contains("6222 0202 0000 1234"))
        assertFalse(serialized.contains("not-declared-secret"))
        assertEquals(7, result.snapshot.redactedNodeCount)
        assertEquals(1, result.references.size)
        assertEquals("r1", result.references.single().ref)
        assertEquals(listOf(0), result.references.single().nodePath)
        assertTrue(result.snapshot.nodes.first { it.text == "继续" }.actions.contains(DeviceNodeAction.TAP))
        assertTrue(result.snapshot.nodes.filter { it.redacted }.all { it.ref == null })
    }

    @Test
    fun summaryAcceptsCurrentSnapshotStructureAndRejectsMalformedNodes() {
        val result = DeviceSnapshotPolicy().build(
            window = rawWindow(children = listOf(rawNode(text = "继续", clickable = true, path = listOf(0)))),
            snapshotId = "snapshot-summary",
            nowMillis = 1_000L,
        ) as DeviceSnapshotAssessment.Available

        val summary = DeviceSnapshotCodec.decodeSummary(DeviceSnapshotCodec.encode(result.snapshot))
        assertEquals("com.example.safe", summary?.packageName)
        assertEquals(1, summary?.nodeCount)
        assertEquals(0, summary?.redactedNodeCount)
        assertEquals(1_000L, summary?.capturedAt)
        assertNull(
            DeviceSnapshotCodec.decodeSummary(
                """{"snapshot_id":"broken","package":"com.example.safe","window_id":7,"window_generation":11,"captured_at":1000,"expires_at":31000,"redacted_node_count":0,"truncated":false,"nodes":[1]}""",
            ),
        )
    }

    @Test
    fun paymentWindowIsBlockedWithoutReturningPackageOrNodeContent() {
        val result = DeviceSnapshotPolicy().build(
            window = rawWindow(
                packageName = "com.tencent.mm",
                children = listOf(rawNode(text = "请输入支付密码", password = true, path = listOf(0))),
            ),
            snapshotId = "snapshot-payment",
            nowMillis = 1_000L,
        ) as DeviceSnapshotAssessment.Blocked

        assertEquals(DeviceSnapshotBlockReason.SENSITIVE_WINDOW, result.reason)
        assertFalse(result.message.contains("com.tencent.mm"))
        assertFalse(result.message.contains("支付密码"))
    }

    @Test
    fun sensitiveWindowTitleBlocksSnapshotEvenWhenNodesDoNotRepeatTheMarker() {
        val result = DeviceSnapshotPolicy().build(
            window = rawWindow(
                windowTitle = "订单确认支付",
                children = listOf(rawNode(text = "继续", clickable = true, path = listOf(0))),
            ),
            snapshotId = "snapshot-sensitive-title",
            nowMillis = 1_000L,
        ) as DeviceSnapshotAssessment.Blocked

        assertEquals(DeviceSnapshotBlockReason.SENSITIVE_WINDOW, result.reason)
    }

    @Test
    fun knownPrivacyApplicationIsBlockedBeforeReadingNodeContent() {
        val result = DeviceSnapshotPolicy().build(
            window = rawWindow(
                packageName = "com.x8bit.bitwarden",
                children = listOf(rawNode(text = "vault-secret", path = listOf(0))),
            ),
            snapshotId = "snapshot-vault",
            nowMillis = 1_000L,
        ) as DeviceSnapshotAssessment.Blocked

        assertEquals(DeviceSnapshotBlockReason.PRIVATE_APPLICATION, result.reason)
        assertFalse(result.message.contains("vault-secret"))
        assertFalse(result.message.contains("bitwarden"))
    }

    @Test
    fun snapshotEnforcesNodeAndTextBudgetsWithoutChangingReferenceOrder() {
        val result = DeviceSnapshotPolicy(maxNodes = 3, maxTextCharacters = 12).build(
            window = rawWindow(
                children = List(8) { index ->
                    rawNode(text = "按钮-$index-very-long", clickable = true, path = listOf(index))
                },
            ),
            snapshotId = "snapshot-bounded",
            nowMillis = 1_000L,
        ) as DeviceSnapshotAssessment.Available

        assertEquals(3, result.snapshot.nodes.size)
        assertTrue(result.snapshot.truncated)
        assertTrue(result.snapshot.nodes.sumOf { it.text.orEmpty().length + it.description.orEmpty().length + it.hint.orEmpty().length } <= 12)
        assertEquals(listOf("r1", "r2", "r3"), result.references.map { it.ref })
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), result.references.map { it.nodePath })
    }

    @Test
    fun nonActionableNodesRemainReadableButDoNotReceiveReferences() {
        val result = DeviceSnapshotPolicy().build(
            window = rawWindow(children = listOf(rawNode(text = "页面标题", path = listOf(0)))),
            snapshotId = "snapshot-readonly",
            nowMillis = 1_000L,
        ) as DeviceSnapshotAssessment.Available

        assertEquals("页面标题", result.snapshot.nodes.single().text)
        assertNull(result.snapshot.nodes.single().ref)
        assertTrue(result.references.isEmpty())
    }

    @Test
    fun disabledNodesNeverReceiveActionsOrReferences() {
        val result = DeviceSnapshotPolicy().build(
            window = rawWindow(
                children = listOf(rawNode(text = "暂不可用", clickable = true, enabled = false, path = listOf(0))),
            ),
            snapshotId = "snapshot-disabled-node",
            nowMillis = 1_000L,
        ) as DeviceSnapshotAssessment.Available

        assertTrue(result.references.isEmpty())
        assertTrue(result.snapshot.nodes.single().actions.isEmpty())
        assertNull(result.snapshot.nodes.single().ref)
    }

    @Test
    fun textBudgetDoesNotSplitUtf16SurrogatePairs() {
        val result = DeviceSnapshotPolicy(maxTextCharacters = 2).build(
            window = rawWindow(children = listOf(rawNode(text = "A😀B", path = listOf(0)))),
            snapshotId = "snapshot-unicode-budget",
            nowMillis = 1_000L,
        ) as DeviceSnapshotAssessment.Available

        assertEquals("A", result.snapshot.nodes.single().text)
        assertTrue(result.snapshot.truncated)
        assertFalse(DeviceSnapshotCodec.encode(result.snapshot).contains("\uFFFD"))
    }

    private fun rawWindow(
        packageName: String = "com.example.safe",
        windowTitle: String = "测试页面",
        children: List<RawDeviceNode>,
    ) = RawDeviceWindow(
        packageName = packageName,
        windowTitle = windowTitle,
        windowId = 7,
        generation = 11L,
        root = rawNode(children = children, path = emptyList()),
    )

    private fun rawNode(
        text: String? = null,
        description: String? = null,
        hint: String? = null,
        password: Boolean = false,
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
        path: List<Int>,
        children: List<RawDeviceNode> = emptyList(),
    ) = RawDeviceNode(
        className = if (editable) "android.widget.EditText" else "android.view.View",
        text = text,
        contentDescription = description,
        hintText = hint,
        bounds = DeviceBounds(0, 0, 100, 48),
        visibleToUser = true,
        enabled = enabled,
        password = password,
        clickable = clickable,
        editable = editable,
        scrollable = scrollable,
        checkable = false,
        checked = false,
        selected = false,
        nodePath = path,
        children = children,
    )
}
