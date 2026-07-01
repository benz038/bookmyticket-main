-- Wallet: one row per user, seeded to ₹1000 on first use (lazy, in WalletService).
-- A `version` column gives optimistic locking so two concurrent debits can't double-spend
-- (same guarantee as show_seats — exactly one debit wins, the other is retried/rejected).
CREATE TABLE bmt_local.wallets (
    user_id VARCHAR(255) PRIMARY KEY,
    balance NUMERIC(12, 2) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);
