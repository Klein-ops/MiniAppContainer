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
- **权限**：沙箱内读写无需审批；访问网络（`net`）、打开外链（`sys.openUrl`）、读写内部储存（`fs.external`）、剪贴板（`clipboard`）、通知（`notification`）、Dex（`dex`）需审批。
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
| `uid` | 身份第一部分 | 允许中文/英文/数字/点/下划线/连字符等任意字符（UTF-8），长度 ≤64（字符数），禁止 `/`、`\`、控制字符（ISO control）、空串，以及单独的 `.` 或 `..` |
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
- `allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` 已禁用；`allowContentAccess` 亦禁用（`content://` 需经 Bridge 的 `fs.readExternal`）。
- WebView `cacheMode = LOAD_NO_CACHE`（无离线缓存）。
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

### 4.0 返回值类型约定

所有接口返回 **Promise**，`await` 后得到以下 JS 类型之一，**不存在其他形式**：

| 类型 | 含义 |
|---|---|
| `boolean` | 操作是否成功，值为 `true` 或 `false` |
| `string` | 文本（文件内容、base64、剪贴板文本） |
| `object` | JSON 对象（如 `{ status, body }`） |
| `array` | JSON 数组（目录列表） |

调用失败时 Promise 被 reject，捕获到的 `Error.message` 为错误原因。

### 4.1 应用与系统

| 接口 | 返回类型 | 返回值 |
|---|---|---|
| `MiniApp.info()` | `object` | `{ uid, uname, version, entry, permissions, appKey }` |
| `MiniApp.system()` | `object` | `{ platform, model, manufacturer, brand, osVersion, sdk, hostAppVersion, density, densityDpi, widthPixels, heightPixels }`（`sdk`、`densityDpi`、`widthPixels`、`heightPixels` 为整数，`density` 为浮点数） |
| `MiniApp.ui.toast(msg)` | `boolean` | 固定 `true` |

`MiniApp.toast(msg)` 与 `MiniApp.ui.toast(msg)` 等价。

```js
const info = await MiniApp.info();       // object
const ok = await MiniApp.ui.toast('提示'); // boolean true
```

### 4.2 文件系统（沙箱内）

路径**相对沙箱根**（`<沙箱>/`），不是相对应用根。

**白名单规则**：写操作（`write`/`writeBytes`/`mkdir`/`remove`）只允许 `data/` 和 `tmp/`，禁止写 `app/`（只读资源区）。读操作允许 `app/`、`data/`、`tmp/`。

| 接口 | 返回类型 | 返回值 |
|---|---|---|
| `fs.read(path)` | `string` | 文件文本内容 |
| `fs.readBytes(path)` | `string` | base64 编码的文件字节 |
| `fs.write(path, content)` | `boolean` | 成功 `true` |
| `fs.writeBytes(path, base64)` | `boolean` | 成功 `true` |
| `fs.list(dir)` | `array` | `[{ name: string, isDir: boolean, size: number }]` |
| `fs.exists(path)` | `boolean` | 存在 `true`，不存在 `false` |
| `fs.stat(path)` | `object` | `{ exists: boolean, isDir: boolean, size: number, name: string, canRead: boolean, canWrite: boolean, lastModified: number }`（`lastModified` 为 Unix **毫秒**时间戳；**精度取决于底层文件系统**，多数 Android 文件系统为秒级，末三位可能恒为 `000`） |
| `fs.mkdir(path)` | `boolean` | 成功 `true` |
| `fs.remove(path)` | `boolean` | 成功 `true`（目录递归删除），失败 `false` |

```js
const text = await MiniApp.fs.read('data/test.txt');        // string
const b64  = await MiniApp.fs.readBytes('app/image.png');   // string (base64)
const ok   = await MiniApp.fs.write('data/test.txt', 'Hi'); // boolean true
const arr  = await MiniApp.fs.list('data');                 // array
const has  = await MiniApp.fs.exists('data/test.txt');      // boolean
const st   = await MiniApp.fs.stat('data/test.txt');        // object
await MiniApp.fs.mkdir('data/sub');                         // boolean true
await MiniApp.fs.remove('data/tmp');                        // boolean
```

#### SAF 导入导出（无需权限）

| 接口 | 返回类型 | 返回值 |
|---|---|---|
| `fs.importFile(destPath)` | `boolean` | 导入成功 `true`；用户取消时 reject |
| `fs.exportFile(path)` | `boolean` | 导出成功 `true`；用户取消时 reject |

