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

\c texera_db

SET search_path TO texera_db;

BEGIN;

-- Per-user Lakekeeper warehouses (BYO-S3 mode). A user may register multiple,
-- one per S3 bucket they own; CU creation lets them pick which to use. Raw
-- S3 creds are NOT stored here; they live in Lakekeeper's encrypted DB.
CREATE TABLE IF NOT EXISTS user_warehouse
(
    whid                     SERIAL       PRIMARY KEY,
    uid                      INT          NOT NULL,
    name                     VARCHAR(128) NOT NULL,
    warehouse_name           VARCHAR(256) NOT NULL UNIQUE,
    lakekeeper_warehouse_id  UUID         NOT NULL,
    flavor                   VARCHAR(16)  NOT NULL,
    s3_bucket                VARCHAR(256) NOT NULL,
    s3_endpoint              VARCHAR(512),
    s3_region                VARCHAR(64)  NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (uid, name),
    FOREIGN KEY (uid) REFERENCES "user"(uid) ON DELETE CASCADE,
    CONSTRAINT chk_user_warehouse_flavor CHECK (flavor IN ('aws', 's3-compat')),
    CONSTRAINT chk_user_warehouse_endpoint
        CHECK ((flavor = 'aws' AND s3_endpoint IS NULL) OR
               (flavor = 's3-compat' AND s3_endpoint IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_user_warehouse_uid ON user_warehouse(uid);

COMMIT;
