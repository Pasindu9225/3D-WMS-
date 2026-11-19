-- The owner said the target customer is not a 3PL, so V35 (stock owners) is taken out again.
-- V35 stays in place for databases that already ran it; this undoes it.
DELETE FROM putaway_rule WHERE owner_id IS NOT NULL; -- a client rule would otherwise become an "every product" rule
ALTER TABLE putaway_rule DROP FOREIGN KEY fk_putaway_rule_owner;
ALTER TABLE putaway_rule DROP COLUMN owner_id;

ALTER TABLE sales_order DROP FOREIGN KEY fk_so_owner;
ALTER TABLE sales_order DROP KEY ix_so_owner, DROP COLUMN owner_id, DROP COLUMN owner_code;

ALTER TABLE purchase_order DROP FOREIGN KEY fk_po_owner;
ALTER TABLE purchase_order DROP KEY ix_po_owner, DROP COLUMN owner_id, DROP COLUMN owner_code;

ALTER TABLE product DROP FOREIGN KEY fk_product_owner;
ALTER TABLE product DROP KEY ix_product_owner, DROP COLUMN owner_id;

DELETE FROM partner WHERE type = 'CLIENT';
ALTER TABLE partner
  MODIFY COLUMN type ENUM('SUPPLIER','CUSTOMER') NOT NULL,
  DROP COLUMN allowed_hs_sections;
