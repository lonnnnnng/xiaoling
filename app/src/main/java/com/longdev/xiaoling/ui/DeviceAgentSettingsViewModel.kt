package com.longdev.xiaoling.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.longdev.xiaoling.device.DeviceAgentHealthState
import com.longdev.xiaoling.device.DeviceObservationComponents
import com.longdev.xiaoling.device.DeviceObservationController
import com.longdev.xiaoling.device.DeviceSnapshot
import com.longdev.xiaoling.device.DeviceSnapshotCapture
import com.longdev.xiaoling.storage.UiPreferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class DeviceAgentSettingsUiState(
    val enabled: Boolean = false,
    val health: DeviceAgentHealthState = DeviceAgentHealthState.AGENT_DISABLED,
    val refreshing: Boolean = false,
    val capturing: Boolean = false,
    val snapshot: DeviceSnapshot? = null,
    val error: String? = null,
)

class DeviceAgentSettingsViewModel internal constructor(
    application: Application,
    private val preferences: UiPreferenceStore,
    private val controller: DeviceObservationController,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        preferences = UiPreferenceStore(application),
        controller = DeviceObservationComponents.controller(application),
    )

    var uiState by mutableStateOf(DeviceAgentSettingsUiState())
        private set

    init {
        refresh()
    }

    fun refresh() {
        uiState = uiState.copy(
            enabled = preferences.loadDeviceAgentEnabled(),
            health = controller.health(),
            refreshing = false,
        )
    }

    fun setEnabled(enabled: Boolean) {
        preferences.saveDeviceAgentEnabled(enabled)
        if (!enabled) {
            // long: 用户关闭独立开关后，尚未过期的节点引用也必须立即退出，不能等 TTL 自然到期后才停止可操作性。
            controller.clearReferences()
        }
        uiState = uiState.copy(enabled = enabled, snapshot = null, error = null)
        refresh()
    }

    fun captureSnapshot() {
        if (uiState.capturing) return
        uiState = uiState.copy(capturing = true, error = null, snapshot = null)
        viewModelScope.launch {
            try {
                when (val result = controller.capture()) {
                    is DeviceSnapshotCapture.Success -> {
                        uiState = uiState.copy(
                            capturing = false,
                            health = controller.health(),
                            snapshot = result.snapshot,
                            error = null,
                        )
                    }
                    is DeviceSnapshotCapture.Failed -> {
                        uiState = uiState.copy(
                            capturing = false,
                            health = controller.health(),
                            snapshot = null,
                            error = result.message,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                uiState = uiState.copy(
                    capturing = false,
                    health = controller.health(),
                    snapshot = null,
                    error = error.message ?: "读取当前界面失败",
                )
            }
        }
    }
}
