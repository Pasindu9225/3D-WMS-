#!/usr/bin/env bash
# SEC-83/84: nightly encrypted backup of the WMS database and product pictures.
#   - mysqldump --single-transaction from the wms-mysql container (consistent, no table locks)
#   - the pictures folder
#   - one tar.gz, encrypted with AES-256 (openssl, PBKDF2) using a key file that is never in Git
#   - kept BACKUP_KEEP_DAYS days (30); optional copy elsewhere (BACKUP_COPY_TO, e.g. a mounted disk)
# Settings (env or /etc/wms/backup.env): BACKUP_DIR, BACKUP_KEY_FILE, FILES_DIR, MYSQL_CONTAINER, BACKUP_KEEP_DAYS.
# Restore: restore.sh <file>. Exit code 0 = good (systemd shows failures; monitoring can watch the log line).
set -euo pipefail

[ -f /etc/wms/backup.env ] && . /etc/wms/backup.env
BACKUP_DIR="${BACKUP_DIR:-/var/backups/wms}"
BACKUP_KEY_FILE="${BACKUP_KEY_FILE:-/etc/wms/backup.key}"
FILES_DIR="${FILES_DIR:-/var/lib/wms/files}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-wms-mysql}"
BACKUP_KEEP_DAYS="${BACKUP_KEEP_DAYS:-30}"   # SEC-83: 30 days

[ -r "$BACKUP_KEY_FILE" ] || { echo "backup: key file $BACKUP_KEY_FILE missing or unreadable" >&2; exit 2; }
umask 077
mkdir -p "$BACKUP_DIR"
stamp="$(date -u +%Y%m%d-%H%M%S)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# The dump runs inside the container with its own root password (never on a command line seen by ps on the host).
docker exec "$MYSQL_CONTAINER" sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers \
    --set-gtid-purged=OFF --databases wms' > "$work/wms.sql"
[ -s "$work/wms.sql" ] || { echo "backup: empty dump" >&2; exit 3; }
mkdir -p "$work/files"
[ -d "$FILES_DIR" ] && cp -a "$FILES_DIR/." "$work/files/"

out="$BACKUP_DIR/wms-$stamp.tar.gz.enc"
tar -C "$work" -czf - wms.sql files | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass "file:$BACKUP_KEY_FILE" -out "$out"
(cd "$BACKUP_DIR" && sha256sum "$(basename "$out")" > "$(basename "$out").sha256")   # name only, so restore.sh can check it from that folder

if [ -n "${BACKUP_COPY_TO:-}" ]; then
    cp "$out" "$out.sha256" "$BACKUP_COPY_TO/"
fi
find "$BACKUP_DIR" -name 'wms-*.tar.gz.enc*' -mtime +"$BACKUP_KEEP_DAYS" -delete

echo "backup: ok $out $(du -h "$out" | cut -f1)"
