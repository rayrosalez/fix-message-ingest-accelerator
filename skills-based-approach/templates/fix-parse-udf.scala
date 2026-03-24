// =============================================================================
// Reusable FIX Parse UDF — Serialization-Safe Pattern
// =============================================================================
// This UDF is the core building block for all exchange pipelines.
// It uses @transient lazy val so each Spark executor initializes its own
// DatabricksFIXMessageParser instance, avoiding serialization of the
// non-serializable parser object across the cluster.
// =============================================================================

import com.databricks.sparkfixencoder.provider.DatabricksFIXMessageParser
import quickfix.{InvalidMessage, FieldNotFound}
import org.apache.spark.sql.api.java.UDF1
import org.apache.spark.sql.types.DataTypes
import com.fasterxml.jackson.core.JsonProcessingException

// -- Single Dictionary Mode (FIX 4.0–4.4) --

class SingleDictParseUDF(transportDictPath: String)
    extends UDF1[String, String] with Serializable {

  @transient private lazy val fixParser: DatabricksFIXMessageParser = {
    val p = new DatabricksFIXMessageParser()
    p.addFIXDataSource(transportDictPath)
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

// -- Dual Dictionary Mode (FIX 5.0+ / FIXT 1.1) --

class DualDictParseUDF(transportDictPath: String, applicationDictPath: String)
    extends UDF1[String, String] with Serializable {

  @transient private lazy val fixParser: DatabricksFIXMessageParser = {
    val p = new DatabricksFIXMessageParser()
    p.addFIXDataSource(transportDictPath, applicationDictPath)
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

// =============================================================================
// Usage:
//
//   // Single dict (ICE FIX 4.2, Euronext FIX 4.4, etc.)
//   val udf = new SingleDictParseUDF("/Volumes/.../EXCHANGE.FIX42.xml")
//   spark.udf.register("parseUDF_EXCHANGE", udf, DataTypes.StringType)
//
//   // Dual dict (LME FIX 5.0 SP2, etc.)
//   val udf = new DualDictParseUDF(
//     "/Volumes/.../EXCHANGE.FIXT.1.1.xml",
//     "/Volumes/.../EXCHANGE.FIX50SP2.xml"
//   )
//   spark.udf.register("parseUDF_EXCHANGE", udf, DataTypes.StringType)
//
//   // Apply to DataFrame
//   val result = df.withColumn("jsonData", call_udf("parseUDF_EXCHANGE", col("fixMessage")))
// =============================================================================
