package com.mycompany.arkitekt

import com.mycompany.mikro.graphql.type.AxisType
import net.imagej.ImageJ
import net.imagej.axis.Axes
import ucar.ma2.DataType as UcarDataType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Reading a mikro `Lens` — the per-axis selection that is the unit of "look at this data".
 *
 * The thing under test is not the S3 round-trip but the axis algebra around it, which is where a
 * v1-shaped assumption survives silently: mikro v2 imposes no canonical axis order, so any code
 * that indexes axes by position produces a plausible image of the wrong pixels. Every case here is
 * one that would come back looking fine.
 */
class LensViewTest {

    private fun axes(vararg spec: Pair<String, AxisType>) = spec.map { AxisSpec(it.first, it.second) }

    private val ctzyx = axes(
            "c" to AxisType.CHANNEL,
            "t" to AxisType.TIME,
            "z" to AxisType.SPACE,
            "y" to AxisType.SPACE,
            "x" to AxisType.SPACE,
    )

    private fun view(
            axes: List<AxisSpec>,
            storeShape: List<Int>,
            slices: List<SliceSpec> = emptyList(),
            render: RenderAxisNames = resolveRenderAxes(axes),
            serverShape: List<Int>? = null,
    ) = buildLensView("store-1", axes, storeShape, slices, render, "test", serverShape)

    // --- render axes ------------------------------------------------------------------------

    @Test
    fun `spatial axes named for the screen bind by name, not by position`() {
        // (x, y, z) is well-formed and means what it says. Binding by position would derive
        // x = z and z = x — transposed, silently, with nothing to raise on.
        val render = resolveRenderAxes(axes("x" to AxisType.SPACE, "y" to AxisType.SPACE, "z" to AxisType.SPACE))
        assertEquals("x", render.x)
        assertEquals("y", render.y)
        assertEquals("z", render.z)
    }

    @Test
    fun `unrecognised spatial names fall back wholly to position`() {
        // (x, y, q) matches neither screen-named set, so ALL THREE go positional. Binding the two
        // recognised names and leaving q positional would let q and x both claim x.
        val render = resolveRenderAxes(axes("x" to AxisType.SPACE, "y" to AxisType.SPACE, "q" to AxisType.SPACE))
        assertEquals("q", render.x)
        assertEquals("y", render.y)
        assertEquals("x", render.z)
    }

    @Test
    fun `screen axes bind through their long names too`() {
        val render = resolveRenderAxes(axes(
                "depth" to AxisType.SPACE,
                "height" to AxisType.SPACE,
                "width" to AxisType.SPACE,
        ))
        assertEquals("width", render.x)
        assertEquals("height", render.y)
        assertEquals("depth", render.z)
    }

    @Test
    fun `an untyped channel axis is still found by its name`() {
        // A writer that left the channel axis as INDEX. Type is asked first and has no answer,
        // so the name supplies one — which is the whole point of using both signals.
        val render = resolveRenderAxes(axes(
                "c" to AxisType.INDEX,
                "z" to AxisType.SPACE,
                "y" to AxisType.SPACE,
                "x" to AxisType.SPACE,
        ))
        assertEquals("c", render.intensity)
        assertEquals("x", render.x)
    }

    @Test
    fun `type beats name - an axis called t but typed SPACE is spatial, not the time axis`() {
        // Candidacy is a fact about the data; a name cannot override it. And `t` must not be
        // claimed twice — once as a spatial axis and once as the time axis.
        val render = resolveRenderAxes(axes(
                "t" to AxisType.SPACE,
                "y" to AxisType.SPACE,
                "x" to AxisType.SPACE,
        ))
        assertEquals(null, render.t)
        assertEquals("t", render.z)   // {t,y,x} is not a screen-named set, so it goes positional
        assertEquals("x", render.x)
    }

    @Test
    fun `time and channel axes are found by type, wherever they sit`() {
        val render = resolveRenderAxes(axes(
                "z" to AxisType.SPACE,
                "c" to AxisType.CHANNEL,
                "y" to AxisType.SPACE,
                "x" to AxisType.SPACE,
        ))
        assertEquals("c", render.intensity)
        assertEquals(null, render.t)
        assertEquals("x", render.x)
        assertEquals("z", render.z)
    }

