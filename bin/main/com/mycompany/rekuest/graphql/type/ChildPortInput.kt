//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.rekuest.graphql.type

import com.apollographql.apollo.api.Optional
import kotlin.Any
import kotlin.Boolean
import kotlin.String
import kotlin.collections.List

public data class ChildPortInput(
  public val default: Optional<Any?> = Optional.Absent,
  public val key: String,
  public val label: Optional<String?> = Optional.Absent,
  public val kind: PortKind,
  public val scope: PortScope,
  public val description: Optional<String?> = Optional.Absent,
  public val identifier: Optional<Any?> = Optional.Absent,
  public val nullable: Boolean,
  public val children: Optional<List<ChildPortInput>?> = Optional.Absent,
  public val effects: Optional<List<EffectInput>?> = Optional.Absent,
  public val assignWidget: Optional<AssignWidgetInput?> = Optional.Absent,
  public val returnWidget: Optional<ReturnWidgetInput?> = Optional.Absent,
)
