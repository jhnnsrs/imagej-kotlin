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
    println("MACRO SMOKE TEST PASSED")
    ij.context().dispose()
    System.exit(0)
}
