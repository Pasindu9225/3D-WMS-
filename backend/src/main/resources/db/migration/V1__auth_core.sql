-- v0.1 auth core. Conventions: docs/SPEC.md "MySQL database schema".
-- Runs as wms_flyway; the app (wms_app) only has SELECT/INSERT/UPDATE/DELETE (SEC-27).

CREATE TABLE tenant (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  name VARCHAR(150) NOT NULL,
  plan_code VARCHAR(30) NOT NULL,
  max_warehouses INT NOT NULL DEFAULT 1,
  status ENUM('ACTIVE','SUSPENDED') NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_tenant_public (public_id)
);

CREATE TABLE app_user (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  email VARCHAR(190) NOT NULL,
  full_name VARCHAR(120) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  status ENUM('INVITED','ACTIVE','DISABLED','LOCKED') NOT NULL,
  failed_logins INT NOT NULL DEFAULT 0,
  locked_until DATETIME(3) NULL,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NULL,
  UNIQUE KEY uk_user_public (public_id),
  UNIQUE KEY uk_user_email (email),           -- global: login is by email before the tenant is known
  KEY ix_user_tenant (tenant_id),
  CONSTRAINT fk_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

-- Built-in roles and permissions are global (seeded in task 6); assignment is per tenant user.
CREATE TABLE role (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(40) NOT NULL,
  name VARCHAR(80) NOT NULL,
  UNIQUE KEY uk_role_code (code)
);

CREATE TABLE permission (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(60) NOT NULL,                  -- e.g. users:manage, inventory:transfer
  description VARCHAR(200) NOT NULL,
  UNIQUE KEY uk_permission_code (code)
);

CREATE TABLE role_permission (
  role_id BIGINT UNSIGNED NOT NULL,
  permission_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (role_id, permission_id),
  KEY ix_rp_permission (permission_id),
  CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES role (id),
  CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permission (id)
);

CREATE TABLE user_role (
  tenant_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  role_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (user_id, role_id),
  KEY ix_ur_tenant (tenant_id),
  KEY ix_ur_role (role_id),
  CONSTRAINT fk_ur_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES role (id)
);

CREATE TABLE user_session (
  id CHAR(26) PRIMARY KEY,                    -- ULID, the JWT "sid" claim
  tenant_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  ip VARCHAR(45) NULL,
  user_agent VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL,
  last_seen_at DATETIME(3) NOT NULL,
  revoked_at DATETIME(3) NULL,
  KEY ix_session_tenant (tenant_id),
  KEY ix_session_user (user_id),
  CONSTRAINT fk_session_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

CREATE TABLE refresh_token (                  -- looked up by hash before the tenant is known
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  session_id CHAR(26) NOT NULL,
  family_id CHAR(26) NOT NULL,
  token_hash CHAR(64) NOT NULL,               -- SHA-256 hex, never the raw token (SEC-03)
  expires_at DATETIME(3) NOT NULL,
  used_at DATETIME(3) NULL,
  revoked_at DATETIME(3) NULL,
  UNIQUE KEY uk_rt_hash (token_hash),
  KEY ix_rt_family (family_id),
  KEY ix_rt_session (session_id),
  CONSTRAINT fk_rt_session FOREIGN KEY (session_id) REFERENCES user_session (id)
);

CREATE TABLE audit_log (                      -- append only (SEC-28); tenant_id NULL for system events
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NULL,
  user_id BIGINT UNSIGNED NULL,
  action VARCHAR(60) NOT NULL,
  entity VARCHAR(60) NOT NULL,
  entity_id VARCHAR(40) NULL,
  before_json JSON NULL,
  after_json JSON NULL,
  ip VARCHAR(45) NULL,
  user_agent VARCHAR(255) NULL,
  correlation_id CHAR(26) NULL,
  created_at DATETIME(3) NOT NULL,
  KEY ix_audit_tenant_time (tenant_id, created_at),
  KEY ix_audit_user (user_id)
);

-- SEC-06 lockout counters per IP and per account. Email is stored hashed: people
-- sometimes type their password into the email field.
CREATE TABLE login_attempt (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  ip VARCHAR(45) NOT NULL,
  email_hash CHAR(64) NOT NULL,
  success BOOLEAN NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY ix_la_ip_time (ip, created_at),
  KEY ix_la_email_time (email_hash, created_at)
);

-- Ledger guard: wms_app has UPDATE/DELETE on the schema, so block them on audit_log here.
CREATE TRIGGER audit_log_no_update BEFORE UPDATE ON audit_log FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';

CREATE TRIGGER audit_log_no_delete BEFORE DELETE ON audit_log FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';
