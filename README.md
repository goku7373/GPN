# GPN Console

**English** · [简体中文](README.zh-CN.md)

A self-hosted control plane for a single-node network egress server. It manages **VLESS + Reality** and **IKEv2** access from one web panel: create client accounts, hand out share links and subscription URLs, watch live connections and traffic, and operate the sing-box core.

> ⚠️ **Read the [Legal Notice](#legal-notice) before using this project.**

---

## Design principles

### Two planes, separated by design

The system deliberately splits into a **control plane** and a **data plane**:

```
                 ┌──────────────────────────────────────────┐
   browser ──────►  gpn-web (nginx, TLS)  :8443 → :443      │  control plane
                 │      │ /api/ reverse proxy               │
                 │      ▼                                   │
                 │  gpn-api (Spring Boot)  :8080            │
                 │      ├─ reads/writes ./data/panel        │
                 │      ├─ runs sing-box  ──► :24443  ──────┼──► data plane (VLESS + Reality)
                 │      └─ writes /etc/swanctl/conf.d ──────┼──► strongSwan (host, IKEv2)
                 └──────────────────────────────────────────┘
```

- **Data plane** is handled by mature daemons: sing-box for VLESS + Reality, strongSwan for IKEv2. The panel never proxies user traffic itself, so a panel restart does not interrupt existing tunnels.
- **Control plane** is a Spring Boot service plus a static SPA. It only *generates configuration*, *collects status*, and *serves the UI*.

### The panel never touches the Docker socket

The API container has no access to the host daemon. Host integration happens through **files and read-only mounts**:

| Host path | Container path | Purpose |
| --- | --- | --- |
| `/etc/swanctl/conf.d` | `/ikev2` | Panel writes `gpn-users.conf`; a host systemd path unit runs `swanctl --load-all` on change |
| `/etc/swanctl/x509ca` | `/ikev2-ca` (ro) | CA certificate handed to iOS clients as a `.mobileconfig` profile |
| `/var/lib/gpn` | `/sessions` (ro) | Host dumps `swanctl --list-sas --raw` every 5 seconds; panel parses it for online devices |
| `./data/panel` | `/data` | `state.json` (Reality keypair, client credentials), sing-box config and logs |

This keeps privilege low: even if the panel is compromised, it cannot start arbitrary containers or read host credentials beyond what is mounted.

### State is a single JSON document

`state.json` stores the Reality keypair, the short ID, and every client (UUID, IKEv2 password, enabled/disabled/banned flags, traffic notes). Writes are atomic (write-temp-then-move), so a crash never leaves a half-written file. Deleting the file regenerates a fresh Reality keypair on next start.

### Live traffic is measured, not guessed

Two independent sources feed the monitoring views:

- **VLESS**: the sing-box Clash API (`127.0.0.1:19090`, random per-process secret) lists active connections with byte counters. The panel polls it and derives per-connection rates from byte deltas.
- **IKEv2**: the host's `swanctl --list-sas --raw` dump plus host `conntrack` for forwarded flows.

Because rates are computed from two samples, the first poll after page load always shows `0 B/s`; the second poll (5 s later) shows real values. Connections that stop transferring bytes for 10 seconds are dropped from the "active" list, so idle `ESTABLISHED` entries and `TIME_WAIT` leftovers do not pollute the view.

### Deliberate scope limits

- **Single node.** No clustering, no HA, no multi-tenant isolation.
- **Self-signed TLS** for the panel; terminate a real certificate in front of it if you expose it publicly.
- **Two protocols only**: VLESS + Reality and IKEv2 / EAP-MSCHAPv2.

---

## Legal notice

This project is published **for learning and research into network engineering and operations** — protocol design, connection tracking, traffic accounting, and configuration management.

**By using this software you agree that:**

1. You will use it **only on infrastructure you own or are explicitly authorized to operate**, and only in ways permitted by the laws and regulations that apply to you and to your server's location.
2. You will **not** use it for any illegal activity, including but not limited to unauthorized access, traffic interception, fraud, or distributing unlawful content.
3. You will **not** resell, sublicense, or operate it as a paid commercial service without independently verifying that doing so is lawful in your jurisdiction and compliant with your hosting provider's terms of service.
4. You are solely responsible for obtaining any licenses, registrations, or permits your jurisdiction requires for running a network service, and for complying with your provider's acceptable-use policy.
5. The authors provide **no warranty of any kind** and accept **no liability** for any direct or indirect damage, legal consequence, or service interruption arising from use of this software. See the license file for the full disclaimer.

If you are unsure whether your intended use is lawful, **do not deploy it**.

---

## Project structure

```
gpn/
├── docker-compose.yml          # Two services: web (nginx) + api (Spring Boot + sing-box)
├── setup.sh                    # Generates .env, builds and starts the stack
├── frontend/
│   ├── Dockerfile              # Vite build → nginx runtime
│   ├── entrypoint.sh           # Self-signed cert bootstrap, then start nginx
│   ├── nginx.conf              # TLS, /api/ reverse proxy, SPA fallback
│   ├── index.html
│   ├── vite.config.js
│   ├── package.json
│   ├── dist/                   # Compiled UI (committed artifact)
│   └── src/
│       ├── main.js
│       ├── Root.vue            # Boot + auth gating
│       ├── App.vue             # Shell, routing, all views (dashboard / sessions / monitor / settings)
│       ├── api.js              # fetch wrapper, credentials + error handling
│       ├── styles.css
│       ├── theme.css           # Design tokens, light/dark themes
│       ├── components/Toast.vue
│       ├── views/LoginView.vue
│       └── composables/        # useTheme, useToast
├── backend-java/
│   ├── Dockerfile              # JRE 17 + sing-box binary
│   ├── pom.xml
│   ├── target/                 # Build output (committed artifact: gpn-panel-1.0.0.jar)
│   └── src/main/
│       ├── resources/application.yml
│       └── java/com/gpn/panel/
│           ├── PanelApplication.java
│           ├── auth/           # Session cookie auth, constant-time credential check
│           ├── proxy/          # sing-box, IKEv2, conntrack, geo lookup, share links
│           ├── state/          # PanelState, ClientAccount, atomic StateStore
│           └── web/            # AuthController, ProxyController (REST API)
└── data/                       # Runtime only (gitignored): state.json, TLS certs, sing-box config
```

### API surface

| Endpoint | Purpose |
| --- | --- |
| `POST /api/login`, `POST /api/logout`, `GET /api/me` | Session auth |
| `GET /api/overview` | Panel state, core status, account summary |
| `GET /api/sessions` | Online devices (VLESS + IKEv2) |
| `POST /api/clients` | Create account |
| `POST /api/clients/{id}/toggle\|rotate\|ban\|unban` | Account lifecycle |
| `DELETE /api/clients/{id}` | Remove account |
| `GET /api/clients/{id}/share` | Share links, QR payload, subscription URL |
| `GET /api/monitor/vless`, `GET /api/monitor/ikev2/{vpnIp}` | Per-device flows |
| `POST /api/proxy/restart` | Restart the sing-box core |
| `GET /api/sub/{token}` | Client subscription (plain text) |
| `GET /api/ikev2/profile/{token}.mobileconfig` | iOS IKEv2 profile |

---

## Deployment on a fresh machine

### 1. Requirements

- Linux server with a public IPv4 address (tested on Debian/Ubuntu)
- Docker Engine + Docker Compose **v2**
- Ports you can open in your provider's firewall:

| Port | Protocol | Purpose |
| --- | --- | --- |
| `8443` | TCP | Web panel (configurable via `PANEL_PORT`) |
| `24443` | TCP | VLESS + Reality (configurable via `PROXY_PORT`) |
| `500` | UDP | IKEv2 |
| `4500` | UDP | IKEv2 NAT-T |

### 2. Clone and start

```bash
git clone https://github.com/goku7373/GPN.git gpn
cd gpn
./setup.sh
```

`setup.sh` creates `.env` with a random admin password, builds both images, starts them, and waits until the panel answers. It prints the URL, username, and password at the end. Re-running it is safe: an existing `.env` is reused.

If you prefer to do it manually:

```bash
docker compose up -d --build
docker compose logs -f api
```

### 3. Configuration

Edit `.env` and restart with `docker compose up -d`:

```ini
PANEL_PORT=8443            # web panel port
PROXY_PORT=24443           # VLESS listening port
PUBLIC_HOST=               # leave empty to auto-detect the public IP
REALITY_DEST=www.apple.com # camouflage SNI; must be a stable, well-known site
ADMIN_USER=admin
ADMIN_PASSWORD=...         # generated by setup.sh
TZ=Asia/Shanghai
```

Then open `https://<server-ip>:8443`, accept the self-signed certificate warning, and log in.

### 4. Optional: enable IKEv2 on the host

VLESS works out of the box because sing-box runs inside the `api` container. IKEv2 needs strongSwan on the **host**, because it must own UDP 500/4500:

```bash
apt install -y strongswan strongswan-pki
mkdir -p /etc/swanctl/{conf.d,x509,x509ca,rsa} /var/lib/gpn
systemctl enable --now strongswan-starter
```

Two host helpers are expected by the panel (adjust paths if your distro differs):

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
# /etc/systemd/system/gpn-sas.service  +  gpn-sas.timer (every 5s)
# Writes the session dump the panel reads through /var/lib/gpn
ExecStart=/bin/sh -c '/usr/sbin/swanctl --list-sas --raw > /var/lib/gpn/ike-sas.txt.tmp && mv /var/lib/gpn/ike-sas.txt.tmp /var/lib/gpn/ike-sas.txt'
```

```bash
systemctl enable --now gpn-ikev2-reload.path gpn-sas.timer
```

Also place the server certificate, key, and CA under `/etc/swanctl/x509`, `/etc/swanctl/rsa`, `/etc/swanctl/x509ca`. Without these, the panel still works for VLESS; the IKEv2 sections simply report as unavailable.

### 5. Open the firewall

Allow TCP `8443`, TCP `24443`, UDP `500`, UDP `4500`. Do **not** expose `8080` — the API speaks plain HTTP and is only reachable through nginx.

### 6. Verify

```bash
curl -k https://127.0.0.1:8443/api/health     # {"status":"ok"}
nc -z <server-ip> 24443                      # VLESS port reachable
```

Log in, create a client account, and import the generated link or subscription into a client app. For iOS, install the CA certificate in *Settings → General → VPN & Device Management* and enable it under *Certificate Trust Settings* before installing the `.mobileconfig` profile.

### 7. Upgrade

```bash
git pull
docker compose up -d --build
```

Existing data lives in `./data` and is preserved. To roll back, check out a previous tag (`git checkout v1.0.0`) and rebuild.

---

## Contributing

Issues and pull requests are welcome. Please:

1. Fork the repository and create a branch: `git checkout -b feature/your-change`
2. Keep changes focused and describe the motivation in the PR
3. Wait for review — `main` is protected, so every change lands through a reviewed pull request

---

## License

See the repository license file. If none is present, all rights are reserved by the author and the disclaimers above apply.
