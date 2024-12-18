//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.rekuest.graphql.type.adapter

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.AnyAdapter
import com.apollographql.apollo.api.BooleanAdapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.StringAdapter
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.api.list
import com.apollographql.apollo.api.obj
import com.apollographql.apollo.api.present
import com.mycompany.rekuest.graphql.type.SetExtensionTemplatesInput
import kotlin.IllegalStateException

public object SetExtensionTemplatesInput_InputAdapter : Adapter<SetExtensionTemplatesInput> {
  override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters):
      SetExtensionTemplatesInput = throw IllegalStateException("Input type used in output position")

  override fun toJson(
    writer: JsonWriter,
    customScalarAdapters: CustomScalarAdapters,
    `value`: SetExtensionTemplatesInput,
  ) {
    writer.name("templates")
    TemplateInput_InputAdapter.obj().list().toJson(writer, customScalarAdapters, value.templates)
    writer.name("instanceId")
    AnyAdapter.toJson(writer, customScalarAdapters, value.instanceId)
    writer.name("extension")
    StringAdapter.toJson(writer, customScalarAdapters, value.extension)
    if (value.runCleanup is Optional.Present) {
      writer.name("runCleanup")
      BooleanAdapter.present().toJson(writer, customScalarAdapters, value.runCleanup)
    }
  }
}