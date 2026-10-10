# 设计文档：FRP 日志转发聊天栏 + 配置外置 + 项目精简为仅 Fabric

- 日期：2026-10-10
- 状态：已与用户逐节确认，待落实施计划
- 目标仓库：`D:\Project\shyFrp`（`cn3shy/shyFrp`）
- 分支：直接在 **`26.3`**（主分支）上操作并推送

## 1. 背景与目标

上一版 FRP 分区（见 `2026-10-08-frp-section-design.md`）把 frpc 的路径/密钥/隧道 ID 做成了界面输入框，并在界面内放了 8 行日志区、只把命中关键词的输出推到聊天栏。实际使用中：

1. 界面里的日志区太小，看不清 frpc 到底报了什么；希望能把全部输出直接打到聊天栏，并有一个随时可切的开关。
2. 界面上的三个输入框每次都要在游戏里敲路径和密钥，麻烦；希望能像普通 frp 配置一样，直接在 `config/shyfrp.json` 里写 exe 路径和启动参数。
3. 三个平台（fabric/forge/neoforge）长期只用到 fabric，希望精简掉另外两个模块，产物也换成 `shyfrp` 前缀。

因此本次三件事：**日志全量转发聊天栏 + `/shyfrp log` 开关**、**配置移到 `config/shyfrp.json` 并精简界面**、**项目精简为仅 Fabric 且产物改名**。

## 2. 已确认的需求决策（用户拍板）

| 项 | 决策 |
|---|---|
| `/shyfrp` 注册方式 | **客户端命令**（Fabric `ClientCommandRegistrationCallback`），不用 OP、不用开作弊；不走现有服务端命令表 |
| 日志粒度 | **真·全量**：去掉关键词过滤、去重、节流，frpc 的每一行都进聊天栏；默认开启 |
| 配置文件 | 全部合并到 **`config/shyfrp.json`**（exe、args、auto-start、log-to-chat），旧的 `mcwifipnp-frp.json` 废弃 |
| 读盘时机 | **每次启动 frpc 前重新读盘**（含首次），手改文件不用重启游戏 |
| 界面保留项 | 只保留「发布局域网后自动开启」开关 + 启动/停止按钮（状态行与一行灰色配置路径提示一并保留，用户未反对） |
| `args` 形式 | 单个字符串，按空白切分；不引入 JSON 数组、不支持引号转义 |
| 产物命名 | **`shyfrp-2.1.4.jar`**（去掉 MC 版本段与平台段） |
| 精简范围 | **全清**：删 forge/neoforge 模块、清构建配置、改 build/release 工作流、删 `publish.yaml`、清 README 的平台说明 |
| `mod_id` | 保持 `mcwifipnp` 不变（改它要连带 assets 命名空间、lang key、存档与配置兼容） |

## 3. 配置模型：`config/shyfrp.json`

路径：`<Minecraft.getInstance().gameDirectory>/config/shyfrp.json`，UTF-8，Gson pretty printing。

```json
{
  "exe": "C:\\Game\\Minecraft\\frpc_windows_amd64.exe",
  "args": "-f 55qt9v9oofm19s1gfjzpkcz9o5ry4qmv:27811507",
  "auto-start": true,
  "log-to-chat": true
}
```

字段与 Java 对应（`io.github.satxm.mcwifipnp.frp.FrpConfig`，类名与包名不变）：

| JSON 字段 | Java 字段 | 默认值 | 说明 |
|---|---|---|---|
| `exe` | `exe` | `""` | frpc 可执行文件绝对路径 |
| `args` | `args` | `""` | 启动参数，按空白切分成 argv |
| `auto-start` | `autoStart` | `true` | 发布局域网成功后自动启动 frpc |
| `log-to-chat` | `logToChat` | `true` | 是否把 frpc 输出推送到聊天栏 |

行为：

- **热重载**：`FrpProcessManager.start()` 内先 `FrpConfig.reload()`（重新读盘，读失败沿用内存值），gui 与命令改动的字段仍走 `save()` 写回同一文件。
- **自动生成**：文件不存在且无旧配置时，写出一份上述默认内容（`exe`/`args` 为空），替代原先硬编码的 `C:\Users\sspan\Downloads\frpc_windows_amd64.exe`。
- **迁移**：`shyfrp.json` 不存在但 `mcwifipnp-frp.json` 存在时，按上表映射一份出来：

  | 旧字段 | 新字段 |
  |---|---|
  | `frpc-path` | `exe` |
  | `token` + `tunnel-id`（都非空时） | `args = "-f " + token + ":" + tunnelId` |
  | `auto-start` | 原值保留 |
  | `push-chat-events` | `log-to-chat` |

  迁移后写出 `shyfrp.json`；**不删除也不改写** `mcwifipnp-frp.json`（迁移后再也不读它）。
