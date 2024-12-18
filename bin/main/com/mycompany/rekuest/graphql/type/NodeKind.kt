//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.rekuest.graphql.type

import com.apollographql.apollo.api.EnumType
import kotlin.Array
import kotlin.Deprecated
import kotlin.String
import kotlin.collections.List

public enum class NodeKind(
  public val rawValue: String,
) {
  FUNCTION("FUNCTION"),
  GENERATOR("GENERATOR"),
  /**
   * Auto generated constant for unknown enum values
   */
  UNKNOWN__("UNKNOWN__"),
  ;

  public companion object {
    public val type: EnumType = EnumType("NodeKind", listOf("FUNCTION", "GENERATOR"))

    /**
     * All [NodeKind] known at compile time
     */
    public val knownEntries: List<NodeKind>
      get() = listOf(
        FUNCTION,
        GENERATOR)

    /**
     * Returns all [NodeKind] known at compile time
     */
    @Deprecated(
      message = "Use knownEntries instead",
      replaceWith = ReplaceWith("this.knownEntries"),
    )
    public fun knownValues(): Array<NodeKind> = knownEntries.toTypedArray()

    /**
     * Returns the [NodeKind] that represents the specified [rawValue].
     * Note: unknown values of [rawValue] will return [UNKNOWN__]. You may want to update your
     * schema instead of calling this function directly.
     */
    public fun safeValueOf(rawValue: String): NodeKind = entries.find { it.rawValue == rawValue } ?:
        UNKNOWN__
  }
}
