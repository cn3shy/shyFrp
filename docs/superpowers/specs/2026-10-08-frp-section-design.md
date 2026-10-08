# 设计文档：世界选项界面「FRP 穿透」分区（樱花frp）

- 日期：2026-10-08
- 状态：待用户评审
- 目标仓库：`D:\Minecraft\mcwifipnp`（mcwifipnp `26.3` 分支的 fork）
- 完成后推送至：`https://github.com/cn3shy/shyFrp`（remote 名：`shyfrp`；`origin` 保留为上游 Satxm/mcwifipnp，用于后续合并更新）

## 1. 背景与目标

mcwifipnp 把原版「对局域网开放」扩展成了功能完整的「世界选项」界面（`WorldOptionsScreenNew`，26.3 风格：滚动单页 + 「常规选项」「多人选项」两个分区）。本设计在该界面中新增第三个分区「FRP 穿透」：

- 在游戏内配置并一键启动/停止樱花frp 客户端（frpc），把局域网世界映射到公网；
- 实时监测 frpc 输出：界面内日志区显示最近若干行，完整输出落盘日志文件，关键事件同步到游戏聊天栏。

## 2. 已确认的需求决策（用户拍板）

| 项 | 决策 |
|---|---|
| 载体 | 直接修改 mcwifipnp 源码（26.3 分支），不做独立兼容模组 |
| 界面形态 | 方案 A：跟随现有设计语言，新增「FRP 穿透」滚动分区（不做页签重构） |
| 输出显示 | 分区内日志区显示最近 8 行（自动滚动）；完整输出写 `logs/frpc.log`；关键事件推游戏聊天栏（前缀 `[FRP]`） |
| frpc 来源 | 外置可执行文件，路径可配置；默认预填 `C:\Users\sspan\Downloads\frpc_windows_amd64.exe` |
| 参数模型 | 樱花frp 格式拆成三个单行输入：frpc 路径 / 访问密钥（密码式打码）/ 隧道ID；启动时拼接 `<frpcPath> -f <token>:<tunnelId>` |
| 启停方式 | 手动「启动 frpc / 停止 frpc」按钮 + 「开局域网时自动启动」开关（默认开）；退出世界/关闭游戏时自动清理进程 |
| 验证平台 | Fabric（`./gradlew :fabric:runClient`）；代码写入 common 层，三平台通用 |

## 3. 界面设计

在 `WorldOptionsScreenNew.init()` 的 `multiplayerOptions(...)` 之后调用 `frpOptions(content)`，样式完全复用现有分区模式（`GridLayout` 两列 + `FocusableTextWidget` 下划线标题）。

```
┌─ 世界选项（滚动页）─────────────────────────┐
│ （…常规选项分区…）                           │
│ （…多人选项分区…）                           │
│                                             │
│ ── FRP 穿透 ───────────────（下划线标题，跨2列）│
│ frpc 路径:                                  │
│ [C:\Users\sspan\Downloads\frpc_windows_amd64.exe] │
│ 访问密钥:                                   │
│ [●●●●●●●●●●                          ]      │
│ 隧道ID:                                     │
│ [12345                               ]      │
│ [开局域网时自动启动: 开]  [启动 frpc]         │
│ 状态: ● 运行中                               │
│ ┌ 日志（最近 8 行，等宽字体，自动跟随）────┐   │
│ │ [INFO] ...                            │   │
│ └───────────────────────────────────────┘   │
├─────────────────────────────────────────────┤
│ [应用常规修改] [应用修改] [取消]              │
└─────────────────────────────────────────────┘
```

字段明细：

| 字段 | 控件 | 说明 |
|---|---|---|
| frpc 路径 | 单行 EditBox（跨 2 列） | 默认 `C:\Users\sspan\Downloads\frpc_windows_amd64.exe` |
| 访问密钥 | 单行密码式 EditBox（跨 2 列） | 显示为掩码字符；真实值存配置文件（明文，与樱花官方客户端一致） |
| 隧道ID | 单行 EditBox（跨 2 列） | 樱花面板上的隧道/映射 ID |
| 开局域网时自动启动 | CycleButton 开关 | 默认开 |
| 启动/停止按钮 | Button | 文案随进程状态切换：「启动 frpc」/「停止 frpc」 |
| 状态指示 | 文本 | `● 运行中`（绿）/ `○ 未启动`（灰）/ `路径无效`（红）/ `已退出(code)`（红） |
| 日志区 | 定高（8 行）自绘 widget（跨 2 列） | 等宽字体、半透明背景；render 时直接读管理器环形缓冲的尾部，无需 tick 逻辑 |

实现备注：
- MC 26.3 的 `EditBox` 若无原生 mask 支持，则子类化并重写渲染为掩码字符（实现阶段先查 26.3 源码确认，再定方案）。
- 日志区不做嵌套内滚动（外层 `ScrollableLayout` 已在滚动，避免滚轮冲突）；完整日志看 `logs/frpc.log`。

## 4. 技术架构（全部在 common 层，三平台通用）

