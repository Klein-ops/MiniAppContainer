# Android 本地小程序容器（MiniAppContainer）

一个运行在 Android 上的**本地小程序容器**：宿主 App 用系统 WebView 渲染 HTML/CSS/JS，
小程序以 zip 包刷入安装，每个小程序拥有独立沙箱，前端通过 JS Bridge 调用宿主本地能力，
高性能计算由 WASM 执行层完成。仅 Android，使用系统 WebView，不自带浏览器内核。

> 本质：专用浏览器 + 小程序容器 + 本地 Web 运行时。

---

## 一、构建要求

- **JDK 17**（AGP 8.5 强制）。
- **Android Studio Ladybug / Koala 及以上**，或命令行 Gradle 8.7（wrapper 已内置）。
- **Android SDK**：compileSdk 34 / targetSdk 34 / minSdk 26。
- **NDK + CMake**：项目含原生层（wasm3 解释器，JNI）。在 Android Studio 中
  `SDK Manager → SDK Tools` 勾选 `NDK (Side by side)` 与 `CMake`。
  首次 Sync 时若 NDK 缺失，Android Studio 会提示安装。
- 未硬绑定 NDK 版本（`app/build.gradle` 未设 `ndkVersion`），使用 SDK 中任一已安装 NDK 即可。

## 二、构建

命令行：
```bash
cd MiniAppContainer
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Android Studio：`Open` 项目根目录 → Sync → Run。

## 三、运行

1. 启动 App，进入“已安装应用”列表（空）。
2. 右上角菜单：
   - **安装内置示例**：安装 `assets/sample/sample_app.zip`（demo/hello），自带一个手写的 `sample.wasm`。
   - **安装应用包**：用系统文件选择器选择任意 zip 应用包安装。
3. 点击列表项启动小程序，进入 WebView 容器。
4. 示例小程序内置按钮：应用信息 / 系统信息 / 文件读写 / 列目录 / WASM add & fib / 网络请求（触发审批）/ 打开链接（触发审批）/ 预请求权限。

---

## 四、目录结构

```
MiniAppContainer/
├─ settings.gradle              项目设置
├─ build.gradle                 顶层（插件版本：AGP 8.5.2 / Kotlin 1.9.24）
├─ gradle.properties
├─ gradlew, gradlew.bat, gradle/wrapper/   Wrapper（Gradle 8.7）
├─ README.md
├─ tools/                        辅助脚本（不参与编译）
│  ├─ make_sample.py            手工组装 sample.wasm（add / fib）
│  ├─ test_wasm.c               纯 C 自测：加载并验证 sample.wasm（见“自测”）
│  └─ sample_src/               示例小程序源（manifest.json / index.html / style.css / app.js / sample.wasm）
└─ app/
   ├─ build.gradle              含 externalNativeBuild(cmake) + ViewBinding
   ├─ proguard-rules.pro        保留 JS Bridge / JNI / 数据模型
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ cpp/
      │  ├─ CMakeLists.txt      构建 libminiapp_wasm.so
      │  ├─ wasm_jni.c          JNI 入口（parse/load/call/unload）
      │  └─ wasm3/             vendored wasm3 0.5.0 核心（MIT）
      ├─ assets/
      │  ├─ bridge.js           注入前端的 JS Bridge 垫片（Promise 封装）
      │  └─ sample/sample_app.zip  内置示例小程序
      ├─ res/                   布局/主题/图标/菜单
      └─ java/com/miniapp/container/
         ├─ MiniAppApp.kt       Application：持有 registry / sandbox / installer / permissionManager
         ├─ core/               应用包管理、注册表、沙箱、路径防护、清单
         ├─ bridge/             JS Bridge（唯一通信通道）+ 方法分发
         ├─ file/               沙箱内文件服务
         ├─ wasm/               WASM 执行层（WasmNative JNI + WasmRuntimeManager）
         ├─ permission/         权限声明/审批/记录（出沙箱门控）
         ├─ sys/                系统信息
         ├─ web/                WebViewClient（隔离拦截）+ WebChromeClient
         └─ ui/                 MainActivity（管理界面）+ MiniAppActivity（容器）+ 适配器
