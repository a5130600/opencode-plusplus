# opencode mobile — Android 客户端

手机端遥控电脑上的 `opencode`：对话、接收产物、一键审批权限。

**没有中转服务器。** App 只做 `opencode serve` 的第三方客户端，
所有请求从这台手机直接打到你自己的电脑上。

---

## 一、这个工程现在能做什么

| 能力 | 状态 |
|---|---|
| 设备管理（私网地址 + Basic 密码，凭据进 Keystore 加密存储） | ✅ 已完成 |
| 工作区同步（拉电脑端 `GET /project`，手机只选不造） | ✅ 已完成 |
| 会话列表 / 新建 / 状态校准（REST 兜底，不只靠 SSE） | ✅ 已完成 |
| 会话内对话（`prompt_async` 下发，SSE 回流，可中止） | ✅ 已完成 |
| 权限审批（会话内卡片 + 通知栏一键批，两处共用一份常量） | ✅ 已完成 |
| 模型切换（只列服务端 `connected` 的 provider） | ✅ 已完成 |
| 产物：已缓存列表 + 浏览电脑文件树 | ✅ 已完成 |
| 文件预览：代码/Markdown/图片/PDF/CSV/HTML | ✅ 已完成 |
| Office（docx/xlsx/pptx）：需要转换，给出可行路径 | ⚠️ 有意留白，见下文 |
| 三层缓存 + LRU 淘汰 + 离线保存 + 分享 | ✅ 已完成 |
| 前台服务保活 + 断线指数退避重连 + 活性探测 | ✅ 已完成 |
| 可编译出 APK | ✅ 已通过（本机命令行工具链，见第二节） |

---

## 二、构建

**已在本机命令行完整验证通过**（`clean assembleDebug`，42/42 任务全量执行），不需要 Android Studio。

工具链（都装在纯 ASCII 路径下，无需管理员）：

| 组件 | 位置 | 版本 |
|---|---|---|
| JDK | `D:\dev\jdk-17`（示例） | 17.0.20.1 |
| Android SDK | `D:\dev\sdk`（示例） | platform-tools / platforms;android-35 / build-tools;35.0.0 |
| Gradle | 由 wrapper 管理 | 8.9 |

```bash
# 标准入口：走工程自带的 wrapper（版本随工程走，不随本机走）
./gradlew assembleDebug

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

`sdk.dir` 在 `local.properties`（按本机实际 SDK 位置填写，该文件已被 gitignore）。
`gradle-wrapper.jar` 已补齐，`gradlew` 可以直接用。

### 版本选择是有依据的，别乱升

**AGP 8.7.3 + Gradle 8.9 + Kotlin 2.0.20。** 选型公式是「先定 compileSdk，再定 AGP，最后定 Gradle」：

- AGP **只保证支持到它发布时的 compileSdk**。我们用 35，而 AGP 8.5.2 只测到 34 —— 之前会打兼容性警告（能过但不干净）。
- compileSdk 35 需要 **AGP ≥ 8.6.0**，选 8.7.x 是因为它要求的 Gradle 下限**正好是 8.9**，与工程既有 wrapper 声明一致，不必连带改 Gradle。
- 再往上（8.8+）就要把 Gradle 升到 8.10.2+，收益不抵风险。

升级后那个警告已消失，现在只剩一条 `android.overridePathCheck` 的 experimental 提示 —— 那是我们**主动**开的，见下。

### 四个环境坑（换机器时会重犯）

1. **`sdkmanager` 已废弃**，`platforms;android-35` 会被拆成两个包名报 not found。
   新版 `android.exe sdk install` 又会**静默卡死** —— 它要去取索引，而本机到
   `dl-ssl.google.com` 路由不通，于是既不报错也不前进（实测挂 9 分钟零输出）。
   **可靠做法：直接从 `dl.google.com` 的仓库清单拉 zip 解压**。
2. **判定平台装好没有要看 `source.properties`**，不能看 `android.jar` ——
   半成品安装会留下只有 jar 的目录，AGP 报 `Failed to find target with hash string 'android-35'`。
3. **项目路径含中文会被 AGP 拒绝**，已在 `gradle.properties` 加
   `android.overridePathCheck=true`。若将来 aapt2 在资源阶段报怪错，**第一个怀疑这一行**。
4. **build-tools 的 zip 解压出来顶层是 `android-15/`**（内部 revision 序号，不是 API level），
   必须改名成 `35.0.0` 才能被认出。

顺手把工程里那个**声明了却没生效**的 `org.gradle.configuration-cache=true` 也接通了
（构建脚本原先一直在用 `--no-configuration-cache` 覆盖它）。现在增量构建约 7 秒。
排障时若想排除配置缓存这个变量，设 `OC_NO_CONFIG_CACHE=1`。

---

## 三、电脑端准备

```bash
# 1) 装 Tailscale（手机和电脑登录同一个账号），记下电脑的私网 IP：
tailscale ip -4          # 形如 100.x.y.z

