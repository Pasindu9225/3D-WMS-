-- v0.4 task 4: receiving (goods received notes). A receipt is opened against an approved purchase order,
-- each scan adds a line, and closing it adds the received quantities to the order lines.
-- Stock is posted from closed receipts by StockMovementService (task 6).

CREATE TABLE receipt (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  number VARCHAR(20) NOT NULL,                -- GRN-000001 per company
  po_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  status ENUM('OPEN','CLOSED') NOT NULL,
  note VARCHAR(500) NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  closed_by BIGINT UNSIGNED NULL,
  closed_at DATETIME(3) NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_receipt_public (public_id),
  UNIQUE KEY uk_receipt_number (tenant_id, number),
  KEY ix_receipt_po (tenant_id, po_id, status),
  CONSTRAINT fk_receipt_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_receipt_po FOREIGN KEY (po_id) REFERENCES purchase_order (id),
  CONSTRAINT fk_receipt_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);

CREATE TABLE receipt_line (                   -- one row per scan; a mistaken scan is removed while the receipt is open
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  receipt_id BIGINT UNSIGNED NOT NULL,
  po_line_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  uom VARCHAR(10) NOT NULL,                   -- the unit scanned or chosen
  factor INT NOT NULL,
  qty DECIMAL(14,3) NOT NULL,                 -- in that unit
  qty_base DECIMAL(14,3) NOT NULL,            -- qty x factor
  damaged_base DECIMAL(14,3) NOT NULL DEFAULT 0, -- part of qty_base that arrived damaged
  damage_note VARCHAR(200) NULL,
  client_ref VARCHAR(64) NOT NULL,            -- Idempotency-Key from the phone: a retried scan is not counted twice
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_rline_public (public_id),
  UNIQUE KEY uk_rline_client (receipt_id, client_ref),
  KEY ix_rline_po_line (tenant_id, po_line_id),
  CONSTRAINT fk_rline_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_rline_receipt FOREIGN KEY (receipt_id) REFERENCES receipt (id) ON DELETE CASCADE,
  CONSTRAINT fk_rline_po_line FOREIGN KEY (po_line_id) REFERENCES po_line (id),
  CONSTRAINT fk_rline_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT ck_rline CHECK (qty > 0 AND factor >= 1 AND qty_base > 0 AND damaged_base >= 0 AND damaged_base <= qty_base)
);
