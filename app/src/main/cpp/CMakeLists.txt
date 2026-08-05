cmake_minimum_required(VERSION 3.22.1)
project("screenomics-vlm" C CXX)

# The VLM stack (llama.cpp + mtmd + JNI bridge) is only built for the
# mindpulseDev flavor, which passes -DVLM_ENABLED=ON. The standard flavor
# builds only the vlm_noop placeholder so it never needs the llama.cpp
# checkout or the heavy native toolchain.
option(VLM_ENABLED "Build the on-device VLM native stack" OFF)

if(NOT VLM_ENABLED)
    add_library(vlm_noop SHARED noop.cpp)
    return()
endif()

if(NOT EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt)
    message(FATAL_ERROR "VLM_ENABLED=ON but the llama.cpp submodule is missing at "
        "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp — run "
        "'git submodule update --init' before building the mindpulseDev flavor.")
endif()

# Disable everything we don't need from llama.cpp
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_METAL           OFF CACHE BOOL "" FORCE)
set(GGML_CUDA            OFF CACHE BOOL "" FORCE)
# To enable Mali GPU (Vulkan) offload of the ViT: flip this to ON, set use_gpu=true
# (already toggleable via nativeSetUseGpu), AND provide a HOST C++ compiler so the
# vulkan-shaders-gen tool can be built (the NDK clang defaults to x86_64-w64-windows-gnu
# but the machine has no MinGW/MSVC sysroot, so linking a host .exe fails). glslc itself
# ships in the NDK at ${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64/glslc.exe.
set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(LLAMA_TOOLS_INSTALL  OFF CACHE BOOL "" FORCE)

# ARM NEON + dotprod optimizations (critical for performance).
# i8mm removed: it is an ARMv8.6 extension and SIGILLs on armv8.2 cores
# (Cortex-A55/A75 era); this forced -march bypasses ggml's runtime dispatch.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

# Set version variable that mtmd expects
if (NOT DEFINED LLAMA_INSTALL_VERSION)
    set(LLAMA_INSTALL_VERSION "0.0.0")
endif()

# Build core llama.cpp (ggml + llama libraries)
add_subdirectory(llama.cpp)

# Build mtmd (multimodal) library manually
set(MTMD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/tools/mtmd)
add_library(mtmd STATIC
    ${MTMD_DIR}/mtmd.cpp
    ${MTMD_DIR}/mtmd-audio.cpp
    ${MTMD_DIR}/mtmd-image.cpp
    ${MTMD_DIR}/mtmd-helper.cpp
    ${MTMD_DIR}/clip.cpp
    ${MTMD_DIR}/models/cogvlm.cpp
    ${MTMD_DIR}/models/conformer.cpp
    ${MTMD_DIR}/models/dotsocr.cpp
    ${MTMD_DIR}/models/gemma4v.cpp
    ${MTMD_DIR}/models/glm4v.cpp
    ${MTMD_DIR}/models/hunyuanocr.cpp
    ${MTMD_DIR}/models/internvl.cpp
    ${MTMD_DIR}/models/kimivl.cpp
    ${MTMD_DIR}/models/kimik25.cpp
    ${MTMD_DIR}/models/nemotron-v2-vl.cpp
    ${MTMD_DIR}/models/llama4.cpp
    ${MTMD_DIR}/models/llava.cpp
    ${MTMD_DIR}/models/minicpmv.cpp
    ${MTMD_DIR}/models/paddleocr.cpp
    ${MTMD_DIR}/models/pixtral.cpp
    ${MTMD_DIR}/models/qwen2vl.cpp
    ${MTMD_DIR}/models/qwen3vl.cpp
    ${MTMD_DIR}/models/step3vl.cpp
    ${MTMD_DIR}/models/siglip.cpp
    ${MTMD_DIR}/models/whisper-enc.cpp
    ${MTMD_DIR}/models/deepseekocr.cpp
    ${MTMD_DIR}/models/mobilenetv5.cpp
    ${MTMD_DIR}/models/youtuvl.cpp
)

target_link_libraries(mtmd PUBLIC ggml llama)
target_include_directories(mtmd PUBLIC ${MTMD_DIR})
target_include_directories(mtmd PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/vendor
)
target_compile_features(mtmd PRIVATE cxx_std_17)

find_package(Threads REQUIRED)
target_link_libraries(mtmd PRIVATE Threads::Threads)

# JNI bridge library
add_library(vlm_bridge SHARED vlm_bridge.cpp)

target_link_libraries(vlm_bridge
    PRIVATE
    llama
    ggml
    mtmd
    android
    log
)

target_include_directories(vlm_bridge PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
    ${MTMD_DIR}
)

target_compile_features(vlm_bridge PRIVATE cxx_std_17)
