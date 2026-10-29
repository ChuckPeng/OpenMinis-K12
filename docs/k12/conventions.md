# 约定

适用于本 fork 的 K12 工作。上游 `CONTRIBUTING.md` 仍声明官方镜像不收 PR；那份文件不要改。本 fork 的 K12 变更通过本仓库的 PR 合并。

## 代码放哪里

| 区域 | 约定 |
| --- | --- |
| 新的 Kotlin | 新文件、新包。根包 `com.openminis.k12`。上游包 `com.openminis.app` 只加挂钩 |
| 挂钩 | 每个挂钩一行记在 `upstream-patches.md`，含文件、符号、原因、验收编号。没有清单记录的上游改动不合并 |
| `ChatViewModel` | 只增加一次委托调用，逻辑留在 K12 类里 |
| 技能 | `assets/k12/skills/` 与平板上的用户技能目录。仓库里只有无个人数据的模板 |
| 学生档案、catalog 实例、NC 图谱、模型 Key | 不进 git |
| 服务端 | 顶层 `server/`，独立 `go.mod`。模块路径按设计写作 `github.com/ChuckPeng/OpenMinis-K12/server`。许可证 GPL-3.0，写在 `server/README.md` |
| 管理前端 | `server/web/`，Vue 3 + Vite + TypeScript，构建结果 `go:embed` |
| 部署 | 所有者用 `server/deploy/systemd/`。`server/deploy/docker/` 仍保留，作为次要产物 |
| 工程文档 | 只写在 `docs/k12/`。上游 `docs/specs/` 编码前要读，但不在 K12 PR 里改写 |

FSRS 移植自 `open-spaced-repetition/java-fsrs`（MIT）。源文件头保留原版权与 MIT 声明。NC 图谱（CC BY-NC-SA）只在运行时下载到本机私有目录，衍生映射保持 BY-NC-SA，不进 GPL 源码树，不打进 APK。

禁用危险能力时关掉入口，不删上游实现。

## 版本号（SemVer）

K12 fork 使用**独立于上游**的 SemVer。上游 `versionName` / `versionCode` 只记入元数据与 `CHANGELOG.md`，不驱动本 fork 的安装升级。

### 产品版本字符串

| 阶段 | `versionName` / 服务端 `Version` | 例子 |
| --- | --- | --- |
| 里程碑开发 | `MAJOR.MINOR.PATCH-mN` 或带字母后缀的里程碑 | `1.0.0-m0`、`1.0.0-m1`、`1.0.0-m5a` |
| 正式发布 | `MAJOR.MINOR.PATCH` | `1.0.0`、`1.1.0`、`1.2.0` |

v1 开发期里程碑与 `roadmap.md` 对齐：`m0`…`m3`、`m5a`、`m5b` → 最终打成 `1.0.0`。此后 `1.1.0`、`1.2.0` 对应路线图批次。

源文件：

| 端 | 权威位置 |
| --- | --- |
| 平板 | `src/android/app/build.gradle.kts` 的 `versionName` / `versionCode`；常量镜像在 `com.openminis.k12.Version` |
| 服务端 | 构建时 `-ldflags` 写入 `github.com/ChuckPeng/OpenMinis-K12/server/internal/version`（`Version`、`VersionCode`、`GitCommit`、`UpstreamVersion`）。`openminis-k12-server --version` 与管理页页脚展示同一字符串 |
| 设备 API | 整数 `device_api_version`（心跳 / 配对声明）。OpenAPI `info.version` 仍是契约修订号（如 `0.0.0-r16`），与产品 SemVer 分开 |

上游基线记在 `Version.UPSTREAM_VERSION_NAME` / `UPSTREAM_VERSION_CODE`（以及服务端同名字段），每个里程碑 PR 的 changelog 也写一行。

### `versionCode` 公式（单调）

```
versionCode = MAJOR × 1_000_000 + MINOR × 1_000 + PATCH × 10 + STAGE
```

`STAGE`（同一 `MAJOR.MINOR.PATCH` 线内）：

| STAGE | 含义 |
| --- | --- |
| 1–8 | 该版本线上的里程碑预发布（按合并顺序递增；v1 约定 m0=1 … m5b=6） |
| 9 | 该 `MAJOR.MINOR.PATCH` 的正式版（GA） |

例子：`1.0.0-m0` → `1000001`；`1.0.0-m1` → `1000002`；`1.0.0` → `1000009`；`1.1.0-m4` → `1001001`；`1.1.0` → `1001009`。

只增不减。热修同一 PATCH 时抬高 `STAGE` 或抬高 `PATCH`（并更新 changelog）。Android `versionCode` 与服务端嵌入的 `VersionCode` 使用同一整数，便于对照。

### 设备 API 兼容

- 平板在心跳等设备请求中携带 `device_api_version`（当前为 `1`）与 `app_version`（SemVer 字符串）。
- 服务端在心跳响应中回显自己支持的 `device_api_version` 与产品 `Version`。
- 规则：服务端接受 `device_api_version ∈ [min_supported, max_supported]`；平板拒绝与本机 `Version.DEVICE_API_MIN`…`DEVICE_API` 无交集的服务端。事件体的 `schema_version` 仍按 `data-model.md`（服务端至少兼容前一版）。

## 变更日志

