//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.rekuest.graphql.type.adapter

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.NullableAnyAdapter
import com.apollographql.apollo.api.NullableBooleanAdapter
import com.apollographql.apollo.api.NullableDoubleAdapter
import com.apollographql.apollo.api.NullableStringAdapter
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.api.list
import com.apollographql.apollo.api.nullable
import com.apollographql.apollo.api.obj
import com.apollographql.apollo.api.present
import com.mycompany.rekuest.graphql.type.AssignWidgetInput
import kotlin.IllegalStateException

public object AssignWidgetInput_InputAdapter : Adapter<AssignWidgetInput> {
  override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters):
      AssignWidgetInput = throw IllegalStateException("Input type used in output position")

  override fun toJson(
    writer: JsonWriter,
    customScalarAdapters: CustomScalarAdapters,
    `value`: AssignWidgetInput,
  ) {
    if (value.asParagraph is Optional.Present) {
      writer.name("asParagraph")
      NullableBooleanAdapter.present().toJson(writer, customScalarAdapters, value.asParagraph)
    }
    writer.name("kind")
    AssignWidgetKind_ResponseAdapter.toJson(writer, customScalarAdapters, value.kind)
    if (value.query is Optional.Present) {
      writer.name("query")
      NullableAnyAdapter.present().toJson(writer, customScalarAdapters, value.query)
    }
    if (value.choices is Optional.Present) {
      writer.name("choices")
      ChoiceInput_InputAdapter.obj().list().nullable().present().toJson(writer,
          customScalarAdapters, value.choices)
    }
    if (value.stateChoices is Optional.Present) {
      writer.name("stateChoices")
      NullableStringAdapter.present().toJson(writer, customScalarAdapters, value.stateChoices)
    }
    if (value.followValue is Optional.Present) {
      writer.name("followValue")
      NullableStringAdapter.present().toJson(writer, customScalarAdapters, value.followValue)
    }
    if (value.min is Optional.Present) {
      writer.name("min")
      NullableDoubleAdapter.present().toJson(writer, customScalarAdapters, value.min)
    }
    if (value.max is Optional.Present) {
      writer.name("max")
      NullableDoubleAdapter.present().toJson(writer, customScalarAdapters, value.max)
    }
    if (value.step is Optional.Present) {
      writer.name("step")
      NullableDoubleAdapter.present().toJson(writer, customScalarAdapters, value.step)
    }
    if (value.placeholder is Optional.Present) {
      writer.name("placeholder")
      NullableStringAdapter.present().toJson(writer, customScalarAdapters, value.placeholder)
    }
    if (value.hook is Optional.Present) {
      writer.name("hook")
      NullableStringAdapter.present().toJson(writer, customScalarAdapters, value.hook)
    }
    if (value.ward is Optional.Present) {
      writer.name("ward")
      NullableStringAdapter.present().toJson(writer, customScalarAdapters, value.ward)
    }
    if (value.fallback is Optional.Present) {
      writer.name("fallback")
      AssignWidgetInput_InputAdapter.obj().nullable().present().toJson(writer, customScalarAdapters,
          value.fallback)
    }
    if (value.filters is Optional.Present) {
      writer.name("filters")
      ChildPortInput_InputAdapter.obj().list().nullable().present().toJson(writer,
          customScalarAdapters, value.filters)
    }
  }
}