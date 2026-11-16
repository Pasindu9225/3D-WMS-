-- v1.0 task 5: dispatch. A shipment is one vehicle trip carrying packed orders of one warehouse; dispatching it
-- takes their goods out of the dispatch area (SHIP movements) and marks the orders shipped.
CREATE TABLE shipment (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  number VARCHAR(20) NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  vehicle_no VARCHAR(20) NULL,
  driver_name VARCHAR(120) NULL,
  note VARCHAR(500) NULL,
  order_count INT NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_shipment_public (public_id),
  UNIQUE KEY uk_shipment_number (tenant_id, number),
  KEY ix_shipment_created (tenant_id, warehouse_id, created_at),
  CONSTRAINT fk_shipment_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_shipment_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
  CONSTRAINT ck_shipment_orders CHECK (order_count >= 1)
);

ALTER TABLE sales_order
  ADD COLUMN shipment_id BIGINT UNSIGNED NULL AFTER packed_at,
  ADD COLUMN shipped_at DATETIME(3) NULL AFTER shipment_id,
  ADD CONSTRAINT fk_so_shipment FOREIGN KEY (shipment_id) REFERENCES shipment (id);

-- A movement needs a side inside the warehouse; since V17 that can also be the dispatch area (shipping leaves from it).
ALTER TABLE stock_movement
  DROP CHECK ck_mv_sides,
  ADD CONSTRAINT ck_mv_sides CHECK (from_bin_id IS NOT NULL OR from_receiving OR from_dispatch
                                    OR to_bin_id IS NOT NULL OR to_receiving OR to_dispatch);
