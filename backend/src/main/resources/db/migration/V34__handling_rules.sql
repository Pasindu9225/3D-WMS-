-- v2.1: product handling rules. A world customs code (HS, World Customs Organization; 6 digits worldwide, up to
-- 10 with national digits) plus the facts putaway checks: storage temperature, UN dangerous goods class, how many
-- high it may be stacked and whether anything may stand on it. Zones say what they can hold.
ALTER TABLE product
  ADD COLUMN hs_code VARCHAR(10) NULL,
  ADD COLUMN storage_temp VARCHAR(8) NOT NULL DEFAULT 'AMBIENT',
  ADD COLUMN hazard_class VARCHAR(4) NULL,
  ADD COLUMN stack_max INT NULL,
  ADD COLUMN fragile BOOLEAN NOT NULL DEFAULT FALSE,
  ADD CONSTRAINT ck_product_hs_code CHECK (hs_code IS NULL OR CHAR_LENGTH(hs_code) BETWEEN 6 AND 10),
  ADD CONSTRAINT ck_product_hazard_class CHECK (hazard_class IS NULL OR CHAR_LENGTH(hazard_class) BETWEEN 1 AND 3),
  ADD CONSTRAINT ck_product_storage_temp CHECK (storage_temp IN ('AMBIENT','CHILLED','FROZEN')),
  ADD CONSTRAINT ck_product_stack_max CHECK (stack_max IS NULL OR stack_max BETWEEN 1 AND 50);

-- COLD stays the chilled store (2-8 C); FROZEN is a freezer (-18 C). hazard_classes: main UN classes allowed, "3,8".
ALTER TABLE zone
  MODIFY COLUMN type ENUM('RECEIVING','STORAGE','PICKING','DISPATCH','QUARANTINE','COLD','FROZEN') NOT NULL,
  ADD COLUMN hazard_classes VARCHAR(20) NULL;
