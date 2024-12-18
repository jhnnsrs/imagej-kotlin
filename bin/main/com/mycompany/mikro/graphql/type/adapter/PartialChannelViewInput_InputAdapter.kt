//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.mikro.graphql.type.adapter

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.NullableIntAdapter
import com.apollographql.apollo.api.NullableStringAdapter
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.StringAdapter
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.api.present
import com.mycompany.mikro.graphql.type.PartialChannelViewInput
import kotlin.IllegalStateException

public object PartialChannelViewInput_InputAdapter : Adapter<PartialChannelViewInput> {
  override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters):
      PartialChannelViewInput = throw IllegalStateException("Input type used in output position")

  override fun toJson(
    writer: JsonWriter,
    customScalarAdapters: CustomScalarAdapters,
    `value`: PartialChannelViewInput,
  ) {
    if (value.collection is Optional.Present) {
      writer.name("collection")
      NullableStringAdapter.present().toJson(writer, customScalarAdapters, value.collection)
    }
    if (value.zMin is Optional.Present) {
      writer.name("zMin")
      NullableIntAdapter.present().toJson(writer, customScalarAdapters, value.zMin)
    }
    if (value.zMax is Optional.Present) {
      writer.name("zMax")
      NullableIntAdapter.present().toJson(writer, customScalarAdapters, value.zMax)
    }
    if (value.xMin is Optional.Present) {
      writer.name("xMin")
      NullableIntAdapter.present().toJson(writer, customScalarAdapters, value.xMin)
    }
    if (value.xMax is Optional.Present) {
      writer.name("xMax")
      NullableIntAdapter.present().toJson(writer, customScalarAdapters, value.xMax)
    }
    if (value.yMin is Optional.Present) {
      writer.name("yMin")
      NullableIntAdapter.present().toJson(writer, customScalarAdapters, value.yMin)
    }
    if (value.yMax is Optional.Present) {
      writer.name("yMax")
      NullableIntAdapter.present().toJson(writer, customScalarAdapters, value.yMax)
    }
    if (value.tMin is Optional.Present) {
      writer.name("tMin")
      NullableIntAdapter.present().toJson(writer, customScalarAdapters, value.tMin)
    }
    if (value.tMax is Optional.Present) {
      writer.name("tMax")
      NullableIntAdapter.present().toJson(writer, customScalarAdapters, value.tMax)
    }
    if (value.cMin is Optional.Present) {
      writer.name("cMin")
      NullableIntAdapter.present().toJson(writer, customScalarAdapters, value.cMin)
    }
    if (value.cMax is Optional.Present) {
      writer.name("cMax")
      NullableIntAdapter.present().toJson(writer, customScalarAdapters, value.cMax)
    }
    writer.name("channel")
    StringAdapter.toJson(writer, customScalarAdapters, value.channel)
  }
}
