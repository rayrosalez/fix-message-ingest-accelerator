// Databricks notebook source
// =============================================================================
// {{EXCHANGE_NAME}} FIX Message Parser Pipeline
// =============================================================================
// Exchange:    {{EXCHANGE_NAME}}
// FIX Version: {{FIX_VERSION}}
// Dict Mode:   {{DICTIONARY_MODE}}
// Generated:   {{GENERATION_DATE}}
// =============================================================================

// COMMAND ----------

// -- Configuration (Databricks Widgets) --

dbutils.widgets.text("transport_dict_path", "{{TRANSPORT_DICT_PATH}}", "Transport Dictionary Path")
dbutils.widgets.text("application_dict_path", "{{APPLICATION_DICT_PATH}}", "Application Dictionary Path (FIX 5.0+ only)")
dbutils.widgets.text("source_path", "{{SOURCE_PATH}}", "Source File Path/Glob")
dbutils.widgets.text("target_table", "{{TARGET_CATALOG}}.{{TARGET_SCHEMA}}.{{TARGET_TABLE}}", "Target Delta Table")
dbutils.widgets.text("quarantine_table", "{{TARGET_CATALOG}}.{{TARGET_SCHEMA}}.{{QUARANTINE_TABLE}}", "Quarantine Delta Table")

val transportDictPath = dbutils.widgets.get("transport_dict_path")
val applicationDictPath = dbutils.widgets.get("application_dict_path")
val sourcePath = dbutils.widgets.get("source_path")
val targetTable = dbutils.widgets.get("target_table")
val quarantineTable = dbutils.widgets.get("quarantine_table")

// COMMAND ----------

// -- Imports --

import com.databricks.sparkfixencoder.provider.DatabricksFIXMessageParser
import quickfix.{ConfigError, DataDictionary, FieldNotFound, InvalidMessage}
import org.apache.spark.sql.api.java.UDF1
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.functions._
import org.apache.spark.sql.expressions.Window
import com.fasterxml.jackson.core.JsonProcessingException

// COMMAND ----------

// -- Dictionary Validation --
// Fail fast if the dictionary can't be loaded.

val validationParser = new DatabricksFIXMessageParser()
try {
  // {{DICT_LOAD_CALL}} — replaced by skill based on dictionary_mode
  // Single:  validationParser.addFIXDataSource(transportDictPath)
  // Dual:    validationParser.addFIXDataSource(transportDictPath, applicationDictPath)
  {{DICT_LOAD_CALL}}
  println(s"SUCCESS: {{EXCHANGE_NAME}} FIX dictionary loaded and validated.")
} catch {
  case e: ConfigError =>
    throw new RuntimeException(
      s"FATAL: Failed to load {{EXCHANGE_NAME}} dictionary at $transportDictPath — ${e.getMessage}", e
    )
}

// COMMAND ----------

// -- Parse UDF (Serialization-Safe) --
// Each Spark executor lazily initializes its own parser instance.
// The dictionary path (a String) is serializable; the parser object is not.

class {{EXCHANGE_NAME}}ParseUDF(
    dictPath: String,
    appDictPath: String = ""
) extends UDF1[String, String] with Serializable {

  @transient private lazy val fixParser: DatabricksFIXMessageParser = {
    val p = new DatabricksFIXMessageParser()
    if (appDictPath.nonEmpty) {
      p.addFIXDataSource(dictPath, appDictPath)
    } else {
      p.addFIXDataSource(dictPath)
    }
    p
  }

  override def call(fixMessage: String): String = {
    if (fixMessage == null || fixMessage.trim.isEmpty) return null
    try {
      fixParser.parseFIXMessageToJSONString(fixMessage)
    } catch {
      case _: InvalidMessage | _: FieldNotFound | _: JsonProcessingException => null
    }
  }
}

// {{UDF_INSTANTIATION}} — replaced by skill based on dictionary_mode
// Single:  val parseUDF = new {{EXCHANGE_NAME}}ParseUDF(transportDictPath)
// Dual:    val parseUDF = new {{EXCHANGE_NAME}}ParseUDF(transportDictPath, applicationDictPath)
{{UDF_INSTANTIATION}}
spark.udf.register("parseUDF_{{EXCHANGE_NAME}}", parseUDF, DataTypes.StringType)

// COMMAND ----------

// -- Data Ingestion --

