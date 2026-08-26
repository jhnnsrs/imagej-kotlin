package com.mycompany.arkitekt

import com.mycompany.mikro.graphql.type.AnnotationKind
import com.mycompany.mikro.graphql.type.AxisType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Turning a drawn ROI into a mikro Annotation.
 *
 * Every case here is one that would otherwise succeed, return an id, and be wrong: mikro reads
 * `vectors` positionally against the collection's declared axis order, so a shape sent in the wrong
 * layout is stored happily and drawn somewhere else entirely.
 */
class RoiAnnotationTest {

    private fun axes(vararg spec: Pair<String, AxisType>) = spec.map { AxisSpec(it.first, it.second) }

    private val ctzyx = axes(
        "c" to AxisType.CHANNEL,
        "t" to AxisType.TIME,
        "z" to AxisType.SPACE,
        "y" to AxisType.SPACE,
        "x" to AxisType.SPACE,
    )

    // --- the axis-order trap -----------------------------------------------------------------

    @Test
    fun `x and y land in their own slots, not in slots 0 and 1`() {
        // The whole point. For a (c,t,z,y,x) lens, components 0 and 1 are the CHANNEL and TIME
        // axes — sending a bare [x, y] would store the drawing against those.
        val spec = annotationSpecFor(
            PlanarShape(AnnotationKind.POLYGON, listOf(100.0 to 200.0, 140.0 to 205.0, 150.0 to 260.0)),
            ctzyx,
            resolveRenderAxes(ctzyx),
            mapOf("c" to 1, "t" to 0, "z" to 5),
        )

        assertEquals(3, spec.vectors.size)
        spec.vectors.forEach { assertEquals(5, it.size, "one component per axis") }
        // (c, t, z, y, x)
        assertEquals(listOf(1.0, 0.0, 5.0, 200.0, 100.0), spec.vectors[0])
        assertEquals(listOf(1.0, 0.0, 5.0, 205.0, 140.0), spec.vectors[1])
    }

    @Test
    fun `a non-canonical z,c,y,x lens still puts x and y where its axes say`() {
        val zcyx = axes(
            "z" to AxisType.SPACE,
            "c" to AxisType.CHANNEL,
            "y" to AxisType.SPACE,
            "x" to AxisType.SPACE,
        )
        val spec = annotationSpecFor(
            PlanarShape(AnnotationKind.LINE, listOf(10.0 to 20.0, 30.0 to 40.0)),
            zcyx,
            resolveRenderAxes(zcyx),
            mapOf("z" to 7, "c" to 2),
        )

        assertEquals(listOf(7.0, 2.0, 20.0, 10.0), spec.vectors[0])
        assertEquals(listOf(7.0, 2.0, 40.0, 30.0), spec.vectors[1])
    }

    @Test
    fun `every axis the shape does not span is pinned`() {
        val spec = annotationSpecFor(
            PlanarShape(AnnotationKind.POINT, listOf(3.0 to 4.0)),
            ctzyx,
            resolveRenderAxes(ctzyx),
            mapOf("c" to 1, "t" to 9, "z" to 5),
        )
        assertEquals(listOf("c" to 1, "t" to 9, "z" to 5), spec.coordinates)
    }

    @Test
    fun `an axis with no known position is left unpinned rather than pinned to a guess`() {
        // An unpinned axis reads server-side as "the shape spans it", which is honest. Pinning a
        // fabricated 0 would claim the shape is on the first slice.
        val spec = annotationSpecFor(
            PlanarShape(AnnotationKind.POINT, listOf(3.0 to 4.0)),
            ctzyx,
            resolveRenderAxes(ctzyx),
            mapOf("c" to 1),
        )
        assertEquals(listOf("c" to 1), spec.coordinates)
        // ...but the vector still needs a value in every slot, and 0 is the only one available.
        assertEquals(listOf(1.0, 0.0, 0.0, 4.0, 3.0), spec.vectors[0])
    }

    // --- the inclusive far corner ------------------------------------------------------------

    @Test
    fun `a rectangle's far corner is inclusive`() {
        // IJ1 width/height are counts; mikro's two-corner kinds hold voxel indices. x=10,w=5 covers
        // voxels 10..14, so the far corner is 14. Sending 15 would claim six voxels.
        val shape = boundsShape(AnnotationKind.RECTANGLE, 10.0, 20.0, 5.0, 5.0)
        assertEquals(listOf(10.0 to 20.0, 14.0 to 24.0), shape.points)
    }

