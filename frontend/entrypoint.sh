#!/bin/sh
set -e

if [ ! -f /etc/nginx/tls/cert.pem ] || [ ! -f /etc/nginx/tls/key.pem ]; then
  mkdir -p /etc/nginx/tls
  openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
    -keyout /etc/nginx/tls/key.pem \
    -out /etc/nginx/tls/cert.pem \
    -subj "/CN=gpn-panel" >/dev/null 2>&1
  chmod 600 /etc/nginx/tls/key.pem
fi

exec nginx -g 'daemon off;'
