# 开发者手册 —— 蜗壳开发指南

> 适用宿主：**蜗壳**（Android 本地小程序容器应用）
> 平台能力：系统 WebView 渲染 + JS Bridge + 文件沙箱 + WASM 执行 + 权限审批

---

## 一、平台概览

你的应用是一个 zip 包，由蜗壳解压安装到独立沙箱。

```
沙箱目录（每个应用独立）
<filesDir>/miniapps/<uid>_<uname>/
├─ app/      ← 前端资源（HTML/CSS/JS/WASM），只读
├─ data/     ← 读写数据
├─ tmp/      ← 临时文件
└─ meta.json ← 安装信息
```

- **前端**：标准 HTML/CSS/JS，运行在系统 WebView 中。
- **WASM**：基于 WebView 内置 WebAssembly JIT，支持二进制、Memory、import 注入。
- **权限**：沙箱内读写无需审批；访问网络、打开外链、读写内部储存需审批。
  分为**普通权限**（可拒绝仍能进入）和**必要权限**（拒绝则不进入）。

---

## 二、应用包结构

zip 包结构说明：

```
推荐扁平结构（简单应用）：
sample_app.zip
├─ manifest.json
├─ index.html
└─ ...

支持嵌套结构（带目录层级）：
my_app/
├─ manifest.json
├─ pages/
│   └─ index.html
├─ assets/
│   └─ logo.png
└─ wasm/
    └─ heavy.wasm
```

> 蜗壳会递归查找 `manifest.json`，以其所在目录为应用根。
> `entry`、`wasm`、`icon` 等路径相对该应用根。

### manifest.json

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

| 字段 | 说明 | 约束 |
|---|---|---|
| `uid` | 身份第一部分 | 允许中文/英文/数字/点/下划线/连字符，长度 ≤64，禁止 `/`、`\`、控制字符、空串、`..` |
| `uname` | 身份第二部分 | 同上 |
| `version` | 版本号 | 任意字符串 |
| `entry` | 入口 HTML 路径 | 相对应用根，如 `index.html` 或 `pages/index.html` |
| `wasm` | WASM 模块路径列表 | 相对应用根，如 `["sample.wasm"]` 或 `["wasm/heavy.wasm"]` |
| `icon` | 应用图标路径（可选） | 相对应用根，支持 SVG/PNG，如 `"icon.png"`；不设则显示默认图标 |
| `permissions` | 能力声明 | 见第五章权限范围 |
| `requiredPermissions` | 必要权限子集 | 必须同时出现在 `permissions` 中 |

**关键规则**：
- `uid` + `uname` 共同构成唯一身份（`appKey = uid + "_" + uname`）。两者完全一致才视为同一应用（更新），否则是全新应用（独立沙箱）。
- **显示名**：默认显示 `uname`，用户可在应用设置页重命名（`displayName`），仅影响显示，不影响应用身份（`uid_uname`）。`uid` 不展示给用户。
- `requiredPermissions` 中的每一项**必须也出现在 `permissions`** 中。

---

## 三、前端开发

### 3.1 HTML/CSS

标准 Web 技术。入口文件由 `manifest.entry` 指定，默认 `index.html`。

**限制**：
- 不能使用 `file://` 协议主动请求沙箱外资源（跨沙箱请求被拦截，返回 403）。
- `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` 已禁用。
- 沙箱内页面可直接引用同目录资源（`<script src="app.js">`、`<link href="style.css">`）。

### 3.2 JS Bridge 调用

前端通过 `window.MiniApp`（Promise 封装）调用宿主能力，底层经 `window.__MiniAppBridge` 收发。所有调用均为异步。

```js
MiniApp.info().then(function (info) {
  console.log(info);
});

MiniApp.fs.write('data/test.txt', 'Hello').then(function () {
  return MiniApp.ui.toast('已写入');
});
```

> 建议页面加载后用轮询等待 `window.MiniApp` 就绪再调用。

---

## 四、JS Bridge API 完整清单

### 4.1 应用与系统

```js
MiniApp.info()
// → { uid, uname, version, entry, permissions, appKey }

MiniApp.system()
// → { platform, model, manufacturer, brand, osVersion, sdk, hostAppVersion, density, densityDpi, widthPixels, heightPixels }

MiniApp.ui.toast('提示文本')     // → "true"
```

### 4.2 文件系统

路径**相对沙箱根**（即 `<沙箱>/`），不是相对应用根。

**白名单规则**：写操作（write/writeBytes/mkdir/remove）只允许 `data/` 和 `tmp/` 目录，禁止写 `app/`（只读资源区）。读操作允许 `app/`、`data/`、`tmp/`。

