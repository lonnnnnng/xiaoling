package com.longdev.xiaoling.agent

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** long: JVM 与 Redmi 共用攻击向量，私钥只在测试进程内生成，绝不写入插件账本。 */
object AgentPluginSigningFixture {
    fun key(curve: String = "secp256r1"): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(curve))
    }.generateKeyPair()

    fun manifest(version: String = "1.0.0") = AgentPluginManifest(
        id = "signed.read", version = version, name = "签名演示", description = "声明只读时间能力",
        publisher = "long", capabilities = setOf("app.current_time", "memory.search"),
        permissions = setOf(AgentPluginPermission.READ_ONLY_CAPABILITIES),
        source = AgentPluginSource("https://example.com/plugin", "a".repeat(40), "b".repeat(64)),
    )

    fun sign(manifest: AgentPluginManifest = manifest(), key: KeyPair = key()): AgentPluginSignedManifest {
        val bytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private); update(AgentPluginSigning.canonicalBytes(manifest)); sign()
        }
        return AgentPluginSignedManifest(manifest, Base64.getEncoder().encodeToString(key.public.encoded),
            Base64.getEncoder().encodeToString(bytes))
    }
}

abstract class AgentPluginSigningContractTests {
    protected open fun freshStore(): AgentPluginStateStore = TestStore()

    @Test
    fun everyManifestFieldAndSourceFieldIsBoundToSignature() {
        val signed = AgentPluginSigningFixture.sign()
        val m = signed.manifest
        val changed = listOf(m.copy(id = "other.read"), m.copy(version = "1.0.1"), m.copy(apiVersion = 2),
            m.copy(name = "变更"), m.copy(description = "变更"), m.copy(publisher = "other"),
            m.copy(capabilities = setOf("app.current_time")), m.copy(permissions = emptySet()),
            m.copy(executionMode = AgentPluginExecutionMode.EXTERNAL_CODE), m.copy(source = null),
            m.copy(source = m.source!!.copy(url = "https://example.com/changed")),
            m.copy(source = m.source!!.copy(commit = "c".repeat(40))),
            m.copy(source = m.source!!.copy(contentSha256 = "d".repeat(64))))
        changed.forEach { assertTrue(runCatching { AgentPluginSigning.verify(signed.copy(manifest = it)) }.isFailure) }
    }

    @Test
    fun canonicalSetsIgnoreInsertionOrderAndUnicodeReplacementIsRejected() {
        val signed = AgentPluginSigningFixture.sign()
        assertEquals(AgentPluginSigning.verify(signed), AgentPluginSigning.verify(signed.copy(
            manifest = signed.manifest.copy(capabilities = signed.manifest.capabilities.reversed().toSet()))))
        assertTrue(runCatching { AgentPluginSigning.canonicalBytes(signed.manifest.copy(name = "\uD800")) }.isFailure)
    }

    @Test
    fun wrongKeyCurveAlgorithmEncodingAndDerAreRejected() {
        val signed = AgentPluginSigningFixture.sign()
        val other = AgentPluginSigningFixture.key()
        val invalid = listOf(signed.copy(algorithm = "ES256"), signed.copy(envelopeVersion = 2),
            signed.copy(publicKeySpkiBase64 = Base64.getEncoder().encodeToString(other.public.encoded)),
            AgentPluginSigningFixture.sign(key = AgentPluginSigningFixture.key("secp384r1")),
            signed.copy(signatureBase64 = signed.signatureBase64 + "\n"),
            signed.copy(publicKeySpkiBase64 = signed.publicKeySpkiBase64.trimEnd('=')),
            signed.copy(signatureBase64 = Base64.getEncoder().encodeToString(byteArrayOf(0x30, 6, 2, 1, 0, 2, 1, 1))),
            signed.copy(signatureBase64 = Base64.getEncoder().encodeToString(Base64.getDecoder().decode(signed.signatureBase64) + 0)),
            signed.copy(publicKeySpkiBase64 = Base64.getEncoder().encodeToString(other.public.encoded + 0)))
        invalid.forEach { assertTrue(runCatching { AgentPluginSigning.verify(it) }.isFailure) }
    }

