-- v0.2 task 3: the published layout. Written only by "publish" from a draft (layout_version).
-- Rows are matched by code on each publish, so a bin keeps its id (and later its stock) across edits.

CREATE TABLE zone (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  code VARCHAR(10) NOT NULL,
  type ENUM('RECEIVING','STORAGE','PICKING','DISPATCH','QUARANTINE','COLD') NOT NULL,
  name VARCHAR(60) NOT NULL,
  polygon JSON NOT NULL,
  UNIQUE KEY uk_zone (tenant_id, warehouse_id, code),
  KEY ix_zone_warehouse (warehouse_id),
  CONSTRAINT fk_zone_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_zone_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);

CREATE TABLE rack (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  zone_id BIGINT UNSIGNED NOT NULL,
  template_id BIGINT UNSIGNED NOT NULL,
  code VARCHAR(10) NOT NULL,
  pos_x_mm INT NOT NULL,                      -- centre of the rack on the floor plan
  pos_y_mm INT NOT NULL,
  rotation_deg SMALLINT NOT NULL DEFAULT 0,   -- 0, 90, 180, 270
  width_mm INT NOT NULL,                      -- copied from the template at publish
  depth_mm INT NOT NULL,
  height_mm INT NOT NULL,
  UNIQUE KEY uk_rack (tenant_id, warehouse_id, code),
  KEY ix_rack_warehouse (warehouse_id),
  KEY ix_rack_zone (zone_id),
  KEY ix_rack_template (template_id),
  CONSTRAINT fk_rack_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_rack_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
  CONSTRAINT fk_rack_zone FOREIGN KEY (zone_id) REFERENCES zone (id),
  CONSTRAINT fk_rack_template FOREIGN KEY (template_id) REFERENCES rack_template (id)
);

CREATE TABLE rack_level (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  rack_id BIGINT UNSIGNED NOT NULL,
  level_no SMALLINT NOT NULL,
  height_from_floor_mm INT NOT NULL,
  max_weight_g BIGINT NOT NULL,
  UNIQUE KEY uk_level (rack_id, level_no),
  KEY ix_level_tenant (tenant_id),
  CONSTRAINT fk_level_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_level_rack FOREIGN KEY (rack_id) REFERENCES rack (id)
);

CREATE TABLE bin (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  level_id BIGINT UNSIGNED NOT NULL,
  position_no SMALLINT NOT NULL,
  code VARCHAR(40) NOT NULL,                  -- e.g. A-03-02-05, printed on the label
  max_weight_g BIGINT NOT NULL,
  max_volume_cm3 BIGINT NOT NULL,
  custom_capacity BOOLEAN NOT NULL DEFAULT FALSE, -- set by a single-bin edit; publish then keeps the values
  is_pick_face BOOLEAN NOT NULL DEFAULT FALSE,
  is_blocked BOOLEAN NOT NULL DEFAULT FALSE,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_bin_public (public_id),
  UNIQUE KEY uk_bin_code (tenant_id, warehouse_id, code),
  UNIQUE KEY uk_bin_slot (level_id, position_no),
  KEY ix_bin_warehouse (warehouse_id),
  CONSTRAINT fk_bin_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_bin_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
  CONSTRAINT fk_bin_level FOREIGN KEY (level_id) REFERENCES rack_level (id)
);
