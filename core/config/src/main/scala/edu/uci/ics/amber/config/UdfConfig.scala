/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.amber.config

import com.typesafe.config.{Config, ConfigFactory}

object UdfConfig {

  // Load configuration
  private val conf: Config = ConfigFactory.parseResources("udf.conf").resolve()

  // Python specifics
  val pythonPath: String = conf.getString("python.path")
  val pythonLogStreamHandlerLevel: String = conf.getString("python.log.streamHandler.level")
  val pythonLogStreamHandlerFormat: String = conf.getString("python.log.streamHandler.format")
  val pythonLogFileHandlerDir: String = conf.getString("python.log.fileHandler.dir")
  val pythonLogFileHandlerLevel: String = conf.getString("python.log.fileHandler.level")
  val pythonLogFileHandlerFormat: String = conf.getString("python.log.fileHandler.format")

  // R specifics
  val rPath: String = conf.getString("r.path")
}
