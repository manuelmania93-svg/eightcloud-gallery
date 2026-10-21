package app.eightcloud.gallery.vlm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Live status shared between the service and the UI. */
object VlmState {
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    /** Increments whenever new results were written; the UI reloads its map. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun status(s: String) { _status.value = s }
    fun running(r: Boolean) { _running.value = r }
    fun bump() { _version.value = _version.value + 1 }
}
