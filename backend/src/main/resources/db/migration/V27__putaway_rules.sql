-- v1.2 task 4: managers tell the system where goods go. A product may have a free-text category; a rule matches one
-- product, one category or every product, and sends it to a fixed bin or into a zone (optionally only that zone).
ALTER TABLE product ADD COLUMN category VARCHAR(40) NULL;

CREATE TABLE putaway_rule (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  priority INT NOT NULL DEFAULT 100,          -- lower first
  product_id BIGINT UNSIGNED NULL,            -- one product, or
  category VARCHAR(40) NULL,                  -- one category, or neither = every product
  bin_id BIGINT UNSIGNED NULL,                -- goes to this bin, or
  zone_code VARCHAR(10) NULL,                 -- into this zone of the published layout
  only_zone BOOLEAN NOT NULL DEFAULT FALSE,   -- never suggest or accept a bin outside the zone
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_putaway_rule_public (public_id),
  KEY ix_putaway_rule_wh (tenant_id, warehouse_id),
  CONSTRAINT fk_putaway_rule_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_putaway_rule_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
  CONSTRAINT fk_putaway_rule_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT fk_putaway_rule_bin FOREIGN KEY (bin_id) REFERENCES bin (id) ON DELETE CASCADE,
  CONSTRAINT ck_putaway_rule_match CHECK (product_id IS NULL OR category IS NULL),
  CONSTRAINT ck_putaway_rule_target CHECK ((bin_id IS NULL) <> (zone_code IS NULL)),
  CONSTRAINT ck_putaway_rule_only CHECK (only_zone = FALSE OR zone_code IS NOT NULL)
);
