//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.rekuest.graphql.type.adapter

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.IntAdapter
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.StringAdapter
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.api.list
import com.apollographql.apollo.api.nullable
import com.apollographql.apollo.api.present
import com.mycompany.rekuest.graphql.type.BindsInput
import kotlin.IllegalStateException

public object BindsInput_InputAdapter : Adapter<BindsInput> {
  override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters): BindsInput
      = throw IllegalStateException("Input type used in output position")

  override fun toJson(
    writer: JsonWriter,
    customScalarAdapters: CustomScalarAdapters,
    `value`: BindsInput,
  ) {
    if (value.templates is Optional.Present) {
      writer.name("templates")
      StringAdapter.list().nullable().present().toJson(writer, customScalarAdapters,
          value.templates)
    }
    if (value.clients is Optional.Present) {
      writer.name("clients")
      StringAdapter.list().nullable().present().toJson(writer, customScalarAdapters, value.clients)
    }
    if (value.desiredInstances is Optional.Present) {
      writer.name("desiredInstances")
      IntAdapter.present().toJson(writer, customScalarAdapters, value.desiredInstances)
    }
  }
}