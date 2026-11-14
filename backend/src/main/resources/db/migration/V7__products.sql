-- v0.4 task 1: product master and its units of measure.
-- Quantities are always kept in the base unit (e.g. EA); a product_uom row says how many base units one
-- larger unit holds (CASE = 12 EA) and may carry that unit's own barcode, so scanning a case counts 12.

CREATE TABLE product (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  sku VARCHAR(40) NOT NULL,
  barcode VARCHAR(64) NULL,
  name VARCHAR(200) NOT NULL,
  base_uom VARCHAR(10) NOT NULL,
  weight_g INT NULL,                          -- one base unit
  volume_cm3 INT NULL,                        -- one base unit; putaway checks bin capacity with these
  track_batch BOOLEAN NOT NULL DEFAULT FALSE,
  track_expiry BOOLEAN NOT NULL DEFAULT FALSE,
  cost_minor BIGINT NULL,                     -- cost per base unit in cents; adjustments above a value need approval
  image_file_id BIGINT UNSIGNED NULL,         -- task 1b
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NULL,
  deleted_at DATETIME(3) NULL,
  UNIQUE KEY uk_product_public (public_id),
  UNIQUE KEY uk_product_sku (tenant_id, sku),
  UNIQUE KEY uk_product_barcode (tenant_id, barcode), -- NULLs do not clash
  KEY ix_product_name (tenant_id, name),
  CONSTRAINT fk_product_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT ck_product_values CHECK ((weight_g IS NULL OR weight_g BETWEEN 0 AND 100000000)
    AND (volume_cm3 IS NULL OR volume_cm3 BETWEEN 0 AND 100000000) AND (cost_minor IS NULL OR cost_minor >= 0))
);

CREATE TABLE product_uom (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  uom VARCHAR(10) NOT NULL,
  factor INT NOT NULL,                        -- base units in one of this unit
  barcode VARCHAR(64) NULL,
  UNIQUE KEY uk_puom (product_id, uom),
  UNIQUE KEY uk_puom_barcode (tenant_id, barcode),
  KEY ix_puom_tenant (tenant_id),
  CONSTRAINT fk_puom_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_puom_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE CASCADE,
  CONSTRAINT ck_puom_factor CHECK (factor BETWEEN 2 AND 1000000)
);