# 2) 带密码启动 opencode server，监听所有网卡
OPENCODE_SERVER_PASSWORD='换成你自己的强随机密码' opencode serve --host 0.0.0.0 --port 4096
```

然后在 App 的「设置 → 设备 → 添加设备」里填：

| 字段 | 值 |
|---|---|
| 名称 | 随便，比如「台式机」 |
| 私网 IP | `100.x.y.z`（Tailscale 分配的，**不是** 192.168.x.x） |
| 端口 | `4096` |
| 用户名 | `opencode` |
| 密码 | 上面那个 `OPENCODE_SERVER_PASSWORD` |

### 三条安全红线

1. **绝不要**把 4096 端口映射到公网。只在 Tailscale/WireGuard 私网里可达。
2. **必须**设 `OPENCODE_SERVER_PASSWORD`。空密码 = 内网里任何人可执行任意命令。
3. **不要**加 `--dangerously-skip-permissions`。审批流是这个 App 的核心价值。

---

## 四、接口字段核对结论（已完成）

此前手写的 5 处"待核对"字段，已对着 opencode 官方生成的类型定义
（`packages/sdk/js/src/gen/types.gen.ts`）**逐字段核对完毕**。
结论：**几乎全错**，已全部修正。核对的完整记录写在 `dto/Dtos.kt` 顶部注释里。

| 位置 | 原以为 | 实际 |
|---|---|---|
| `prompt_async` 请求体 | 顶层 `providerID` / `modelID` | **嵌套** `model: { providerID, modelID }`。传错服务端会静默用默认模型 |
| `GET /session/{id}/message` | `Message[]`（自带 parts） | `Array<{ info, parts }>` **信封**，parts 与 info 平级 |
| 事件 `message.updated` | properties 就是消息 | properties 是 `{ info }` |
| 流式输出 | 靠 `message.updated` | **`message.part.updated`**（`{ part, delta? }`）—— 这是真正的流式通道 |
| `/file/content` | 原始字节流，可 `Range` 续传 | **JSON**（二进制走 base64）。故无 `@Streaming`、无断点续传 |
| `/file` 列表 | 有 `size` / `mtime` | **没有**。只有 `{name, path, absolute, type, ignored}` |
| `session diff` | `{path, patch, ...}` | `{file, before, after, additions, deletions}` |
| 事件 `type` | 子串匹配即可 | 必须**精确匹配**（`permission.updated`、`session.status` …） |
| `permissions` 的 `response` | ? | ✅ 确认无误：`"once" \| "always" \| "reject"` |

顺手发现的两个纯 bug：`Project` 类型**没有 `name` 字段**；
`Path` 是 `{state, config, worktree, directory}`（原来的 `path`/`cwd` 都不存在）。

**还发现一个没实施的点**：几乎所有端点上都有 `directory?: string` 查询参数，
它是**读**操作的目录作用域。但**没有"切换电脑当前项目"的端点**（`/project/current` 只读）。
目前一律不传 `directory`（= 服务端默认 = 电脑真实当前项目），
因为本 App 的定位是"看住正在跑的那一个"。将来要做多工作区面板，接口层已留好口子。

> 要再核对时**直接读官方 `types.gen.ts`**，不要凭文档或直觉猜。
> 本机留了一份：opencode 仓库的 `packages/sdk/js/src/gen/types.gen.ts`。

---

## 五、工程结构

```
app/src/main/java/com/opencode/mobile/
├── core/                     # AppResult(Ok/Err)、协程限定符、权限响应常量
├── data/
│   ├── remote/               # Retrofit 接口、运行时改写 host 的拦截器、手写 SSE 客户端
│   ├── local/                # Room（device/session/message/cached_file/workspace）+ Keystore 加密存储
│   ├── storage/              # 三层缓存：BlobStore / CacheEvictor / ShareStaging
│   └── repository/           # 6 个仓库，SSE 归一化与重连在 ConnectionRepository
├── di/                       # Hilt 模块
├── service/                  # 前台服务：保活 + 权限通知（带批准/拒绝按钮）
└── ui/
    ├── theme/                # 设计令牌（配色 + 两条缓动曲线）
    ├── components/           # StatusChip / ConnectionBar / UsageMeter …
    ├── nav/                  # AppRoot（AnimatedContent 转场）+ AppViewModel
    ├── sessions/             # 会话列表
    ├── chat/                 # 对话页：消息流 + 权限卡 + composer
    ├── artifacts/            # 产物页、文件查看器、无依赖 Markdown 渲染
    └── settings/             # 设置页 + 三个底部弹层（设备/工作区/模型）
