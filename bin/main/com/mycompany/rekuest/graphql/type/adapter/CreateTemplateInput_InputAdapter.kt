//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.rekuest.graphql.type.adapter

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.AnyAdapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.StringAdapter
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.api.obj
import com.mycompany.rekuest.graphql.type.CreateTemplateInput
import kotlin.IllegalStateException

public object CreateTemplateInput_InputAdapter : Adapter<CreateTemplateInput> {
  override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters):
      CreateTemplateInput = throw IllegalStateException("Input type used in output position")

  override fun toJson(
    writer: JsonWriter,
    customScalarAdapters: CustomScalarAdapters,
    `value`: CreateTemplateInput,
  ) {
    writer.name("template")
    TemplateInput_InputAdapter.obj().toJson(writer, customScalarAdapters, value.template)
    writer.name("instanceId")
    AnyAdapter.toJson(writer, customScalarAdapters, value.instanceId)
    writer.name("extension")
    StringAdapter.toJson(writer, customScalarAdapters, value.extension)
  }
}