```js
MiniApp.fs.read('data/test.txt')           // → "文件内容字符串"
MiniApp.fs.write('data/test.txt', 'Hello') // → "true"
MiniApp.fs.readBytes('app/image.png')      // → base64 字符串
MiniApp.fs.writeBytes('data/avatar.b64', base64String) // → "true"
MiniApp.fs.list('data')  // → [{ name, isDir, size }]
MiniApp.fs.exists('data/test.txt')  // → "true" / "false"
MiniApp.fs.stat('data/test.txt')  // → { exists, isDir, size, name, canRead, canWrite, lastModified }
MiniApp.fs.mkdir('data/sub')  // → "true"
MiniApp.fs.remove('data/tmp')  // → "true" / "false"
```

#### SAF 导入导出（无需权限）

通过 Android 存储访问框架选择文件，不需要任何权限声明：

```js
// 导入：弹出系统文件选择器，选中后写入沙箱指定路径
await MiniApp.fs.importFile('data/imported.bin');   // → "true" / ""

// 导出：弹出系统保存对话框，将沙箱文件内容写出
await MiniApp.fs.exportFile('data/report.txt');    // → "true" / ""
```

#### 静默读写内部储存（需 `fs.external` 权限）

声明 `fs.external` 后，可静默读写内部储存（`/storage/emulated/0`）。

**安全限制**：禁止访问应用私有目录（`/data/data/...`），防止篡改权限记录。

**权限适配**（蜗壳自动处理，开发者无需关心）：
- Android 10 及以下：首次调用时蜗壳主动弹窗申请运行时存储权限。
- Android 11+：`MANAGE_EXTERNAL_STORAGE` 是系统特殊权限，需在系统设置开启「所有文件访问」。蜗壳会自动跳转设置页，开启后返回重试。

```js
// 读取内部储存文件（返回 base64）
const b64 = await MiniApp.fs.readExternalFile('/storage/emulated/0/Documents/note.txt');

// 写入内部储存文件（base64）
await MiniApp.fs.writeExternalFile('/storage/emulated/0/Documents/out.txt', base64String);

// 列出内部储存某目录的文件列表（不递归）
const files = await MiniApp.fs.listExternal('/storage/emulated/0/Documents');
// → [{ name, isDir, size }, ...]

// 检查文件/目录是否存在
await MiniApp.fs.existsExternal('/storage/emulated/0/Documents/note.txt'); // → "true" / "false"

// 查询文件信息
await MiniApp.fs.statExternal('/storage/emulated/0/Documents/note.txt');
// → { exists, isDir, size, name, canRead, canWrite, lastModified }

// 创建目录（含父目录）
await MiniApp.fs.mkdirExternal('/storage/emulated/0/Documents/sub'); // → "true" / "false"

// 删除文件或目录（递归删除目录及其内容）
await MiniApp.fs.removeExternal('/storage/emulated/0/Documents/old_dir'); // → "true" / "false"

// 重命名/移动文件（同文件系统内）
await MiniApp.fs.renameExternal(
  '/storage/emulated/0/Documents/a.txt',
  '/storage/emulated/0/Documents/b.txt'
); // → "true" / "false"

// 读取外部 content:// URI（如其他应用共享的文件，返回 base64）
const b64 = await MiniApp.call('fs.readExternal', { uri: 'content://...' });
```

### 4.3 WASM 执行

蜗壳 WASM 基于 **WebAssembly 原生**（WebView 内置 JIT 引擎），无额外解释层。

> **重要**：运行在 `file://` 协议下，`fetch('x.wasm')` 会被沙箱拦截。
> **必须**先通过 `MiniApp.fs.readBytes` 读取文件字节后再实例化。
> `MiniApp.wasm.instantiate` 也支持直接传路径字符串（内部自动读取字节）。

```js
// 辅助函数：读取 WASM 文件（相对沙箱根，app/ 下）并实例化
async function loadWasm(path, imports) {
  const b64 = await MiniApp.fs.readBytes(path);   // 路径相对沙箱根，如 'app/heavy.wasm'
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return MiniApp.wasm.instantiate(bytes, imports || {});
}

// 1. 创建共享内存（WASM 与 JS 共用，零拷贝）
const memory = MiniApp.wasm.createMemory(256); // 256 页 ≈ 16MB

// 2. 准备宿主能力（import object）
const imports = {
  env: {
    memory: memory,
    log: (ptr, len) => {
      const bytes = new Uint8Array(memory.buffer, ptr, len);
      console.log('[WASM]', new TextDecoder().decode(bytes));
    }
  }
};

// 3. 加载并实例化（传字节或路径均可）
const instance = await loadWasm('app/heavy.wasm', imports);

// 4. 直接调用导出函数（高性能，零拷贝）
instance.exports.compute(42);

// 5. 通过 Memory 高效读写二进制
const view = new Uint32Array(memory.buffer);
view[0] = 1024;
const result = view[1];
```