```

### 三个刻意的设计决定

**1. 彩色只表示状态，不表示操作。**
主按钮用近黑而不是品牌色 —— 工具型产品里，彩色应该留给「运行中 / 待审批」
这类真正要抓眼球的信息。

**2. 只准两条缓动曲线。**
位移用 `Emphasized (0.32,0.72,0,1)`，透明度/缩放用 `Standard (0.22,1,0.36,1)`。
曲线一多，产品立刻显得业余。

**3. SSE 不是真相源。**
手机切后台、锁屏、隧道抖动都会丢事件，所以进列表/进会话时
必须用一次 REST（`session/status`）把状态校准回来，否则界面会一直卡在「运行中」。

---

## 六、缓存的三层布局

```
cacheDir/oc/          易失：系统可清、App 可 LRU 淘汰
├── raw/              临时预览的原始字节
├── render/           转换产物（可再生产物，最先被淘汰）
├── thumb/            缩略图
└── tmp/              下载中的 .part 分片

filesDir/            持久：只有用户显式"离线保存"才进来
├── offline/          离线文件（永不参与自动淘汰）
└── share/            分享暂存（FileProvider 只暴露这一个目录）
```

`cacheDir` 与 `filesDir` 在同一文件系统上，所以「离线保存」是一次
**原子 rename**，零拷贝、不占额外空间、中途失败不会留两份。

淘汰顺序固定为 **render → thumb → raw**：永远先牺牲重建成本最低的那一份。

**缓存身份 = `sha256(deviceId | remotePath)`，只是路径。**

这一点是核对接口之后**改过的设计**，值得记一笔。最初想做内容指纹
`sha256(deviceId|path|size|mtime)`，好处是"文件一改就自动失效重建"。但服务端的
`FileNode` **既不返回 `size` 也不返回 `mtime`**，那个指纹里有两个字段是编出来的 ——
**编出来的精度比没有精度更危险**，它会让"永远读不到过期内容"这句话变成假的。

于是拆成三件各司其职的机制：

| 职责 | 机制 |
|---|---|
| 身份 | 路径。同一条远端路径 = 同一个 blobId，跨会话天然复用，也天然免疫路径穿越 |
| 完整性 | 下载完成后算 sha256 存进 `content_hash`：校验落盘是否完整，以及下次重取时判断内容到底变没变 |
| 新鲜度 | `fetched_at` + 10 分钟 TTL，外加 SSE 的 `file.edited` 事件主动失效 + 查看器里的手动刷新 |

**下载是原子操作，没有断点续传。** `/file/content` 返回的是一整个 JSON
（二进制还是 base64 塞在字符串里），既不能流式读也没法 `Range`。
所以落盘一律 `.part` + 原子 `rename`，要么全有要么全无；
单次内容上限 `MAX_CONTENT_BYTES = 64MB`（整份内容要过 base64 字符串和字节数组两次，
峰值内存约为文件的 3 倍）。也正因为没有字节流，查看器用的是**不确定进度条**，
不假装知道百分比。

---

## 七、Office 文件为什么没有端上渲染

docx/xlsx/pptx 是 ZIP + XML，Android 没有任何内置能力直接渲染。
两条真正可行的路都需要额外代价：

- **端上渲染**：WebView 里跑 mammoth.js / SheetJS —— 要往 assets 里塞几百 KB 的 JS。
- **电脑端渲染**：`soffice --headless --convert-to pdf` 再回传 —— 要电脑装 LibreOffice。

所以 `FileViewerScreen.kt` 里的 `OfficePanel` **不假装能渲染**，
而是把「让 AI 读」和「分享到其他应用」两个真正能走通的出口摆出来。
要接端上渲染，在 `FileContent` 的 `needsConversion` 分支里换成 WebView 即可，
渲染骨架（含 `loadKey` 防重复加载）已经就位。

---

## 八、已知的下一步

- [x] ~~核对接口字段~~ —— 已完成，见第四节
- [x] ~~本机命令行编译~~ —— 已完成，`clean assembleDebug` 全量通过
- [x] ~~升 AGP 到 8.7.x~~ —— 已完成，compileSdk 35 兼容性警告消失
- [x] ~~补 `gradle-wrapper.jar`~~ —— 已完成，`./gradlew` 可用
- [x] ~~补 `.gitignore`~~ —— 已完成（根目录 + `android/`）
- [ ] 真机联调：远端跑 `opencode serve` + 设 `OPENCODE_SERVER_PASSWORD`，走一遍 M1–M4
- [ ] 会话列表的产物入口（现在产物页是设备级，按会话过滤还没接）
- [ ] 深色模式校对（令牌已就位，未逐一过一遍）
- [ ] 单元测试：`BlobId` 稳定性、`CacheEvictor` 淘汰顺序、`mergePart` 的 delta 拼接、CSV 解析、Markdown 转义
