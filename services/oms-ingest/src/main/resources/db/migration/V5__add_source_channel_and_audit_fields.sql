-- Add source/channel dimension and basic audit fields to orders.
-- Idempotency scope becomes (account_id, source_channel, client_order_id).

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS source_channel VARCHAR(20);

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS received_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(100);

-- Backfill existing rows
UPDATE orders
SET source_channel = COALESCE(source_channel, 'REST'),
    received_at = COALESCE(received_at, created_at);

-- Enforce NOT NULL after backfill
ALTER TABLE orders
    ALTER COLUMN source_channel SET NOT NULL;

ALTER TABLE orders
    ALTER COLUMN received_at SET NOT NULL;

-- Replace previous idempotency constraint
ALTER TABLE orders
    DROP CONSTRAINT IF EXISTS uk_orders_account_client_order_id;

ALTER TABLE orders
    ADD CONSTRAINT uk_orders_account_source_client_order_id UNIQUE (account_id, source_channel, client_order_id);

-- Optional supporting index for common queries
CREATE INDEX IF NOT EXISTS idx_orders_account_channel ON orders(account_id, source_channel);
