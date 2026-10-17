package com.longdev.xiaoling.agent

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class AgentPluginSignedManifest(
    val manifest: AgentPluginManifest,
    val publicKeySpkiBase64: String,
    val signatureBase64: String,
    val envelopeVersion: Int = 1,
    val algorithm: String = AgentPluginSigning.ALGORITHM,
)

data class AgentPluginPublisherConfirmation(
    val manifestSha256: String,
    val publicKeySha256: String,
)

object AgentPluginSigning {
    const val ALGORITHM = "SHA256withECDSA-P256-DER"
    const val MAX_HISTORY = 3

    fun verify(signed: AgentPluginSignedManifest): AgentPluginPublisherConfirmation {
        require(signed.envelopeVersion == 1 && signed.algorithm == ALGORITHM) { "插件签名版本或算法不受支持" }
        val bytes = canonicalBytes(signed.manifest)
        val encodedKey = base64(signed.publicKeySpkiBase64, 512)
        val spkiPrefix = byteArrayOf(0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48,
            0xce.toByte(), 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07,
            0x03, 0x42, 0x00, 0x04)
        // long: 固定 named P-256 和未压缩点 SPKI，避免不同 provider 对显式参数或压缩点保留不同编码。
        require(encodedKey.size == 91 && encodedKey.copyOfRange(0, spkiPrefix.size).contentEquals(spkiPrefix)) {
            "插件公钥必须是 named P-256 SPKI"
        }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encodedKey))
        require(key is ECPublicKey) { "插件公钥必须使用 EC" }
        val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        require(key.params.curve == expected.curve && key.params.generator == expected.generator &&
            key.params.order == expected.order && key.params.cofactor == expected.cofactor) { "插件公钥必须使用 P-256" }
        // long: 固定 SPKI 编码，拒绝尾随字节和同一公钥的非规范表示，保证确认指纹在 JVM 与 Android 一致。
        require(encodedKey.contentEquals(key.encoded)) { "插件公钥 SPKI 编码不规范" }
        val signature = base64(signed.signatureBase64, 100)
        validateDer(signature, expected.order)
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(bytes)
            verify(signature)
        }) { "插件 manifest 签名无效" }
        return AgentPluginPublisherConfirmation(sha256(bytes), sha256(encodedKey))
    }

    fun canonicalBytes(manifest: AgentPluginManifest): ByteArray {
        AgentPluginPolicy.validate(manifest)
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                fun field(value: String) {
                    // long: 不接受孤立代理项，防止不同字符串被 UTF-8 替换符折叠成相同的签名内容。
                    val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
                    val bytes = ByteArray(encoded.remaining()).also(encoded::get)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
                // long: 独立域标签和长度前缀防止跨协议签名复用；集合排序不依赖 JSON 排版或插入顺序。
                field("XIAOLING_AGENT_PLUGIN_MANIFEST_V1")
                field(manifest.id)
                field(manifest.version)
                out.writeInt(manifest.apiVersion)
                field(manifest.name)
                field(manifest.description)
                field(manifest.publisher)
                out.writeInt(manifest.capabilities.size)
                manifest.capabilities.sorted().forEach(::field)
                out.writeInt(manifest.permissions.size)
                manifest.permissions.map { it.name }.sorted().forEach(::field)
                field(manifest.executionMode.name)
                out.writeBoolean(manifest.source != null)
                manifest.source?.let { field(it.url); field(it.commit); field(it.contentSha256) }
            }
        }.toByteArray()
    }

    private fun base64(value: String, maxLength: Int): ByteArray {
        require(value.isNotEmpty() && value.length <= maxLength) { "插件公钥或签名长度无效" }
        val bytes = Base64.getDecoder().decode(value)
        require(Base64.getEncoder().encodeToString(bytes) == value) { "插件公钥或签名必须是规范 Base64" }
        return bytes
    }

    private fun validateDer(bytes: ByteArray, order: BigInteger) {
        require(bytes.size in 8..72 && bytes[0] == 0x30.toByte() && (bytes[1].toInt() and 255) == bytes.size - 2) {
            "插件签名必须是规范 ECDSA DER"
        }
        var offset = 2
        repeat(2) {
            require(offset + 2 <= bytes.size && bytes[offset++] == 2.toByte()) { "插件签名 DER 整数无效" }
            val length = bytes[offset++].toInt() and 255
            require(length in 1..33 && offset + length <= bytes.size) { "插件签名 DER 整数长度无效" }
            val value = bytes.copyOfRange(offset, offset + length)
            require(value[0] >= 0 && !(length > 1 && value[0] == 0.toByte() && value[1] >= 0)) {
                "插件签名 DER 整数不规范"
            }
            val integer = BigInteger(value)
            require(integer.signum() > 0 && integer < order) { "插件签名 DER 整数超出 P-256 范围" }
            offset += length
        }
        require(offset == bytes.size) { "插件签名 DER 存在尾随数据" }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
