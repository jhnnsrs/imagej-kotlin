package com.mycompany.arkitekt

import com.mycompany.mikro.graphql.type.AxisType
import net.imagej.axis.Axes
import net.imagej.axis.AxisType as ImageJAxisType

// Reading a mikro `Lens` — "a selection over a dataset, nothing else" — into ImageJ.
//
// Everything here is pure: no network, no S3, no ImageJ context. The IO half lives in
// `Arkitekt.kt` (`loadLensView`), which opens the store and hands the bytes to `buildDataset`.
//
// THE AXIS RULES THIS FILE ENCODES (mikro v2), because they are not what the old code assumed:
//
//  * There is no canonical axis order. `Axis.order` IS the store's dimension order, and
//    `(z,c,y,x)` is as legal as `(c,t,z,y,x)`. Nothing may index axes by position.
//  * A selection never drops or reorders an axis, so a lens' axis list and its dataset's are
//    the same list — only the extents differ.
//  * Which axis is screen x/y/z/time/channel is DERIVED, from the axis names and types together
//    (`resolveRenderAxes`). `Lens.renderAxes` is deprecated and deliberately not queried.

/** One axis of a source, in array order: its name and its semantic type. */
data class AxisSpec(val name: String, val type: AxisType)

/** Which array axis a renderer maps to screen x, y, z, time and intensity. */
data class RenderAxisNames(
    val x: String,
    val y: String,
    val z: String?,
    val t: String?,
    val intensity: String?,
)

/** One `Lens.slices` entry: a selection along a named axis. All three bounds are optional. */
data class SliceSpec(val axis: String, val start: Int?, val stop: Int?, val step: Int?)

/**
 * A rectangular selection ready to be read: where to start, how much to read, and how to give
 * the result ImageJ axes.
 *
 * `extent` is the size of the BOX to pull out of the store (`stop - start`), which is what
 * zarr's `read(offset, shape)` wants. `count` is how many elements survive the stride, which
 * is what the resulting image is actually shaped like. The two differ exactly when `step > 1`.
 */
class LensView(
    val storeId: String,
    val axes: List<AxisSpec>,
    val offset: LongArray,
    val extent: LongArray,
    val step: IntArray,
    val count: LongArray,
    val render: RenderAxisNames,
    val name: String,
) {
    val rank: Int get() = axes.size
}

/** The spatial axes, in array order. Candidacy is decided by TYPE alone. */
private fun spatialAxes(axes: List<AxisSpec>): List<AxisSpec> = axes.filter { it.type == AxisType.SPACE }

/** The screen-axis name sets that bind by name. Two and three, and nothing in between. */
private val SCREEN_AXIS_NAMES = listOf(setOf("x", "y"), setOf("x", "y", "z"))

/** What a spatial axis may be called to count as that screen axis. */
private val SCREEN_ALIASES = mapOf(
        "x" to "x", "y" to "y", "z" to "z",
        "width" to "x", "height" to "y", "depth" to "z",
)

/** Names a TIME axis may go by, when its type does not already say so. */
private val TIME_NAMES = setOf("t", "time", "frame")

/** Names a CHANNEL axis may go by, when its type does not already say so. */
private val CHANNEL_NAMES = setOf("c", "channel", "ch")

/**
 * Derive which array axis maps to screen x, y, z, time and intensity, from the axis **names and
 * types together**.
 *
 * `Lens.renderAxes` is deprecated, so this is the answer — not a fallback, and not a mirror of a
 * server field. Both signals are load-bearing and neither alone is enough:
 *
 *  * **Type decides candidacy.** Which axes are spatial at all, and which single axis is the time
 *    or the channel axis, is a fact about the data that a name cannot override. An axis typed
 *    SPACE but called `t` is a spatial axis.
 *  * **Name decides which spatial axis is which.** Position alone cannot: `(z, y, x)` and
 *    `(x, y, z)` are both well-formed, only one is meant, and reading the second positionally
 *    derives `x = z, z = x` — transposed, silently, with nothing to raise on. So when the spatial
 *    axes are exactly the screen axes — `{x, y}` or `{x, y, z}`, aliases allowed — each one is the
 *    axis it is called.
 *
 * **All-or-nothing, and that is the whole subtlety.** A spatial set like `(x, y, q)` matches
 * neither, so it falls back *wholly* to the array convention: the last spatial axis is x, the
 * second-to-last y, the third-to-last z. Binding the two recognised names and leaving `q`
 * positional would let `q` and `x` both claim x — inconsistent, which is worse than merely
 * conventional.
 *
 * Time and channel are found by type first and by name only as a fallback, so a store that types
 * its axes properly is never second-guessed, and one that leaves everything SPACE (or unset) still
 * gets a usable answer out of `t`/`c`.
 */
