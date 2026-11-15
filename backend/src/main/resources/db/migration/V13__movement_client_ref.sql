-- v0.4 task 5: moves made from a phone (putaway now, transfers next) carry the phone's Idempotency-Key, so a retry
-- on bad Wi-Fi moves the stock once. NULL for moves the server makes itself (receipts).

ALTER TABLE stock_movement
  ADD COLUMN client_ref VARCHAR(64) NULL AFTER ref_id,
  ADD UNIQUE KEY uk_mv_client (tenant_id, client_ref);
