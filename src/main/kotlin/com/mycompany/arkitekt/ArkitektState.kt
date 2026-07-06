package com.mycompany.arkitekt

import net.imagej.DatasetService
import net.imagej.display.ImageDisplayService
import org.scijava.ui.UIService
import java.util.concurrent.CopyOnWriteArrayList

/** Connection status shared between the toolbar tool and the login dialog. */
sealed class ConnState {
    object Disconnected : ConnState()
    object Connecting : ConnState()
    data class Connected(val username: String) : ConnState()
    data class Error(val message: String) : ConnState()
}

/**
 * Process-wide holder for the single [Arkitekt] orchestrator and the current connection
 * state. Both the SciJava toolbar tool ([ArkitektTool]) and the menu dialog ([Dialog]) talk
 * to the same instance, so the toolbar icon and the dialog labels never disagree and only one
 * agent provide-loop / WebSocket is ever running.
 *
 * Listeners are notified on whatever thread drives the transition (login runs on a background
 * dispatcher); each listener is responsible for marshalling its own Swing work onto the EDT.
 */
object ArkitektState {
    @Volatile
    private var instance: Arkitekt? = null

    @Volatile
    var state: ConnState = ConnState.Disconnected
        private set

    private val listeners = CopyOnWriteArrayList<(ConnState) -> Unit>()

    /** Get the shared orchestrator, creating it on first use from the injected services. */
    @Synchronized
    fun getOrCreate(
            uiService: UIService,
            datasetService: DatasetService,
            imageDisplayService: ImageDisplayService
    ): Arkitekt =
            instance
                    ?: Arkitekt(uiService, datasetService, imageDisplayService).also { instance = it }

    /** The shared orchestrator if it has already been created, else null. */
    val arkitektOrNull: Arkitekt?
        get() = instance

    /** Register a listener; the current state is replayed immediately so it can paint. */
    fun addListener(listener: (ConnState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (ConnState) -> Unit) {
        listeners.remove(listener)
    }

    /** Called by [Arkitekt] as login/logout progress; notifies all listeners. */
    fun setState(newState: ConnState) {
        state = newState
        for (listener in listeners) {
            try {
                listener(newState)
            } catch (e: Exception) {
                println("Arkitekt state listener failed: ${e.message}")
            }
        }
    }
}