```js
await MiniApp.fs.importFile('data/imported.bin');  // boolean true
await MiniApp.fs.exportFile('data/report.txt');    // boolean true
```

#### 静默操作内部储存（需 `fs.external` 权限）

声明 `fs.external` 后，可静默操作内部储存（`/storage/emulated/0`）。

**安全限制**：禁止访问应用私有目录（`/data/data/...`），防止篡改权限记录。路径为**绝对路径**。

**权限适配**（蜗壳自动处理）：
- Android 10 及以下：首次调用时蜗壳主动弹窗申请运行时存储权限。
- Android 11+：`MANAGE_EXTERNAL_STORAGE` 需在系统设置开启「所有文件访问」，蜗壳会自动跳转设置页，开启后返回重试。

| 接口 | 返回类型 | 返回值 |
|---|---|---|
| `fs.readExternalFile(absPath)` | `string` | base64 编码的文件字节 |
| `fs.writeExternalFile(absPath, base64)` | `boolean` | 成功 `true` |
| `fs.listExternal(dir)` | `array` | `[{ name: string, isDir: boolean, size: number }]`（不递归） |
| `fs.existsExternal(absPath)` | `boolean` | 存在 `true`，不存在 `false` |
| `fs.statExternal(absPath)` | `object` | 同 `fs.stat` 的字段（`lastModified` 精度同样取决于文件系统） |
| `fs.mkdirExternal(dir)` | `boolean` | 成功 `true`；已存在 `true` |
| `fs.removeExternal(absPath)` | `boolean` | 成功 `true`（目录递归删除），失败 `false` |
| `fs.renameExternal(from, to)` | `boolean` | 成功 `true`，失败 `false` |
| `MiniApp.call('fs.readExternal', { uri })` | `string` | 读取 content:// URI，返回 base64 |

```js
const b64  = await MiniApp.fs.readExternalFile('/storage/emulated/0/Documents/a.txt'); // string
await MiniApp.fs.writeExternalFile('/storage/emulated/0/Documents/b.txt', base64);     // boolean
const arr  = await MiniApp.fs.listExternal('/storage/emulated/0/Documents');           // array
const has  = await MiniApp.fs.existsExternal('/storage/emulated/0/Documents/a.txt');   // boolean
const st   = await MiniApp.fs.statExternal('/storage/emulated/0/Documents/a.txt');     // object
await MiniApp.fs.mkdirExternal('/storage/emulated/0/Documents/sub');                  // boolean
await MiniApp.fs.removeExternal('/storage/emulated/0/Documents/old_dir');             // boolean
await MiniApp.fs.renameExternal('/storage/emulated/0/a.txt', '/storage/emulated/0/b.txt'); // boolean
```

### 4.3 WASM 执行

蜗壳 WASM 基于 **WebView 内置 WebAssembly JIT**，无额外解释层。

| 接口 | 返回类型 | 返回值 |
|---|---|---|
| `wasm.instantiate(pathOrBytes, imports)` | `Promise<WebAssembly.Instance>` | 异步返回实例 |
| `wasm.createMemory(pages, maxPages)` | `WebAssembly.Memory` | 共享内存对象 |

> **重要**：运行在 `file://` 协议下，`fetch('x.wasm')` 会被沙箱拦截。
> `instantiate` 传路径字符串时，**内部通过 JS Bridge `fs.readBytes` 读取**（不走 `fetch`），可直接使用。
> 也可自行先 `MiniApp.fs.readBytes` 读取字节再实例化。

```js
// 方式 A：直接传路径（内部经 Bridge 读取，路径相对沙箱根）
const instance = await MiniApp.wasm.instantiate('app/heavy.wasm', imports);
```

```js
// 方式 B：自行读取字节后实例化（路径相对沙箱根）
const b64 = await MiniApp.fs.readBytes('app/heavy.wasm');   // string (base64)
const bin = atob(b64);
const bytes = new Uint8Array(bin.length);
for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);

const memory = MiniApp.wasm.createMemory(256);              // WebAssembly.Memory
const instance = await MiniApp.wasm.instantiate(bytes, {
  env: {
    memory: memory,
    log: (ptr, len) => {
      const bytes = new Uint8Array(memory.buffer, ptr, len);
      console.log('[WASM]', new TextDecoder().decode(bytes));
    }
  }
});
instance.exports.compute(42);
```

