/*-
 * #%L
 * A Maven project implementing an ImageJ command.
 * %%
 * Copyright (C) 2017 - 2024 My Company, Inc.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
/*
 * To the extent possible under law, the ImageJ developers have waived
 * all copyright and related or neighboring rights to this tutorial code.
 *
 * See the CC0 1.0 Universal license for details:
 *     http://creativecommons.org/publicdomain/zero/1.0/
 */
@file:JvmMultifileClass

package com.mycompany.arkitekt


import net.imagej.DatasetService
import net.imagej.ImageJ
import net.imagej.display.ImageDisplayService
import net.imagej.ops.OpService
import net.imglib2.type.numeric.RealType
import org.scijava.Context
import org.scijava.command.Command
import org.scijava.plugin.Parameter
import org.scijava.plugin.Plugin
import org.scijava.ui.UIService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder


fun main() {
    // Patch the IJ1 legacy layer (inject ij.IJ's `_hooks` field) BEFORE the ImageJ2 context builds
    // LegacyService. Standalone (`./gradlew run`) nothing does this the way Fiji's launcher does, so
    // without it LegacyService.<clinit> dies with "No _hooks field found in ij.IJ". Must run before
    // any IJ1 class loads. Requires `--add-opens java.base/java.lang=ALL-UNNAMED` (set in the run
    // task) so the patcher can reflect into ClassLoader on JDK 9+.
    // Reflective so the class isn't required when imagej-legacy is absent (it is compileOnly for the
    // Fiji build, which never calls main()).
    try {
        Class.forName("net.imagej.patcher.LegacyInjector").getMethod("preinit").invoke(null)
    } catch (e: ClassNotFoundException) {
        // imagej-legacy not on the classpath — boot anyway; the macro action just won't work.
    }
    // create the ImageJ application context with all available services
    val ij = ImageJ()
    // Launch the UI. The Arkitekt toolbar button (ArkitektTool) is the primary entry point and
    // appears automatically; we intentionally do NOT open the login dialog here. The menu item
    // (Plugins > Arkitekt) remains available as a fallback.
    ij.launch()
}

class Dialog : JDialog {

