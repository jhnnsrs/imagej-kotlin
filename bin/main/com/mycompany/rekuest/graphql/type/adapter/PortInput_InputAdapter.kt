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
import com.apollographql.apollo.api.NullableStringAdapter
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.StringAdapter
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.api.list
import com.apollographql.apollo.api.nullable
import com.apollographql.apollo.api.obj
import com.apollographql.apollo.api.present
import com.mycompany.rekuest.graphql.type.PortInput
import kotlin.IllegalStateException

public object PortInput_InputAdapter : Adapter<PortInput> {
  override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters): PortInput =
      throw IllegalStateException("Input type used in output position")

  override fun toJson(
    writer: JsonWriter,
    customScalarAdapters: CustomScalarAdapters,
    `value`: PortInput,
  ) {
    if (value.validators is Optional.Present) {
      writer.name("validators")
      ValidatorInput_InputAdapter.obj().list().nullable().present().toJson(writer,
          customScalarAdapters, value.validators)
    }
    writer.name("key")
    StringAdapter.toJson(writer, customScalarAdapters, value.key)
    writer.name("scope")
    PortScope_ResponseAdapter.toJson(writer, customScalarAdapters, value.scope)
    if (value.label is Optional.Present) {
      writer.name("label")
      NullableStringAdapter.present().toJson(writer, customScalarAdapters, value.label)
    }
    writer.name("kind")
    PortKind_ResponseAdapter.toJson(writer, customScalarAdapters, value.kind)
    if (value.description is Optional.Present) {
      writer.name("description")
      NullableStringAdapter.present().toJson(writer, customScalarAdapters, value.description)
    }
    if (value.identifier is Optional.Present) {
      writer.name("identifier")
      NullableStringAdapter.present().toJson(writer, customScalarAdapters, value.identifier)
    }
    if (value.nullable is Optional.Present) {
      writer.name("nullable")
      BooleanAdapter.present().toJson(writer, customScalarAdapters, value.nullable)
    }
    if (value.effects is Optional.Present) {
      writer.name("effects")
      EffectInput_InputAdapter.obj().list().nullable().present().toJson(writer,
          customScalarAdapters, value.effects)
    }
    if (value.default is Optional.Present) {
      writer.name("default")
      NullableAnyAdapter.present().toJson(writer, customScalarAdapters, value.default)
    }
    if (value.children is Optional.Present) {
      writer.name("children")
      ChildPortInput_InputAdapter.obj().list().nullable().present().toJson(writer,
          customScalarAdapters, value.children)
    }
    if (value.assignWidget is Optional.Present) {
      writer.name("assignWidget")
      AssignWidgetInput_InputAdapter.obj().nullable().present().toJson(writer, customScalarAdapters,
          value.assignWidget)
    }
    if (value.returnWidget is Optional.Present) {
      writer.name("returnWidget")
      ReturnWidgetInput_InputAdapter.obj().nullable().present().toJson(writer, customScalarAdapters,
          value.returnWidget)
    }
    if (value.groups is Optional.Present) {
      writer.name("groups")
      StringAdapter.list().nullable().present().toJson(writer, customScalarAdapters, value.groups)
    }
  }
}