    @Test
    fun `a one-pixel rectangle is a single voxel, not a degenerate box`() {
        val shape = boundsShape(AnnotationKind.RECTANGLE, 10.0, 20.0, 1.0, 1.0)
        assertEquals(listOf(10.0 to 20.0, 10.0 to 20.0), shape.points)
    }

    // --- the per-kind minimum vertex counts --------------------------------------------------

    @Test
    fun `an under-vertexed shape is not drawable`() {
        // A 2-point polygon is a hard server error. It has to be caught here, or one bad shape
        // throws in the middle of a drawing session.
        assertFalse(isDrawable(PlanarShape(AnnotationKind.POLYGON, listOf(1.0 to 1.0, 2.0 to 2.0))))
        assertTrue(isDrawable(PlanarShape(AnnotationKind.POLYGON, listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0))))
        assertFalse(isDrawable(PlanarShape(AnnotationKind.LINE, listOf(1.0 to 1.0))))
        assertTrue(isDrawable(PlanarShape(AnnotationKind.POINT, listOf(1.0 to 1.0))))
    }

    @Test
    fun `building a spec for an under-vertexed shape is refused`() {
        assertFailsWith<IllegalArgumentException> {
            annotationSpecFor(
                PlanarShape(AnnotationKind.POLYGON, listOf(1.0 to 1.0, 2.0 to 2.0)),
                ctzyx,
                resolveRenderAxes(ctzyx),
                emptyMap(),
            )
        }
    }

    @Test
    fun `render axes that are not axes of the source are refused`() {
        assertFailsWith<IllegalArgumentException> {
            annotationSpecFor(
                PlanarShape(AnnotationKind.POINT, listOf(1.0 to 1.0)),
                ctzyx,
                RenderAxisNames(x = "u", y = "v", z = null, t = null, intensity = null),
                emptyMap(),
            )
        }
    }

    // --- an IJ1 ROI's own declared slice ------------------------------------------------------

    @Test
    fun `IJ1 positions are 1-based and land 0-based on the lens' axis names`() {
        // Reading these naively pins every shape one slice too high, which stores fine and is
        // simply on the wrong plane.
        val pins = ij1Pins(Ij1Position(c = 2, z = 6, t = 4), resolveRenderAxes(ctzyx))
        assertEquals(mapOf("c" to 1, "z" to 5, "t" to 3), pins)
    }

    @Test
    fun `an IJ1 position of 0 means unset, not slice zero`() {
        // The other half of the same trap: 0 is IJ1's "no position", so pinning it would claim the
        // shape is on the first slice. Absent lets the caller fall back to the viewer's position.
        assertEquals(mapOf("z" to 5), ij1Pins(Ij1Position(c = 0, z = 6, t = 0), resolveRenderAxes(ctzyx)))
        assertEquals(emptyMap(), ij1Pins(Ij1Position(0, 0, 0), resolveRenderAxes(ctzyx)))
        assertEquals(emptyMap(), ij1Pins(null, resolveRenderAxes(ctzyx)))
    }

    @Test
    fun `an axis the lens does not have is never pinned`() {
        // A (y,x) lens has no channel, z or time axis for an IJ1 position to name.
        val yx = axes("y" to AxisType.SPACE, "x" to AxisType.SPACE)
        assertEquals(emptyMap(), ij1Pins(Ij1Position(2, 6, 4), resolveRenderAxes(yx)))
    }

    // --- no half-voxel shift, no permutation -------------------------------------------------

    @Test
    fun `vectors are passed through unshifted`() {
        // The server applies +/-0.5 itself, in vectors_bbox, purely to derive intrinsicBbox. Doing
        // it here too would widen every derived box by a pixel.
        val yx = axes("y" to AxisType.SPACE, "x" to AxisType.SPACE)
        val spec = annotationSpecFor(
            PlanarShape(AnnotationKind.POINT, listOf(10.0 to 20.0)),
            yx,
            resolveRenderAxes(yx),
            emptyMap(),
        )
        assertEquals(listOf(listOf(20.0, 10.0)), spec.vectors)
    }
}
