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
| 服务端 | 顶层 `server/`，独立 `go.mod`。模块路径按设计写作 `github.com/ChuckPeng/OpenMinis-K12/server` |
| 管理前端 | `server/web/`，Vue 3 + Vite + TypeScript，构建结果 `go:embed` |
| 部署 | `server/deploy/docker/`、`server/deploy/systemd/` |
| 工程文档 | 只写在 `docs/k12/`。上游 `docs/specs/` 编码前要读，但不在 K12 PR 里改写 |

FSRS 移植自 `open-spaced-repetition/java-fsrs`（MIT）。源文件头保留原版权与 MIT 声明。NC 图谱（CC BY-NC-SA）只在运行时下载到本机私有目录，衍生映射保持 BY-NC-SA，不进 GPL 源码树，不打进 APK。

禁用危险能力时关掉入口，不删上游实现。

## 分支

- 一个分支一件可独立合并的事，对应 `roadmap.md` 里的一个工作包。
- 分支名小写：`k12/<里程碑>-<短名>`，例如 `k12/m0-pin-gate`。
- 合并上游单独分支，不与功能提交混在一起。节奏：每月一次，合并后跑评测与回归（§19.8）。
- 不要把真实档案、Key、图谱数据、签名密钥放进任何分支。

## 提交

沿用上游的短格式：`type(scope): 说明`。

| type | 何时 |
| --- | --- |
| `feat` | 新行为 |
| `fix` | 纠正已有行为 |
| `docs` | 只改 `docs/k12/` |
| `test` | 只改测试 |
| `chore` | 构建、CI、依赖 |

scope 用模块短名：`k12-reveal`、`k12-server`、`k12-admin`。一次提交不跨「平板功能 + 服务端功能」，除非工作包本身是契约变更（先改 `api/` 与测试向量，两端再跟）。

## PR

- 说明里写工作包 ID、验收编号、影响的挂钩 ID。
- 动到提示词、技能、揭晓或批改：附评测结果，不过门槛不合并（`test-plan.md`）。
- 动到 `server/**` 的 CI 与 Android CI 分开（设计：`server.yml` 用 paths 过滤；Android 工作流 `paths-ignore: server/**`）。
- 不向上游 `OpenMinis/OpenMinis` 提 PR。

## 测试

- 夹具只用合成的 `child_id`、哈希与标签，不用真实姓名、学校、班级、成绩。
- 平板与服务端共用的签名字节串、授权 JSON、指标向量各放一份，两端对拍。
- 改挂钩时跑该挂点附近的上游测试，并在 PR 里写明。

## 标签与发布

| 标签 | 产物 | 分发 |
| --- | --- | --- |
| `v*` | 平板版本（APK） | 私有 APK 由自托管服务端提供。仓库保持公开。不发 GitHub Releases 二进制 |
| `server-v*` | `openminis-k12-server` 的 linux/amd64 与 arm64，附 SHA-256；多架构镜像 `openminis-k12-server` | 与 APK 版本号独立 |

发布签名密钥不进仓库，离线保管并另有备份。APK 同时公布 SHA-256。服务端发布跑 `govulncheck`。

## 配置与日志

- 服务端进程配置：`/etc/openminis-k12/config.yaml`，环境变量前缀 `OPENMINIS_K12_`。
- 业务配置在数据库里，管理页修改，经心跳下发的那部分以服务端版本号为准。
- 日志用结构化 JSON，脱敏 Secret、token、EncodingAESKey、备份口令、告警片段。
- 时区默认 `Asia/Shanghai`。
