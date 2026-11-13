# Decisions log

| Date | Decision | Reason |
| --- | --- | --- |
| 2026-11-13 | MySQL 9.7 LTS (8.4 LTS also OK) instead of PostgreSQL | Owner preference; tenant isolation done in the app (SEC-24) |
| 2026-11-13 | English only UI, text in en.json | No Sinhala/Tamil needed |
| 2026-11-13 | Pricing per warehouse | Owner decision |
| 2026-11-13 | No ERP/accounting integration in v1 | Not needed by customers |
| 2026-11-13 | Phone camera scanning only | No hardware scanners |
| 2026-11-13 | No Redis; Caffeine + Bucket4j | Must run on low-spec computers (4 GB RAM) |
| 2026-11-13 | Modular monolith, Spring Boot 4.1 + React 19 | Simple for one developer |
| 2026-11-13 | Java 17 LTS instead of Java 21; Spring Boot 4.1 kept | Owner prefers stable tech; Boot 4.1 runs on Java 17 and still gets security patches |
| 2026-11-13 | MySQL 8.4 LTS (not 9.7) in its own container `wms-mysql`, port 127.0.0.1:3308 | More mature; never share the bakery BMS test container |
| 2026-11-13 | Own image `deploy/mysql/Dockerfile` (Debian 12 + Oracle APT MySQL 8.4) instead of `mysql:8.4` | Official 8.4 image needs x86-64-v2 CPU; server is a basic QEMU vCPU. 8.0-debian is EOL (Apr 2026) |
| 2026-11-13 | DB users `wms_flyway` (schema) and `wms_app` (data only); ledger UPDATE/DELETE blocked by triggers | SEC-27; MySQL db-level grants cannot exclude single tables |
