package com.mycompany.arkitekt

import com.mycompany.mikro.graphql.type.AnnotationKind
import com.mycompany.mikro.graphql.type.AxisType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turning a stored mikro Annotation back into a drawable shape — the pull half of `annotate_in_fiji`.
 *
 * The cases here are the ones that come back *plausible* when they are wrong: a shape decoded
 * against the wrong axis order lands somewhere else on the image, a bounding box read as exclusive
 * shrinks a pixel per round trip, and a pin read 0-based pulls every shape one slice off. None of
 * them throws, and the sync would then push the wrong geometry back over the right one.
 */
class AnnotationRoiTest {

    private fun axes(vararg spec: Pair<String, AxisType>) = spec.map { AxisSpec(it.first, it.second) }

    private val ctzyx = axes(
        "c" to AxisType.CHANNEL,
        "t" to AxisType.TIME,
        "z" to AxisType.SPACE,
        "y" to AxisType.SPACE,
        "x" to AxisType.SPACE,
    )

    private val render = resolveRenderAxes(ctzyx)

    private fun annotation(
        kind: AnnotationKind,
        vectors: List<List<Double>>,
        coordinates: List<Pair<String, Int>> = emptyList(),
    ) = RemoteAnnotation("a-1", kind, vectors, coordinates)

    // --- the round trip ------------------------------------------------------------------------

    @Test
    fun `a pushed shape decodes back to the shape that was drawn`() {
        val drawn = PlanarShape(AnnotationKind.POLYGON, listOf(100.0 to 200.0, 140.0 to 205.0, 150.0 to 260.0))
        val position = mapOf("c" to 1, "t" to 0, "z" to 5)

        val spec = annotationSpecFor(drawn, ctzyx, render, position)
        val back = prefillShapeFor(annotation(spec.kind, spec.vectors, spec.coordinates), ctzyx, render)!!

        assertEquals(drawn.points, back.shape.points)
        assertEquals(position, back.position)
        assertEquals(AnnotationKind.POLYGON, back.shape.kind)
    }

    @Test
    fun `x and y come out of their own slots, not slots 0 and 1`() {
        // For a (c,t,z,y,x) collection, components 0 and 1 are CHANNEL and TIME. Reading them as
        // the drawn coordinates puts a shape drawn at (100, 200) at (1, 0) — on the image, in
        // range, and completely wrong.
        val vectors = listOf(listOf(1.0, 0.0, 5.0, 200.0, 100.0))
        val back = prefillShapeFor(annotation(AnnotationKind.POINT, vectors), ctzyx, render)!!

        assertEquals(listOf(100.0 to 200.0), back.shape.points)
    }

    @Test
    fun `a collection whose axes run the other way is decoded by name, not by position`() {
        // Same shape, a collection that declares (x, y, z, t, c). Nothing about the vectors says
        // which order they are in — only the collection's own axes do.
        val xyztc = axes(
            "x" to AxisType.SPACE,
            "y" to AxisType.SPACE,
            "z" to AxisType.SPACE,
            "t" to AxisType.TIME,
            "c" to AxisType.CHANNEL,
        )
        val vectors = listOf(listOf(100.0, 200.0, 5.0, 0.0, 1.0))
        val back = prefillShapeFor(annotation(AnnotationKind.POINT, vectors), xyztc, resolveRenderAxes(xyztc))!!

        assertEquals(listOf(100.0 to 200.0), back.shape.points)
        assertEquals(mapOf("z" to 5, "t" to 0, "c" to 1), back.position)
    }

    // --- the axis cross-check ------------------------------------------------------------------

    @Test
    fun `a collection that disagrees with the lens about x and y is refused`() {
        // A collection drawn over (x, q) against a lens rendering (x, y): `resolveRenderAxes` falls
        // back to the positional convention for an unrecognised spatial set, so it answers — with
        // an answer that is not this lens'. Decoding anyway would transpose every shape.
        val foreign = axes("y" to AxisType.SPACE, "q" to AxisType.SPACE)

        assertFailsWith<IllegalArgumentException> { collectionRenderAxes(foreign, render) }
    }

    @Test
    fun `a collection with the lens' own axes passes the cross-check`() {
        assertEquals(render, collectionRenderAxes(ctzyx, render))
    }

