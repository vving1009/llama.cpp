# llama.android Agent 能力迁移记录

> 将 Mini Claude Code (Python) 的 Agent 逻辑迁移到 `llama.cpp/examples/llama.android`，让本地 Qwen3.5_4B 模型具备完整的工具调用能力。
>
> 日期：2026-07-04

---

## 背景

`llama.android` 原本只是一个"纯聊天 Demo"——加载 GGUF 模型后逐字生成回复，没有任何工具调用、Agent Loop 或上下文管理能力。

Mini Claude Code (Python) 实现了完整的 Agent 架构（13 个工具、Agent Loop、4 层压缩、权限系统等），但依赖云端 API（Anthropic/OpenAI），需要联网且需付费。

**目标**：将 Python 版的 Agent 核心逻辑移植到 `llama.android`，用本地 llama.cpp 替代云端 API，实现完全离线的 Agent 循环。

---

## 当前基线（迁移前）

```
llama.android/
├── lib/src/main/java/com/arm/aichat/
│   ├── AiChat.kt                   # 单例门面
│   ├── InferenceEngine.kt          # 接口 + State sealed class
│   ├── gguf/                       # GGUF 元数据解析
│   └── internal/
│       └── InferenceEngineImpl.kt  # JNI 包装 + 流式生成
└── app/src/main/java/com/example/llama/
    ├── MessageAdapter.kt           # 只有 USER/ASSISTANT 两种类型
    └── MainActivity.kt             # 纯聊天 UI，直接调 engine.sendUserPrompt
```

**能力**：加载 GGUF → 设置 system prompt → 发送 user prompt → 流式生成 → 显示在 RecyclerView

---

## 迁移后的架构

```
llama.android/
├── lib/src/main/java/com/arm/aichat/
│   ├── AiChat.kt                   # 未改动
│   ├── InferenceEngine.kt          # 未改动
│   ├── gguf/                       # 未改动
│   ├── tool/                       # 【新增】工具系统
│   │   ├── Tool.kt                 #   Tool 接口 + ToolDefinition/ToolParameter
│   │   ├── ToolCallParser.kt       #   解析 <tool_call>{JSON}</tool_call>
│   │   ├── ToolRegistry.kt         #   工具注册中心 + prompt 生成
│   │   ├── ReadFileTool.kt         #   读文件（沙箱限制）
│   │   ├── WriteFileTool.kt        #   写文件（沙箱限制）
│   │   ├── ListFilesTool.kt        #   glob 列文件
│   │   └── RunShellTool.kt         #   受限 shell（白名单 + 危险参数过滤）
│   ├── agent/                      # 【新增】Agent 核心
│   │   └── AgentLoop.kt            #   Agent 核心循环 + 事件类型定义
│   └── internal/
│       └── InferenceEngineImpl.kt  # 未改动
└── app/src/main/
    ├── java/com/example/llama/
    │   ├── MessageAdapter.kt       # 【修改】4 种消息类型，4 种 ViewHolder
    │   └── MainActivity.kt         # 【修改】接入 AgentLoop，工具调用可视化
    └── res/
        ├── drawable/
        │   ├── bg_tool_call.xml    # 【新增】工具调用气泡背景（橙色）
        │   └── bg_tool_result.xml  # 【新增】工具结果气泡背景（青绿色）
        └── layout/
            ├── item_message_tool_call.xml    # 【新增】工具调用布局
            └── item_message_tool_result.xml  # 【新增】工具结果布局
```

**新增能力**：
1. 注册 4 个内置工具（read_file, write_file, list_files, run_shell）
2. Agent Loop：LLM 生成 → 检测 `<tool_call>` → 执行工具 → 结果回传 → 继续生成
3. 流式输出：token 逐字显示（`AgentEvent.Generating`）
4. 工具调用可视化：橙色气泡（工具名+参数）、青绿色气泡（工具执行结果摘要）

---

## 核心架构设计

### 1. 工具系统（Tool System）

**接口设计**（`tool/Tool.kt`）：

```kotlin
interface Tool {
    val definition: ToolDefinition
    suspend fun execute(params: Map<String, String>): String
}
```

