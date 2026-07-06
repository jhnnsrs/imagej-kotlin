package com.mycompany.arkitekt

import net.imagej.DatasetService
import net.imagej.display.ImageDisplayService
import org.scijava.Priority
import org.scijava.app.StatusService
import org.scijava.log.LogService
import org.scijava.plugin.Parameter
import org.scijava.plugin.Plugin
import org.scijava.tool.AbstractTool
import org.scijava.tool.IconService
import org.scijava.tool.Tool
import org.scijava.ui.UIService
import javax.swing.AbstractButton
import javax.swing.ImageIcon
import javax.swing.SwingUtilities

/**
 * A SciJava toolbar button that shows the Arkitekt logo and reflects the current connection
 * state: a grayed logo when disconnected, the full-colour logo when connected. Login happens
 * automatically on launch if a config is already cached; otherwise clicking the button starts
 * an interactive login. The menu item (`Plugins > Arkitekt`) remains as a fallback.
 *
 * Note: SciJava has no public API to enable/disable a specific tool button, so the icon swap is
 * best-effort via reflection into [IconService]'s button map — any failure is logged and ignored
 * (the menu item still works). We deliberately do NOT toggle the button's `enabled` flag: a
 * disabled Swing button swallows clicks, which would make the click-to-login path dead exactly
 * when it is needed.
 */
@Plugin(
        type = Tool::class,
        name = "Arkitekt",
        description = "Arkitekt connection status — click to log in",
        iconPath = "/icons/arkitekt-gray.png",
        priority = Priority.HIGH
)
class ArkitektTool : AbstractTool() {

    @Parameter(required = false)
    private var uiService: UIService? = null

    @Parameter(required = false)
    private var datasetService: DatasetService? = null

    @Parameter(required = false)
    private var imageDisplayService: ImageDisplayService? = null

    @Parameter(required = false)
    private var statusService: StatusService? = null

    @Parameter(required = false)
    private var log: LogService? = null

    private val iconColor: ImageIcon? by lazy { loadIcon("/icons/arkitekt.png") }
    private val iconGray: ImageIcon? by lazy { loadIcon("/icons/arkitekt-gray.png") }

    private val stateListener: (ConnState) -> Unit = { state -> onState(state) }

    // The toolbar may auto-select a tool at startup, which fires activate(). We must NOT treat
    // that as a user click (it would start a login and force the device-code browser flow). Arm
    // click-to-login only after startup settles, via an EDT task queued from the constructor.
    //
    // IMPORTANT: SciJava's ToolService does NOT call Initializable.initialize() for tools — only
    // @Parameter injection runs. So the arming/setup MUST live in this init{} block (which always
    // runs at construction), not in an initialize() override (which never fires). Anything needing
    // an injected service is deferred into the queued EDT task, because @Parameter fields are
    // populated by context.inject() AFTER the constructor returns.
    @Volatile
    private var armed = false

    init {
        // Registration needs no injected services, so it is safe here in the constructor.
        // NOTE: do NOT write to System.out / the LogService or start a login here (or in the
        // queued task below). This runs while ImageJ is still building its Swing console
        // (SwingConsolePane), which captures stdout + the LogService and DEAD-LOOPS if anything
        // writes to them mid-init — leaving `component` null and throwing
        // "contentPane cannot be set to null", which aborts the whole UI (no window appears).
        // So startup does field flips only; all logging/login is deferred to a real click.
        ArkitektState.addListener(stateListener)

        SwingUtilities.invokeLater {
            // Runs after the toolbar finishes building and any startup default-tool activation
            // has passed, so a later genuine click is distinguishable from that. No I/O here.
            armed = true
        }
    }

    /**
     * Button click: start login when disconnected, or cancel the in-flight login when a
     * device-code challenge is still pending. No-op when already connected (status only).
     */
    override fun activate() {
        report("ArkitektTool.activate() — armed=$armed state=${ArkitektState.state}")

        // Ignore the startup default-tool activation — only genuine clicks should act.
        if (!armed) {
            report("ArkitektTool.activate() ignored (not armed yet — startup activation)")
            return
        }

        val orchestrator = ensureOrchestrator()
        if (orchestrator == null) {
            // This is the classic silent-no-op: report it loudly so it is diagnosable.
            reportError(
                    "Cannot log in: Arkitekt services are unavailable " +
                            "(ui=$uiService ds=$datasetService ids=$imageDisplayService)."
            )
            return
        }

        when (val state = ArkitektState.state) {
            is ConnState.Connecting -> {
                report("ArkitektTool: cancelling in-flight login")
                orchestrator.cancelLogin()
            }
            is ConnState.Connected -> {
                report("ArkitektTool: already connected as ${state.username} — nothing to do")
                statusService?.showStatus("Arkitekt: connected as ${state.username}")
            }
            else -> {
                report("ArkitektTool: starting interactive login to ${Dialog.DEFAULT_SERVER}")
                startLogin(orchestrator)
            }
        }
    }

