#!/bin/bash
# Runs once, on first start of an empty data volume (SEC-27: least-privilege users).
set -euo pipefail
: "${WMS_FLYWAY_PASSWORD:?missing}" "${WMS_APP_PASSWORD:?missing}"

mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS wms CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Flyway: schema changes on wms only
CREATE USER IF NOT EXISTS 'wms_flyway'@'%' IDENTIFIED BY '${WMS_FLYWAY_PASSWORD}';
GRANT ALL PRIVILEGES ON wms.* TO 'wms_flyway'@'%';

-- App: data only. UPDATE/DELETE on stock_movement and audit_log is blocked by
-- triggers created in Flyway migrations (db-level grants can't exclude tables).
CREATE USER IF NOT EXISTS 'wms_app'@'%' IDENTIFIED BY '${WMS_APP_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE ON wms.* TO 'wms_app'@'%';

FLUSH PRIVILEGES;
SQL
