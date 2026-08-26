package com.mycompany.arkitekt

import com.mycompany.mikro.graphql.type.AnnotationKind
import net.imagej.display.ImageDisplay
import net.imagej.display.OverlayService
import net.imagej.overlay.EllipseOverlay
import net.imagej.overlay.LineOverlay
import net.imagej.overlay.Overlay
import net.imagej.overlay.PointOverlay
import net.imagej.overlay.PolygonOverlay
import net.imagej.overlay.RectangleOverlay

// Reading whatever the user has drawn, out of whichever UI is drawing it.
//
// THIS IS THE FORK THE WHOLE FEATURE TURNS ON. There is no single place drawn shapes live:
//
//  * Under `./gradlew run` the ImageJ2 Swing UI owns the window, and a drawn shape becomes a
//    `net.imagej.overlay.Overlay` on the ImageDisplay.
//  * Inside Fiji the default UI is the IJ1 legacy one, and a drawn shape is an `ij.gui.Roi` on an
//    ImagePlus — no Overlay is created until a harmonization pass runs.
//
// So both are read, every poll, and whichever answers wins. That is also why this is polled rather
// than driven by `OverlayCreatedEvent`: those events fire only on the IJ2 half of the fork, and
// SciJava holds event subscribers *weakly*, so a collected handler stops firing with no error.

/**
 * A shape plus the object it came from.
 *
 * [source] is the identity across polls: ImageJ hands back the *same* Overlay / Roi instance each
 * time, so an `IdentityHashMap` keyed on it distinguishes "the user edited that shape" (same object,
 * new geometry -> updateAnnotation) from "the user drew another" (new object -> createAnnotation).
 * Keying on the geometry instead would make every edit a duplicate.
 *
 * [ij1Position] is the slice an IJ1 ROI declares for itself (1-based c/z/t, 0 for "unset"). It is
 * preferred over the viewer's current position for IJ1 shapes: a banked ROI remembers the slice it
 * was drawn on, while the display has since moved on, and in Fiji the two routinely disagree.
 */
data class DrawnShape(val source: Any, val shape: PlanarShape, val ij1Position: Ij1Position? = null)

/** An IJ1 ROI's own channel/slice/frame, 1-based, with 0 meaning "not set". */
data class Ij1Position(val c: Int, val z: Int, val t: Int)

/**
 * Every shape currently drawn, from both sources, de-duplicated by key.
 *
 * Must be called on the EDT — it touches ImageJ UI state.
 */
fun readDrawnShapes(
    overlayService: OverlayService?,
    display: ImageDisplay?,
    ignore: Set<Any> = emptySet(),
): List<DrawnShape> {
    val fromOverlays = if (overlayService != null && display != null) {
        runCatching { overlayService.getOverlays(display).mapNotNull(::overlayShape) }.getOrDefault(emptyList())
    } else {
        emptyList()
    }

    // The IJ1 half is optional at runtime: `imagej-legacy` is compileOnly, so a host without it
    // throws NoClassDefFoundError on first touch of Ij1Rois rather than at our class-load. Catching
    // Throwable (not Exception) is deliberate — NoClassDefFoundError is an Error.
    val fromIj1 = runCatching { Ij1Rois.readAll() }.getOrDefault(emptyList())

    // `ignore` is the baseline: whatever was already lying around when the session started. The ROI
    // Manager is a global singleton that outlives a run, so without this a second `annotate_lens`
    // would re-save every shape from the first one into its new collection.
    return (fromOverlays + fromIj1).filterNot { it.source in ignore }
}

/** Whatever is already drawn, as an identity baseline to exclude from a new session. */
fun drawnBaseline(overlayService: OverlayService?, display: ImageDisplay?): Set<Any> =
    java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>()).apply {
        addAll(readDrawnShapes(overlayService, display).map { it.source })
    }

