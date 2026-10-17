package com.longdev.xiaoling.agent

import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.storage.SharedPreferencesAgentPluginStateStore
import java.util.UUID
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** long: 通过外部脚本逐轮重启进程，只共享签名公钥账本，不保存签名私钥或接触正式插件状态。 */
class AgentPluginSigningProcessInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name: String get() {
        assertEquals("begonia", android.os.Build.DEVICE)
        val value = requireNotNull(InstrumentationRegistry.getArguments().getString("pluginPreferences"))
        val uuid = UUID.fromString(value.removePrefix("stage288-plugin-process-"))
        require(value == "stage288-plugin-process-$uuid")
        return value
    }
    private fun store() = SharedPreferencesAgentPluginStateStore(context, name)
    private fun report(confirmation: AgentPluginPublisherConfirmation, cleaned: Boolean = false) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("pluginPid", Process.myPid().toString())
            putString("pluginKeySha256", confirmation.publicKeySha256)
            putString("pluginManifestSha256", confirmation.manifestSha256)
            if (cleaned) putString("pluginCleaned", "true")
        })
    }

    @Test fun reportProtectedPreferencesAndMemoryUndoHashes() {
        assertEquals("begonia", android.os.Build.DEVICE)
        fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        // long: SELinux 不允许主机 run-as 时，只在应用 UID 内返回不可逆摘要；正式 Provider/MCP 内容绝不进入日志。
        val files = JSONObject()
        File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .filter { it.extension == "xml" && !it.name.startsWith("stage") && !it.name.contains("instrumentation") }
            .sortedBy { it.name }.forEach { files.put(it.name, hash(it)) }
        val undo = File(context.filesDir, "agent-memory-delete-undo.json")
        files.put("memoryUndo", if (undo.exists()) hash(undo) else "absent")
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("pluginProtectedState", files.toString())
        })
    }

    @Test fun seedConfirmedSignedUpgrade() {
        assertNull(store().loadState())
        val key = AgentPluginSigningFixture.key()
        val first = AgentPluginSigningFixture.sign(key = key)
        val next = AgentPluginSigningFixture.sign(first.manifest.copy(version = "2.0.0"), key)
        val registry = AgentPluginRegistry(store())
        assertTrue(registry.installSigned(first, AgentPluginSigning.verify(first)) is AgentPluginInstallResult.Installed)
        assertTrue(registry.installSigned(next) is AgentPluginInstallResult.Installed)
        assertNotNull(registry.setEnabled(first.manifest.id, true))
        report(AgentPluginSigning.verify(next))
    }

    @Test fun restoreKeyAndRollbackWithHighestVersionPreserved() {
        val registry = AgentPluginRegistry(store())
        val plugin = requireNotNull(registry.get("signed.read"))
        assertEquals("2.0.0", plugin.manifest.version)
        assertTrue(plugin.enabled)
        assertTrue(registry.rollback(plugin.manifest.id, "1.0.0", plugin.manifest) is AgentPluginInstallResult.Installed)
        val rolled = requireNotNull(AgentPluginRegistry(store()).get(plugin.manifest.id))
        assertEquals("1.0.0", rolled.manifest.version)
        assertFalse(rolled.enabled)
        assertEquals("2.0.0", rolled.highestSignedManifest!!.manifest.version)
        report(AgentPluginSigning.verify(rolled.highestSignedManifest))
    }

    @Test fun restoreRollbackThenVerifyUninstallAndReinstallBoundary() {
        val registry = AgentPluginRegistry(store())
        val plugin = requireNotNull(registry.get("signed.read"))
        assertEquals("1.0.0", plugin.manifest.version)
        assertFalse(plugin.enabled)
        assertTrue(registry.uninstall(plugin.manifest.id, plugin.manifest))
        val removed = AgentPluginRegistry(store())
        assertTrue(removed.list().isEmpty())
        assertTrue(removed.installSigned(plugin.signedManifest!!) is AgentPluginInstallResult.Rejected)
        assertTrue(removed.install(plugin.manifest) is AgentPluginInstallResult.Rejected)
        val highest = requireNotNull(plugin.highestSignedManifest)
        assertTrue(removed.installSigned(highest) is AgentPluginInstallResult.Installed)
        assertFalse(AgentPluginRegistry(store()).get(plugin.manifest.id)!!.enabled)
        // long: 全部恢复和身份边界通过后，只清理本轮严格 UUID 名称的隔离偏好文件。
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        assertNull(store().loadState())
        report(AgentPluginSigning.verify(highest), cleaned = true)
    }
}