    /** Kick off a login, wiring the error callback so failures surface (dialog + status + log). */
    private fun startLogin(orchestrator: Arkitekt) {
        statusService?.showStatus("Arkitekt: connecting to ${Dialog.DEFAULT_SERVER}…")
        orchestrator.login(
                Dialog.DEFAULT_SERVER,
                onSuccess = { report("ArkitektTool: login succeeded") },
                onError = { e -> reportError("Login failed: ${e.message ?: e.toString()}") }
        )
    }

    /**
     * Get the shared orchestrator, creating it from this tool's injected services if it does not
     * exist yet (i.e. the menu dialog was never opened). Returns null only if the required
     * services were not injected.
     */
    private fun ensureOrchestrator(): Arkitekt? {
        ArkitektState.arkitektOrNull?.let { return it }
        val ui = uiService ?: return null
        val ds = datasetService ?: return null
        val ids = imageDisplayService ?: return null
        return ArkitektState.getOrCreate(ui, ds, ids)
    }

    private fun onState(state: ConnState) {
        val connected = state is ConnState.Connected
        SwingUtilities.invokeLater {
            val button = findButton()
            if (button != null) {
                (if (connected) iconColor else iconGray)?.let { button.icon = it }
                // Keep the button enabled at all times — see class note. The gray icon is the
                // "not connected" cue; disabling would make clicks (and thus login) impossible.
                button.isEnabled = true
                button.toolTipText = when (state) {
                    is ConnState.Connected -> "Arkitekt: connected as ${state.username}"
                    is ConnState.Connecting -> "Arkitekt: connecting…"
                    is ConnState.Error -> "Arkitekt: login failed — ${state.message.take(120)}"
                    ConnState.Disconnected -> "Arkitekt: not connected — click to log in"
                }
            }

            // Surface every transition in the status bar, and pop a dialog on failure so a
            // toolbar-only user (no dialog open) still sees why login did not happen.
            when (state) {
                is ConnState.Connected -> statusService?.showStatus("Arkitekt: connected as ${state.username}")
                is ConnState.Connecting -> statusService?.showStatus("Arkitekt: connecting…")
                is ConnState.Error -> reportError("Login failed: ${state.message}")
                ConnState.Disconnected -> statusService?.clearStatus()
            }
        }
    }

    /**
     * Look up the actual toolbar button for this tool by reflecting into the Swing
     * [IconService]'s private `buttonMap`. Returns null (best-effort) if the UI/service isn't a
     * Swing toolbar or the internals have changed.
     */
    private fun findButton(): AbstractButton? {
        return try {
            val ctx = context ?: return null
            val iconService = ctx.getService(IconService::class.java) ?: return null
            val field = iconService.javaClass.getDeclaredField("buttonMap")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field.get(iconService) as? Map<Tool, AbstractButton> ?: return null
            map[this]
        } catch (e: Exception) {
            log?.debug("Arkitekt: could not resolve toolbar button: ${e.message}")
            null
        }
    }

    private fun loadIcon(path: String): ImageIcon? {
        val url = javaClass.getResource(path) ?: return null
        return ImageIcon(url)
    }

    /** Informational trace: goes to the SciJava log and stdout (console), never blocks. */
    private fun report(message: String) {
        log?.info(message)
        println(message)
    }

    /** Error trace: log + stdout + status bar + a modal dialog so it can't be missed. */
    private fun reportError(message: String) {
        log?.error(message)
        println("Arkitekt ERROR: $message")
        SwingUtilities.invokeLater {
            statusService?.showStatus(message)
            try {
                uiService?.showDialog(message, "Arkitekt")
            } catch (e: Exception) {
                log?.debug("Arkitekt: could not show error dialog: ${e.message}")
            }
        }
    }
}
