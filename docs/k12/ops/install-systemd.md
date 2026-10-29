# 安装（systemd 主路径）

目标：全新主机约 10 分钟内走完安装向导（R1）。文档不写主机名或真实 IP。

## 前置

- Linux amd64 或 arm64
- 已有 HTTPS 反向代理（终结 TLS）
- 可写目录安装二进制与数据

## 步骤

1. 创建系统用户与目录：

```bash
sudo useradd --system --home /var/lib/openminis-k12 --shell /usr/sbin/nologin openminis-k12
sudo mkdir -p /var/lib/openminis-k12 /etc/openminis-k12
sudo cp server/deploy/systemd/config.example.yaml /etc/openminis-k12/config.yaml
# 编辑 config.yaml：填写 public_base_url 与 trusted_proxies（必填）
sudo chown -R openminis-k12:openminis-k12 /var/lib/openminis-k12
sudo chmod 750 /var/lib/openminis-k12
sudo chmod 640 /etc/openminis-k12/config.yaml
```

2. 安装二进制（从 release 资产或本地交叉编译）：

```bash
sudo install -o root -g root -m 755 openminis-k12-server /usr/local/bin/openminis-k12-server
sudo cp server/deploy/systemd/openminis-k12-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now openminis-k12-server
```

3. 反向代理路径（同一 `public_base_url`）：

| 路径前缀 | 转到 |
| --- | --- |
| `/api/v1/device/`、`/wecom/`、`/r/`、`/healthz`、`/readyz` | `127.0.0.1:8080`（设备端口） |
| `/`、`/api/v1/admin/`、`/metrics` | `127.0.0.1:8081`（管理端口） |

更具体的设备路径必须先于管理界面的 `/` 匹配。不要把 8080/8081 直接映射到公网。

4. 打开 `{public_base_url}/`，完成向导：设口令 → 填企微 → 测 token → 复制回调 URL / Token / AESKey → 添加可审批家长。

## CLI

```text
openminis-k12-server serve|migrate|backup|restore|healthcheck|reset-admin|--version
```

- `reset-admin <user> <password>`：忘记口令时使用（无 2FA）
- 升级：换二进制后重启；启动前自动备份再迁移（R10）
- 备份文件不含 `master.key`；恢复时密钥须另存另放（R9）

## Docker（次要）

见 `server/deploy/docker/`。容器端口仍只绑到本机代理。
