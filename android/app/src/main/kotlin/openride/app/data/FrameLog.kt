package openride.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import openride.core.session.FrameEvent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FrameLog @Inject constructor() {
    private val _events = MutableStateFlow<List<FrameEvent>>(emptyList())
    val events: StateFlow<List<FrameEvent>> = _events

    fun add(e: FrameEvent) = _events.update { (it + e).takeLast(500) }
    fun clear() = _events.update { emptyList() }
}
