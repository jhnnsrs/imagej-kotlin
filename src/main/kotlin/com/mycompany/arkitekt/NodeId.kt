package com.mycompany.arkitekt

import java.io.File
import java.util.UUID

/**
 * Stable per-machine node id — the Kotlin equivalent of arkitekt-next's
 * `arkitekt_next.node_id.get_or_set_node_id()`, which delegates the hard part to the
 * `py-machineid` package. Rather than pull in a JVM dependency we port py-machineid's
 * `id()` directly (it is just a set of OS-level reads), keeping the plugin dependency-free
 * and Java-8 compatible.
 *
 * Resolution order (identical to the Python side):
 *   1. `ARKITEKT_NODE_ID` environment variable, if set.
 *   2. The OS-level machine GUID (see [machineId]) — the same sources py-machineid reads.
 *   3. A UUID persisted to `~/.arkitekt/node_id.txt`, generated once and then reused.
 *
 * On this project the persisted fallback lives next to the fakts cache (`~/.arkitekt/`)
 * for consistency; the Python package uses platformdirs (`~/.config/arkitekt_next/`).
 */
object NodeId {

    private val fallbackFile = File(System.getProperty("user.home"), ".arkitekt/node_id.txt")

    /** Resolve the node id, minting and persisting one if the OS can't supply a machine GUID. */
    fun getOrSet(): String {
        System.getenv("ARKITEKT_NODE_ID")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        machineId()?.let { return it }

        return persistedUuid()
    }

    /**
     * The platform-specific device GUID, read without admin privileges — a direct port of
     * py-machineid's `id()`. Returns null (instead of throwing) when it can't be determined,
     * so callers fall back to a persisted UUID.
     */
    fun machineId(): String? {
        val os = System.getProperty("os.name").lowercase()
        val raw =
                when {
                    os.contains("mac") || os.contains("darwin") ->
                            extractIoregUuid(exec("ioreg", "-d2", "-c", "IOPlatformExpertDevice"))
                    os.contains("win") -> readWindowsMachineGuid()
                    // linux (and *bsd hostid) — the common machine-id files cover the vast majority.
                    else ->
                            readFirst(
                                    "/var/lib/dbus/machine-id",
                                    "/etc/machine-id",
                                    "/etc/hostid"
                            )
                }
        return raw?.let(::sanitize)?.takeIf { it.isNotEmpty() }
    }

    // ---- helpers ------------------------------------------------------------------------

    /** Strip control chars and whitespace, mirroring py-machineid's `__sanitize__`. */
    private fun sanitize(id: String): String =
            id.replace(Regex("[\\x00-\\x1f\\x7f-\\x9f\\s]"), "").trim()

    private fun readFirst(vararg paths: String): String? {
        for (path in paths) {
            try {
                val f = File(path)
                if (f.exists()) {
                    val text = f.readText().trim()
                    if (text.isNotEmpty()) return text
                }
            } catch (e: Exception) {
                // unreadable path — try the next candidate
            }
        }
        return null
    }

    private fun exec(vararg cmd: String): String? =
            try {
                val process = ProcessBuilder(*cmd).redirectErrorStream(false).start()
                val out = process.inputStream.bufferedReader().readText()
                process.waitFor()
                out
            } catch (e: Exception) {
                null
            }

    /** Pull the IOPlatformUUID value out of `ioreg` output (the `"…" = "<uuid>"` line). */
    private fun extractIoregUuid(ioreg: String?): String? {
        if (ioreg == null) return null
        val line = ioreg.lineSequence().firstOrNull { it.contains("IOPlatformUUID") } ?: return null
        // …"IOPlatformUUID" = "17A28A73-BEA9-4D4B-AF5B-03A5AAE9B92C" -> take the last quoted token.
        return line.split('"').dropLast(1).lastOrNull()
    }

    /** Read `HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid` via `reg query`. */
    private fun readWindowsMachineGuid(): String? {
        val out =
                exec(
                        "reg",
                        "query",
                        "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                        "/v",
                        "MachineGuid"
                )
                        ?: return null
        // The value line looks like:  MachineGuid    REG_SZ    <guid>
        val line = out.lineSequence().firstOrNull { it.contains("MachineGuid") } ?: return null
        return line.trim().split(Regex("\\s+")).lastOrNull()
    }

    private fun persistedUuid(): String {
        try {
            if (fallbackFile.exists()) {
                val existing = fallbackFile.readText().trim()
                if (existing.isNotEmpty()) return existing
            }
            val fresh = UUID.randomUUID().toString()
            fallbackFile.parentFile?.mkdirs()
            fallbackFile.writeText(fresh)
            return fresh
        } catch (e: Exception) {
            println("NodeId: could not persist fallback node id: ${e.message}")
            return UUID.randomUUID().toString()
        }
    }
}
