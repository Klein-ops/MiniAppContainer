# 蜗壳（MiniAppContainer）

一个运行在 Android 上的**本地小程序容器**：宿主 App 用系统 WebView 渲染 HTML/CSS/JS，
小程序以 zip 包安装，每个小程序拥有独立沙箱，前端通过 JS Bridge 调用宿主本地能力，
高性能计算由 WebView 内置 WebAssembly JIT 完成。仅 Android，使用系统 WebView，不自带浏览器内核。

> 本质：专用浏览器 + 小程序容器 + 本地 Web 运行时。

- **写小程序**：接口手册见 [DEVELOPER.md](./DEVELOPER.md)

---

## 一、构建要求

- **JDK 17**（AGP 8.5 强制）。
- **Android Studio Ladybug / Koala 及以上**，或命令行 Gradle 8.7（wrapper 已内置）。
- **Android SDK**：compileSdk 34 / targetSdk 34 / minSdk 26。
- **无需 NDK / CMake**：项目不含原生层，WASM 走 WebView 内置 WebAssembly JIT。

## 二、构建

命令行：
```bash
cd MiniAppContainer
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Android Studio：`Open` 项目根目录 → Sync → Run。

## 三、运行

1. 启动 App，进入应用列表（空）。底部导航栏：**应用** / **设置**。
2. 点击列表顶部的 **「安装新应用」卡片**，三种安装方式：
   - **安装应用包（zip）**：系统文件选择器选 zip 安装。
   - **安装内置示例**：安装 `assets/sample/sample_app.zip`（demo/hello），含手写 `sample.wasm`（add / fib）。
   - **从 URL 安装**：输入 zip 直链，下载后安装并清理临时包。
3. 点击列表项启动小程序，进入全屏 WebView 容器（左上角悬浮球，闲置自动贴边收起）。
4. 应用列表分类栏支持切换分类、长按分类删除、长按应用拖动排序；应用设置页支持：重命名 / 权限管理 / 移动分类 / 创建桌面快捷方式 / 清空数据 / 卸载。
5. 底部导航「**设置**」页：
   - **调试模式**（开关）：开启后记录所有小程序接口调用（方法/参数/返回值/耗时）。
   - **查看调用日志**：实时查看调试记录；支持手动「清空」。关闭调试模式不会清空日志（日志仅存内存，蜗壳被划掉后自动消失）。
   - **备份与恢复**：导出/导入数据（zip），可选应用与是否含数据；支持备份到 WebDAV（路径 `/MiniAppContainer/backup/`）及从 WebDAV 恢复。
   - **网络存储**：配置 WebDAV 服务器（备份与小程序 `storage` 接口共用）。
   - **权限状态**：查看各小程序接口（通知/内部存储/WebDAV/ADB/快捷方式等）在宿主侧是否真正可用，不可用可直接跳转对应系统设置或配置页。
   - **清理 WebView 缓存**。
6. **数据开放**：内置 SAF DocumentsProvider，其他应用可通过系统文件选择器（SAF）在用户授权后浏览蜗壳的数据目录。

---

## 四、目录结构

```
MiniAppContainer/
├─ DEVELOPER.md                 详细开发者接口文档
├─ README.md
├─ settings.gradle / build.gradle（顶层，AGP 8.5.2 / Kotlin 1.9.24）
├─ gradlew, gradlew.bat, gradle/wrapper/   Wrapper（Gradle 8.7）
├─ tools/                        辅助脚本（不参与编译）
│  └─ sample_src/               示例小程序源（manifest.json / index.html / style.css / app.js / sample.wasm）
└─ app/
   ├─ build.gradle              含 ViewBinding；无 NDK/CMake
   ├─ proguard-rules.pro
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ assets/
      │  ├─ bridge.js           注入前端的 JS Bridge 垫片（Promise 封装）
      │  └─ sample/sample_app.zip  内置示例小程序
      ├─ res/                   布局/主题/图标/菜单
      └─ java/com/miniapp/container/
         ├─ MiniAppApp.kt       Application：持有 registry / installer / permissionManager
         ├─ core/               应用包管理、注册表、清单、路径防护
         ├─ bridge/             JS Bridge（唯一通信通道）+ 方法分发
         ├─ file/               沙箱内文件服务
         ├─ service/            沙箱外能力服务（外部储存/网络/剪贴板/通知）
         ├─ dex/                隔离进程 Dex 执行（AIDL + Service + Runner）
         ├─ debug/              调试模式调用日志总线
         ├─ provider/           SAF DocumentsProvider（数据开放）
         ├─ permission/         权限声明/审批/记录
         ├─ sys/                系统信息
         ├─ util/               IO/工具
         ├─ web/                WebViewClient（隔离拦截）+ WebChromeClient
         └─ ui/                 MainActivity（列表）+ SettingsActivity（设置）+ MiniAppActivity（容器）
                                + AppSettingsActivity / PermissionManageActivity / BackupActivity / DebugActivity + 适配器
