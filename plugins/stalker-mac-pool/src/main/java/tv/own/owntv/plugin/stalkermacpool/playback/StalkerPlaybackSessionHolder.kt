package tv.own.owntv.plugin.stalkermacpool.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.own.owntv.plugin.stalkermacpool.model.ActivePlaybackContext

/**
 * Tracks the currently playing source/item published by the app shell plus per-item
 * automatic failover attempt streak so conflict auto-switching cannot loop infinitely.
 */
class StalkerPlaybackSessionHolder {

    private val _active = MutableStateFlow<ActivePlaybackContext?>(null)
    val active: StateFlow<ActivePlaybackContext?> = _active.asStateFlow()

    @Volatile
    private var lastContextKey: String? = null

    @Volatile
    private var autoSwitchStreak: Int = 0

    fun setActive(context: ActivePlaybackContext?) {
        _active.value = context
        val nextKey = context?.let { "${it.sourceId}:${it.channelId ?: -1L}:${it.mediaKind.name}" }
        if (nextKey != lastContextKey) {
            lastContextKey = nextKey
            autoSwitchStreak = 0
        }
    }

    fun clear() {
        _active.value = null
        lastContextKey = null
        autoSwitchStreak = 0
    }

    fun currentAutoSwitchStreak(): Int = autoSwitchStreak

    fun incrementAutoSwitchStreak(): Int {
        autoSwitchStreak += 1
        return autoSwitchStreak
    }

    fun resetAutoSwitchStreak() {
        autoSwitchStreak = 0
    }
}
