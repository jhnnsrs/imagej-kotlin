package com.mycompany.arkitekt

import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import net.imagej.Dataset
import net.imagej.ImageJ
import org.scijava.convert.ConvertService

// Headless smoke test for the standalone IJ1-legacy macro path (the same Dataset<->ImagePlus +
// IJ.runMacro flow that runMacroOnDataset uses). Run via the `macroSmokeTest` Gradle task, which
// supplies the ij1Runtime classpath + `--add-opens java.base/java.lang=ALL-UNNAMED`. Proves that
// LegacyInjector.preinit() actually patched ij.IJ (not just that ImageJ2 booted).
fun main() {
    System.setProperty("java.awt.headless", "true")
    // Same preinit as ArkitektCommand.main() — must run before any IJ1 class loads.
    Class.forName("net.imagej.patcher.LegacyInjector").getMethod("preinit").invoke(null)

    // Confirm the patch landed: ij.IJ must now have the injected `_hooks` field.
    val hasHooks = runCatching { IJ::class.java.getField("_hooks") }.isSuccess
    println("ij.IJ._hooks present after preinit: $hasHooks")
    check(hasHooks) { "preinit did not patch ij.IJ (_hooks missing) — legacy layer would fail" }

    val ij = ImageJ()
    val convert = ij.context().getService(ConvertService::class.java)

    // Build a tiny 4x4 test image and push it through the exact convert+macro path.
    val dataset = ij.dataset().create(longArrayOf(4, 4), "smoke", arrayOf(net.imagej.axis.Axes.X, net.imagej.axis.Axes.Y), 8, false, false)
    val imp = convert.convert(dataset, ImagePlus::class.java)
        ?: error("Dataset -> ImagePlus conversion returned null")
    println("Dataset -> ImagePlus OK: ${imp.width}x${imp.height}")

    val result: ImagePlus = try {
        WindowManager.setTempCurrentImage(imp)
        IJ.runMacro("setPixel(0, 0, 200); run(\"Invert\");")
        WindowManager.getCurrentImage() ?: imp
    } finally {
        WindowManager.setTempCurrentImage(null)
    }
    println("IJ.runMacro OK: result ${result.width}x${result.height}, pixel(0,0)=${result.processor.getPixel(0, 0)}")

    val back = convert.convert(result, Dataset::class.java)
        ?: error("ImagePlus -> Dataset conversion returned null")
    println("ImagePlus -> Dataset OK: ${back.name} dims=${back.numDimensions()}")

    checkShadedRuntime(dataset)

    println("MACRO SMOKE TEST PASSED")
    ij.context().dispose()
    System.exit(0)
}


/**
 * The parts of the runtime that a botched shading would break, and nothing else here touches.
 *
 * Run via the `shadedSmokeTest` Gradle task, which puts Fiji's OWN library versions (kotlin-stdlib
 * 1.8.22, guava 31.1, jackson 2.14.2, okio 3.3.0 ...) on the classpath AHEAD of the shaded jar --
 * exactly the collision Fiji's flat classloader creates. Each of these three exercises a package
 * the shadowJar block relocates:
 *
 *  - kotlinx-serialization: compiler-generated serializers, the most stdlib-coupled thing we run,
 *    and the reason relocating `kotlin` is the risky part of the whole scheme.
 *  - coroutines: `runBlocking` pulls in the dispatcher ServiceLoader files that mergeServiceFiles()
 *    has to have relocated in step with the classes.
 *  - ucar.ma2: cdm-core 5.9.1 vs Fiji's 5.3.3, on the plugin's actual write path.
 *
 * Under `macroSmokeTest` (unshaded) it simply passes, which keeps the two tasks sharing one main.
 */
private fun checkShadedRuntime(dataset: Dataset) {
    val assign = AgentMessage.Assign(functionName = "smoke", task = "t-1")
    val wire = agentJson.encodeToString(AgentMessage.serializer(), assign)
    check("\"type\":\"ASSIGN\"" in wire) { "serialization produced no discriminator: $wire" }
    val decoded = agentJson.decodeFromString(AgentMessage.serializer(), wire)
    check(decoded == assign) { "serialization round-trip changed the message: $decoded" }
    // NB: do not start these strings with a relocated package token ("kotlinx…", "ucar…").
    // Shadow rewrites string constants as well as bytecode, so the message would come out as
    // "com.mycompany.arkitekt.shaded.kotlinx-serialization round-trip OK".
    println("serialization round-trip OK: $wire")

    val fromCoroutine = kotlinx.coroutines.runBlocking { "ok" }
    check(fromCoroutine == "ok")
    println("coroutines runBlocking OK")

    val array = imgPlusToCTZYXUcarArray(dataset.imgPlus)
    check(array.rank == 5) { "expected a rank-5 c,t,z,y,x array, got rank ${array.rank}" }
    check(array.size == 16L) { "expected 4x4 = 16 elements, got ${array.size}" }
    println("ma2 array OK: rank=${array.rank} shape=${array.shape.joinToString(",")}")
}
