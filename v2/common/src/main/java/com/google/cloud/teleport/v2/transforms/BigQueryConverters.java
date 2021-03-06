/*
 * Copyright (C) 2019 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.teleport.v2.transforms;

import static org.apache.beam.vendor.guava.v20_0.com.google.common.base.Preconditions.checkArgument;

import com.google.api.services.bigquery.model.TableRow;
import com.google.auto.value.AutoValue;
import com.google.cloud.teleport.v2.transforms.JavascriptTextTransformer.JavascriptTextTransformerOptions;
import com.google.cloud.teleport.v2.values.FailsafeElement;
import com.google.gson.Gson;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.beam.sdk.coders.Coder.Context;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.TypedRead.Method;
import org.apache.beam.sdk.io.gcp.bigquery.TableRowJsonCoder;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.vendor.guava.v20_0.com.google.common.base.Throwables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common transforms for Teleport BigQueryIO. */
public class BigQueryConverters {

  /* Logger for class. */
  private static final Logger LOG = LoggerFactory.getLogger(BigQueryConverters.class);

  /**
   * Converts a JSON string to a {@link TableRow} object. If the data fails to convert, a {@link
   * RuntimeException} will be thrown.
   *
   * @param json The JSON string to parse.
   * @return The parsed {@link TableRow} object.
   */
  private static TableRow convertJsonToTableRow(String json) {
    TableRow row;
    // Parse the JSON into a {@link TableRow} object.
    try (InputStream inputStream =
        new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
      row = TableRowJsonCoder.of().decode(inputStream, Context.OUTER);

    } catch (IOException e) {
      throw new RuntimeException("Failed to serialize json to table row: " + json, e);
    }

    return row;
  }

  /**
   * The {@link TableRowToJsonFn} class converts a tableRow to Json using {@link #tableRowToJson(TableRow)}.
   */
  public static class TableRowToJsonFn extends DoFn<TableRow, String> {

    @ProcessElement
    public void processElement(ProcessContext context) {
      TableRow row = context.element();
      context.output(tableRowToJson(row));
    }
  }
  
  /** Converts a {@link TableRow} into a Json string using {@link Gson}. */
  private static String tableRowToJson(TableRow row) { return new Gson().toJson(row, TableRow.class); }

  /**
   * The {@link BigQueryReadOptions} interface contains option necessary to interface with BigQuery.
   */
  public interface BigQueryReadOptions extends PipelineOptions {
    @Description("BigQuery table to export from in the form <project>:<dataset>.<table>")
    String getInputTableSpec();

    void setInputTableSpec(String inputTableSpec);

    @Description(
        "The dead-letter table to output to within BigQuery in <project-id>:<dataset>.<table> "
            + "format. If it doesn't exist, it will be created during pipeline execution.")
    String getOutputDeadletterTable();

    void setOutputDeadletterTable(String outputDeadletterTable);

    @Description("Optional: Query to run against input table")
    String getQuery();

    void setQuery(String query);

    @Description("Set to true to use legacy SQL. Default:false")
    @Default.Boolean(false)
    Boolean getUseLegacySql();

    void setUseLegacySql(Boolean useLegacySql);
  }

  /**
   * The {@link FailsafeJsonToTableRow} transform converts JSON strings to {@link TableRow} objects.
   * The transform accepts a {@link FailsafeElement} object so the original payload of the incoming
   * record can be maintained across multiple series of transforms.
   */
  @AutoValue
  public abstract static class FailsafeJsonToTableRow<T>
      extends PTransform<PCollection<FailsafeElement<T, String>>, PCollectionTuple> {

    public static <T> Builder<T> newBuilder() {
      return new AutoValue_BigQueryConverters_FailsafeJsonToTableRow.Builder<>();
    }

    public abstract TupleTag<TableRow> successTag();

    public abstract TupleTag<FailsafeElement<T, String>> failureTag();

    @Override
    public PCollectionTuple expand(PCollection<FailsafeElement<T, String>> failsafeElements) {
      return failsafeElements.apply(
          "JsonToTableRow",
          ParDo.of(
                  new DoFn<FailsafeElement<T, String>, TableRow>() {
                    @ProcessElement
                    public void processElement(ProcessContext context) {
                      FailsafeElement<T, String> element = context.element();
                      String json = element.getPayload();

                      try {
                        TableRow row = convertJsonToTableRow(json);
                        context.output(row);
                      } catch (Exception e) {
                        context.output(
                            failureTag(),
                            FailsafeElement.of(element)
                                .setErrorMessage(e.getMessage())
                                .setStacktrace(Throwables.getStackTraceAsString(e)));
                      }
                    }
                  })
              .withOutputTags(successTag(), TupleTagList.of(failureTag())));
    }

    /** Builder for {@link FailsafeJsonToTableRow}. */
    @AutoValue.Builder
    public abstract static class Builder<T> {

      public abstract Builder<T> setSuccessTag(TupleTag<TableRow> successTag);

      public abstract Builder<T> setFailureTag(TupleTag<FailsafeElement<T, String>> failureTag);

      public abstract FailsafeJsonToTableRow<T> build();
    }
  }

