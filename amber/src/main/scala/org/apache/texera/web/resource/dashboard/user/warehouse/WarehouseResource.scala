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

import io.dropwizard.auth.Auth
import javax.annotation.security.RolesAllowed
import org.apache.texera.amber.config.StorageConfig
import org.apache.texera.auth.SessionUser
import org.apache.texera.dao.SqlServer
import org.apache.texera.dao.jooq.generated.tables.daos.UserWarehouseDao
import org.apache.texera.dao.jooq.generated.tables.pojos.UserWarehouse

import javax.ws.rs._
import javax.ws.rs.core.{MediaType, Response}
import scala.jdk.CollectionConverters.CollectionHasAsScala

object WarehouseResource {
  private def context = SqlServer.getInstance().createDSLContext()
  private def dao = new UserWarehouseDao(context.configuration)

  // Allowed characters for the user-typed name. Must produce a Lakekeeper
  // warehouse name (after the "user-{uid}-" prefix) that's URL-safe and
  // matches what the lakekeeper-init script's regex would accept.
  private val NAME_PATTERN = "^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$".r

  // Supported Lakekeeper storage flavors. "aws" expects no endpoint; "s3-compat"
  // (MinIO, R2, StorageGRID, Ceph, ...) expects the user to supply one.
  private val ALLOWED_FLAVORS = Set("aws", "s3-compat")

  case class WarehouseCreationRequest(
      name: String,
      flavor: String,
      s3Bucket: String,
      s3Endpoint: Option[String],
      s3Region: String,
      s3AccessKey: String,
      s3SecretKey: String
  )

  case class WarehouseInfo(
      whid: Int,
      name: String,
      warehouseName: String,
      lakekeeperWarehouseId: String,
      flavor: String,
      s3Bucket: String,
      s3Endpoint: Option[String],
      s3Region: String,
      createdAt: String
  )

  case class WarehouseStatus(
      byoEnabled: Boolean,
      warehouses: List[WarehouseInfo]
  )

  private def toInfo(row: UserWarehouse): WarehouseInfo =
    WarehouseInfo(
      whid = row.getWhid,
      name = row.getName,
      warehouseName = row.getWarehouseName,
      lakekeeperWarehouseId = row.getLakekeeperWarehouseId.toString,
      flavor = row.getFlavor,
      s3Bucket = row.getS3Bucket,
      s3Endpoint = Option(row.getS3Endpoint).filter(_.nonEmpty),
      s3Region = row.getS3Region,
      createdAt = row.getCreatedAt.toString
    )
}

@Path("/warehouse")
@Consumes(Array(MediaType.APPLICATION_JSON))
@Produces(Array(MediaType.APPLICATION_JSON))
@RolesAllowed(Array("REGULAR", "ADMIN"))
class WarehouseResource {
  import WarehouseResource._

  /**
    * Returns whether BYO-S3 is enabled and the list of warehouses owned by
    * the current user.
    */
  @GET
  @Path("/status")
  def status(@Auth user: SessionUser): WarehouseStatus = {
    val rows = dao.fetchByUid(user.getUid).asScala.toList.map(toInfo)
    WarehouseStatus(StorageConfig.icebergRESTCatalogByoS3, rows)
  }

