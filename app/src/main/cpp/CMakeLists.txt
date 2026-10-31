cmake_minimum_required(VERSION 3.22.1)
project(jarvis_offline LANGUAGES C CXX)
include(FetchContent)

# Pinned, verified upstream llama.cpp release tag; no runtime downloads.
# Source license: MIT (ggml-org/llama.cpp/LICENSE at b6000).
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b6000
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama_cpp)

# A tag is not immutable. Refuse to compile an unexpected upstream revision.
# Exact GitHub commit behind upstream release tag b6000 (verified 2026-10-31).
set(JARVIS_LLAMA_EXPECTED_SHA "4762ad7316dcdec20016ab5985fb46a27902204d")
execute_process(
    COMMAND git -C "${llama_cpp_SOURCE_DIR}" rev-parse HEAD
    OUTPUT_VARIABLE JARVIS_LLAMA_ACTUAL_SHA
    OUTPUT_STRIP_TRAILING_WHITESPACE
    RESULT_VARIABLE JARVIS_LLAMA_GIT_RESULT
    ERROR_QUIET)
if(NOT JARVIS_LLAMA_GIT_RESULT EQUAL 0 OR
   NOT JARVIS_LLAMA_ACTUAL_SHA STREQUAL JARVIS_LLAMA_EXPECTED_SHA)
    message(FATAL_ERROR
        "llama.cpp revision mismatch: expected ${JARVIS_LLAMA_EXPECTED_SHA}, "
        "actual ${JARVIS_LLAMA_ACTUAL_SHA}. Build aborted.")
endif()

add_library(jarvis_offline SHARED jarvis_offline.cpp)
target_compile_features(jarvis_offline PRIVATE cxx_std_17)
target_link_libraries(jarvis_offline PRIVATE llama android log)
