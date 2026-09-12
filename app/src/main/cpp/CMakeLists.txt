cmake_minimum_required(VERSION 3.22.1)
project(miniapp_wasm C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# wasm3 核心解释器（不链接 WASI/libc，保持“默认无文件/网络/系统权限”）。
add_library(miniapp_wasm SHARED
    wasm_jni.c
    wasm3/m3_bind.c
    wasm3/m3_code.c
    wasm3/m3_compile.c
    wasm3/m3_core.c
    wasm3/m3_emit.c
    wasm3/m3_env.c
    wasm3/m3_exec.c
    wasm3/m3_function.c
    wasm3/m3_info.c
    wasm3/m3_module.c
    wasm3/m3_optimize.c
    wasm3/m3_parse.c
)

target_include_directories(miniapp_wasm PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/wasm3
)

# 关闭一些无害警告，避免噪音。
target_compile_options(miniapp_wasm PRIVATE
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-sign-compare
)

find_library(log-lib log)
find_library(m-lib m)
target_link_libraries(miniapp_wasm ${log-lib} ${m-lib})
