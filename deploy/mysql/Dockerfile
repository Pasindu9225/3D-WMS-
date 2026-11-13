# MySQL 8.4 LTS on Debian 12. The official mysql:8.4 image (Oracle Linux 9) needs an
# x86-64-v2 CPU; many cheap QEMU VPSes don't have it. Same packages Oracle ships, same
# entrypoint as the official image, so MYSQL_* env vars and initdb.d still work.
FROM mysql:8.0-debian AS official
FROM debian:bookworm-slim

RUN groupadd --system --gid 999 mysql && useradd --system --uid 999 --gid 999 --home-dir /var/lib/mysql --no-create-home mysql \
 && apt-get update && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
 && curl -fsSL https://repo.mysql.com/RPM-GPG-KEY-mysql-2025 | gpg --dearmor -o /usr/share/keyrings/mysql.gpg \
 && echo "deb [signed-by=/usr/share/keyrings/mysql.gpg] http://repo.mysql.com/apt/debian bookworm mysql-8.4-lts" > /etc/apt/sources.list.d/mysql.list \
 && apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends mysql-community-server-core mysql-community-client \
 && apt-get purge -y curl gnupg && apt-get autoremove -y && rm -rf /var/lib/apt/lists/* \
 && rm -rf /var/lib/mysql && mkdir -p /var/lib/mysql /var/run/mysqld /var/lib/mysql-files /etc/mysql/conf.d /docker-entrypoint-initdb.d \
 && chown -R mysql:mysql /var/lib/mysql /var/run/mysqld /var/lib/mysql-files \
 && printf '[mysqld]\nhost-cache-size=0\nskip-name-resolve\ndatadir=/var/lib/mysql\nsocket=/var/run/mysqld/mysqld.sock\nsecure-file-priv=/var/lib/mysql-files\nuser=mysql\npid-file=/var/run/mysqld/mysqld.pid\n[client]\nsocket=/var/run/mysqld/mysqld.sock\n!includedir /etc/mysql/conf.d/\n' > /etc/mysql/my.cnf

COPY --from=official /usr/local/bin/gosu /usr/local/bin/docker-entrypoint.sh /usr/local/bin/
VOLUME /var/lib/mysql
ENTRYPOINT ["docker-entrypoint.sh"]
EXPOSE 3306
CMD ["mysqld"]
