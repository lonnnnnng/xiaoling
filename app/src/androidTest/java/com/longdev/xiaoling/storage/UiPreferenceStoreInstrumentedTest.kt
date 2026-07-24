package com.longdev.xiaoling.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceRolloutPreference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiPreferenceStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var isolatedContext: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        isolatedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("$TEST_PREFIX$name", mode)
        }
        clearPreferences()
    }

    @After
    fun tearDown() {
        clearPreferences()
    }

    @Test
    fun reasoningSummaryIsOptInAndRestoredAcrossStoreInstances() {
        assertFalse(UiPreferenceStore(isolatedContext).loadReasoningSummaryEnabled())

        UiPreferenceStore(isolatedContext).saveReasoningSummaryEnabled(true)

        assertTrue(UiPreferenceStore(isolatedContext).loadReasoningSummaryEnabled())
    }

    @Test
    fun deviceAgentIsOptInAndRestoredAcrossStoreInstances() {
        assertFalse(UiPreferenceStore(isolatedContext).loadDeviceAgentEnabled())

        UiPreferenceStore(isolatedContext).saveDeviceAgentEnabled(true)

        assertTrue(UiPreferenceStore(isolatedContext).loadDeviceAgentEnabled())
    }

    @Test
    fun knowledgeRelevanceRolloutIsOffByDefaultAndRollbackClearsIdentity() {
        val enabled = KnowledgeRelevanceRolloutPreference(
            enforcementEnabled = true,
            gateVersion = "stage85-raw-top1-qwen-v1",
            providerId = "provider-a",
            model = "embedding-a",
        )
        val store = UiPreferenceStore(isolatedContext)

        assertEquals(KnowledgeRelevanceRolloutPreference(), store.loadKnowledgeRelevanceRolloutPreference())
        store.saveKnowledgeRelevanceRolloutPreference(enabled)
        assertEquals(enabled, UiPreferenceStore(isolatedContext).loadKnowledgeRelevanceRolloutPreference())

        store.rollbackKnowledgeRelevanceRollout()

        assertEquals(
            KnowledgeRelevanceRolloutPreference(),
            UiPreferenceStore(isolatedContext).loadKnowledgeRelevanceRolloutPreference(),
        )
    }

    private fun clearPreferences() {
        context.getSharedPreferences("${TEST_PREFIX}xiaoling_ui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    companion object {
        private const val TEST_PREFIX = "ui_preference_test_"
    }
}