fun resolveRenderAxes(axes: List<AxisSpec>): RenderAxisNames {
    val spatial = spatialAxes(axes)
    require(spatial.size >= 2) {
        "A renderable coordinate system needs at least two spatial axes, got ${spatial.map { it.name }}"
    }

    // Map each spatial axis onto the screen axis its name claims, if any.
    val claimed = spatial.mapNotNull { axis -> SCREEN_ALIASES[axis.name.lowercase()]?.let { it to axis.name } }.toMap()
    val screenNamed = claimed.size == spatial.size && SCREEN_AXIS_NAMES.any { it == claimed.keys }

    val x: String
    val y: String
    val z: String?
    if (screenNamed) {
        x = claimed.getValue("x")
        y = claimed.getValue("y")
        z = claimed["z"]
    } else {
        x = spatial[spatial.size - 1].name
        y = spatial[spatial.size - 2].name
        z = if (spatial.size >= 3) spatial[spatial.size - 3].name else null
    }

    val spatialNames = spatial.map { it.name }.toSet()
    fun byTypeThenName(type: AxisType, names: Set<String>): String? =
            axes.firstOrNull { it.type == type }?.name
                    ?: axes.firstOrNull { it.name.lowercase() in names && it.name !in spatialNames }?.name

    return RenderAxisNames(
            x = x,
            y = y,
            z = z,
            t = byTypeThenName(AxisType.TIME, TIME_NAMES),
            intensity = byTypeThenName(AxisType.CHANNEL, CHANNEL_NAMES),
    )
}

/**
 * Resolve one axis' slice against its size, with Python's own `slice(...).indices(size)`
 * semantics — which is what the server uses (`core.logic.coords.lens_shape`), so negatives,
 * omitted bounds and out-of-range stops resolve identically on both sides.
 *
 * Returns `(start, stop, step, count)` where `count == len(range(start, stop, step))`.
 */
internal fun resolveSlice(slice: SliceSpec?, size: Int): IntArray {
    val step = slice?.step ?: 1
    require(step != 0) { "Slice on axis '${slice?.axis}' has step 0" }
    require(step > 0) {
        "Slice on axis '${slice?.axis}' has a negative step ($step), which reverses the axis. " +
                "The zarr reader can only read forward, so such a lens cannot be displayed."
    }

    fun clamp(bound: Int?, default: Int): Int = when {
        bound == null -> default
        bound < 0 -> maxOf(0, size + bound)
        else -> minOf(bound, size)
    }

    val start = clamp(slice?.start, 0)
    val stop = clamp(slice?.stop, size)
    val span = maxOf(0, stop - start)
    val count = (span + step - 1) / step
    return intArrayOf(start, start + span, step, count)
}

/**
 * Turn a store shape plus a lens' slices into a readable [LensView].
 *
 * `serverShape` is `Lens.shape` — what the server says the slices cut out. It is compared
 * against what we derived and a mismatch throws, naming both. This is deliberately a hard
 * failure: the alternative is reading the wrong pixels and displaying them as if they were
 * right, which no one would notice.
 */