- **校验**（在 `start()` 内，失败信息回聊天栏并带上配置文件路径）：`exe` 为空或文件不存在 → 报错；`args` 切分后为空 → 报错。原「token / 隧道 ID 不能为空」校验删除。
- `args` 的切分用 `String.split("\\s+")` 并过滤空串；不处理引号，文档中写明含空格的参数不支持。

## 4. 界面变更（`WorldOptionsScreenNew.frpOptions`）

- 删除：`frpcPathEdit`、`frpTokenEdit`、`frpTunnelIdEdit` 三个输入框及其行容器；8 行日志区（`FRP_LOG_LINES`、`frpLogLines`、`frpLogVersion`）。
- 删除随之无用的 `updateFrpWidgets` 中的日志刷新分支（保留状态与按钮刷新）。
- 保留：分区标题、`autoStart` 开关、启动/停止按钮、状态行 `状态: 运行中/未启动/错误`。
- 新增：一行灰色不可交互文字「配置文件: config/shyfrp.json」，给玩家指明去哪儿改。

## 5. 日志转发（`FrpProcessManager`）

- 删除 `KEY_EVENT_KEYWORDS`、`pushKeyEvent()`、聊天去重（`lastChatLine`/`lastChatTime`）与节流（`chatWindowStart`/`chatWindowCount`/`CHAT_WINDOW_MS`/`CHAT_MAX_PER_WINDOW`）。
- **同时删除环形缓冲 `buffer`/`version`/`getVersion()`/`getRecentLines()`/`MAX_BUFFER_LINES`**：它们唯一的消费者是本次要删掉的界面日志区，删掉界面后即成死代码。
- `readLoop` 内每读到一行 → 写 `logs/frpc.log` → `pushChat("[FRP] " + line)`。
- `pushChat` 改为只判断 `FrpConfig.get().logToChat`，为真则 `Minecraft.getInstance().execute(() -> ... addClientSystemMessage(...))`；保留 `[FRP]` 前缀与 `ChatFormatting.GRAY`。
- 启动/停止/退出等状态行仍走同一通道，同样受开关控制。
- `logs/frpc.log` 的全量落盘**保留且不受开关影响**。

## 6. `/shyfrp` 客户端命令

注册位置：`fabric/src/main/java/io/github/satxm/mcwifipnp/MCWiFiPnP.java` 的 `onInitializeClient()`（Fabric 客户端命令只在客户端加载）：

```java
ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> { ... });
```

语法与行为：

| 输入 | 行为 |
|---|---|
| `/shyfrp` | 输出用法 + 当前开关状态与配置文件路径 |
| `/shyfrp log` | 回显当前 `log-to-chat` 状态 |
| `/shyfrp log true` / `false` | 设置并 `save()`，回显新状态 |

- 布尔参数用 brigadier 的 `BoolArgumentType.bool()`；命令源是 `FabricClientCommandSource`，回显用 `sendFeedback`。
- 已用真实的 `fabric-api 0.161.0+26.3`（`fabric-command-api-v2`）反查确认以下 API 存在且签名匹配：`ClientCommandRegistrationCallback.EVENT`、`ClientCommandRegistrationCallback.register(CommandDispatcher<FabricClientCommandSource>, CommandBuildContext)`、`ClientCommands.literal(String)`、`FabricClientCommandSource.sendFeedback(Component)`。
- 命令在客户端本地执行，单机/局域网存档下无需开作弊、无需 OP（这正是它不沿用 `/ip` 服务端注册模式的原因）。

## 7. 项目精简（仅 Fabric）

删除：

- `forge/`、`neoforge/` 两个模块目录（各含一个入口类与一份 mods.toml；共享源码 `src/main/java` 不引用它们，已确认）。
- `.github/workflows/publish.yaml`（引用的 `quilt` 模块在仓库中已不存在，且使用上游 mcwifipnp 的 Modrinth/CurseForge 项目 ID）。

修改：

