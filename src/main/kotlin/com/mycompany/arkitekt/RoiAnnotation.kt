package com.mycompany.arkitekt

import com.mycompany.mikro.graphql.type.AnnotationKind

// Turning a drawn ROI into a mikro Annotation.
//
// Pure: no ImageJ, no network. The IJ2/IJ1 readers live in `RoiSources.kt` and reduce to
// [PlanarShape]; the handler in `Arkitekt.kt` does the mutations.
//
// THREE RULES THIS FILE ENCODES, each of which is a way to be silently wrong:
//
//  1. **Vectors are positional in the COLLECTION's declared axis order — every axis, including
//     non-spatial ones.** For a lens over a (c,t,z,y,x) store, component 0 is `c`, not `x`. We
//     declare the collection's axes to be the lens' axes, so the layout is the lens' array order.
//  2. **No half-voxel shift.** The server applies +/-0.5 itself, in `vectors_bbox`, purely to
//     derive `intrinsicBbox`; the stored vectors are untouched. A component of 10 means voxel 10.
//     Subtracting 0.5 here would widen every derived box by a pixel.
//  3. **No array->vertex permutation.** mikro has an `array_to_vertex_order` documented as "THE
//     permutation", but it has no caller on the annotation path. Vectors stay in axis order.

/** One shape as drawn, in screen x/y — what both the IJ2 overlay and IJ1 Roi readers reduce to. */
data class PlanarShape(
    val kind: AnnotationKind,
    val points: List<Pair<Double, Double>>,
)

/** A shape ready to send: vectors in collection-axis order, plus the slice it is pinned to. */
data class AnnotationSpec(
    val kind: AnnotationKind,
    val vectors: List<List<Double>>,
    val coordinates: List<Pair<String, Int>>,
)

/**
 * The fewest vertices each kind can be drawn from, mirroring `_MINIMUM_VERTICES` in the backend's
 * `core/inputs/validators.py`. A shape below its minimum is a hard server error, so it is dropped
 * here rather than allowed to throw in the middle of a drawing session.
 */
private val MINIMUM_VERTICES: Map<AnnotationKind, Int> = mapOf(
    AnnotationKind.POINT to 1,
    AnnotationKind.MULTI_POINT to 1,
    AnnotationKind.LINE to 2,
    AnnotationKind.PATH to 2,
    AnnotationKind.RECTANGLE to 2,
    AnnotationKind.CUBE to 2,
    AnnotationKind.CIRCLE to 2,
    AnnotationKind.ELLIPSE to 2,
    AnnotationKind.SPHERE to 2,
    AnnotationKind.ELLIPSOID to 2,
    AnnotationKind.POLYGON to 3,
)

/** Whether this shape has enough vertices for its kind to be drawn from. */
fun isDrawable(shape: PlanarShape): Boolean =
    shape.points.size >= (MINIMUM_VERTICES[shape.kind] ?: 1)

/**
 * A rectangle or ellipse from an IJ1-style bounding box, as the two opposite corners mikro stores.
 *
 * **The far corner is inclusive.** IJ1's `width`/`height` are counts; mikro's two-corner kinds hold
 * voxel indices. A rect at `x=10,w=5` covers voxels 10..14, so the corners are 10 and **14** — the
 * server then reads that as the half-open box [9.5, 14.5], five voxels. Using `x + w` would claim
 * six.
 */
fun boundsShape(kind: AnnotationKind, x: Double, y: Double, width: Double, height: Double): PlanarShape =
    PlanarShape(kind, listOf(x to y, (x + width - 1) to (y + height - 1)))

/**
 * Place a drawn shape into the collection's coordinate space.
 *
 * Emits **one component per axis, in [axes] order** — the alternative, a bare 2-component vector,
 * would be read against the collection's first two axes, which for an ordinary (c,t,z,y,x) lens are
 * the channel and time axes. The axis named [RenderAxisNames.x] takes the shape's x and
 * [RenderAxisNames.y] its y; every other axis takes its current slice index from [position], or 0.
 *
 * The same slice indices go out again as `coordinates` pins, which is what the backend documents
 * them for. Pins are deliberately not validated against the drawing space's axes server-side, so
 * pinning a channel is fine.
 */
fun annotationSpecFor(
    shape: PlanarShape,
    axes: List<AxisSpec>,
    render: RenderAxisNames,
    position: Map<String, Int>,
): AnnotationSpec {
    require(isDrawable(shape)) {
        "A ${shape.kind} is drawn from at least ${MINIMUM_VERTICES[shape.kind] ?: 1} vertices, " +
                "but this shape has ${shape.points.size}"
    }
    require(axes.any { it.name == render.x } && axes.any { it.name == render.y }) {
        "The render axes (x='${render.x}', y='${render.y}') are not axes of ${axes.map { it.name }}"
    }

    val vectors = shape.points.map { (px, py) ->
        axes.map { axis ->
            when (axis.name) {
                render.x -> px
                render.y -> py
                else -> (position[axis.name] ?: 0).toDouble()
            }
        }
    }

    // Every axis the shape does not span is a slice it sits on. An axis with no known position is
    // left unpinned rather than pinned to a guessed 0 — an unpinned axis reads as "spans it", which
    // is the honest answer when we do not know.
    val pins = axes
        .filter { it.name != render.x && it.name != render.y }
        .mapNotNull { axis -> position[axis.name]?.let { axis.name to it } }

    return AnnotationSpec(shape.kind, vectors, pins)
}

/**
 * The pins an IJ1 ROI declares for itself, mapped onto the lens' axis names.
 *
 * IJ1 numbers channel/slice/frame from **1**, and uses **0 for "not set"** — so a naive read pins
 * every shape one slice too high, and pins shapes that never declared a position at all. Both are
 * silent: the annotation stores fine and is simply on the wrong plane.
 *
 * An axis the ROI says nothing about is absent from the result, which lets the caller fall back to
 * the viewer's current position rather than to a fabricated 0.
 */
fun ij1Pins(position: Ij1Position?, render: RenderAxisNames): Map<String, Int> {
    if (position == null) return emptyMap()
    return buildMap {
        render.intensity?.let { if (position.c > 0) put(it, position.c - 1) }
        render.z?.let { if (position.z > 0) put(it, position.z - 1) }
        render.t?.let { if (position.t > 0) put(it, position.t - 1) }
    }
}