fun buildLensView(
    storeId: String,
    axes: List<AxisSpec>,
    storeShape: List<Int>,
    slices: List<SliceSpec>,
    render: RenderAxisNames,
    name: String,
    serverShape: List<Int>? = null,
): LensView {
    require(axes.size == storeShape.size) {
        "The source has ${axes.size} axes ${axes.map { it.name }} but its store has rank ${storeShape.size} $storeShape"
    }

    val byAxis = slices.associateBy { it.axis }
    val unknown = slices.map { it.axis }.filterNot { name -> axes.any { it.name == name } }
    require(unknown.isEmpty()) {
        "Slices name axes ${unknown} that are not axes of this source (${axes.map { it.name }})"
    }

    val offset = LongArray(axes.size)
    val extent = LongArray(axes.size)
    val step = IntArray(axes.size)
    val count = LongArray(axes.size)

    axes.forEachIndexed { i, axis ->
        val resolved = resolveSlice(byAxis[axis.name], storeShape[i])
        offset[i] = resolved[0].toLong()
        extent[i] = (resolved[1] - resolved[0]).toLong()
        step[i] = resolved[2]
        count[i] = resolved[3].toLong()
    }

    if (serverShape != null) {
        val derived = count.map { it.toInt() }
        require(derived == serverShape) {
            "Derived selection shape $derived disagrees with the server's shape $serverShape " +
                    "for axes ${axes.map { it.name }} (slices: $slices). Refusing to read."
        }
    }

    return LensView(storeId, axes, offset, extent, step, count, render, name)
}

/**
 * The order ImageJ wants the dimensions in — X, Y, Z, CHANNEL, TIME, then whatever is left in
 * array order — as indices into [LensView.axes].
 *
 * x,y,z,c,t rather than the XYCZT of ImageJ1/Bio-Formats, because that is the layout this
 * plugin's own writer round-trips (`imgPlusToCTZYXUcarArray` builds c,t,z,y,x, whose reverse
 * this is). Diverging here would transpose every image the plugin itself uploaded.
 *
 * Anything not named by the render axes keeps its relative array order and lands after the
 * five ImageJ knows about. That is how a MICROTIME or SPECTRUM axis survives into the viewer
 * instead of being an error.
 */
fun imageJDimOrder(view: LensView): IntArray {
    val names = view.axes.map { it.name }
    val ordered = ArrayList<Int>(names.size)

    for (axisName in listOf(view.render.x, view.render.y, view.render.z, view.render.intensity, view.render.t)) {
        if (axisName == null) continue
        val index = names.indexOf(axisName)
        require(index >= 0) { "Render axis '$axisName' is not an axis of this source ($names)" }
        if (index !in ordered) ordered.add(index)
    }
    names.indices.forEach { if (it !in ordered) ordered.add(it) }

    return ordered.toIntArray()
}

/**
 * The ImageJ axis type for one array axis, resolved BY NAME against the render axes rather
 * than by position. An axis the renderer does not name gets a custom named axis, which ImageJ
 * supports and which keeps a FLIM or spectral axis labelled rather than rejected.
 */
fun imageJAxisTypeFor(axisName: String, render: RenderAxisNames): ImageJAxisType = when (axisName) {
    render.x -> Axes.X
    render.y -> Axes.Y
    render.z -> Axes.Z
    render.t -> Axes.TIME
    render.intensity -> Axes.CHANNEL
    else -> Axes.get(axisName)
}

/**
 * Subsample a read box down to the elements a stepped lens actually selects.
 *
 * `sectionNoReduce`, NOT `section`: section drops length-1 dimensions, and a lens pinning a
 * single channel or a single z-plane is the most ordinary lens there is. Losing its rank here
 * would make [imageJDimOrder]'s permutation address the wrong axes.
 *
 * The `shape` argument counts elements in the RESULT, not in the source — hence [LensView.count]
 * rather than [LensView.extent].
 */
fun applyStride(box: ucar.ma2.Array, view: LensView): ucar.ma2.Array {
    if (view.step.all { it == 1 }) return box
    return box.sectionNoReduce(
            IntArray(view.rank),
            view.count.map { it.toInt() }.toIntArray(),
            view.step,
    )
}