```

---

## 五、应用包格式（zip）

zip 内含 `manifest.json`（可在根目录或子目录，递归查找），以其所在目录为应用根：

```json
{
  "uid": "demo",
  "uname": "hello",
  "version": "1.0.0",
  "entry": "index.html",
  "wasm": ["sample.wasm"],
  "icon": "icon.png",
  "permissions": ["net", "sys.openUrl", "fs.external"],
  "requiredPermissions": ["net"]
}
```

- `uid` / `uname`：允许中文/英文/数字/点/下划线/连字符等任意字符（UTF-8），长度 ≤64，禁止 `/`、`\`、控制字符、空串，以及单独的 `.` 或 `..`。
- `entry`：入口 HTML（相对应用根）。
- `wasm`：WASM 模块路径列表（相对应用根）。
- `icon`：应用图标（可选，SVG/PNG，相对应用根）。
- `permissions`：出沙箱能力声明。
- `requiredPermissions`：必要权限子集（拒绝则不进入，必须同时出现在 `permissions` 中）。

---

## 六、JS Bridge API 概览

垫片 `bridge.js` 暴露 `window.MiniApp`，全部 Promise 化。底层唯一通道
`@JavascriptInterface call(reqId, method, params)`，异步 `__resolve` 回传。

```js
MiniApp.info()                          // 应用信息
MiniApp.system(fields?)               // 系统信息（可按需取字段；含 apiVersion / webviewVersion）
MiniApp.ui.toast(msg)                   // 提示

// 沙箱内文件（data/ tmp/ 可写，app/ 只读）
MiniApp.fs.read / readBytes / write / writeBytes / list / exists / stat / mkdir / remove
MiniApp.fs.grep(path, pattern, opts) / sed(path, script)   // 局部编辑，免全量写入
// SAF 导入导出（无需权限）
MiniApp.fs.importFile(destPath) / exportFile(path)
// 内部储存（需 fs.external 权限）
MiniApp.fs.readExternalFile / writeExternalFile / listExternal / existsExternal
MiniApp.fs.statExternal / mkdirExternal / removeExternal / renameExternal
MiniApp.fs.grepExternal / sedExternal   // 同上，作用于内部储存

// WASM（WebView 内置 JIT）
MiniApp.wasm.createMemory(pages)        // 共享内存
MiniApp.wasm.instantiate(bytesOrPath, imports)  // 实例化

// 网络（需 net 权限，支持 GET/POST/PUT/DELETE/PATCH + headers）
MiniApp.net.get(url) / post(url, body) / request(method, url, opts)
// 剪贴板（需 clipboard 权限）
MiniApp.clipboard.read() / write(text)
// 通知（需 notification 权限）
MiniApp.notification.show(title, body) / cancel()
// 网络存储（需 storage 权限）：小程序在 WebDAV 上的独立目录
MiniApp.storage.upload(path, base64) / download(path) / list(path) / delete(path)
// 设备能力（各需对应权限）
MiniApp.sys.vibrate(ms)   // 震动
MiniApp.sys.flashlight({ on: true|false })   // 手电筒
// ADB / Shell（需 adb 权限，⚠ 危险，经 Shizuku 执行）
MiniApp.adb.exec(command, { timeout })   // → { ok, exitCode, stdout, stderr } 或 { ok:false, error, detail }
// Dex 执行（无需权限，android:isolatedProcess 隔离进程内运行）
MiniApp.dex.run({ dex, className, methodName, params, input, output })
// 打开外部链接（需 sys.openUrl 权限）
MiniApp.sys.openUrl(url)
// 预请求权限
MiniApp.permission.request(scope)
```

> 完整接口说明、参数、返回值、示例、权限模型、WASM 开发指南见 **[DEVELOPER.md](./DEVELOPER.md)**。

---

## 七、隔离与安全

- **跨沙箱隔离**：`MiniAppWebViewClient` 拦截所有请求，仅放行当前应用沙箱内的 `file://` 资源；跨沙箱路径或 `http/https/content` 等一律返回 403，强制走 JS Bridge。
- **路径穿越防护**：`PathGuard.resolveUnderRoot` 规范化并校验 canonical 路径未逃出沙箱。
- **写白名单**：沙箱内写操作仅允许 `data/` 和 `tmp/`，禁止写 `app/`（只读资源区）。
- **内部储存安全**：`fs.external` 操作禁止访问应用私有目录（`/data/data/...`），防篡改权限记录。
- **权限分级**：权限系统为注册表驱动，分三级——**安全**（无需审批，如沙箱内文件）、**普通**（需审批）、**危险**（需审批 + 醒目标警，且**不可声明为必要权限**）。新增权限只需在 `PermissionRegistry` 注册一条定义。
- **权限审批**：普通/危险权限调用前经 `PermissionManager` 审批，授权持久化到 `miniapps/permissions.json`。弹窗提供 4 种选择：允许 / 仅允许一次 / 拒绝 / 不再询问。
- **ADB / Shell**：`adb.exec` 需 `adb` 权限（⚠ 危险，审批时醒目警告），经 Shizuku 执行；Shizuku 未激活时返回明确错误而非崩溃
- **Dex 隔离**：`dex.run` 无需权限，在 `android:isolatedProcess="true"` 独立进程中执行（独立 UID + SELinux `isolated_app` 域），无网络/无路径访问/无系统服务/不能加载 native 库；只能读写主进程通过 FD 传入的文件，无法主动打开路径。

---

## 八、WASM

基于 **WebView 内置 WebAssembly JIT**（无额外解释层）。运行在 `file://` 协议下，`fetch('x.wasm')` 会被拦截，需先通过 `MiniApp.fs.readBytes` 读取字节再实例化（`instantiate` 也支持直接传路径字符串）。支持 `import object` 注入宿主能力，可运行 SQLite、FFmpeg 等真实库。编译指南（Rust/C/Emscripten）见 DEVELOPER.md 第六章。

---

## 九、已知限制

- 无后台服务、无推送。
- WASM 模块无系统能力，须通过 JS import shim 间接触发文件/网络。
- WebView `LOAD_NO_CACHE`，每次启动重新加载，无离线缓存。
- 无热更新，须重新打包 zip 安装。
- `file://` 协议下 `SharedArrayBuffer` / Web Worker 跨域策略可能受限，建议优先单线程分片计算。
