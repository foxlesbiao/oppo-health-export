#!/usr/bin/env python3
"""
oppo-health MCP Server — OPPO 健康数据接收/查询的标准 MCP 规范接口
================================================================
独立于 Flask 接收端(纯 mcp + sqlite3, 无 flask 依赖), 与手机 APK
通道共用同一 sink 库 (~/oppo-health/sink/health.db), 数据天然一致。

安全设计:
  - 所有标识符(表名/列名)白名单校验 ^[A-Za-z_][A-Za-z0-9_]*$, 杜绝 SQL 注入
  - health_query 的 columns 逐列校验必须是该表真实存在的列, 禁止任意 SQL 片段
  - health_ingest 的表/列来自外部文本, 同样校验, 非法即拒绝
  - CSV 用 csv 模块解析(支持带引号字段), 不用脆弱 split(',')
  - 数据写入全参数化(INSERT VALUES 绑参)

注意: 本 server 是本地可信环境(stdio, 供 agent/hook 用)。若暴露到公网,
需在接入层加鉴权(Token/网络隔离), 健康数据属敏感个人信息。
"""
import csv
import hashlib
import io
import os
import re
import sqlite3

from mcp.server.fastmcp import FastMCP

HERE = os.path.dirname(os.path.abspath(__file__))
SINK_DB = os.path.normpath(os.path.join(HERE, '..', 'sink', 'health.db'))

mcp = FastMCP("oppo-health")

# ---------- 安全校验 ----------
_IDENT = re.compile(r'^[A-Za-z_][A-Za-z0-9_]*$')
# 允许查询时附加的元数据列(sink 库自动生成, 不在手机 schema 里)
_META_COLS = {"__row_hash", "__t"}


def _check_ident(name: str, kind: str = "标识符") -> str:
    """校验并原样返回合法标识符; 非法抛 ValueError(禁止 SQL 注入/Traversal)。"""
    if not isinstance(name, str) or not _IDENT.match(name):
        raise ValueError(f"非法{kind}: {name!r}(仅允许字母/数字/下划线)")
    return name