每个工具实现此接口，注册到 `ToolRegistry`。`ToolRegistry.getAll()` 可获取所有工具定义（用于 system prompt），`execute(name, params)` 调用对应工具。

### 2. 工具调用解析器（`tool/ToolCallParser.kt`）

**输入**：模型输出的自由文本（含 `<tool_call>...</tool_call>` 标记）

**处理流程**：
1. 正则提取 `<tool_call>(...)</tool_call>`
2. 修复 JSON 内部因换行导致的断裂（兜底保护）
3. `JSONObject` 解析
4. 返回 `ToolCall(name, params)`

**容错设计**：即使 model 输出格式不完美（如值内含换行字节），解析器也能修复。

### 3. Agent 核心循环（`agent/AgentLoop.kt`）

**循环流程**：

```
用户输入
    ↓
sendUserPrompt(prompt) ──→ [流式 emit Generating(token)] ──→ UI 实时显示
    ↓
完整收集响应文本
    ↓
检查是否含 <tool_call> ... </tool_call>
    ├── 是：解析 → execute_tool → emit ToolCallDetected + ToolResult
    │        → 以 "[Tool result from X]: ..." 为下一轮 prompt → 继续循环
    └── 否：emit AssistantMessage → 结束
```

**事件类型**（`AgentEvent` sealed class）：

| 事件 | 触发时机 | UI 响应 |
|------|---------|--------|
| `UserMessage` | 用户输入 | 添加用户气泡 |
| `Generating` | 逐 token | 流式追加到占位助手气泡 |
| `ToolCallDetected` | 解析到工具调用 | 添加橙色工具调用气泡 |
| `ToolResult` | 工具执行完成 | 添加青绿色工具结果气泡 |
| `AssistantMessage` | 最终纯文本回复 | 替换占位助手气泡为最终文本 |
| `Completed` | 循环结束 | 恢复输入框可用 |

### 4. 系统提示词策略

构建包含工具描述 + 调用规则的 system prompt：

```
You are an AI assistant running on Android.
Available Tools:
- read_file: Read file content
- write_file: Write file content
- list_files: List files
- run_shell: Execute safe shell commands

Rules:
1. If a tool is needed, output ONLY: <tool_call>{JSON}
2. Do NOT add explanations before/after tool call
3. Tool results look like "[Tool result from X]: ..."
4. These are tool return values, NOT user input
```

---

## 关键设计决策

### 决策 1：自由文本解析 VS 结构化 API

**问题**：云端 API（Anthropic/OpenAI）直接返回结构化的 `tool_calls[]`，本地 llama.cpp 只返回自由文本。

**方案**：模型输出 `<tool_call>{JSON}</tool_call>` 标记，由 `ToolCallParser` 正则提取 + JSON 解析。

**理由**：不改 native 层（`ai_chat.cpp`），最小侵入性。Qwen3.5_4B 已验证能稳定输出此格式。

### 决策 2：流式输出 VS 完整收集后判断

**问题**：必须等生成完毕才能判断是否是 tool call（无法拒绝一个半成品标记），但用户等待时需要有打字效果。

**方案**：两者兼顾——collect 阶段同时 `emit Generating(token)` 给 UI 流式显示，等到完整的 `</tool_call>` 闭标签出现后再决定后续动作。

### 决策 3：工具结果回传方式

**问题**：native 层 `InferenceEngineImpl` 没有暴露 tool role 接口，无法区分"用户发言"和"工具返回值"。

**方案（当前）**：用 `[Tool result from X]: content` 文本格式发回 `sendUserPrompt()`，在 system prompt 里明确告知模型这是工具返回值。

**方案（Phase 2）**：扩展 `InferenceEngine` 接口添加 `sendToolResult(toolName, result)`，在 `ai_chat.cpp` 中用 chat template 的 tool 角色插入。

### 决策 4：工具安全边界

