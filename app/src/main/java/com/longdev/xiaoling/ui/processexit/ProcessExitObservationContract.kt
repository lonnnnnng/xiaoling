package com.longdev.xiaoling.ui.processexit

import com.longdev.xiaoling.system.ProcessExitObservation

interface ProcessExitObservationActions {
    fun refreshProcessExitObservations()
}

internal data class ProcessExitObservationUiState(
    val observations: List<ProcessExitObservation> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)
