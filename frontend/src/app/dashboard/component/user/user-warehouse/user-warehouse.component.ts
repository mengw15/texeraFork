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

import { CommonModule } from "@angular/common";
import { Component, OnInit } from "@angular/core";
import { FormsModule } from "@angular/forms";
import { UntilDestroy, untilDestroyed } from "@ngneat/until-destroy";
import { NzAlertModule } from "ng-zorro-antd/alert";
import { NzButtonModule } from "ng-zorro-antd/button";
import { NzCardModule } from "ng-zorro-antd/card";
import { NzFormModule } from "ng-zorro-antd/form";
import { NzIconModule } from "ng-zorro-antd/icon";
import { NzInputModule } from "ng-zorro-antd/input";
import { NzModalService } from "ng-zorro-antd/modal";
import { NzRadioModule } from "ng-zorro-antd/radio";
import { NzSpinModule } from "ng-zorro-antd/spin";
import { NzTableModule } from "ng-zorro-antd/table";
import { NotificationService } from "../../../../common/service/notification/notification.service";
import { extractErrorMessage } from "../../../../common/util/error";
import {
  WarehouseFlavor,
  WarehouseInfo,
  WarehouseService,
} from "../../../service/user/warehouse/warehouse.service";

const NAME_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$/;

@UntilDestroy()
@Component({
  selector: "texera-user-warehouse",
  templateUrl: "user-warehouse.component.html",
  styleUrls: ["user-warehouse.component.scss"],
  imports: [
    CommonModule,
    FormsModule,
    NzAlertModule,
    NzButtonModule,
    NzCardModule,
    NzFormModule,
    NzIconModule,
    NzInputModule,
    NzRadioModule,
    NzSpinModule,
    NzTableModule,
  ],
})
export class UserWarehouseComponent implements OnInit {
  loading = false;
  saving = false;
  byoEnabled = false;
  warehouses: WarehouseInfo[] = [];

  // create form state
  formVisible = false;
  name = "";
  flavor: WarehouseFlavor = "aws";
  s3Bucket = "";
  s3Endpoint = "";
  s3Region = "us-west-2";
  s3AccessKey = "";
  s3SecretKey = "";

  constructor(
    private warehouseService: WarehouseService,
    private notificationService: NotificationService,
    private modalService: NzModalService
  ) {}

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    this.warehouseService
      .getStatus()
      .pipe(untilDestroyed(this))
      .subscribe({
        next: status => {
          this.byoEnabled = status.byoEnabled;
          this.warehouses = status.warehouses;
          this.loading = false;
        },
        error: err => {
          this.notificationService.error(`Failed to load warehouses: ${extractErrorMessage(err)}`);
          this.loading = false;
        },
      });
  }

  openForm(): void {
    this.formVisible = true;
    this.name = "";
    this.flavor = "aws";
    this.s3Bucket = "";
    this.s3Endpoint = "";
    this.s3Region = "us-west-2";
    this.s3AccessKey = "";
    this.s3SecretKey = "";
  }

  onFlavorChange(): void {
    // AWS uses the SDK's default endpoint resolver based on region; clearing
    // any stale endpoint here keeps validation honest.
    if (this.flavor === "aws") {
      this.s3Endpoint = "";
    }
  }

  closeForm(): void {
    this.formVisible = false;
  }

  isNameValid(): boolean {
    return NAME_PATTERN.test(this.name.trim());
  }

  canSubmit(): boolean {
    const endpointOk =
      this.flavor === "aws" ? this.s3Endpoint.trim().length === 0 : this.s3Endpoint.trim().length > 0;
    return (
      this.isNameValid() &&
      endpointOk &&
      this.s3Bucket.trim().length > 0 &&
      this.s3Region.trim().length > 0 &&
      this.s3AccessKey.trim().length > 0 &&
      this.s3SecretKey.trim().length > 0
    );
  }

  submit(): void {
    if (!this.canSubmit()) {
      return;
    }
    this.saving = true;
    this.warehouseService
      .create({
        name: this.name.trim(),
        flavor: this.flavor,
        s3Bucket: this.s3Bucket.trim(),
        s3Endpoint: this.flavor === "s3-compat" ? this.s3Endpoint.trim() : undefined,
        s3Region: this.s3Region.trim(),
        s3AccessKey: this.s3AccessKey,
        s3SecretKey: this.s3SecretKey,
      })
      .pipe(untilDestroyed(this))
      .subscribe({
        next: warehouse => {
          this.warehouses = [...this.warehouses, warehouse];
          this.saving = false;
          this.formVisible = false;
          this.notificationService.success(`Warehouse "${warehouse.name}" created.`);
        },
        error: err => {
          this.saving = false;
          this.notificationService.error(`Failed to create warehouse: ${extractErrorMessage(err)}`);
        },
      });
  }

  confirmDelete(warehouse: WarehouseInfo): void {
    this.modalService.confirm({
      nzTitle: `Delete warehouse "${warehouse.name}"?`,
      nzContent:
        "This removes the warehouse from Lakekeeper. Iceberg tables stored in this S3 bucket will become " +
        "inaccessible from Texera until you re-register the warehouse pointing to the same bucket. " +
        "Existing data in S3 is not deleted. Computing units pinned to this warehouse will fail to spawn.",
      nzOkText: "Delete",
      nzOkDanger: true,
      nzOnOk: () => this.delete(warehouse.whid),
    });
  }

  private delete(whid: number): void {
    this.warehouseService
      .delete(whid)
      .pipe(untilDestroyed(this))
      .subscribe({
        next: () => {
          this.warehouses = this.warehouses.filter(w => w.whid !== whid);
          this.notificationService.success("Warehouse deleted.");
        },
        error: err => {
          this.notificationService.error(`Failed to delete warehouse: ${extractErrorMessage(err)}`);
        },
      });
  }
}
