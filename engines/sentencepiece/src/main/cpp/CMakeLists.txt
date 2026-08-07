cmake_minimum_required(VERSION 3.22.1)
project("sentencepiece_android")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── 关闭不需要的功能，减小体积 ──────────────────────────────
set(SPM_ENABLE_SHARED ON CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_NFKC_COMPILE OFF CACHE BOOL "" FORCE)
set(SPM_DISABLE_EMBEDDED_DATA ON CACHE BOOL "" FORCE)
set(SPM_NLCODEC_BPE OFF CACHE BOOL "" FORCE)

# 使用内置 protobuf-lite（避免外部依赖）
set(SPM_PROTOBUF_PROVIDER "internal" CACHE STRING "" FORCE)
set(SPM_ABSL_PROVIDER "module" CACHE STRING "" FORCE)

# ── 添加 sentencepiece 源码 ──────────────────────────────
set(SENTENCEPIECE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")

file(STRINGS "${SENTENCEPIECE_ROOT}/VERSION.txt" SPM_VERSION)
message(STATUS "SentencePiece VERSION: ${SPM_VERSION}")

include_directories(${SENTENCEPIECE_ROOT})
include_directories(${SENTENCEPIECE_ROOT}/third_party/abseil-cpp)
include_directories(${CMAKE_CURRENT_BINARY_DIR})

# 生成 config.h
configure_file("${SENTENCEPIECE_ROOT}/config.h.in" "${CMAKE_CURRENT_BINARY_DIR}/config.h")

# ── Abseil（使用本地已下载的源码）──────────────────────────
add_subdirectory(${SENTENCEPIECE_ROOT}/third_party/abseil-cpp ${CMAKE_CURRENT_BINARY_DIR}/abseil-cpp)

if (NOT EXISTS ${SENTENCEPIECE_ROOT}/third_party/absl)
  file(CREATE_LINK
       ${SENTENCEPIECE_ROOT}/third_party/abseil-cpp/absl
       ${SENTENCEPIECE_ROOT}/third_party/absl
       SYMBOLIC)
endif()

# ── Protobuf-lite（内置）──────────────────────────────────
set(PROTOBUF_LITE_SRCS
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/arena.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/arenastring.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/bytestream.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/coded_stream.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/common.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/extension_set.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/generated_enum_util.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/generated_message_table_driven_lite.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/generated_message_util.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/implicit_weak_message.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/int128.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/io_win32.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/message_lite.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/parse_context.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/repeated_field.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/status.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/statusor.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/stringpiece.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/stringprintf.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/structurally_valid.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/strutil.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/time.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/wire_format_lite.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/zero_copy_stream.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/zero_copy_stream_impl.cc
  ${SENTENCEPIECE_ROOT}/third_party/protobuf-lite/zero_copy_stream_impl_lite.cc
)

add_definitions("-pthread -DHAVE_PTHREAD=1 -Wno-sign-compare -Wno-deprecated-declarations")
include_directories(${SENTENCEPIECE_ROOT}/third_party/protobuf-lite)
include_directories(${SENTENCEPIECE_ROOT}/src/builtin_pb)

# ── SentencePiece 核心源文件 ──────────────────────────────
set(SPM_SRCS
  ${PROTOBUF_LITE_SRCS}
  ${SENTENCEPIECE_ROOT}/src/builtin_pb/sentencepiece.pb.h
  ${SENTENCEPIECE_ROOT}/src/builtin_pb/sentencepiece.pb.cc
  ${SENTENCEPIECE_ROOT}/src/builtin_pb/sentencepiece_model.pb.h
  ${SENTENCEPIECE_ROOT}/src/builtin_pb/sentencepiece_model.pb.cc
  ${SENTENCEPIECE_ROOT}/src/bpe_model.cc
  ${SENTENCEPIECE_ROOT}/src/char_model.cc
  ${SENTENCEPIECE_ROOT}/src/filesystem.cc
  ${SENTENCEPIECE_ROOT}/src/init.cc
  ${SENTENCEPIECE_ROOT}/src/model_factory.cc
  ${SENTENCEPIECE_ROOT}/src/model_interface.cc
  ${SENTENCEPIECE_ROOT}/src/normalizer.cc
  ${SENTENCEPIECE_ROOT}/src/sentencepiece_processor.cc
  ${SENTENCEPIECE_ROOT}/src/unigram_model.cc
  ${SENTENCEPIECE_ROOT}/src/util.cc
  ${SENTENCEPIECE_ROOT}/src/word_model.cc
)

# Abseil 依赖库
list(APPEND SPM_LIBS
  absl::status
  absl::status_builder
  absl::statusor
  absl::strings
  absl::flags
  absl::flags_parse
  absl::log
  absl::log_initialize
  absl::check
  absl::random_random
  absl::time
  absl::base
  absl::spinlock_wait
  absl::throw_delegate
  absl::raw_logging_internal
  absl::malloc_internal
  absl::cord
  absl::cord_internal
  absl::cordz_info
  absl::cordz_handle
  absl::cordz_sample_token
  absl::crc32c
  absl::crc_internal
  absl::crc_cpu_detect
  absl::hash
  absl::city
  absl::synchronization
  absl::graphcycles_internal
  absl::kernel_timeout_internal
  absl::stacktrace
  absl::symbolize
  absl::examine_stack
  absl::demangle_internal
  absl::debugging_internal
  absl::scoped_set_env
)

# Android 不需要显式 find_package(Threads)，使用内置 pthread
# 从 SPM_LIBS 中移除 Threads::Threads
list(REMOVE_ITEM SPM_LIBS Threads::Threads)

# 查找 Android 基础库
find_library(log-lib log)
find_library(android-lib android)

# ── 构建静态库（核心，供 JNI 链接）──────────────────────────
add_library(sentencepiece-static STATIC ${SPM_SRCS})

target_link_libraries(sentencepiece-static PUBLIC ${SPM_LIBS})

target_compile_options(sentencepiece-static PRIVATE
  -O3
  -fPIC
  -ffunction-sections
  -fdata-sections
  -fvisibility=hidden
)

# ── JNI 桥接层 ────────────────────────────────────────────
set(JNI_SOURCES
  ${SENTENCEPIECE_ROOT}/sentencepiece_jni.cpp
)

add_library(sentencepiece_android SHARED ${JNI_SOURCES})

target_link_libraries(sentencepiece_android
  sentencepiece-static
  ${SPM_LIBS}
  ${log-lib}
  ${android-lib}
)

target_compile_options(sentencepiece_android PRIVATE
  -O3
  -fPIC
  -ffunction-sections
  -fdata-sections
)

set_target_properties(sentencepiece_android PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON
)

# Strip 减小体积
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -s")