**原生路径优势**：
- **二进制高效传递**：`WebAssembly.Memory` 是 `ArrayBuffer`，JS 和 WASM 共享同一块内存，无需 base64 序列化
- **性能**：WebView 内置 WASM JIT（TurboFan/Sparkplug），足以运行 SQLite、FFmpeg、llama.cpp 等真实库
- **灵活性**：支持任意 import object，可注入文件/网络/日志等宿主能力

### 4.4 网络（需审批）

```js
// GET
const res = await MiniApp.net.get('https://example.com/api');
// → { status: 200, body: "响应文本" }

// POST（带请求体）
const res = await MiniApp.net.post('https://example.com/api', JSON.stringify({ key: 'value' }));

// 通用请求（自定义方法 + 请求头）
const res = await MiniApp.net.request('PATCH', 'https://example.com/api', {
  headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer xxx' },
  body: JSON.stringify({ patch: true })
});
```

支持 GET/POST/PUT/DELETE/PATCH 等任意方法及自定义请求头；必须声明 `"net"`；首次调用弹窗审批，授权持久化。

### 4.5 剪贴板（需审批）

```js
const text = await MiniApp.clipboard.read();   // → "剪贴板文本"
await MiniApp.clipboard.write('复制内容');     // → "true"
```

需声明 `"clipboard"`；首次读写弹窗审批。

### 4.6 打开外部链接（需审批）

```js
await MiniApp.sys.openUrl('https://example.com');  // → "true"
```

需声明 `"sys.openUrl"`。

### 4.7 预请求权限

```js
var granted = await MiniApp.permission.request('net');  // → true / false
```

---

## 五、权限模型

### 5.1 两种权限

| 类型 | 字段 | 行为 |
|---|---|---|
| 普通权限 | `permissions` | 可拒绝，仍能进入；运行时首次调用弹窗 |
| 必要权限 | `requiredPermissions` | **拒绝则不进入**，打开时一次性审批 |

### 5.2 权限范围

| 权限 scope | 能力 | 默认 |
|---|---|---|
| 沙箱内读写 `app/`/`data/`/`tmp/` | 文件操作（写仅 `data/`/`tmp/`） | **允许**，无需审批 |
| `net` | HTTP GET 请求 | 拒绝 |
| `sys.openUrl` | 打开外部链接 | 拒绝 |
| `fs.external` | 静默操作内部储存：读写/列目录/查存在/查信息/建目录/删除/重命名 + 读取外部 content:// | 拒绝 |
| `clipboard` | 读写系统剪贴板 | 拒绝 |

### 5.3 审批流程

- **必要权限**：用户点击应用时，若有未授权的必要权限，弹窗列出全部并要求一次性允许/拒绝。拒绝任一 → 不进入。
- **普通权限**：进入后，运行时首次调用对应能力时弹窗。
- 授权结果持久化到 `miniapps/permissions.json`。
- **卸载应用时清除该应用全部授权记录**（重装会重新请求）。
- 权限管理入口：应用列表 → 应用设置页 → 权限管理。

### 5.4 权限选项

弹窗提供 4 种选择：

| 选项 | 行为 |
|---|---|
| 允许 | 持久化授权 |
| 仅允许一次 | 本次会话有效，下次再问 |
| 拒绝 | 本次拒绝，下次运行时再弹窗 |
| 不再询问 | 持久化拒绝，之后不再弹窗（可在权限管理页撤销） |

---

## 六、WASM 模块开发

### 6.1 编译 WASM

#### 6.1.1 Rust（推荐）

```bash
# 无 std（简单模块）
rustup target add wasm32-unknown-unknown
cargo build --target wasm32-unknown-unknown --release

# WASI（支持文件/网络 shim）
rustup target add wasm32-wasip1
cargo build --target wasm32-wasip1 --release
```

#### 6.1.2 C/C++

```bash
# 简单 WASM
clang --target=wasm32 -O3 -nostdlib -Wl,--no-entry -o app.wasm app.c

# Emscripten（WASI + 完整库支持）
emcc app.c -o app.wasm -O3 -s WASM=1 -s SIDE_MODULE=1
```

#### 6.1.3 真实库编译

**SQLite**：
```bash
emcc sqlite3.c -o sqlite3.wasm -O3 -s WASM=1 -s SIDE_MODULE=1
```