### 4.4 网络（需审批）

| 接口 | 返回类型 | 返回值 |
|---|---|---|
| `net.get(url)` | `object` | `{ status: number, body: string }` |
| `net.post(url, body)` | `object` | 同上 |
| `net.put(url, body)` | `object` | 同上 |
| `net.delete(url)` | `object` | 同上 |
| `net.request(method, url, opts)` | `object` | 同上 |

`status` 为 HTTP 状态码（整数），`body` 为响应文本（字符串）。`opts` 为 `{ headers: object, body: string }`。支持 GET/POST/PUT/DELETE/PATCH 等任意方法。

**Content-Type 行为**：宿主**不会**自动添加 `Content-Type`。若不通过 `headers` 显式指定，底层 `HttpURLConnection` 的默认值为 `application/x-www-form-urlencoded`（这是 Android 平台的默认行为，可能导致服务端按表单而非 JSON 解析请求体）。因此发送 JSON 时**必须显式指定**：

```js
await MiniApp.net.post(url, JSON.stringify({ hello: '蜗壳' }), {
  headers: { 'Content-Type': 'application/json' }
});
```

> 注：`net.post(url, body)` / `net.put(url, body)` 便捷方法不接受 headers 参数。需要自定义请求头时请用 `net.request(method, url, opts)`。
>
> 底层分发方法为 `net.httpRequest`。另有兼容别名 `net.httpGet`（等价于 `net.request('GET', url)`），仅供旧代码直接调用 `MiniApp.call('net.httpGet', { url })`，新代码请用 `net.get`。

需声明 `"net"`；首次调用弹窗审批，授权持久化。

```js
const res = await MiniApp.net.get('https://example.com/api');  // { status, body }
const res2 = await MiniApp.net.request('PATCH', url, {
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ patch: true })
});
```

### 4.5 剪贴板（需审批）

| 接口 | 返回类型 | 返回值 |
|---|---|---|
| `clipboard.read()` | `string` | 剪贴板文本；剪贴板为空时返回 `""` |
| `clipboard.write(text)` | `boolean` | 成功 `true` |

需声明 `"clipboard"`。

```js
const text = await MiniApp.clipboard.read();   // string
await MiniApp.clipboard.write('复制内容');     // boolean true
```

### 4.6 通知（需审批）

| 接口 | 返回类型 | 返回值 |
|---|---|---|
| `notification.show(title, body)` | `boolean` | 成功 `true` |
| `notification.cancel()` | `boolean` | 成功 `true` |

需声明 `"notification"`。通知栏以 `[小程序名] 标题` 标注发送来源；每个小程序独立通知渠道。Android 13+ 首次会额外申请系统通知权限（`POST_NOTIFICATIONS`）。

```js
await MiniApp.notification.show('标题', '内容');  // boolean true
await MiniApp.notification.cancel();              // boolean true
```

### 4.7 打开外部链接（需审批）

| 接口 | 返回类型 | 返回值 |
|---|---|---|
| `sys.openUrl(url)` | `boolean` | 成功 `true` |

需声明 `"sys.openUrl"`。

```js
await MiniApp.sys.openUrl('https://example.com');  // boolean true
```

### 4.8 预请求权限

| 接口 | 返回类型 | 返回值 |
|---|---|---|
| `permission.request(scope)` | `boolean` | 已授权/刚授权 `true`，拒绝 `false` |

```js
const granted = await MiniApp.permission.request('net');  // boolean
```

### 4.9 Dex 执行（无需权限）

| 接口 | 返回类型 | 返回值 |
|---|---|---|
| `dex.run(opts)` | `object` | dex 内 `run` 方法返回的 `Bundle` 键值（值转为字符串），并含 `ok: "true"`；失败时含 `error` |

`opts` 字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `dex` | `string` | 是 | dex 文件路径（相对沙箱根，如 `data/plugin.dex`） |
| `className` | `string` | 是 | 入口类全名 |
| `methodName` | `string` | 否 | 静态方法名，默认 `run` |
| `params` | `object` | 否 | 传入参数（键值均按字符串传入 Bundle）**不会**被宿主注入额外键 |
| `input` | `string` | 否 | 输入文件路径（相对沙箱根） |
| `output` | `string` | 否 | 输出文件路径（相对沙箱根） |

