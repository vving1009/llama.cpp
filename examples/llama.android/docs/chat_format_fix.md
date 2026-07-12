# Multi-turn Chat Prompt Rendering Bug Fix

## Bug Description

In a multi-turn chat with tool calls, when the tool result is sent back as a user message (turn 2), the formatted prompt loses the `<|im_start|>user\n` prefix. This causes the LLM to receive a malformed prompt, producing incorrect or unexpected output.

### Symptom

- **Turn 1 (user → assistant with tool call)**: works correctly
- **Turn 2 (tool result → re-prompt LLM)**: prompt starts with `\nThe tool 'list_files'...` instead of `<|im_start|>user\nThe tool 'list_files'...`
- The missing prefix is exactly 28 bytes (later narrowed to 19 bytes offset)

## Conceptual Background

### Why diff? Why not just render the new message directly?

You might think: "I have a new message, just render it — why involve all the past messages?"

The key insight is that the jinja template needs **context** from the full message list to produce correct output:

- Role boundaries: `<|im_start|>user\n` vs `<|im_start|>assistant\n` — the template decides which wrapper to use based on the message's role and its position relative to other messages
- Tool call chains: if the previous assistant message called a tool, the template needs to see the `<tool_call>` wrapper to know how to format the next user message
- Thinking tags: the template decides whether to strip thinking tags from historical assistant messages based on `last_query_index` (which depends on the position of the last non-tool-response user message)
- Generation prompt: `add_generation_prompt=true` adds `<|im_start|>assistant\n` at the end, but only the template knows where that should go

Therefore, the workflow is always: `render(past + new)` → extract the "new" portion from the result. The diff algorithm is just the extraction mechanism.

### add_ass / add_generation_prompt

- `add_generation_prompt = true` → template appends `<|im_start|>assistant\n` at the end of the prompt, signaling to the LLM "it's your turn to respond"
- `add_ass` is the parameter name in the code, derived from `role == ROLE_USER` — only user messages trigger this because only after a user message should the assistant be prompted to reply
- `add_generation_prompt = false` → no assistant header appended. Used for intermediate renders (e.g., rendering just the history without triggering a response)

### enable_thinking and thinking tags

The Qwen3-style template handles thinking tags in a **counterintuitive** way:

| `enable_thinking` | Generation prompt output | Meaning |
|---|---|---|
| `true` | `<|im_start|>assistant\n thinking\n` | Only the thinking start tag; model generates thinking content, then emits ` response\n` itself |
| `false` | `<|im_start|>assistant\n thinking\n\n response\n\n` | Both start and end tags; model skips thinking and goes straight to response content |

So `enable_thinking=false` adds **more** tags, not fewer — it brackets the empty thinking phase so the model knows to jump straight to the response.

### Why thinking tag stripping causes the 19-byte offset

The underlying cause is that the jinja template **conditionally strips thinking tags** from historical assistant messages based on the `multi_step_tool` logic:

```jinja
{%- if loop.index0 > ns.last_query_index %}
    {{- '<|im_start|>assistant\n thinking\n' + reasoning_content + '\n response\n\n' + content }}
{%- else %}
    {{- '<|im_start|>assistant\n' + content }}
{%- endif %}
```

`last_query_index` is the index of the last "real" user query (not a `<tool_response>` wrapper). When a new user message (tool result) is added to `chat_msgs`, `last_query_index` shifts, causing the same assistant message to be rendered **without** thinking tags in `fmt_new_msg` even though it had them in `fmt_past_msg`.

This is the root cause of the 19-byte offset: `render(past)` and `render(past + new)` produce different outputs for the "past" portion because the last query index changes.

## Root Cause

### `common_chat_format_single`'s broken diff algorithm

`common_chat_format_single` (in `common/chat.cpp:607-637`) works by:

