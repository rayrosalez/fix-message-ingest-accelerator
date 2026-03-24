%%{init: {"theme": "neutral", "flowchart": {"curve": "basis", "padding": 16}}}%%
flowchart TB
  subgraph TOPROW[" "]
    direction LR
    subgraph L7["LAYER 7 — SKILL / AUTOMATION"]
      direction TB
      SKILL["Exchange Onboarding Skill<br/>────────────────────<br/>Generates pipeline artifacts for new exchanges<br/>from templates (paths, dictionaries, jobs, tables)"]:::skillbox
    end
    subgraph PIPE["FIX Message Ingest Accelerator — Databricks"]
      direction TB

      subgraph L1["LAYER 1 — EXCHANGES"]
        direction LR
        ICE["ICE<br/>FIX 4.2<br/>Deployed"]:::deployed
        LME["LME<br/>FIX 5.0 SP2<br/>Deployed"]:::deployed
        EUR["Euronext<br/>FIX 4.4<br/>To be onboarded"]:::planned
        CME["CME<br/>FIX 4.2 / 4.4<br/>To be onboarded"]:::planned
        OTH["Other exchanges<br/>Future"]:::future
        ICE ~~~ LME ~~~ EUR ~~~ CME ~~~ OTH
      end

      L1 --> L2

      subgraph L2["LAYER 2 — RAW DATA"]
        RAW["Raw FIX log files (.log, .csv)<br/>Land on Unity Catalog Volumes<br/>Path pattern:<br/>/Volumes/{catalog}/{schema}/{volume}/{exchange}/*.log"]
      end

      L2 --> L3

      subgraph L3["LAYER 3 — FIX DATA DICTIONARIES"]
        DICT["Per-exchange FIX Data Dictionary XML on UC Volumes<br/>FIX 4.x: single application dictionary (one XML)<br/>FIX 5.0+: dual dictionary (transport XML + application XML)"]
      end

      L3 --> L4

      subgraph L4["LAYER 4 — PARSING ENGINE (core)"]
        PARSE["spark-fix-encoder-1.0.jar (custom Databricks library)<br/>DatabricksFIXMessageParser — QuickFIX/J under the hood<br/>Serialization-safe Spark UDF (@transient lazy val pattern)<br/>Runs distributed across Spark executors"]
      end

      L4 --> L5

      subgraph L5["LAYER 5 — TRANSFORMATION"]
        XFORM["Raw FIX string → JSON string → Databricks VARIANT<br/>Successful parses → main output stream<br/>Failed parses → quarantine route"]
      end

      L5 --> L6

      subgraph L6["LAYER 6 — OUTPUT (Unity Catalog)"]
        OUT["Delta tables in Unity Catalog<br/>{exchange}_fix_messages — parsed data, VARIANT column<br/>{exchange}_fix_quarantine — unparseable messages"]
      end
    end
  end

  SKILL -.->|"new exchange config"| L1
  SKILL -.->|"dictionary layout"| L3
  SKILL -.->|"pipelines & table DDL"| L6

  classDef deployed fill:#c8e6c9,stroke:#1b5e20,stroke-width:2px,color:#1b3009
  classDef planned fill:#ffe0b2,stroke:#e65100,stroke-width:2px,color:#5d2800
  classDef future fill:#eceff1,stroke:#546e7a,stroke-width:2px,color:#263238
  classDef skillbox fill:#e3f2fd,stroke:#0d47a1,stroke-width:2px,color:#0d1b2a

  style PIPE fill:#ffffff,stroke:#37474f,stroke-width:2px
  style L7 fill:#fffde7,stroke:#f9a825,stroke-width:2px,stroke-dasharray: 6 4
  style TOPROW fill:#ffffff,stroke:#ffffff
