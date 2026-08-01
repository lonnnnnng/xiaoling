package com.longdev.xiaoling.device

import android.content.Context
import com.longdev.xiaoling.storage.UiPreferenceStore

object DeviceObservationComponents {
    private val referenceStore = DeviceNodeReferenceStore()

    @Volatile
    private var controller: DeviceObservationController? = null

    fun controller(context: Context): DeviceObservationController {
        controller?.let { return it }
        return synchronized(this) {
            controller ?: DeviceObservationController(
                agentEnabled = { UiPreferenceStore(context.applicationContext).loadDeviceAgentEnabled() },
                gateway = AndroidDeviceAccessibilityGateway(context.applicationContext),
                referenceStore = referenceStore,
            ).also { controller = it }
        }
    }

    fun clearReferences() {
        controller?.clearReferences() ?: referenceStore.clear()
    }
}
