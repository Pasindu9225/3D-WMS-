-- v1.0 task 6: supervisor task board. A pick list gets a priority and can be given to one picker.
ALTER TABLE pick_list
  ADD COLUMN priority ENUM('URGENT','HIGH','NORMAL') NOT NULL DEFAULT 'NORMAL' AFTER status,
  ADD COLUMN assigned_to BIGINT UNSIGNED NULL AFTER priority,
  ADD COLUMN assigned_at DATETIME(3) NULL AFTER assigned_to,
  ADD KEY ix_pick_board (tenant_id, warehouse_id, status, priority),
  ADD CONSTRAINT fk_pick_assignee FOREIGN KEY (assigned_to) REFERENCES app_user (id);
