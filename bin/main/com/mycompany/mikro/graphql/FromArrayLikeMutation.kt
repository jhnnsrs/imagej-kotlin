//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.mikro.graphql

import com.apollographql.apollo.annotations.ApolloAdaptableWith
import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.api.obj
import com.mycompany.mikro.graphql.adapter.FromArrayLikeMutation_ResponseAdapter
import com.mycompany.mikro.graphql.adapter.FromArrayLikeMutation_VariablesAdapter
import com.mycompany.mikro.graphql.selections.FromArrayLikeMutationSelections
import com.mycompany.mikro.graphql.type.FromArrayLikeInput
import kotlin.Boolean
import kotlin.String
import com.mycompany.mikro.graphql.type.Mutation as CompiledMutation

public data class FromArrayLikeMutation(
  public val input: FromArrayLikeInput,
) : Mutation<FromArrayLikeMutation.Data> {
  override fun id(): String = OPERATION_ID

  override fun document(): String = OPERATION_DOCUMENT

  override fun name(): String = OPERATION_NAME

  override fun serializeVariables(
    writer: JsonWriter,
    customScalarAdapters: CustomScalarAdapters,
    withDefaultValues: Boolean,
  ) {
    FromArrayLikeMutation_VariablesAdapter.serializeVariables(writer, this, customScalarAdapters,
        withDefaultValues)
  }

  override fun adapter(): Adapter<Data> = FromArrayLikeMutation_ResponseAdapter.Data.obj()

  override fun rootField(): CompiledField = CompiledField.Builder(
    name = "data",
    type = CompiledMutation.type
  )
  .selections(selections = FromArrayLikeMutationSelections.__root)
  .build()

  @ApolloAdaptableWith(FromArrayLikeMutation_ResponseAdapter.Data::class)
  public data class Data(
    /**
     * Create an image from array-like data
     */
    public val fromArrayLike: FromArrayLike,
  ) : Mutation.Data

  public data class FromArrayLike(
    public val id: String,
    /**
     * The name of the image
     */
    public val name: String,
  )

  public companion object {
    public const val OPERATION_ID: String =
        "a7bf69b5b19e82b261c9ba72b0b917de33f44e18f9e97c77bdd1e1d47ce8cffb"

    /**
     * The minimized GraphQL document being sent to the server to save a few bytes.
     * The un-minimized version is:
     *
     * ```
     * mutation FromArrayLike($input: FromArrayLikeInput!) {
     *   fromArrayLike(input: $input) {
     *     id
     *     name
     *   }
     * }
     * ```
     */
    public val OPERATION_DOCUMENT: String
      get() =
          "mutation FromArrayLike(${'$'}input: FromArrayLikeInput!) { fromArrayLike(input: ${'$'}input) { id name } }"

    public const val OPERATION_NAME: String = "FromArrayLike"
  }
}