```cpp
// 1. Render past history alone (no generation prompt)
fmt_past_msg = render(past_msg, add_generation_prompt=false);

// 2. Render past + new message (with generation prompt)
fmt_new_msg = render(past_msg + new_msg, add_generation_prompt=true);

// 3. Assume fmt_past_msg is a prefix of fmt_new_msg
//    Return only the "new" part
ss << fmt_new_msg.substr(fmt_past_msg.size(), ...);
```

**Assumption**: `fmt_new_msg` starts with `fmt_past_msg` as a byte-for-byte prefix.

**Reality**: Jinja template output diverges between the two renders because:

| Factor | `fmt_past_msg` render | `fmt_new_msg` render |
|--------|----------------------|----------------------|
| `add_generation_prompt` | `false` | `true` |
| Input messages | `past_msg` (N items) | `past_msg + new_msg` (N+1 items) |

When `add_generation_prompt` toggles, the jinja template can produce different output for the "past" portion of the string. This breaks the prefix-match invariant.

### Concrete cause (19-byte offset)

The jinja template (Qwen3 style) has logic for handling assistant messages with thinking tags:

```jinja
{%- if loop.index0 > ns.last_query_index %}
    {{- ' thinking\n' + reasoning_content + '\n response\n\n' + content }}
{%- else %}
    {{- '<|im_start|>' + message.role + '\n' + content }}
{%- endif %}
```

The `last_query_index` is determined by a `multi_step_tool` logic that scans messages in reverse to find the last non-tool-response user query.

| Scenario | chat_msgs | assistant1 index | last_query_index | Add thinking tags? |
|---|---|---|---|---|
| `past_user_003` | [sys, user1, **asst1**] | 2 | 1 (user1) | ✅ 2 > 1 → yes |
| `new_user_003` | [sys, user1, asst1, **user2**] | 2 | 3 (user2) | ❌ 2 < 3 → no |

The `thinking\n\n response\n\n` wrapper (~19 bytes) is present in `fmt_past_msg` but stripped from the same assistant message in `fmt_new_msg`, causing a 19-byte offset. `substr(fmt_past_msg.size())` therefore cuts at the wrong position and drops the `<|im_start|>user` prefix.

### Data from logs

| Render | add_ass | past_size | new_size | seam_off | seam_minus_past | Note |
|--------|---------|-----------|----------|----------|-----------------|------|
| Turn 1 user | true | 3226 | 3325 | 3226 | 0 | OK |
| Turn 1 assistant | false | 3284 | 3451 | 3284 | 0 | OK |
| **Turn 2 user** | **true** | **3451** | **3745** | **3432** | **-19** | **BUG** |
| Turn 2 assistant | false | 3704 | 3898 | 3704 | 0 | OK |

## The Fix: Anchor-based Seam Search

### Approach

Replace the broken prefix-match with a **tail-anchor search**:

1. Take the last 64 bytes of `fmt_past_msg` as a literal anchor
2. `rfind()` this anchor in `fmt_new_msg` (from the end, to avoid false matches)
3. The anchor position + anchor length = seam_off (the byte boundary between "already seen" and "new" content)
4. Use `fmt_new_msg.substr(seam_off)` to get only the new content

### Multi-length fallback

```
Try: 64 → 32 → 16 → 8 → 4 bytes
↓
If all fail: return whole fmt_new_msg (safe, slightly more tokenisation)
```

### Why it works

`fmt_past_msg`'s tail (the closing wrapper `<|im_end|>\n` of the last assistant message) is always present byte-for-byte in `fmt_new_msg` because the jinja template renders the same past messages identically. The anchor search finds the exact byte position of this tail, regardless of any jinja-introduced prefix drift in the earlier part of the string.

### Files changed

| File | Change | Notes |
|------|--------|-------|
| `lib/src/main/cpp/chat_format_local.cpp` | **NEW**: Local copy of `common_chat_format_single` with anchor-based diff | Main fix |
| `lib/src/main/cpp/CMakeLists.txt` | Added `chat_format_local.cpp` to build | |
| `lib/src/main/cpp/ai_chat.cpp` | Replaced `common_chat_format_single` call with `chat_format_local`; added `set_log_dump_dir` JNI | |
| `lib/src/main/java/.../InferenceEngineImpl.kt` | Added `diagDir` constructor param + `setDiagDir` JNI call | |

