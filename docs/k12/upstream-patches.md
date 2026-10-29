# 上游挂钩

核对对象：本仓库当前 `main`（`1a71df0`，上游 Android 1.15）。设计 §15 的行号会漂移；下表是这次核对后的位置。新增挂钩先改本表再改代码。

路径均相对仓库根。包名 `com.openminis.app` 除非另注。

## 允许的改动原则

上游文件只做委派或路由守卫。K12 逻辑放在新包，根包 `com.openminis.k12`。不删上游工具实现。

## 已核对挂点

| ID | 需求 | 挂点 | 与 §15 的差异 |
| --- | --- | --- | --- |
| H-01 | 构建参数 | `src/android/app/build.gradle.kts`：`compileSdk = 36`、`minSdk = 26`、`targetSdk = 35`、`versionName` / `versionCode`、`abiFilters` 含 `arm64-v8a`、`RELEASE_REPO` | 上游基线曾为 `versionName = "1.15"`、`versionCode = 37`、`RELEASE_REPO = "OpenMinis/OpenMinis"`。K12 改用独立 SemVer（见 `conventions.md`）：当前 `1.0.0-m0` / `1000001`，上游版本写入 `BuildConfig.K12_UPSTREAM_*` 与 `com.openminis.k12.Version`。OkHttp 4.12.0 与 `okhttp-sse`、navigation-compose 2.8.5、datastore-preferences 1.1.1 均在 |
| H-02 | 包名与并存 | 同文件 `namespace` / `applicationId` 现为 `com.openminis.app` | K12 改为 `com.openminis.k12`（已决定）。显示名「伴学 Minis」 |
| H-03 | 入口 | `AndroidManifest.xml` 中 `.MainActivity`，`launchMode="singleTask"`。图标别名 `MainActivityIconAuto` / `Light` / `Dark`。`MainActivity.setContent` 在 `MainActivity.kt` 约第 520 行 | 行号与设计一致。儿童外壳套在这次 `setContent` |
| H-04 | 起始路由与 PIN 守卫 | `ui/navigation/AppNavigation.kt` 的 `object Routes`（约第 89 行）。`NavHost` 的 `startDestination` 约第 539–555 行，默认 `Routes.SESSION_LIST` | 一致。儿童主页改为新的起始路由。设计点名要守卫的路由：`SETTINGS`、`PROVIDER_LIST`、`TERMINAL`、`FILE_BROWSER`、`MCP`、`AGENT_TOOLS`、`ENV_VARS`、`NETWORK` |
| H-05 | 深链 | `deeplink/DeepLinkHandler.kt` 的 `sealed class DeepLinkAction`（约第 47 行）。`AppNavigation` 只消费它 | **纠正**：`DeepLinkAction` 不在 `AppNavigation.kt`。`OpenTerminal`、`OpenSettingsScreen`、`CreateEnvironmentVariable`、`NewChat` / `NewVoiceChat` / `NewCameraChat` 都会绕过会话列表 |
| H-06 | 其他入口 | `share/ShareReceiverActivity`；`webapp/WebAppActivity`（`singleInstance`）；`auth/OAuthRedirectActivity`（`singleTask`，清单约第 416 行） | 与设计一致，儿童模式须一并拦截 |
| H-07 | 工具开关 | `tools/AgentToolSwitch.kt`：`enum class AgentToolSwitch`，`PREFS = "agent_settings"`，`governing(toolName)`。`ChatViewModel` 约第 2515、14171、16285 行读取开关 | **纠正**：没有名为 `AgentTools` 的类型。现有开关只覆盖 `browser_use`、子代理、`user_ask`。shell / 通讯录等不在这个枚举里 |
| H-08 | offload 门 | `sandbox/offload/OffloadGate.kt` 的 `internal object OffloadGate` | 设计写 `OffloadGate`，位置在 `sandbox/offload`。儿童模式在此增加拒绝，不删各 `*OffloadHandler` |
| H-09 | 系统提示 | `ui/chat/ChatViewModel.kt` `buildSystemPrompt()` 约第 16250 行 | 一致。只加一个委托；教法放技能 |
| H-10 | 视觉 | `tools/ReadImageTool.kt`（`object ReadImageTool`，工具名 `read_image`）。`ChatViewModel` 约第 14156 行调用；同文件约第 2438 行说明 Vision Group | 拍题与拍目录复用这条路径，不改模型层 |
| H-11 | 屏幕时长 | `MinisApp.kt`：`ProcessLifecycleOwner` 观察者约第 588、697 行；`registerActivityLifecycleCallbacks` 约第 936 行 | 一致。在前后台处计时与重新上锁 |
| H-12 | 不要误用的用量 | `ui/settings/UsageStatsScreen.kt`；`data/db/UsageRecord.kt` 的 `tokenUsage` | 这是模型 token，不是屏幕时长。K12 用量另建计数 |
| H-13 | 设置页 | `ui/settings/SettingsScreen.kt` 的 `SettingsScreen`（约第 82 行）。`data/repository/BackgroundSettingsRepository.kt` 使用 SharedPreferences | 家长区只加分组：PIN/TOTP、配对、模型 Key、catalog 拍照、解锁兜底、权限自检。报告不放这里 |
| H-14 | 供应商 | `provider/` 与 `ui/settings` 中的供应商页。`data/UpdateChecker.kt` 读取 `BuildConfig.RELEASE_REPO` | 供应商实现不改。`RELEASE_REPO` 改为本 fork。更新通道改为自托管 APK（§19.7），不再依赖上游 GitHub Releases |
| H-15 | 本机通知 | `notification/UserAskNotifier.kt` 是 **object**，渠道 `minis_user_ask`。`UserAskNotificationReceiver`、`BackgroundTaskNotifier` | 照此新增家长提醒渠道。清单已有 `POST_NOTIFICATIONS` |
| H-16 | 闹钟 | `scheduled/ScheduledTaskManager.kt`、`scheduled/ScheduledTaskAlarmReceiver.kt`。清单已有 `SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED` | 休息提醒可参考。**周报不在平板上跑**，由服务端生成。禁止儿童创建 shell 类定时任务 |
| H-17 | 访问服务端 | `network/NetworkHttp.kt` 的 `object NetworkHttp`，`client(tag, base)`。清单已有 `INTERNET` | 用派生 OkHttpClient。证书指纹在配对时固定。平板不调用 qyapi |
| H-18 | 技能 | `data/repository/SkillRepository.kt`，`MAX_SKILLS_IN_PROMPT = 20` | 安装 core + 本机 student + catalog。不覆盖用户更高版本 |
| H-19 | 语音 | `speech/TextToSpeechManager.kt`；`speech/SpeechRecognitionManager.kt`；`speech/SpeechRecognitionEngine.kt` | 练习页直接调用，不走 shell CLI。有中文系统引擎则用系统，否则 sherpa |
| H-20 | 与 PIN 无关 | `accessibility/RestrictedSettingsManager.kt` | 只处理 Android 13+ 无障碍「受限设置」。不并入家长锁 |

