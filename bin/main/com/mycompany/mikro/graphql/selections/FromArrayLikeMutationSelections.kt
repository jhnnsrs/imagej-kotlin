//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.mikro.graphql.selections

import com.apollographql.apollo.api.CompiledArgument
import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.CompiledSelection
import com.apollographql.apollo.api.CompiledVariable
import com.apollographql.apollo.api.notNull
import com.mycompany.mikro.graphql.type.GraphQLID
import com.mycompany.mikro.graphql.type.GraphQLString
import com.mycompany.mikro.graphql.type.Image
import com.mycompany.mikro.graphql.type.Mutation
import kotlin.collections.List

public object FromArrayLikeMutationSelections {
  private val __fromArrayLike: List<CompiledSelection> = listOf(
        CompiledField.Builder(
          name = "id",
          type = GraphQLID.type.notNull()
        ).build(),
        CompiledField.Builder(
          name = "name",
          type = GraphQLString.type.notNull()
        ).build()
      )

  public val __root: List<CompiledSelection> = listOf(
        CompiledField.Builder(
          name = "fromArrayLike",
          type = Image.type.notNull()
        ).arguments(listOf(
          CompiledArgument.Builder(Mutation.__fromArrayLike_input).value(CompiledVariable("input")).build()
        ))
        .selections(__fromArrayLike)
        .build()
      )
}