  /**
   * The {@link ReadBigQuery} class reads from BigQuery using {@link BigQueryIO}. The transform
   * returns a {@link PCollection} of {@link TableRow}.
   */
  @AutoValue
  public abstract static class ReadBigQuery extends PTransform<PBegin, PCollection<TableRow>> {

    public static Builder newBuilder() {
      return new AutoValue_BigQueryConverters_ReadBigQuery.Builder();
    }

    public abstract BigQueryReadOptions options();

    @Override
    public PCollection<TableRow> expand(PBegin pipeline) {

      if (options().getQuery() == null) {
        LOG.info("No query provided, reading directly from: " + options().getInputTableSpec());
        return pipeline.apply(
            "ReadFromBigQuery",
            BigQueryIO.readTableRows()
                .from(options().getInputTableSpec())
                .withTemplateCompatibility()
                .withMethod(Method.DIRECT_READ)
                .withCoder(TableRowJsonCoder.of()));

      } else {
        LOG.info("Using query: " + options().getQuery());

        if (!options().getUseLegacySql()) {

          LOG.info("Using Standard SQL");
          return pipeline.apply(
              "ReadFromBigQueryWithQuery",
              BigQueryIO.readTableRows()
                  .fromQuery(options().getQuery())
                  .withTemplateCompatibility()
                  .usingStandardSql()
                  .withCoder(TableRowJsonCoder.of()));
        } else {

          LOG.info("Using Legacy SQL");
          return pipeline.apply(
              "ReadFromBigQueryWithQuery",
              BigQueryIO.readTableRows()
                  .fromQuery(options().getQuery())
                  .withTemplateCompatibility()
                  .withCoder(TableRowJsonCoder.of()));
        }
      }
    }

    /** Builder for {@link ReadBigQuery}. */
    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder setOptions(BigQueryReadOptions options);

      abstract ReadBigQuery autoBuild();

