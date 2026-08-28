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
import net.imglib2.RealPoint

// Installing stored annotations into whichever UI is drawing — the write half of the fork
// `RoiSources.kt` documents on the read side.
//
// **Exactly one surface is written, never both.** Both are *read* every poll, so a shape installed
// as an IJ1 Roi AND as an IJ2 Overlay comes back as two DrawnShapes with two identities, and the
// one that was not seeded gets pushed to the server as a brand-new annotation. Duplicating on
// pull-down is the failure mode this feature would be judged by, so the sink picks the surface the
// user actually draws on — IJ1 inside Fiji, the ImageJ2 overlay only when the legacy layer is
// absent (`./gradlew run`) — and leaves the other alone.
//
// ⚠️ **The IJ2 branch cannot pin a slice.** An `Overlay` carries no channel/z/frame of its own (it
// is placed by the display's current position), so a pulled shape's pins survive only on the IJ1
// side. On IJ2 an edited shape is re-pushed against wherever the viewer is standing. That is the
// dev-path UI, and the alternative — refusing to prefill there at all — is worse.

/** A shape that was installed, and the object identity it now has on the drawing surface. */
data class InstalledShape(val annotationId: String, val source: Any)

/** What a prefill managed to do: what went on screen, and what could not be drawn. */
data class PrefillResult(val installed: List<InstalledShape>, val skipped: Int)

/**
 * Draw stored shapes onto the surface in use, returning the identity each one landed on.
 *
 * The returned identities are what the polling session keys its "already saved" map on, so they
 * MUST be the objects the readers will hand back — not the objects constructed here. IJ1's
 * `RoiManager.addRoi` **clones**, so the IJ1 branch reads the manager back after adding.
 *
 * Must be called on the EDT — it touches ImageJ UI state.
 */
fun drawShapes(
    overlayService: OverlayService?,
    display: ImageDisplay?,
    shapes: List<PrefillShape>,
    render: RenderAxisNames,
): PrefillResult {
    if (shapes.isEmpty()) return PrefillResult(emptyList(), 0)

    // AVAILABILITY IS PROBED, THE INSTALL IS NOT GUARDED. `imagej-legacy` is compileOnly, so
    // touching Ij1Sink on a host without it throws NoClassDefFoundError — an Error, hence the
    // Throwable-catching probe. But wrapping the *install* the way `RoiSources.readDrawnShapes`
    // wraps its read would be a different thing entirely: that read is side-effect-free and falls
    // back to an empty list, while a half-finished install that fell through to the IJ2 branch
    // would leave shapes banked on BOTH surfaces — exactly the duplication this file exists to
    // prevent, and with the IJ1 half unseeded, so the next poll re-creates every one of them as a
    // new annotation. So the probe decides the branch and a failure inside the branch surfaces.
    if (runCatching { Ij1Sink.probe() }.isSuccess) return Ij1Sink.install(shapes, render)

    if (overlayService == null || display == null) return PrefillResult(emptyList(), shapes.size)
    return installOverlays(overlayService, display, shapes)
}

/** The ImageJ2 half: build overlays and hand them to the display in one call. */
private fun installOverlays(
    overlayService: OverlayService,
    display: ImageDisplay,
    shapes: List<PrefillShape>,
): PrefillResult {
    val context = overlayService.context()
    val installed = ArrayList<InstalledShape>()
    val overlays = ArrayList<Overlay>()
    var skipped = 0

    for (item in shapes) {
        val overlay = runCatching { overlayFor(context, item.shape) }.getOrNull()
        if (overlay == null) {
            skipped++
            continue
        }
        overlays.add(overlay)
        // The Overlay we construct IS the object the OverlayService hands back, so unlike the IJ1
        // branch there is nothing to read back.
        installed.add(InstalledShape(item.id, overlay))
    }

    if (overlays.isNotEmpty()) overlayService.addOverlays(display, overlays)
    return PrefillResult(installed, skipped)
}

/** One overlay, or null for a kind the ImageJ2 UI has no equivalent for (notably an open PATH). */
private fun overlayFor(context: org.scijava.Context, shape: PlanarShape): Overlay? = when (shape.kind) {
    AnnotationKind.RECTANGLE -> {
        val box = boundsRectOf(shape.points)
        RectangleOverlay(context).apply {
            setOrigin(box.x.toDouble(), 0)
            setOrigin(box.y.toDouble(), 1)
            setExtent(box.width.toDouble(), 0)
            setExtent(box.height.toDouble(), 1)
        }
    }
    // An ImageJ2 ellipse is centre + per-axis radius, while mikro stores the two bbox corners —
    // the same conversion `RoiSources.overlayShape` does, run backwards.
    AnnotationKind.ELLIPSE, AnnotationKind.CIRCLE -> {
        val box = boundsRectOf(shape.points)
        EllipseOverlay(context).apply {
            setOrigin(box.x + box.width / 2.0, 0)
            setOrigin(box.y + box.height / 2.0, 1)
            setRadius(box.width / 2.0, 0)
            setRadius(box.height / 2.0, 1)
        }
    }
    AnnotationKind.LINE -> LineOverlay(
        context,
        doubleArrayOf(shape.points[0].first, shape.points[0].second),
        doubleArrayOf(shape.points[1].first, shape.points[1].second),
    )
    AnnotationKind.POLYGON -> PolygonOverlay(context).apply {
        shape.points.forEachIndexed { i, (x, y) -> regionOfInterest.addVertex(i, RealPoint(x, y)) }
    }
    AnnotationKind.POINT, AnnotationKind.MULTI_POINT ->
        PointOverlay(context, shape.points.map { doubleArrayOf(it.first, it.second) })
    // PATH is an open polyline; ImageJ2's PolygonOverlay closes itself, so drawing one here would
    // change the shape. GeneralPathOverlay is not something RoiSources reads back either.
    else -> null
}

