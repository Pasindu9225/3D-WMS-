-- v1.0 task 7: dashboard low stock. A product is low when its stock (all places) is at or below this (base units).
ALTER TABLE product
  ADD COLUMN reorder_point DECIMAL(14,3) NULL AFTER cost_minor,
  ADD CONSTRAINT ck_product_reorder CHECK (reorder_point IS NULL OR reorder_point >= 0);
