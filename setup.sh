#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
info() { printf '\033[36m%s\033[0m\n' "$*"; }

command -v docker >/dev/null 2>&1 || die "docker not found"
docker compose version >/dev/null 2>&1 || die "docker compose v2 not found"

if [ ! -f .env ]; then
  info "generating .env"
  cat > .env <<EOF
TZ=Asia/Shanghai
PANEL_PORT=8443
PROXY_PORT=24443
PUBLIC_HOST=
ADMIN_USER=admin
ADMIN_PASSWORD=$(openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | cut -c1-20)
EOF
  chmod 600 .env
fi

set -a
# shellcheck disable=SC1091
. ./.env
set +a

mkdir -p data/tls data/panel

info "building and starting control plane"
docker compose up -d --build

info "waiting for panel"
ready=0
for _ in $(seq 1 90); do
  if curl -fsSk "https://127.0.0.1:${PANEL_PORT}/api/health" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done

host=$(curl -s4 --max-time 5 https://api.ipify.org 2>/dev/null || true)
[ -n "${host}" ] || host="<server-ip>"

printf '\n'
printf 'Panel   : https://%s:%s\n' "${host}" "${PANEL_PORT}"
printf 'Username: %s\n' "${ADMIN_USER}"
printf 'Password: %s\n' "${ADMIN_PASSWORD}"
if [ "${ready}" -eq 1 ]; then
  printf 'Status  : running\n'
else
  printf 'Status  : not ready yet; check logs with: docker compose logs -f api\n'
fi

printf '\nProxy port : %s (TCP; allow it in the firewall/security group)\n' "${PROXY_PORT}"
printf 'If PUBLIC_HOST is left empty, the server auto-detects its public IP; you can also set it manually in .env.\n'
