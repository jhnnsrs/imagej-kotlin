package com.mycompany.arkitekt

import com.mycompany.mikro.graphql.type.AnnotationKind

// Turning a stored mikro Annotation back into a drawable shape — the exact inverse of
// `RoiAnnotation.kt`, and pure for the same reason: no ImageJ, no network. The code that actually
// installs the shapes into a viewer lives in `RoiSinks.kt`.
//
// The three rules `RoiAnnotation.kt` encodes all run backwards here, and each is still a way to be
// silently wrong:
//
//  1. **Vectors are positional in the COLLECTION's declared axis order.** Reading component 0 as x
//     is right only for a collection whose first axis IS x. So the decode indexes by *name*, using
//     the axes the collection itself declares — which is why [GetAnnotationCollectionQuery] selects
//     them rather than reusing the lens'.
//  2. **No half-voxel shift**: a component of 10 means voxel 10, so it comes back as 10.
//  3. **The far corner is inclusive**: corners 10 and 14 are five voxels, so the IJ1 width is
//     `14 - 10 + 1`. Reading it as `14 - 10` loses a pixel off every rectangle and ellipse, every
//     round trip, cumulatively.

/** One annotation as the server stores it: vectors in the collection's axis order, plus its pins. */
data class RemoteAnnotation(
    val id: String,
    val kind: AnnotationKind,
    val vectors: List<List<Double>>,
    val coordinates: List<Pair<String, Int>>,
)

/** A stored annotation decoded into screen x/y plus the slice it belongs on. */
data class PrefillShape(
    val id: String,
    val shape: PlanarShape,
    val position: Map<String, Int>,
)

/**
 * The kinds this plugin cannot draw: a 2D drawing surface has no way to show a volume, and
 * inventing a planar stand-in would round-trip as a different shape than the one stored.
 *
 * They are skipped with a count rather than dropped quietly — "pulled 3 annotations" when the
 * collection held 7 reads as success.
 */
val VOLUMETRIC_KINDS: Set<AnnotationKind> = setOf(
    AnnotationKind.CUBE,
    AnnotationKind.SPHERE,
    AnnotationKind.ELLIPSOID,
)

/**
 * Check that a collection's own axes agree with the lens the shapes will be drawn over, and return
 * the collection's render axes.
 *
 * **This is the discriminator for silently transposed geometry.** A collection minted by this
 * plugin has the lens' axes, but a caller may hand us any collection id, and a collection over
 * `(y, x)` decoded against a `(x, y)` lens produces shapes that are wrong in a way nothing
 * downstream can detect: every mutation succeeds and every pixel is off. So the render axes are
 * resolved from the *collection's* declared axes and required to name the same x and y as the
 * lens'; anything else refuses the prefill instead of guessing.
 */
fun collectionRenderAxes(collectionAxes: List<AxisSpec>, lensRender: RenderAxisNames): RenderAxisNames {
    val render = resolveRenderAxes(collectionAxes)
    require(render.x == lensRender.x && render.y == lensRender.y) {
        "The collection is drawn over (x='${render.x}', y='${render.y}') but the lens renders " +
                "(x='${lensRender.x}', y='${lensRender.y}') — refusing to decode its shapes, which " +
                "would place them transposed"
    }
    return render
}

/**
 * Decode one stored annotation into a planar shape, or null for one that cannot be drawn.
 *
 * Null covers three cases, all legitimate: a volumetric kind ([VOLUMETRIC_KINDS]), a vector whose
 * width does not match the collection's axis count (a foreign writer's shape we cannot index), and
 * a shape with too few vertices for its kind.
 *
 * The position is taken from the annotation's own `coordinates` pins first — that is what the
 * server documents them for. An axis with no pin falls back to the value the vectors themselves
 * agree on, which is what this plugin writes for every non-render axis; an axis whose vectors
 * disagree is one the shape genuinely spans, and is left out rather than pinned to a made-up value.
 */
fun prefillShapeFor(
    annotation: RemoteAnnotation,
    axes: List<AxisSpec>,
    render: RenderAxisNames,
): PrefillShape? {
    if (annotation.kind in VOLUMETRIC_KINDS) return null
    if (annotation.vectors.isEmpty()) return null
    if (annotation.vectors.any { it.size != axes.size }) return null

    val xi = axes.indexOfFirst { it.name == render.x }
    val yi = axes.indexOfFirst { it.name == render.y }
    if (xi < 0 || yi < 0) return null

    val shape = PlanarShape(annotation.kind, annotation.vectors.map { it[xi] to it[yi] })
    if (!isDrawable(shape)) return null

    val pins = annotation.coordinates.toMap()
    val position = axes.withIndex()
        .filter { (_, axis) -> axis.name != render.x && axis.name != render.y }
        .mapNotNull { (index, axis) ->
            val pinned = pins[axis.name]
            if (pinned != null) {
                axis.name to pinned
            } else {
                val values = annotation.vectors.map { it[index] }.distinct()
                if (values.size == 1) axis.name to values[0].toInt() else null
            }
        }
        .toMap()

    return PrefillShape(annotation.id, shape, position)
}

/** An IJ1-style bounding box: origin plus counts. */
data class BoundsRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The IJ1 bounding box of a two-corner shape — the inverse of [boundsShape].
 *
 * The stored corners are voxel indices and the far one is inclusive, so the width is the difference
 * **plus one**. Corners are min/max'd rather than assumed ordered: a rectangle dragged up-and-left
 * is stored exactly as it was drawn, and IJ1 refuses a negative width.
 */
fun boundsRectOf(points: List<Pair<Double, Double>>): BoundsRect {
    require(points.size >= 2) { "A bounding-box shape needs two corners, got ${points.size}" }
    val xs = points.map { it.first }
    val ys = points.map { it.second }
    val x0 = Math.round(xs.min()).toInt()
    val y0 = Math.round(ys.min()).toInt()
    val x1 = Math.round(xs.max()).toInt()
    val y1 = Math.round(ys.max()).toInt()
    return BoundsRect(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
}

/**
 * The IJ1 channel/slice/frame a decoded shape should be banked on, from its axis positions.
 *
 * The inverse of [ij1Pins]: IJ1 numbers from **1** and reads **0 as "unset"**, so an axis with a
 * known position is written `+ 1` and one without stays 0. Writing a known 0-based position as 0
 * would make the ROI claim no position at all, and the next poll would then pin it to wherever the
 * viewer happened to be.
 */
fun ij1PositionFor(position: Map<String, Int>, render: RenderAxisNames): Ij1Position = Ij1Position(
    c = render.intensity?.let { position[it] }?.plus(1) ?: 0,
    z = render.z?.let { position[it] }?.plus(1) ?: 0,
    t = render.t?.let { position[it] }?.plus(1) ?: 0,
)
