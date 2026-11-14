-- v0.4 task 2: business partners. Suppliers now (purchase orders, receiving); customers in v1.0.

CREATE TABLE partner (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  type ENUM('SUPPLIER','CUSTOMER') NOT NULL,
  code VARCHAR(20) NOT NULL,
  name VARCHAR(200) NOT NULL,
  contact_name VARCHAR(120) NULL,
  phone VARCHAR(30) NULL,
  email VARCHAR(254) NULL,
  address VARCHAR(500) NULL,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NULL,
  deleted_at DATETIME(3) NULL,
  UNIQUE KEY uk_partner_public (public_id),
  UNIQUE KEY uk_partner_code (tenant_id, type, code),
  KEY ix_partner_name (tenant_id, type, name),
  CONSTRAINT fk_partner_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);
