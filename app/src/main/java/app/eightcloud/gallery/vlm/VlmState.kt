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

    /** User pressed Pause. */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused
    /** Why the pass is waiting on its own (heat, battery, charger), or blank. */
    private val _autoPause = MutableStateFlow("")
    val autoPause: StateFlow<String> = _autoPause
    private val _progress = MutableStateFlow(Pair(0, 0)) // done, total in this run
    val progress: StateFlow<Pair<Int, Int>> = _progress

    fun paused(p: Boolean) { _paused.value = p }
    fun autoPause(s: String) { _autoPause.value = s }
    fun progress(done: Int, total: Int) { _progress.value = Pair(done, total) }
    /** True while the normal label/CLIP scan runs; the full AI pass waits for it (CPU and heat). */
    @Volatile var scanActive = false

    fun status(s: String) { _status.value = s }
    fun running(r: Boolean) { _running.value = r }
    fun bump() { _version.value = _version.value + 1 }
}
