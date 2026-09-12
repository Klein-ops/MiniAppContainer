#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "wasm3.h"

static unsigned char* read_file(const char* path, size_t* out_len) {
    FILE* f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char* buf = (unsigned char*)malloc((size_t)sz);
    if (fread(buf, 1, (size_t)sz, f) != (size_t)sz) { free(buf); fclose(f); return NULL; }
    fclose(f);
    *out_len = (size_t)sz;
    return buf;
}

static int call_i32(IM3Runtime rt, const char* name, int argc, const char** argv) {
    IM3Function fn = NULL;
    M3Result r = m3_FindFunction(&fn, rt, name);
    if (r) { printf("FIND %s: %s\n", name, r); return -1000000; }
    if (m3_GetArgCount(fn) != (uint32_t)argc) {
        printf("ARGC %s: expected %u got %d\n", name, m3_GetArgCount(fn), argc);
        return -1000000;
    }
    r = m3_CallArgv(fn, (uint32_t)argc, argv);
    if (r) { printf("CALL %s: %s\n", name, r); return -1000000; }
    if (m3_GetRetCount(fn) != 1) { printf("RETC %s != 1\n", name); return -1000000; }
    int32_t ret = 0;
    const void* retptrs[1] = { &ret };
    r = m3_GetResults(fn, 1, retptrs);
    if (r) { printf("GETRES %s: %s\n", name, r); return -1000000; }
    return ret;
}

int main(int argc, char** argv) {
    const char* path = argc > 1 ? argv[1] : "sample.wasm";
    size_t len = 0;
    unsigned char* bytes = read_file(path, &len);
    if (!bytes) { printf("cannot read %s\n", path); return 1; }

    IM3Environment env = m3_NewEnvironment();
    IM3Runtime rt = m3_NewRuntime(env, 64 * 1024, NULL);
    if (!rt) { printf("runtime alloc failed\n"); return 2; }

    IM3Module mod = NULL;
    M3Result r = m3_ParseModule(env, &mod, bytes, (uint32_t)len);
    if (r) { printf("parse: %s\n", r); return 3; }
    r = m3_LoadModule(rt, mod);
    if (r) { printf("load: %s\n", r); return 4; }

    int fails = 0;
    const char* a2[] = {"2","3"};
    int add = call_i32(rt, "add", 2, a2);
    printf("add(2,3) = %d (expect 5)\n", add); if (add != 5) fails++;

    const char* a0[] = {"0"};
    const char* a1[] = {"1"};
    const char* a10[] = {"10"};
    const char* a15[] = {"15"};
    int f0 = call_i32(rt, "fib", 1, a0);
    int f1 = call_i32(rt, "fib", 1, a1);
    int f10 = call_i32(rt, "fib", 1, a10);
    int f15 = call_i32(rt, "fib", 1, a15);
    printf("fib(0)=%d (expect 0)\n", f0); if (f0 != 0) fails++;
    printf("fib(1)=%d (expect 1)\n", f1); if (f1 != 1) fails++;
    printf("fib(10)=%d (expect 55)\n", f10); if (f10 != 55) fails++;
    printf("fib(15)=%d (expect 610)\n", f15); if (f15 != 610) fails++;

    m3_FreeRuntime(rt);
    free(bytes);
    printf(fails == 0 ? "ALL OK\n" : "FAILED\n");
    return fails == 0 ? 0 : 5;
}
