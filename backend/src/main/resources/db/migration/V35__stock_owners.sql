-- v2.1 step 1 for 3PLs: stock owners. A client is a partner whose goods the company stores. A product may belong to
-- one client (null = the company's own goods); stock reaches its owner through the product, so stock rows are
-- unchanged. Orders keep the owner of their lines (one owner per order) for filtering and, later, billing.
-- allowed_hs_sections: the World Customs Organization HS sections (1-21) a client may store, "2,4"; null = any.
ALTER TABLE partner
  MODIFY COLUMN type ENUM('SUPPLIER','CUSTOMER','CLIENT') NOT NULL,
  ADD COLUMN allowed_hs_sections VARCHAR(80) NULL;

ALTER TABLE product
  ADD COLUMN owner_id BIGINT UNSIGNED NULL,
  ADD KEY ix_product_owner (tenant_id, owner_id),
  ADD CONSTRAINT fk_product_owner FOREIGN KEY (owner_id) REFERENCES partner (id);

ALTER TABLE purchase_order
  ADD COLUMN owner_id BIGINT UNSIGNED NULL,
  ADD COLUMN owner_code VARCHAR(20) NULL,
  ADD KEY ix_po_owner (tenant_id, owner_id),
  ADD CONSTRAINT fk_po_owner FOREIGN KEY (owner_id) REFERENCES partner (id);

ALTER TABLE sales_order
  ADD COLUMN owner_id BIGINT UNSIGNED NULL,
  ADD COLUMN owner_code VARCHAR(20) NULL,
  ADD KEY ix_so_owner (tenant_id, owner_id),
  ADD CONSTRAINT fk_so_owner FOREIGN KEY (owner_id) REFERENCES partner (id);

-- putaway rules can target one client's goods
ALTER TABLE putaway_rule
  ADD COLUMN owner_id BIGINT UNSIGNED NULL,
  ADD CONSTRAINT fk_putaway_rule_owner FOREIGN KEY (owner_id) REFERENCES partner (id);