    @Test
    fun `a source with fewer than two spatial axes is not renderable`() {
        assertFailsWith<IllegalArgumentException> {
            resolveRenderAxes(axes("t" to AxisType.TIME, "x" to AxisType.SPACE))
        }
    }

    // --- slice resolution -------------------------------------------------------------------

    @Test
    fun `an unsliced axis falls back to its full extent`() {
        val v = view(ctzyx, listOf(3, 10, 16, 64, 64), listOf(SliceSpec("z", 4, 8, null)))

        assertContentEquals(longArrayOf(0, 0, 4, 0, 0), v.offset)
        assertContentEquals(longArrayOf(3, 10, 4, 64, 64), v.extent)
        assertContentEquals(longArrayOf(3, 10, 4, 64, 64), v.count)
    }

    @Test
    fun `a stepped slice reads the whole box but counts only what survives the stride`() {
        // The box is what zarr reads (it has no stride); the count is what the image ends up
        // being. Conflating them is how a stepped lens comes back the wrong size.
        val v = view(ctzyx, listOf(3, 10, 16, 64, 64), listOf(SliceSpec("z", 0, 9, 2)))

        assertEquals(9L, v.extent[2])
        assertEquals(5L, v.count[2])   // len(range(0, 9, 2))
        assertEquals(2, v.step[2])
    }

    @Test
    fun `negative bounds resolve from the end, as they do on the server`() {
        val v = view(ctzyx, listOf(3, 10, 16, 64, 64), listOf(SliceSpec("z", -4, null, null)))
        assertEquals(12L, v.offset[2])
        assertEquals(4L, v.count[2])
    }

    @Test
    fun `an out-of-range stop clamps rather than overruns`() {
        val v = view(ctzyx, listOf(3, 10, 16, 64, 64), listOf(SliceSpec("z", 14, 999, null)))
        assertEquals(2L, v.count[2])
    }

    @Test
    fun `a negative step is refused, loudly`() {
        // zarr can only read forward. Silently dropping the reversal would flip an axis.
        val failure = assertFailsWith<IllegalArgumentException> {
            view(ctzyx, listOf(3, 10, 16, 64, 64), listOf(SliceSpec("z", null, null, -1)))
        }
        assertTrue(failure.message!!.contains("negative step"), failure.message!!)
    }

    @Test
    fun `a slice naming an axis the source does not have is refused`() {
        assertFailsWith<IllegalArgumentException> {
            view(ctzyx, listOf(3, 10, 16, 64, 64), listOf(SliceSpec("tau", 0, 1, null)))
        }
    }

    // --- the server cross-check -------------------------------------------------------------

    @Test
    fun `a shape that disagrees with the server's is refused rather than read`() {
        // The one check that turns "wrong pixels, displayed as if right" into a loud failure.
        val failure = assertFailsWith<IllegalArgumentException> {
            view(
                    ctzyx,
                    listOf(3, 10, 16, 64, 64),
                    listOf(SliceSpec("z", 4, 8, null)),
                    serverShape = listOf(3, 10, 99, 64, 64),
            )
        }
        assertTrue(failure.message!!.contains("disagrees with the server"), failure.message!!)
    }

    @Test
    fun `a shape that agrees with the server's passes`() {
        val v = view(
                ctzyx,
                listOf(3, 10, 16, 64, 64),
                listOf(SliceSpec("z", 4, 8, null)),
                serverShape = listOf(3, 10, 4, 64, 64),
        )
        assertEquals(5, v.rank)
    }

    // --- permutation ------------------------------------------------------------------------

    @Test
    fun `the canonical c,t,z,y,x store permutes to ImageJ's x,y,z,c,t`() {
        val v = view(ctzyx, listOf(3, 10, 16, 64, 64))
        // indices into (c,t,z,y,x): x=4, y=3, z=2, c=0, t=1
        assertContentEquals(intArrayOf(4, 3, 2, 0, 1), imageJDimOrder(v))
    }

