#!/bin/sh
# A self-signed certificate for the local rehearsal only (the server uses Let's Encrypt). Kept out of Git.
set -eu
cd "$(dirname "$0")"
mkdir -p certs
MSYS_NO_PATHCONV=1 openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
  -keyout certs/local.key -out certs/local.crt \
  -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,DNS:host.docker.internal,IP:127.0.0.1"
chmod 600 certs/local.key
echo "certs/local.crt and certs/local.key made"
