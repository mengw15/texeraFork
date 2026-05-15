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

package org.apache.texera.amber.operator.visualization.videoTimeline

import com.fasterxml.jackson.annotation.{JsonProperty, JsonPropertyDescription}
import com.kjetland.jackson.jsonSchema.annotations.JsonSchemaTitle
import org.apache.texera.amber.core.tuple.{AttributeType, Schema}
import org.apache.texera.amber.core.workflow.PortIdentity
import org.apache.texera.amber.operator.PythonOperatorDescriptor
import org.apache.texera.amber.operator.metadata.annotations.AutofillAttributeName
import org.apache.texera.amber.operator.metadata.{OperatorGroupConstants, OperatorInfo}
import org.apache.texera.amber.pybuilder.PyStringTypes.EncodableString
import org.apache.texera.amber.pybuilder.PythonTemplateBuilder.PythonTemplateBuilderStringContext

class VideoTimelineSinkOpDesc extends PythonOperatorDescriptor {

  @JsonProperty(required = true, defaultValue = "timestamp_sec")
  @JsonSchemaTitle("Timestamp Column")
  @JsonPropertyDescription("Numeric column with seconds offset from start of video")
  @AutofillAttributeName
  var timestampColumn: EncodableString = _

  @JsonProperty(required = true)
  @JsonSchemaTitle("Label Column")
  @JsonPropertyDescription("Text column to use as the event label (e.g. LLM output)")
  @AutofillAttributeName
  var labelColumn: EncodableString = _

  @JsonProperty(defaultValue = "frame_b64")
  @JsonSchemaTitle("Frame Column (optional)")
  @JsonPropertyDescription(
    "Base64 JPEG column to render thumbnails. Leave blank to skip thumbnails."
  )
  @AutofillAttributeName
  var frameColumn: EncodableString = _

  override def operatorInfo: OperatorInfo =
    OperatorInfo.forVisualization(
      "Video Timeline",
      "Render an interactive timeline of events extracted from a video",
      OperatorGroupConstants.VISUALIZATION_MEDIA_GROUP
    )

  override def getOutputSchemas(
      inputSchemas: Map[PortIdentity, Schema]
  ): Map[PortIdentity, Schema] = {
    val outputSchema = Schema().add("html-content", AttributeType.STRING)
    Map(operatorInfo.outputPorts.head.id -> outputSchema)
  }

  override def generatePythonCode(): String = {
    pyb"""from pytexera import *
       |import plotly.express as px
       |import plotly.io
       |import html
       |
       |class ProcessTableOperator(UDFTableOperator):
       |    timestamp_column = $timestampColumn
       |    label_column = $labelColumn
       |    frame_column = $frameColumn
       |
       |    def render_error(self, msg):
       |        return f"<h3>Video Timeline unavailable</h3><p>{msg}</p>"
       |
       |    @overrides
       |    def process_table(self, table: Table, port: int) -> Iterator[Optional[TableLike]]:
       |        if table.empty:
       |            yield {"html-content": self.render_error("Input table is empty.")}
       |            return
       |        sorted_table = table.sort_values(self.timestamp_column).reset_index(drop=True)
       |        fig = px.scatter(
       |            sorted_table,
       |            x=self.timestamp_column,
       |            y=[0] * len(sorted_table),
       |            color=self.label_column,
       |            hover_data=[self.label_column],
       |            labels={self.timestamp_column: "Time (s)", "y": ""},
       |        )
       |        fig.update_yaxes(showticklabels=False, showgrid=False, zeroline=False)
       |        fig.update_traces(marker=dict(size=14, line=dict(width=1, color="DarkSlateGrey")))
       |        fig.update_layout(
       |            height=180,
       |            margin=dict(l=20, r=20, t=30, b=20),
       |            legend=dict(orientation="h", yanchor="bottom", y=1.02, xanchor="right", x=1),
       |        )
       |        timeline_html = plotly.io.to_html(fig, include_plotlyjs="cdn", full_html=False)
       |
       |        thumbnails_html = ""
       |        has_frames = self.frame_column and self.frame_column in sorted_table.columns
       |        if has_frames:
       |            cards = []
       |            for _, row in sorted_table.iterrows():
       |                ts = row[self.timestamp_column]
       |                label = html.escape(str(row[self.label_column])[:200])
       |                b64 = row[self.frame_column]
       |                if not b64:
       |                    continue
       |                cards.append(f'''
       |                    <div style="display:inline-block; width:200px; margin:6px; vertical-align:top;
       |                                border:1px solid #ddd; border-radius:6px; padding:6px; background:#fff;">
       |                      <img src="data:image/jpeg;base64,{b64}" style="width:100%; border-radius:4px;" />
       |                      <div style="font-size:11px; color:#888; margin-top:4px;">t = {ts:.1f}s</div>
       |                      <div style="font-size:12px; margin-top:2px;">{label}</div>
       |                    </div>
       |                ''')
       |            thumbnails_html = (
       |                '<div style="white-space:nowrap; overflow-x:auto; padding:8px; '
       |                'background:#fafafa; border-radius:6px;">' + "".join(cards) + '</div>'
       |            )
       |
       |        out = f'''
       |        <div style="font-family: -apple-system, sans-serif;">
       |          <div>{timeline_html}</div>
       |          {thumbnails_html}
       |        </div>
       |        '''
       |        yield {"html-content": out}""".encode
  }
}
