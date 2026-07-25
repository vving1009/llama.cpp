# OpenCL Backend (Adreno GPU)

## Overview

This document records the integration of the llama.cpp OpenCL GPU backend into
the llama.android app, targeting Qualcomm Adreno GPUs (specifically Adreno 730
on Snapdragon 8 Gen 1+).  It covers the build setup, the runtime linker
namespace issue, and the known limitations.

## Build integration

### Dependencies

The Android NDK ships neither OpenCL headers nor a loader, so a vendored SDK
is committed at `lib/src/main/cpp/opencl-sdk/`:

- `include/CL/` — Khronos OpenCL-Headers (18 headers)
- `lib/libOpenCL.so` — Khronos OpenCL-ICD-Loader built for `arm64-v8a` with
  NDK 29 on macOS

The ICD loader is used only for **link time** — it is excluded from the APK
(see below).

### CMakeLists (`lib/src/main/cpp/CMakeLists.txt`)

In the `arm64-v8a` branch, a guarded block probes
`opencl-sdk/include/CL/cl.h` and sets `GGML_OPENCL=ON` with `FORCE` (to
override a stale `OFF` from a prior configure).  It also sets
`OpenCL_INCLUDE_DIR` and `OpenCL_LIBRARY` cache variables so
`find_package(OpenCL)` in the ggml sub-build resolves.

An env-var tripwire (`ANDROID_DISABLE_OPENCL`) forces the backend off.

### build.gradle.kts (`lib/`)

`GGML_BACKEND_DL=ON` is already set — the OpenCL backend builds as a separate
`libggml-opencl.so` and is loaded dynamically by `ggml_backend_load_all_from_path`.

### Packaging: `libOpenCL.so` must NOT ship

The ICD loader built into `opencl-sdk/lib/libOpenCL.so` is for link-time only.
If it ends up in the APK (via `intermediates/cxx/.../obj/arm64-v8a/`), it
shadows the device's vendor `libOpenCL.so` at runtime and yields:

```
ggml_opencl: platform IDs not available.
```

Both `lib/build.gradle.kts` and `app/build.gradle.kts` exclude it:

```kotlin
packaging {
    jniLibs {
        excludes += "**/libOpenCL.so"
    }
}
```

## Runtime: the linker namespace roadblock

### The problem

Even with `libOpenCL.so` listed in `/vendor/etc/public.libraries.txt`, an app
process on this Xiaomi/HyperOS device cannot `dlopen("libOpenCL.so")` by bare
name — the linker namespace `clns-8` has `permitted_paths = /data:...` and
does **not** include `/vendor`.  A full-path dlopen of
`/vendor/lib64/libOpenCL.so` is also denied:

```
library "/vendor/lib64/libOpenCL.so" ... is not accessible for the namespace
"clns-8" [permitted_paths="/data:/mnt/expand:/data/data/com.example.llama.aichat"]
```

### The fix: `<uses-native-library>` in the manifest

The app must declare the vendor OpenCL library in
`app/src/main/AndroidManifest.xml`:

```xml
<uses-native-library
    android:name="libOpenCL.so"
    android:required="false" />
```

This tells the nativeloader to add the library to the app's exposed libs,
making it resolvable by both bare-name `dlopen` and `DT_NEEDED`.

### Verification

Build → install → run → logcat:

```
DIAG dlopen(libOpenCL.so bare) = 0x... err=(ok)
ggml_opencl: selected platform: 'QUALCOMM Snapdragon(TM)'
ggml_opencl: device: 'QUALCOMM Adreno(TM) (OpenCL 3.0 Adreno(TM) 730)'
load_backend: loaded OpenCL backend from .../libggml-opencl.so
load_tensors: offloaded N/N layers to GPU
load_tensors: OpenCL model buffer size = ... MiB
active backends: OpenCL
```

## Flash attention

The OpenCL flash-attention kernels use `sub_group_shuffle_xor`, which the
Adreno 730 OpenCL C compiler does not support
(`vector subgroup broadcast support: false`).  The kernel fails to compile and
`ggml_cl_flash_attn` throws `std::out_of_range` looking up the missing kernel.

Disabled in `ai_chat.cpp`:

```cpp
ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
```

