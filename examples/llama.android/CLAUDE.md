# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **parent-project context**: This directory is `examples/llama.android` of the [llama.cpp](https://github.com/ggml-org/llama.cpp) monorepo. The repository root `CLAUDE.md` directs you to read `AGENTS.md` first — it carries project-wide contributor policy that overrides anything in this file. Key carry-over rules: AI tooling is permitted only as an assistive aid (no fully-generated PRs), use ASCII-only characters in code (`-`, `->`, `x`, `...` — not `—`, `→`, `×`, `…`), keep comments concise, do not commit/push or open PRs without explicit per-action human approval.

## Project shape

A two-module Android app that runs an LLM locally by linking llama.cpp as a shared library through JNI:

- **`:app`** (`app/`, namespace `com.example.llama`, applicationId `com.example.llama.aichat`) — the Kotlin sample: a single `MainActivity` chat UI. Opens a system document picker, asks the user to import a `.gguf` file, parses its metadata via the library, copies the file into `filesDir/models/`, loads it through `InferenceEngine`, then drives a streaming conversation.
- **`:lib`** (`lib/`, namespace `com.arm.aichat`) — an Android library module exposing the public `AiChat` / `InferenceEngine` API plus an embedded CMake build of llama.cpp.

The native build is the source of truth for everything runtime-critical — there is no `lib` consumer other than `:app`, and ProGuard keeps `com.arm.aichat.*` symbols intact (`app/proguard-rules.pro`).

## Build / verification

Prereqs: Android SDK + NDK `29.0.14206865`, CMake `3.31.6`, Java 21 (`local.properties` points `sdk.dir` at the host SDK). Gradle wrapper is checked in (`gradlew`); use it for everything.

```sh
# top-level build (compile + assemble both modules for all CI ABIs)
./gradlew build

# build the installable APK pair
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease    # both buildTypes enable minify+shrink on app module

# native build only (forces CMake re-config; useful when iterating in lib/src/main/cpp/)
./gradlew :lib:externalNativeBuildDebug -x lintAnalyzeDebug
# or fully clean the native intermediary state
rm -rf lib/.cxx lib/build && ./gradlew :lib:build

# unit/instrumented tests are stock templates — nothing custom wired up
./gradlew test                        # JVM unit tests (none yet — placeholders only)
./gradlew connectedAndroidTest        # needs a device/emulator
./gradlew :lib:lintDebug :app:lintDebug
```

Native build output is verbose (`android.native.buildOutput=verbose` in `gradle.properties`); inspect `lib/.cxx/` for per-ABI build logs and `lib/build/intermediates/cmake/` for object outputs.

## Code architecture

### Module `:lib` — the public API + JNI surface (`com.arm.aichat`)

- **`AiChat.kt`** — singleton facade. Only entry point from the outside: `AiChat.getInferenceEngine(context)`.
- **`InferenceEngine.kt`** — interface + `State` sealed class. State machine: `Uninitialized → Initializing → Initialized → LoadingModel → ModelReady` (branches into `ProcessingSystemPrompt` / `ProcessingUserPrompt` / `Generating` / `Benchmarking`, all returning to `ModelReady`). Two extension properties on `State` (`isUninterruptible`, `isModelLoaded`) gate which state transitions are valid. Companion holds `DEFAULT_PREDICT_LENGTH = 1024`.
- **`internal/InferenceEngineImpl.kt`** — singleton JNI wrapper. All native calls are `@FastNative` and serialized through a single-threaded `Dispatchers.IO.limitedParallelism(1)` scope (`llamaDispatcher`). Lifecycle: singleton on `getInstance(context)`, which reads `applicationInfo.nativeLibraryDir` and `System.loadLibrary("ai-chat")`. Owns the `_state: MutableStateFlow` and exposes it as `state: StateFlow`. `sendUserPrompt` returns a `Flow<String>` produced by repeatedly calling `generateNextToken()` and terminating on `null` (EOG) or `_cancelGeneration`.
- **`internal/ai_chat.cpp`** — JNI counterpart. C functions are `Java_com_arm_aichat_internal_InferenceEngineImpl_<camelCase>`. It owns static globals (`g_model`, `g_context`, `g_batch`, `g_chat_templates`, `g_sampler`) and a long-term `chat_msgs` list. `processSystemPrompt` and `processUserPrompt` tokenize and decode in `BATCH_SIZE` chunks; `generateNextToken` samples one token at a time and returns it as a validated UTF-8 `jstring` (incomplete UTF-8 fragments stay in `cached_token_chars`). On context overflow it triggers `shift_context()`, which discards half the tokens past the system prompt and re-positions the KV cache.
- **`internal/logging.h`** — `LOGv/d/i/w/e` macros wiring `android_log_print` to llm-style log priorities, plus `aichat_android_log_callback` that is plugged into llama.cpp via `llama_log_set`.
- **`gguf/GgufMetadataReader.kt`** + **`internal/gguf/GgufMetadataReaderImpl.kt`** — pure-Kotlin streaming parser for GGUF v1/v2/v3 header + key/value section, skipping tensor arrays (configurable `skipKeys` and `arraySummariseThreshold`). Defends against Android's quirky `InputStream.skip` behavior via a hand-rolled `skipFully` that falls back to `read()`+discard.
- **`gguf/GgufMetadata.kt`** — strongly-typed data tree the parser populates (`BasicInfo`, `AuthorInfo`, `ArchitectureInfo`, `TokenizerInfo`, `DimensionsInfo`, `AttentionInfo`, `RopeInfo`, `ExpertsInfo`, ...).
- **`gguf/FileType.kt`** — `general.file_type` enum with the standard llama.cpp quantization codes.

