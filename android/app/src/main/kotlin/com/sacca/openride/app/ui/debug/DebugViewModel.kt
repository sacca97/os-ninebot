package com.sacca.openride.app.ui.debug

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import com.sacca.openride.app.data.FrameLog
import com.sacca.openride.core.session.FrameEvent
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(private val log: FrameLog) : ViewModel() {
    val events: StateFlow<List<FrameEvent>> = log.events
    fun clear() = log.clear()
}
