cmake_minimum_required(VERSION 3.31.6)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)


set(ENV{http_proxy} "http://127.0.0.1:7890")
set(ENV{https_proxy} "http://127.0.0.1:7890")

# Make sure the ninja used by the main build is on PATH for ExternalProject
# sub-builds (e.g. ggml-vulkan's vulkan-shaders-gen). GUI-launched gradle
# daemons (Android Studio) don't inherit ~/.zshrc, so this in-CMake fix is
# the only reliable way to propagate it to child cmake processes.
#if(CMAKE_MAKE_PROGRAM)
#    get_filename_component(_ninja_dir "${CMAKE_MAKE_PROGRAM}" DIRECTORY)
#    if(EXISTS "${_ninja_dir}/ninja")
#        set(ENV{PATH} "${_ninja_dir}:$ENV{PATH}")
#    endif()
#endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

# --------------------------------------------------------------------------
# Optional Vulkan backend — enabled automatically when the host has glslc
# (LunarG Vulkan SDK).  When not available, the build falls back to CPU-only.
# Searches $VULKAN_SDK (and the macOS-style <SDK>/macOS layout) plus PATH.
#
# Override with -DGGML_VULKAN=ON / OFF to force a particular choice. Useful
# when the SDK is installed but the target device's Vulkan HAL is broken or
# the loader advertises `vulkan_renderengine=false` on Android.
# --------------------------------------------------------------------------
#if(NOT DEFINED GGML_VULKAN)
#    if(DEFINED ENV{ANDROID_DISABLE_VULKAN})
#        # Trip-wire: set this env var on the gradle invocation to force CPU-only
#        # builds even when the LunarG SDK is present (handy while debugging).
#        message(STATUS "ANDROID_DISABLE_VULKAN set -- Vulkan backend disabled by env override")
#        set(GGML_VULKAN OFF CACHE BOOL "ggml: use Vulkan" FORCE)
#    else()
#    message(STATUS "=== Vulkan probe: VULKAN_SDK=\$ENV{VULKAN_SDK} ===")
#    # LunarG macOS SDK ships glslc and SPIRV-Headers under $VULKAN_SDK/macOS,
#    # but the exported env var sometimes points at the outer version dir, so
#    # probe a few candidate roots rather than relying on a single layout.
#    set(_vk_sdk_roods "$ENV{VULKAN_SDK}" "$ENV{VULKAN_SDK}/macOS")
#    find_program(GLSLC_EXECUTABLE
#        NAMES glslc
#        PATHS ${_vk_sdk_roods} PATH_SUFFIXES bin NO_DEFAULT_PATH)
#    find_program(GLSLC_EXECUTABLE glslc)  # fall back to system PATH
#    if(GLSLC_EXECUTABLE)
#        # Derive SDK root from glslc location (e.g. /path/macOS/bin/glslc -> /path/macOS)
#        get_filename_component(_vk_bin_dir "${GLSLC_EXECUTABLE}" DIRECTORY)
#        get_filename_component(_vk_root   "${_vk_bin_dir}/.." ABSOLUTE)
#        message(STATUS "=== Vulkan probe: glslc found at ${GLSLC_EXECUTABLE} -> _vk_root=${_vk_root} ===")
#        # Add SDK root to search prefixes so find_package(Vulkan) picks up
#        # the LunarG-installed Vulkan headers.
#        list(PREPEND CMAKE_PREFIX_PATH "${_vk_root}")
#        # Probe SPIRV-Headers — try the cmake-package first, then brute-force
#        # file existence checks under the SDK root (covers LunarG SDK layouts
#        # that ship SPIRV-HeadersConfig.cmake at non-standard depths).
#        find_package(SPIRV-Headers QUIET)
#        if(NOT SPIRV-Headers_FOUND)
#            set(_spirv_cfg_candidates
#                "${_vk_root}/lib/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake"
#                "${_vk_root}/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake"
#                "${_vk_root}/SPIRV-HeadersConfig.cmake"
#                "$ENV{VULKAN_SDK}/lib/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake"
#                "$ENV{VULKAN_SDK}/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake"
#                "$ENV{VULKAN_SDK}/macOS/lib/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake"
#                "$ENV{VULKAN_SDK}/macOS/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake"
#            )
#            foreach(_spirv_cfg IN LISTS _spirv_cfg_candidates)
#                if(EXISTS "${_spirv_cfg}")
#                    message(STATUS "=== Vulkan probe: SPIRV-Headers found at ${_spirv_cfg} ===")
#                    set(SPIRV_HEADERS_FOUND "${_spirv_cfg}")
#                    # Extract the directory containing SPIRV-HeadersConfig.cmake and
#                    # push its parent (the SPIRV-Headers install root) onto CMAKE_PREFIX_PATH
#                    # so find_package(SPIRV-Headers) works in downstream cmake sub-builds.
#                    get_filename_component(_spirv_dir    "${_spirv_cfg}" DIRECTORY)
#                    get_filename_component(_spirv_prefix "${_spirv_dir}" DIRECTORY)
#                    list(PREPEND CMAKE_PREFIX_PATH "${_spirv_prefix}")
#                    # Set BOTH cache (FORCE to override stale entries) and a normal
#                    # variable so the hint survives into add_subdirectory() children.
#                    set(SPIRV-Headers_DIR "${_spirv_dir}" CACHE PATH "Path to SPIRV-Headers cmake config" FORCE)
#                    set(SPIRV-Headers_DIR "${_spirv_dir}")
#                    set(SPIRV-Headers_ROOT "${_spirv_prefix}" CACHE PATH "Root of SPIRV-Headers installation" FORCE)
#                    set(SPIRV-Headers_ROOT "${_spirv_prefix}")
#                    message(STATUS "=== Vulkan probe: set SPIRV-Headers_DIR=${_spirv_dir}, SPIRV-Headers_ROOT=${_spirv_prefix}, CMAKE_PREFIX_PATH=${CMAKE_PREFIX_PATH} ===")
#                    break()
#                endif()
#            endforeach()
#            if(NOT SPIRV_HEADERS_FOUND)
#                message(STATUS "=== Vulkan probe: none of the 7 candidate paths exist. Listing contents of _vk_root/lib/cmake (if any): ===")
#                file(GLOB _vk_cmake_dirs LIST_DIRECTORIES true "${_vk_root}/lib/cmake/*")
#                foreach(_d IN LISTS _vk_cmake_dirs)
#                    message(STATUS "===   _vk_root/lib/cmake entry: ${_d} ===")
#                endforeach()
#                message(STATUS "===   Listing \$ENV{VULKAN_SDK}/lib/cmake (if any): ===")
#                file(GLOB _env_cmake_dirs LIST_DIRECTORIES true "$ENV{VULKAN_SDK}/lib/cmake/*")
#                foreach(_d IN LISTS _env_cmake_dirs)
#                    message(STATUS "===   \$ENV{VULKAN_SDK}/lib/cmake entry: ${_d} ===")
#                endforeach()
#            endif()
#        elseif(SPIRV-Headers_FOUND)
#            set(SPIRV_HEADERS_FOUND TRUE)
#        endif()
#    endif()
#    if(GLSLC_EXECUTABLE AND SPIRV_HEADERS_FOUND)
#        set(GGML_VULKAN ON CACHE BOOL "ggml: use Vulkan" FORCE)
#        # The Android NDK only ships the C Vulkan header, not the C++ binding
#        # ggml-vulkan relies on (vulkan.hpp + helpers). Add the LunarG SDK's
#        # include/ globally so ggml-vulkan.cpp resolves #include <vulkan/vulkan.hpp>.
#        # Also force CMake's find_package(Vulkan) to use NDK's libvulkan.so at link time.
#        list(APPEND CMAKE_FIND_ROOT_PATH "${_vk_root}")
#        set(Vulkan_INCLUDE_DIR "${_vk_root}/include" CACHE PATH "Vulkan headers (LunarG SDK, provides vulkan.hpp)")
#        message(STATUS "Vulkan SDK found (glslc=${GLSLC_EXECUTABLE}) -- Vulkan GPU backend enabled, headers at ${Vulkan_INCLUDE_DIR}")
#    else()
#        set(GGML_VULKAN OFF CACHE BOOL "ggml: use Vulkan" FORCE)
#        message(WARNING "Vulkan SDK not found (glslc=${GLSLC_EXECUTABLE}, SPIRV=${SPIRV_HEADERS_FOUND}) - Vulkan GPU backend disabled. Set VULKAN_SDK to your LunarG SDK root (the dir containing bin/glslc) to enable GPU acceleration.")
#    endif()
#    endif()  # ANDROID_DISABLE_VULKAN else-branch
#endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../../)

