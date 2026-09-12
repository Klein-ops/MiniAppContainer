/*
 * WASM 执行层 JNI 入口（wasm3）。
 *
 * 设计要点（对齐文档“WASM 执行层”）：
 *  - 模块字节由宿主读取后传入；这里做一份持久拷贝（wasm3 要求字节在
 *    模块生命周期内有效），实例释放时再回收。
 *  - 单个共享 Environment；每个模块一个 Runtime。
 *  - 不链接 WASI / libc，模块默认无文件/网络/系统能力。
 *  - 失败统一抛 java.lang.RuntimeException，由 Kotlin 层捕获并转为错误 JSON。
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include <stdbool.h>

#include "wasm3.h"

#define TAG "MiniAppWasm"
#define MAX_RETS 16

typedef struct {
    IM3Runtime runtime;
    uint8_t   *data;   /* 持久字节缓冲 */
    size_t     len;
} Instance;

static jclass g_runtimeEx = NULL;
static IM3Environment g_env = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return -1;
    jclass tmp = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (tmp) {
        g_runtimeEx = (jclass) (*env)->NewGlobalRef(env, tmp);
        (*env)->DeleteLocalRef(env, tmp);
    }
    return JNI_VERSION_1_6;
}

static void throwRuntime(JNIEnv *env, const char *msg) {
    if (!env) return;
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (g_runtimeEx) (*env)->ThrowNew(env, g_runtimeEx, msg ? msg : "wasm error");
}

static IM3Environment ensureEnv(void) {
    if (!g_env) g_env = m3_NewEnvironment();
    return g_env;
}

JNIEXPORT jlong JNICALL
Java_com_miniapp_container_wasm_WasmNative_nativeLoadWasm(
        JNIEnv *env, jclass cls, jbyteArray bytes, jint stackSize) {
    (void)cls;
    if (!bytes) { throwRuntime(env, "null wasm bytes"); return 0; }
    jsize len = (*env)->GetArrayLength(env, bytes);
    if (len <= 0) { throwRuntime(env, "empty wasm bytes"); return 0; }

    uint8_t *data = (uint8_t *) malloc((size_t) len);
    if (!data) { throwRuntime(env, "alloc bytes failed"); return 0; }
    (*env)->GetByteArrayRegion(env, bytes, 0, len, (jbyte *) data);
    if ((*env)->ExceptionCheck(env)) { free(data); return 0; }

    IM3Environment ienv = ensureEnv();
    IM3Module module = NULL;
    M3Result r = m3_ParseModule(ienv, &module, data, (uint32_t) len);
    if (r) { free(data); throwRuntime(env, r); return 0; }

    IM3Runtime rt = m3_NewRuntime(ienv, (uint32_t) (stackSize > 0 ? stackSize : 64 * 1024), NULL);
    if (!rt) { m3_FreeModule(module); free(data); throwRuntime(env, "m3_NewRuntime failed"); return 0; }

    r = m3_LoadModule(rt, module);
    if (r) {
        m3_FreeModule(module);   /* 未加载成功，需手动释放 */
        m3_FreeRuntime(rt);
        free(data);
        throwRuntime(env, r);
        return 0;
    }

    Instance *inst = (Instance *) malloc(sizeof(Instance));
    if (!inst) { m3_FreeRuntime(rt); free(data); throwRuntime(env, "alloc instance failed"); return 0; }
    inst->runtime = rt;
    inst->data = data;
    inst->len = (size_t) len;
    return (jlong) (intptr_t) inst;
}

