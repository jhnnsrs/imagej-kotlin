//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.mikro.graphql.type

import com.apollographql.apollo.api.Optional
import kotlin.Int
import kotlin.String

public data class PartialChannelViewInput(
  /**
   * The collection this view belongs to
   */
  public val collection: Optional<String?> = Optional.Absent,
  /**
   * The minimum z coordinate of the view
   */
  public val zMin: Optional<Int?> = Optional.Absent,
  /**
   * The maximum z coordinate of the view
   */
  public val zMax: Optional<Int?> = Optional.Absent,
  /**
   * The minimum x coordinate of the view
   */
  public val xMin: Optional<Int?> = Optional.Absent,
  /**
   * The maximum x coordinate of the view
   */
  public val xMax: Optional<Int?> = Optional.Absent,
  /**
   * The minimum y coordinate of the view
   */
  public val yMin: Optional<Int?> = Optional.Absent,
  /**
   * The maximum y coordinate of the view
   */
  public val yMax: Optional<Int?> = Optional.Absent,
  /**
   * The minimum t coordinate of the view
   */
  public val tMin: Optional<Int?> = Optional.Absent,
  /**
   * The maximum t coordinate of the view
   */
  public val tMax: Optional<Int?> = Optional.Absent,
  /**
   * The minimum c (channel) coordinate of the view
   */
  public val cMin: Optional<Int?> = Optional.Absent,
  /**
   * The maximum c (channel) coordinate of the view
   */
  public val cMax: Optional<Int?> = Optional.Absent,
  /**
   * The ID of the channel this view is for
   */
  public val channel: String,
)