### Native build (CMake)

- `lib/src/main/cpp/CMakeLists.txt` pulls in the parent `llama.cpp` source via `add_subdirectory(../../../../../../ build-llama)` (six `../` reaches the repo root). `BUILD_SHARED_LIBS=ON`, `LLAMA_BUILD_APP=OFF`, `LLAMA_BUILD_COMMON=ON`, `GGML_BACKEND_DL=ON` are set in `lib/build.gradle.kts`'s `externalNativeBuild` block.
- ABI is selected at CMake time via `ANDROID_ABI`: `arm64-v8a` enables KleidiAI + OpenMP; `x86_64` disables both. `i686`, `armeabi-v7a`, etc. fail with `FATAL_ERROR "Unsupported ABI"`. ABI filters are `arm64-v8a` and `x86_64` only (`lib/build.gradle.kts`).

### Module `:app` — sample chat UI (`com.example.llama`)

- **`MainActivity.kt`** wires up `gguf` metadata `TextView`, a `RecyclerView` of `Message`s (`MessageAdapter`), and a `FloatingActionButton` that toggles between file-picker mode and send-prompt mode. Picks a file via `ActivityResultContracts.OpenDocument`, then in IO scope: parses GGUF → derives `modelName` from metadata (`filename()` ext fn at file bottom) → copies the picker Uri into `filesDir/models/{name}.gguf` → calls `engine.loadModel(...)`. After model load, sends user text to `engine.sendUserPrompt(...)`, replacing the trailing assistant message in-place as tokens stream out. `runBenchmark` exists but is `@Deprecated` and not invoked; bench params live in the `companion object`.
- Lifecycle: `generationJob.cancel()` in `onStop`, `engine.destroy()` in `onDestroy`.
- Constraints in `app/build.gradle.kts`: `isMinifyEnabled=true` and `isShrinkResources=true` for **both debug and release** — do not assume a non-shrunk debug build. `compileOptions` = `VERSION_21`.

## Conventions to follow

- New public Kotlin APIs live in `com.arm.aichat` or `com.arm.aichat.gguf`; everything else (JNI glue, GGUF parser impl) goes under `com.arm.aichat.internal`.
- Native functions on `InferenceEngineImpl` mirror `Java_com_arm_aichat_internal_InferenceEngineImpl_<name>` in `ai_chat.cpp`. Return `0` for success, nonzero error codes (the Kotlin side currently special-cases `1` from `load` → `UnsupportedArchitectureException`).
- All native calls must go through `llamaDispatcher` — the engine has exactly one thread. Don't bypass it with `Dispatchers.Default` inside `InferenceEngineImpl` overrides.
- ABI-specific CPU features belong in the `arm64-v8a` / `x86_64` branches of `lib/src/main/cpp/CMakeLists.txt`; don't add new ABIs without updating both `abiFilters` and the switch.
- Logging from native: `LOGv/d/i/w/e` (defined in `logging.h`, all guarded by `LOG_MIN_LEVEL`). From Kotlin: `android.util.Log` with tag `MainActivity::class.java.simpleName` / `InferenceEngineImpl::class.java.simpleName`.
- ProGuard keep rules already cover `com.arm.aichat.*` and `com.arm.aichat.gguf.*`; new public classes in those packages don't need extra `-keep`.

## Common pitfalls

- `local.properties` is host-specific and shouldn't be checked in; on a fresh checkout it must point at the host Android SDK (`sdk.dir=...`).
- Context overflow is handled by `shift_context()` halving the token stream after the system prompt — there is no sliding-window implementation yet (note in source: `TODO-hyin: implement sliding-window version`). Long conversations will lose the middle of history.
- `InferenceEngineImpl` is process-wide singleton. Re-instantiation per Activity is a foot-gun; rely on `AiChat.getInferenceEngine(...)` only.
- `app/build.gradle.kts` enables R8 + resource shrinking on `debug`; if a stack trace lacks line numbers, suspect ProGuard stripping rather than a real bug.
- The native library is loaded via `System.loadLibrary("ai-chat")` — the CMake `project(...)` name `"ai-chat"` and the library suffix `.so` give `libai-chat.so`. Renaming the project without updating `System.loadLibrary` will crash on startup.
</content>
</invoke>