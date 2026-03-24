---
name: fix-exchange-onboarding
description: Generate a production-ready Databricks Scala pipeline for parsing FIX protocol messages from a new exchange. Scaffolds UDFs, data ingestion, VARIANT conversion, and Delta table writes. Use when onboarding a new exchange, adding a FIX data source, building a FIX parser pipeline, or extending the UDP with new exchange connectivity.
---

# FIX Exchange Onboarding

Generates a complete Databricks pipeline for parsing FIX (Financial Information eXchange) protocol messages from a new exchange into structured Delta tables using Unity Catalog.

## Prerequisites

Before starting, confirm the user has:
1. The exchange's **FIX data dictionary XML** file(s) uploaded to a Unity Catalog Volume
2. **Raw FIX message files** (logs or CSVs) accessible on a Volume
3. A **target catalog/schema** in Unity Catalog for the output table

## Workflow

### Step 1: Gather Exchange Configuration

Collect these inputs from the user (use AskQuestion when available):

| Parameter | Description | Example |
|-----------|-------------|---------|
| `exchange_name` | Short identifier (uppercase) | `EURONEXT`, `CME`, `NASDAQ` |
| `fix_version` | FIX protocol version | `4.2`, `4.4`, `5.0SP2` |
| `dictionary_mode` | Single dict or transport+application split | `single` for FIX <=4.4, `dual` for 5.0+ |
| `transport_dict_path` | Volume path to transport dictionary XML | `/Volumes/users/.../EURONEXT.FIX44.xml` |
| `application_dict_path` | Volume path to app dictionary (if dual) | `/Volumes/users/.../EURONEXT.FIX50SP2.xml` |
| `source_format` | Raw data file format | `text` (one FIX msg per line) or `csv` |
| `source_path` | Volume path/glob for source files | `/Volumes/data/euronext/*.log` |
| `csv_fix_column` | Column containing FIX message (csv only) | `_c11` |
| `target_catalog` | Unity Catalog catalog name | `prod_trading` |
| `target_schema` | Schema name | `fix_parsed` |
| `target_table` | Table name | `euronext_messages` |
| `quarantine_table` | Table for unparseable messages | `euronext_quarantine` |

### Step 2: Validate the FIX Dictionary

Before generating the pipeline, create a validation cell to test the dictionary:

```scala
import com.databricks.sparkfixencoder.provider.DatabricksFIXMessageParser
import quickfix.DataDictionary

// Validate dictionary loads without error
val parser = new DatabricksFIXMessageParser()
// For single dictionary (FIX 4.x):
parser.addFIXDataSource("{{transport_dict_path}}")
// For dual dictionary (FIX 5.0+):
// parser.addFIXDataSource("{{transport_dict_path}}", "{{application_dict_path}}")

println(s"Dictionary loaded successfully for {{exchange_name}}")
```

If this fails, direct the user to the debugging guide in [reference.md](reference.md#dictionary-troubleshooting).

### Step 3: Generate the Pipeline Notebook

Read the template at [templates/exchange-pipeline.scala](templates/exchange-pipeline.scala) and substitute all `{{placeholder}}` values with the gathered configuration.

The generated notebook contains these sections:
1. **Configuration** — all exchange parameters as notebook widgets
2. **Parser initialization** — dictionary loading with validation
3. **UDF definition** — serialization-safe `@transient lazy val` pattern
4. **Data ingestion** — format-appropriate reader with metadata columns
5. **Transformation** — FIX → JSON → VARIANT with error routing
6. **Write** — merge into target Delta table + quarantine table for failures
7. **Validation** — row counts and sample output

### Step 4: Generate the Exchange Config

Create a JSON config file from [exchange-config-template.json](exchange-config-template.json) with the gathered parameters. Save it to the project as `configs/{{exchange_name_lower}}-config.json`.

### Step 5: Output Checklist

After generating, present this checklist to the user:

```
Exchange Onboarding Checklist — {{exchange_name}}:
- [ ] FIX dictionary XML uploaded to Volume
- [ ] Dictionary validation cell runs without error
- [ ] Raw data files accessible at source path
- [ ] Target catalog/schema exists in Unity Catalog
- [ ] Pipeline notebook generated and reviewed
- [ ] Test run with small data subset
- [ ] Quarantine table reviewed for parsing errors
- [ ] Schedule as Databricks Job (if ready for production)
```

## Key Design Decisions

### UDF Serialization Pattern

Always use the `@transient lazy val` pattern for the parser. This is critical for distributed Spark execution — each executor lazily initializes its own parser instance:

```scala
class ExchangeParseUDF(dictPath: String) extends UDF1[String, String] with Serializable {
  @transient private lazy val parser: DatabricksFIXMessageParser = {
    val p = new DatabricksFIXMessageParser()
    p.addFIXDataSource(dictPath)
    p
  }
  override def call(s: String): String = parser.parseFIXMessageToJSONString(s)
}
```

Never pass the parser instance directly — it is not serializable.

### Error Handling Strategy

Route unparseable messages to a quarantine table instead of failing the pipeline:

```scala
val parseUDF = udf((msg: String) => {
  try { Some(parser.parseFIXMessageToJSONString(msg)) }
  catch { case _: Exception => None }
})

val parsed = df.withColumn("jsonData", parseUDF(col("fixMessage")))
val success = parsed.filter(col("jsonData").isNotNull)
val quarantine = parsed.filter(col("jsonData").isNull)
```

### Dictionary Mode Decision

| FIX Version | Dictionary Mode | Dictionaries Needed |
|-------------|-----------------|---------------------|
| FIX 4.0–4.4 | `single` | 1 transport dictionary |
| FIX 5.0+ / FIXT 1.1 | `dual` | 1 transport + 1 application dictionary |

## Additional Resources

- For FIX protocol details and dictionary troubleshooting, see [reference.md](reference.md)
- For worked examples (ICE, LME, Euronext), see [examples.md](examples.md)