    // --- bounding boxes ------------------------------------------------------------------------

    @Test
    fun `an inclusive far corner comes back as the count it was drawn with`() {
        // x=10,w=5 is stored as corners 10 and 14. Reading 14 - 10 gives 4, and every save-reopen
        // cycle would shave another pixel off the shape.
        val drawn = boundsShape(AnnotationKind.RECTANGLE, 10.0, 20.0, 5.0, 7.0)
        val box = boundsRectOf(drawn.points)

        assertEquals(BoundsRect(10, 20, 5, 7), box)
    }

    @Test
    fun `corners are ordered, so a box dragged up-and-left keeps a positive extent`() {
        // Stored as drawn, far corner first. IJ1 refuses a negative width.
        val box = boundsRectOf(listOf(14.0 to 26.0, 10.0 to 20.0))

        assertEquals(BoundsRect(10, 20, 5, 7), box)
    }

    // --- pins ---------------------------------------------------------------------------------

    @Test
    fun `pins go back out 1-based, and an unknown axis stays unset`() {
        // IJ1 numbers from 1 and reads 0 as "unset". Writing a known z=0 as 0 makes the ROI claim
        // no position, and the next poll pins it to wherever the viewer happens to be.
        val position = ij1PositionFor(mapOf("c" to 1, "z" to 0), render)

        assertEquals(Ij1Position(c = 2, z = 1, t = 0), position)
    }

    @Test
    fun `an IJ1 position round-trips through the pins it was built from`() {
        val position = mapOf("c" to 1, "z" to 5, "t" to 2)

        assertEquals(position, ij1Pins(ij1PositionFor(position, render), render))
    }

    @Test
    fun `an axis with no pin falls back to the value the vectors agree on`() {
        // What this plugin writes for a non-render axis is the same constant in every vector, so a
        // collection written by something that omits `coordinates` is still placeable.
        val vectors = listOf(
            listOf(1.0, 0.0, 5.0, 200.0, 100.0),
            listOf(1.0, 0.0, 5.0, 205.0, 140.0),
        )
        val back = prefillShapeFor(annotation(AnnotationKind.LINE, vectors), ctzyx, render)!!

        assertEquals(mapOf("c" to 1, "t" to 0, "z" to 5), back.position)
    }

    @Test
    fun `an axis the shape genuinely spans is left unpinned rather than guessed`() {
        val vectors = listOf(
            listOf(1.0, 0.0, 4.0, 200.0, 100.0),
            listOf(1.0, 0.0, 6.0, 205.0, 140.0),
        )
        val back = prefillShapeFor(annotation(AnnotationKind.LINE, vectors), ctzyx, render)!!

        assertEquals(mapOf("c" to 1, "t" to 0), back.position)
        assertEquals(Ij1Position(c = 2, z = 0, t = 1), ij1PositionFor(back.position, render))
    }

    // --- what cannot be drawn -------------------------------------------------------------------

    @Test
    fun `volumetric kinds are skipped rather than flattened`() {
        val corners = listOf(
            listOf(0.0, 0.0, 2.0, 20.0, 10.0),
            listOf(0.0, 0.0, 6.0, 26.0, 14.0),
        )
        for (kind in VOLUMETRIC_KINDS) {
            assertNull(prefillShapeFor(annotation(kind, corners), ctzyx, render), "$kind should not be drawn")
        }
    }

    @Test
    fun `a vector of the wrong width is not indexed against these axes`() {
        // A foreign writer's shape over a different rank: component 4 is not this collection's x.
        assertNull(prefillShapeFor(annotation(AnnotationKind.POINT, listOf(listOf(100.0, 200.0))), ctzyx, render))
    }

    @Test
    fun `a shape with too few vertices for its kind is dropped, not sent to a viewer`() {
        val oneCorner = listOf(listOf(0.0, 0.0, 0.0, 20.0, 10.0))
        assertNull(prefillShapeFor(annotation(AnnotationKind.RECTANGLE, oneCorner), ctzyx, render))
        assertTrue(prefillShapeFor(annotation(AnnotationKind.POINT, oneCorner), ctzyx, render) != null)
    }
}