    @Test
    fun `a non-canonical z,c,y,x store still puts x first`() {
        // The case v1 could not express and v2 makes legal. Position-indexing this layout reads
        // the channel axis as x.
        val zcyx = axes(
                "z" to AxisType.SPACE,
                "c" to AxisType.CHANNEL,
                "y" to AxisType.SPACE,
                "x" to AxisType.SPACE,
        )
        val v = view(zcyx, listOf(16, 3, 64, 64))
        val order = imageJDimOrder(v)
        assertEquals(3, order[0])   // x
        assertEquals(2, order[1])   // y
        assertEquals(0, order[2])   // z
        assertEquals(1, order[3])   // c (t is absent)
        assertEquals(4, order.size)
    }

    @Test
    fun `an axis the renderer does not name is kept, in array order, after the ones it does`() {
        val flim = axes(
                "tau" to AxisType.MICROTIME,
                "z" to AxisType.SPACE,
                "y" to AxisType.SPACE,
                "x" to AxisType.SPACE,
        )
        val v = view(flim, listOf(32, 16, 64, 64))
        assertContentEquals(intArrayOf(3, 2, 1, 0), imageJDimOrder(v))
        // and it reaches the viewer as a named axis rather than being an error
        assertEquals(Axes.get("tau"), imageJAxisTypeFor("tau", v.render))
        assertEquals(Axes.X, imageJAxisTypeFor("x", v.render))
    }

    @Test
    fun `the permutation is a no-op unless the array carries BOTH a channel and a time axis`() {
        // zarr/numpy order is slowest-first (..., z, y, x): x is LAST and fastest-varying.
        // ImgLib2 order is x FIRST, and dimension 0 is fastest-varying. The two conventions flip
        // together, so for an ordinary layout the desired ImageJ order is the exact reverse of the
        // array order and the flat memory layout already matches — permute() is the identity and
        // no bytes move.
        //
        // The one layout where that breaks is (c, t, ...): reversed it would be x,y,z,t,c, but
        // ImageJ (and this plugin's own writer) wants x,y,z,c,t. That c<->t swap is the ONLY real
        // reshuffle, and it is exactly the case the old hard-coded loader got wrong.
        fun permutation(axes: List<AxisSpec>, shape: List<Int>): List<Int> =
                imageJDimOrder(view(axes, shape)).reversedArray().toList()

        val identity3 = listOf(0, 1, 2)
        assertEquals(identity3, permutation(
                axes("z" to AxisType.SPACE, "y" to AxisType.SPACE, "x" to AxisType.SPACE),
                listOf(4, 5, 6)))
        assertEquals(identity3, permutation(
                axes("c" to AxisType.CHANNEL, "y" to AxisType.SPACE, "x" to AxisType.SPACE),
                listOf(4, 5, 6)))
        assertEquals(identity3, permutation(
                axes("t" to AxisType.TIME, "y" to AxisType.SPACE, "x" to AxisType.SPACE),
                listOf(4, 5, 6)))
        assertEquals(listOf(0, 1, 2, 3), permutation(
                axes("c" to AxisType.CHANNEL, "z" to AxisType.SPACE, "y" to AxisType.SPACE, "x" to AxisType.SPACE),
                listOf(3, 4, 5, 6)))

        // ...and the one that is not: c and t swap, everything else stays put.
        assertEquals(listOf(1, 0, 2, 3, 4), permutation(ctzyx, listOf(2, 3, 4, 5, 6)))
    }

    // --- the stride ---------------------------------------------------------------------------

    @Test
    fun `applyStride selects every step-th element and keeps the rank`() {
        // This is the check that section() vs sectionNoReduce() decides, and the one place the
        // meaning of sectionNoReduce's `shape` argument (result count, not source extent) is
        // pinned down rather than assumed.
        val shape = intArrayOf(1, 1, 9, 1, 4)     // c, t, z, y, x — two length-1 dims on purpose
        val box = ucar.ma2.Array.factory(UcarDataType.INT, shape)
        val index = box.index
        for (z in 0 until 9) for (x in 0 until 4) {
            box.setInt(index.set(0, 0, z, 0, x), z * 10 + x)
        }

        val v = view(ctzyx, listOf(1, 1, 9, 1, 4), listOf(SliceSpec("z", 0, 9, 2)))
        val strided = applyStride(box, v)

        assertContentEquals(intArrayOf(1, 1, 5, 1, 4), strided.shape)  // rank preserved

        val out = strided.index
        for (i in 0 until 5) for (x in 0 until 4) {
            assertEquals(((i * 2) * 10 + x), strided.getInt(out.set(0, 0, i, 0, x)), "i=$i x=$x")
        }
    }

