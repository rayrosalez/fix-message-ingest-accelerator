// Databricks notebook source
// =============================================================================
// Universal FIX Exchange Parser
// =============================================================================
// A single parameterized notebook that parses FIX messages from ANY exchange.
// Fill in the widgets at the top, attach spark-fix-encoder-1.0.jar to your
// cluster, and run. Works for ICE, LME, Euronext, CME, or any new exchange.
// =============================================================================

// COMMAND ----------

// -- Widget Configuration --
// Fill these in for your exchange. All paths should be Unity Catalog Volumes.

dbutils.widgets.text("exchange_name", "", "Exchange Name (e.g. EURONEXT)")
dbutils.widgets.dropdown("fix_version", "4.4", Seq("4.0", "4.2", "4.4", "5.0SP2"), "FIX Version")
dbutils.widgets.dropdown("dictionary_mode", "single", Seq("single", "dual"), "Dictionary Mode (single=FIX 4.x, dual=FIX 5.0+)")
dbutils.widgets.text("transport_dict_path", "", "Transport Dictionary XML Path")
dbutils.widgets.text("application_dict_path", "", "Application Dictionary XML Path (FIX 5.0+ only)")
dbutils.widgets.dropdown("source_format", "text", Seq("text", "csv"), "Source File Format")
dbutils.widgets.text("source_path", "", "Source File Path or Glob Pattern")
dbutils.widgets.text("csv_fix_column", "_c11", "CSV Column with FIX Message (csv only)")
dbutils.widgets.text("target_table", "", "Target Table (catalog.schema.table)")
dbutils.widgets.text("quarantine_table", "", "Quarantine Table (catalog.schema.table)")

// COMMAND ----------

// -- Read Widget Values --

val exchangeName     = dbutils.widgets.get("exchange_name").trim.toUpperCase
val fixVersion       = dbutils.widgets.get("fix_version")
val dictionaryMode   = dbutils.widgets.get("dictionary_mode")
val transportDictPath   = dbutils.widgets.get("transport_dict_path").trim
val applicationDictPath = dbutils.widgets.get("application_dict_path").trim
val sourceFormat     = dbutils.widgets.get("source_format")
val sourcePath       = dbutils.widgets.get("source_path").trim
val csvFixColumn     = dbutils.widgets.get("csv_fix_column").trim
val targetTable      = dbutils.widgets.get("target_table").trim
val quarantineTable  = dbutils.widgets.get("quarantine_table").trim

require(exchangeName.nonEmpty,    "exchange_name is required")
require(transportDictPath.nonEmpty, "transport_dict_path is required")
require(sourcePath.nonEmpty,      "source_path is required")
require(targetTable.nonEmpty,     "target_table is required")
require(quarantineTable.nonEmpty,  "quarantine_table is required")
if (dictionaryMode == "dual") {
  require(applicationDictPath.nonEmpty, "application_dict_path is required for dual dictionary mode (FIX 5.0+)")
}

println(s"Exchange:         $exchangeName")
println(s"FIX Version:      $fixVersion")
println(s"Dictionary Mode:  $dictionaryMode")
println(s"Source Format:    $sourceFormat")
println(s"Source Path:      $sourcePath")
println(s"Target Table:     $targetTable")
println(s"Quarantine Table: $quarantineTable")

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
// Fail fast if the dictionary can't be loaded before processing any data.

val validationParser = new DatabricksFIXMessageParser()
try {
  if (dictionaryMode == "dual") {
    validationParser.addFIXDataSource(transportDictPath, applicationDictPath)
  } else {
    validationParser.addFIXDataSource(transportDictPath)
  }
  println(s"SUCCESS: $exchangeName FIX dictionary loaded and validated.")
} catch {
  case e: ConfigError =>
    throw new RuntimeException(
      s"FATAL: Failed to load $exchangeName dictionary at $transportDictPath — ${e.getMessage}\n" +
      "Check: Is the XML well-formed? Is the encoding UTF-8? Does the root element have a 'major' attribute?", e
    )
}

// COMMAND ----------

// -- Parse UDF (Serialization-Safe) --
// Each Spark executor lazily initializes its own parser instance.
// The dictionary paths (Strings) are serializable; the parser object is not.

