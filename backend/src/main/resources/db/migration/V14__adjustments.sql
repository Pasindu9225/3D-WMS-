-- v0.4 task 7: stock adjustments with manager approval above a value, per company.

-- LKR 10,000.00 by default (cents). Adjustments worth more, or of products with no cost, wait for approval.
ALTER TABLE tenant ADD COLUMN adjust_approval_minor BIGINT NOT NULL DEFAULT 1000000;

CREATE TABLE stock_adjustment (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  number VARCHAR(20) NOT NULL,                -- ADJ-000001 per company
  warehouse_id BIGINT UNSIGNED NOT NULL,
  bin_id BIGINT UNSIGNED NULL,                -- NULL = receiving area
  bin_code VARCHAR(40) NULL,                  -- as it was, for the record
  product_id BIGINT UNSIGNED NOT NULL,
  sku VARCHAR(40) NOT NULL,
  batch_no VARCHAR(40) NULL,
  expiry_date DATE NULL,
  direction ENUM('IN','OUT') NOT NULL,        -- found / added, or lost / damaged / written off
  qty DECIMAL(14,3) NOT NULL,                 -- base units
  reason ENUM('DAMAGED','LOST','FOUND','EXPIRED','COUNT_CORRECTION','OTHER') NOT NULL,
  note VARCHAR(200) NULL,
  value_minor BIGINT NULL,                    -- qty x cost; NULL when the product has no cost
  status ENUM('PENDING','APPLIED','REJECTED') NOT NULL,
  requested_by BIGINT UNSIGNED NOT NULL,
  requested_at DATETIME(3) NOT NULL,
  decided_by BIGINT UNSIGNED NULL,
  decided_at DATETIME(3) NULL,
  decision_note VARCHAR(200) NULL,
  client_ref VARCHAR(64) NOT NULL,            -- Idempotency-Key of the request
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_adj_public (public_id),
  UNIQUE KEY uk_adj_number (tenant_id, number),
  UNIQUE KEY uk_adj_client (tenant_id, client_ref),
  KEY ix_adj_status (tenant_id, status, requested_at),
  KEY ix_adj_warehouse (warehouse_id),
  CONSTRAINT fk_adj_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_adj_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
  CONSTRAINT fk_adj_bin FOREIGN KEY (bin_id) REFERENCES bin (id),
  CONSTRAINT fk_adj_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT ck_adj_qty CHECK (qty > 0)
);