    @Test
    fun signedJsonRejectsDuplicatesUnknownFieldsLeniencyAndOversize() {
        val signed = AgentPluginSigningFixture.sign()
        val raw = AgentPluginJsonCodec.encodeSignedManifest(signed)
        assertEquals(signed, AgentPluginJsonCodec.decodeSignedManifest(raw))
        val duplicate = raw.replace("\"envelopeVersion\":1", "\"envelopeVersion\":1,\"envelope\\u0056ersion\":1")
        val invalid = listOf(duplicate, raw.replace("\"algorithm\"", "algorithm"), raw + " true",
            JSONObject(raw).put("untrusted", true).toString(), " ".repeat(24 * 1024) + raw,
            raw.replace("\"apiVersion\":1", "\"apiVersion\":1."),
            raw.replace("\"apiVersion\":1", "\"apiVersion\":1.000000000000000000000001"),
            raw.replace("\"apiVersion\":1", "\"apiVersion\":1e0"))
        invalid.forEach { assertTrue(runCatching { AgentPluginJsonCodec.decodeSignedManifest(it) }.isFailure) }
    }

    @Test
    fun firstInstallRequiresConfirmationOfExactManifestAndKey() {
        val registry = AgentPluginRegistry(freshStore())
        val key = AgentPluginSigningFixture.key()
        val signed = AgentPluginSigningFixture.sign(key = key)
        assertRejected(registry.installSigned(signed))
        val confirmation = AgentPluginSigning.verify(signed)
        assertRejected(registry.installSigned(AgentPluginSigningFixture.sign(signed.manifest.copy(description = "变更"), key), confirmation))
        assertRejected(registry.installSigned(AgentPluginSigningFixture.sign(), confirmation))
        assertTrue(registry.list().isEmpty())
        assertInstalled(registry.installSigned(signed, confirmation))
        assertFalse(registry.get(signed.manifest.id)!!.enabled)
    }

    @Test
    fun resigningSameContentIsIdempotentAndVersionDriftIsRejected() {
        val registry = AgentPluginRegistry(freshStore())
        val key = AgentPluginSigningFixture.key()
        val signed = AgentPluginSigningFixture.sign(key = key)
        assertInstalled(registry.installSigned(signed, AgentPluginSigning.verify(signed)))
        registry.setEnabled(signed.manifest.id, true)
        assertTrue(registry.installSigned(AgentPluginSigningFixture.sign(signed.manifest, key)) is AgentPluginInstallResult.AlreadyInstalled)
        assertTrue(registry.get(signed.manifest.id)!!.enabled)
        assertRejected(registry.installSigned(AgentPluginSigningFixture.sign(signed.manifest.copy(description = "漂移"), key)))
    }

    @Test
    fun upgradeCannotChangeConfirmedPublisherOrRemoveSignature() {
        val registry = AgentPluginRegistry(freshStore())
        val key = AgentPluginSigningFixture.key()
        val signed = AgentPluginSigningFixture.sign(key = key)
        registry.installSigned(signed, AgentPluginSigning.verify(signed))
        val next = signed.manifest.copy(version = "2.0.0")
        assertRejected(registry.installSigned(AgentPluginSigningFixture.sign(next)))
        assertRejected(registry.installSigned(AgentPluginSigningFixture.sign(next.copy(publisher = "other"), key)))
        assertRejected(registry.install(next))
        assertInstalled(registry.installSigned(AgentPluginSigningFixture.sign(next, key)))
        assertFalse(registry.get(next.id)!!.enabled)
    }

    @Test
    fun rollbackRevalidatesRetainedVersionsAndPreservesHighestAcrossRestart() {
        val store = freshStore()
        val key = AgentPluginSigningFixture.key()
        val registry = AgentPluginRegistry(store)
        val first = AgentPluginSigningFixture.sign(key = key)
        registry.installSigned(first, AgentPluginSigning.verify(first))
        registry.setEnabled(first.manifest.id, true)
        val next = AgentPluginSigningFixture.sign(first.manifest.copy(version = "2.0.0"), key)
        registry.installSigned(next)
        registry.setEnabled(first.manifest.id, true)
        assertRejected(registry.rollback(first.manifest.id, "0.1.0"))
        assertRejected(registry.rollback(first.manifest.id, "1.0.0", first.manifest))
        assertInstalled(registry.rollback(first.manifest.id, "1.0.0", next.manifest))
        val restored = AgentPluginRegistry(store)
        assertEquals(first.manifest, restored.get(first.manifest.id)!!.manifest)
        assertFalse(restored.get(first.manifest.id)!!.enabled)
        assertEquals("2.0.0", restored.get(first.manifest.id)!!.highestSignedManifest!!.manifest.version)
        assertRejected(restored.installSigned(AgentPluginSigningFixture.sign(first.manifest.copy(version = "1.5.0"), key)))
        assertInstalled(restored.installSigned(next))
    }