```js
const result = await MiniApp.dex.run({
  dex: 'data/plugin.dex',
  className: 'com.example.Plugin',
  methodName: 'run',
  params: { mode: 'fast' },
  input: 'data/in.bin',
  output: 'data/out.bin'
});
// → { ok: "true", /* dex 返回的键值 */ }
```

**无需任何权限**，直接调用即可（隔离进程本身即为安全边界，见下）。

> `className` / `methodName` 由宿主通过内部保留键（`__className` / `__methodName`）传递，**不会**出现在 `params` 中，也不会覆盖调用方同名的 `params` 键。

执行发生在 **`android:isolatedProcess="true"` 的隔离进程**中（这是蜗壳 Dex 沙箱的核心机制）：

- 独立 UID + SELinux `isolated_app` 域，**不继承宿主任何权限**：无网络、无路径访问、无系统服务、不能加载 native 库。
- dex 内**只能使用 Android 框架类与 Java 标准库**；不能引用蜗壳的自定义类。
- 与宿主的唯一通道是 Binder：dex 只能读写主进程通过 FD 传入的 `input`/`output` 文件，**无法主动打开任何路径**，因此不会破坏沙箱。
- dex 内约定的入口方法签名：

```java
public static android.os.Bundle run(
    android.os.Bundle params,
    android.os.ParcelFileDescriptor inputFd,   // 可能为 null
    android.os.ParcelFileDescriptor outputFd   // 可能为 null
);
```

- 能力边界：确定可行——Dex 加载、反射调用、FD 读写、Java 库（压缩/加密/正则/时间）、多线程、Bundle 通信；确定不可行——加载 native 库、执行 ELF、主动 open 路径、网络、系统服务、访问宿主或其他沙箱。
- 性能：无 AOT，首次解释执行；热点 JIT 后接近普通 Java；数值计算慢于 WASM，适合结构化逻辑、加密压缩等场景。

### 4.10 网络存储（需审批）

小程序可把自己产生的数据存到用户在「设置 → 网络存储」中配置的 WebDAV 服务器上。

**存储路径由宿主强制拼装，小程序只能给相对路径**：

```
/MiniAppContainer/data/<uid>_<uname>/<你给的相对路径>
```

- 根文件夹固定为 `MiniAppContainer`（`WebdavConfig.ROOT_FOLDER`）。
- `<uid>_<uname>` 为你的应用身份。**每个小程序有独立目录，彼此无法访问**。
- 相对路径禁止 `.`、`..`（越权直接报错），开头 `/` 会被忽略。

| 接口 | 返回类型 | 返回值 |
|---|---|---|
| `storage.upload(path, base64)` | `boolean` | 成功 `true` |
| `storage.download(path)` | `string` | base64 编码的文件字节 |
| `storage.list(path)` | `array` | `[{ name: string, isDir: boolean }]`（`path` 为 `''` 表示小程序根目录） |
| `storage.delete(path)` | `boolean` | 删除成功 `true`；目标不存在也返回 `true` |

**失败约定**（Promise reject，`Error.message` 为以下之一）：

| message | 含义 |
|---|---|
| `permission denied: storage` | 用户未授予 `storage` 权限（或未声明） |
| `storage not configured` | 已授权，但用户尚未在「设置 → 网络存储」中配置 WebDAV |

```js
// 上传（base64 内容）
await MiniApp.storage.upload('notes/a.txt', btoa('hello'));   // boolean true

// 下载
const b64 = await MiniApp.storage.download('notes/a.txt');     // string (base64)

// 列表（'' = 小程序根目录）
const items = await MiniApp.storage.list('notes');             // [{ name, isDir }]

// 删除
await MiniApp.storage.delete('notes/a.txt');                   // boolean true

// 区分失败原因
try {
  await MiniApp.storage.list('');
} catch (e) {
  if (e.message === 'permission denied: storage') { /* 用户未授权 */ }
  else if (e.message === 'storage not configured') { /* 未配置 WebDAV */ }
}
```

需声明 `"storage"`；首次调用弹窗审批，授权持久化。

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
| `net` | HTTP 请求（GET/POST/PUT/DELETE/PATCH 等） | 拒绝 |
| `sys.openUrl` | 打开外部链接 | 拒绝 |
| `fs.external` | 静默操作内部储存：读写/列目录/查存在/查信息/建目录/删除/重命名 + 读取外部 content:// | 拒绝 |
| `clipboard` | 读写系统剪贴板 | 拒绝 |
| `notification` | 发送状态栏通知 | 拒绝 |
| `storage` | 读写 WebDAV 网络存储（仅小程序自己的目录） | 拒绝 |

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

