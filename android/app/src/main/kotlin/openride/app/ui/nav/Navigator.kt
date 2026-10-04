package openride.app.ui.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

sealed interface Screen {
    data object Scan : Screen
    data object Device : Screen
    data object Dashboard : Screen
    data object Battery : Screen
    data object Ride : Screen
    data object ScooterInfo : Screen
    data object Debug : Screen
    data object Settings : Screen
}

/** Back stack as state (the shape Navigation 3 uses), without the extra dependency. */
@Singleton
class Navigator @Inject constructor() {
    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Scan))
    val stack: StateFlow<List<Screen>> = _stack

    val top: Screen get() = _stack.value.last()
    fun push(s: Screen) = _stack.update { if (it.last() == s) it else it + s }
    fun pop(): Boolean {
        if (_stack.value.size <= 1) return false
        _stack.update { it.dropLast(1) }
        return true
    }
}