    @Test
    fun `applyStride is a no-op when nothing is stepped`() {
        val shape = intArrayOf(2, 3, 4, 5, 6)
        val box = ucar.ma2.Array.factory(UcarDataType.INT, shape)
        val strided = applyStride(box, view(ctzyx, shape.toList()))
        assertContentEquals(shape, strided.shape)
    }

    // --- end to end, over a real ucar array and a real ImageJ context ------------------------

    private val ij: ImageJ by lazy {
        System.setProperty("java.awt.headless", "true")
        ImageJ()
    }

    private fun app(): App = App(
            Mikro(localAlias(1), fixedTokenManager()),
            Datalayer(localAlias(1), Mikro(localAlias(1), fixedTokenManager())),
            Unlok(localAlias(1), fixedTokenManager()),
            Rekuest(localAlias(1), fixedTokenManager()),
            ij.ui(),
            ij.dataset(),
            ij.imageDisplay(),
    )

    @Test
    fun `buildDataset labels every dimension and lays them out x, y, z, channel, time`() {
        val shape = intArrayOf(2, 3, 4, 5, 6)   // c, t, z, y, x
        val array = ucar.ma2.Array.factory(UcarDataType.USHORT, shape)
        val v = view(ctzyx, shape.toList())

        val dataset = buildDataset(app(), array, v)

        assertEquals(5, dataset.numDimensions())
        assertContentEquals(
                listOf(Axes.X, Axes.Y, Axes.Z, Axes.CHANNEL, Axes.TIME),
                (0 until dataset.numDimensions()).map { dataset.axis(it).type() },
        )
        assertContentEquals(
                listOf(6L, 5L, 4L, 2L, 3L),
                (0 until dataset.numDimensions()).map { dataset.dimension(it) },
        )
    }

    @Test
    fun `a lens pinning a single channel keeps its rank`() {
        // The case that decides section vs sectionNoReduce: a length-1 dimension must survive,
        // or the permutation reorders the wrong axes.
        val shape = intArrayOf(1, 3, 4, 5, 6)
        val array = ucar.ma2.Array.factory(UcarDataType.USHORT, shape)
        val v = view(ctzyx, listOf(2, 3, 4, 5, 6), listOf(SliceSpec("c", 0, 1, null)))

        val dataset = buildDataset(app(), array, v)

        assertEquals(5, dataset.numDimensions())
        assertEquals(1L, dataset.dimension(3))   // the channel axis, still there
        assertEquals(Axes.CHANNEL, dataset.axis(3).type())
    }

    @Test
    fun `values land in the right cells under the permutation`() {
        // A flat iterator zip is only correct because of the permute; this pins the pixel that
        // the old c,t,z,y,x code put in the wrong place whenever channels > 1 AND time > 1.
        val shape = intArrayOf(2, 3, 1, 2, 2)   // c, t, z, y, x
        val array = ucar.ma2.Array.factory(UcarDataType.INT, shape)
        val index = array.index
        // value = c*1000 + t*100 + y*10 + x
        for (c in 0 until 2) for (t in 0 until 3) for (y in 0 until 2) for (x in 0 until 2) {
            array.setInt(index.set(c, t, 0, y, x), c * 1000 + t * 100 + y * 10 + x)
        }

        val dataset = buildDataset(app(), array, view(ctzyx, shape.toList()))

        // ImageJ order is x, y, z, c, t
        val access = dataset.randomAccess()
        for (c in 0 until 2) for (t in 0 until 3) for (y in 0 until 2) for (x in 0 until 2) {
            access.setPosition(intArrayOf(x, y, 0, c, t))
            assertEquals(
                    (c * 1000 + t * 100 + y * 10 + x).toDouble(),
                    access.get().realDouble,
                    "c=$c t=$t y=$y x=$x",
            )
        }
    }
}
