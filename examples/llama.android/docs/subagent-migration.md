# Multi-Agent (SubAgent) 系统迁移文档

## 概述

将 `claude-code-from-scratch` TypeScript 项目的多 agent 架构迁移到 Android 项目 (`llama.android`)。子 agent 通过 OpenAI 兼容 API 访问远端模型供应商，主 agent 继续使用本地 llama.cpp 模型。

## 架构

```
AgentLoop (本地 llama.cpp 模型, llamaDispatcher)
  └── agent tool call → SubAgent (远端 OpenAI 兼容 API, Dispatchers.IO)
       ├── explore: 只读搜索
       ├── plan: 只读结构化规划
       └── general: 完整工具访问
```

子 agent 的 token 通过 `Flow<String>` 实时流式传输到 UI。

## 新增文件

### 1. `lib/src/main/java/com/arm/aichat/subagent/SubAgentSettings.kt`

API 连接配置数据类：

```kotlin
data class SubAgentSettings(
    val apiBase: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini"
)
```

### 2. `lib/src/main/java/com/arm/aichat/subagent/SubAgentResult.kt`

子 agent 执行结果：

```kotlin
data class SubAgentResult(
    val text: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0
)
```

### 3. `lib/src/main/java/com/arm/aichat/subagent/SubAgentType.kt`

Agent 类型定义，包含三种内置类型：

- **explore**: 只读工具（`read_file`, `list_files`），用于代码搜索
- **plan**: 只读工具，用于结构化实现规划
- **general**: 全部工具（除 `agent` 外），用于独立任务

### 4. `lib/src/main/java/com/arm/aichat/subagent/OpenAiClient.kt`

基于 Ktor + Moshi 的 OpenAI 兼容 HTTP 客户端：

- **HTTP 客户端**: `HttpClient(OkHttp)` — Ktor 的 OkHttp 引擎
- **JSON 序列化**: `Moshi` + `KotlinJsonAdapterFactory` — 反射适配器
- **SSE 流式解析**: 逐行读取 `data: {...}` 格式，支持 `[DONE]` 终止信号
- **工具调用累积**: 按 index 累积流式分块的 tool_calls 参数
- **Token 回调**: `onTokens: suspend (String) -> Unit` 支持从 Flow 直接 emit

关键数据类：

| 类 | 用途 |
|---|---|
| `ChatCompletionRequest` | 请求体（model, messages, tools, stream） |
| `ChatCompletionChunk` | SSE 响应块（choices, usage） |
| `ChatMessage` | 内部消息类型 |
| `OpenAiMessage` | OpenAI 格式消息 |
| `ChatTool` / `ChatFunction` | 工具定义（OpenAI function 格式） |
| `ToolCallData` | 解析后的工具调用 |
| `Usage` | Token 用量统计 |

### 5. `lib/src/main/java/com/arm/aichat/subagent/SubAgent.kt`

子 agent 执行器：

- **`runStreaming(prompt: String): Flow<String>`** — 流式执行
  - 维护独立消息历史
  - 自动循环：API 调用 → 工具执行 → 结果反馈 → 继续
  - 最多 10 轮 (MAX_TURNS)
  - 运行在 `Dispatchers.IO`
- **`runOnce(prompt: String): SubAgentResult`** — 非流式包装
- 工具调用通过共享的 `ToolRegistry` 执行（与主 agent 共用）

### 6. `lib/src/main/java/com/arm/aichat/tool/AgentTool.kt`

`agent` 工具实现：

```kotlin
class AgentTool(settings: SubAgentSettings, toolRegistry: ToolRegistry) : Tool
```

参数：
- `type`: "explore" | "plan" | "general"（默认 "general"）
- `description`: 简短任务描述
- `prompt`: 详细任务指令

## 修改文件

### 1. `lib/src/main/java/com/arm/aichat/tool/Tool.kt`

添加 `executeStreaming()` 默认实现：

```kotlin
interface Tool {
    val definition: ToolDefinition
    suspend fun execute(params: Map<String, String>): String

    suspend fun executeStreaming(params: Map<String, String>): Flow<String> = flow {
        emit(execute(params))
    }
    // ...
}
```

