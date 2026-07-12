# Skills 系统迁移文档

## 概述

参考 [claude-code-from-scratch](https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android) 项目的 Skills 系统，将可复用的 Prompt 模板机制迁移到 Android llama.cpp 项目中。

> 原项目是一个 TypeScript CLI agent，Skills 是存储在 `.claude/skills/*/SKILL.md` 中的可复用 prompt 模板，支持 frontmatter 元数据 + 模板变量替换 + 双重调用路径。

---

## 新增文件

| 文件 | 包路径 | 说明 |
|------|--------|------|
| `Skill.kt` | `com.arm.aichat.skill` | 数据类型：SkillDefinition, SkillContext, ResolvedSkill, FrontmatterResult |
| `FrontmatterParser.kt` | `com.arm.aichat.skill` | 手动 YAML frontmatter 解析器（无外部依赖） |
| `SkillLoader.kt` | `com.arm.aichat.skill` | 扫描 filesDir 和 assets 目录发现 SKILL.md 文件 |
| `SkillRegistry.kt` | `com.arm.aichat.skill` | 技能注册中心：发现、缓存、查询、解析 prompt、生成系统提示词 |
| `InvokeSkillTool.kt` | `com.arm.aichat.skill` | 实现 Tool 接口的 invoke_skill 工具，供 LLM 自动调用技能 |
| `SkillRegistryTest.kt` | `com.arm.aichat.skill` (test) | 单元测试：frontmatter 解析、prompt 解析、描述生成 |

## 修改文件

| 文件 | 包路径 | 改动内容 |
|------|--------|----------|
| `AgentLoop.kt` | `com.arm.aichat.agent` | 添加 SkillRegistry 参数；初始化时调用 skillRegistry.initialize()；系统提示词追加技能描述 |
| `MainActivity.kt` | `com.example.llama` | 创建 SkillRegistry 和 InvokeSkillTool；注册到 AgentLoop；handleUserInput() 添加 `/` 命令检测 |

## 新增资源

| 资源 | 路径 | 说明 |
|------|------|------|
| translate 技能 | `app/src/main/assets/skills/translate/SKILL.md` | 内置翻译技能（较低优先级） |
| summarize 技能 | `app/src/main/assets/skills/summarize/SKILL.md` | 内置摘要技能（较低优先级） |

---

## 架构设计

### SKILL.md 格式

```
---
name: translate
description: Translate text between languages
user-invocable: true
when_to_use: When the user asks to translate text, or mentions translation
allowed-tools: [read_file, write_file]
---
Translate the following text to the target language.

$ARGUMENTS
```

| Frontmatter 字段 | 必需 | 说明 |
|------|--------|------|
| `name` | 是 | 技能名称，用户通过 `/name` 调用，模型通过 `invoke_skill` 调用 |
| `description` | 否 | 显示在系统提示词中的描述 |
| `when_to_use` | 否 | 给模型看的触发条件 |
| `allowed-tools` | 否 | 技能可用工具白名单（当前未启用） |
| `user-invocable` | 否 | 默认为 true，false 表示只能由模型自动调用 |
| `context` | 否 | inline 或 fork（fork 未实现） |

### 模板变量

| 变量 | 替换为 |
|------|--------|
| `$ARGUMENTS` | 用户传入的参数 |
| `${ARGUMENTS}` | 用户传入的参数（同上） |
| `${CLAUDE_SKILL_DIR}` | 技能目录路径 |

替换顺序：先替换 `${ARGUMENTS}`（长匹配），再替换 `$ARGUMENTS`（短匹配），防止 `${ARGUMENTS}` 中的 `$ARGUMENTS` 部分被提前替换。

### 技能加载优先级

```
filesDir/skills/ (用户级, 高优先级)
    ↓ 同名技能覆盖
assets/skills/   (内置, 低优先级)
```

使用 `LinkedMap` 去重：先加载内置技能，再加载用户级技能（同名 key 覆盖前者）。和参考项目的 `Map<String, SkillDefinition>` 去重模式一致。

---

## 双重调用路径

### 路径 1：用户手动调用

用户在聊天框输入 `/translate hello world`：

```
MainActivity.handleUserInput()
    → 检测到 "/" 前缀
    → skillRegistry.getByName("translate")
    → skillRegistry.resolvePrompt(skill, "hello world")
    → 替换后的 prompt 作为 userMsg 发送给 LLM
```

### 路径 2：模型自动调用

LLM 输出 `<tool_call>` 调用 `invoke_skill`：

```
AgentLoop 检测到 tool_call
    → toolRegistry.execute("invoke_skill", {"skill_name":"translate","args":"hello"})
    → InvokeSkillTool.execute() 调用 skillRegistry.executeSkill()
    → 返回 "[Skill "translate" activated]\n\nTranslate: hello"
    → AgentLoop 将结果包装为 tool_result 发给 LLM
    → LLM 看到技能指令并执行
```

---

## 调用示例

### 系统提示词中的技能描述

```
# Available Skills

User-invocable skills (type /<name> in chat):
- **/translate**: Translate text between languages
  When to use: When the user asks to translate text
- **/summarize**: Summarize text concisely
  When to use: When the user asks to summarize or condense text

To invoke a skill programmatically, use the invoke_skill tool with skill_name and optional args.
```

### invoke_skill 工具定义（显示在 ## Available Tools 部分）

```
### invoke_skill
Invoke a registered skill by name. Returns the skill's prompt template with $ARGUMENTS resolved.
Use this when a skill's when_to_use condition matches the user's request.

Parameters:
- skill_name: string - The name of the skill to invoke (required)
- args: string - Optional arguments to pass to the skill (optional)
```

---

## 验证方法

### 用户触发
```
输入: /translate Hello world
→ 技能 prompt 注入对话，LLM 执行翻译
```

### 模型自动触发
```
在系统提示词中已包含技能描述和 invoke_skill 工具定义
→ LLM 可自行判断调用
```

### 优先级覆盖
```
adb push <skill_dir> /data/data/com.example.llama.aichat/files/skills/
→ 同名技能会覆盖内置版本
```

---

## 限制

- **fork 模式未实现**：参考项目支持子 Agent 执行模式，移动端单线程推理不适用
- **allowed-tools 未启用**：前端解析器支持该字段，但当前 AgentLoop 未做工具白名单过滤
- **技能名严格匹配**：大小写敏感，不支持下划线/连字符容错匹配

---

## 文件结构

```
lib/src/main/java/com/arm/aichat/skill/
    ├── Skill.kt
    ├── FrontmatterParser.kt
    ├── SkillLoader.kt
    ├── SkillRegistry.kt
    └── InvokeSkillTool.kt

lib/src/test/java/com/arm/aichat/skill/
    └── SkillRegistryTest.kt

app/src/main/assets/skills/
    ├── translate/SKILL.md
    └── summarize/SKILL.md
```