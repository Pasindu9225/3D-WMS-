-- v2.1 count limits: a count may have an allowed difference in percent (null = a manager reviews everything).
-- A line outside it is sent back once for a recount; first_qty keeps the first figure.
ALTER TABLE count_plan
  ADD COLUMN tolerance_pct DECIMAL(5,2) NULL,
  ADD CONSTRAINT ck_count_plan_tolerance CHECK (tolerance_pct BETWEEN 0 AND 100);

ALTER TABLE count_line ADD COLUMN first_qty DECIMAL(14,3) NULL;
