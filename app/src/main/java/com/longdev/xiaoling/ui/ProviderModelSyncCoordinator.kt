package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.ApiFailure
import com.longdev.xiaoling.network.FailureKind
import com.longdev.xiaoling.network.ProviderApiUrlBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface ProviderModelSyncCommitOutcome {
    data class Committed(
        val profile: ProviderProfile,
    ) : ProviderModelSyncCommitOutcome

    data object Missing : ProviderModelSyncCommitOutcome

    data object Stale : ProviderModelSyncCommitOutcome
}

internal sealed interface ProviderModelSyncOutcome {
    data class Invalid(
        val profileId: String,
        val message: String,
    ) : ProviderModelSyncOutcome

    data class Failed(
        val profileId: String,
        val title: String,
        val message: String,
    ) : ProviderModelSyncOutcome

    data class Missing(
        val profileId: String,
        val message: String,
    ) : ProviderModelSyncOutcome

    data class Stale(
        val profileId: String,
        val message: String,
    ) : ProviderModelSyncOutcome

    data class Succeeded(
        val profile: ProviderProfile,
        val modelCount: Int,
    ) : ProviderModelSyncOutcome
}

internal class ProviderModelSyncCoordinator(
    private val fetchModels: suspend (ProviderRequestConfig) -> List<String>,
    private val commitProfile: suspend (ProviderProfile, ProviderProfile) -> ProviderModelSyncCommitOutcome,
    private val nowSyncTimeText: () -> String,
) {
    private val commitMutex = Mutex()

    suspend fun syncAll(
        profiles: List<ProviderProfile>,
        userAgent: String,
        onProfileStarted: (ProviderProfile) -> Unit = {},
        onOutcome: (ProviderModelSyncOutcome) -> Unit,
    ): List<ProviderModelSyncOutcome> = buildList {
        // long: 批量同步按用户看到的 Provider 顺序逐项收敛；普通失败保留在结果列表中继续下一项，取消则由单项同步直接中止整批。
        profiles.forEach { profile ->
            onProfileStarted(profile)
            val outcome = sync(profile, userAgent)
            add(outcome)
            onOutcome(outcome)
        }
    }

    suspend fun sync(
        profile: ProviderProfile,
        userAgent: String,
    ): ProviderModelSyncOutcome {
        ProviderApiUrlBuilder.validate(profile.baseUrl)?.let { message ->
            return ProviderModelSyncOutcome.Invalid(profile.id, message)
        }
        return try {
            val config = ProviderRequestConfig(
                baseUrl = profile.baseUrl.trim(),
                apiKey = profile.apiKey.trim(),
                model = "",
                userAgent = userAgent,
            )
            val models = fetchModels(config).distinct()
            val selectedModel = profile.model
                .takeIf { it in models }
                ?: models.firstOrNull()
                ?: ""
            val candidate = profile.copy(
                model = selectedModel,
                availableModels = models,
                enabledModels = models,
                lastSyncedAt = nowSyncTimeText(),
            )
            // long: 网络请求可以并行，但完整 Provider 快照必须串行提交，避免两个迟到结果互相覆盖另一方刚写入的配置。
            when (val commitOutcome = commitMutex.withLock { commitProfile(profile, candidate) }) {
                is ProviderModelSyncCommitOutcome.Committed -> {
                    // long: 对外只发布已经完成持久化的 Provider 快照，避免 UI 把网络成功误报为模型配置已经可供 Agent 使用。
                    ProviderModelSyncOutcome.Succeeded(
                        profile = commitOutcome.profile,
                        modelCount = models.size,
                    )
                }

                ProviderModelSyncCommitOutcome.Missing -> ProviderModelSyncOutcome.Missing(
                    profileId = profile.id,
                    message = "Provider 已删除，无法保存同步结果",
                )

                ProviderModelSyncCommitOutcome.Stale -> ProviderModelSyncOutcome.Stale(
                    profileId = profile.id,
                    message = "Provider 配置已变化，请重新同步",
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val failure = error as? ApiFailure
            ProviderModelSyncOutcome.Failed(
                profileId = profile.id,
                title = failure?.kind?.title ?: FailureKind.UNKNOWN.title,
                message = error.message ?: "未知错误",
            )
        }
    }
}
