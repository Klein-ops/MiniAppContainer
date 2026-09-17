# 蜗壳 · 项目文档

> 本文档面向**蜗壳自身的开发与维护者**：架构、内部机制、集成要求、构建发布约定。
> 小程序开发者请看 [DEVELOPER.md](DEVELOPER.md)。

---

## 一、项目概况

蜗壳（MiniAppContainer）是一个 **Android 本地小程序容器**：宿主 App 用系统 WebView 渲染
HTML/CSS/JS，小程序以 zip 包安装，每个小程序拥有独立沙箱；前端通过 JS Bridge 调用宿主
本地能力；高性能计算由 WebView 内置 WebAssembly JIT 完成；`dex.run` 在隔离进程内执行
Android dex。

- 语言 / 构建：Kotlin + Gradle（AGP 8.5，JDK 17），compileSdk 34 / minSdk 26
- 无原生层（无 NDK/CMake）
- 关键三方依赖：OkHttp 4.12（WebDAV / 网络）、Shizuku API 13.1.5（ADB 能力）

### 源码结构

```
app/src/main/java/com/miniapp/container/
├─ MiniAppApp.kt            应用级单例：持有 sandbox / registry / installer /
│                            permissionManager / categoryManager / backupService
├─ bridge/      MiniAppBridge.kt        唯一的 JS Bridge 入口（@JavascriptInterface call）
├─ core/        AppInstaller            安装 / 更新 / 卸载 / 从备份恢复
│               AppManifest             manifest.json 解析
│               AppRegistry             已安装应用注册表（registry.json）
│               SandboxManager          沙箱目录布局（app/ data/ tmp/ meta.json）
│               PathGuard               路径校验（防 `..` / 符号链接穿越）
│               MetaInfo                沙箱 meta.json 模型
│               MiniAppInfo             注册表条目模型
│               CategoryManager         分类与排序（categories.json）
│               BackupService           本地 zip / WebDAV 备份与恢复
├─ permission/  PermissionRegistry      **权限注册表（框架核心）**
│               PermLevel               三级：SAFE / NORMAL / DANGEROUS
│               PermissionScope         常量与转发
│               PermissionManager       审批流程（ensurePermission）
│               PermissionStore         授权持久化（permissions.json）
│               PermissionDialogFragment 审批对话框
├─ service/     NetService / ClipboardService / NotificationService /
│               ExternalFileService / StorageService / AdbService
├─ file/        FileService             沙箱内文件操作
├─ web/         MiniAppWebViewClient    请求拦截（白名单 file://）
│               MiniAppWebChromeClient  console 转发、文件选择
│               FloatingExitView        可拖动悬浮退出球
├─ dex/         DexService / DexRunner  隔离进程 dex 执行
├─ netdisk/     WebdavClient / WebdavConfig
├─ provider/    MiniAppDocumentsProvider  SAF 数据开放
├─ debug/       DebugBus                接口调用日志总线（内存环形缓冲）
├─ ui/          各 Activity / Adapter
└─ util/        IoUtil / PathGuard 辅助 / TextEditor / RoundedDialog / 扩展
```

---

## 二、架构要点

### 渲染与桥

- 所有请求经 `MiniAppWebViewClient` 拦截，仅放行**当前沙箱**内的 `file://`，其余 403。
- 唯一入口 `MiniAppNative.call(reqId, method, params)`；异步 `__resolve(reqId, result)` 回传。
- `assets/bridge.js` 把 `window.__MiniAppBridge` 封装为 Promise 化的 `window.MiniApp`。
- 分发集中在 `MiniAppBridge` 的 `when (method)`；**新增接口 = bridge.js 加方法 + 加分发分支**。

### 沙箱

```
<filesDir>/miniapps/<uid>_<uname>/
├─ app/    前端资源（只读）
├─ data/   读写数据
├─ tmp/    临时文件
└─ meta.json
```

- `appKey = uid + "_" + uname`；`uid`+`uname` 完全一致视为同一应用（更新），否则是全新应用。
- 更新只替换 `app/`，保留 `data/`、`tmp/` 与 `displayName`。
- 卸载清除沙箱 + 权限记录 + 通知渠道 + 分类引用。
- 备份恢复走 `AppInstaller.restoreFromBackup()`（权限从重新解压的 manifest 读取）。

### 调试总线

`DebugBus` 记录每次 Bridge 调用的方法、参数、返回值与耗时，环形缓冲 500 条。

**日志留存规则**：日志只存在内存中，清空只有两种时机——① 蜗壳进程结束（被从后台划掉时
自然消失）；② 在「调用日志」页手动点「清空」。关闭「调试模式」开关**不会**清空日志，
只是停止记录新日志。

---

## 三、权限框架

### 设计

权限是**注册表驱动**的：所有权限定义集中在 `PermissionRegistry`，审批流程、必要权限过滤、
审批弹窗警告、权限管理页标签、`PermissionScope` 转发值均由此自动派生。

