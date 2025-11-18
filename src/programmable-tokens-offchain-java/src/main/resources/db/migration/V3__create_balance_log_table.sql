-- Create balance_log table to store balance history for programmable token addresses
CREATE TABLE balance_log (
    id BIGSERIAL PRIMARY KEY,

    -- Address Information
    address VARCHAR(200) NOT NULL,
    payment_script_hash VARCHAR(56) NOT NULL,
    stake_key_hash VARCHAR(56),

    -- Transaction Context
    tx_hash VARCHAR(64) NOT NULL,
    slot BIGINT NOT NULL,
    block_height BIGINT NOT NULL,

    -- Asset Information
    policy_id VARCHAR(56) NOT NULL,  -- "ADA" for lovelace
    asset_name VARCHAR(128),         -- NULL for ADA

    -- Balance State (after this transaction)
    quantity BIGINT NOT NULL,

    -- Asset Classification (from registry lookup)
    is_programmable_token BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMP NOT NULL,

    -- Unique constraint: one entry per address/asset/tx
    CONSTRAINT unique_balance_entry UNIQUE(address, policy_id, asset_name, tx_hash)
);

-- Create indexes for efficient querying
CREATE INDEX idx_balance_address ON balance_log(address);
CREATE INDEX idx_balance_payment_script ON balance_log(payment_script_hash);
CREATE INDEX idx_balance_stake_key ON balance_log(stake_key_hash);
CREATE INDEX idx_balance_payment_stake ON balance_log(payment_script_hash, stake_key_hash);
CREATE INDEX idx_balance_tx_hash ON balance_log(tx_hash);
CREATE INDEX idx_balance_slot ON balance_log(slot);
CREATE INDEX idx_balance_policy ON balance_log(policy_id);
CREATE INDEX idx_balance_addr_asset_slot ON balance_log(address, policy_id, asset_name, slot DESC);

-- Add comments to table
COMMENT ON TABLE balance_log IS 'Append-only log of balance changes for programmable token addresses';
COMMENT ON COLUMN balance_log.address IS 'Full bech32 address';
COMMENT ON COLUMN balance_log.payment_script_hash IS 'Payment credential hash (must match programmable token base script)';
COMMENT ON COLUMN balance_log.stake_key_hash IS 'Optional stake credential hash';
COMMENT ON COLUMN balance_log.tx_hash IS 'Transaction hash that caused this balance change';
COMMENT ON COLUMN balance_log.slot IS 'Cardano slot number';
COMMENT ON COLUMN balance_log.policy_id IS 'Asset policy ID (or "ADA" for lovelace)';
COMMENT ON COLUMN balance_log.asset_name IS 'Asset name (null for ADA)';
COMMENT ON COLUMN balance_log.quantity IS 'Balance after this transaction';
COMMENT ON COLUMN balance_log.is_programmable_token IS 'Whether this asset is registered in the directory';