新增包 `io.github.satxm.mcwifipnp.frp`：

### 4.1 `FrpConfig`

- 全局配置，路径 `<游戏目录>/config/mcwifipnp-frp.json`（用 `Minecraft.getInstance().gameDirectory` 推导，不用世界存档路径——frp 配置与单个世界无关）。
- 字段：`frpcPath`、`token`、`tunnelId`、`autoStart = true`、`pushChatEvents = true`。
- GSON 读写，风格照现有 `Config` 类；读取失败降级为默认值并记录日志。

### 4.2 `FrpProcessManager`（单例）

职责：frpc 进程的完整生命周期与输出分发。

- `start(command)`：启动前校验路径存在且可执行（`Files.isExecutable`）；`ProcessBuilder` + `redirectErrorStream(true)` 合并 stdout/stderr 启动。
- 后台读线程：`BufferedReader` 逐行读取——
  1. 追加写入 `logs/frpc.log`（启动时插入分隔标记）；
  2. 写入线程安全环形缓冲（`ArrayDeque`，上限 500 行，`synchronized`）；
  3. 匹配关键字的行 → 经 `Minecraft.getInstance().execute(...)` 切到客户端主线程 → 游戏聊天栏 `[FRP] …`（连续重复行去重）。
- `stop()`：`destroy()` → 等待 3 秒 → `destroyForcibly()`（Windows 下即为终止进程）。
- 进程自然退出（崩溃/被杀）→ 读线程 EOF 检测 → 状态复位 + 聊天栏提示退出码。
- `isRunning()`、`getStatus()`、`getRecentLines(8)` 供 UI 读取。
- `Runtime.getRuntime().addShutdownHook()` 兜底杀进程（防 Windows 孤儿进程）。

### 4.3 数据流

```
frpc 进程 stdout/stderr ──(后台读线程)──┬─→ logs/frpc.log（全量，追加）
                                        ├─→ 环形缓冲(500行) ←(渲染线程, 每帧)← 界面日志区
                                        └─→ 关键字过滤 → 主线程同步 → 游戏聊天栏
```

关键事件关键字（初版，联调时按真实输出迭代）：`error`/`失败`/`连接`/`断开`/`disconnect`/`reconnect`/`closed`/`已启动`/`隧道`。

### 4.4 UI 集成（`WorldOptionsScreenNew`）

- 新增字段：路径/密钥/隧道ID 三个 EditBox、自动启动开关、启停按钮、状态文本、日志 widget。
- `init()` 中 `frpOptions(content)`；输入框 `setResponder` 即写回 `FrpConfig` 并保存。
- 界面的「应用修改 / 取消」不影响 frpc 进程（进程独立于界面生命周期，后台持续运行）。

## 5. 生命周期

- **自动启动**：在现有 `applyChanges()` 中，局域网发布成功（`cfg.multiplayerScope == LAN` 且发布成功）且 `autoStart=true` 时调用 `FrpProcessManager.start(...)`；frpc 已在运行则不重复启动。
- **手动启动/停止**：按钮随时可用；未运行显示「启动 frpc」，运行中显示「停止 frpc」。
- **世界退出**：在现有 `MixinIntegratedServer` 中注入 `IntegratedServer` 停止流程 → `stop()`。
- **游戏关闭**：JVM shutdown hook 兜底 `stop()`。

## 6. 错误处理

| 场景 | 行为 |
|---|---|
| 路径不存在/不可执行 | 点击启动时前置校验 → 状态下显示红色「路径无效」+ 聊天栏提示；不启动 |
| 进程启动抛异常（IOException） | 状态 = 错误 + 聊天栏报错 |
| frpc 中途退出 | 读线程 EOF → 状态复位 + 聊天栏 `[FRP] frpc 已退出 (code=X)` |
| 重复点击启动 | 运行中按钮为「停止」，不重复启动 |
| 停止超时 | 3 秒后 `destroyForcibly()` |

## 7. 验证方案

1. `./gradlew :fabric:build` 编译通过。
2. `./gradlew :fabric:runClient` 进游戏手动验证清单：
   - 界面：FRP 分区显示正常、随页面滚动、密钥打码显示；
   - 错误路径：填不存在的路径 → 红字提示；
   - 真实启动（用户提供真樱花 token/隧道ID）：日志区滚动、`logs/frpc.log` 落盘、聊天栏出现关键事件；
   - 停止按钮：进程被杀（任务管理器确认）；
   - 自动启动：开开关 → 应用修改成功发布局域网 → frpc 自动起；
   - 退出世界：frpc 进程被清理；重进世界可再次启动；
   - 常规/多人选项功能无回归。

## 8. 明确不做（YAGNI）

- frpc 自动下载/更新；多隧道管理；`%PORT%` 占位符（樱花端口在面板隧道配置里，命令行不涉及端口）；
- 界面内嵌套滚动日志（用定高 8 行 + 日志文件代替）；
- Forge/NeoForge 专项测试（代码在 common，天然通用，后续要用再验证）。
