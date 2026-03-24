# FIX Exchange Onboarding — Reference Guide

## FIX Protocol Overview

FIX (Financial Information eXchange) is the standard messaging protocol for electronic trading. Each message is a sequence of `tag=value` pairs separated by SOH (`\x01`) delimiters.

Example raw FIX message:
```
8=FIX.4.2\x019=178\x0135=D\x0149=SENDER\x0156=TARGET\x0134=12\x0152=20250520-14:30:00\x0111=ORD001\x0121=1\x0155=AAPL\x0154=1\x0138=100\x0140=2\x0144=150.00\x0159=0\x0110=128\x01
```

### FIX Versions in Use

| Version | Protocol | Dictionary Files | Exchanges Using It |
|---------|----------|------------------|--------------------|
| FIX 4.0 | Legacy | Single transport XML | Rare |
| FIX 4.2 | Widely deployed | Single transport XML | ICE, many US venues |
| FIX 4.4 | Common | Single transport XML | Euronext, CME (some) |
| FIXT 1.1 / FIX 5.0 SP2 | Modern split | Transport XML + Application XML | LME, newer venues |

### Single vs Dual Dictionary

**FIX 4.x (single dictionary):** One XML file defines all message types, fields, and components. Session-level and application-level messages are in the same file.

```scala
parser.addFIXDataSource("/path/to/EXCHANGE.FIX42.xml")
```

**FIX 5.0+ (dual dictionary):** The protocol splits into a transport layer (FIXT 1.1 — session messages like Logon, Heartbeat) and an application layer (business messages like NewOrderSingle, ExecutionReport). Two XML files are required.

```scala
parser.addFIXDataSource(
  "/path/to/EXCHANGE.FIXT.1.1.xml",       // transport
  "/path/to/EXCHANGE.FIX50SP2.xml"         // application
)
```

## FIX Data Dictionary XML Structure

A FIX dictionary XML has this structure:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<fix major="4" minor="2" type="FIX" servicepack="0">
  <header>
    <field name="BeginString" required="Y"/>
    <field name="BodyLength" required="Y"/>
    <field name="MsgType" required="Y"/>
    ...
  </header>
  <messages>
    <message name="NewOrderSingle" msgtype="D" msgcat="app">
      <field name="ClOrdID" required="Y"/>
      ...
    </message>
  </messages>
  <components>
    <component name="Instrument">
      <field name="Symbol" required="Y"/>
      ...
    </component>
  </components>
  <fields>
    <field number="1" name="Account" type="STRING"/>
    <field number="11" name="ClOrdID" type="STRING"/>
    ...
  </fields>
</fix>
```

Exchanges often customize the standard dictionary with custom fields (tag numbers > 5000), custom message types, and custom components.

## Dictionary Troubleshooting

### Problem: `ConfigError` on dictionary load

**Cause:** The XML file is malformed, uses wrong encoding, or has structural issues.

**Steps:**
1. Validate XML is well-formed:
```scala
import javax.xml.parsers.SAXParserFactory
val factory = SAXParserFactory.newInstance()
val parser = factory.newSAXParser()
parser.parse("/path/to/dictionary.xml", new org.xml.sax.helpers.DefaultHandler())
// If this throws, the XML itself is broken
```

2. Check encoding — the XML declaration must match actual file encoding:
```scala
import java.io.FileInputStream
val fis = new FileInputStream("/path/to/dictionary.xml")
val header = new Array[Byte](100)
fis.read(header)
println(new String(header, "UTF-8"))
// Look for BOM bytes (EF BB BF) or encoding mismatch
```

3. Verify the root element is `<fix>` with `major` attribute:
```scala
import javax.xml.parsers.DocumentBuilderFactory
val factory = DocumentBuilderFactory.newInstance()
val builder = factory.newDocumentBuilder()
val doc = builder.parse("/path/to/dictionary.xml")
val root = doc.getDocumentElement
println(s"Root: ${root.getNodeName}, major: ${root.getAttribute("major")}")
```

### Problem: `InvalidMessage` during parsing

**Cause:** The raw FIX message doesn't conform to the dictionary.

**Common reasons:**
- Custom tags not defined in the dictionary
- Wrong FIX version (message is 4.4 but dictionary is 4.2)
- SOH delimiter mismatch (some logs use `|` instead of `\x01`)
- Message is truncated or corrupted

**Fix:** Route to quarantine table and inspect the failing messages:
```scala
quarantineDF.select("fixMessage").show(5, truncate = false)
```

### Problem: UDF serialization failure (`NotSerializableException`)

**Cause:** The `DatabricksFIXMessageParser` is not serializable. If you pass it directly into a UDF closure, Spark tries to serialize it to send to executors.

**Fix:** Always use the `@transient lazy val` pattern. See `templates/fix-parse-udf.scala`.

## Common Exchange Specifics

### ICE (Intercontinental Exchange)
- **FIX Version:** 4.2
- **Dict Mode:** Single
- **Source Format:** Text log files, one FIX message per line
- **File Pattern:** `orders_sfi_sfl_futures_ICE_YYYYMMDD.log`
- **Known Issues:** Dictionary XML may have encoding issues (see hex dump debugging in the original notebook)

### LME (London Metal Exchange)
- **FIX Version:** FIXT 1.1 / FIX 5.0 SP2
- **Dict Mode:** Dual (transport + application)
- **Source Format:** CSV, FIX message in column `_c11`
- **File Pattern:** `NN.csv`

### Euronext
- **FIX Version:** 4.4 (Optiq FIX)
- **Dict Mode:** Single
- **Source Format:** Typically text log files
- **Notes:** Euronext uses its "Optiq" trading platform. The FIX dictionary will include Optiq-specific custom fields. Obtain the dictionary from the Euronext Optiq FIX specification.

### CME (Chicago Mercantile Exchange)
- **FIX Version:** 4.2 / 4.4 (iLink)
- **Dict Mode:** Single
- **Source Format:** Varies by connectivity
- **Notes:** CME uses iLink for order entry (binary, not FIX) but FIX for drop copies and market data. Ensure you're working with the FIX gateway logs, not iLink.

## Spark Performance Considerations

### Executor-level parser initialization
The `@transient lazy val` pattern means each executor core creates one parser instance. For a 10-worker cluster with 4 cores each, you'll have ~40 parser instances. Each loads the dictionary XML into memory. For very large dictionaries, ensure adequate executor memory.

### Partition sizing
Aim for partitions of 128MB–256MB for optimal parallelism. If source files are small, consider:
```scala
val coalescedDF = sourceDF.coalesce(numPartitions)
```

### Auto Loader for incremental ingestion
For production pipelines processing new files daily, use Databricks Auto Loader instead of `spark.read`:
```scala
val rawDF = spark.readStream
  .format("cloudFiles")
  .option("cloudFiles.format", "text")
  .option("cloudFiles.schemaLocation", "/path/to/schema")
  .load(sourcePath)
```
This automatically tracks which files have been processed and only ingests new ones.
