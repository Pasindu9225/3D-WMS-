-- v0.2 task 2: rack template library and the layout draft (one JSON document per warehouse).

CREATE TABLE rack_template (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  code VARCHAR(20) NOT NULL,
  name VARCHAR(80) NOT NULL,
  width_mm INT NOT NULL,
  depth_mm INT NOT NULL,
  height_mm INT NOT NULL,
  levels SMALLINT NOT NULL,
  bins_per_level SMALLINT NOT NULL,
  max_weight_per_level_g BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NULL,
  deleted_at DATETIME(3) NULL,
  UNIQUE KEY uk_rt_public (public_id),
  UNIQUE KEY uk_rt_code (tenant_id, code),
  KEY ix_rt_tenant (tenant_id),
  CONSTRAINT fk_rtpl_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT ck_rt_size CHECK (width_mm BETWEEN 300 AND 30000 AND depth_mm BETWEEN 300 AND 5000
    AND height_mm BETWEEN 500 AND 40000 AND levels BETWEEN 1 AND 20 AND bins_per_level BETWEEN 1 AND 50
    AND max_weight_per_level_g > 0)
);

-- Layout versions of a warehouse: at most one DRAFT (being edited) plus the PUBLISHED history.
-- `layout` holds floor, doors/docks, zones, racks and settings; publish (task 3) turns it into rows.
CREATE TABLE layout_version (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  status ENUM('DRAFT','PUBLISHED') NOT NULL,
  revision INT NOT NULL,                      -- bumps on every draft save; clients send it back (409 if stale)
  layout JSON NOT NULL,
  created_by BIGINT UNSIGNED NULL,
  created_at DATETIME(3) NOT NULL,
  updated_by BIGINT UNSIGNED NULL,
  updated_at DATETIME(3) NULL,
  published_by BIGINT UNSIGNED NULL,
  published_at DATETIME(3) NULL,
  KEY ix_lv_tenant (tenant_id),
  KEY ix_lv_warehouse_status (warehouse_id, status),
  CONSTRAINT fk_lv_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_lv_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);
