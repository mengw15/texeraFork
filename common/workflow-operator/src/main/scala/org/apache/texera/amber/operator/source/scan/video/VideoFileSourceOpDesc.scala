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

package org.apache.texera.amber.operator.source.scan.video

import com.fasterxml.jackson.annotation.{JsonIgnoreProperties, JsonProperty, JsonPropertyDescription}
import com.kjetland.jackson.jsonSchema.annotations.{JsonSchemaInject, JsonSchemaTitle}
import org.apache.texera.amber.core.executor.OpExecWithCode
import org.apache.texera.amber.core.tuple.{AttributeType, Schema}
import org.apache.texera.amber.core.virtualidentity.{ExecutionIdentity, WorkflowIdentity}
import org.apache.texera.amber.core.workflow.{PhysicalOp, SchemaPropagationFunc}
import org.apache.texera.amber.operator.source.scan.ScanSourceOpDesc
import org.apache.texera.amber.pybuilder.PyStringTypes.EncodableString
import org.apache.texera.amber.pybuilder.PythonTemplateBuilder.PythonTemplateBuilderStringContext

@JsonIgnoreProperties(value = Array("limit", "offset", "fileEncoding"))
class VideoFileSourceOpDesc extends ScanSourceOpDesc {

  @JsonProperty(required = true)
  @JsonSchemaTitle("Sampling FPS")
  @JsonPropertyDescription("Frames per second to sample (0.5 = one frame every 2s)")
  @JsonSchemaInject(json = """{ "minimum": 0.01, "maximum": 30.0 }""")
  var samplingFps: Double = 0.5

  @JsonProperty(required = true, defaultValue = "100")
  @JsonSchemaTitle("Max Frames")
  @JsonPropertyDescription("Cap on total frames emitted (protects against huge videos / API spend)")
  @JsonSchemaInject(json = """{ "minimum": 1, "maximum": 10000, "default": 100 }""")
  var maxFrames: Int = 100

  @JsonProperty(defaultValue = "75")
  @JsonSchemaTitle("JPEG Quality")
  @JsonPropertyDescription("0-100, lower = smaller base64 payload to downstream operators")
  @JsonSchemaInject(json = """{ "minimum": 10, "maximum": 100, "default": 75 }""")
  var jpegQuality: Int = 75

  fileTypeName = Option("Video")

  override def getPhysicalOp(
      workflowId: WorkflowIdentity,
      executionId: ExecutionIdentity
  ): PhysicalOp = {
    PhysicalOp
      .sourcePhysicalOp(
        workflowId,
        executionId,
        operatorIdentifier,
        OpExecWithCode(generatePythonCode(), "python")
      )
      .withInputPorts(operatorInfo.inputPorts)
      .withOutputPorts(operatorInfo.outputPorts)
      .withPropagateSchema(
        SchemaPropagationFunc(_ => Map(operatorInfo.outputPorts.head.id -> sourceSchema()))
      )
  }

  override def sourceSchema(): Schema =
    Schema()
      .add("frame_id", AttributeType.INTEGER)
      .add("timestamp_sec", AttributeType.DOUBLE)
      .add("frame_b64", AttributeType.STRING)

  private def generatePythonCode(): String = {
    val videoUri: EncodableString = fileName.getOrElse("")
    pyb"""from pytexera import *
       |import cv2
       |import base64
       |from urllib.parse import urlparse
       |
       |class ProcessTupleOperator(UDFSourceOperator):
       |    video_uri = $videoUri
       |    sampling_fps = $samplingFps
       |    max_frames = $maxFrames
       |    jpeg_quality = $jpegQuality
       |
       |    @overrides
       |    def produce(self) -> Iterator[Union[TupleLike, TableLike, None]]:
       |        if not self.video_uri:
       |            raise ValueError("Video file is not set")
       |        parsed = urlparse(self.video_uri)
       |        path = parsed.path if parsed.scheme in ("", "file") else self.video_uri
       |        cap = cv2.VideoCapture(path)
       |        if not cap.isOpened():
       |            raise IOError(f"Could not open video at {path}")
       |        try:
       |            src_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
       |            stride = max(1, int(round(src_fps / float(self.sampling_fps))))
       |            encode_params = [int(cv2.IMWRITE_JPEG_QUALITY), int(self.jpeg_quality)]
       |            emitted = 0
       |            frame_idx = 0
       |            while emitted < self.max_frames:
       |                ret, frame = cap.read()
       |                if not ret:
       |                    break
       |                if frame_idx % stride == 0:
       |                    ok, buf = cv2.imencode(".jpg", frame, encode_params)
       |                    if ok:
       |                        yield Tuple({
       |                            "frame_id": emitted,
       |                            "timestamp_sec": float(frame_idx / src_fps),
       |                            "frame_b64": base64.b64encode(buf.tobytes()).decode("ascii"),
       |                        })
       |                        emitted += 1
       |                frame_idx += 1
       |        finally:
       |            cap.release()""".encode
  }
}