| 层面 | 措施 |
|------|------|
| 文件系统 | `canonicalPath` 检查，只允许 `filesDir` / `cacheDir` |
| Shell 命令 | 白名单（ls, pwd, cat, echo, grep, find 等）+ 危险模式过滤（`>`, `<`, `|`, `&&`, `` ` `` 等） |

---

## 编译注意事项

1. **ProGuard**：`app/proguard-rules.pro` 已有 `-keep class com.arm.aichat.**`，新增的 `tool/` 和 `agent/` 包无需额外规则
2. **依赖**：全部使用标准库 + `kotlinx.coroutines`（已有），无新增依赖
3. **native 层未修改**：`ai_chat.cpp`、`CMakeLists.txt`、`build.gradle.kts` 均无变动

---

## 验证清单

### 编译
```bash
cd llama.cpp/examples/llama.android
./gradlew :app:assembleDebug
# 关注 Log: checking ProGuard keep rules for com.arm.aichat.*
```

### 功能测试（从简单到复杂）

#### 1. 普通对话（无工具调用）
```
你好，今天天气怎么样？
```
→ 流式输出文本，无工具调用气泡

#### 2. 单工具调用（核心测试）
```
列出当前目录下的文件
```
→ 预期流程：
```
助手：让我列出当前目录的文件。
🔧 list_files(pattern: *)
✅ list_files: [FILE] test.txt
       [FILE] data.txt
