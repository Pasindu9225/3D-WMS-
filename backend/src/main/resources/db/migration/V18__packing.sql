-- v1.0 task 4: packing. The packer's scans are checked against what was picked; finishing gives the order its
-- delivery note number and records cartons and weight. The pick lines already hold what is in the boxes.
ALTER TABLE sales_order
  ADD COLUMN delivery_note_no VARCHAR(20) NULL AFTER released_at,
  ADD COLUMN cartons INT NULL AFTER delivery_note_no,
  ADD COLUMN weight_g INT NULL AFTER cartons,
  ADD COLUMN packed_by BIGINT UNSIGNED NULL AFTER weight_g,
  ADD COLUMN packed_at DATETIME(3) NULL AFTER packed_by,
  ADD UNIQUE KEY uk_so_delivery_note (tenant_id, delivery_note_no),
  ADD CONSTRAINT ck_so_cartons CHECK (cartons IS NULL OR cartons BETWEEN 1 AND 999),
  ADD CONSTRAINT ck_so_weight CHECK (weight_g IS NULL OR weight_g >= 0);
