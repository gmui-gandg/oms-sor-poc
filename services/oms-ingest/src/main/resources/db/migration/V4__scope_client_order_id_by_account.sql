-- Scope client_order_id uniqueness by account_id.
-- This supports idempotent order submission per account (not globally across all accounts).

-- Drop the previous global unique constraint (name differs by environment; Postgres default is usually orders_client_order_id_key)
ALTER TABLE orders
    DROP CONSTRAINT IF EXISTS orders_client_order_id_key;

-- Ensure there is no existing duplicate for (account_id, client_order_id) before adding the constraint.
-- If this fails in a non-dev environment, you must resolve duplicates prior to applying this migration.

ALTER TABLE orders
    ADD CONSTRAINT uk_orders_account_client_order_id UNIQUE (account_id, client_order_id);