```

---

## 五、与设计文档的对应

| 文档章节 | 实现 |
|---|---|
| 二 总体架构 | `web`（渲染层）/ `bridge`（通信层）/ `core`+`file`+`wasm`+`permission`+`sys`（能力层）/ `core.SandboxManager`（存储层） |
| 三 应用身份 | `PathGuard.appKey(uid,uname)`；`uid` 完全一致且 `uname` 完全一致才算同一应用（注册表按 appKey 索引） |
| 三 沙箱 | `SandboxManager`：`<filesDir>/miniapps/<uid>_<uname>/{app,data,tmp,meta.json}` |
| 四 应用包清单 | `AppManifest`（uid/uname/version/entry/wasm/permissions） |
| 四 安装流程 | `AppInstaller.installFromZip`：解压→读清单→清洗→查注册表→建/复用沙箱→解压到 app/→写 meta.json→注册 |
| 四 更新规则 | 同 appKey 视为更新：只清 `app/`，保留 `data/` 与 `tmp/` |
| 五 JS Bridge | `MiniAppBridge`：唯一 `@JavascriptInterface call(reqId,method,params)`，异步 `__resolve` 回传 |
| 六 WASM 执行层 | `wasm3` 解释器 + JNI；不链接 WASI/libc，默认无文件/网络/系统能力 |
| 七 权限模型 | `permission`：沙箱内默认允许，出沙箱（net/sys.openUrl/fs.external）弹窗审批，结果持久化 |
| 八 宿主核心模块 | 见目录结构中的各包 |
| 九 关键流程 | 安装/启动/执行WASM/出沙箱审批 均已实现 |

---

## 六、应用包格式（zip）

zip 根目录扁平结构，至少含 `manifest.json`：

```json
{
  "uid": "demo",
  "uname": "hello",
  "version": "1.0.0",
  "entry": "index.html",
  "wasm": ["sample.wasm"],
  "permissions": ["net", "sys.openUrl", "fs.external"]
}
```

- `uid`/`uname`：须匹配 `^[A-Za-z0-9._-]{1,64}$`，禁路径分隔符与 `..`。
- `entry`：相对 `app/` 的入口 HTML。
- `wasm`：沙箱内 WASM 模块相对路径列表（供 JS Bridge `wasm.load` 使用）。
- `permissions`：出沙箱能力声明。未声明的能力即使运行时请求也会被拒绝。

---

## 七、JS Bridge API（前端）

垫片 `bridge.js` 暴露 `window.MiniApp`，全部 Promise 化。底层唯一通道
`MiniAppNative.call(reqId, method, JSON)`（由垫片封装，前端不直接调用）。

```js
MiniApp.info()                              // 应用信息
MiniApp.system()                            // 系统信息
MiniApp.call('ui.toast', { message })       // 提示
MiniApp.fs.read(path)                       // 读文本（沙箱内）
MiniApp.fs.readBytes(path)                  // 读二进制(base64)
MiniApp.fs.write(path, content)             // 写文本
MiniApp.fs.writeBytes(path, base64)         // 写二进制
MiniApp.fs.list(dir)                        // 列目录
MiniApp.fs.exists(path) / stat(path) / mkdir(path) / remove(path)
MiniApp.wasm.load(path)                     // -> { handle }
MiniApp.wasm.call(handle, func, args)      // args 为数字/字符串数组
MiniApp.wasm.unload(handle)
MiniApp.net.get(url)                        // -> { status, body }（需审批 net）
MiniApp.sys.openUrl(url)                    // 打开外部链接（需审批 sys.openUrl）
MiniApp.permission.request(scope)           // 预请求权限 -> true/false
```

WASM 结果按返回类型编码为 JSON：i32/i64 为数字，f32/f64 为数字（NaN/Inf 为 null），
多返回值为数组，无返回值为 `null`。

---

## 八、隔离与安全

- **跨沙箱隔离**：`MiniAppWebViewClient` 拦截所有请求，仅放行当前应用沙箱内的 `file://`
  资源；跨沙箱路径或 `http/https/content` 等一律返回 403，强制走 JS Bridge。
- **路径穿越防护**：`PathGuard.resolveUnderRoot` 规范化并校验 canonical 路径未逃出沙箱。
- **WASM 默认无特权**：wasm3 不链接 WASI/libc，模块无文件/网络/系统入口。
- **出沙箱审批**：`net` / `sys.openUrl` / `fs.external` 调用前经 `PermissionManager`
  审批，授权持久化到 `miniapps/permissions.json`。

---

## 九、自测说明（交付前已做）

工作区有 gcc，已用纯 C（不含 JNI/Android）验证 wasm3 核心可编译、`sample.wasm` 合法、
JNI 用到的 wasm3 API 用法正确：

```bash
cd tools
gcc -std=c11 -I ../app/src/main/cpp/wasm3 -O2 \
    $(for c in m3_bind.c m3_code.c m3_compile.c m3_core.c m3_emit.c m3_env.c \
              m3_exec.c m3_function.c m3_info.c m3_module.c m3_optimize.c m3_parse.c; \
        do echo ../app/src/main/cpp/wasm3/$c; done) \
    test_wasm.c -o test_wasm -lm
./test_wasm sample_src/sample.wasm
# add(2,3)=5  fib(10)=55  fib(15)=610  -> ALL OK
```

此自测曾在 `export_entry` 把“向量计数”误当名字长度前缀的 bug 上失败，已修复并复测通过。
完整 Android 编译与运行需你在本机用 Android Studio / NDK 完成。

---

## 十、已知限制 / 可改进点

- WASM 仅支持纯计算模块（无 WASI/导入）；如需 libc 导入可在 CMake 加入
  `wasm3/m3_api_libc.c` 并在加载后调用 `m3_LinkLibC`。
- 权限范围固定为 `net` / `sys.openUrl` / `fs.external`；`fs.external` 通过 content URI
  读取（需前端先获得 SAF URI）。
- 沙箱内允许页面直接 file:// 访问自身沙箱（符合“沙箱内默认允许”），但文件读写仍建议走 Bridge 以便统一控制。