/** Map one ImageJ2 overlay onto a planar shape, or null for a kind we do not draw. */
private fun overlayShape(overlay: Overlay): DrawnShape? {
    val shape = when (overlay) {
        is RectangleOverlay -> boundsShape(
            AnnotationKind.RECTANGLE,
            overlay.getOrigin(0), overlay.getOrigin(1),
            overlay.getExtent(0), overlay.getExtent(1),
        )
        // An ImageJ2 ellipse is centre + per-axis radius; mikro stores the two bbox corners.
        is EllipseOverlay -> PlanarShape(
            AnnotationKind.ELLIPSE,
            listOf(
                (overlay.getOrigin(0) - overlay.getRadius(0)) to (overlay.getOrigin(1) - overlay.getRadius(1)),
                (overlay.getOrigin(0) + overlay.getRadius(0)) to (overlay.getOrigin(1) + overlay.getRadius(1)),
            ),
        )
        is LineOverlay -> PlanarShape(
            AnnotationKind.LINE,
            listOf(
                overlay.getLineStart(0) to overlay.getLineStart(1),
                overlay.getLineEnd(0) to overlay.getLineEnd(1),
            ),
        )
        is PolygonOverlay -> {
            val roi = overlay.regionOfInterest
            PlanarShape(
                AnnotationKind.POLYGON,
                (0 until roi.vertexCount).map { i ->
                    val v = roi.getVertex(i)
                    v.getDoublePosition(0) to v.getDoublePosition(1)
                },
            )
        }
        is PointOverlay -> {
            val points = overlay.points.map { it[0] to it[1] }
            PlanarShape(
                if (points.size > 1) AnnotationKind.MULTI_POINT else AnnotationKind.POINT,
                points,
            )
        }
        else -> return null
    }
    return if (isDrawable(shape)) DrawnShape(overlay, shape) else null
}

/**
 * The IJ1 reader, isolated in its own object so the class only loads when it is first touched.
 *
 * `ij.*` arrives from `imagej-legacy`, which is `compileOnly` — present in Fiji and on the
 * `ij1Runtime` classpath of `./gradlew run`, absent anywhere else. Keeping it in a separate class
 * means a host without it fails at *this* object's initialization, which [readDrawnShapes] catches,
 * rather than at load of anything on the main path.
 */
private object Ij1Rois {
    /**
     * The ROI Manager's contents — the shapes the user has *banked*, with `t`.
     *
     * Deliberately NOT `imp.roi`, the live selection: IJ1 replaces that object as the user drags, so
     * polling it would create an annotation per intermediate drag state. The manager is the standard
     * Fiji gesture for "keep this one", and it doubles as the "I am finished with this shape"
     * signal that a poll otherwise has no way to read.
     */
    fun readAll(): List<DrawnShape> {
        val rois = ij.plugin.frame.RoiManager.getInstance()?.roisAsArray ?: return emptyList()
        val current = ij.WindowManager.getCurrentImage()
        return rois.mapNotNull { roi ->
            // The manager is global and holds ROIs for every open image. A ROI that names an image
            // other than the one in front of the user belongs to a different picture, and mapping it
            // through THIS lens' axes would place it somewhere meaningless.
            val owner = runCatching { roi.image }.getOrNull()
            if (owner != null && current != null && owner !== current) return@mapNotNull null

            val shape = ij1Shape(roi) ?: return@mapNotNull null
            if (!isDrawable(shape)) return@mapNotNull null
            DrawnShape(roi, shape, Ij1Position(roi.cPosition, roi.zPosition, roi.tPosition))
        }
    }

    private fun ij1Shape(roi: ij.gui.Roi): PlanarShape? = when (roi.type) {
        ij.gui.Roi.RECTANGLE -> roi.bounds.let {
            boundsShape(AnnotationKind.RECTANGLE, it.x.toDouble(), it.y.toDouble(), it.width.toDouble(), it.height.toDouble())
        }
        ij.gui.Roi.OVAL -> roi.bounds.let {
            boundsShape(AnnotationKind.ELLIPSE, it.x.toDouble(), it.y.toDouble(), it.width.toDouble(), it.height.toDouble())
        }
        ij.gui.Roi.LINE -> PlanarShape(AnnotationKind.LINE, floatPoints(roi))
        ij.gui.Roi.POLYLINE, ij.gui.Roi.FREELINE, ij.gui.Roi.ANGLE ->
            PlanarShape(AnnotationKind.PATH, floatPoints(roi))
        ij.gui.Roi.POINT -> floatPoints(roi).let {
            PlanarShape(if (it.size > 1) AnnotationKind.MULTI_POINT else AnnotationKind.POINT, it)
        }
        ij.gui.Roi.POLYGON, ij.gui.Roi.FREEROI, ij.gui.Roi.TRACED_ROI, ij.gui.Roi.COMPOSITE ->
            PlanarShape(AnnotationKind.POLYGON, floatPoints(roi))
        else -> null
    }

    private fun floatPoints(roi: ij.gui.Roi): List<Pair<Double, Double>> {
        val polygon = roi.floatPolygon
        return (0 until polygon.npoints).map { polygon.xpoints[it].toDouble() to polygon.ypoints[it].toDouble() }
    }
}
