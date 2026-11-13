#!/bin/bash
# Creates the RS256 key pair for access tokens (SEC-02). Never commit the output.
# Usage: deploy/gen-jwt-keys.sh /opt/wms/keys
set -euo pipefail
dir="${1:?usage: gen-jwt-keys.sh <dir>}"
mkdir -p "$dir"
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$dir/jwt.key"
openssl pkey -in "$dir/jwt.key" -pubout -out "$dir/jwt.pub"
echo "Created $dir/jwt.key (private, keep secret) and $dir/jwt.pub"
