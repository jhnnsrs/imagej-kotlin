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

public enum class AssignWidgetKind(
  public val rawValue: String,
) {
  SEARCH("SEARCH"),
  CHOICE("CHOICE"),
  SLIDER("SLIDER"),
  CUSTOM("CUSTOM"),
  STRING("STRING"),
  STATE_CHOICE("STATE_CHOICE"),
  /**
   * Auto generated constant for unknown enum values
   */
  UNKNOWN__("UNKNOWN__"),
  ;

  public companion object {
    public val type: EnumType = EnumType("AssignWidgetKind", listOf("SEARCH", "CHOICE", "SLIDER",
        "CUSTOM", "STRING", "STATE_CHOICE"))

    /**
     * All [AssignWidgetKind] known at compile time
     */
    public val knownEntries: List<AssignWidgetKind>
      get() = listOf(
        SEARCH,
        CHOICE,
        SLIDER,
        CUSTOM,
        STRING,
        STATE_CHOICE)

    /**
     * Returns all [AssignWidgetKind] known at compile time
     */
    @Deprecated(
      message = "Use knownEntries instead",
      replaceWith = ReplaceWith("this.knownEntries"),
    )
    public fun knownValues(): Array<AssignWidgetKind> = knownEntries.toTypedArray()

    /**
     * Returns the [AssignWidgetKind] that represents the specified [rawValue].
     * Note: unknown values of [rawValue] will return [UNKNOWN__]. You may want to update your
     * schema instead of calling this function directly.
     */
    public fun safeValueOf(rawValue: String): AssignWidgetKind =
        entries.find { it.rawValue == rawValue } ?: UNKNOWN__
  }
}