## 儿童模式要关掉的 Android 入口

设计点名且本树存在：

| 能力 | 挂点 |
| --- | --- |
| 终端 / shell | `Routes.TERMINAL`，深链 `DeepLinkAction.OpenTerminal` |
| 浏览器自动化 | `AgentToolSwitch.BROWSER`；`sandbox/offload/BrowserUseOffloadHandler.kt` |
| 通讯录 | `sandbox/offload/ContactsOffloadHandler.kt`（`OffloadGate.enforce("contacts", ...)`） |
| 文件浏览 | `Routes.FILE_BROWSER` |
| 定位 | `sandbox/offload/LocationOffloadHandler.kt`。产品决定不做定位，一并拒绝 |

本树**没有**蓝牙控制接口，也**没有** HomeKit。不要为了设计里的这两个名字去造禁用点。

儿童模式再拒绝：日历、剪贴板、通知、相册、设备信息、Shizuku、无障碍、调试类 offload，以及儿童创建定时任务。设置、网络、MCP、工具、环境变量页仍要 PIN。本树没有蓝牙或 HomeKit 接口。

合并上游时若出现 `server/` 目录，先停下来看冲突，再更新本表。

## 明确不挂钩

- `provider/*` 的协议实现、默认模型、计费。
- `UsageRecord` 的 token 账本。
- `RestrictedSettingsManager`。
- 上游 `docs/specs/` 的正文。

