-- v0.2 task 1: warehouses and who may see them (SEC-10).
-- Zones, racks, levels and bins (the published layout) come in a later migration.

CREATE TABLE warehouse (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  code VARCHAR(20) NOT NULL,
  name VARCHAR(120) NOT NULL,
  floor_polygon JSON NOT NULL,                -- [[x_mm, y_mm], ...], published floor outline
  wall_height_mm INT NOT NULL,
  bin_code_pattern VARCHAR(60) NOT NULL DEFAULT '{zone}-{rack}-{level}-{bin}',
  layout_status ENUM('DRAFT','PUBLISHED') NOT NULL DEFAULT 'DRAFT',
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT UNSIGNED NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NULL,
  deleted_at DATETIME(3) NULL,                -- archived: hidden, frees a plan slot
  UNIQUE KEY uk_wh_public (public_id),
  UNIQUE KEY uk_wh_code (tenant_id, code),
  KEY ix_wh_tenant (tenant_id),
  CONSTRAINT fk_wh_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT ck_wh_height CHECK (wall_height_mm BETWEEN 2000 AND 40000)
);

-- Which warehouses a user may see. Tenant admins see all without rows here.
CREATE TABLE user_warehouse (
  tenant_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (user_id, warehouse_id),
  KEY ix_uw_tenant (tenant_id),
  KEY ix_uw_warehouse (warehouse_id),
  CONSTRAINT fk_uw_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_uw_user FOREIGN KEY (user_id) REFERENCES app_user (id),
  CONSTRAINT fk_uw_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);
