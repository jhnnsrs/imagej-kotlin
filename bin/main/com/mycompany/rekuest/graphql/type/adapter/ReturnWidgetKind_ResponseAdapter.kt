//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.rekuest.graphql.type.adapter

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.mycompany.rekuest.graphql.type.ReturnWidgetKind

public object ReturnWidgetKind_ResponseAdapter : Adapter<ReturnWidgetKind> {
  override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters):
      ReturnWidgetKind {
    val rawValue = reader.nextString()!!
    return ReturnWidgetKind.safeValueOf(rawValue)
  }

  override fun toJson(
    writer: JsonWriter,
    customScalarAdapters: CustomScalarAdapters,
    `value`: ReturnWidgetKind,
  ) {
    writer.value(value.rawValue)
  }
}
