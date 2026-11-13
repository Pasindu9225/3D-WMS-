-- v0.1 task 6: user invites, built-in roles and permissions (approved 2026-11-13).

-- One-time invite links (no email server: the admin sends the link). Only the SHA-256 is stored.
CREATE TABLE user_invite (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  token_hash CHAR(64) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  used_at DATETIME(3) NULL,
  revoked_at DATETIME(3) NULL,
  created_by BIGINT UNSIGNED NULL,           -- NULL when created by the bootstrap command
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_invite_hash (token_hash),
  KEY ix_invite_tenant (tenant_id),
  KEY ix_invite_user (user_id),
  CONSTRAINT fk_invite_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
  CONSTRAINT fk_invite_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

INSERT INTO role (code, name) VALUES
  ('TENANT_ADMIN', 'Tenant admin'),
  ('WAREHOUSE_MANAGER', 'Warehouse manager'),
  ('SUPERVISOR', 'Supervisor'),
  ('INVENTORY_CONTROLLER', 'Inventory controller'),
  ('RECEIVER', 'Receiver'),
  ('PICKER', 'Picker'),
  ('PACKER', 'Packer'),
  ('VIEWER', 'Viewer');

INSERT INTO permission (code, description) VALUES
  ('users:read', 'See users and their roles'),
  ('users:manage', 'Invite, rename, disable users and set their roles'),
  ('roles:read', 'See roles and permissions'),
  ('audit:read', 'See the audit log'),
  ('warehouses:read', 'See warehouses and layouts'),
  ('warehouses:manage', 'Create warehouses, edit and publish layouts'),
  ('labels:print', 'Print bin labels'),
  ('catalog:read', 'See products and partners'),
  ('catalog:manage', 'Create and edit products and partners'),
  ('inbound:read', 'See purchase orders and receipts'),
  ('inbound:manage', 'Create, approve and cancel purchase orders'),
  ('inbound:receive', 'Receive goods'),
  ('putaway:execute', 'Put goods away into bins'),
  ('inventory:read', 'See stock and movements'),
  ('inventory:transfer', 'Move stock between bins'),
  ('inventory:adjust', 'Adjust stock with a reason'),
  ('inventory:approve', 'Approve large stock adjustments'),
  ('outbound:read', 'See sales orders and shipments'),
  ('outbound:manage', 'Create, release and cancel sales orders'),
  ('picking:execute', 'Pick orders'),
  ('packing:execute', 'Pack orders'),
  ('dispatch:execute', 'Dispatch shipments'),
  ('tasks:read', 'See worker tasks'),
  ('tasks:assign', 'Assign and prioritise tasks'),
  ('reports:read', 'See the dashboard and reports'),
  ('reports:export', 'Export reports to Excel and PDF'),
  ('counts:execute', 'Do stock counts'),
  ('counts:approve', 'Approve count variances');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p WHERE r.code = 'TENANT_ADMIN';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p WHERE r.code = 'WAREHOUSE_MANAGER' AND p.code <> 'users:manage';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p WHERE r.code = 'SUPERVISOR' AND p.code IN (
  'users:read', 'roles:read', 'warehouses:read', 'labels:print', 'catalog:read', 'inbound:read', 'inventory:read',
  'outbound:read', 'tasks:read', 'tasks:assign', 'reports:read', 'counts:execute');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p WHERE r.code = 'INVENTORY_CONTROLLER' AND p.code IN (
  'warehouses:read', 'labels:print', 'catalog:read', 'inventory:read', 'inventory:transfer', 'inventory:adjust',
  'counts:execute', 'tasks:read', 'reports:read');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p WHERE r.code = 'RECEIVER' AND p.code IN (
  'warehouses:read', 'catalog:read', 'inbound:read', 'inbound:receive', 'putaway:execute', 'inventory:read', 'tasks:read');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p WHERE r.code = 'PICKER' AND p.code IN (
  'warehouses:read', 'catalog:read', 'outbound:read', 'picking:execute', 'inventory:read', 'tasks:read');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p WHERE r.code = 'PACKER' AND p.code IN (
  'warehouses:read', 'catalog:read', 'outbound:read', 'packing:execute', 'dispatch:execute', 'tasks:read');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p WHERE r.code = 'VIEWER' AND p.code IN (
  'warehouses:read', 'catalog:read', 'inbound:read', 'inventory:read', 'outbound:read', 'tasks:read',
  'reports:read', 'reports:export');
