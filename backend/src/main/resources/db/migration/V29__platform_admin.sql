-- v2.0 task 1: the Super Admin portal. The platform operator is a user of one special company (platform = TRUE)
-- with the SUPER_ADMIN role; that role alone holds the platform permissions. Plans list what can be sold.
ALTER TABLE tenant
  ADD COLUMN platform BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN suspended_at DATETIME(3) NULL,
  ADD COLUMN suspended_reason VARCHAR(200) NULL,
  ADD COLUMN version INT NOT NULL DEFAULT 0;

CREATE TABLE plan (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(30) NOT NULL,
  name VARCHAR(80) NOT NULL,
  price_per_warehouse_minor BIGINT NOT NULL,   -- LKR cents per warehouse per month
  default_max_warehouses INT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(3) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_plan_code (code),
  CONSTRAINT ck_plan CHECK (price_per_warehouse_minor >= 0 AND default_max_warehouses BETWEEN 1 AND 1000)
);
INSERT INTO plan (code, name, price_per_warehouse_minor, default_max_warehouses, created_at)
VALUES ('PILOT', 'Pilot (free)', 0, 1, NOW(3));

INSERT INTO permission (code, description) VALUES
  ('platform:read', 'See all companies, plans and usage (Super Admin)'),
  ('platform:manage', 'Create, suspend and change companies and plans (Super Admin)');
INSERT INTO role (code, name) VALUES ('SUPER_ADMIN', 'Super Admin (platform)');
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p WHERE r.code = 'SUPER_ADMIN' AND p.code IN ('platform:read', 'platform:manage');
