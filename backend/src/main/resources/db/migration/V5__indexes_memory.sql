-- liquibase formatted sql

-- changeset app:20251104-003-mem-kv-ttl-index
-- comment: Add index on MEMORY_KV.TTL_TS to accelerate expiry cleanup and reads with TTL filters
CREATE INDEX idx_memory_kv_ttl_ts ON memory_kv(ttl_ts);
-- rollback DROP INDEX idx_memory_kv_ttl_ts;

-- changeset app:20251104-004-mem-long-updated-index
-- comment: Add index on MEMORY_LONG.UPDATED_AT to speed incremental summary fetch logic
CREATE INDEX idx_memory_long_updated ON memory_long(updated_at);
-- rollback DROP INDEX idx_memory_long_updated;
