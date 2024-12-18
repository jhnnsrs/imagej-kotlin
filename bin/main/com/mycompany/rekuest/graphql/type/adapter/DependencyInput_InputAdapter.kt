//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.rekuest.graphql.type.adapter

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.BooleanAdapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.NullableAnyAdapter
import com.apollographql.apollo.api.NullableIntAdapter
import com.apollographql.apollo.api.NullableStringAdapter
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.api.nullable
import com.apollographql.apollo.api.obj
import com.apollographql.apollo.api.present
import com.mycompany.rekuest.graphql.type.DependencyInput
import kotlin.IllegalStateException

public object DependencyInput_InputAdapter : Adapter<DependencyInput> {
  override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters):
      DependencyInput = throw IllegalStateException("Input type used in output position")

  override fun toJson(
    writer: JsonWriter,
    customScalarAdapters: CustomScalarAdapters,
    `value`: DependencyInput,
  ) {
    if (value.hash is Optional.Present) {
      writer.name("hash")
      NullableAnyAdapter.present().toJson(writer, customScalarAdapters, value.hash)
    }
    if (value.reference is Optional.Present) {
      writer.name("reference")
      NullableStringAdapter.present().toJson(writer, customScalarAdapters, value.reference)
    }
    if (value.binds is Optional.Present) {
      writer.name("binds")
      BindsInput_InputAdapter.obj().nullable().present().toJson(writer, customScalarAdapters,
          value.binds)
    }
    if (value.optional is Optional.Present) {
      writer.name("optional")
      BooleanAdapter.present().toJson(writer, customScalarAdapters, value.optional)
    }
    if (value.viableInstances is Optional.Present) {
      writer.name("viableInstances")
      NullableIntAdapter.present().toJson(writer, customScalarAdapters, value.viableInstances)
    }
  }
}
