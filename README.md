# OPPO Health Export

通过 LSPosed hook 从 OPPO/欢太健康 App 导出加密健康数据，经标准 **MCP (Model Context Protocol)** 接口接入任意 AI agent 分析。

> ⚠️ **仅供本人学习研究**。请勿用于侵犯他人隐私或商用。导出的是**你自己的**健康数据。

## 架构

```
┌─ 手机(LSPosed 模块) ────────────────┐        ┌─ 服务端(Hermes/任意主机) ──────┐
│ OPPO 健康 App (com.heytap.health)   │        │                                │
│   ↓ hook 提取 SQLCipher 密钥        │  HTTP  │  Flask REST (内部,手机专通道)   │
│   ↓ 流式分片导出 + 分块上传          │──────▶ │   /api/cursor + /api/upload    │
│   (增量: 服务端水位 + 行哈希幂等)    │        │         │                      │
│                                     │        │         ▼                      │
│                                     │        │  sink/health.db  ──●  MCP server│
└─────────────────────────────────────┘        └────────────────────│──────────┘
                                                                    ▼
                                                      任意 AI agent(mcp__oppo_health__*)
```

- **手机端**：LSPosed/libxposed 模块 hook OPPO 健康的 SQLCipher 加解密方法拿到密钥，逐表**流式分批**导出，按 `===TABLE:<表>|TIMECOL:<列>===` 分节 + **增量水位**(`/api/cursor`)只导新增，每满 2 万行一个分块上传。
- **服务端**：`server/mcp_server.py` 是**标准 MCP server**(stdio)，暴露 `health_*` 工具，任意 MCP 客户端可接入；行哈希 `__row_hash` UNIQUE 幂等入库，杜绝重复。

## 目录

```
android/   # LSPosed 模块(App 端：hook + 导出 + 上传)
server/    # MCP server(数据接收/查询的规范接口)
```

## Android 模块构建

1. 从 [libxposed/api](https://github.com/libxposed/api/releases) 下载最新的 `.aar`，解压出 `classes.jar`，重命名为 `libxposed-api.jar` 放到 `android/app/libs/`。
2. 配置 SDK（`android/local.properties` 写 `sdk.dir=<你的 SDK 路径>`），`compileSdk=34`。
3. 构建：
   ```bash
   cd android && JAVA_HOME=<JDK21> gradle :app:assembleDebug --no-daemon
   ```
4. 安装 `app/build/outputs/apk/debug/app-debug.apk`，在 LSPosed 里启用，勾选作用域 `com.heytap.health`。

> 模块依赖 `libxposed` API，**不能**打包进 APK（`compileOnly`）。

## MCP server 使用

```bash
# 依赖: mcp python 包 (pip install mcp)
python server/mcp_server.py   # stdio 传输
```

注册到任意 MCP 客户端（如 Hermes `~/.hermes/config.yaml`）：

```yaml
mcp_servers:
  oppo_health:
    command: <python 解释器(已装 mcp)>
    args: ["<绝对路径>/server/mcp_server.py"]
```

暴露工具：

| 工具 | 功能 |
|---|---|
| `health_ingest` | 接收增量分节数据（幂等入库 + 更新水位） |
| `health_watermark` | 查询各表水位（增量同步游标） |
| `health_list_tables` / `health_stats` | 列出表 / 统计概览 |
| `health_query` | 按时间/列查询已入库数据 |
| `health_schema` | 表结构 |

**安全**：所有标识符白名单校验，杜绝 SQL 注入；数据写入全部参数化。

## 数据格式（分节增量 CSV）

```
===TABLE:DBHeartRate|TIMECOL:data_created_timestamp===
ssoid,device_type,data_created_timestamp,value,...
1249250120,,1779393420000,85,...
```

## 版本

- 5.0.0：MCP 规范化服务端 + 模块版本对齐；增量水位/流式分片/分块上传/幂等去重。

## License

[MIT](LICENSE) — 源码；libxposed-api.jar 属其原作者，按 LGPL-3.0 分发。
