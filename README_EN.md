# OPPO Health Export

Export encrypted health data from the OPPO/HeyTap Health app via an LSPosed hook, then serve it to any AI agent over a standard **MCP (Model Context Protocol)** interface.

> ⚠️ **For personal study only.** Don't use it to invade others' privacy or commercially. It exports **your own** health data.

## Architecture

```
┌─ Phone (LSPosed module) ──────────────┐      ┌─ Server (Hermes / any host) ─────┐
│ OPPO Health (com.heytap.health)       │      │                                  │
│   ↓ hook grab SQLCipher key          │ HTTP │  Flask REST (internal, phone)    │
│   ↓ stream & chunked upload           │────▶ │   /api/cursor + /api/upload     │
│   (incremental: server watermark +    │      │        │                         │
│    row-hash idempotency)              │      │        ▼                         │
│                                       │      │  sink/health.db ──● MCP server   │
└───────────────────────────────────────┘      └─────────────────│───────────────┘
                                                                 ▼
                                                    any AI agent (mcp__oppo_health__*)
```

- **Phone side**: an LSPosed/libxposed module hooks OPPO Health's SQLCipher methods to grab the decryption key, then streams **incremental** exports (server watermark via `/api/cursor`) and uploads in chunks (~20k rows each).
- **Server side**: `server/mcp_server.py` is a **standard MCP server** (stdio) exposing `health_*` tools. Any MCP client can connect; rows are deduped idempotently via a `__row_hash` UNIQUE column.

## Layout

```
android/   # LSPosed module (phone: hook + export + upload)
server/    # MCP server (standard data ingest/query interface)
```

## Build the Android module

1. Download the newest [`libxposed/api`](https://github.com/libxposed/api/releases) `.aar`, extract `classes.jar`, rename it to `libxposed-api.jar`, and put it in `android/app/libs/`.
2. Set the SDK in `android/local.properties` (`sdk.dir=<your sdk path>`), `compileSdk=34`.
3. Build:

   ```bash
   cd android && JAVA_HOME=<JDK21> gradle :app:assembleDebug --no-daemon
   ```

4. Install `app/build/outputs/apk/debug/app-debug.apk`, enable it in LSPosed and check scope **`com.heytap.health`**.

> The module depends on `libxposed` and **must not** bundle the API (it's `compileOnly`).

## Use the MCP server

```bash
# requires the mcp python package: pip install mcp
python server/mcp_server.py   # stdio transport
```

Register in any MCP client (e.g. Hermes `~/.hermes/config.yaml`):

```yaml
mcp_servers:
  oppo_health:
    command: <python interpreter with mcp installed>
    args: ["<absolute path>/server/mcp_server.py"]
```

Tools exposed:

| Tool | Purpose |
|---|---|
| `health_ingest` | Ingest incremental chunked data (idempotent + update watermark) |
| `health_watermark` | Read per-table watermark (incremental cursor) |
| `health_list_tables` / `health_stats` | List tables / overview stats |
| `health_query` | Query ingested data by time / columns |
| `health_schema` | Show table structure |

**Security**: all identifiers go through whitelist validation (no SQL injection); all writes are parameterized. See `server/mcp-tools.json` for the machine-readable tool schema.

## Data format (sectioned incremental CSV)

```
===TABLE:DBHeartRate|TIMECOL:data_created_timestamp===
ssoid,device_type,data_created_timestamp,value,...
1249250120,,1779393420000,85,...
```

## Version

- 5.1.0: chunked-batch dedup — upload meta carries batch_id/final/total_chunks; server triggers analysis only on the final chunk, eliminating repeated analyses from a single export.
- 5.0.0: MCP-standardized server + version-aligned module; incremental watermark / stream batching / chunked upload / idempotent dedup.

## License

[MIT](LICENSE) — source; `libxposed-api.jar` belongs to its author, distributed under LGPL-3.0.
