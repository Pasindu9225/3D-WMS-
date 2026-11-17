-- v1.1 task 3: cycle counts. A plan lists the places to count (bin + product + lot); counters count blind on the
-- phone; the expected quantity is read when a line is counted (work does not stop); a manager approves the
-- differences, which become COUNT movements in the ledger.
CREATE TABLE count_plan (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  number VARCHAR(20) NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  scope ENUM('ALL','ZONE','ABC') NOT NULL,
  scope_value VARCHAR(20) NULL,               -- zone code or A/B/C
  status ENUM('OPEN','REVIEW','APPROVED','CANCELLED') NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  approved_by BIGINT UNSIGNED NULL,
  approved_at DATETIME(3) NULL,
  cancel_reason VARCHAR(200) NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_count_public (public_id),
  UNIQUE KEY uk_count_number (tenant_id, number),
  KEY ix_count_status (tenant_id, warehouse_id, status),
  CONSTRAINT fk_count_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_count_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);

CREATE TABLE count_line (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  plan_id BIGINT UNSIGNED NOT NULL,
  seq INT NOT NULL,                           -- walking order (bin code)
  bin_id BIGINT UNSIGNED NOT NULL,
  bin_code VARCHAR(60) NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  sku VARCHAR(40) NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  base_uom VARCHAR(10) NOT NULL,
  batch_no VARCHAR(40) NULL,
  expiry_date DATE NULL,
  added BOOLEAN NOT NULL DEFAULT FALSE,       -- found by the counter, not on the list
  status ENUM('OPEN','COUNTED') NOT NULL,
  expected_qty DECIMAL(14,3) NULL,            -- on hand when counted; never shown to counters
  counted_qty DECIMAL(14,3) NULL,
  counted_by BIGINT UNSIGNED NULL,
  counted_at DATETIME(3) NULL,
  UNIQUE KEY uk_count_line (plan_id, seq),
  CONSTRAINT fk_count_line_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_count_line_plan FOREIGN KEY (plan_id) REFERENCES count_plan (id),
  CONSTRAINT fk_count_line_bin FOREIGN KEY (bin_id) REFERENCES bin (id),
  CONSTRAINT fk_count_line_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT ck_count_line CHECK ((expected_qty IS NULL OR expected_qty >= 0) AND (counted_qty IS NULL OR counted_qty >= 0))
);
