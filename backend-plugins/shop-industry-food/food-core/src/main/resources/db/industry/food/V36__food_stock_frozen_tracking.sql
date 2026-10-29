-- Preserve the original policy; historical NULL is unknown and must never be guessed from today's balance.
ALTER TABLE fnb_stock_reservation_item ADD COLUMN tracking_mode VARCHAR(16) NULL;
