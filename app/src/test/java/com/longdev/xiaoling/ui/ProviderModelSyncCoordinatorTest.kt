package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.ApiFailure
import com.longdev.xiaoling.network.FailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProviderModelSyncCoordinatorTest {
    @Test
    fun successfulSyncNormalizesRequestAndCommitsTheMergedProfile() = runTest {
        val baseline = profile(
            model = "model-b",
            availableModels = listOf("old-model"),
            enabledModels = listOf("old-model"),
        )
        var fetchedConfig: ProviderRequestConfig? = null
        var committedBaseline: ProviderProfile? = null
        var committedProfile: ProviderProfile? = null
        val coordinator = ProviderModelSyncCoordinator(
            fetchModels = { config ->
                fetchedConfig = config
                listOf("model-a", "model-b", "model-a")
            },
            commitProfile = { original, synced ->
                committedBaseline = original
                committedProfile = synced
                ProviderModelSyncCommitOutcome.Committed(synced)
            },
            nowSyncTimeText = { "2026-07-26 15:00:00" },
        )

        val outcome = coordinator.sync(
            profile = baseline,
            userAgent = "xiaoling-test-agent",
        )

        assertEquals(
            ProviderRequestConfig(
                baseUrl = "https://example.com/v1",
                apiKey = "secret-key",
                model = "",
                userAgent = "xiaoling-test-agent",
            ),
            fetchedConfig,
        )
        val expected = baseline.copy(
            model = "model-b",
            availableModels = listOf("model-a", "model-b"),
            enabledModels = listOf("model-a", "model-b"),
            lastSyncedAt = "2026-07-26 15:00:00",
        )
        assertEquals(baseline, committedBaseline)
        assertEquals(expected, committedProfile)
        assertEquals(
            ProviderModelSyncOutcome.Succeeded(
                profile = expected,
                modelCount = 2,
            ),
            outcome,
        )
    }

    @Test
    fun invalidBaseUrlFailsBeforeFetchingOrCommitting() = runTest {
        var fetchCount = 0
        var commitCount = 0
        val coordinator = ProviderModelSyncCoordinator(
            fetchModels = {
                fetchCount += 1
                emptyList()
            },
            commitProfile = { _, synced ->
                commitCount += 1
                ProviderModelSyncCommitOutcome.Committed(synced)
            },
            nowSyncTimeText = { "2026-07-26 15:00:00" },
        )
        val invalid = profile().copy(baseUrl = "not-a-provider-url")

        val outcome = coordinator.sync(invalid, userAgent = "xiaoling-test-agent")

        assertEquals(0, fetchCount)
        assertEquals(0, commitCount)
        assertEquals(
            ProviderModelSyncOutcome.Invalid(
                profileId = "provider-1",
                message = "Base URL 必须以 http:// 或 https:// 开头",
            ),
            outcome,
        )
    }

    @Test
    fun providerFailureKeepsItsStableKindAndNeverCommits() = runTest {
        var commitCount = 0
        val coordinator = ProviderModelSyncCoordinator(
            fetchModels = { throw ApiFailure(FailureKind.AUTHENTICATION, "HTTP 401 · invalid key", 401) },
            commitProfile = { _, synced ->
                commitCount += 1
                ProviderModelSyncCommitOutcome.Committed(synced)
            },
            nowSyncTimeText = { "2026-07-26 15:00:00" },
        )

        val outcome = coordinator.sync(profile(), userAgent = "xiaoling-test-agent")

        assertEquals(0, commitCount)
        assertEquals(
            ProviderModelSyncOutcome.Failed(
                profileId = "provider-1",
                title = "鉴权失败",
                message = "HTTP 401 · invalid key",
            ),
            outcome,
        )
    }

    @Test
    fun cancellationPropagatesWithoutBecomingASyncFailure() = runTest {
        val cancellation = CancellationException("用户离开 Provider 页面")
        val coordinator = ProviderModelSyncCoordinator(
            fetchModels = { throw cancellation },
            commitProfile = { _, synced -> ProviderModelSyncCommitOutcome.Committed(synced) },
            nowSyncTimeText = { "2026-07-26 15:00:00" },
        )

        val thrown = try {
            coordinator.sync(profile(), userAgent = "xiaoling-test-agent")
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun changedProviderIdentityRejectsTheLateNetworkResult() = runTest {
        val coordinator = ProviderModelSyncCoordinator(
            fetchModels = { listOf("model-a") },
            commitProfile = { _, _ -> ProviderModelSyncCommitOutcome.Stale },
            nowSyncTimeText = { "2026-07-26 15:00:00" },
        )

        val outcome = coordinator.sync(profile(), userAgent = "xiaoling-test-agent")

        assertEquals(
            ProviderModelSyncOutcome.Stale(
                profileId = "provider-1",
                message = "Provider 配置已变化，请重新同步",
            ),
            outcome,
        )
    }

    @Test
    fun deletedProviderRejectsTheLateNetworkResult() = runTest {
        val coordinator = ProviderModelSyncCoordinator(
            fetchModels = { listOf("model-a") },
            commitProfile = { _, _ -> ProviderModelSyncCommitOutcome.Missing },
            nowSyncTimeText = { "2026-07-26 15:00:00" },
        )

        val outcome = coordinator.sync(profile(), userAgent = "xiaoling-test-agent")

        assertEquals(
            ProviderModelSyncOutcome.Missing(
                profileId = "provider-1",
                message = "Provider 已删除，无法保存同步结果",
            ),
            outcome,
        )
    }

    @Test
    fun batchSyncKeepsInputOrderAndContinuesAfterAnOrdinaryFailure() = runTest {
        val fetchedProviderIds = mutableListOf<String>()
        val publishedOutcomes = mutableListOf<ProviderModelSyncOutcome>()
        val coordinator = ProviderModelSyncCoordinator(
            fetchModels = { config ->
                val providerId = config.baseUrl.substringAfter("https://").substringBefore('.')
                fetchedProviderIds += providerId
                if (providerId == "provider-1") {
                    throw ApiFailure(FailureKind.UNKNOWN, "HTTP 503 · unavailable", 503)
                }
                listOf("model-b")
            },
            commitProfile = { _, synced -> ProviderModelSyncCommitOutcome.Committed(synced) },
            nowSyncTimeText = { "2026-07-26 15:00:00" },
        )
        val profiles = listOf(
            profile(id = "provider-1", baseUrl = "  https://provider-1.example.com/v1  ", model = "model-a"),
            profile(id = "provider-2", baseUrl = "  https://provider-2.example.com/v1  ", model = "model-b"),
        )

        val outcomes = coordinator.syncAll(profiles, userAgent = "xiaoling-test-agent") { outcome: ProviderModelSyncOutcome ->
            publishedOutcomes += outcome
        }

        assertEquals(listOf("provider-1", "provider-2"), fetchedProviderIds)
        assertEquals(
            listOf(
                ProviderModelSyncOutcome.Failed(
                    profileId = "provider-1",
                    title = "请求失败",
                    message = "HTTP 503 · unavailable",
                ),
                ProviderModelSyncOutcome.Succeeded(
                    profile = profiles[1].copy(
                        availableModels = listOf("model-b"),
                        enabledModels = listOf("model-b"),
                        lastSyncedAt = "2026-07-26 15:00:00",
                    ),
                    modelCount = 1,
                ),
            ),
            outcomes,
        )
        assertEquals(outcomes, publishedOutcomes)
    }

    @Test
    fun concurrentSyncsSerializeTheirProfileCommits() = runTest {
        val firstCommitEntered = CompletableDeferred<Unit>()
        val releaseFirstCommit = CompletableDeferred<Unit>()
        var activeCommits = 0
        var maxActiveCommits = 0
        val coordinator = ProviderModelSyncCoordinator(
            fetchModels = { listOf("model-a") },
            commitProfile = { _, synced ->
                activeCommits += 1
                maxActiveCommits = maxOf(maxActiveCommits, activeCommits)
                if (synced.id == "provider-1") {
                    firstCommitEntered.complete(Unit)
                    releaseFirstCommit.await()
                }
                activeCommits -= 1
                ProviderModelSyncCommitOutcome.Committed(synced)
            },
            nowSyncTimeText = { "2026-07-26 15:00:00" },
        )

        val first = async { coordinator.sync(profile(id = "provider-1"), "xiaoling-test-agent") }
        firstCommitEntered.await()
        val second = async { coordinator.sync(profile(id = "provider-2"), "xiaoling-test-agent") }
        yield()
        releaseFirstCommit.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, maxActiveCommits)
    }

    private fun profile(
        id: String = "provider-1",
        baseUrl: String = "  https://example.com/v1  ",
        model: String = "model-a",
        availableModels: List<String> = listOf("model-a"),
        enabledModels: List<String> = listOf("model-a"),
    ) = ProviderProfile(
        id = id,
        name = id,
        baseUrl = baseUrl,
        apiKey = "  secret-key  ",
        model = model,
        availableModels = availableModels,
        enabledModels = enabledModels,
    )
}
