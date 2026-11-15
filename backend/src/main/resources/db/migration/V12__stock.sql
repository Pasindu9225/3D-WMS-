-- v0.4 task 6: stock and the stock ledger (SEC-26). StockMovementService is the only code that writes them.
-- stock = what is where now; stock_movement = every change, append-only (triggers below), so stock can always be
-- rebuilt as the sum of movements. A stock row with no bin is goods received but not yet put away (receiving area).

CREATE TABLE stock (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  bin_id BIGINT UNSIGNED NULL,                -- NULL = receiving area, awaiting putaway
  product_id BIGINT UNSIGNED NOT NULL,
  batch_no VARCHAR(40) NULL,
  expiry_date DATE NULL,
  qty_on_hand DECIMAL(14,3) NOT NULL DEFAULT 0, -- base units
  qty_reserved DECIMAL(14,3) NOT NULL DEFAULT 0, -- for picking (v1.0)
  received_at DATETIME(3) NOT NULL,           -- first arrival in this place (FIFO)
  -- MySQL lets NULLs repeat in a unique key, so the key uses these stand-ins.
  bin_key BIGINT UNSIGNED AS (IFNULL(bin_id, 0)) STORED,
  batch_key VARCHAR(40) AS (IFNULL(batch_no, '')) STORED,
  expiry_key DATE AS (IFNULL(expiry_date, '1000-01-01')) STORED,
  UNIQUE KEY uk_stock (tenant_id, warehouse_id, bin_key, product_id, batch_key, expiry_key),
  KEY ix_stock_product (tenant_id, product_id),
  KEY ix_stock_bin (bin_id),
  KEY ix_stock_warehouse (warehouse_id),
  CONSTRAINT fk_stock_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_stock_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
  CONSTRAINT fk_stock_bin FOREIGN KEY (bin_id) REFERENCES bin (id),
  CONSTRAINT fk_stock_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT ck_stock_qty CHECK (qty_on_hand >= 0 AND qty_reserved >= 0 AND qty_reserved <= qty_on_hand)
);

CREATE TABLE stock_movement (                 -- append only: no UPDATE, no DELETE
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  type ENUM('RECEIVE','PUTAWAY','TRANSFER','ADJUST','PICK','SHIP','RETURN','COUNT') NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  batch_no VARCHAR(40) NULL,
  expiry_date DATE NULL,
  from_bin_id BIGINT UNSIGNED NULL,
  to_bin_id BIGINT UNSIGNED NULL,
  from_receiving BOOLEAN NOT NULL DEFAULT FALSE, -- taken from the receiving area (putaway)
  to_receiving BOOLEAN NOT NULL DEFAULT FALSE,   -- placed in the receiving area (receive)
  qty DECIMAL(14,3) NOT NULL,                 -- base units, always positive
  reason VARCHAR(200) NULL,
  ref_type VARCHAR(30) NULL,
  ref_id BIGINT UNSIGNED NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY ix_mv_product_time (tenant_id, product_id, created_at),
  KEY ix_mv_bin (tenant_id, from_bin_id),
  KEY ix_mv_bin_to (tenant_id, to_bin_id),
  KEY ix_mv_warehouse_time (tenant_id, warehouse_id, created_at),
  KEY ix_mv_ref (tenant_id, ref_type, ref_id),
  CONSTRAINT fk_mv_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_mv_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
  CONSTRAINT fk_mv_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT ck_mv_qty CHECK (qty > 0),
  CONSTRAINT ck_mv_sides CHECK ((from_bin_id IS NOT NULL OR from_receiving OR to_bin_id IS NOT NULL OR to_receiving))
);

CREATE TRIGGER stock_movement_no_update BEFORE UPDATE ON stock_movement FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stock_movement is append-only';

CREATE TRIGGER stock_movement_no_delete BEFORE DELETE ON stock_movement FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'stock_movement is append-only';

-- Batch and expiry captured while receiving, for products that track them.
ALTER TABLE receipt_line
  ADD COLUMN batch_no VARCHAR(40) NULL AFTER damage_note,
  ADD COLUMN expiry_date DATE NULL AFTER batch_no;
