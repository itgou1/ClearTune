package com.cleartune.app.player

import com.cleartune.core.model.InternetRadioStation

data class RadioUiState(
    val stations: List<InternetRadioStation> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)
