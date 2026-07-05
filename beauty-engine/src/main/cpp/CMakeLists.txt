cmake_minimum_required(VERSION 3.10.2)
project("beauty_native")

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── MNN 配置 ────────────────────────────────────────────
set(MNN_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/mnn")

# 尝试从下载的 macOS 包复制头文件
set(MNN_HEADER_SOURCE "/tmp/mnn_macos/mnn_3.5.0_macos_x64_arm82_cpu_opencl_metal/Dynamic/MNN.framework/Versions/A/Headers")
if(EXISTS ${MNN_HEADER_SOURCE}/MNNDefine.h AND NOT EXISTS ${MNN_ROOT_DIR}/include/MNN/MNNDefine.h)
    file(MAKE_DIRECTORY ${MNN_ROOT_DIR}/include/MNN)
    file(COPY ${MNN_HEADER_SOURCE}/ DESTINATION ${MNN_ROOT_DIR}/include/MNN)
    message(STATUS "Copied MNN headers to ${MNN_ROOT_DIR}/include/MNN")
endif()

if(EXISTS "${MNN_ROOT_DIR}/include/MNN/MNNDefine.h")
    include_directories(${MNN_ROOT_DIR}/include)
    message(STATUS "MNN headers found at ${MNN_ROOT_DIR}/include/MNN")
else()
    message(FATAL_ERROR "MNN headers not found. Please ensure MNN is set up.")
endif()

# MNN 库路径（优先从 runtime-core 的 jniLibs 查找，回退到自身 jniLibs）
set(MNN_LIB_DIR_RUNTIME_CORE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../runtime-core/src/main/jniLibs/${ANDROID_ABI}")
set(MNN_LIB_DIR_SELF "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

if(EXISTS "${MNN_LIB_DIR_RUNTIME_CORE}/libMNN.so")
    set(MNN_LIB_DIR ${MNN_LIB_DIR_RUNTIME_CORE})
    message(STATUS "Using MNN from runtime-core: ${MNN_LIB_DIR}")
elseif(EXISTS "${MNN_LIB_DIR_SELF}/libMNN.so")
    set(MNN_LIB_DIR ${MNN_LIB_DIR_SELF})
    message(STATUS "Using MNN from beauty-engine: ${MNN_LIB_DIR}")
else()
    message(FATAL_ERROR "libMNN.so not found in runtime-core or beauty-engine jniLibs")
endif()

set(MNN_SO_PATH "${MNN_LIB_DIR}/libMNN.so")
add_library(mnn SHARED IMPORTED)
set_target_properties(mnn PROPERTIES IMPORTED_LOCATION ${MNN_SO_PATH})

# [注意] libMNN_CL.so 提供 OpenCL GPU 后端
set(MNN_CL_SO_PATH "${MNN_LIB_DIR}/libMNN_CL.so")
if(EXISTS ${MNN_CL_SO_PATH})
    message(STATUS "libMNN_CL.so found - OpenCL GPU backend available")
endif()

# MNN-LLM 库
set(MNN_LLM_SO_PATH "${MNN_LIB_DIR}/libllm.so")
if(EXISTS ${MNN_LLM_SO_PATH})
    message(STATUS "Using MNN-LLM library: ${MNN_LLM_SO_PATH}")
    add_library(mnn_llm SHARED IMPORTED)
    set_target_properties(mnn_llm PROPERTIES IMPORTED_LOCATION ${MNN_LLM_SO_PATH})
else()
    message(STATUS "libllm.so not found. LLM symbols should be in libMNN.so (unified build)")
endif()

# MNN Express 库（已合并到 libMNN.so）
set(MNN_EXPRESS_SO_PATH "${MNN_LIB_DIR}/libMNN_Express.so")
if(EXISTS ${MNN_EXPRESS_SO_PATH})
    message(STATUS "libMNN_Express.so found but NOT linking - Express symbols are already in libMNN.so")
endif()

# ── 源文件 ──────────────────────────────────────────────
set(NATIVE_SOURCES
    mnn_jni_bridge.cpp
    mnn_face_detector.cpp
    mnn_face_embedder.cpp
)

# ── 创建共享库 ──────────────────────────────────────────
add_library(beauty_native SHARED ${NATIVE_SOURCES})

# 查找 Android 基础库
find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

# 编译定义
target_compile_definitions(beauty_native PRIVATE
    MNN_USE_THREAD_POOL=1
)

# 链接库
set(LINK_LIBRARIES
    mnn
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
    vulkan
)

if(TARGET mnn_llm)
    list(APPEND LINK_LIBRARIES mnn_llm)
endif()

target_link_libraries(beauty_native ${LINK_LIBRARIES})

# 编译选项
target_compile_options(beauty_native PRIVATE
    -O3
    -fexceptions
    -fPIC
    -ffunction-sections
    -fdata-sections
)

set_target_properties(beauty_native PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