# --------------------------------------------------------------------------
# Optional OpenCL backend (Qualcomm Adreno). The Android NDK ships no OpenCL
# headers or loader, so we vendor a prebuilt Khronos ICD loader + headers in
# opencl-sdk/. At runtime the loader dlopens the device's vendor libOpenCL.so.
# Force-enabled when the SDK is present for arm64-v8a (a prior CMakeCache with
# GGML_OPENCL=OFF would otherwise poison a `NOT DEFINED` guard, so we FORCE).
# Set ANDROID_DISABLE_OPENCL on the gradle invocation to opt out.
# --------------------------------------------------------------------------
set(OPENCL_SDK_ROOT "${CMAKE_CURRENT_LIST_DIR}/opencl-sdk")
if(DEFINED ENV{ANDROID_DISABLE_OPENCL})
    set(GGML_OPENCL OFF CACHE BOOL "ggml: use OpenCL" FORCE)
    message(STATUS "ANDROID_DISABLE_OPENCL set -- OpenCL backend disabled by env override")
elseif(ANDROID_ABI STREQUAL "arm64-v8a"
        AND EXISTS "${OPENCL_SDK_ROOT}/include/CL/cl.h"
        AND EXISTS "${OPENCL_SDK_ROOT}/lib/libOpenCL.so")
    set(GGML_OPENCL ON CACHE BOOL "ggml: use OpenCL" FORCE)
    message(STATUS "OpenCL SDK found -- enabling OpenCL backend (Adreno)")
endif()

if(GGML_OPENCL)
    if(NOT EXISTS "${OPENCL_SDK_ROOT}/include/CL/cl.h")
        message(FATAL_ERROR "GGML_OPENCL=ON but opencl-sdk headers missing at ${OPENCL_SDK_ROOT}/include/CL")
    endif()
    # Feed find_package(OpenCL) in the ggml sub-build: cache the discovered
    # paths and push the SDK onto the prefix path so find_library's `lib`
    # suffix resolves libOpenCL.so.
    set(OpenCL_INCLUDE_DIR "${OPENCL_SDK_ROOT}/include" CACHE PATH "OpenCL headers" FORCE)
    set(OpenCL_LIBRARY     "${OPENCL_SDK_ROOT}/lib/libOpenCL.so" CACHE FILEPATH "OpenCL ICD loader" FORCE)
    list(PREPEND CMAKE_PREFIX_PATH "${OPENCL_SDK_ROOT}")
    find_package(Python3 REQUIRED)  # kernel embedding (ggml-opencl/kernels/embed_kernel.py)
    message(STATUS "OpenCL: include=${OpenCL_INCLUDE_DIR} lib=${OpenCL_LIBRARY}")
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp
        chat_format_local.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
