-- Validated orders table for oms-validator service
-- Stores validation results (own table, not shared with oms-ingest)

CREATE TABLE IF NOT EXISTS validated_orders (
    order_id UUID PRIMARY KEY,
    client_order_id VARCHAR(100) NOT NULL,
    account_id VARCHAR(50) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    side VARCHAR(10) NOT NULL CHECK (side IN ('BUY', 'SELL')),
    order_type VARCHAR(20) NOT NULL CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP', 'STOP_LIMIT')),
    quantity DECIMAL(18,6) NOT NULL CHECK (quantity > 0),
    limit_price DECIMAL(18,6),
    stop_price DECIMAL(18,6),
    time_in_force VARCHAR(10) NOT NULL CHECK (time_in_force IN ('DAY', 'GTC', 'IOC', 'FOK')),
    
    -- Validation result
    validation_status VARCHAR(20) NOT NULL CHECK (validation_status IN ('VALIDATED', 'REJECTED')),
    rejection_reason TEXT,
    validated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Audit
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_validated_orders_account ON validated_orders(account_id);
CREATE INDEX IF NOT EXISTS idx_validated_orders_status ON validated_orders(validation_status);
CREATE INDEX IF NOT EXISTS idx_validated_orders_validated_at ON validated_orders(validated_at);
