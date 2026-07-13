# MCP (Model Context Protocol) Android 集成

## 概述

将 MCP 客户端架构从 [claude-code-from-scratch](https://github.com/ggml-org/llama.cpp)（TypeScript CLI）迁移到 `llama.android` 项目。原始实现通过 JSON-RPC 2.0 over stdio（子进程 stdin/stdout）连接 MCP 服务器。Android 上适配为 **LocalSocket**（同设备跨进程 IPC）和 **TCP**（远程连接）两种传输方式，架构、协议、集成模式与原版一致。

## 包结构

```
lib/src/main/java/com/arm/aichat/mcp/
├── McpTypes.kt          — 数据类（McpServerConfig, McpToolInfo, McpToolDefinition）
├── McpJsonRpc.kt        — JSON-RPC 2.0 消息构建 + Map→JSON 类型转换
├── McpException.kt      — 自定义 MCP 异常
├── McpTransport.kt      — 传输抽象层（LocalSocketTransport / TcpSocketTransport）
├── McpConnection.kt     — 单个 MCP 连接（JSON-RPC 2.0 + NDJSON 协议）
├── McpConfigLoader.kt   — 从 assets/.mcp.json → filesDir/.mcp.json 加载配置
└── McpManager.kt        — 总协调器：连接、发现工具、路由调用、断开清理
```

## 架构

```
McpManager (per AgentLoop instance)
  ├── McpConnection("server1", LocalSocketTransport)  ← 同设备跨进程（Unix domain socket）
  ├── McpConnection("server2", TcpSocketTransport)    ← 远程连接（TCP）
  └── 工具列表: McpToolInfo[] (扁平化，前缀 "mcp__server__tool")
```

### 集成点

```
AgentLoop
  ├── 系统提示词:  内置工具描述 + MCP 工具描述 → 发给 LLM
  ├── 工具路由:    mcp__server__tool → McpManager.callTool()
  │                其他 → ToolRegistry.execute()
  └── 生命周期:     handleSelectedModel() 时连接，onDestroy() 时断开
```

## 传输层

两种传输方式，通过配置自动选择：

| 传输方式 | 类 | 配置字段 | 权限 | 场景 |
|---------|-----|---------|------|------|
| LocalSocket | `LocalSocketTransport` | `"address"` | 无需网络权限 | 同设备跨进程 IPC |
| TCP | `TcpSocketTransport` | `"host"` + `"port"` | 需要 `INTERNET` | 远程/桌面 MCP 服务器 |

`McpConnection` 接收 `McpTransport` 接口，通过 `InputStream`/`OutputStream` 读写，与底层 socket 类型无关。

## 协议

JSON-RPC 2.0 over NDJSON（换行分隔 JSON），与原版 TypeScript 实现完全一致：

```
→ {"jsonrpc":"2.0","id":1,"method":"initialize",       "params":{...}}
← {"jsonrpc":"2.0","id":1,"result":{...}}
→ {"jsonrpc":"2.0","method":"notifications/initialized"}
→ {"jsonrpc":"2.0","id":2,"method":"tools/list",       "params":{}}
← {"jsonrpc":"2.0","id":2,"result":{"tools":[...]}}
→ {"jsonrpc":"2.0","id":3,"method":"tools/call",       "params":{"name":"echo","arguments":{...}}}
← {"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"hi"}]}}
```

## 配置文件

### 位置（优先级从低到高）

1. `app/src/main/assets/.mcp.json` — 打包在 APK 中，只读
2. `filesDir/.mcp.json` — 用户可写，运行时覆盖

### 格式

```json
{
  "mcpServers": {
    "local_db": {
      "address": "com.example.mcp.server"      // LocalSocket
    },
    "remote_weather": {
      "host": "192.168.1.100",
      "port": 5000                              // TCP
    }
  }
}
```

## 工具命名规则

MCP 工具暴露给 LLM 时使用 `mcp__serverName__toolName` 前缀：

- `mcp__` — 标识这是 MCP 工具（与内置工具区分）
- `serverName` — 路由到对应的连接
- `toolName` — 该服务器上的具体工具名

支持工具名中包含 `__`：`mcp__myServer__my__tool` → server=`myServer`, tool=`my__tool`

## 参数类型转换

`ToolCallParser` 产出 `Map<String, String>`，MCP 服务器需要类型化 JSON。转换规则：

| Schema 类型 | Kotlin 转换 |
|-------------|-------------|
| `"string"` | 原样传递 |
| `"number"` | `value.toDoubleOrNull()` |
| `"integer"` | `value.toIntOrNull()` |
| `"boolean"` | `value.toBooleanStrictOrNull()` |
| `"array"` | 尝试 JSON 解析，失败时按逗号分割 |
| `"object"` | 尝试 JSONObject 解析，失败时传字符串 |

无 schema 时所有参数按字符串传递。

## 关键设计决策

### McpConnection 生命周期

```
构造函数 → 后台 reader 协程启动（Dispatchers.IO）
    ↓
initialize() → MCP 握手（15s 超时）
    ↓
listTools() → 发现工具（15s 超时）
    ↓
callTool() → 工具调用（可多次）
    ↓
close() → 关闭 transport → 拒绝所有 pending 请求 → 取消 reader → 清理资源
```

### 后台 Reader 协程

- 运行在 `Dispatchers.IO`，持续读取 `BufferedReader`
- 每行解析为 JSON，按 `id` 匹配 pending 请求并 resolve/reject
- 非 JSON 行（服务器日志）静默忽略
- 流关闭时自动退出循环

### 错误处理

- **连接失败**: 单个服务器失败不影响其他服务器
- **工具调用失败**: 返回错误字符串给 LLM，由 LLM 解释给用户
- **超时**: initialize/listTools 有 15s 超时；connect 有 5s 超时
- **无配置**: 无 `.mcp.json` 时 `loadAndConnect()` 是空操作

## 文件变更清单

### 新增 (8 个文件)

| # | 文件 | 行数 | 说明 |
|---|------|------|------|
| 1 | `lib/.../mcp/McpTypes.kt` | ~40 | 数据类定义 |
| 2 | `lib/.../mcp/McpJsonRpc.kt` | ~100 | JSON-RPC 消息构建 + 类型转换 |
| 3 | `lib/.../mcp/McpException.kt` | ~5 | 自定义异常 |
| 4 | `lib/.../mcp/McpTransport.kt` | ~100 | LocalSocket + TCP 传输实现 |
| 5 | `lib/.../mcp/McpConnection.kt` | ~210 | JSON-RPC 2.0 协议实现 |
| 6 | `lib/.../mcp/McpConfigLoader.kt` | ~80 | 配置加载 |
| 7 | `lib/.../mcp/McpManager.kt` | ~160 | 总协调器 |
| 8 | `app/src/main/assets/.mcp.json` | ~11 | 示例配置 |

### 修改 (5 个文件)

| # | 文件 | 变更内容 |
|---|------|---------|
| 1 | `lib/.../agent/AgentLoop.kt` | 添加 `McpManager` 参数；`mcp__` 前缀工具路由到 MCP；系统提示词追加 MCP 工具描述 |
| 2 | `app/.../MainActivity.kt` | 初始化 `McpManager`；传递给 `AgentLoop`；`onDestroy()` 中 disconnect |
| 3 | `app/.../AndroidManifest.xml` | 添加 `INTERNET` 权限 |
| 4 | `app/proguard-rules.pro` | 添加 `-keep class com.arm.aichat.mcp.*` |
| 5 | `lib/consumer-rules.pro` | 添加 `-keep class com.arm.aichat.mcp.*` |

## 使用方式

### 1. 本地 MCP 服务器（LocalSocket）

在 Android 设备上运行一个监听 LocalSocket 的 MCP 服务进程（Service 或独立 app），配置：

```json
{
  "mcpServers": {
    "my_local": { "address": "com.example.mcp.server" }
  }
}
```

### 2. 远程 MCP 服务器（TCP）

在桌面/服务器上运行 MCP 服务器（如 `npx @anthropic/mcp-server-filesystem`），配置：

```json
{
  "mcpServers": {
    "desktop_tools": { "host": "192.168.1.100", "port": 5000 }
  }
}
```

### 3. 无 MCP

不配置 `.mcp.json` 文件即可，`McpManager.loadAndConnect()` 会是空操作，应用行为完全不变。

## 验证

1. **编译检查**: `./gradlew :lib:build` 应成功
2. **无 MCP 配置**: 应用完全正常工作
3. **有 MCP 配置**: logcat 中应看到 `MCP connected to 'X' with N tools`
4. **工具调用**: LLM 输出 `mcp__server__tool` 格式的工具调用应正确路由到对应服务器
5. **断开清理**: `onDestroy()` 关闭所有 socket，logcat 无泄漏警告