  /**
    * Register a new Lakekeeper warehouse for the current user. The user-typed
    * name becomes part of the Lakekeeper warehouse identifier
    * ("user-{uid}-{name}"), so it must be URL-safe. S3 creds are forwarded to
    * Lakekeeper and never persisted by Texera.
    */
  @POST
  def create(
      @Auth user: SessionUser,
      request: WarehouseCreationRequest
  ): WarehouseInfo = {
    if (!StorageConfig.icebergRESTCatalogByoS3) {
      throw new ForbiddenException(
        "BYO-S3 mode is not enabled on this deployment; warehouse creation is disabled."
      )
    }

    val name = Option(request.name).map(_.trim).getOrElse("")
    if (name.isEmpty || NAME_PATTERN.findFirstIn(name).isEmpty) {
      throw new BadRequestException(
        "name must be 1–64 characters, start with alphanumeric, contain only letters, digits, '-' and '_'."
      )
    }
    val flavor = Option(request.flavor).map(_.trim).getOrElse("")
    if (!ALLOWED_FLAVORS.contains(flavor)) {
      throw new BadRequestException(
        s"flavor must be one of ${ALLOWED_FLAVORS.mkString(", ")}."
      )
    }
    val endpoint = request.s3Endpoint.map(_.trim).filter(_.nonEmpty)
    flavor match {
      case "aws" if endpoint.isDefined =>
        throw new BadRequestException("Endpoint must be empty when flavor is 'aws'.")
      case "s3-compat" if endpoint.isEmpty =>
        throw new BadRequestException("Endpoint is required when flavor is 's3-compat'.")
      case _ => ()
    }
    if (Option(request.s3Bucket).forall(_.trim.isEmpty)) {
      throw new BadRequestException("s3Bucket is required.")
    }
    if (Option(request.s3Region).forall(_.trim.isEmpty)) {
      throw new BadRequestException("s3Region is required.")
    }
    if (Option(request.s3AccessKey).forall(_.trim.isEmpty) ||
        Option(request.s3SecretKey).forall(_.trim.isEmpty)) {
      throw new BadRequestException("s3AccessKey and s3SecretKey are required.")
    }

    val warehouseName = s"user-${user.getUid}-$name"
    val lakekeeperId =
      try {
        LakekeeperClient.createWarehouse(
          warehouseName = warehouseName,
          flavor = flavor,
          s3Bucket = request.s3Bucket.trim,
          s3Endpoint = endpoint,
          s3Region = request.s3Region.trim,
          s3AccessKey = request.s3AccessKey,
          s3SecretKey = request.s3SecretKey
        )
      } catch {
        case e: LakekeeperException =>
          throw new WebApplicationException(
            s"Failed to create warehouse in Lakekeeper: ${e.getMessage}",
            Response.Status.BAD_GATEWAY
          )
      }

    val row = new UserWarehouse()
    row.setUid(user.getUid)
    row.setName(name)
    row.setWarehouseName(warehouseName)
    row.setLakekeeperWarehouseId(lakekeeperId)
    row.setFlavor(flavor)
    row.setS3Bucket(request.s3Bucket.trim)
    row.setS3Endpoint(endpoint.orNull)
    row.setS3Region(request.s3Region.trim)
    try {
      dao.insert(row)
    } catch {
      case e: org.jooq.exception.DataAccessException =>
        // Local insert failed (e.g. duplicate name). Roll back the Lakekeeper
        // side so we don't leak orphaned warehouses on the catalog.
        try LakekeeperClient.deleteWarehouse(lakekeeperId)
        catch { case _: Throwable => () }
        throw new WebApplicationException(
          s"Failed to record warehouse: ${e.getMessage}",
          Response.Status.CONFLICT
        )
    }

    // Reload to populate Postgres-assigned whid and created_at.
    val saved = dao.fetchOneByWarehouseName(warehouseName)
    toInfo(saved)
  }

  @DELETE
  @Path("/{whid}")
  def delete(@Auth user: SessionUser, @PathParam("whid") whid: Int): Response = {
    val existing = dao.fetchOneByWhid(whid)
    if (existing == null) {
      return Response.noContent().build()
    }
    if (existing.getUid != user.getUid) {
      throw new ForbiddenException("Warehouse does not belong to you.")
    }
    try {
      LakekeeperClient.deleteWarehouse(existing.getLakekeeperWarehouseId)
    } catch {
      case e: LakekeeperException =>
        throw new WebApplicationException(
          s"Failed to delete warehouse in Lakekeeper: ${e.getMessage}",
          Response.Status.BAD_GATEWAY
        )
    }
    dao.deleteById(whid)
    Response.noContent().build()
  }
}
