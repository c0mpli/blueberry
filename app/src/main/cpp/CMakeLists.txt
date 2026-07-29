cmake_minimum_required(VERSION 3.22.1)
project("blueberry_llama" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Only what the app needs. Building the tools, tests, server and examples roughly triples the
# build and none of it ships in the APK.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # KleidiAI gives a real matmul speedup on ARMv8.2+ cores, which is every phone this targets.
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../native/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(blueberry_llama SHARED blueberry_llama.cpp)

target_include_directories(blueberry_llama PRIVATE
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include)

target_link_libraries(blueberry_llama llama android log)