    @Test
    fun signedUninstallRetainsIdentityAndHighestVersionForReinstall() {
        val store = freshStore()
        val key = AgentPluginSigningFixture.key()
        val signed = AgentPluginSigningFixture.sign(AgentPluginSigningFixture.manifest("2.0.0"), key)
        val registry = AgentPluginRegistry(store)
        registry.installSigned(signed, AgentPluginSigning.verify(signed))
        assertTrue(registry.uninstall(signed.manifest.id))
        val restored = AgentPluginRegistry(store)
        assertTrue(restored.list().isEmpty())
        assertNull(restored.get(signed.manifest.id))
        assertNull(restored.setEnabled(signed.manifest.id, true))
        assertRejected(restored.install(signed.manifest))
        assertRejected(restored.installSigned(AgentPluginSigningFixture.sign(AgentPluginSigningFixture.manifest(), key)))
        assertRejected(restored.installSigned(AgentPluginSigningFixture.sign(signed.manifest)))
        assertInstalled(restored.installSigned(signed))
        assertFalse(restored.get(signed.manifest.id)!!.enabled)
    }

    @Test
    fun historyIsBoundedAndEvictedVersionCannotBeReintroduced() {
        val store = freshStore()
        val registry = AgentPluginRegistry(store)
        val key = AgentPluginSigningFixture.key()
        for (version in 1..6) {
            val signed = AgentPluginSigningFixture.sign(AgentPluginSigningFixture.manifest("$version.0.0"), key)
            assertInstalled(registry.installSigned(signed, AgentPluginSigning.verify(signed)))
        }
        val restored = AgentPluginRegistry(store)
        assertEquals(listOf("5.0.0", "4.0.0", "3.0.0"), restored.rollbackVersions("signed.read"))
        assertRejected(restored.rollback("signed.read", "2.0.0"))
        assertEquals(3, restored.get("signed.read")!!.history.size)
    }

    @Test
    fun oldUnsignedSnapshotRemainsPreviewOnlyUntilExactConfirmation() {
        val signed = AgentPluginSigningFixture.sign()
        val item = JSONObject(AgentPluginJsonCodec.encode(listOf(InstalledAgentPlugin(signed.manifest, true))))
        item.put("schemaVersion", 1)
        val plugin = item.getJSONArray("plugins").getJSONObject(0)
        listOf("signedManifest", "highestSignedManifest", "history", "removed").forEach(plugin::remove)
        val store = freshStore().apply { saveState(item.toString()) }
        val registry = AgentPluginRegistry(store)
        assertTrue(registry.get(signed.manifest.id)!!.enabled)
        assertNull(registry.get(signed.manifest.id)!!.signedManifest)
        assertRejected(registry.installSigned(signed))
        assertInstalled(registry.installSigned(signed, AgentPluginSigning.verify(signed)))
        assertFalse(AgentPluginRegistry(store).get(signed.manifest.id)!!.enabled)
    }

    @Test
    fun damagedCurrentHistoryOrHighestSnapshotFreezesChanges() {
        val key = AgentPluginSigningFixture.key()
        val first = AgentPluginSigningFixture.sign(key = key)
        val store = freshStore()
        AgentPluginRegistry(store).apply {
            installSigned(first, AgentPluginSigning.verify(first))
            installSigned(AgentPluginSigningFixture.sign(first.manifest.copy(version = "2.0.0"), key))
        }
        val good = store.loadState()!!
        for (field in listOf("signedManifest", "highestSignedManifest", "history")) {
            val root = JSONObject(good)
            val plugin = root.getJSONArray("plugins").getJSONObject(0)
            val envelope = if (field == "history") plugin.getJSONArray(field).getJSONObject(0) else plugin.getJSONObject(field)
            envelope.getJSONObject("manifest").put("description", "被篡改")
            val damaged = root.toString()
            store.saveState(damaged)
            val restored = AgentPluginRegistry(store)
            assertNotNull(restored.persistenceFailureReason())
            assertTrue(restored.list().isEmpty())
            assertRejected(restored.installSigned(first, AgentPluginSigning.verify(first)))
            assertEquals(damaged, store.loadState())
        }
    }

