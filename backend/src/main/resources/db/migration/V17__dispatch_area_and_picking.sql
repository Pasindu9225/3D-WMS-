-- v1.0 task 3: picking. Picked goods move from their bin to the warehouse's dispatch area (like the receiving
-- area, for outgoing goods) and stay counted there until dispatch takes them out (task 5).

-- 1. Stock places: a bin, the receiving area or the dispatch area.
ALTER TABLE stock ADD COLUMN area ENUM('BIN','RECEIVING','DISPATCH') NOT NULL DEFAULT 'BIN' AFTER bin_id;
UPDATE stock SET area = 'RECEIVING' WHERE bin_id IS NULL;
ALTER TABLE stock
  DROP INDEX uk_stock,
  ADD UNIQUE KEY uk_stock (tenant_id, warehouse_id, bin_key, area, product_id, batch_key, expiry_key),
  ADD CONSTRAINT ck_stock_area CHECK ((area = 'BIN') = (bin_id IS NOT NULL));

-- The ledger is append-only (no UPDATE/DELETE triggers); adding columns with a default rewrites no history.
ALTER TABLE stock_movement
  ADD COLUMN from_dispatch BOOLEAN NOT NULL DEFAULT FALSE AFTER from_receiving,
  ADD COLUMN to_dispatch BOOLEAN NOT NULL DEFAULT FALSE AFTER to_receiving;

-- 2. Pick lists: one per released sales order, lines in bin order.
CREATE TABLE pick_list (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  number VARCHAR(20) NOT NULL,
  so_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  status ENUM('OPEN','IN_PROGRESS','DONE') NOT NULL,
  created_at DATETIME(3) NOT NULL,
  started_by BIGINT UNSIGNED NULL,
  started_at DATETIME(3) NULL,
  done_at DATETIME(3) NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_pick_public (public_id),
  UNIQUE KEY uk_pick_number (tenant_id, number),
  UNIQUE KEY uk_pick_order (so_id),
  KEY ix_pick_status (tenant_id, warehouse_id, status),
  CONSTRAINT fk_pick_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_pick_order FOREIGN KEY (so_id) REFERENCES sales_order (id),
  CONSTRAINT fk_pick_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);

CREATE TABLE pick_line (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  pick_list_id BIGINT UNSIGNED NOT NULL,
  seq INT NOT NULL,                           -- walking order (bin code)
  so_line_no INT NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  sku VARCHAR(40) NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  base_uom VARCHAR(10) NOT NULL,
  bin_id BIGINT UNSIGNED NOT NULL,
  bin_code VARCHAR(60) NOT NULL,
  batch_no VARCHAR(40) NULL,
  expiry_date DATE NULL,
  qty_to_pick DECIMAL(14,3) NOT NULL,         -- base units
  qty_picked DECIMAL(14,3) NOT NULL DEFAULT 0,
  status ENUM('OPEN','PICKED','SHORT') NOT NULL,
  short_reason VARCHAR(20) NULL,
  UNIQUE KEY uk_pick_line (pick_list_id, seq),
  CONSTRAINT fk_pick_line_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_pick_line_list FOREIGN KEY (pick_list_id) REFERENCES pick_list (id) ON DELETE CASCADE,
  CONSTRAINT fk_pick_line_bin FOREIGN KEY (bin_id) REFERENCES bin (id),
  CONSTRAINT ck_pick_line CHECK (qty_to_pick > 0 AND qty_picked >= 0 AND qty_picked <= qty_to_pick)
);
