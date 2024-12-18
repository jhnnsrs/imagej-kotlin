//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.rekuest.graphql.type.adapter

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.AnyAdapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.NullableStringAdapter
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.StringAdapter
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.api.present
import com.mycompany.rekuest.graphql.type.ChoiceInput
import kotlin.IllegalStateException

public object ChoiceInput_InputAdapter : Adapter<ChoiceInput> {
  override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters): ChoiceInput
      = throw IllegalStateException("Input type used in output position")

  override fun toJson(
    writer: JsonWriter,
    customScalarAdapters: CustomScalarAdapters,
    `value`: ChoiceInput,
  ) {
    writer.name("value")
    AnyAdapter.toJson(writer, customScalarAdapters, value.`value`)
    writer.name("label")
    StringAdapter.toJson(writer, customScalarAdapters, value.label)
    if (value.description is Optional.Present) {
      writer.name("description")
      NullableStringAdapter.present().toJson(writer, customScalarAdapters, value.description)
    }
  }
}