// 路径相对沙箱根；instantiate 内部经 Bridge 读取字节（不走 fetch）
const instance = await MiniApp.wasm.instantiate('app/app.wasm', imports);
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

// 注意：await 必须在 async 函数内
(async function () {
  try {
    await MiniApp.fs.read('data/missing.txt');
  } catch (e) {
    console.error(e.message);
  }
})();
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

- `MiniAppBridge`：Bridge 调用与错误（Tag 为 `MiniAppBridge`）
- 前端 `console.*` 输出：由 WebChromeClient 转发为 `MiniAppJS`；未接转发时仅出现在 WebView 控制台

> **WASM 不产生宿主日志**：`wasm.instantiate` / `wasm.createMemory` 是 `bridge.js` 中的纯 JS 实现，**不经过 JS Bridge**，因此不会出现在 Bridge 调用日志（调试模式）中。WASM 相关输出请在页面 `console` 查看（`bridge.js` 会输出 `[MiniApp.wasm] instantiated` / `[MiniApp.wasm] instantiate failed:` 前缀的日志）。

```bash
adb logcat -s MiniAppJS MiniAppBridge
```

### 7.4 常见错误

| 错误 | 原因 | 解决 |
|---|---|---|
| `未获得必要权限，无法运行` | 必要权限被拒 | 在弹窗允许，或去权限管理页授予 |
| `permission denied: net` | 未声明 `net` 或用户拒绝 | manifest 声明 `"net"` 并允许 |
| `permission denied: fs.external` | 未声明 `fs.external` 或用户拒绝 | manifest 声明并允许 |
| `permission denied: sys.openUrl` | 未声明 `sys.openUrl` 或用户拒绝 | manifest 声明并允许 |
| `permission denied: clipboard` | 未声明 `clipboard` 或用户拒绝 | manifest 声明并允许 |
| `permission denied: notification` | 未声明 `notification` 或用户拒绝 | manifest 声明并允许 |
| `permission denied: dex` | 未声明 `dex` 或用户拒绝 | manifest 声明并允许 |
| `写操作仅允许 data/ 和 tmp/ 目录: xxx` | 尝试写 `app/` | 写数据放 `data/` 或 `tmp/` |
| `path escapes sandbox: xxx` | 路径含 `..` 逃出沙箱 | 使用沙箱内相对路径 |
| `文件不存在: xxx` / `目录不存在: xxx` | 目标路径不存在 | 先用 `fs.exists` 判断或创建 |
| `禁止访问应用私有目录: xxx` | `fs.external` 访问 `/data/data/` | 只访问内部储存 `/storage/emulated/0` |
| `已跳转系统设置，请开启「所有文件访问权限」后重试` | Android 11+ 未开 `MANAGE_EXTERNAL_STORAGE` | 在设置页开启后返回重试 |
| `[MiniApp.wasm] instantiate failed: ...` | WASM 格式错误或 import 缺失 | 检查 import object 是否提供 `env.memory` 等必需项 |
| `unknown method: xxx` | 方法名拼写错误 | 对照本手册 API 清单 |
| `permission denied: storage` | 未声明 `storage` 或用户拒绝 | manifest 声明并允许 |
| `storage not configured` | 已授权但未配置 WebDAV | 在「设置 → 网络存储」中填写地址 |
| `dex 文件不存在: xxx` | `dex` 路径不对 | 路径相对沙箱根，确认 zip/`data/` 含该 dex |

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
- [ ] `uid` / `uname` 不含 `/`、`\`、控制字符，且不是空串、`.` 或 `..`，长度 ≤64
- [ ] `entry` 指向的 HTML 文件存在
- [ ] `wasm` 列出的模块文件存在且路径正确
- [ ] `icon` 路径（若设置）指向存在的 SVG/PNG 文件
- [ ] `requiredPermissions` 的每一项都同时出现在 `permissions` 中
- [ ] `permissions` 声明了所有能力
- [ ] 前端通过 `window.MiniApp` 调用，而非直接 `fetch`/`XMLHttpRequest`
- [ ] WASM 实例化提供完整 `import object`（含 `env.memory`）
- [ ] 写文件只写 `data/` 或 `tmp/`，不写 `app/`
