-- v1.1 task 5: customer returns. A return belongs to a shipped sales order; nothing enters stock until a line is
-- inspected: restock (into a bin), quarantine (into a quarantine bin) or scrap (never re-enters stock).
CREATE TABLE return_order (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  number VARCHAR(20) NOT NULL,
  so_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  status ENUM('OPEN','CLOSED','CANCELLED') NOT NULL,
  note VARCHAR(500) NULL,
  cancel_reason VARCHAR(200) NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  closed_at DATETIME(3) NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_return_public (public_id),
  UNIQUE KEY uk_return_number (tenant_id, number),
  KEY ix_return_status (tenant_id, warehouse_id, status),
  KEY ix_return_order (tenant_id, so_id),
  CONSTRAINT fk_return_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_return_order FOREIGN KEY (so_id) REFERENCES sales_order (id),
  CONSTRAINT fk_return_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);

CREATE TABLE return_line (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  return_id BIGINT UNSIGNED NOT NULL,
  seq INT NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  sku VARCHAR(40) NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  base_uom VARCHAR(10) NOT NULL,
  qty DECIMAL(14,3) NOT NULL,                 -- base units
  reason ENUM('DAMAGED','WRONG_ITEM','NOT_WANTED','EXPIRED','OTHER') NOT NULL,
  outcome ENUM('RESTOCK','QUARANTINE','SCRAP') NULL,
  bin_id BIGINT UNSIGNED NULL,
  bin_code VARCHAR(60) NULL,
  batch_no VARCHAR(40) NULL,
  expiry_date DATE NULL,
  inspected_by BIGINT UNSIGNED NULL,
  inspected_at DATETIME(3) NULL,
  UNIQUE KEY uk_return_line (return_id, seq),
  CONSTRAINT fk_return_line_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_return_line_return FOREIGN KEY (return_id) REFERENCES return_order (id),
  CONSTRAINT fk_return_line_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT fk_return_line_bin FOREIGN KEY (bin_id) REFERENCES bin (id),
  CONSTRAINT ck_return_line CHECK (qty > 0 AND (outcome IS NULL OR outcome = 'SCRAP' OR bin_id IS NOT NULL))
);