### 2. `lib/src/main/java/com/arm/aichat/tool/ToolRegistry.kt`

添加 `executeStreaming()` 方法，路由到工具的流式实现。

### 3. `lib/src/main/java/com/arm/aichat/agent/AgentLoop.kt`

- 工具执行改为调用 `executeStreaming()` 并收集 Flow
- 新增 `AgentEvent.SubAgentToken` 事件类型
- 系统提示词中添加 `agent` 工具描述

```kotlin
// 新的流式工具执行
val resultBuffer = StringBuilder()
toolRegistry.executeStreaming(toolCall.name, toolCall.params)
    .collect { token ->
        resultBuffer.append(token)
        emit(AgentEvent.SubAgentToken(token))
    }
val result = resultBuffer.toString()
```

### 4. `app/src/main/java/com/example/llama/MainActivity.kt`

- 注册 `AgentTool` 到 `ToolRegistry`
- 处理 `AgentEvent.SubAgentToken` 事件 — 追加到最后的 TOOL_RESULT 气泡

### 5. `gradle/libs.versions.toml` + `lib/build.gradle.kts`

添加依赖：

```toml
[versions]
ktor = "3.1.0"
moshi = "1.15.2"

[libraries]
ktor-client-core = { group = "io.ktor", name = "ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { group = "io.ktor", name = "ktor-client-okhttp", version.ref = "ktor" }
moshi = { group = "com.squareup.moshi", name = "moshi", version.ref = "moshi" }
moshi-kotlin = { group = "com.squareup.moshi", name = "moshi-kotlin", version.ref = "moshi" }
```

## 数据流

```
用户输入 → AgentLoop (本地 LLM)
  → 本地 LLM 生成 <tool_call>{"name":"agent",...}
  → ToolCallParser 解析工具调用
  → toolRegistry.executeStreaming("agent", params)
    → AgentTool.executeStreaming()
      → SubAgent.runStreaming(prompt)
        → OpenAiClient.chatCompletion() (Ktor + SSE)
          → 流式返回 token 给 SubAgent
        → SubAgent 通过 Flow<String> 发射 token
      → AgentTool 传递 Flow
    → AgentLoop 收集 Flow, 发射 SubAgentToken 事件
    → UI 实时显示子 agent 输出
  → 子 agent 执行完成, 收集完整结果文本
  → AgentLoop 发送结果给本地 LLM 做总结
  → 本地 LLM 生成自然语言总结 → AssistantMessage → UI
```

## 子 agent 内部工具循环

```
SubAgent.runStreaming()
  1. 初始化消息: [system, user]
  2. API 调用 → 流式 token
  3. 检查是否有 tool_calls
     a. 有 → 执行工具 → 添加结果到消息历史 → 回到第 2 步
     b. 无 → 完成, 返回最终文本
  4. 最多 10 轮
```

## 线程安全

| 组件 | 调度器 | 说明 |
|---|---|---|
| AgentLoop (主 agent) | `llamaDispatcher` (单线程) | 本地 llama.cpp 模型 |
| SubAgent.runStreaming() | `Dispatchers.IO` | 独立于 llamaDispatcher |
| OpenAiClient HTTP | `Dispatchers.IO` | 通过 Ktor OkHttp 引擎 |
| ToolRegistry.execute() | 调用者协程 | 共享但线程安全 |

## 验证

```sh
# 编译 lib 模块
JAVA_HOME=/path/to/jdk-21 ./gradlew :lib:compileDebugKotlin

# 编译 app 模块
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:compileDebugKotlin
```

## 配置

API key 等目前在 `MainActivity.kt` 中硬编码：

```kotlin
val subAgentSettings = SubAgentSettings(
    apiBase = "https://api.openai.com/v1",
    apiKey = "",  // 替换为真实 key
    model = "gpt-4o-mini"
)
```

## 参考

- 参考项目: `claude-code-from-scratch` (TypeScript)
  - `src/subagent.ts` — agent 类型定义
  - `src/agent.ts` — 双后端 (Anthropic/OpenAI) Agent 实现
  - `src/tools.ts` — 工具定义 + 权限系统
- Android 项目: `llama.cpp/examples/llama.android`