-- v1.0 task 1: sales orders. Drafted (by hand or CSV), released for picking (task 3), picked, packed, shipped;
-- or cancelled. Customer, warehouse and product codes/names are copied onto the order, like purchase orders.
-- No prices: the warehouse ships quantities; invoicing stays in the customer's accounts system.

CREATE TABLE sales_order (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  number VARCHAR(20) NOT NULL,
  status ENUM('DRAFT','RELEASED','PICKING','PICKED','PACKED','SHIPPED','CANCELLED') NOT NULL,
  customer_id BIGINT UNSIGNED NOT NULL,
  customer_code VARCHAR(20) NOT NULL,
  customer_name VARCHAR(200) NOT NULL,
  customer_ref VARCHAR(40) NULL,              -- the customer's own order number; stops a CSV being imported twice
  warehouse_id BIGINT UNSIGNED NOT NULL,
  warehouse_code VARCHAR(20) NOT NULL,
  ship_to VARCHAR(500) NULL,                  -- the customer's address unless changed on the order
  requested_date DATE NULL,
  note VARCHAR(500) NULL,
  line_count INT NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NULL,
  cancelled_by BIGINT UNSIGNED NULL,
  cancelled_at DATETIME(3) NULL,
  cancel_reason VARCHAR(200) NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_so_public (public_id),
  UNIQUE KEY uk_so_number (tenant_id, number),
  UNIQUE KEY uk_so_customer_ref (tenant_id, customer_id, customer_ref), -- NULLs do not clash
  KEY ix_so_status (tenant_id, status, created_at),
  CONSTRAINT fk_so_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_so_customer FOREIGN KEY (customer_id) REFERENCES partner (id),
  CONSTRAINT fk_so_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);

CREATE TABLE so_line (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  so_id BIGINT UNSIGNED NOT NULL,
  line_no INT NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  sku VARCHAR(40) NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  uom VARCHAR(10) NOT NULL,                   -- the unit ordered in (EA, CASE ...)
  factor INT NOT NULL,                        -- base units in one of it, fixed when ordered
  qty DECIMAL(14,3) NOT NULL,                 -- in the ordered unit
  UNIQUE KEY uk_so_line (so_id, line_no),
  KEY ix_so_line_product (tenant_id, product_id),
  CONSTRAINT fk_so_line_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_so_line_so FOREIGN KEY (so_id) REFERENCES sales_order (id) ON DELETE CASCADE,
  CONSTRAINT fk_so_line_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT ck_so_line CHECK (qty > 0 AND factor >= 1)
);