**FFmpeg**：
```bash
emcc ffmpeg.c -o ffmpeg.wasm -O3 -s WASM=1 -s SIDE_MODULE=1
# 注意：FFmpeg 体积大，需配置 Memory 上限
```

**llama.cpp**：
```bash
cmake -B build-wasm -DCMAKE_TOOLCHAIN_FILE=path/to/wasi-toolchain.cmake
cmake --build build-wasm
```

### 6.2 注入宿主能力（文件/网络/权限）

WASM 模块默认无文件/网络/系统能力。通过 **import object** 注入宿主能力：

```js
const memory = MiniApp.wasm.createMemory(256);

const imports = {
  env: {
    memory: memory,
    log: (ptr, len) => {
      const bytes = new Uint8Array(memory.buffer, ptr, len);
      console.log('[WASM]', new TextDecoder().decode(bytes));
    },

    // 文件读取（WASM 请求 → JS 通过 Bridge 读取 → 写回 Memory）
    fs_read: (fd, bufPtr, bufLen) => {
      // 实现见下方 6.3
    },

    // 网络请求
    net_get: (urlPtr, urlLen, respBufPtr, respBufLen) => {
      // 实现见下方 6.3
    }
  }
};

const instance = await loadWasm('app/app.wasm', imports);
```

### 6.3 Import Shim 设计模式

WASM 模块通过 import 调用 JS 函数，JS 函数通过 `MiniApp` Bridge 访问宿主能力。

**同步桥接（缓存/小数据）**：
```js
const fileCache = new Map();

function fs_read(fd, bufPtr, bufLen) {
  const data = fileCache.get(fd);
  if (!data) return -1;
  const bytes = new Uint8Array(memory.buffer, bufPtr, bufLen);
  bytes.set(data.subarray(0, bufLen));
  return 0;
}
```

**异步桥接（回调/轮询）**：
```js
function net_get(urlPtr, urlLen, respBufPtr, respBufLen) {
  const url = new TextDecoder().decode(new Uint8Array(memory.buffer, urlPtr, urlLen));

  MiniApp.net.get(url).then(resp => {
    const body = new TextEncoder().encode(resp.body);
    new Uint8Array(memory.buffer, respBufPtr, Math.min(body.length, respBufLen)).set(body.subarray(0, respBufLen));
    instance.exports.on_net_complete(0, body.length);
  });

  return 0; // 异步操作已启动
}
```

**WASI Shim**：如果 WASM 模块通过 WASI（`wasi_snapshot_preview1`）访问文件/网络，可以实现完整的 WASI import 对象，底层调用 `MiniApp.fs.*` 和 `MiniApp.net.*`。这是运行 SQLite/FFmpeg 等真实库的关键。

### 6.4 多线程与不阻塞 UI

**重要限制**：`file://` 协议下，`SharedArrayBuffer` 和 Web Worker 的跨域策略可能受限（取决于 Android WebView 版本和安全配置）。

**方案 A：单线程 JIT（推荐）**
- WebAssembly JIT 本身性能已足够
- 单线程足以流畅运行 SQLite、FFmpeg（非实时场景）、llama.cpp（推理）
- 避免在主线程做超长计算，改用分片：

```js
function computeLongRunning(wasmFn, total) {
  return new Promise(resolve => {
    let i = 0;
    function slice() {
      const end = Math.min(i + 1000, total);
      for (; i < end; i++) wasmFn(i);
      if (i < total) setTimeout(slice, 0);
      else resolve();
    }
    slice();
  });
}
```

**方案 B：Web Worker（如果环境允许）**
- 在 Worker 中加载 WASM，避免阻塞 UI
- 注意：`file://` 下 Worker 可能不可用，需测试

```js
// main.js
const worker = new Worker('wasm-worker.js');
worker.postMessage({ cmd: 'load', path: 'heavy.wasm' });

// wasm-worker.js
self.onmessage = async (e) => {
  if (e.data.cmd === 'load') {
    // 主线程通过 MiniApp.fs.readBytes 读取字节后 postMessage 给 worker
    const bytes = e.data.bytes; // Uint8Array
    const { instance } = await WebAssembly.instantiate(bytes, wasiImports);
    self.postMessage({ cmd: 'ready' });
  }
};
```

### 6.5 性能建议

- **JIT 预热**：首次调用 WASM 函数有编译开销，先预热再计时
- **Memory 大小**：初始 256 页（16MB）够大多数场景，动态 `memory.grow()` 可扩容
- **避免频繁 JS-WASM 调用**：批量操作合并为一次调用
- **大块数据**：用 `WebAssembly.Memory` + `DataView`/`TypedArray` 直接操作，避免逐元素调用

