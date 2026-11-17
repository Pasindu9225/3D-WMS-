-- v1.2 task 3: keep pick-face bins full. A rule says "keep between min and max of this product in this pick bin";
-- when a pick takes it below min, a refill task asks for stock to be moved up from bulk storage.
CREATE TABLE refill_rule (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  bin_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  min_qty DECIMAL(14,3) NOT NULL,             -- base units
  max_qty DECIMAL(14,3) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_refill_rule_public (public_id),
  UNIQUE KEY uk_refill_rule (tenant_id, bin_id, product_id),
  KEY ix_refill_rule_wh (tenant_id, warehouse_id),
  CONSTRAINT fk_refill_rule_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_refill_rule_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
  CONSTRAINT fk_refill_rule_bin FOREIGN KEY (bin_id) REFERENCES bin (id),
  CONSTRAINT fk_refill_rule_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT ck_refill_rule CHECK (min_qty >= 0 AND max_qty > min_qty)
);

CREATE TABLE refill_task (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  number VARCHAR(20) NOT NULL,
  rule_id BIGINT UNSIGNED NOT NULL,
  open_rule_id BIGINT UNSIGNED NULL,          -- = rule_id while OPEN: at most one open task per rule
  warehouse_id BIGINT UNSIGNED NOT NULL,
  bin_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  qty DECIMAL(14,3) NOT NULL,                 -- base units wanted (max - on hand when made)
  moved_qty DECIMAL(14,3) NULL,
  status ENUM('OPEN','DONE','CANCELLED') NOT NULL,
  created_at DATETIME(3) NOT NULL,
  done_by BIGINT UNSIGNED NULL,
  done_at DATETIME(3) NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_refill_task_public (public_id),
  UNIQUE KEY uk_refill_task_number (tenant_id, number),
  UNIQUE KEY uk_refill_task_open (open_rule_id),
  KEY ix_refill_task_status (tenant_id, warehouse_id, status),
  CONSTRAINT fk_refill_task_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_refill_task_rule FOREIGN KEY (rule_id) REFERENCES refill_rule (id),
  CONSTRAINT fk_refill_task_bin FOREIGN KEY (bin_id) REFERENCES bin (id),
  CONSTRAINT ck_refill_task CHECK (qty > 0 AND (moved_qty IS NULL OR moved_qty >= 0)
    AND ((status = 'OPEN' AND open_rule_id = rule_id) OR (status <> 'OPEN' AND open_rule_id IS NULL)))
);
