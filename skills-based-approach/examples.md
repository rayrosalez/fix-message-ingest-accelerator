# FIX Exchange Onboarding — Worked Examples

## Example 1: Onboard ICE (Already Deployed)

**Configuration gathered:**
```json
{
  "exchange_name": "ICE",
  "fix_version": "4.2",
  "dictionary_mode": "single",
  "transport_dict_path": "/Volumes/users/josh_seidel/FIX/ICE_POF.FIX42.xml",
  "source_format": "text",
  "source_path": "/Volumes/users/josh_seidel/FIX/ICE/orders_sfi_sfl_futures_ICE_*.log",
  "target_catalog": "prod_trading",
  "target_schema": "fix_parsed",
  "target_table": "ice_fix_messages",
  "quarantine_table": "ice_fix_quarantine"
}
```

**Generated UDF instantiation (single dict):**
```scala
val parseUDF = new ICEParseUDF(transportDictPath)
spark.udf.register("parseUDF_ICE", parseUDF, DataTypes.StringType)
```

**Generated ingestion block (text format):**
```scala
val rawDF = spark.read.text(sourcePath)
val sourceDF = rawDF
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
```

---

## Example 2: Onboard LME (Already Deployed)

**Configuration gathered:**
```json
{
  "exchange_name": "LME",
  "fix_version": "5.0SP2",
  "dictionary_mode": "dual",
  "transport_dict_path": "/Volumes/users/josh_seidel/FIX/LME_Select.FIXT.1.1.xml",
  "application_dict_path": "/Volumes/users/josh_seidel/FIX/LME_Select.FIX50SP2.xml",
  "source_format": "csv",
  "source_path": "/Volumes/users/josh_seidel/FIX/LME/00.csv",
  "csv_fix_column": "_c11",
  "target_catalog": "prod_trading",
  "target_schema": "fix_parsed",
  "target_table": "lme_fix_messages",
  "quarantine_table": "lme_fix_quarantine"
}
```

**Generated UDF instantiation (dual dict):**
```scala
val parseUDF = new LMEParseUDF(transportDictPath, applicationDictPath)
spark.udf.register("parseUDF_LME", parseUDF, DataTypes.StringType)
```

**Generated ingestion block (CSV format):**
```scala
val rawDF = spark.read
  .format("csv")
  .option("header", "false")
  .option("inferSchema", "false")
  .load(sourcePath)
val sourceDF = rawDF
  .withColumn("filePath", input_file_name())
  .withColumn("fileName", regexp_replace(col("filePath"), ".*[/\\\\]", ""))
  .withColumn("rowNumber", row_number().over(
    Window.partitionBy(input_file_name()).orderBy(monotonically_increasing_id())
  ))
  .select(
    col("_c11").alias("fixMessage"),
    col("fileName"),
    col("rowNumber")
  )
```

---

## Example 3: Onboard Euronext (New Exchange)

This is a walkthrough of what the skill generates for a new exchange.

**User says:** "Onboard Euronext into the FIX pipeline"

**Skill gathers configuration:**
```json
{
  "exchange_name": "EURONEXT",
  "fix_version": "4.4",
  "dictionary_mode": "single",
  "transport_dict_path": "/Volumes/users/josh_seidel/FIX/EURONEXT_OPTIQ.FIX44.xml",
  "source_format": "text",
  "source_path": "/Volumes/data/euronext/orders_optiq_*.log",
  "target_catalog": "prod_trading",
  "target_schema": "fix_parsed",
  "target_table": "euronext_fix_messages",
  "quarantine_table": "euronext_fix_quarantine"
}
```

**Skill generates complete notebook with these substitutions:**

### Dictionary validation cell:
```scala
val validationParser = new DatabricksFIXMessageParser()
try {
  validationParser.addFIXDataSource(transportDictPath)
  println(s"SUCCESS: EURONEXT FIX dictionary loaded and validated.")
} catch {
  case e: ConfigError =>
    throw new RuntimeException(
      s"FATAL: Failed to load EURONEXT dictionary at $transportDictPath — ${e.getMessage}", e
    )
}
```

### UDF cell:
```scala
class EURONEXTParseUDF(dictPath: String)
    extends UDF1[String, String] with Serializable {

  @transient private lazy val fixParser: DatabricksFIXMessageParser = {
    val p = new DatabricksFIXMessageParser()
    p.addFIXDataSource(dictPath)
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

val parseUDF = new EURONEXTParseUDF(transportDictPath)
spark.udf.register("parseUDF_EURONEXT", parseUDF, DataTypes.StringType)
```

### Ingestion cell (text format):
```scala
val rawDF = spark.read.text(sourcePath)
val sourceDF = rawDF
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
```

### Transform + write cells remain identical across all exchanges.

**Skill presents checklist:**
```
Exchange Onboarding Checklist — EURONEXT:
- [ ] FIX dictionary XML uploaded to Volume
- [ ] Dictionary validation cell runs without error
- [ ] Raw data files accessible at source path
- [ ] Target catalog/schema exists in Unity Catalog
- [ ] Pipeline notebook generated and reviewed
- [ ] Test run with small data subset
- [ ] Quarantine table reviewed for parsing errors
- [ ] Schedule as Databricks Job (if ready for production)
```

---

## Example 4: Onboard CME (New Exchange, CSV Format)

**Configuration:**
```json
{
  "exchange_name": "CME",
  "fix_version": "4.2",
  "dictionary_mode": "single",
  "transport_dict_path": "/Volumes/users/josh_seidel/FIX/CME_GLOBEX.FIX42.xml",
  "source_format": "csv",
  "source_path": "/Volumes/data/cme/drop_copy_*.csv",
  "csv_fix_column": "_c5",
  "target_catalog": "prod_trading",
  "target_schema": "fix_parsed",
  "target_table": "cme_fix_messages",
  "quarantine_table": "cme_fix_quarantine"
}
```

Key difference: CME uses CSV files with the FIX message in column `_c5`, so the ingestion block uses the CSV reader variant.
