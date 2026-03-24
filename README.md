# FIX Message Ingest Accelerator

A reusable toolkit for parsing **FIX (Financial Information eXchange) protocol** messages from commodity and financial exchanges into structured Delta tables on Databricks. Built for StoneX to rapidly onboard new exchanges into their Unified Data Platform (UDP).

## Architecture

![FIX Message Ingest Accelerator Architecture](docs/architecture.png)

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                      EXCHANGES                                          │
│                                                                                         │
│   ┌───────────┐  ┌───────────┐  ┌──────────────┐  ┌───────────┐  ┌──────────────────┐  │
│   │    ICE     │  │    LME    │  │  Euronext    │  │    CME    │  │ Other Exchanges  │  │
│   │  FIX 4.2  │  │ FIX 5.0SP2│  │   FIX 4.4    │  │ FIX 4.2/  │  │     Future       │  │
│   │ DEPLOYED  │  │ DEPLOYED  │  │  ONBOARDING  │  │    4.4    │  │                  │  │
│   └─────┬─────┘  └─────┬─────┘  └──────┬───────┘  └─────┬─────┘  └────────┬─────────┘  │
│         │               │               │                │                 │             │
└─────────┼───────────────┼───────────────┼────────────────┼─────────────────┼─────────────┘
          │               │               │                │                 │
          ▼               ▼               ▼                ▼                 ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  RAW FIX DATA — Unity Catalog Volumes                                                   │
