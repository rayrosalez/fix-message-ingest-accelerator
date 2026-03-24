# Deployment Guide — FIX Exchange Parser for Databricks

How to deploy the universal FIX parser into your Databricks workspace so the engineering team can onboard new exchanges self-service.

## What You're Deploying

| Artifact | What It Does |
|----------|--------------|
| `FIX_Exchange_Parser.scala` | A single parameterized Databricks notebook that parses FIX messages from **any** exchange. No code changes needed per exchange — just fill in the widgets. |
| `assistant-instructions.md` | Custom instructions for the Databricks AI Assistant so it can guide engineers through onboarding. |
| `spark-fix-encoder-1.0.jar` | The custom library (already exists). Must be on every cluster that runs the notebook. |

## Step 1: Upload the Notebook

Import `FIX_Exchange_Parser.scala` into your Databricks workspace:

1. Open your Databricks workspace
2. Navigate to your target folder (e.g. `/Repos/trading-team/fix-pipelines/` or `/Workspace/Shared/FIX/`)
3. Click **Import** → select `FIX_Exchange_Parser.scala`
4. The notebook will appear with all widget parameters ready to configure

**Alternative — Repos:** If this repo is connected via Databricks Repos, the notebook is already synced.

## Step 2: Install the JAR on Your Cluster

The `spark-fix-encoder-1.0.jar` must be installed as a cluster library:

1. Go to **Compute** → select your cluster
2. Click **Libraries** → **Install New**
3. Choose **Upload** → select `spark-fix-encoder-1.0.jar`
4. Restart the cluster

For production, upload the JAR to a Unity Catalog Volume and reference it from there so all clusters can use it:
```
/Volumes/{catalog}/{schema}/jars/spark-fix-encoder-1.0.jar
```

## Step 3: Configure the Databricks Assistant (Optional but Recommended)

This gives the AI Assistant context about the FIX pipeline so it can help engineers interactively.

1. Go to **Workspace Settings** → **AI/BI Assistant** → **Custom Instructions**
2. Copy the contents of the code block inside `assistant-instructions.md`
3. Paste it into the custom instructions field
4. Save

Now when an engineer opens the FIX parser notebook and asks the assistant "How do I onboard Euronext?", it will walk them through the widget configuration.

## Step 4: Onboard an Exchange

### For the engineer onboarding a new exchange:

1. **Get the FIX dictionary XML** from the exchange's FIX specification
   - FIX 4.x: You need 1 XML file (transport dictionary)
   - FIX 5.0+: You need 2 XML files (transport + application dictionary)

2. **Upload the dictionary** to a Unity Catalog Volume:
   ```
   /Volumes/users/{your_name}/FIX/{EXCHANGE}.{FIX_VERSION}.xml
   ```

3. **Upload or verify raw data files** are on a Volume:
   ```
   /Volumes/{catalog}/{schema}/{volume}/{exchange}/*.log
   ```

4. **Create the target tables' schema** if it doesn't exist:
   ```sql
   CREATE SCHEMA IF NOT EXISTS {catalog}.{schema};
   ```

5. **Open `FIX_Exchange_Parser`** notebook and fill in the widgets:

   | Widget | Euronext Example |
   |--------|------------------|
   | exchange_name | `EURONEXT` |
   | fix_version | `4.4` |
   | dictionary_mode | `single` |
   | transport_dict_path | `/Volumes/users/josh_seidel/FIX/EURONEXT_OPTIQ.FIX44.xml` |
   | application_dict_path | *(leave empty)* |
   | source_format | `text` |
   | source_path | `/Volumes/data/euronext/orders_optiq_*.log` |
   | csv_fix_column | *(leave default)* |
   | target_table | `prod_trading.fix_parsed.euronext_fix_messages` |
   | quarantine_table | `prod_trading.fix_parsed.euronext_fix_quarantine` |

6. **Run All** — the notebook will:
   - Validate the dictionary (fails fast if XML is bad)
   - Ingest the raw files with metadata (filename, row number)
   - Parse each FIX message to JSON via the distributed UDF
   - Cast to VARIANT for semi-structured querying
   - Write successes to the target Delta table
   - Route failures to the quarantine table
   - Print a summary with counts and sample data

7. **Review the quarantine table** — if messages failed to parse, inspect them:
   ```sql
   SELECT * FROM prod_trading.fix_parsed.euronext_fix_quarantine LIMIT 20;
   ```
   Common causes: custom tags not in dictionary, FIX version mismatch, delimiter issues.

## Step 5: Schedule for Production

Once validated, schedule the notebook as a Databricks Job:

1. Click **Schedule** in the notebook toolbar
2. Set the cron expression (e.g. daily at 6 AM: `0 0 6 * * ?`)
3. Select the cluster with `spark-fix-encoder-1.0.jar` installed
4. Set widget parameter values in the Job configuration
5. Add alerting for failures

## Reference: Currently Deployed Exchanges

| Exchange | FIX Version | Dict Mode | Source Format | Source Column | Status |
|----------|-------------|-----------|---------------|---------------|--------|
| ICE | 4.2 | single | text | *(N/A — full line)* | Deployed |
| LME | 5.0 SP2 | dual | csv | `_c11` | Deployed |
| Euronext | 4.4 | single | text | *(N/A)* | To be onboarded |
| CME | 4.2 / 4.4 | single | varies | varies | To be onboarded |

## Troubleshooting Quick Reference

| Symptom | Cause | Fix |
|---------|-------|-----|
| `ConfigError` on cell 4 | Bad dictionary XML | Check encoding (must be UTF-8), validate XML is well-formed, verify root `<fix>` element has `major` attribute |
| `NotSerializableException` | *(Shouldn't happen with this notebook)* | The UDF already uses `@transient lazy val`. If you see this, check that you haven't modified the UDF cell. |
| All messages go to quarantine | Wrong dictionary for the data | Verify FIX version of dictionary matches source messages. Try a different dictionary version. |
| `java.io.FileNotFoundException` | Wrong Volume path | Verify the path exists: `%fs ls /Volumes/...` |
| Cluster can't find `DatabricksFIXMessageParser` | JAR not installed | Install `spark-fix-encoder-1.0.jar` as a cluster library and restart |
