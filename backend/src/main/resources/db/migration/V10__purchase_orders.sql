-- v0.4 task 3: purchase orders. A PO is drafted, approved, then received (task 4) or cancelled.
-- Supplier, warehouse and product codes/names are copied onto the order: it is a document and reads the
-- same later even if the supplier or product is renamed or archived.

CREATE TABLE doc_counter (                    -- per-company running numbers (PO-000001, later GRN-000001)
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  kind VARCHAR(10) NOT NULL,
  next_no INT NOT NULL,
  UNIQUE KEY uk_doc_counter (tenant_id, kind),
  CONSTRAINT fk_doc_counter_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

CREATE TABLE purchase_order (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  number VARCHAR(20) NOT NULL,
  status ENUM('DRAFT','APPROVED','PARTLY_RECEIVED','RECEIVED','CANCELLED') NOT NULL,
  supplier_id BIGINT UNSIGNED NOT NULL,
  supplier_code VARCHAR(20) NOT NULL,
  supplier_name VARCHAR(200) NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  warehouse_code VARCHAR(20) NOT NULL,
  expected_date DATE NULL,
  note VARCHAR(500) NULL,
  total_minor BIGINT NOT NULL,                -- sum of line qty x unit cost, in cents
  line_count INT NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NULL,
  approved_by BIGINT UNSIGNED NULL,
  approved_at DATETIME(3) NULL,
  cancelled_by BIGINT UNSIGNED NULL,
  cancelled_at DATETIME(3) NULL,
  cancel_reason VARCHAR(200) NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_po_public (public_id),
  UNIQUE KEY uk_po_number (tenant_id, number),
  KEY ix_po_status (tenant_id, status, created_at),
  KEY ix_po_supplier (tenant_id, supplier_id),
  CONSTRAINT fk_po_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_po_supplier FOREIGN KEY (supplier_id) REFERENCES partner (id),
  CONSTRAINT fk_po_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
  CONSTRAINT ck_po_total CHECK (total_minor >= 0)
);

CREATE TABLE po_line (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  po_id BIGINT UNSIGNED NOT NULL,
  line_no INT NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  sku VARCHAR(40) NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  uom VARCHAR(10) NOT NULL,                   -- the unit ordered in (EA, CASE ...)
  factor INT NOT NULL,                        -- base units in one of it, fixed when ordered
  qty DECIMAL(14,3) NOT NULL,                 -- in the ordered unit
  unit_cost_minor BIGINT NOT NULL,            -- per ordered unit, in cents
  qty_received DECIMAL(14,3) NOT NULL DEFAULT 0, -- in base units (task 4)
  UNIQUE KEY uk_po_line (po_id, line_no),
  KEY ix_po_line_product (tenant_id, product_id),
  CONSTRAINT fk_po_line_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_po_line_po FOREIGN KEY (po_id) REFERENCES purchase_order (id) ON DELETE CASCADE,
  CONSTRAINT fk_po_line_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT ck_po_line CHECK (qty > 0 AND factor >= 1 AND unit_cost_minor >= 0 AND qty_received >= 0)
);