def _existing_tables(c) -> set:
    return {r[0] for r in c.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name!='sink_cursor'")}


def _table_cols(c, table: str) -> list:
    return [r[1] for r in c.execute(f'PRAGMA table_info("{table}")')]


def _conn():
    c = sqlite3.connect(SINK_DB, timeout=15)
    c.execute("PRAGMA journal_mode=WAL")
    c.execute("PRAGMA busy_timeout=15000")
    c.execute("CREATE TABLE IF NOT EXISTS sink_cursor(table_name TEXT PRIMARY KEY, last_ts INTEGER)")
    c.commit()
    return c


def _cursor_map():
    c = _conn()
    try:
        return {t: v for t, v in c.execute("SELECT table_name, last_ts FROM sink_cursor").fetchall()}
    finally:
        c.close()


def _parse_section_rows(text):
    """把分节文本转成 [(表, 时间列, [列头], [行])]。行用 csv 解析(支持引号字段)。"""
    sections, cur = [], None
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith('===TABLE:'):
            if cur is not None and cur["header"] is not None:
                sections.append((cur["table"], cur["tc"], cur["header"], cur["rows"]))
            m = line[len('===TABLE:'):]
            if m.endswith('==='):
                m = m[:-3]
            parts = [p.strip() for p in m.split('|')]
            if not parts or not parts[0]:
                continue
            table = _check_ident(parts[0], "表名")
            tc = ""
            for p in parts[1:]:
                if p.startswith('TIMECOL:'):
                    tc = p[len('TIMECOL:'):].strip()
            if tc:
                tc = _check_ident(tc, "时间列")
            cur = {"table": table, "tc": tc, "header": None, "rows": []}
        elif cur is not None:
            cells = next(csv.reader(io.StringIO(line))) if line else []
            cells = [x.strip() for x in cells]
            if cur["header"] is None:
                cur["header"] = cells
            elif cells and not (len(cells) == 1 and cells[0] == ''):
                cur["rows"].append(cells)
    if cur is not None and cur["header"] is not None:
        sections.append((cur["table"], cur["tc"], cur["header"], cur["rows"]))
    return sections


def ingest_sections(sections):
    """分节数据校验 + 幂等入库(行哈希 UNIQUE) + 更新水位 -> (表数, 新行, 重复跳过)。"""
    c = _conn()
    total_tables = total_rows = skipped = 0
    try:
        tables = _existing_tables(c)
        for table, timecol, header, rows in sections:
            if not table or not header or not rows:
                continue
            # 列头校验(合法标识符 + 去重)
            seen, clean_head = set(), []
            for h in header:
                h = _check_ident(h, "列名")
                if h in seen:
                    continue
                seen.add(h)
                clean_head.append(h)
            if not clean_head:
                continue
            col_sql = ", ".join(f'"{x}"' for x in clean_head)
            # 建表(如已存在则无关), 并补齐缺失列(增量导入列集合可能新增)
            if table not in tables:
                c.execute(f'CREATE TABLE IF NOT EXISTS "{table}" ('
                          + col_sql
                          + ', "__row_hash" TEXT UNIQUE, "__t" INTEGER)')
                tables.add(table)
            else:
                have = set(_table_cols(c, table))
                for h in clean_head:
                    if h not in have and h not in _META_COLS:
                        c.execute(f'ALTER TABLE "{table}" ADD COLUMN "{h}" TEXT')
                        have.add(h)
            ti = clean_head.index(timecol) if timecol in clean_head else -1
            placeholders = ",".join(["?"] * (len(clean_head) + 2))
            col_names = ", ".join(f'"{x}"' for x in clean_head) + ', "__row_hash", "__t"'
            insert_sql = f'INSERT OR IGNORE INTO "{table}" ({col_names}) VALUES ({placeholders})'
            n, last_ts = 0, None
            for row in rows:
                # 按 header 长度归一(缺位补空, 超位截断)
                row = (row + [''] * len(clean_head))[:len(clean_head)]
                h = hashlib.sha256("\x1f".join(row).encode('utf-8')).hexdigest()
                ts = None
                if ti >= 0:
                    try:
                        ts = int(float(row[ti]))
                    except (ValueError, TypeError):
                        ts = None
                if ts is not None:
                    last_ts = ts if last_ts is None else max(last_ts, ts)
                cur = c.execute(insert_sql, list(row) + [h, ts])
                if cur.rowcount > 0:
                    n += 1
                else:
                    skipped += 1
            if n:
                total_tables += 1
                total_rows += n
                if last_ts is not None:
                    c.execute("INSERT INTO sink_cursor(table_name,last_ts) VALUES(?,?) "
                              "ON CONFLICT(table_name) DO UPDATE SET last_ts=max(sink_cursor.last_ts, excluded.last_ts)",
                              (table, last_ts))
                c.commit()
    finally:
        c.close()
    return total_tables, total_rows, skipped


# ---------- MCP 工具 ----------
@mcp.tool()
def health_ingest(sections_text: str) -> dict:
    """接收健康数据增量分节文本(格式: ===TABLE:<表>|TIMECOL:<列>=== + 列头行 + 数据行,
    可多表分节)。幂等入库(行哈希 UNIQUE)并更新水位。
    返回 {tables, new_rows, dup_skipped, watermark}。"""
    try:
        secs = _parse_section_rows(sections_text)
        t, r, s = ingest_sections(secs)
    except ValueError as e:
        return {"error": f"非法标识符: {e}"}
    return {"tables": t, "new_rows": r, "dup_skipped": s, "watermark": _cursor_map()}


@mcp.tool()
def health_watermark() -> dict:
    """查询各表水位(已同步到的最大时间戳, 毫秒)。"""
    return _cursor_map()


@mcp.tool()
def health_list_tables() -> list:
    """列出 sink 库所有已入库表 + 各自行数。"""
    c = _conn()
    try:
        tabs = sorted(_existing_tables(c))
        return [{"table": t, "rows": c.execute(f'SELECT count(*) FROM "{t}"').fetchone()[0]}
                for t in tabs]
    finally:
        c.close()


@mcp.tool()
def health_stats() -> dict:
    """各表统计概览 {表: {rows, min_t, max_t}}。"""
    c = _conn()
    try:
        res = {}
        for t in sorted(_existing_tables(c)):
            n = c.execute(f'SELECT count(*) FROM "{t}"').fetchone()[0]
            try:
                tmin, tmax = c.execute(f'SELECT min(__t), max(__t) FROM "{t}"').fetchone()
            except Exception:
                tmin = tmax = None
            res[t] = {"rows": n, "min_t": tmin, "max_t": tmax}
        return res
    finally:
        c.close()


@mcp.tool()
def health_query(table: str, columns: str = "*", since: int = None, until: int = None,
                 limit: int = 500) -> dict:
    """查询某表已入库数据。table=表名(须已存在); columns=逗号分隔的合法列名(默认* 全部,
    可含 __t/__row_hash); since/until=毫秒时间戳过滤 __t(增量同步用);
    limit=最多行(默认500, 最大5000)。返回 {columns, rows}。"""
    limit = min(int(limit), 5000) if limit else 500
    c = _conn()
    try:
        tables = _existing_tables(c)
        if table not in tables:
            return {"error": f"表不存在: {table!r}，可用表见 health_list_tables"}
        valid = set(_table_cols(c, table)) | _META_COLS
        if not columns or columns.strip() == '*' or columns.strip().lower() == 'all':
            sel = [f'"{x}"' for x in sorted(valid)]  # all: 列名全部引号包裹, 无注入面
        else:
            sel = []
            for col in columns.split(','):
                col = col.strip()
                if not col:
                    continue
                if col not in valid:
                    raise ValueError(f"非法列: {col!r}(该表无此列, 可用列: {sorted(valid)})")
                sel.append(f'"{col}"')
            if not sel:
                raise ValueError("未指定有效列")
        where, params = [], []
        if since is not None:
            where.append("__t >= ?"); params.append(int(since))
        if until is not None:
            where.append("__t <= ?"); params.append(int(until))
        sql = f'SELECT {", ".join(sel)} FROM "{table}"'
        if where:
            sql += " WHERE " + " AND ".join(where)
        sql += " ORDER BY __t LIMIT ?"
        params.append(limit)
        cur = c.execute(sql, params)
        colnames = [d[0] for d in cur.description]
        rows = [list(r) for r in cur.fetchall()]
        return {"columns": colnames, "rows": rows}
    except ValueError as e:
        return {"error": str(e)}
    finally:
        c.close()


@mcp.tool()
def health_schema(table: str) -> dict:
    """查看某表结构(列名)。table 须已存在。"""
    c = _conn()
    try:
        tables = _existing_tables(c)
        if table not in tables:
            return {"error": f"表不存在: {table!r}"}
        return {"table": table, "columns": _table_cols(c, table)}
    finally:
        c.close()


if __name__ == "__main__":
    mcp.run()