/**
 * The IJ1 half, isolated in its own object so `ij.*` only loads when it is first touched — the same
 * reason `RoiSources.Ij1Rois` is structured this way.
 */
private object Ij1Sink {
    /** Touch `ij.*` and nothing else: this either loads the legacy classes or throws. */
    fun probe() {
        ij.plugin.frame.RoiManager::class.java.name
    }

    /**
     * Bank each shape in the ROI Manager, on the slice it was pinned to.
     *
     * Two IJ1 facts the read path never had to know:
     *
     *  * **`getInstance()` returns null until the manager has been opened**, and the read path is
     *    happy with that (nothing drawn yet). Here it would make the whole prefill a silent no-op,
     *    so this opens it — `getRoiManager()` is the "give me one, creating it if needed" call.
     *  * **`addRoi` stores a clone.** Keying the session on the Roi we constructed would therefore
     *    never match what the poll reads back, and every pulled shape would be re-created as a new
     *    annotation on the first poll. So the manager is read back afterwards and the newly
     *    appended entries — which is what the count taken beforehand identifies — are the
     *    identities returned.
     */
    fun install(shapes: List<PrefillShape>, render: RenderAxisNames): PrefillResult {
        val manager = ij.plugin.frame.RoiManager.getRoiManager()
        val before = manager.count

        val added = ArrayList<String>()
        var skipped = 0
        for (item in shapes) {
            // The whole per-item install is guarded, not just the geometry: a shape that fails to
            // bank must cost that one shape, not the identities of everything already added.
            val ok = runCatching {
                val roi = roiFor(item.shape) ?: return@runCatching false
                val pos = ij1PositionFor(item.position, render)
                roi.setPosition(pos.c, pos.z, pos.t)
                manager.addRoi(roi)
                true
            }.onFailure { println("annotate: could not draw a ${item.shape.kind}: ${it.message}") }
                .getOrDefault(false)

            if (ok) added.add(item.id) else skipped++
        }

        val rois = manager.roisAsArray
        // Defensive: if the manager did not grow the way we counted (a hyperstack ROI can expand
        // into several entries), the id -> identity mapping would be off by an unknown amount, and
        // a wrong mapping means editing one shape overwrites another's annotation. Seeding nothing
        // is recoverable — a duplicate write is not.
        if (rois.size - before != added.size) {
            println("annotate: the ROI Manager grew by ${rois.size - before} for ${added.size} shape(s); not seeding identities")
            return PrefillResult(emptyList(), skipped)
        }

        return PrefillResult(added.mapIndexed { i, id -> InstalledShape(id, rois[before + i]) }, skipped)
    }

    /** One IJ1 ROI, or null for a kind we do not draw. Mirrors `RoiSources.Ij1Rois.ij1Shape`. */
    private fun roiFor(shape: PlanarShape): ij.gui.Roi? = when (shape.kind) {
        AnnotationKind.RECTANGLE -> boundsRectOf(shape.points).let {
            ij.gui.Roi(it.x, it.y, it.width, it.height)
        }
        AnnotationKind.ELLIPSE, AnnotationKind.CIRCLE -> boundsRectOf(shape.points).let {
            ij.gui.OvalRoi(it.x, it.y, it.width, it.height)
        }
        AnnotationKind.LINE -> ij.gui.Line(
            shape.points[0].first, shape.points[0].second,
            shape.points[1].first, shape.points[1].second,
        )
        AnnotationKind.PATH -> polygonRoi(shape, ij.gui.Roi.POLYLINE)
        AnnotationKind.POLYGON -> polygonRoi(shape, ij.gui.Roi.POLYGON)
        AnnotationKind.POINT, AnnotationKind.MULTI_POINT ->
            ij.gui.PointRoi(xs(shape), ys(shape), shape.points.size)
        else -> null
    }

    private fun polygonRoi(shape: PlanarShape, type: Int): ij.gui.Roi =
        ij.gui.PolygonRoi(xs(shape), ys(shape), shape.points.size, type)

    private fun xs(shape: PlanarShape) = FloatArray(shape.points.size) { shape.points[it].first.toFloat() }

    private fun ys(shape: PlanarShape) = FloatArray(shape.points.size) { shape.points[it].second.toFloat() }
}
