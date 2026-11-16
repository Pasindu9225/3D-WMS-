#!/usr/bin/env bash
# Restores a backup made by backup.sh:   restore.sh /var/backups/wms/wms-20261116-013000.tar.gz.enc
# 1. checks the sha256, 2. decrypts with the same key file, 3. loads the SQL into the MySQL container named in
# RESTORE_CONTAINER (default wms-mysql — stop the app first!), 4. puts the pictures back into FILES_DIR.
# Rehearse on a throw-away container first (see docs/deploy.md, "Restore").
set -euo pipefail

[ -f /etc/wms/backup.env ] && . /etc/wms/backup.env
file="${1:?usage: restore.sh <backup.tar.gz.enc>}"
BACKUP_KEY_FILE="${BACKUP_KEY_FILE:-/etc/wms/backup.key}"
FILES_DIR="${FILES_DIR:-/var/lib/wms/files}"
RESTORE_CONTAINER="${RESTORE_CONTAINER:-wms-mysql}"

if [ -f "$file.sha256" ]; then
    (cd "$(dirname "$file")" && sha256sum -c "$(basename "$file").sha256")
fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$BACKUP_KEY_FILE" -in "$file" | tar -C "$work" -xzf -

docker exec -i "$RESTORE_CONTAINER" sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < "$work/wms.sql"
if [ -d "$work/files" ] && [ -n "$(ls -A "$work/files")" ]; then
    mkdir -p "$FILES_DIR"
    cp -a "$work/files/." "$FILES_DIR/"
fi
echo "restore: ok from $file into $RESTORE_CONTAINER"