│  /Volumes/{catalog}/{schema}/{volume}/{exchange}/*.log                                  │
│                                                                                         │
│  • ICE:  text log files (one FIX message per line)                                      │
│  • LME:  CSV files (FIX message in column _c11)                                        │
│  • New exchanges: text or CSV depending on connectivity                                 │
└─────────────────────────────────────────┬───────────────────────────────────────────────┘
                                          │
          ┌───────────────────────────────┤
          ▼                               ▼
┌─────────────────────────┐  ┌───────────────────────────────────────────────────────────┐
│  FIX DATA DICTIONARIES  │  │  PARSING ENGINE                                           │
│  (UC Volumes)           │  │                                                           │
│                         │  │  ┌─────────────────────────────────────┐                  │
│  FIX 4.x (single):     │  │  │  spark-fix-encoder-1.0.jar          │                  │
│   • 1 transport XML     │──▶  │  DatabricksFIXMessageParser          │                  │
│                         │  │  │  (QuickFIX/J under the hood)        │                  │
│  FIX 5.0+ (dual):      │  │  └──────────────┬──────────────────────┘                  │
│   • 1 transport XML     │  │                 │                                          │
│   • 1 application XML   │  │  ┌──────────────▼──────────────────────┐                  │
│                         │  │  │  Spark UDF                           │                  │
│  Per-exchange custom    │  │  │  @transient lazy val per executor    │                  │
│  dictionaries           │  │  │  Distributed across cluster          │                  │
└─────────────────────────┘  │  └──────────────┬──────────────────────┘                  │
                             └─────────────────┼─────────────────────────────────────────┘
                                               │
                                               ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  TRANSFORMATION                                                                         │
│                                                                                         │
│  Raw FIX string ──▶ JSON string ──▶ Databricks VARIANT                                 │
│                                         │                                               │
│                              ┌──────────┴──────────┐                                    │
│                              ▼                     ▼                                    │
│                      Parse succeeded         Parse failed                               │
└──────────────────────────────┬─────────────────────┬────────────────────────────────────┘
                               │                     │
                               ▼                     ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│  OUTPUT — Delta Tables in Unity Catalog                                                 │
│                                                                                         │
│  ┌──────────────────────────────────┐  ┌──────────────────────────────────┐             │
│  │  {exchange}_fix_messages         │  │  {exchange}_fix_quarantine       │             │
│  │                                  │  │                                  │             │
│  │  • fixMessage (raw string)       │  │  • fixMessage (raw string)       │             │
│  │  • jsonData (parsed JSON)        │  │  • fileName                      │             │
│  │  • fixVariant (VARIANT type)     │  │  • rowNumber                     │             │
│  │  • fileName, rowNumber           │  │  • exchange                      │             │
│  │  • exchange, parsedAt            │  │  • quarantinedAt                 │             │
│  └──────────────────────────────────┘  └──────────────────────────────────┘             │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

## Current State

| Exchange | FIX Version | Dictionary Mode | Status |
|----------|-------------|-----------------|--------|
| **ICE** | 4.2 | Single | Deployed (prototype) |
| **LME** | 5.0 SP2 / FIXT 1.1 | Dual | Deployed (prototype) |
| **Euronext** | 4.4 (Optiq) | Single | To be onboarded |
| **CME** | 4.2 / 4.4 | Single | To be onboarded |

## Repository Structure

```
fix-message-ingest-accelerator/
│
├── README.md                              ← You are here
├── spark-fix-encoder-1.0.jar              ← Core parsing library (install on cluster)
├── anant FIX Parser V2.ipynb              ← Original prototype notebook (ICE + LME)
│
├── docs/
│   ├── architecture.png                   ← Architecture diagram
│   └── architecture.mmd                   ← Mermaid source (editable)
│
└── skills-based-approach/                 ← Exchange onboarding accelerator
    ├── SKILL.md                           ← Cursor Agent Skill for onboarding
    ├── exchange-config-template.json      ← Parameterized exchange config
    ├── reference.md                       ← FIX protocol & troubleshooting guide
    ├── examples.md                        ← Worked examples (ICE, LME, Euronext, CME)
    └── templates/
        ├── exchange-pipeline.scala        ← Production notebook template
        └── fix-parse-udf.scala            ← Reusable UDF patterns
```

## Quick Start: Onboard a New Exchange

### Prerequisites

1. **Databricks cluster** with `spark-fix-encoder-1.0.jar` installed as a cluster library
2. The exchange's **FIX data dictionary XML** uploaded to a Unity Catalog Volume
3. **Raw FIX log files** accessible on a Unity Catalog Volume
4. A **target catalog and schema** in Unity Catalog

### Steps

**1. Obtain the FIX data dictionary**

Every exchange publishes a FIX specification. You need the XML data dictionary file(s):

| FIX Version | Files Needed |
|---|---|
| FIX 4.0–4.4 | 1 transport dictionary XML |
| FIX 5.0+ / FIXT 1.1 | 1 transport XML + 1 application XML |

Upload the XML file(s) to a Unity Catalog Volume (e.g. `/Volumes/users/{you}/FIX/`).

**2. Create the exchange config**

Copy `skills-based-approach/exchange-config-template.json` and fill in the exchange-specific values:

```json
{
  "exchange": { "name": "EURONEXT" },
  "fix_protocol": {
    "version": "4.4",
    "dictionary_mode": "single",
    "transport_dictionary_path": "/Volumes/users/josh_seidel/FIX/EURONEXT_OPTIQ.FIX44.xml"
  },
  "source": {
    "format": "text",
    "path": "/Volumes/data/euronext/orders_optiq_*.log"
  },
  "target": {
    "catalog": "prod_trading",
    "schema": "fix_parsed",
    "table": "euronext_fix_messages",
    "quarantine_table": "euronext_fix_quarantine"
  }
}
```

**3. Generate the pipeline notebook**

Use the template at `skills-based-approach/templates/exchange-pipeline.scala`. Replace all `{{placeholder}}` values with your config. The template includes:

- Widget-driven configuration (no hardcoded paths)
- Dictionary validation (fail-fast on bad XML)
- Serialization-safe Spark UDF
- FIX → JSON → VARIANT transformation
- Delta table writes with quarantine routing
- Post-write validation

**4. Validate the dictionary**

Run the dictionary validation cell first. If it fails, consult `skills-based-approach/reference.md#dictionary-troubleshooting`.

**5. Test with a small dataset**

Run the pipeline against a single file or small subset before processing the full history.

**6. Review quarantine**

Check the quarantine table for unparseable messages. Common causes:
- Custom tags not in the dictionary
- FIX version mismatch
- Delimiter issues (`|` vs `\x01`)

**7. Schedule for production**

Once validated, schedule the notebook as a Databricks Job with the appropriate cron expression.

## How the Parser Works

The pipeline follows the same pattern for every exchange:

```
1. INGEST     spark.read.text() or spark.read.csv()
                 ↓
2. CONFIGURE  DatabricksFIXMessageParser + FIX Dictionary XML(s)
                 ↓
3. PARSE      Spark UDF calls parseFIXMessageToJSONString()
              distributed across executors via @transient lazy val
                 ↓
4. TRANSFORM  JSON string → CAST(jsonData AS VARIANT)
                 ↓
5. ROUTE      Success → main table | Failure → quarantine table
                 ↓
6. WRITE      Delta table in Unity Catalog
```

### The UDF Serialization Pattern

The `DatabricksFIXMessageParser` is **not serializable**. It cannot be passed directly into a Spark UDF closure. The solution is `@transient lazy val` — each executor lazily creates its own parser instance using the serializable dictionary path string:

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

This is the single most important implementation detail. The original prototype had a version without this pattern that would fail at scale.

## Key Dependencies

| Component | Purpose |
|---|---|
| `spark-fix-encoder-1.0.jar` | Custom Databricks library wrapping the FIX parser |
| **QuickFIX/J** | Industry-standard open-source FIX engine (bundled in JAR) |
| **Databricks Runtime 14.x+** | Required for `VARIANT` type support |
| **Unity Catalog** | Storage for Volumes (source data, dictionaries) and Delta tables |

## Troubleshooting

| Problem | Likely Cause | Solution |
|---|---|---|
| `ConfigError` on dictionary load | Malformed XML or encoding issue | See `reference.md` — validate XML, check for BOM bytes |
| `InvalidMessage` during parsing | Message doesn't match dictionary | Check FIX version, custom tags, delimiters |
| `NotSerializableException` | Parser passed directly to UDF | Use `@transient lazy val` pattern (see templates) |
| Low success rate | Wrong dictionary for the data | Verify the dictionary version matches the source messages |
| Out of memory on executors | Large dictionary + many cores | Increase executor memory — each core loads one dictionary instance |