等级（`PermLevel`）：

| 等级 | 行为 |
|---|---|
| `SAFE` | 无需审批，调用即可用（也无需在 manifest 声明） |
| `NORMAL` | 需用户审批 |
| `DANGEROUS` | 需审批 + 审批界面显示警告；**不可作为必要权限**（声明会被忽略） |

「危险权限不可作必要权限」是一项保护：避免小程序用"不授权就用不了"胁迫用户交出高危能力。
落实在两处：安装/恢复时 `PermissionRegistry.sanitizeRequired()` 剔除，必要时 `MainActivity`
再冗余过滤一次（防旧数据）。

### 新增一个权限

**第 1 步**：在 `PermissionRegistry.defaults` 追加一条定义

```kotlin
PermissionDef(
    scope = "camera",                       // 与 manifest permissions 里的字符串一致
    label = "相机",                          // 审批弹窗标题 / 权限管理页显示
    description = "该小程序请求使用相机。",     // 审批弹窗正文
    level = PermLevel.NORMAL,               // SAFE / NORMAL / DANGEROUS
    warning = ""                            // DANGEROUS 时的额外警告文案
)
```

**第 2 步**：在服务代码里调用 `ensurePermission(...)`

```kotlin
val ok = permissionManager.ensurePermission(
    activity, appInfo.appKey, appInfo.permissions, "camera"
)
if (!ok) throw SecurityException("permission denied: camera")
```

**自动生效**（无需改动）：审批是否弹窗、危险权限的必要权限剔除、审批弹窗警告框、
权限管理页等级标签、`PermissionScope.label/description/warning`。

> 同时记得在 `DEVELOPER.md` 的「5.3 权限范围」与「7.4 常见错误」补上对应条目。

---

## 四、宿主集成要求

### Shizuku（`adb.exec`）

`adb.exec` 依赖 Shizuku。Shizuku 的 binder 由**服务端主动推送**给客户端——服务端通过
`ContentResolver.call(METHOD_SEND_BINDER)` 调用客户端声明过的 `ShizukuProvider`。
而 provider AAR 的 manifest 只含 uses-permission 与 meta-data，**不含 `<provider>`**，
必须由使用方显式声明，否则 `Shizuku.pingBinder()` 恒为 false、授权弹窗也不会出现。

```xml
<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />

<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:enabled="true"
    android:exported="true"
    android:multiprocess="false"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

（provider 的 `attachInfo` 会强制校验 `multiprocess=false`、`exported=true`。）

**风险提示**：Shizuku 官方无公开的 shell 执行 API（`Shizuku.newProcess` 自 API 13 起为
private 并计划于 API 14 移除，`bindUserService` 又要求调用方提供 Service 组件）。
`AdbService` 直接调用其内部 AIDL `IShizukuService.newProcess`，**依赖内部接口**，
Shizuku 大版本升级可能失效。

其他集成要点：

- binder 推送是异步的，应用刚启动时可能尚未到达 —— `AdbService.awaitShizukuState()`
  会短暂轮询（最多 2s）再判定"未激活"，避免误报。
- 执行时并发读取 stdout/stderr（两个读线程 + `CountDownLatch`），避免单流写满管道缓冲死锁。
- 超时由小程序指定（`opts.timeout`，默认 30000，夹紧 1000~600000），超时强杀进程。

---

## 五、备份与恢复

### 备份内容（刻意精简）

**只含小程序资源与应用数据**，不含权限声明、权限授权记录、分类、`meta.json`。

```
backup.json                       { format, includeData, apps:[{uid,uname,version,entry,wasm,icon,displayName,appKey}] }
miniapps/<appKey>/app/...         应用资源
miniapps/<appKey>/data/...        应用数据（includeData=true 时）
```

恢复时权限**从重新解压的 `app/manifest.json` 读取**，保证与资源一致。

### WebDAV

- 根文件夹固定 `MiniAppContainer`（`WebdavConfig.ROOT_FOLDER`），必须为 ASCII：
  备份在 `/MiniAppContainer/backup/`，小程序数据在 `/MiniAppContainer/data/<uid>_<uname>/`。
- 采用 OkHttp（`HttpURLConnection` 不支持 `PROPFIND`/`MKCOL`，WebDAV 依赖它们）。
- 压缩等级由用户在「网络存储」页设置（0-9，默认 6），经 `ZipOutputStream.setLevel` 应用。
- 备份列表支持单文件删除（行内删除按钮 + 确认）。

### 本地 SAF 数据开放

`MiniAppDocumentsProvider` 通过 DocumentsProvider 把应用私有目录暴露给其他应用，
根文档标题固定为 ASCII 的 `MiniAppContainer Data`（避免非 ASCII 在部分文件管理器出现兼容问题）。

---

## 六、UI 规范

### 颜色

集中定义于 `res/values/colors.xml`：`brand_primary` / `brand_primary_dark` / `brand_accent` /
`bg_window` / `bg_card` / `divider` / `text_primary` / `text_secondary` / `danger`。
**不要**在布局里写死颜色十六进制值（`bg_warning.xml` 的浅红除外）。

### 设置类页面：统一卡片

设置页与应用设置页的所有条目统一使用样式 `Widget.MiniApp.SettingCard`
（MaterialCardView：16dp 圆角、1dp `divider` 描边、0 阴影、可点击涟漪）。

行内结构统一为：`24dp 图标（brand_primary 着色）` + `标题 15sp` + `可选副标题 12sp` +
`可选 SwitchCompat`。危险操作（清空数据、卸载）改用 `danger` 着色。

**不要**在设置类页面里混用 `MaterialButton` 与卡片。

### 输入弹窗

需要用户输入文本的对话框**必须**经 `util/InputDialog.create(context, hint, ...)` 构造视图，
不要 `setView(EditText(this))` —— 裸 `EditText` 会贴到卡片边缘（下划线与文字顶边）。
`InputDialog` 内部用 `TextInputLayout`（outlined）+ 24dp 水平内边距，并支持 `inputType`：

```kotlin
val (view, input) = InputDialog.create(this, "分类名称")
MaterialAlertDialogBuilder(this).setTitle("新建分类").setView(view)
    .setPositiveButton("创建") { _, _ -> /* input.text */ }
    .setNegativeButton("取消", null).showRounded()
