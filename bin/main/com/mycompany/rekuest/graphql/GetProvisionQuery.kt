//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.rekuest.graphql

import com.apollographql.apollo.annotations.ApolloAdaptableWith
import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.api.obj
import com.mycompany.rekuest.graphql.adapter.GetProvisionQuery_ResponseAdapter
import com.mycompany.rekuest.graphql.adapter.GetProvisionQuery_VariablesAdapter
import com.mycompany.rekuest.graphql.selections.GetProvisionQuerySelections
import kotlin.Boolean
import kotlin.String
import com.mycompany.rekuest.graphql.type.Query as CompiledQuery

public data class GetProvisionQuery(
  public val id: String,
) : Query<GetProvisionQuery.Data> {
  override fun id(): String = OPERATION_ID

  override fun document(): String = OPERATION_DOCUMENT

  override fun name(): String = OPERATION_NAME

  override fun serializeVariables(
    writer: JsonWriter,
    customScalarAdapters: CustomScalarAdapters,
    withDefaultValues: Boolean,
  ) {
    GetProvisionQuery_VariablesAdapter.serializeVariables(writer, this, customScalarAdapters,
        withDefaultValues)
  }

  override fun adapter(): Adapter<Data> = GetProvisionQuery_ResponseAdapter.Data.obj()

  override fun rootField(): CompiledField = CompiledField.Builder(
    name = "data",
    type = CompiledQuery.type
  )
  .selections(selections = GetProvisionQuerySelections.__root)
  .build()

  @ApolloAdaptableWith(GetProvisionQuery_ResponseAdapter.Data::class)
  public data class Data(
    public val provision: Provision,
  ) : Query.Data

  public data class Provision(
    public val id: String,
    public val template: Template,
  )

  public data class Template(
    public val id: String,
    public val `interface`: String,
    public val extension: String,
  )

  public companion object {
    public const val OPERATION_ID: String =
        "9f4d27360459efe965c6d0cd1ea5518eab8b7c5f00d1899213613fdea860b5da"

    /**
     * The minimized GraphQL document being sent to the server to save a few bytes.
     * The un-minimized version is:
     *
     * ```
     * query GetProvision($id: ID!) {
     *   provision(id: $id) {
     *     id
     *     template {
     *       id
     *       interface
     *       extension
     *     }
     *   }
     * }
     * ```
     */
    public val OPERATION_DOCUMENT: String
      get() =
          "query GetProvision(${'$'}id: ID!) { provision(id: ${'$'}id) { id template { id interface extension } } }"

    public const val OPERATION_NAME: String = "GetProvision"
  }
}