## 合并上游

每月单独分支合并 `OpenMinis/OpenMinis`。冲突热点是 `ChatViewModel.kt` 与 `AppNavigation.kt`。挂钩若行号变化，更新本表，不把 K12 逻辑扩进这两个文件。

## M0 已接入

逻辑在 `com.openminis.k12`。下表是这次实际改到的上游符号。

| ID | 文件 | 符号 | 原因 | 验收 |
| --- | --- | --- | --- | --- |
| H-01 | `src/android/app/build.gradle.kts` | `versionName` / `versionCode` / `K12_*` BuildConfig | K12 独立 SemVer；上游 1.15/37 记入元数据 | WP-M0-1、版本约定 |
| H-02 | `src/android/app/build.gradle.kts` | `applicationId` | 安装身份改为 `com.openminis.k12`，可与原版并存。`namespace` 仍是 `com.openminis.app`，这样 `R` / `BuildConfig` 和清单里的相对类名都不用改 | WP-M0-1 |
| H-02 | `src/android/app/src/main/res/values*/strings.xml` | `app_name` | 显示名「伴学 Minis」 | WP-M0-1 |
| H-03 | `MainActivity.kt` | `setContent` | 休息提醒盖在现有内容上 | WP-M0-5、C4 |
| H-04 | `AppNavigation.kt` | `startDestination`、`NavHost` | 冷启动进入儿童主页或向导，并登记 K12 路由 | WP-M0-2、WP-M0-6 |
| H-04 | `AppNavigation.kt` | `OnDestinationChangedListener` | 未解锁时弹回受守卫路由；回到儿童界面即上锁 | E1、F1 |
| H-05 | `MainActivity.kt`、`AppNavigation.kt` | `DeepLinkHandler.parse` 的消费处 | 儿童模式丢弃会绕过主页的深链 | F1 |
| H-06 | `ShareReceiverActivity`、`WebAppActivity`、`OAuthRedirectActivity` | `onCreate` | 儿童模式直接结束这三条旁路 | F1 |
| H-07 | `AgentToolSwitch.kt` | `isToolEnabled` | 儿童模式关掉 `browser_use`。实现不删 | F2 |
| H-08 | `OffloadGate.kt` | `allow` | 儿童模式在门上拒绝。各 handler 不删 | F2 |
| H-08 | `NativeOffload.kt` | `handleClient` | 没走 `OffloadGate` 的命令（设备信息、调试、定时任务等）同样拒绝。听说放行 | F2 |
| H-11 | `MinisApp.kt` | `onActivityStarted` / `onActivityStopped` | 只记本进程前后台，作为本 App 时长 | E3、C4 |
| H-14 | `build.gradle.kts`、`UpdateChecker.kt` | `RELEASE_REPO`、`RELEASES_URL`、`AUTO_UPDATE_CHECK` | 更新仓库改到本 fork。自动检查先关掉，自托管清单在 M5b | WP-M0-8 |
| H-01 | `build.gradle.kts`、`src/rcloneStub` | rclone 依赖 | `rclone.aar` 不进 git。没有这份产物时改编译同包名的占位类，保证 CI 能汇编和跑单测。有 aar 时不用占位类 | WP-M0-8 |
| H-16 | `ScheduledTaskManager.kt` | `create` | 儿童模式拒绝创建定时任务 | F2 |
| H-18 | `SkillRepository.kt` | `init`（`installBundledSkills` 之后） | 安装 `study-companion-core`，并物化无个人数据的 student / catalog 模板；不覆盖更新的本地版本 | WP-M1-1…4、K1、K3 |
| H-09 | `ChatViewModel.kt` | `buildSystemPrompt` 返回前 | 一行追加 `TutoringRuntime.promptSuffix()`（当前层与禁止事项）；教法仍在技能里 | WP-M2-1…3、A1 |

`ChatViewModel` 只加 H-09 一行委托。供应商协议、`UsageRecord`、`RestrictedSettingsManager` 未改。

公开树没有 `provider-customization.properties`。依赖那份私有提示词的上游快照测试在本仓库跑不起来，CI 不把它们算进 M0 门槛。
