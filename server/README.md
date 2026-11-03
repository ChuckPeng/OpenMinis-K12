# OpenMinis-K12 服务端

技术标识 `openminis-k12-server`。本目录目前只有这份说明，实现尚未开始。

**许可证：GPL-3.0-only**，与平板应用相同。派生作品保持 GPL-3.0。

工程契约在仓库的 `docs/k12/`。所有者的部署方式是 systemd 与原生 Linux 二进制（`server/deploy/systemd/`，实现时再添加）。Docker 镜像是支持的次要产物，不是这台部署的主路径。文档不记录主机名。
