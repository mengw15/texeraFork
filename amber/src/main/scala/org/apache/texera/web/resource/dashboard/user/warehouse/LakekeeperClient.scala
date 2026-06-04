/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.texera.web.resource.dashboard.user.warehouse

import org.apache.texera.amber.config.StorageConfig
import play.api.libs.json.{JsValue, Json}

import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.io.Source
import scala.util.Using

case class LakekeeperException(status: Int, body: String)
    extends RuntimeException(s"Lakekeeper API error (HTTP $status): $body")

object LakekeeperClient {

  // Lakekeeper's default project — matches LAKEKEEPER__ENABLE_DEFAULT_PROJECT=true
  // (see bin/single-node/docker-compose.yml).
  private val DEFAULT_PROJECT_ID = "00000000-0000-0000-0000-000000000000"

  private def managementBase: String = StorageConfig.lakekeeperManagementUri

  /**
    * Create a per-user warehouse in Lakekeeper. S3 credentials are forwarded
    * once and stored encrypted inside Lakekeeper's DB; they are never persisted
    * by Texera.
    *
    * @return the UUID assigned to the new warehouse by Lakekeeper.
    */
  def createWarehouse(
      warehouseName: String,
      flavor: String,
      s3Bucket: String,
      s3Endpoint: Option[String],
      s3Region: String,
      s3AccessKey: String,
      s3SecretKey: String
  ): UUID = {
    // AWS: use virtual-hosted-style URLs (path-style is deprecated for buckets
    // created after 2020) and let the SDK derive the endpoint from region.
    // S3-compatible (MinIO/R2/etc.): explicit endpoint, path-style is the
    // safe default since most S3-compat services don't do DNS-based hosting.
    val baseProfile = Json.obj(
      "type" -> "s3",
      "bucket" -> s3Bucket,
      "region" -> s3Region,
      "flavor" -> flavor,
      "sts-enabled" -> false
    )
    val storageProfile = flavor match {
      case "s3-compat" =>
        baseProfile ++ Json.obj("path-style-access" -> true) ++
          s3Endpoint.filter(_.nonEmpty).fold(Json.obj())(ep => Json.obj("endpoint" -> ep))
      case _ => baseProfile
    }

    val payload = Json.obj(
      "warehouse-name" -> warehouseName,
      "project-id" -> DEFAULT_PROJECT_ID,
      "storage-profile" -> storageProfile,
      "storage-credential" -> Json.obj(
        "type" -> "s3",
        "credential-type" -> "access-key",
        "aws-access-key-id" -> s3AccessKey,
        "aws-secret-access-key" -> s3SecretKey
      )
    )

    val response = postJson(s"$managementBase/management/v1/warehouse", payload)
    val idStr = (response \ "warehouse-id")
      .asOpt[String]
      .orElse((response \ "warehouseId").asOpt[String])
      .getOrElse(
        throw new RuntimeException(
          s"Lakekeeper response missing warehouse-id field: ${Json.stringify(response)}"
        )
      )
    UUID.fromString(idStr)
  }

  def deleteWarehouse(warehouseId: UUID): Unit = {
    val (status, body) =
      sendRequest("DELETE", s"$managementBase/management/v1/warehouse/$warehouseId", None)
    // 204 No Content is the expected success; treat 404 as already-gone (idempotent).
    if (status != 204 && status != 200 && status != 404) {
      throw LakekeeperException(status, body)
    }
  }

  private def postJson(url: String, body: JsValue): JsValue = {
    val (status, respBody) = sendRequest("POST", url, Some(Json.stringify(body)))
    if (status < 200 || status >= 300) {
      throw LakekeeperException(status, respBody)
    }
    if (respBody.isEmpty) Json.obj() else Json.parse(respBody)
  }

  private def sendRequest(
      method: String,
      url: String,
      body: Option[String]
  ): (Int, String) = {
    val conn = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
    try {
      conn.setRequestMethod(method)
      conn.setRequestProperty("Accept", "application/json")
      conn.setConnectTimeout(10000)
      conn.setReadTimeout(30000)
      body.foreach { b =>
        conn.setDoOutput(true)
        conn.setRequestProperty("Content-Type", "application/json")
        val out = conn.getOutputStream
        try out.write(b.getBytes(StandardCharsets.UTF_8))
        finally out.close()
      }
      val status = conn.getResponseCode
      val stream =
        if (status >= 200 && status < 300) conn.getInputStream
        else conn.getErrorStream
      val text =
        if (stream == null) ""
        else
          Using.resource(Source.fromInputStream(stream, StandardCharsets.UTF_8.name))(_.mkString)
      (status, text)
    } finally conn.disconnect()
  }
}
