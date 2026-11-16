-- v1.0 task 2: releasing a sales order reserves its stock (FIFO by receive date). A reservation points at one
-- stock place (bin, product, batch, expiry) and raises stock.qty_reserved by its qty; picking (task 3) consumes
-- it. Written only by StockMovementService, under the stock row locks (SEC-26).

CREATE TABLE reservation (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  bin_id BIGINT UNSIGNED NOT NULL,            -- only stock in bins is reserved, never the receiving area
  product_id BIGINT UNSIGNED NOT NULL,
  batch_no VARCHAR(40) NULL,
  expiry_date DATE NULL,
  qty DECIMAL(14,3) NOT NULL,                 -- base units
  ref_type VARCHAR(10) NOT NULL,              -- 'SO'
  ref_id BIGINT UNSIGNED NOT NULL,            -- sales_order.id
  ref_line INT NOT NULL,                      -- so_line.line_no
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  KEY ix_reservation_ref (tenant_id, ref_type, ref_id),
  KEY ix_reservation_place (bin_id, product_id),
  CONSTRAINT fk_reservation_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_reservation_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
  CONSTRAINT fk_reservation_bin FOREIGN KEY (bin_id) REFERENCES bin (id),
  CONSTRAINT fk_reservation_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT ck_reservation_qty CHECK (qty > 0)
);

ALTER TABLE sales_order
  ADD COLUMN released_by BIGINT UNSIGNED NULL AFTER updated_at,
  ADD COLUMN released_at DATETIME(3) NULL AFTER released_by;
