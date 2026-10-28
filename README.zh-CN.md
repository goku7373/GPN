# GPN 控制台

[English](README.md) · **简体中文**

单节点网络出口服务器的自托管控制面板。在一个网页里同时管理 **VLESS + Reality** 与 **IKEv2** 接入：创建客户端账号、下发分享链接与订阅地址、查看实时连接与流量、操作 sing-box 内核。

> ⚠️ **使用前请先阅读[法律声明](#法律声明)。**

---

## 设计原理

### 控制面与数据面分离

系统刻意拆成 **控制面** 与 **数据面** 两层：

```
                 ┌──────────────────────────────────────────┐
   浏览器 ───────►  gpn-web（nginx，TLS）  :8443 → :443      │  控制面
                 │      │ /api/ 反向代理                   │
                 │      ▼                                   │
                 │  gpn-api（Spring Boot）  :8080           │
                 │      ├─ 读写 ./data/panel                │
                 │      ├─ 拉起 sing-box ──► :24443 ────────┼──► 数据面（VLESS + Reality）
                 │      └─ 写 /etc/swanctl/conf.d ──────────┼──► strongSwan（宿主机，IKEv2）
                 └──────────────────────────────────────────┘
```

- **数据面** 交给成熟守护进程：VLESS + Reality 由 sing-box 处理，IKEv2 由 strongSwan 处理。面板本身不转发用户流量，因此重启面板不会中断已建立的隧道。
- **控制面** 是一个 Spring Boot 服务加一套静态 SPA。它只负责*生成配置*、*采集状态*、*提供界面*。

### 面板不接触 Docker socket

API 容器无法访问宿主机 Docker 守护进程，宿主机联动完全依赖 **文件与只读挂载**：

| 宿主机路径 | 容器内路径 | 用途 |
| --- | --- | --- |
| `/etc/swanctl/conf.d` | `/ikev2` | 面板写入 `gpn-users.conf`；宿主机 systemd path 单元在文件变更时执行 `swanctl --load-all` |
| `/etc/swanctl/x509ca` | `/ikev2-ca`（只读） | 打包进 `.mobileconfig` 描述文件，供 iOS 客户端安装 CA |
| `/var/lib/gpn` | `/sessions`（只读） | 宿主机每 5 秒导出 `swanctl --list-sas --raw`，面板解析后展示在线设备 |
| `./data/panel` | `/data` | `state.json`（Reality 密钥对、客户端凭据）、sing-box 配置与日志 |

这样权限被压到最低：即使面板被攻破，也无法随意启动容器，或读取挂载范围之外的主机凭据。

### 状态是一份 JSON

`state.json` 保存 Reality 密钥对、short ID，以及每个客户端（UUID、IKEv2 密码、启用/停用/封禁标记、流量备注）。写入采用原子方式（先写临时文件再移动），进程崩溃不会留下半截文件；删除后下次启动会自动重建一套新的 Reality 密钥。

### 流量是实测出来的，不是估算

监控数据来自两条独立通道：

- **VLESS**：通过 sing-box 的 Clash API（`127.0.0.1:19090`，每次启动生成随机 secret）读取活动连接与字节计数。面板轮询后按字节差计算速率。
- **IKEv2**：宿主机 `swanctl --list-sas --raw` 导出 + 宿主机 `conntrack` 中转发流的统计。

因为速率由两次采样相减得到，页面首次加载时必然全是 `0 B/s`，第二次轮询（5 秒后）才有真实值。连续 10 秒没有字节增长的连接会从「活动」列表中移除，因此空闲的 `ESTABLISHED` 与 `TIME_WAIT` 残留不会污染列表。

### 刻意划定的范围

- **单节点**：没有集群、没有高可用、没有多租户隔离。
- **面板使用自签证书**：若要公网长期使用，建议在前面挂真实证书。
- **只支持两种协议**：VLESS + Reality，以及 IKEv2 / EAP-MSCHAPv2。

---

## 法律声明

本项目公开发布，**仅供学习与研究网络工程与运维原理** —— 包括协议设计、连接跟踪、流量统计与配置管理。

**使用本软件即表示你同意：**

1. 仅在**自己拥有或获得明确授权**的基础设施上使用，且仅以你所在地及服务器所在地法律法规允许的方式使用。
2. **不得**用于任何违法活动，包括但不限于未授权访问、流量窃听、欺诈或传播违法内容。
3. **不得**在未独立确认所在司法辖区合法性、且未遵守托管服务商服务条款的前提下，将其转售、再许可或作为付费商业服务运营。
4. 运行网络服务所需的备案、许可、资质，以及遵守服务商可接受使用政策，全部由你自行负责。
5. 作者**不提供任何形式的担保**，对因使用本软件造成的任何直接或间接损失、法律后果、服务中断**不承担任何责任**。完整免责条款见许可证文件。

如果你不确定自己的用途是否合法，**请不要部署**。

---

## 项目结构

```
gpn/
├── docker-compose.yml          # 两个服务：web（nginx）+ api（Spring Boot + sing-box）
├── setup.sh                    # 生成 .env，构建并启动整套服务
├── frontend/
│   ├── Dockerfile              # Vite 构建 → nginx 运行
│   ├── entrypoint.sh           # 自签证书初始化后启动 nginx
│   ├── nginx.conf              # TLS、/api/ 反向代理、SPA 回退
│   ├── index.html
│   ├── vite.config.js
│   ├── package.json
│   ├── dist/                   # 已编译界面（提交在仓库中的产物）
│   └── src/
│       ├── main.js
│       ├── Root.vue            # 启动与登录态判断
│       ├── App.vue             # 外壳、路由与全部视图（总览 / 连接 / 监控 / 设置）
│       ├── api.js              # fetch 封装、凭据与错误处理
│       ├── styles.css
│       ├── theme.css           # 设计变量、浅色/深色主题
│       ├── components/Toast.vue
│       ├── views/LoginView.vue
│       └── composables/        # useTheme、useToast
├── backend-java/
│   ├── Dockerfile              # JRE 17 + sing-box 可执行文件
│   ├── pom.xml
│   ├── target/                 # 构建输出（提交在仓库中的产物：gpn-panel-1.0.0.jar）
│   └── src/main/
│       ├── resources/application.yml
│       └── java/com/gpn/panel/
│           ├── PanelApplication.java
│           ├── auth/           # 会话 Cookie 认证、恒定时间凭据校验
│           ├── proxy/          # sing-box、IKEv2、conntrack、IP 归属地、分享链接
│           ├── state/          # PanelState、ClientAccount、原子化 StateStore
│           └── web/            # AuthController、ProxyController（REST API）
└── data/                       # 仅运行时（已 gitignore）：state.json、TLS 证书、sing-box 配置
```

### API 一览

| 端点 | 用途 |
| --- | --- |
| `POST /api/login`、`POST /api/logout`、`GET /api/me` | 会话认证 |
| `GET /api/overview` | 面板状态、内核状态、账号汇总 |
| `GET /api/sessions` | 在线设备（VLESS + IKEv2） |
| `POST /api/clients` | 创建账号 |
| `POST /api/clients/{id}/toggle\|rotate\|ban\|unban` | 账号生命周期操作 |
| `DELETE /api/clients/{id}` | 删除账号 |
| `GET /api/clients/{id}/share` | 分享链接、二维码内容、订阅地址 |
| `GET /api/monitor/vless`、`GET /api/monitor/ikev2/{vpnIp}` | 按设备查看连接 |
| `POST /api/proxy/restart` | 重启 sing-box 内核 |
| `GET /api/sub/{token}` | 客户端订阅（纯文本） |
| `GET /api/ikev2/profile/{token}.mobileconfig` | iOS IKEv2 描述文件 |

---

## 在全新机器上部署

### 1. 环境要求

- 具备公网 IPv4 的 Linux 服务器（在 Debian/Ubuntu 上验证）
- Docker Engine + Docker Compose **v2**
- 可在服务商防火墙放行的端口：

| 端口 | 协议 | 用途 |
| --- | --- | --- |
| `8443` | TCP | Web 面板（可通过 `PANEL_PORT` 修改） |
| `24443` | TCP | VLESS + Reality（可通过 `PROXY_PORT` 修改） |
| `500` | UDP | IKEv2 |
| `4500` | UDP | IKEv2 NAT-T |

### 2. 克隆并启动

```bash
git clone https://github.com/goku7373/GPN.git gpn
cd gpn
./setup.sh
```

`setup.sh` 会生成随机管理员密码的 `.env`，构建并启动两个镜像，等待面板可用，最后打印地址、用户名与密码。重复执行是安全的：已存在的 `.env` 会被复用。

也可以手动执行：

```bash
docker compose up -d --build
docker compose logs -f api
```

### 3. 配置

编辑 `.env` 后执行 `docker compose up -d` 生效：

```ini
PANEL_PORT=8443            # 面板端口
PROXY_PORT=24443           # VLESS 监听端口
PUBLIC_HOST=               # 留空则自动探测公网 IP
REALITY_DEST=www.apple.com # 伪装 SNI，必须是稳定知名站点
ADMIN_USER=admin
ADMIN_PASSWORD=...         # 由 setup.sh 生成
TZ=Asia/Shanghai
```

然后访问 `https://<服务器IP>:8443`，接受自签证书警告后登录。

### 4. 可选：在宿主机启用 IKEv2

VLESS 开箱即用，因为 sing-box 运行在 `api` 容器内。IKEv2 必须使用宿主机上的 strongSwan，因为它要独占 UDP 500/4500：

```bash
apt install -y strongswan strongswan-pki
mkdir -p /etc/swanctl/{conf.d,x509,x509ca,rsa} /var/lib/gpn
systemctl enable --now strongswan-starter
```

面板依赖宿主机上的两个辅助单元（路径按发行版自行调整）：

```bash
# /etc/systemd/system/gpn-ikev2-reload.path
[Unit]
Description=Reload strongSwan when the panel updates EAP accounts

[Path]
PathModified=/etc/swanctl/conf.d/gpn-users.conf

[Install]
WantedBy=multi-user.target
```

```bash
# /etc/systemd/system/gpn-ikev2-reload.service
[Unit]
Description=swanctl load-all

[Service]
Type=oneshot
ExecStart=/usr/sbin/swanctl --load-all
```

```bash
# /etc/systemd/system/gpn-sas.service + gpn-sas.timer（每 5 秒执行一次）
# 导出会话供面板经 /var/lib/gpn 只读读取
ExecStart=/bin/sh -c '/usr/sbin/swanctl --list-sas --raw > /var/lib/gpn/ike-sas.txt.tmp && mv /var/lib/gpn/ike-sas.txt.tmp /var/lib/gpn/ike-sas.txt'
```

```bash
systemctl enable --now gpn-ikev2-reload.path gpn-sas.timer
```

同时把服务器证书、私钥与 CA 分别放到 `/etc/swanctl/x509`、`/etc/swanctl/rsa`、`/etc/swanctl/x509ca`。缺失时面板仍可用于 VLESS，只是 IKEv2 相关区域会显示为不可用。

### 5. 放行防火墙

放行 TCP `8443`、TCP `24443`、UDP `500`、UDP `4500`。**不要**对外暴露 `8080` —— 后端未做 TLS，只能经 nginx 访问。

### 6. 验证

```bash
curl -k https://127.0.0.1:8443/api/health     # {"status":"ok"}
nc -z <服务器IP> 24443                       # VLESS 端口可达
```

登录后创建客户端账号，把生成的链接或订阅导入客户端。iOS 需要先在「设置 → 通用 → VPN 与设备管理」安装 CA 证书，并在「证书信任设置」中信任它，再安装 `.mobileconfig` 描述文件。

### 7. 升级

```bash
git pull
docker compose up -d --build
```

运行数据都在 `./data` 中，会保留。回退时切换到之前的标签（`git checkout v1.0.0`）后重新构建即可。

---

## 参与贡献

欢迎提交 Issue 与 Pull Request：

1. Fork 仓库并新建分支：`git checkout -b feature/your-change`
2. 保持改动聚焦，并在 PR 中说明动机
3. 等待审核 —— `main` 已开启分支保护，所有改动都必须经过审核的 Pull Request 合并

---

## 许可证

见仓库中的许可证文件。若不存在，则版权归作者所有，并适用上述免责声明。