助手：当前目录下有两个文件：test.txt 和 data.txt。
```

#### 3. 读文件后总结（多轮工具）
```
先读取 test.txt，然后总结内容
```
→ 预期流程：
```
助手：让我先读取 test.txt 文件。
🔧 read_file(file_path: test.txt)
✅ read_file: 文件内容...
助手：文件中提到...（模型总结）
```

#### 4. 写文件
```
帮我创建一个叫 hello.txt 的文件，内容写 "Hello World"
```
→ 预期流程：
```
助手：我来创建这个文件。
🔧 write_file(file_path: hello.txt, content: Hello World)
✅ write_file: 成功写入 hello.txt (1行)
助手：文件已创建成功。
```

#### 5. 安全限制测试
```
执行 rm -rf /
```
→ 预期：工具返回"命令不在白名单中"
```
助手：我尝试执行，但...
🔧 run_shell(command: rm -rf /)
✅ run_shell: Error: Command 'rm' is not in the whitelist.
```

#### 6. 多轮工具调用链（终极测试）
```
列出当前目录文件，然后读取最大的那个文件
```
→ 预期：先 list_files，模型根据结果选择最大文件，再 read_file

### 预期 UI 效果

```
┌─────────────────────────────────────┐
│  用户：读取 test.txt                 │
│                                     │
│  助手：让我先读取这个文件。           │ ← 模型先解释再调用工具
│  ┌──────────────────────────────┐   │
│  │ 🔧 read_file(test.txt)      │   │ ← 橙色气泡
│  └──────────────────────────────┘   │
│  ┌──────────────────────────────┐   │
│  │ ✅ read_file: 文件内容...    │   │ ← 青绿色气泡
│  └──────────────────────────────┘   │
│  助手：文件内容是...                 │
└─────────────────────────────────────┘
```

### Logcat 观察
```bash
adb logcat -s AgentLoop
# 查看 turn 计数、工具执行日志
```

---

## 与 Mini Claude Code (Python) 的架构对比

| 组件 | Python 版 | llama.android 版 | 差异原因 |
|------|-----------|-----------------|----------|
| **LLM 后端** | Anthropic API / OpenAI API | 本地 llama.cpp (JNI) | 离线需求 |
| **工具调用格式** | `tool_calls[]` (结构化) | `<tool_call>{JSON}` (文本解析) | 本地模型无结构化 API |
| **并行工具执行** | `asyncio.gather()` | 串行（单线程 llamaDispatcher） | native 层线程限制 |
| **上下文压缩** | 4 层渐进式 | native `shift_context`（简单截断） | 未迁移（留作后续） |
| **权限系统** | 5 种模式 + 声明式规则 | 内置安全限制 | Android 沙箱天然约束 |
| **记忆系统** | 文件存储 + 语义召回 | 无 | 移动端存储限制 |
| **子 Agent** | fork-return 模式 | 无 | 本地模型性能不足 |

---

## 后续可扩展方向

1. **更多工具**：grep_search、edit_file（需要 mtime 防护）、web_fetch（需要 INTERNET 权限）
2. **Android 特有工具**：get_device_info、read_sms（需权限）、take_photo（需权限）
3. **权限系统**：危险工具弹确认对话框（类似 Python 版的 `check_permission`）
4. **上下文压缩**：替换 native 层 `shift_context` 的简单截断，改为智能压缩
5. **native 层 tool role**：扩展 `InferenceEngine` + `ai_chat.cpp` 支持真正的 tool message 角色
6. **会话持久化**：保存/恢复对话历史

---

## 2026-07-06 更新：修复 Agent 循环的两个关键问题

### 背景

测试发现 Agent 循环存在两个问题：
1. 模型输出 `<tool_call>` 时没有前言说明（如"我将为你列出当前目录的文件"）
2. 工具执行结果后没有总结回复（如"当前目录的文件有 xxx"）

### 根因分析

**问题 1 根因**：模型直接输出 `<tool_call>` 而不带前言。系统提示词虽然要求"first explain briefly"，但仅有英文指令，而且 `AgentLoop` 没有将 preamble 提取为 `AssistantMessage` 事件——只作为 `Generating` token 流式输出。

**问题 2 根因（关键 bug）**：`MainActivity.kt` 的 `ToolResult` handler 将 `TOOL_RESULT` 气泡追加到消息列表末尾，但 `ToolCallDetected` handler 之前已经在末尾放了一个 `ASSISTANT("")` 占位符。顺序变成 `[..., TOOL_CALL, ASSISTANT(""), TOOL_RESULT]` ——`TOOL_RESULT` 在最后。第二轮 `Generating` 事件检查 `messages.last().type == ASSISTANT` → 类型不匹配 → token 被静默丢弃。`AssistantMessage` 也因同样原因被丢弃。

### 改动

#### 1. `AgentLoop.kt` — 提取 preamble 作为 AssistantMessage 发出

在检测到 `<tool_call>` 时，将标记之前的文本单独作为 `AssistantMessage` 事件发出，确保 UI 正确渲染。

#### 2. `AgentLoop.kt` — 改进工具结果回传格式

将原来的 `[Tool result from X]: $result` 改为 `buildToolResultPrompt()`，用更清晰的格式引导模型总结结果。

#### 3. `AgentLoop.kt` — 系统提示词强化

- 中英双语规则（匹配用户输入语言）
- 中文 `<tool_call>` 示例（读文件、列目录）
- 强化"必须总结工具结果"指令
- 明确标注工具结果格式

#### 4. `MainActivity.kt` — 修复 ToolResult 插入顺序（关键 bug）

将 `TOOL_RESULT` 插入到末尾的 `ASSISTANT` 占位符之前，确保 `ASSISTANT` 始终在最后，使第二轮 generation 的 `Generating` 和 `AssistantMessage` 事件能正确更新。

#### 5. `AgentLoop.kt` + `MainActivity.kt` — 添加 thinking 控制

Qwen3 4B 等模型会输出空的 `...` 标签，浪费 token 和时间。添加 `thinkingEnabled` 参数：

- `initialize(thinkingEnabled: Boolean = true)` — 初始化时设置
- `reset(thinkingEnabled: Boolean? = null)` — 切换时重新初始化
- 禁用时系统提示词追加抑制指令："Do NOT output thinking or reasoning tags"
- `MainActivity` 默认传入 `thinkingEnabled = false` 关闭

### 文件变更（2026-07-06 增量）

| 文件 | 改动 |
|------|------|
| `lib/.../agent/AgentLoop.kt` | preamble 提取、tool result 格式化、系统提示词强化、thinking 控制 |
| `app/.../MainActivity.kt` | 修复 ToolResult 插入顺序、传入 thinkingEnabled=false |

### 验证

```bash
./gradlew :app:assembleDebug
```

安装后测试：
1. 发送"当前目录的文件有哪些" → 预期：前言气泡 → 工具调用气泡 → 结果气泡 → 总结气泡
2. 发送"当前目录有多少个文件" → 预期：模型直接回答（无工具调用）
3. 观察 `adb logcat -s AgentLoop:*` 查看 turn 计数

---

## 文件清单（新增/修改共 14 个文件）

| 文件 | 操作 | 说明 |
|------|------|------|
| `lib/.../tool/Tool.kt` | 新增 | 工具接口 + 数据类型 |
| `lib/.../tool/ToolCallParser.kt` | 新增 | 工具调用解析 |
| `lib/.../tool/ToolRegistry.kt` | 新增 | 工具注册中心 |
| `lib/.../tool/ReadFileTool.kt` | 新增 | 读文件工具 |
| `lib/.../tool/WriteFileTool.kt` | 新增 | 写文件工具 |
| `lib/.../tool/ListFilesTool.kt` | 新增 | 列文件工具 |
| `lib/.../tool/RunShellTool.kt` | 新增 | Shell 执行工具 |
| `lib/.../agent/AgentLoop.kt` | 新增 | Agent 核心循环（后经两次迭代修复） |
| `app/.../MainActivity.kt` | 修改 | 接入 AgentLoop（后经两次迭代修复） |
| `app/.../MessageAdapter.kt` | 修改 | 4 种消息类型 |
| `app/.../drawable/bg_tool_call.xml` | 新增 | 工具调用气泡背景 |
| `app/.../drawable/bg_tool_result.xml` | 新增 | 工具结果气泡背景 |
| `app/.../layout/item_message_tool_call.xml` | 新增 | 工具调用布局 |
| `app/.../layout/item_message_tool_result.xml` | 新增 | 工具结果布局 |

---

## 2026-07-07 更新：thinking 控制从 prompt 驱动改为 native `--reasoning off` 等价实现

### 背景

之前的 thinking 控制（2026-07-06 更新）靠的是在 system prompt 里追加一段抑制指令：

```
IMPORTANT: Do NOT output thinking or reasoning tags...
```

但模型经常无视这条指令，依然输出 `<thinking>...</thinking>` 块，浪费 token 和响应时间。

### 根因定位

经过代码追踪，发现 `ai_chat.cpp` 的 `chat_add_and_format()` 调用了 `common_chat_format_single(..., use_jinja=false)`，而 `enable_thinking` 这个模板变量**只有 Jinja 渲染路径会消费**，旧版渲染器完全不读它——所以 prompt 级别的抑制指令是唯一生效的机制，且不可靠。

`llama-cli` 的 `--reasoning off` 之所以生效，是因为：
1. `common_params.use_jinja` 默认 `true`（common.h:619）
2. `--reasoning off` 把 `enable_thinking` 设为 `false` 并传进 Jinja 模板
3. 模板根据 `{% if enable_thinking %}` 不输出 thinking 块

### 改动方案（选项 B：修改上游函数 + 默认参数）

给 `common_chat_format_single` 加尾参数 `bool enable_thinking = true`（向后兼容），Android 侧切到 `use_jinja=true` 并显式传 `enable_thinking=false`。

**验证结论（对 CLI/server 零影响）**：`llama-cli` / `llama-server` **不调** `common_chat_format_single`，它们直接调 `common_chat_templates_apply`。全 monorepo 调用此函数的仅 3 处：mtmd-cli、completion、Android。前两个不传新参数 → 走默认值 `true` → behavior 完全不变。

### 涉及的改动

| 文件 | 改动 |
|------|------|
| `common/chat.h` | `common_chat_format_single` 声明加 `bool enable_thinking = true` |
| `common/chat.cpp` | 定义加参数 + 函数体 `inputs.enable_thinking = enable_thinking;` |
| `lib/src/main/cpp/ai_chat.cpp` | 加 `g_thinking_enabled` 全局 + JNI 方法 + `chat_add_and_format` 切 `use_jinja=true` 传开关 |
| `lib/.../InferenceEngine.kt` | 接口加 `suspend fun setThinkingEnabled(enabled: Boolean)` |
| `lib/.../InferenceEngineImpl.kt` | 加 `@FastNative external fun setThinkingEnabledNative` + override 实现（注意使用块体避免 `Log.i` 返回 Int 导致类型不匹配） |
| `app/.../AgentLoop.kt` | `initialize()` 调 `setThinkingEnabled` 在 `setSystemPrompt` 前；`buildSystemPrompt()` 删除 prompt 拼接块 |

### 关键设计笔记

- **`use_jinja=true` 的必要性**：`enable_thinking` 只有 Jinja 渲染路径会消费。旧版渲染器（`llama_chat_apply_template`）完全忽略它。所以必须切到 `use_jinja=true`。
- **Jinja 可用性确认**：`llama-common` 库包含 minijinja 源码（`common/CMakeLists.txt:109-118`），且 Android 的 `CMakeLists.txt:54` 已链接 `llama-common`，无需额外构建配置。
- **默认值安全**：`g_thinking_enabled` 初值 `true`，即使 Kotlin 侧不调 `setThinkingEnabled`，native 行为与原一致（只是渲染器从 legacy 换为 jinja——对现代 GGUF 模板二者输出等价）。
- **Log.i 陷阱**：`Log.i()` 返回 `Int`，用表达式体 `=` 时是 withContext 的最后一个表达式，导致 override 返回 `Int` 而非 `Unit`。解决方案：改用块体 `{ withContext { ... } }`。

### 验证方法

```bash
export JAVA_HOME="/Users/vv/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :lib:externalNativeBuildDebug -x lintAnalyzeDebug   # 仅 native
./gradlew :app:assembleDebug                                   # 全模块
```

端到端测试：
1. 用 Qwen3 等 thinking 模型 → 关闭 thinking 时无 `<thinking>` 输出，开启时正常出现
2. 用无 thinking 模型 → 回归正常
3. 多轮工具调用 → 全链路格式化正常

### 实施结果

| # | 任务 | 状态 |
|---|------|------|
| 1 | `common/chat.h` 加 `enable_thinking` 参数 | ✅ 完成 |
| 2 | `common/chat.cpp` 接通 `enable_thinking` | ✅ 完成 |
| 3 | `ai_chat.cpp` 加 `g_thinking_enabled` + JNI + `use_jinja` | ✅ 完成 |
| 4 | `InferenceEngine.kt` 接口加方法 | ✅ 完成 |
| 5 | `InferenceEngineImpl.kt` 加 JNI external + 实现 | ✅ 完成 |
| 6 | `AgentLoop.kt` 改用 native 开关 | ✅ 完成 |
| 7 | 编译验证 native + app | ✅ 完成 |

**最终文件变更清单**：

| 文件 | 类型 | 改动 |
|------|------|------|
| `common/chat.h` | 修改 | `common_chat_format_single` 声明加 `bool enable_thinking = true`（默认参数，向后兼容） |
| `common/chat.cpp` | 修改 | 定义处加同名参数，函数体 `inputs.enable_thinking = enable_thinking` |
| `lib/src/main/cpp/ai_chat.cpp` | 修改 | 加 `g_thinking_enabled` 全局变量 + `setThinkingEnabledNative` JNI 方法 + `chat_add_and_format` 切 `use_jinja=true` 并传 `g_thinking_enabled` |
| `lib/.../InferenceEngine.kt` | 修改 | 接口加 `suspend fun setThinkingEnabled(enabled: Boolean)` |
| `lib/.../InferenceEngineImpl.kt` | 修改 | 加 `@FastNative external fun setThinkingEnabledNative` + override 实现（块体避免 `Log.i` 返回 Int 导致类型不匹配） |
| `app/.../AgentLoop.kt` | 修改 | `initialize()` 先调 `setThinkingEnabled` 再 `setSystemPrompt`；`buildSystemPrompt()` 删除 prompt 拼接块 |
| 本文件 | 修改 | 追加本次改动记录 |

**数据流**：

```
MainActivity.kt 传 thinkingEnabled=false
  → AgentLoop.initialize(thinkingEnabled=false)
    → inferenceEngine.setThinkingEnabled(false)  ← 推送到 native
      → JNI → ai_chat.cpp: g_thinking_enabled = false
    → inferenceEngine.setSystemPrompt(prompt)
      → JNI → ai_chat.cpp: processSystemPrompt()
        → chat_add_and_format(use_jinja=true, enable_thinking=false)
          → common_chat_format_single(..., use_jinja=true, enable_thinking=false)
            → Jinja 模板收到 enable_thinking=False
            → 模板不渲染 <thinking> 块 ✓
```