根目录 `CHANGELOG.md`，[Keep a Changelog](https://keepachangelog.com/) 格式，版本段标题与 SemVer / git 标签一致。每个里程碑或发布 PR **必须**更新：Added / Changed / Fixed / Security 等小节；并注明本里程碑所基于的上游版本。未发布变更写在 `[Unreleased]` 下，打标签前挪入对应版本段。

## 分支

- 一个分支一件可独立合并的事，通常对应 `roadmap.md` 的一个里程碑或工作包。
- 本地/常规命名小写：`k12/<里程碑>-<短名>`，例如 `k12/m0-pin-gate`。
- Cursor Cloud Agent 分支另遵仓库前缀/后缀模板（如 `cursor/<短名>-7a0f`），语义上仍按里程碑拆分。
- 功能/里程碑分支只经 PR 合入其基线分支；不要把互不相关的里程碑塞进同一 PR。
- 合并上游单独分支，不与功能提交混在一起。节奏：每月一次，合并后跑评测与回归（§19.8）。细则见下方「上游同步」与 `upstream-patches.md`。
- 不要把真实档案、Key、图谱数据、签名密钥放进任何分支。

## 提交

[Conventional Commits](https://www.conventionalcommits.org/)：`type(scope): 说明`。说明用中文或英文均可，但同一 PR 内保持一致。

| type | 何时 |
| --- | --- |
| `feat` | 新行为 |
| `fix` | 纠正已有行为 |
| `docs` | 只改文档（含 `docs/k12/`、`CHANGELOG.md`） |
| `test` | 只改测试 |
| `chore` | 构建、CI、依赖 |
| `refactor` | 无行为变化的结构调整 |

scope 用模块短名：`k12-shell`、`k12-skills`、`k12-server`、`k12-admin`。一次提交不跨「平板功能 + 服务端功能」，除非工作包本身是契约变更（先改 `api/` 与测试向量，两端再跟）。

## PR

- 说明里写工作包 ID、验收编号、影响的挂钩 ID，以及本 PR 的目标 SemVer（如 `1.0.0-m1`）。
- 同步更新 `CHANGELOG.md` 与版本字段（`versionName` / `versionCode` / 服务端 ldflags 默认值）。
- 动到提示词、技能、揭晓或批改：附评测结果，不过门槛不合并（`test-plan.md`）。
- 动到 `server/**` 的 CI 与 Android CI 分开（设计：`server.yml` 用 paths 过滤；Android 工作流 `paths-ignore: server/**`）。
- 不向上游 `OpenMinis/OpenMinis` 提 PR。

## 测试

- 夹具只用合成的 `child_id`、哈希与标签，不用真实姓名、学校、班级、成绩。
- 平板与服务端共用的签名字节串、授权 JSON、指标向量各放一份，两端对拍。
- 改挂钩时跑该挂点附近的上游测试，并在 PR 里写明。

## 标签与发布

| 标签 | 含义 | 产物 |
| --- | --- | --- |
| `v1.0.0-m0`、`v1.0.0-m1`、… | 里程碑完成（通常在对应 PR **合并之后**打在合并提交上） | CI 附加 debug/release APK（有密钥则签 release）与校验和；服务端目录存在时再附 linux/amd64、linux/arm64 二进制与 SHA-256 |
| `v1.0.0`、`v1.1.0`、`v1.2.0` | 批次正式版 | 同上；管理页与 `--version` 显示无 `-mN` 后缀的版本 |
| `server-v*` | 仅当需要与平板 SemVer **解耦**热修服务端时使用 | 只发服务端二进制 / 镜像 |

不能在未合并的功能分支上代表「已完成里程碑」打正式标签；PR 说明里写清「合并后请打 `v…`」。所有者在合并后打标签，触发发布工作流。

仓库可保持公开；试点期二进制也可改由自托管服务端分发（§19.7）。发布签名密钥不进仓库，离线保管并另有备份。服务端发布跑 `govulncheck`。

## 上游同步

1. 添加或更新 remote：`git remote add upstream https://github.com/OpenMinis/OpenMinis.git`（若已有则 `git fetch upstream`）。
2. 每月单独开分支（例如 `k12/upstream-sync-YYYYMM`），**只**做上游同步，不混功能。
3. 默认 `git merge upstream/<upstream-default-branch>`。若历史已高度分叉且所有者明确要求，才改用 rebase；rebase 后须强制核对 `upstream-patches.md` 全部挂点。
4. 冲突优先保留挂钩委派形态：上游文件只留一行委托 / 路由守卫，K12 逻辑留在 `com.openminis.k12`（见 `upstream-patches.md`）。
5. 合并后：更新 `Version.UPSTREAM_*` 与 changelog 中的上游基线；跑 Android / 服务端 CI 与评测门槛；若挂点行号或符号变了，先改 `upstream-patches.md` 再改代码。
6. 若上游新增顶层 `server/` 或同名路径，先停下来按 ADR-0001 处理，再继续。

## 发布产物（CI）

标签推送触发 `.github/workflows/release.yml`：

- 构建 `assembleDebug`（及在提供 `OPENMINIS_K12_KEYSTORE_*` secrets 时的 `assembleRelease`）。
- 上传 APK 与 SHA-256 到该 tag 的 GitHub Release。
- 若存在 `server/go.mod`：交叉编译 `openminis-k12-server`（linux/amd64、linux/arm64），`-ldflags` 嵌入与标签对应的 SemVer / `versionCode`，上传二进制与 SHA-256；镜像发布仍按服务端模块文档。

## 配置与日志

- 服务端进程配置：`/etc/openminis-k12/config.yaml`，环境变量前缀 `OPENMINIS_K12_`。
- 业务配置在数据库里，管理页修改，经心跳下发的那部分以服务端版本号为准。
- 日志用结构化 JSON，脱敏 Secret、token、EncodingAESKey、备份口令、告警片段。
- 时区默认 `Asia/Shanghai`。
