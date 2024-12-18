//
// AUTO-GENERATED FILE. DO NOT MODIFY.
//
// This class was automatically generated by Apollo GraphQL version '4.0.0'.
//
package com.mycompany.mikro.graphql.adapter

import com.apollographql.apollo.api.Adapter
import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.StringAdapter
import com.apollographql.apollo.api.json.JsonReader
import com.apollographql.apollo.api.json.JsonWriter
import com.apollographql.apollo.api.missingField
import com.apollographql.apollo.api.obj
import com.mycompany.mikro.graphql.RequestUploadMutation
import kotlin.String
import kotlin.collections.List

public object RequestUploadMutation_ResponseAdapter {
  public object Data : Adapter<RequestUploadMutation.Data> {
    public val RESPONSE_NAMES: List<String> = listOf("requestUpload")

    override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters):
        RequestUploadMutation.Data {
      var _requestUpload: RequestUploadMutation.RequestUpload? = null

      while (true) {
        when (reader.selectName(RESPONSE_NAMES)) {
          0 -> _requestUpload = RequestUpload.obj().fromJson(reader, customScalarAdapters)
          else -> break
        }
      }

      return RequestUploadMutation.Data(
        requestUpload = _requestUpload ?: missingField(reader, "requestUpload")
      )
    }

    override fun toJson(
      writer: JsonWriter,
      customScalarAdapters: CustomScalarAdapters,
      `value`: RequestUploadMutation.Data,
    ) {
      writer.name("requestUpload")
      RequestUpload.obj().toJson(writer, customScalarAdapters, value.requestUpload)
    }
  }

  public object RequestUpload : Adapter<RequestUploadMutation.RequestUpload> {
    public val RESPONSE_NAMES: List<String> = listOf("accessKey", "status", "secretKey", "bucket",
        "key", "sessionToken", "store")

    override fun fromJson(reader: JsonReader, customScalarAdapters: CustomScalarAdapters):
        RequestUploadMutation.RequestUpload {
      var _accessKey: String? = null
      var _status: String? = null
      var _secretKey: String? = null
      var _bucket: String? = null
      var _key: String? = null
      var _sessionToken: String? = null
      var _store: String? = null

      while (true) {
        when (reader.selectName(RESPONSE_NAMES)) {
          0 -> _accessKey = StringAdapter.fromJson(reader, customScalarAdapters)
          1 -> _status = StringAdapter.fromJson(reader, customScalarAdapters)
          2 -> _secretKey = StringAdapter.fromJson(reader, customScalarAdapters)
          3 -> _bucket = StringAdapter.fromJson(reader, customScalarAdapters)
          4 -> _key = StringAdapter.fromJson(reader, customScalarAdapters)
          5 -> _sessionToken = StringAdapter.fromJson(reader, customScalarAdapters)
          6 -> _store = StringAdapter.fromJson(reader, customScalarAdapters)
          else -> break
        }
      }

      return RequestUploadMutation.RequestUpload(
        accessKey = _accessKey ?: missingField(reader, "accessKey"),
        status = _status ?: missingField(reader, "status"),
        secretKey = _secretKey ?: missingField(reader, "secretKey"),
        bucket = _bucket ?: missingField(reader, "bucket"),
        key = _key ?: missingField(reader, "key"),
        sessionToken = _sessionToken ?: missingField(reader, "sessionToken"),
        store = _store ?: missingField(reader, "store")
      )
    }

    override fun toJson(
      writer: JsonWriter,
      customScalarAdapters: CustomScalarAdapters,
      `value`: RequestUploadMutation.RequestUpload,
    ) {
      writer.name("accessKey")
      StringAdapter.toJson(writer, customScalarAdapters, value.accessKey)

      writer.name("status")
      StringAdapter.toJson(writer, customScalarAdapters, value.status)

      writer.name("secretKey")
      StringAdapter.toJson(writer, customScalarAdapters, value.secretKey)

      writer.name("bucket")
      StringAdapter.toJson(writer, customScalarAdapters, value.bucket)

      writer.name("key")
      StringAdapter.toJson(writer, customScalarAdapters, value.key)

      writer.name("sessionToken")
      StringAdapter.toJson(writer, customScalarAdapters, value.sessionToken)

      writer.name("store")
      StringAdapter.toJson(writer, customScalarAdapters, value.store)
    }
  }
}