- `settings.gradle`：`include("forge")`、`include("neoforge")` 删除；pluginManagement 中 `MinecraftForge`、`NeoForge` 两个 maven 仓库删除（保留 Fabric/Minecraft libraries/Sponge/mavenCentral/gradlePluginPortal）。
- `gradle.properties`：删除 `forge_version`、`neo_version`、`forge_version_range`、`forge_loader_version_range`、`neo_version_range`、`neo_loader_version_range`、`net.minecraftforge.gradle.merge-source-sets`。
- `.github/workflows/build.yml`：artifact 名 `mcwifipnp-artifacts` → `shyfrp-artifacts`（`path` 的 `**/build/libs/*.jar` 无需改，但会只匹配到 fabric）。
- `.github/workflows/release.yml`：资产收集循环改为只扫 `fabric/build/libs/*.jar`，仍排除 `*-sources.jar`、`*-dev.jar`；Release 标题保持 `shyFrp <tag>`。
- `README.md`、`README.zh-CN.md`：删除 Forge/NeoForge 的安装与说明段落，保留 Fabric。

## 8. 产物命名

只剩 fabric 一个模块，故只改一处：

- `fabric/build.gradle`：`base.archivesName = "shyfrp"`（原为 `project.mod_id`），`version = project.mod_version`（去掉 `-<minecraft_version>-fabric`）。
- 结果：`shyfrp-2.1.4.jar`、`shyfrp-2.1.4-sources.jar`。

## 9. 文件改动清单

| 文件 | 动作 |
|---|---|
| `src/main/java/.../frp/FrpConfig.java` | 字段/路径/迁移/热重载/校验 |
| `src/main/java/.../frp/FrpProcessManager.java` | 全量转发、去过滤节流与环形缓冲、启动前读盘、用 args 拼 argv |
| `src/main/java/.../frp/FrpLogCommand.java`（新） | 命令逻辑与回显文本（纯逻辑，供 fabric 入口调用） |
| `src/main/java/.../client/WorldOptionsScreenNew.java` | 删三个输入框与日志区，加配置路径提示 |
| `fabric/src/main/java/.../MCWiFiPnP.java` | 注册客户端命令 |
| `src/main/resources/assets/mcwifipnp/lang/{zh_cn,en_us}.json` | 删 path/token/tunnelId 相关 key，加配置提示与命令回显 key |
| `settings.gradle`、`gradle.properties`、`fabric/build.gradle` | 精简与改名 |
| `.github/workflows/{build.yml,release.yml}` | 只跑 fabric；删 `publish.yaml` |
| `README.md`、`README.zh-CN.md` | 清 Forge/NeoForge 说明 |
| `forge/`、`neoforge/` | 整目录删除 |

## 10. 错误处理

- 配置读失败（IO/JSON 解析）→ 记 WARN 日志，沿用内存中的默认值，不崩溃。
- `exe` 为空/不存在、`args` 为空 → 聊天栏红字 `[FRP] ...`，附带 `config/shyfrp.json` 路径。
- 保存失败 → WARN 日志（沿用现有行为）。
- 命令参数非法（非 true/false）→ brigadier 自身报错，无需额外处理。

## 11. 验证

- CI：推送后 `.github/workflows/build.yml` 执行 `./gradlew build`（本机无 Gradle 缓存，完整构建需重下并反编译 MC，故以 CI 编译结果为准，不本地跑完整构建）。
- 手动验收清单（游戏内）：
  1. 首次启动后自动生成 `config/shyfrp.json`，内容为默认值。
  2. 把 `exe`/`args` 填好，点「启动 frpc」→ 状态行变为运行中。
  3. `/shyfrp log false` → 聊天栏停止刷日志并回显已关闭；`/shyfrp log true` → 恢复逐行输出；`/shyfrp log` 回显当前状态。
  4. 开着游戏直接改 `shyfrp.json` 的 `args`，再点启动 → 新参数生效（不用重启游戏）。
  5. `exe` 故意写错 → 聊天栏红字报错并给出配置文件路径。
  6. 放置旧版 `mcwifipnp-frp.json` → 启动后生成迁移后的 `shyfrp.json`，旧文件仍在且未被改写。
  7. 界面 FRP 分区只剩标题、提示行、自动开启开关、启动/停止按钮、状态行。
  8. 产物为 `shyfrp-2.1.4.jar`。

## 12. 不在本次范围内

- 不引入测试框架（仓库无 test 源集）；`args` 解析与迁移映射写成纯函数，后续想补测试时容易接。
- 不支持带空格的参数（需引号转义的那种）。
- 不做 `WatchService` 文件监听（改为每次启动前读盘已满足需求）。
- 不改 `mod_id`、不改资源命名空间与 lang key 前缀。