class FIXParseUDF(
    dictPath: String,
    appDictPath: String,
    dictMode: String
) extends UDF1[String, String] with Serializable {

  @transient private lazy val fixParser: DatabricksFIXMessageParser = {
    val p = new DatabricksFIXMessageParser()
    if (dictMode == "dual" && appDictPath.nonEmpty) {
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

val parseUDF = new FIXParseUDF(transportDictPath, applicationDictPath, dictionaryMode)
spark.udf.register(s"parseUDF_$exchangeName", parseUDF, DataTypes.StringType)

println(s"UDF registered as parseUDF_$exchangeName")

// COMMAND ----------

// -- Data Ingestion --

val sourceDF = if (sourceFormat == "csv") {
  val rawDF = spark.read
    .format("csv")
    .option("header", "false")
    .option("inferSchema", "false")
    .load(sourcePath)
  rawDF
    .withColumn("filePath", input_file_name())
    .withColumn("fileName", regexp_replace(col("filePath"), ".*[/\\\\]", ""))
    .withColumn("rowNumber", row_number().over(
      Window.partitionBy(input_file_name()).orderBy(monotonically_increasing_id())
    ))
    .select(
      col(csvFixColumn).alias("fixMessage"),
      col("fileName"),
      col("rowNumber")
    )
} else {
  val rawDF = spark.read.text(sourcePath)
  rawDF
    .withColumn("filePath", input_file_name())
    .withColumn("fileName", regexp_replace(col("filePath"), ".*[/\\\\]", ""))
    .withColumn("rowNumber", row_number().over(
      Window.partitionBy(input_file_name()).orderBy(monotonically_increasing_id())
    ))
    .select(
      col("value").alias("fixMessage"),
      col("fileName"),
      col("rowNumber")
    )
}

val ingestedCount = sourceDF.count()
println(s"Ingested $ingestedCount raw messages from $exchangeName")
sourceDF.groupBy("fileName").agg(count("*").alias("recordCount")).orderBy("fileName").show(false)

// COMMAND ----------

// -- Transformation: FIX → JSON → VARIANT --

val parsedDF = sourceDF
  .withColumn("jsonData", call_udf(s"parseUDF_$exchangeName", col("fixMessage")))

val successDF = parsedDF
  .filter(col("jsonData").isNotNull)
  .withColumn("fixVariant", expr("CAST(jsonData AS VARIANT)"))
  .withColumn("exchange", lit(exchangeName))
  .withColumn("parsedAt", current_timestamp())

val quarantineDF = parsedDF
  .filter(col("jsonData").isNull)
  .withColumn("exchange", lit(exchangeName))
  .withColumn("quarantinedAt", current_timestamp())
  .select("fixMessage", "fileName", "rowNumber", "exchange", "quarantinedAt")

val totalCount = parsedDF.count()
val successCount = successDF.count()
val quarantineCount = quarantineDF.count()

println(s"\n$exchangeName Parsing Summary:")
println(s"  Total messages:       $totalCount")
println(s"  Parsed successfully:  $successCount")
println(s"  Quarantined (errors): $quarantineCount")
if (totalCount > 0) {
  println(f"  Success rate:         ${successCount.toDouble / totalCount * 100}%.1f%%")
}

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
} else {
  println("No quarantined messages — all messages parsed successfully.")
}

// COMMAND ----------

// -- Post-Write Validation --

println(s"\n=== Post-Write Validation for $exchangeName ===\n")

val writtenCount = spark.table(targetTable).filter(col("exchange") === exchangeName).count()
println(s"Total rows in $targetTable for $exchangeName: $writtenCount")

println("\nSample parsed output:")
spark.table(targetTable)
  .filter(col("exchange") === exchangeName)
  .select("fileName", "rowNumber", "jsonData")
  .orderBy(desc("parsedAt"))
  .limit(5)
  .show(false)

if (quarantineCount > 0) {
  println(s"\nSample quarantined messages (review these for dictionary/format issues):")
  spark.table(quarantineTable)
    .filter(col("exchange") === exchangeName)
    .select("fileName", "rowNumber", "fixMessage")
    .limit(5)
    .show(100, truncate = false)
}

println(s"\n$exchangeName pipeline complete.")
