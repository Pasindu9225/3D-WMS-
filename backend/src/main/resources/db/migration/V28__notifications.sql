-- v1.2 task 5: in-app notifications. Each row is for one person; the words come from the app's text file, the row
-- only holds the kind, a few values (params) and where clicking goes. The same alert is kept once per person (dedupe).
CREATE TABLE notification (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  kind ENUM('LOW_STOCK','TASK_ASSIGNED','EXPIRY') NOT NULL,
  params JSON NOT NULL,
  link VARCHAR(200) NULL,
  dedupe VARCHAR(100) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  read_at DATETIME(3) NULL,
  UNIQUE KEY uk_notification_public (public_id),
  UNIQUE KEY uk_notification_dedupe (user_id, dedupe),
  KEY ix_notification_user (tenant_id, user_id, created_at),
  CONSTRAINT fk_notification_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_notification_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);