---

## 七、调试

### 7.1 前端

```js
MiniApp.info().then(console.log);
MiniApp.system().then(console.log);
try { await MiniApp.fs.read('data/missing.txt'); }
catch (e) { console.error(e.message); }
```

### 7.2 WASM 调试

```js
// 查看 Memory 内容
const mem = new Uint8Array(memory.buffer);
console.log(mem.subarray(0, 64));

// 查看导出函数
console.log(Object.keys(instance.exports));

// 性能计时
const t0 = performance.now();
instance.exports.heavy();
console.log('WASM time:', performance.now() - t0);
```

### 7.3 日志标签

- `MiniAppJS`：前端 console 输出
- `MiniAppBridge`：Bridge 调用与错误
- `MiniAppWasm`：WASM 加载/调用

```bash
adb logcat -s MiniAppJS MiniAppBridge MiniAppWasm
```

### 7.4 常见错误

| 错误 | 原因 | 解决 |
|---|---|---|
| `未获得必要权限，无法运行` | 必要权限被拒 | 在弹窗允许，或去权限管理页授予 |
| `WASM 模块不存在或非文件: xxx` | 路径不对 | `wasm` 路径相对应用根；确认 zip 含该文件 |
| `WebAssembly.instantiate failed` | import 缺失或 WASM 格式错误 | 检查 import object 是否提供 `env.memory` 等必需项 |
| `permission denied: net` | 未声明权限或用户拒绝 | manifest 声明 `"net"` 并允许 |
| `unknown method` | 方法名拼写错误 | 对照本手册 API 清单 |
| `写操作仅允许 data/ 和 tmp/ 目录` | 尝试写 `app/` | 写数据放 `data/` 或 `tmp/` |
| `禁止访问应用私有目录` | fs.external 访问 `/data/data/` | 只访问内部储存 `/storage/emulated/0` |
| `已跳转系统设置，请开启所有文件访问权限` | Android 11+ 未开 MANAGE_EXTERNAL_STORAGE | 在设置页开启后返回重试 |

---

## 八、更新规则

- `uid` + `uname` 完全一致 → **更新**：保留 `data/`、`tmp/` 和 `displayName`，替换 `app/` 内容。
- `uid` 或 `uname` 任一不同 → **全新应用**：创建独立沙箱，旧沙箱保留。
- **卸载会清除沙箱与该应用全部权限记录**。

---

## 九、已知限制

- **无后台服务**：后台运行、推送暂不支持。
- **WASM 无系统能力**：模块不能直接读写文件/网络，必须通过 JS import shim 间接触发。
- **无离线缓存**：WebView `LOAD_NO_CACHE`，每次启动重新加载。
- **无热更新**：必须重新打包 zip 并安装。
- **SharedArrayBuffer 限制**：`file://` 协议下跨域策略可能禁用 `SharedArrayBuffer`，多线程需测试环境支持。
- **Web Worker 限制**：`file://` 协议下 Worker 可用性取决于 WebView 实现，建议优先单线程分片计算。

---

## 十、完整示例

详见 `tools/sample_src/` 和 `app/src/main/assets/sample/sample_app.zip`。

包含：
- `manifest.json`：声明 `demo/hello`，权限 `net`（必要）、`sys.openUrl`、`fs.external`
- `index.html` + `style.css` + `app.js`：UI 与交互
- `sample.wasm`：导出 `add(i32,i32)->i32` 和 `fib(i32)->i32`

构建示例 zip：

```bash
cd tools/sample_src
zip -j ../../app/src/main/assets/sample/sample_app.zip \
  manifest.json index.html style.css app.js sample.wasm
```

---

## 十一、快速检查清单

发布/测试前确认：
- [ ] `manifest.json` 位于 zip 内（可在根目录或子目录），字段完整
- [ ] `uid` / `uname` 不包含 `/`、`\`、控制字符、`..`
- [ ] `entry` 指向的 HTML 文件存在
- [ ] `wasm` 列出的模块文件存在且路径正确
- [ ] `icon` 路径（若设置）指向存在的 SVG/PNG 文件
- [ ] `requiredPermissions` 的每一项都同时出现在 `permissions` 中
- [ ] `permissions` 声明了所有能力
- [ ] 前端通过 `window.MiniApp` 调用，而非直接 `fetch`/`XMLHttpRequest`
- [ ] WASM 实例化提供完整 `import object`（含 `env.memory`）
- [ ] 写文件只写 `data/` 或 `tmp/`，不写 `app/`