    @Test
    fun writeFailureCannotCommitTrustUpgradeRollbackOrUninstall() {
        val store = TestStore()
        val registry = AgentPluginRegistry(store)
        val key = AgentPluginSigningFixture.key()
        val first = AgentPluginSigningFixture.sign(key = key)
        store.failWrites = true
        assertRejected(registry.installSigned(first, AgentPluginSigning.verify(first)))
        assertTrue(registry.list().isEmpty())
        store.failWrites = false
        registry.installSigned(first, AgentPluginSigning.verify(first))
        val next = AgentPluginSigningFixture.sign(first.manifest.copy(version = "2.0.0"), key)
        val original = store.loadState()
        store.failWrites = true
        assertRejected(registry.installSigned(next))
        assertEquals(original, store.loadState())
        assertEquals(first.manifest, registry.get(first.manifest.id)!!.manifest)
        store.failWrites = false
        registry.installSigned(next)
        val upgraded = store.loadState()
        store.failWrites = true
        assertRejected(registry.rollback(first.manifest.id, "1.0.0"))
        assertFalse(registry.uninstall(first.manifest.id))
        assertNull(registry.setEnabled(first.manifest.id, true))
        assertEquals(upgraded, store.loadState())
        assertEquals(next.manifest, registry.get(first.manifest.id)!!.manifest)
    }

    @Test
    fun whitespacePaddingCannotEscapeOriginalFieldBounds() {
        val m = AgentPluginSigningFixture.manifest()
        listOf(m.copy(name = " ".repeat(200) + "x"), m.copy(description = " ".repeat(2_000) + "x"))
            .forEach { assertTrue(runCatching { AgentPluginSigning.canonicalBytes(it) }.isFailure) }
    }

    @Test
    fun inputAndReturnedCollectionsCannotChangeRegistryOutsidePersistence() {
        val store = freshStore()
        val capabilities = mutableSetOf("app.current_time", "memory.search")
        val manifest = AgentPluginSigningFixture.manifest().copy(capabilities = capabilities)
        val registry = AgentPluginRegistry(store)
        registry.install(manifest)
        capabilities.add("app.get_info")
        assertFalse(registry.get(manifest.id)!!.manifest.capabilities.contains("app.get_info"))
        val signed = AgentPluginSigningFixture.sign(registry.get(manifest.id)!!.manifest)
        registry.installSigned(signed, AgentPluginSigning.verify(signed))
        val restored = AgentPluginRegistry(store)
        val original = store.loadState()
        val item = restored.list().single()
        assertTrue(runCatching { (item.manifest.capabilities as MutableSet<String>).add("app.get_info") }.isFailure)
        assertTrue(runCatching { (item.signedManifest!!.manifest.permissions as MutableSet<AgentPluginPermission>).clear() }.isFailure)
        assertTrue(runCatching { (item.history as MutableList<AgentPluginSignedManifest>).clear() }.isFailure)
        assertEquals(original, store.loadState())
        assertEquals(AgentPluginSigning.verify(signed), AgentPluginSigning.verify(restored.get(manifest.id)!!.signedManifest!!))
    }

    @Test
    fun uninstallConfirmationCannotRemoveAChangedVersion() {
        val registry = AgentPluginRegistry(freshStore())
        val key = AgentPluginSigningFixture.key()
        val signed = AgentPluginSigningFixture.sign(key = key)
        registry.installSigned(signed, AgentPluginSigning.verify(signed))
        registry.installSigned(AgentPluginSigningFixture.sign(signed.manifest.copy(version = "2.0.0"), key))
        assertFalse(registry.uninstall(signed.manifest.id, signed.manifest))
        assertEquals("2.0.0", registry.get(signed.manifest.id)!!.manifest.version)
    }

    private fun assertRejected(result: AgentPluginInstallResult) = assertTrue(result is AgentPluginInstallResult.Rejected)
    private fun assertInstalled(result: AgentPluginInstallResult) = assertTrue(result is AgentPluginInstallResult.Installed)

    private class TestStore(var state: String? = null, var failWrites: Boolean = false) : AgentPluginStateStore {
        override fun loadState() = state
        override fun saveState(state: String) { if (failWrites) error("write failed"); this.state = state }
    }
}
