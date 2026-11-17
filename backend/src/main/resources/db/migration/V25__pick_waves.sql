-- v1.2 task 1: batch picking. A wave groups several orders' pick lists into one walk; each order goes into its own
-- tote on the trolley. Pick lists, reservations and stock moves stay per order.
CREATE TABLE pick_wave (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  number VARCHAR(20) NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  status ENUM('OPEN','IN_PROGRESS','DONE') NOT NULL,
  cut_off DATE NULL,                          -- set when made "by cut-off"
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  done_at DATETIME(3) NULL,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_wave_public (public_id),
  UNIQUE KEY uk_wave_number (tenant_id, number),
  KEY ix_wave_status (tenant_id, warehouse_id, status),
  CONSTRAINT fk_wave_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_wave_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse (id)
);

ALTER TABLE pick_list
  ADD COLUMN wave_id BIGINT UNSIGNED NULL AFTER warehouse_id,
  ADD COLUMN tote INT NULL AFTER wave_id,
  ADD KEY ix_pick_wave (wave_id),
  ADD CONSTRAINT fk_pick_wave FOREIGN KEY (wave_id) REFERENCES pick_wave (id),
  ADD CONSTRAINT ck_pick_tote CHECK ((wave_id IS NULL AND tote IS NULL) OR (wave_id IS NOT NULL AND tote BETWEEN 1 AND 20));
