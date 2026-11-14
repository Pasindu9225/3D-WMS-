-- v0.4 task 1b: stored files (SEC-40..45). Only re-encoded images are stored, never what was uploaded.
-- path is relative to wms.files.dir: {tenantId}/{random uuid}.jpg, outside the web root.

CREATE TABLE file_object (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  public_id CHAR(26) NOT NULL,
  tenant_id BIGINT UNSIGNED NOT NULL,
  content_type VARCHAR(40) NOT NULL,
  size_bytes INT NOT NULL,
  width_px INT NOT NULL,
  height_px INT NOT NULL,
  path VARCHAR(200) NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL,
  deleted_at DATETIME(3) NULL,
  UNIQUE KEY uk_file_public (public_id),
  UNIQUE KEY uk_file_path (path),
  KEY ix_file_tenant (tenant_id),
  CONSTRAINT fk_file_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id)
);

ALTER TABLE product ADD CONSTRAINT fk_product_image FOREIGN KEY (image_file_id) REFERENCES file_object (id);
