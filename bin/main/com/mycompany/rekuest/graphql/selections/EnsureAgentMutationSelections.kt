//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.rekuest.graphql.selections

import com.apollographql.apollo.api.CompiledArgument
import com.apollographql.apollo.api.CompiledField
import com.apollographql.apollo.api.CompiledSelection
import com.apollographql.apollo.api.CompiledVariable
import com.apollographql.apollo.api.list
import com.apollographql.apollo.api.notNull
import com.mycompany.rekuest.graphql.type.Agent
import com.mycompany.rekuest.graphql.type.GraphQLID
import com.mycompany.rekuest.graphql.type.GraphQLString
import com.mycompany.rekuest.graphql.type.InstanceId
import com.mycompany.rekuest.graphql.type.Mutation
import kotlin.collections.List

public object EnsureAgentMutationSelections {
  private val __ensureAgent: List<CompiledSelection> = listOf(
        CompiledField.Builder(
          name = "id",
          type = GraphQLID.type.notNull()
        ).build(),
        CompiledField.Builder(
          name = "instanceId",
          type = InstanceId.type.notNull()
        ).build(),
        CompiledField.Builder(
          name = "extensions",
          type = GraphQLString.type.notNull().list().notNull()
        ).build(),
        CompiledField.Builder(
          name = "name",
          type = GraphQLString.type.notNull()
        ).build()
      )

  public val __root: List<CompiledSelection> = listOf(
        CompiledField.Builder(
          name = "ensureAgent",
          type = Agent.type.notNull()
        ).arguments(listOf(
          CompiledArgument.Builder(Mutation.__ensureAgent_input).value(CompiledVariable("input")).build()
        ))
        .selections(__ensureAgent)
        .build()
      )
}