// {{INGESTION_BLOCK}} — replaced by skill based on source_format
//
// TEXT FORMAT (one FIX message per line):
//   val rawDF = spark.read.text(sourcePath)
//   val sourceDF = rawDF
//     .withColumn("filePath", input_file_name())
//     .withColumn("fileName", regexp_replace(col("filePath"), ".*[/\\\\]", ""))
//     .withColumn("rowNumber", row_number().over(
//       Window.partitionBy(input_file_name()).orderBy(monotonically_increasing_id())
//     ))
//     .select(
//       col("value").alias("fixMessage"),
//       col("fileName"),
//       col("rowNumber")
//     )
//
// CSV FORMAT (FIX message in a specific column):
//   val rawDF = spark.read
//     .format("csv")
//     .option("header", "false")
//     .option("inferSchema", "false")
//     .load(sourcePath)
//   val sourceDF = rawDF
//     .withColumn("filePath", input_file_name())
//     .withColumn("fileName", regexp_replace(col("filePath"), ".*[/\\\\]", ""))
//     .withColumn("rowNumber", row_number().over(
//       Window.partitionBy(input_file_name()).orderBy(monotonically_increasing_id())
//     ))
//     .select(
//       col("{{CSV_FIX_COLUMN}}").alias("fixMessage"),
//       col("fileName"),
//       col("rowNumber")
//     )

{{INGESTION_BLOCK}}

println(s"Ingested ${sourceDF.count()} raw messages from {{EXCHANGE_NAME}}")
sourceDF.groupBy("fileName").agg(count("*").alias("recordCount")).show(false)

// COMMAND ----------

// -- Transformation: FIX → JSON → VARIANT --

val parsedDF = sourceDF
  .withColumn("jsonData", call_udf("parseUDF_{{EXCHANGE_NAME}}", col("fixMessage")))

val successDF = parsedDF
  .filter(col("jsonData").isNotNull)
  .withColumn("fixVariant", expr("CAST(jsonData AS VARIANT)"))
  .withColumn("exchange", lit("{{EXCHANGE_NAME}}"))
  .withColumn("parsedAt", current_timestamp())

val quarantineDF = parsedDF
  .filter(col("jsonData").isNull)
  .withColumn("exchange", lit("{{EXCHANGE_NAME}}"))
  .withColumn("quarantinedAt", current_timestamp())
  .select("fixMessage", "fileName", "rowNumber", "exchange", "quarantinedAt")

val totalCount = parsedDF.count()
val successCount = successDF.count()
val quarantineCount = quarantineDF.count()

println(s"{{EXCHANGE_NAME}} Parsing Summary:")
println(s"  Total messages:       $totalCount")
println(s"  Parsed successfully:  $successCount")
println(s"  Quarantined (errors): $quarantineCount")
println(f"  Success rate:         ${successCount.toDouble / totalCount * 100}%.1f%%")

// COMMAND ----------

// -- Write to Delta Tables --

successDF
  .select("fixMessage", "jsonData", "fixVariant", "fileName", "rowNumber", "exchange", "parsedAt")
  .write
  .mode("append")
  .option("mergeSchema", "true")
  .saveAsTable(targetTable)

println(s"Wrote $successCount parsed messages to $targetTable")

if (quarantineCount > 0) {
  quarantineDF
    .write
    .mode("append")
    .option("mergeSchema", "true")
    .saveAsTable(quarantineTable)

  println(s"Wrote $quarantineCount quarantined messages to $quarantineTable")
}

// COMMAND ----------

// -- Validation --

println(s"\n=== Post-Write Validation for {{EXCHANGE_NAME}} ===")
val writtenCount = spark.table(targetTable).filter(col("exchange") === "{{EXCHANGE_NAME}}").count()
println(s"Total rows in $targetTable for {{EXCHANGE_NAME}}: $writtenCount")

println("\nSample parsed output:")
spark.table(targetTable)
  .filter(col("exchange") === "{{EXCHANGE_NAME}}")
  .select("fileName", "rowNumber", "jsonData")
  .limit(5)
  .show(false)

if (quarantineCount > 0) {
  println(s"\nSample quarantined messages:")
  spark.table(quarantineTable)
    .filter(col("exchange") === "{{EXCHANGE_NAME}}")
    .select("fileName", "rowNumber", "fixMessage")
    .limit(5)
    .show(false)
}
