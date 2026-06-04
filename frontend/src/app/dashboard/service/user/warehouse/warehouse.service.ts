/**
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

import { HttpClient } from "@angular/common/http";
import { Injectable } from "@angular/core";
import { Observable } from "rxjs";
import { AppSettings } from "../../../../common/app-setting";

export type WarehouseFlavor = "aws" | "s3-compat";

export interface WarehouseInfo {
  whid: number;
  name: string;
  warehouseName: string;
  lakekeeperWarehouseId: string;
  flavor: WarehouseFlavor;
  s3Bucket: string;
  s3Endpoint?: string | null;
  s3Region: string;
  createdAt: string;
}

export interface WarehouseStatus {
  byoEnabled: boolean;
  warehouses: WarehouseInfo[];
}

export interface WarehouseCreationRequest {
  name: string;
  flavor: WarehouseFlavor;
  s3Bucket: string;
  s3Endpoint?: string;
  s3Region: string;
  s3AccessKey: string;
  s3SecretKey: string;
}

const WAREHOUSE_BASE_URL = `${AppSettings.getApiEndpoint()}/warehouse`;

@Injectable({
  providedIn: "root",
})
export class WarehouseService {
  constructor(private http: HttpClient) {}

  public getStatus(): Observable<WarehouseStatus> {
    return this.http.get<WarehouseStatus>(`${WAREHOUSE_BASE_URL}/status`);
  }

  public create(request: WarehouseCreationRequest): Observable<WarehouseInfo> {
    return this.http.post<WarehouseInfo>(WAREHOUSE_BASE_URL, request);
  }

  public delete(whid: number): Observable<void> {
    return this.http.delete<void>(`${WAREHOUSE_BASE_URL}/${whid}`);
  }
}
