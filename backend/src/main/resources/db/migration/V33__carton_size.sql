-- v2.1: a product's real carton (outer box) size and how many units it holds, so the 3D view can stack real-size
-- cartons. All optional: products without a size keep the simple stack. Sides in millimetres.
ALTER TABLE product
  ADD COLUMN carton_length_mm INT NULL,
  ADD COLUMN carton_width_mm INT NULL,
  ADD COLUMN carton_height_mm INT NULL,
  ADD COLUMN units_per_carton INT NULL,
  ADD CONSTRAINT ck_product_carton CHECK ((carton_length_mm IS NULL AND carton_width_mm IS NULL AND carton_height_mm IS NULL)
    OR (carton_length_mm BETWEEN 10 AND 5000 AND carton_width_mm BETWEEN 10 AND 5000 AND carton_height_mm BETWEEN 10 AND 5000)),
  ADD CONSTRAINT ck_product_units_per_carton CHECK (units_per_carton IS NULL OR units_per_carton BETWEEN 1 AND 100000);