    constructor(ctx: Context, arkitekt: Arkitekt) : super() {
        ctx.inject(this)

        title = "Arkitekt"
        isResizable = false
        defaultCloseOperation = DISPOSE_ON_CLOSE

        val root = JPanel(BorderLayout())
        root.background = BG
        root.border = EmptyBorder(24, 28, 22, 28)
        contentPane = root

        // --- Header: title + subtitle ---
        val header = JPanel()
        header.layout = BoxLayout(header, BoxLayout.Y_AXIS)
        header.isOpaque = false

        val title = JLabel("Arkitekt")
        title.font = title.font.deriveFont(Font.BOLD, 22f)
        title.foreground = ACCENT
        title.alignmentX = Component.LEFT_ALIGNMENT

        val subtitle = JLabel("Connect this ImageJ instance to the Arkitekt platform")
        subtitle.font = subtitle.font.deriveFont(12f)
        subtitle.foreground = MUTED
        subtitle.alignmentX = Component.LEFT_ALIGNMENT

        header.add(title)
        header.add(Box.createVerticalStrut(4))
        header.add(subtitle)
        root.add(header, BorderLayout.NORTH)

        // --- Form: server field + status ---
        val form = JPanel(GridBagLayout())
        form.isOpaque = false
        form.border = EmptyBorder(20, 0, 16, 0)
        val c = GridBagConstraints()
        c.insets = Insets(0, 0, 0, 0)

        val serverLabel = JLabel("Server")
        serverLabel.font = serverLabel.font.deriveFont(Font.BOLD, 12f)
        serverLabel.foreground = MUTED
        c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST
        c.weightx = 1.0; c.fill = GridBagConstraints.HORIZONTAL
        form.add(serverLabel, c)

        // Advanced override: the coordination server. Defaults to go.arkitekt.live; power users
        // can edit it to point at another deployment (e.g. a local dev server).
        val serverInput = JTextField(DEFAULT_SERVER)
        serverInput.font = serverInput.font.deriveFont(13f)
        serverInput.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER, 1, true),
            EmptyBorder(8, 10, 8, 10)
        )
        c.gridy = 1; c.insets = Insets(6, 0, 0, 0)
        form.add(serverInput, c)

        val status = JLabel("Not connected")
        status.font = status.font.deriveFont(12f)
        status.foreground = MUTED
        c.gridy = 2; c.insets = Insets(14, 0, 0, 0)
        form.add(status, c)

        root.add(form, BorderLayout.CENTER)

        // --- Buttons ---
        val buttons = JPanel(BorderLayout())
        buttons.isOpaque = false

        val logoutButton = styledButton("Log out", LOGOUT_BG, LOGOUT_FG)
        val loginButton = styledButton("Log in", ACCENT, Color.WHITE)
        val buttonRow = JPanel()
        buttonRow.isOpaque = false
        buttonRow.layout = BoxLayout(buttonRow, BoxLayout.X_AXIS)
        buttonRow.add(logoutButton)
        buttonRow.add(Box.createHorizontalStrut(10))
        buttonRow.add(loginButton)
        buttons.add(buttonRow, BorderLayout.EAST)
        root.add(buttons, BorderLayout.SOUTH)

        // --- State transitions ---
        // Tracks whether the previous login attempt failed, so the login button offers a
        // "Re-Login" that first discards the (possibly stale) cached configuration.
        var loginFailed = false

        fun showLoggedOut() {
            loginFailed = false
            serverInput.isEnabled = true
            loginButton.isEnabled = true
            loginButton.text = "Log in"
            logoutButton.isEnabled = false
            status.text = "Not connected"
            status.foreground = MUTED
        }

        fun showConnecting() {
            serverInput.isEnabled = false
            loginButton.isEnabled = false
            logoutButton.isEnabled = false
            status.text = "Connecting…"
            status.foreground = WARN
        }

        fun showConnected(username: String) {
            loginFailed = false
            serverInput.isEnabled = false
            loginButton.isEnabled = false
            loginButton.text = "Log in"
            logoutButton.isEnabled = true
            status.text = "Connected as $username"
            status.foreground = SUCCESS
        }

        fun showError(message: String) {
            loginFailed = true
            serverInput.isEnabled = true
            loginButton.isEnabled = true
            loginButton.text = "Re-Login"
            logoutButton.isEnabled = false
            status.text = "<html>Login failed: ${message.take(120)}</html>"
            status.foreground = ERROR
        }

        // Drive the labels off the shared connection state so the dialog and the toolbar
        // button always agree, and so changes made elsewhere (e.g. the tool's auto-login on
        // launch) are reflected when the dialog opens. addListener replays the current state.
        val stateListener: (ConnState) -> Unit = { state ->
            SwingUtilities.invokeLater {
                when (state) {
                    is ConnState.Connected -> showConnected(state.username)
                    is ConnState.Connecting -> showConnecting()
                    is ConnState.Error -> showError(state.message)
                    ConnState.Disconnected -> showLoggedOut()
                }
            }
        }
        ArkitektState.addListener(stateListener)
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent?) {
                ArkitektState.removeListener(stateListener)
            }
        })

        loginButton.addActionListener {
            val serverUrl = serverInput.text.trim().ifEmpty { DEFAULT_SERVER }
            // After a failure, a Re-Login clears the cached (stale) configuration so the
            // next attempt re-negotiates from scratch. The stateListener updates the labels.
            if (loginFailed) {
                arkitekt.logout()
            }
            arkitekt.login(serverUrl, {}, {})
        }

        logoutButton.addActionListener {
            arkitekt.logout()
        }

        pack()
        minimumSize = Dimension(420, size.height)
        setLocationRelativeTo(null)
    }

    private fun styledButton(text: String, bg: Color, fg: Color): JButton {
        val button = JButton(text)
        button.foreground = fg
        button.background = bg
        button.font = button.font.deriveFont(Font.BOLD, 13f)
        button.isFocusPainted = false
        button.isOpaque = true
        button.isContentAreaFilled = true
        button.border = EmptyBorder(9, 18, 9, 18)
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        return button
    }

    companion object {
        const val DEFAULT_SERVER = "https://go.arkitekt.live"

        private val BG = Color(0xF7, 0xF8, 0xFA)
        private val ACCENT = Color(0x4F, 0x46, 0xE5)
        private val MUTED = Color(0x6B, 0x72, 0x80)
        private val BORDER = Color(0xD1, 0xD5, 0xDB)
        private val SUCCESS = Color(0x16, 0xA3, 0x4A)
        private val WARN = Color(0xD9, 0x77, 0x06)
        private val ERROR = Color(0xDC, 0x26, 0x26)
        private val LOGOUT_BG = Color(0xE5, 0xE7, 0xEB)
        private val LOGOUT_FG = Color(0x37, 0x41, 0x51)
    }
}

/**
 * This example illustrates how to create an ImageJ [Command] plugin.
 *
 * The code here is a simple Gaussian blur using ImageJ Ops.
 *
 * You should replace the parameter fields with your own inputs and outputs, and replace the [run]
 * method implementation with your own logic.
 */
@Plugin(type = Command::class, menuPath = "Plugins > Arkitekt")
open class ArkitektCommand<T : RealType<T>> : Command {
    //
    // Feel free to add more parameters here...
    //
    @Parameter
    private var datasetService: DatasetService? = null


    @Parameter
    private var imageDisplayService: ImageDisplayService? = null


    @Parameter
    private var ctx: Context? = null

    @Parameter private var uiService: UIService? = null

    @Parameter private var opService: OpService? = null

    override fun run() {
        ctx?.let { context ->
            if (uiService == null || datasetService == null || imageDisplayService == null ){
                return@let
            }





            // Share the single orchestrator with the toolbar tool (ArkitektTool) via ArkitektState.
            val arkitekt = ArkitektState.getOrCreate(uiService!!, datasetService!!, imageDisplayService!!)
            var dialog = Dialog(context, arkitekt)
            dialog.isVisible = true
        }
    }
}
