# AGENT_GUIDE — Developer / Agent Guide

This file is written for AI coding agents and developers working in this repository.
It explains the repo, how to build and run it, and the exact contracts (MCP tools, data format, incremental-sync protocol) you must honor.

> If your tool auto-discovers `AGENTS.md`, copy this content to `AGENTS.md` on your own; this filename is protected from self-modification in some environments (e.g. Hermes).

## 1. What this project is

A self-hosted pipeline that exports encrypted health data from the **OPPO / HeyTap Health** app on a rooted Android phone (via an **LSPosed/libxposed** hook) and makes it available to AI agents over a **standard MCP server**.

Two independent channels share one SQLite sink DB (`sink/health.db`):
- Phone APK → Flask REST (`/api/cursor`, `/api/upload`) — the phone can't speak MCP; this is its internal channel.
- Any AI agent → MCP server (`server/mcp_server.py`) — the public, standardized interface.

Both write the same sink DB, so data is consistent.

> ⚠️ Study/private use only. It manipulates the user's own health data. Never use for others' data.

## 2. Repo layout

```
android/                          # LSPosed hook module (Kotlin/Compose, libxposed)
  app/build.gradle.kts            # versionCode 500 / versionName 5.0.0
  app/libs/                       # put libxposed-api.jar here (NOT committed, LGPL)
  app/src/main/
    java/com/hermes/dbkeyhook/
      Main.kt                     # hooks AesGcmAndroidKeyStore.b / bj4.a / Activity.onCreate
      MainActivity.kt             # Compose UI (upload URL, external URL, token)
      ExportWorker.kt             # stream-export + chunked upload + watermark logic
    resources/META-INF/xposed/    # java_init.list / scope.list / module.prop
server/
  mcp_server.py                   # standalone MCP server (mcp + sqlite3 ONLY, no Flask)
  mcp-tools.json                  # machine-readable MCP tool schemas
README.md / README_EN.md
```

## 3. Build the Android module

```bash
cd android
# 1. place libxposed-api.jar in app/libs/ (from https://github.com/libxposed/api releases)
# 2. write android/local.properties:  sdk.dir=<sdk path>  (compileSdk=34)
JAVA_HOME=<JDK21> gradle :app:assembleDebug --no-daemon
# output: app/build/outputs/apk/debug/app-debug.apk
```

- `libxposed` API is `compileOnly` — it MUST NOT be packaged into the APK.
- Keep `module.prop` fields (`id/name/version/version_name/author/description`) in sync with `build.gradle.kts` versions.

## 4. MCP server

Run with any Python that has `mcp` + stdlib `sqlite3` (no Flask needed):

```bash
pip install mcp
python server/mcp_server.py        # stdio transport
```

`SINK_DB` is resolved relative to the script: `<repo>/sink/health.db`.

Register in a client's `mcp_servers` (e.g. Hermes config.yaml):

```yaml
mcp_servers:
  oppo_health:
    command: <python with mcp>
    args: ["/abs/path/server/mcp_server.py"]
```

### Tools (exact contract — see also server/mcp-tools.json)

| Tool | Params | Returns |
|---|---|---|
| `health_ingest` | `sections_text: str` (sectioned CSV, see §5) | `{tables, new_rows, dup_skipped, watermark}` |
| `health_watermark` | — | `{<table>: last_ts_ms}` |
| `health_list_tables` | — | `[{table, rows}]` |
| `health_stats` | — | `{<table>: {rows, min_t, max_t}}` |
| `health_query` | `table`, `columns="*"`, `since`, `until`, `limit=500 (max 5000)` | `{columns, rows}` |
| `health_schema` | `table` | `{table, columns}` |

Safety contract (must not be broken):
- Table & column names are validated against `^[A-Za-z_][A-Za-z0-9_]*$` and, for queries, must be real columns of an existing table. This is the only defense against SQL injection — never bypass it, never interpolate identifiers without this check.
- All values are bound parameters.

## 5. Data format (sectioned incremental CSV)

Each upload/ingest body is one or more sections:

```
===TABLE:<table>|TIMECOL:<time_column>===
<header col1>,<col2>,...
<val1>,<val2>,...
...rows...
```

Rules:
- The header line immediately follows the `===TABLE:` line.
- Rows are comma-separated; `|TIMECOL:` names the millisecond-epoch timestamp column used for the watermark (may be empty for small tables).
- Server stores every column as TEXT, plus `__row_hash` (SHA-256 of the row) as UNIQUE and `__t` (int ms timestamp). `INSERT OR IGNORE` makes retries idempotent.

Parser pitfall: strip the trailing `===` from the `===TABLE:` line before parsing, or the `TIMECOL` value picks up `===`.

## 6. Incremental sync design (understand before editing)

- The phone reads `GET /api/cursor` → returns `{table: last_ts}`.
- It streams each table with `WHERE time_col > watermark ORDER BY time_col LIMIT 2000` (never `SELECT *` whole table → OOM on phone).
- The first run uses an **initial watermark of now-90 days** (not 0) to build a baseline.
- Every **20,000 rows** it flushes one chunk and POSTs it — keeps memory constant and per-request small.
- A single in-process `AtomicBoolean` guards against duplicate concurrent exports (multi-entry hooks fire in different threads).
- Server updates `sink_cursor.last_ts` with `max(existing, new)` inside the SAME transaction that inserts rows.

## 7. Conventions & safety

- Never fetch automatically in read-only contexts; don't `--force` pushes.
- SQLite connections: use `PRAGMA journal_mode=WAL` + `busy_timeout` (multi-connection writes).
- On the phone, prefer full `adb reboot` over `su -c "stop;start"` (the latter can hang system_server).
- Health data is sensitive: redact values in any report/log; don't log raw credentials.

## 8. Pitfalls already learned

1. **OOM**: exporting a whole table into memory crashes the app — always stream/batch (LIMIT 2000).
2. **Runaway CPU**: multiple hook entry points spawn parallel exports → guard with an `AtomicBoolean` single-instance.
3. **First full-history too slow**: use an initial watermark (90 days), not 0.
4. **SQL injection**: identifiers are whitelisted; values parameterized. Don't "simplify" this.
5. **DB locked**: without WAL + busy_timeout, concurrent connections deadlock.
6. **`module.prop` missing fields**: affects LSPosed manager display — keep id/name/version/author/description present.
