//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.mikro.graphql.type

import com.apollographql.apollo.api.EnumType
import kotlin.Array
import kotlin.Deprecated
import kotlin.String
import kotlin.collections.List

public enum class ColorMap(
  public val rawValue: String,
) {
  VIRIDIS("VIRIDIS"),
  PLASMA("PLASMA"),
  INFERNO("INFERNO"),
  MAGMA("MAGMA"),
  RED("RED"),
  GREEN("GREEN"),
  BLUE("BLUE"),
  INTENSITY("INTENSITY"),
  /**
   * Auto generated constant for unknown enum values
   */
  UNKNOWN__("UNKNOWN__"),
  ;

  public companion object {
    public val type: EnumType = EnumType("ColorMap", listOf("VIRIDIS", "PLASMA", "INFERNO", "MAGMA",
        "RED", "GREEN", "BLUE", "INTENSITY"))

    /**
     * All [ColorMap] known at compile time
     */
    public val knownEntries: List<ColorMap>
      get() = listOf(
        VIRIDIS,
        PLASMA,
        INFERNO,
        MAGMA,
        RED,
        GREEN,
        BLUE,
        INTENSITY)

    /**
     * Returns all [ColorMap] known at compile time
     */
    @Deprecated(
      message = "Use knownEntries instead",
      replaceWith = ReplaceWith("this.knownEntries"),
    )
    public fun knownValues(): Array<ColorMap> = knownEntries.toTypedArray()

    /**
     * Returns the [ColorMap] that represents the specified [rawValue].
     * Note: unknown values of [rawValue] will return [UNKNOWN__]. You may want to update your
     * schema instead of calling this function directly.
     */
    public fun safeValueOf(rawValue: String): ColorMap = entries.find { it.rawValue == rawValue } ?:
        UNKNOWN__
  }
}
