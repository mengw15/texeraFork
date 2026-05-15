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

package org.apache.texera.amber.operator.vision

import com.fasterxml.jackson.annotation.{JsonProperty, JsonPropertyDescription}
import com.kjetland.jackson.jsonSchema.annotations.{JsonSchemaInject, JsonSchemaTitle}
import org.apache.texera.amber.core.tuple.{AttributeType, Schema}
import org.apache.texera.amber.core.workflow.{InputPort, OutputPort, PortIdentity}
import org.apache.texera.amber.operator.PythonOperatorDescriptor
import org.apache.texera.amber.operator.metadata.annotations.AutofillAttributeName
import org.apache.texera.amber.operator.metadata.{OperatorGroupConstants, OperatorInfo}
import org.apache.texera.amber.pybuilder.PyStringTypes.EncodableString
import org.apache.texera.amber.pybuilder.PythonTemplateBuilder.PythonTemplateBuilderStringContext

class VisionLLMFrameOpDesc extends PythonOperatorDescriptor {

  @JsonProperty(required = true, defaultValue = "frame_b64")
  @JsonSchemaTitle("Frame Column")
  @JsonPropertyDescription("Column holding base64-encoded JPEG frames")
  @AutofillAttributeName
  var frameColumn: EncodableString = _

  @JsonProperty(required = true)
  @JsonSchemaTitle("Prompt")
  @JsonPropertyDescription(
    "Instruction for the vision model. Ask it to respond in JSON if you plan to parse fields downstream."
  )
  var prompt: EncodableString = _

  @JsonProperty(required = true, defaultValue = "llm_output")
  @JsonSchemaTitle("Output Column")
  @JsonPropertyDescription("Name of the column to hold the LLM response text")
  var outputColumn: EncodableString = _

  @JsonProperty(required = true)
  @JsonSchemaTitle("Anthropic API Key")
  @JsonPropertyDescription("Anthropic API key (sk-ant-...)")
  var apiKey: EncodableString = _

  @JsonProperty(required = true, defaultValue = "claude-sonnet-4-6")
  @JsonSchemaTitle("Model")
  @JsonPropertyDescription("Anthropic model id, e.g. claude-sonnet-4-6, claude-opus-4-7")
  var model: EncodableString = _

  @JsonProperty(defaultValue = "1024")
  @JsonSchemaTitle("Max Tokens")
  @JsonPropertyDescription("Upper bound on response tokens per frame")
  @JsonSchemaInject(json = """{ "minimum": 16, "maximum": 4096, "default": 1024 }""")
  var maxTokens: Int = 1024

  override def operatorInfo: OperatorInfo =
    OperatorInfo(
      "Vision LLM Frame",
      "Run a prompt against each video frame with a vision-capable LLM (Anthropic Claude)",
      OperatorGroupConstants.MACHINE_LEARNING_GENERAL_GROUP,
      inputPorts = List(InputPort()),
      outputPorts = List(OutputPort())
    )

  override def getOutputSchemas(
      inputSchemas: Map[PortIdentity, Schema]
  ): Map[PortIdentity, Schema] = {
    if (outputColumn == null || outputColumn.trim.isEmpty) return null
    Map(
      operatorInfo.outputPorts.head.id ->
        inputSchemas(operatorInfo.inputPorts.head.id).add(outputColumn, AttributeType.STRING)
    )
  }

  override def generatePythonCode(): String = {
    pyb"""from pytexera import *
       |import anthropic
       |
       |class ProcessTupleOperator(UDFOperatorV2):
       |    frame_column = $frameColumn
       |    output_column = $outputColumn
       |    prompt = $prompt
       |    model = $model
       |    api_key = $apiKey
       |    max_tokens = $maxTokens
       |
       |    def open(self):
       |        if not self.api_key:
       |            raise ValueError("Anthropic API key is required")
       |        if not self.prompt:
       |            raise ValueError("Prompt is required")
       |        self.client = anthropic.Anthropic(api_key=self.api_key)
       |
       |    @overrides
       |    def process_tuple(self, tuple_: Tuple, port: int) -> Iterator[Optional[TupleLike]]:
       |        frame_b64 = tuple_[self.frame_column]
       |        if not frame_b64:
       |            tuple_[self.output_column] = None
       |            yield tuple_
       |            return
       |        resp = self.client.messages.create(
       |            model=self.model,
       |            max_tokens=int(self.max_tokens),
       |            messages=[{
       |                "role": "user",
       |                "content": [
       |                    {
       |                        "type": "image",
       |                        "source": {
       |                            "type": "base64",
       |                            "media_type": "image/jpeg",
       |                            "data": frame_b64,
       |                        },
       |                    },
       |                    {"type": "text", "text": self.prompt},
       |                ],
       |            }],
       |        )
       |        text_parts = [block.text for block in resp.content if block.type == "text"]
       |        tuple_[self.output_column] = "".join(text_parts)
       |        yield tuple_""".encode
  }
}
