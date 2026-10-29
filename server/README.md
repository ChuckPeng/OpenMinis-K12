# OpenMinis-K12 服务端

技术标识 `openminis-k12-server`。模块路径 `github.com/ChuckPeng/OpenMinis-K12/server`。

**许可证：GPL-3.0-only**，与平板应用相同。派生作品保持 GPL-3.0。

## 范围（M5a）

- Go 1.22+、SQLite（`modernc.org/sqlite`）、明文 HTTP 双端口（设备 8080 / 管理 8081，默认回环）
- `public_base_url` + 必填 `trusted_proxies`
- 管理员 argon2id 口令登录（无 2FA）、会话 Cookie、CSRF
- 企微回调 `/wecom/callback/{callback_key}`（WXBizMsgCrypt）
- J12 按钮回调实测 harness + 文本/6 位备用码回落（J14）
- Vue 3 + Vite 管理界面（`web/`，`go:embed`）
- 部署：`deploy/systemd/` 主路径；`deploy/docker/` 次要

工程契约在仓库 `docs/k12/`。安装见 `docs/k12/ops/install-systemd.md`。J12 现场步骤见 `docs/k12/ops/j12-live-test.md`。

## 构建

```bash
cd server/web && npm install && npm run build:ci && cd ..
PKG=github.com/ChuckPeng/OpenMinis-K12/server/internal/version
CGO_ENABLED=0 go build -trimpath \
  -ldflags "-X ${PKG}.Version=1.0.0-m5a -X ${PKG}.VersionCode=1000005" \
  -o openminis-k12-server ./cmd/openminis-k12-server
./openminis-k12-server --version
```

CLI：`serve` · `migrate` · `backup` · `restore` · `healthcheck` · `reset-admin`

文档不记录主机名或真实家庭数据。
