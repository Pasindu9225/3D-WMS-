-- v1.1 task 4: ABC class per product from pick frequency (weekly job). NULL = not classified yet.
ALTER TABLE product
  ADD COLUMN abc_class CHAR(1) NULL AFTER reorder_point,
  ADD COLUMN abc_at DATETIME(6) NULL AFTER abc_class,
  ADD CONSTRAINT ck_product_abc CHECK (abc_class IS NULL OR abc_class IN ('A', 'B', 'C'));
