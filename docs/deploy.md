# Running in production (prod profile)

The backend starts in production mode with `SPRING_PROFILES_ACTIVE=prod` (file:
`backend/src/main/resources/application-prod.properties`). The Nginx, HTTPS, systemd and backup setup comes with the
deploy task; this page lists what the app itself expects.

## Environment variables

| Variable | Needed | Default | What it is |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | yes | – | `prod` |
| `WMS_APP_PASSWORD` | yes | – | MySQL password of `wms_app` (runtime user) |
| `WMS_FLYWAY_PASSWORD` | yes | – | MySQL password of `wms_flyway` (schema changes only) |
| `WMS_APP_BASE_URL` | yes | – (start fails without it) | Public address of the app, e.g. `https://wms.example.lk`; invite links point here |
| `WMS_DB_HOST` / `WMS_DB_PORT` | no | `localhost` / `3308` | MySQL (the `wms-mysql` container) |
| `WMS_KEYS_DIR` | no | `/opt/wms/keys` | `jwt.key` and `jwt.pub` (make them with `deploy/gen-jwt-keys.sh`; mode 600, owner wms) |
| `WMS_FILES_DIR` | no | `/var/lib/wms/files` | Uploaded product pictures, outside the web root |
| `WMS_LOG_FILE` | no | `/var/log/wms/wms.log` | Log file (directory must exist and belong to the wms user) |
| `WMS_PORT` / `WMS_MANAGEMENT_PORT` | no | `8080` / `8081` | App and health ports |

Secrets go in an env file readable only by the service user (e.g. `/etc/wms/wms.env`, mode 600), never in Git.

## Ports

- **8080, 127.0.0.1 only:** the app (`/api`, `/ws`). Nginx is the only way in from outside.
- **8081, 127.0.0.1 only:** Actuator, `GET /actuator/health` only (`{"status":"UP"}`, no details). For the systemd
  health check and monitoring on the server; Nginx never forwards it.
- No Swagger/OpenAPI in the build (`NoSwaggerTest`).

## Logs

- Console → journald (`journalctl -u wms`), and the file `WMS_LOG_FILE`.
- The file rotates at 20 MB, keeps 14 days and at most 1 GB in all. Each line carries the request's correlation id
  (`[01M7...]`), the same id an error response shows the user — search the log for it.
- Tokens, passwords and password hashes are never logged.

## Memory (4 GB server)

Run the JVM with `-Xms256m -Xmx512m` (set in the systemd unit). The app idles at about 200 MB; exports stream, so
large files do not grow the heap.

## Server install checklist (when the server exists)

Every file below is in `deploy/` and was rehearsed on the dev PC (2026-11-17, see "Local rehearsal").

1. Packages: Java 17 runtime, nginx, certbot (`python3-certbot-nginx`), Docker (for `wms-mysql`).
2. User and folders: `useradd --system --home /opt/wms wms`; create `/opt/wms`, `/etc/wms` (700),
   `/var/log/wms` and `/var/lib/wms/files` owned by wms, `/var/backups/wms` (700, root).
3. Database: `deploy/docker-compose.yml` (`wms-mysql`, port 3308 on localhost only) with `deploy/.env`.
4. Keys: `deploy/gen-jwt-keys.sh /opt/wms/keys` (owner wms, 600). Backup key:
   `openssl rand -base64 48 > /etc/wms/backup.key && chmod 600 /etc/wms/backup.key`. **Keep a copy of this key off
   the server** (a password manager): without it the backups cannot be opened.
5. App: `./mvnw -DskipTests package`, copy `backend/target/*.jar` to `/opt/wms/wms.jar`; write `/etc/wms/wms.env`
   (variables in the table above, mode 600); copy `deploy/systemd/wms.service` to `/etc/systemd/system/`,
   `systemctl enable --now wms`; check `curl -s 127.0.0.1:8081/actuator/health`.
6. Web app: `npm ci && npm run build` in `frontend/`, copy `dist/` to `/var/www/wms`.
7. Nginx: copy `deploy/nginx/wms-common.conf` and `wms-headers.conf` to `/etc/nginx/snippets/`, `wms.conf` to
   `/etc/nginx/sites-available/wms` (set the real domain), enable it, `nginx -t && systemctl reload nginx`, then
   `certbot --nginx -d <domain>`.
8. Backups: copy `deploy/backup.sh` and `restore.sh` to `/opt/wms/` (700, root) and the two `wms-backup.*` units to
   systemd; `systemctl enable --now wms-backup.timer`; run one by hand (`systemctl start wms-backup`) and check that
   `journalctl -u wms-backup` ends with `backup: ok`.
9. Restore drill once a month (SEC-83), below. Then repeat the ZAP baseline against the real domain.

## Backups and restore

`backup.sh` (nightly at 01:30 via `wms-backup.timer`): `mysqldump --single-transaction` of the `wms` database
(triggers included) plus the product pictures, as one `tar.gz` encrypted with AES-256 (`openssl enc -aes-256-cbc
-pbkdf2 -iter 200000`) using `/etc/wms/backup.key`, with a `.sha256` beside it; kept **30 days**; optional copy to
`BACKUP_COPY_TO` (another disk or a mounted share).

**Restore drill** (rehearsed locally 2026-11-17: stock rows, movements, orders, products, audit lines and total
quantity all matched; the ledger triggers and pictures came back; a wrong key failed before anything was loaded):

```sh
# 1. a throw-away MySQL to restore into (never test on the live one)
docker run -d --name wms-restore-test -e MYSQL_ROOT_PASSWORD="$(openssl rand -hex 16)" mysql:8.4 --skip-log-bin
# 2. restore the newest backup into it (checks the sha256, decrypts, loads, copies pictures)
RESTORE_CONTAINER=wms-restore-test FILES_DIR=/tmp/restored-files /opt/wms/restore.sh /var/backups/wms/wms-<stamp>.tar.gz.enc
# 3. compare a few counts with the live database, then remove the test container
docker rm -f wms-restore-test
```

A real restore after a disaster: `systemctl stop wms`, run `restore.sh <file>` against `wms-mysql` (the default),
`systemctl start wms`.

## Incident response (SEC-84)

- **Sign everyone out** (stolen password or token suspected): delete all refresh tokens and sessions,
  `DELETE FROM refresh_token; DELETE FROM user_session;` in the `wms` database (as root in `wms-mysql`). Access
  tokens expire within minutes and nobody can refresh. For one user: "Sign out on all devices", or disable the user
  on the Users page.
- **Rotate the token keys** (private key leaked): `deploy/gen-jwt-keys.sh /opt/wms/keys` makes a new pair, then
  `systemctl restart wms`; every existing token stops working at once and users sign in again.
- **Rotate database passwords:** change `wms_app` / `wms_flyway` in MySQL, update `/etc/wms/wms.env`, restart.
- **Restore data:** as above. Then read the Audit log page for what happened before.

## Local rehearsal (until there is a server)

`deploy/local/`: the same nginx rules in a container in front of the dev backend, with a self-signed certificate.
Run `sh make-cert.sh`, build the web app, `docker compose up -d`, open `https://localhost:8443` and accept the
certificate warning once. One difference from the server: Tomcat trusts `X-Forwarded-Proto` only from `127.0.0.1`
(where nginx runs on the server), so in the container rehearsal the live-updates WebSocket sees `http` and its
same-origin check may refuse; on the server it does not.