Attention takes the standard matmul path instead.

## Model compatibility

### Supported quantizations (from OPENCL.md + kernel list)

`Q4_0` `Q4_1` `Q5_0` `Q5_1` `Q8_0` `Q4_K` `Q5_K` `Q6_K` `MXFP4` `IQ4_NL`

### NOT supported

`IQ4_XS` `Q3_K*` `Q2_K*` `Q1_0` — no GPU kernels, fall back to CPU.

### Architecture

The OpenCL backend is validated for **standard Transformer** models (Llama,
Qwen2.5, Phi, Gemma).  **Hybrid SSM/Mamba architectures** such as
**Qwen3/Qwen3.5** (which uses `rope type=40`, `ssm_d_conv`,
`nextn_predict_layers`) are not well supported — `graph splits` in the
hundreds cause massive CPU↔GPU data transfer and garbage output.

### Expected graph splits

A well-supported model on OpenCL:

```
graph nodes  = 1405
graph splits = 1~2
```

An unsupported model (hybrid SSM + unsupported quant):

```
graph splits = 122
```

## Device details

| Device | Snapdragon 8 Gen 1+ (Xiaomi / HyperOS) |
|---|---|
| GPU | Adreno 730 |
| OpenCL platform | `QUALCOMM Snapdragon(TM)` |
| OpenCL device | `QUALCOMM Adreno(TM) (OpenCL 3.0 Adreno(TM) 730)` |
| OpenCL driver | E031.38.11.14 (01/23/25) |
| ICM loader | Vendor driver at `/vendor/lib64/libOpenCL.so` (163 KiB, stripped) |
| NDK | 29.0.14206865 |
| AGP | 8.13.2 |

## Key files changed

| File | Change |
|---|---|
| `lib/src/main/cpp/CMakeLists.txt` | OpenCL SDK auto-detect, `GGML_OPENCL=ON` |
| `lib/build.gradle.kts` | `packaging.jniLibs.excludes` for `libOpenCL.so` |
| `app/build.gradle.kts` | `packaging.jniLibs.excludes` for `libOpenCL.so` |
| `app/src/main/AndroidManifest.xml` | `<uses-native-library android:name="libOpenCL.so">` |
| `lib/src/main/cpp/ai_chat.cpp` | `flash_attn_type = DISABLED`; `dlfcn.h` include |
| `lib/src/main/java/.../InferenceEngine.kt` | `DEFAULT_N_GPU_LAYERS = 99`; overloaded `loadModel` |
| `lib/src/main/java/.../InferenceEngineImpl.kt` | Configurable `nGpuLayers` parameter |

## Troubleshooting quick-reference

| Symptom | Cause | Fix |
|---|---|---|
| `platform IDs not available` | `libOpenCL.so` shipped in APK shadows vendor driver | Exclude from `jniLibs` |
| `active backends: CPU` | Backend .so failed to load silently (Release build) | Check `dlopen` with `dlerror`, or build Debug |
| `dlopen(libOpenCL.so) not found` | Vendor lib not in app linker namespace | Add `<uses-native-library>` to manifest |
| `kernel compile error: sub_group_shuffle_xor` | Adreno 730 doesn't support FA subgroup ext | `flash_attn_type = DISABLED` |
| `graph splits = 122` / garbage output | Unsupported quant or arch | Use Q4_0/Q4_K on standard Transformer |
| `offloaded N/N layers to GPU` but all CPU buffers | Backend loaded but no device registered | Check `clGetPlatformIDs` / `selected platform` |

## Rebuilding the ICD loader

One-time host build (macOS, NDK 29):

```sh
NDK=~/Library/Android/sdk/ndk/29.0.14206865
TC=$NDK/build/cmake/android.toolchain.cmake

git clone https://github.com/KhronosGroup/OpenCL-Headers
git clone https://github.com/KhronosGroup/OpenCL-ICD-Loader

cd OpenCL-ICD-Loader && mkdir b && cd b
cmake .. -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=$TC -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared \
  -DOPENCL_ICD_LOADER_HEADERS_DIR=<abs-path>/OpenCL-Headers
ninja
```

Copy `libOpenCL.so` to `opencl-sdk/lib/`, headers to `opencl-sdk/include/`.