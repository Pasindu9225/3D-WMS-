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