      public ReadBigQuery build() {

        ReadBigQuery readBigQuery = autoBuild();

        if (readBigQuery.options().getInputTableSpec() == null) {
          checkArgument(
              readBigQuery.options().getQuery() != null,
              "If no inputTableSpec is provided then a query is required.");
        }

        if (readBigQuery.options().getQuery() == null) {
          checkArgument(
              readBigQuery.options().getInputTableSpec() != null,
              "If no query is provided then an inputTableSpec is required.");
        }

        return readBigQuery;
      }
    }
  }

  /**
   * The {@link TableRowToFailsafeJsonDocument} class is a {@link PTransform} which transforms {@link
   * TableRow} objects into Json documents for insertion into Elasticsearch. Optionally a javascript
   * UDF can be supplied to parse the {@link TableRow} object. The executions of the UDF and
   * transformation to {@link TableRow} objects is done in a fail-safe way by wrapping the element
   * with it's original payload inside the {@link FailsafeElement} class. The {@link
   * TableRowToFailsafeJsonDocument} transform will output a {@link PCollectionTuple} which contains all
   * output and dead-letter {@link PCollection}.
   *
   * <p>The {@link PCollectionTuple} output will contain the following {@link PCollection}:
   *
   * <ul>
   *   <li>{@link TableRowToFailsafeJsonDocument#transformOutTag()} - Contains all records successfully
   *       converted from JSON to {@link TableRow} objects.
   *   <li>{@link TableRowToFailsafeJsonDocument#transformDeadletterOutTag()} - Contains all {@link
   *       FailsafeElement} records which couldn't be converted to table rows.
   * </ul>
   */
  @AutoValue
  public abstract static class TableRowToFailsafeJsonDocument
      extends PTransform<PCollection<TableRow>, PCollectionTuple> {

    public static Builder newBuilder() {
      return new AutoValue_BigQueryConverters_TableRowToFailsafeJsonDocument.Builder();
    }

    public abstract JavascriptTextTransformerOptions options();

    public abstract TupleTag<FailsafeElement<TableRow, String>> udfOutTag();

    public abstract TupleTag<FailsafeElement<TableRow, String>> udfDeadletterOutTag();

    public abstract TupleTag<FailsafeElement<TableRow, String>> transformOutTag();

    public abstract TupleTag<FailsafeElement<TableRow, String>> transformDeadletterOutTag();

    @Override
    public PCollectionTuple expand(PCollection<TableRow> input) {

      PCollectionTuple udfOut;

      PCollectionTuple failsafeTableRows =
          input.apply(
              "TableRowToFailsafeElement",
              ParDo.of(new TableRowToFailsafeElementFn(transformDeadletterOutTag()))
                  .withOutputTags(transformOutTag(), TupleTagList.of(transformDeadletterOutTag())));

      // Use Udf to parse table rows if supplied.
      if (options().getJavascriptTextTransformGcsPath() != null) {
        udfOut =
            failsafeTableRows
                .get(transformOutTag())
                .apply(
                    "ProcessFailsafeRowsUdf",
                    JavascriptTextTransformer.FailsafeJavascriptUdf.<TableRow>newBuilder()
                        .setFileSystemPath(options().getJavascriptTextTransformGcsPath())
                        .setFunctionName(options().getJavascriptTextTransformFunctionName())
                        .setSuccessTag(udfOutTag())
                        .setFailureTag(udfDeadletterOutTag())
                        .build());

        PCollection<FailsafeElement<TableRow, String>> failedOut =
            PCollectionList.of(udfOut.get(udfDeadletterOutTag()))
                .and(failsafeTableRows.get(transformDeadletterOutTag()))
                .apply("FlattenFailedOut", Flatten.pCollections());

        return PCollectionTuple.of(transformOutTag(), udfOut.get(udfOutTag()))
            .and(transformDeadletterOutTag(), failedOut);
      } else {
        return failsafeTableRows;
      }
    }

    /** Builder for {@link TableRowToFailsafeJsonDocument}. */
    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder setOptions(JavascriptTextTransformerOptions options);

      public abstract Builder setTransformOutTag(
          TupleTag<FailsafeElement<TableRow, String>> transformOutTag);

      public abstract Builder setTransformDeadletterOutTag(
          TupleTag<FailsafeElement<TableRow, String>> transformDeadletterOutTag);

      public abstract Builder setUdfOutTag(TupleTag<FailsafeElement<TableRow, String>> udfOutTag);

      public abstract Builder setUdfDeadletterOutTag(
          TupleTag<FailsafeElement<TableRow, String>> udfDeadletterOutTag);

      public abstract TableRowToFailsafeJsonDocument build();
    }
  }

  /**
   * The {@link TableRowToFailsafeElementFn} wraps an {@link TableRow} with the {@link
   * FailsafeElement} class so errors can be recovered from and the original message can be output
   * to a error records table.
   */
  static class TableRowToFailsafeElementFn
      extends DoFn<TableRow, FailsafeElement<TableRow, String>> {
    
    private final TupleTag<FailsafeElement<TableRow, String>> transformDeadletterOutTag;

    /** {@link Counter} for successfully processed elements. */
    private Counter successCounter =
        Metrics.counter(TableRowToFailsafeElementFn.class, "SuccessProcessCounter");

    /** {@link Counter} for un-successfully processed elements. */
    private Counter failedCounter =
        Metrics.counter(TableRowToFailsafeElementFn.class, "FailedProcessCounter");

    TableRowToFailsafeElementFn(
        TupleTag<FailsafeElement<TableRow, String>> transformDeadletterOutTag) {

      this.transformDeadletterOutTag = transformDeadletterOutTag;
    }

    @ProcessElement
    public void processElement(ProcessContext context) {
      TableRow row = context.element();
      try {
        context.output(FailsafeElement.of(row, tableRowToJson(row)));
        successCounter.inc();
      } catch (Exception e) {
        context.output(
            this.transformDeadletterOutTag,
            FailsafeElement.of(row, row.toString())
                .setErrorMessage(e.getMessage())
                .setStacktrace(Throwables.getStackTraceAsString(e)));
        failedCounter.inc();
      }
    }
  }

  /**
   * The {@link FailsafeTableRowToFailsafeStringFn} converts a {@link FailsafeElement} containing a
   * {@link TableRow} and string into a {@link FailsafeElement} containing two strings. The output
   * {@link FailsafeElement#getOriginalPayload()} will return {@link TableRow#toString()}.
   */
  public static class FailsafeTableRowToFailsafeStringFn
      extends DoFn<FailsafeElement<TableRow, String>, FailsafeElement<String, String>> {

    @ProcessElement
    public void processElement(ProcessContext context) {
      FailsafeElement<TableRow, String> element = context.element();
      context.output(
          FailsafeElement.of(element.getOriginalPayload().toString(), element.getPayload()));
    }
  }
}
