#!/bin/sh
# 在本 Linux 工作区（aarch64）重编 libminiapp_wasm.so（arm64-v8a）。
# 关键点：
#   -nostdlib               不链 glibc，避免 GLIBC 版本符号
#   -L stub -lc -lm         stub 仅提供 SONAME，强制写入 DT_NEEDED libc.so/libm.so
#   --no-as-needed          强制保留对 stub 的 DT_NEEDED（否则因 stub 空被丢弃）
#   --unresolved-symbols=ignore-all  允许 malloc/memcpy/sqrt 等留 UND，运行时由 bionic 解析
#   OpenJDK 的 jni.h/jni_md.h 与 Android JNI 函数表布局二进制一致
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cd "$HERE/../.." && pwd)"
W="$PROJ/app/src/main/cpp/wasm3"
J="$PROJ/app/src/main/cpp/wasm_jni.c"
CORE="m3_bind m3_code m3_compile m3_core m3_emit m3_env m3_exec m3_function m3_info m3_module m3_optimize m3_parse"
OUT="$PROJ/app/src/main/jniLibs/arm64-v8a/libminiapp_wasm.so"
mkdir -p "$(dirname "$OUT")" "$HERE/stub"
# stub libc.so / libm.so（仅 SONAME，无实现）
echo '' | gcc -nostdlib -shared -Wl,-soname,libc.so -x c - -o "$HERE/stub/libc.so"
echo '' | gcc -nostdlib -shared -Wl,-soname,libm.so -x c - -o "$HERE/stub/libm.so"
gcc -nostdlib -shared -fPIC -fno-stack-protector -fno-builtin -U_FORTIFY_SOURCE -O2 \
    -Wno-unused-parameter -Wno-unused-function -Wno-sign-compare \
    -I "$HERE" -I "$W" \
    -Wl,-soname,libminiapp_wasm.so \
    -L"$HERE/stub" -Wl,--no-as-needed -lc -lm -Wl,--as-needed \
    -Wl,--unresolved-symbols=ignore-all \
    -o "$OUT" "$J" $(for c in $CORE; do echo "$W/$c.c"; done)
echo "built: $OUT ($(stat -c%s "$OUT") bytes)"
echo "--- DT_NEEDED ---"; readelf -d "$OUT" | grep NEEDED
echo "--- 未定义符号数 ---"; readelf --dyn-syms "$OUT" | grep -c UND