JNIEXPORT jstring JNICALL
Java_com_miniapp_container_wasm_WasmNative_nativeCall(
        JNIEnv *env, jclass cls, jlong handle, jstring func, jobjectArray args) {
    (void)cls;
    Instance *inst = (Instance *) (intptr_t) handle;
    if (!inst || !inst->runtime) { throwRuntime(env, "invalid wasm handle"); return NULL; }
    if (!func) { throwRuntime(env, "null func name"); return NULL; }

    const char *funcName = (*env)->GetStringUTFChars(env, func, NULL);
    if (!funcName) { return NULL; }

    IM3Function fn = NULL;
    M3Result r = m3_FindFunction(&fn, inst->runtime, funcName);
    if (r) { (*env)->ReleaseStringUTFChars(env, func, funcName); throwRuntime(env, r); return NULL; }

    uint32_t argc = m3_GetArgCount(fn);
    jsize argn = args ? (*env)->GetArrayLength(env, args) : 0;
    if (argc != (uint32_t) argn) {
        (*env)->ReleaseStringUTFChars(env, func, funcName);
        char buf[128];
        snprintf(buf, sizeof(buf), "arg count mismatch: expected %u, got %d", argc, argn);
        throwRuntime(env, buf);
        return NULL;
    }

    const char **argv = NULL;
    jstring *localStrs = NULL;
    bool failed = false;

    if (argn > 0) {
        argv = (const char **) calloc((size_t) argn, sizeof(char *));
        localStrs = (jstring *) calloc((size_t) argn, sizeof(jstring));
        if (!argv || !localStrs) {
            free(argv); free(localStrs);
            (*env)->ReleaseStringUTFChars(env, func, funcName);
            throwRuntime(env, "alloc argv failed");
            return NULL;
        }
        for (jsize i = 0; i < argn; i++) {
            jstring s = (jstring) (*env)->GetObjectArrayElement(env, args, i);
            localStrs[i] = s;
            if (s) {
                argv[i] = (*env)->GetStringUTFChars(env, s, NULL);
                if (!argv[i]) { failed = true; break; }
            } else {
                argv[i] = "";
            }
        }
    }

    if (!failed) {
        r = m3_CallArgv(fn, argc, argv);
    }

    /* 释放参数字符串 */
    if (argn > 0) {
        for (jsize i = 0; i < argn; i++) {
            if (localStrs[i] && argv[i]) (*env)->ReleaseStringUTFChars(env, localStrs[i], argv[i]);
            if (localStrs[i]) (*env)->DeleteLocalRef(env, localStrs[i]);
        }
        free(argv);
        free(localStrs);
    }
    (*env)->ReleaseStringUTFChars(env, func, funcName);

    if (failed) { throwRuntime(env, "get arg string failed"); return NULL; }
    if (r) { throwRuntime(env, r); return NULL; }

    uint32_t retc = m3_GetRetCount(fn);
    if (retc == 0) {
        return (*env)->NewStringUTF(env, "null");
    }
    if (retc > MAX_RETS) retc = MAX_RETS;

    uint64_t slots[MAX_RETS];
    const void *retptrs[MAX_RETS];
    for (uint32_t i = 0; i < retc; i++) retptrs[i] = &slots[i];
    r = m3_GetResults(fn, retc, retptrs);
    if (r) { throwRuntime(env, r); return NULL; }

    char out[256];
    if (retc == 1) {
        M3ValueType t = m3_GetRetType(fn, 0);
        switch (t) {
            case c_m3Type_i32:
                snprintf(out, sizeof(out), "%d", (int32_t) slots[0]);
                break;
            case c_m3Type_i64:
                snprintf(out, sizeof(out), "%lld", (long long) slots[0]);
                break;
            case c_m3Type_f32: {
                float f; memcpy(&f, &slots[0], sizeof(float));
                if (isnan(f) || isinf(f)) snprintf(out, sizeof(out), "null");
                else snprintf(out, sizeof(out), "%g", (double) f);
                break;
            }
            case c_m3Type_f64: {
                double d; memcpy(&d, &slots[0], sizeof(double));
                if (isnan(d) || isinf(d)) snprintf(out, sizeof(out), "null");
                else snprintf(out, sizeof(out), "%g", d);
                break;
            }
            default:
                snprintf(out, sizeof(out), "null");
                break;
        }
        return (*env)->NewStringUTF(env, out);
    }

    /* 多返回值：JSON 数组 */
    char buf[1024];
    size_t pos = 0;
    buf[pos++] = '[';
    for (uint32_t i = 0; i < retc; i++) {
        M3ValueType t = m3_GetRetType(fn, i);
        char elem[64];
        switch (t) {
            case c_m3Type_i32:
                snprintf(elem, sizeof(elem), "%d", (int32_t) slots[i]); break;
            case c_m3Type_i64:
                snprintf(elem, sizeof(elem), "%lld", (long long) slots[i]); break;
            case c_m3Type_f32: {
                float f; memcpy(&f, &slots[i], sizeof(float));
                if (isnan(f) || isinf(f)) snprintf(elem, sizeof(elem), "null");
                else snprintf(elem, sizeof(elem), "%g", (double) f);
                break;
            }
            case c_m3Type_f64: {
                double d; memcpy(&d, &slots[i], sizeof(double));
                if (isnan(d) || isinf(d)) snprintf(elem, sizeof(elem), "null");
                else snprintf(elem, sizeof(elem), "%g", d);
                break;
            }
            default:
                snprintf(elem, sizeof(elem), "null"); break;
        }
        size_t n = strlen(elem);
        if (pos + n + 2 >= sizeof(buf)) { pos = sizeof(buf) - 2; break; }
        memcpy(buf + pos, elem, n); pos += n;
        if (i + 1 < retc) buf[pos++] = ',';
    }
    if (pos >= sizeof(buf)) pos = sizeof(buf) - 1;
    buf[pos++] = ']';
    buf[pos] = '\0';
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT void JNICALL
Java_com_miniapp_container_wasm_WasmNative_nativeUnload(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    Instance *inst = (Instance *) (intptr_t) handle;
    if (!inst) return;
    if (inst->runtime) m3_FreeRuntime(inst->runtime);   /* 释放已加载模块 */
    if (inst->data) free(inst->data);
    free(inst);
}
