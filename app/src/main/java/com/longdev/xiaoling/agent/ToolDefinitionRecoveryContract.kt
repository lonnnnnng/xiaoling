package com.longdev.xiaoling.agent

import java.security.MessageDigest

object ToolDefinitionRecoveryContract {
    private const val SCHEMA_VERSION = 1

    fun snapshot(definition: ToolDefinition): ToolDefinitionRecoverySnapshot {
        return ToolDefinitionRecoverySnapshot(
            schemaVersion = SCHEMA_VERSION,
            contractVersion = definition.recoveryContractVersion,
            notCommittedReplayPolicy = definition.notCommittedReplayPolicy,
            definitionFingerprint = fingerprint(definition),
        )
    }

    fun matches(definition: ToolDefinition, snapshot: ToolDefinitionRecoverySnapshot): Boolean {
        return snapshot.schemaVersion == SCHEMA_VERSION && snapshot == snapshot(definition)
    }

    private fun fingerprint(definition: ToolDefinition): String {
        val fields = buildList {
            add(SCHEMA_VERSION.toString())
            add(definition.recoveryContractVersion.toString())
            add(definition.name)
            add(definition.description)
            add(definition.risk.name)
            add(definition.approvalPolicy.name)
            add(definition.verificationPolicy.name)
            add(definition.replaySafety.name)
            add(definition.notCommittedReplayPolicy.name)
            add(definition.validateBeforeAudit.toString())
            add(definition.timeoutMs?.toString().orEmpty())
            add(definition.permissionPolicy.supportsBackground.toString())
            add(definition.businessValidators.size.toString())
            definition.permissionPolicy.requiredAndroidPermissions.sorted().forEach { add("permission:$it") }
            definition.inputSchema.forEachIndexed { index, field ->
                add("field:$index")
                add(field.name)
                add(field.description)
                add(field.required.toString())
                add(field.type.name)
                add(field.minLength?.toString().orEmpty())
                add(field.maxLength?.toString().orEmpty())
                add(field.minimum?.toString().orEmpty())
                add(field.maximum?.toString().orEmpty())
                field.enumValues.sorted().forEach { add("enum:$it") }
            }
        }
        // long: 长度前缀避免描述或枚举值中的分隔符制造同串异义；业务校验器代码不可序列化，修改其语义时必须同步递增 recoveryContractVersion。
        val canonical = buildString {
            fields.forEach { field -> append(field.length).append(':').append(field) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
