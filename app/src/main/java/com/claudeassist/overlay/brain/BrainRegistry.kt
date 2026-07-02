package com.claudeassist.overlay.brain

import com.claudeassist.overlay.data.SecureSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Holds all connectors and tracks which one is active (persisted in settings). */
class BrainRegistry(
    private val brains: List<Brain>,
    private val settings: SecureSettings,
) {
    private val _active = MutableStateFlow(resolveActive())
    val active: StateFlow<Brain> = _active

    fun all(): List<Brain> = brains

    fun byId(id: String): Brain? = brains.firstOrNull { it.id == id }

    fun setActive(id: String) {
        val brain = byId(id) ?: return
        settings.activeBrainId = id
        _active.value = brain
    }

    private fun resolveActive(): Brain =
        byId(settings.activeBrainId) ?: brains.first()
}
