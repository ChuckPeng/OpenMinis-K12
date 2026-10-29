# ADR-0002 自研 Go 服务，不采用 jianxcao/notify

- 状态：已接受
- 日期：2026-10-29

## 背景

远程解锁需要按钮卡片、回调和更新卡片。已部署的开源通知服务 jianxcao/notify 只能出站发送 text/news，没有这些能力。

## 决定

家长自托管一个 Go 服务，产品名 OpenMinis-K12 服务端，技术标识 `openminis-k12-server`。单二进制内含 SQLite 与管理界面。所有者用 systemd 运行该二进制。Docker 镜像仍然发布，作为次要产物。许可证 GPL-3.0，与平板相同。

## 后果

工作量在 M5a/M5b/M5c。不引入第三方加密库。`server/README.md` 写明 GPL-3.0。