```

### 圆角对话框

对话框圆角在部分 ROM 上会被系统/Material 覆盖成直角，因此统一经
`util/RoundedDialog.apply(dialog)` 显式校正：

- 若窗口背景是 `MaterialShapeDrawable`（Material 自身绘制），直接改其圆角为 24dp
  ——保留 Material 内边距，不影响布局；
- 否则用圆角矩形（`GradientDrawable`，圆角 24dp）替换窗口背景。

新增对话框时**用 `MaterialAlertDialogBuilder(...).showRounded()`** 代替 `.show()`；
`DialogFragment` 在 `onCreateDialog` 里对 `create()` 出的实例显式调用 `RoundedDialog.apply(dialog)`。

主题侧配合：`Theme.MiniApp` 的 `materialAlertDialogTheme` 指向
`ThemeOverlay.MiniApp.RoundedDialog`（父级为 `ThemeOverlay.MaterialComponents.MaterialAlertDialog`，
`shapeAppearanceOverlay` 指定 24dp）。

### 其他

- 页面切换动画、列表项级联淡入、导航切换淡入定义在 `res/anim/` 与 `themes.xml`。
- 小程序的界面：全屏 WebView + 顶部进度条 + 可拖动悬浮退出球（闲置 3s 自动贴边收起）。
- 所有会遮挡内容的操作入口必须放在可滚动容器内（极小屏可用性）。

---

## 七、构建与发布

### 构建

```bash
cd MiniAppContainer
./gradlew assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
```

要求 JDK 17、Android SDK（compileSdk 34）。无 NDK/CMake。

### 版本号规则

- 已发布正式版从 `1.0.0`（`versionCode` 19）起。
- 正式版更新递增 `versionName`（`1.x.0`），`versionCode` 递增整数；小修补用 `1.x.y`。
- 在 `app/build.gradle` 的 `defaultConfig` 中修改，**每次更新都要同步递增**。

### Git 约定

提交者固定为项目历史中的真实提交者：

```bash
git -c user.name="Klein-ops" -c user.email="15305420968@163.com" commit -m "..."
```

### 提交前自检

无法在本机编译时，至少跑一遍静态检查：

- 所有 `.kt` 的括号/引号配平（含注释、字符串、三引号字符串）
- `R.id.*` / `R.layout.*` / `R.drawable.*` 引用在 `res/` 中存在
- 扩展函数（`optStringOr` / `showRounded` 等）是否已 import
- 文档中提到的行为与代码是否一致（**改行为时全书 grep 相关描述**）

---

## 八、文档约定

| 文档 | 面向 | 内容 |
|---|---|---|
| `README.md` | 使用者 | 项目简介、构建、运行、功能概览 |
| `DEVELOPER.md` | **小程序开发者** | 只讲如何开发/调试小程序：包结构、manifest、JS Bridge API、权限（开发者视角）、WASM、Dex、调试、更新规则、检查清单 |
| `PROJECT.md`（本文） | 项目维护者 | 架构、内部机制、集成要求、权限框架扩展、UI 规范、构建发布 |

**写文档时注意**：

- `DEVELOPER.md` 里不要出现维护者内容（如何新增权限、宿主集成要求、内部机制原理）。
- 不要出现与调用无关的评语、设计理由、括号旁白（如"因此不会破坏沙箱"）。
- 任何影响小程序行为的改动，都要同步更新 `DEVELOPER.md` 对应章节与错误表。