## Alternatives Considered

### B: Full-render (no diff, no KV cache rebuild)

Return `fmt_new_msg` whole (no `substr`). This increases tokenisation per turn (past + new re-tokenised) but KV cache continues accumulating as before.

```cpp
return fmt_new_msg;  // No substr needed
```

**Rejected**: KV cache accumulates faster (past + new per turn, not just new), and the same content gets decoded multiple times.

### C: Full-render + KV cache rebuild (llama-server approach)

`llama-server` does **not** use `common_chat_format_single` at all. Instead it calls `common_chat_templates_apply` directly with the full messages array and feeds the entire result to `llama_decode`:

```
HTTP POST /v1/chat/completions  receives messages[] array
  → inputs.messages = messages (full history + new message)
  → inputs.add_generation_prompt = true
  → common_chat_templates_apply(tmpls, inputs)
  → llama_params["prompt"] = chat_params.prompt  (full prompt, no diff)

tokenize(full_prompt)  →  slot.task->tokens = all tokens
llama_decode(batch)    →  KV cache starts from position 0
                         (each request resets the slot's KV cache)
```

**Key differences from our approach:**
- No diff — renders the entire message list each time
- No KV cache accumulation — each request starts from position 0 (KV cache is rebuilt)
- Each HTTP request is self-contained — server has no concept of "past turns"

**For our Android app**, this would require:
- Resetting `n_past = 0` each turn
- Rewriting `processUserPrompt`, `generateNextToken`, `shift_context` to handle full re-decode
- Losing the performance benefit of KV cache accumulation across turns

**Rejected** for our fix because it's too invasive and loses the performance advantage of cumulative KV cache. The anchor-based approach preserves KV cache accumulation while fixing the correctness bug.

## Diagnostics / Debugging Tools

### File dump utilities

The fix includes helper functions that can be enabled for debugging:

- `write_to_file(path, contents)` — writes full text to app's cache directory
- `dump_str(tag, label, s)` — chunked logcat output (1800 bytes per line)
- `set_log_dump_dir(path)` — JNI-callable; sets output directory

### Per-turn past/new file dump

Commented out in `chat_format_local.cpp` (line ~200-206):

```cpp
// write_to_file(dir + "/past_" + idbuf + ".txt", fmt_past_msg);
// write_to_file(dir + "/new_"  + idbuf + ".txt", fmt_new_msg);
```

Uncomment to dump full prompt text per render to `context.cacheDir/llama_fmt/`.

### Template source dump

Commented out in `chat_format_local.cpp` (line ~174-188):

```cpp
// write_to_file(dir + "/template_src.txt", tmpl_src);
```

Uncomment to dump the jinja template source once per process.

## Lessons Learned

1. **`common_chat_format_single`'s prefix assumption is fragile**: Jinja template output can diverge when `add_generation_prompt` toggles, even for the same input messages. The template's `multi_step_tool` logic and thinking tag handling are common sources of this divergence.

2. **KV cache vs prompt**: The diff algorithm is about which portion of the prompt string to feed to `llama_decode`. KV cache is a separate concern — it accumulates from `n_past` onward regardless of where the prompt substring starts.

3. **Tail-anchor is more robust than prefix-match**: Using `rfind` on the tail avoids the "prefix drift" problem entirely. The tail (wrapper closing) is stable because it's the last thing the template outputs for a given message.

4. **`enable_thinking` in Qwen3 templates**: The template's `add_generation_prompt` block handles thinking tags in an inverted manner:
   - `enable_thinking=false` → outputs `thinking\n\n response\n\n` (both tags, model goes straight to response)
   - `enable_thinking=true` → outputs `thinking\n` (only start tag, model generates thinking content)
