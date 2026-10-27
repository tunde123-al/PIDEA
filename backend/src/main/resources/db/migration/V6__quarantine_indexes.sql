-- Supports the cross-job quarantine listing (newest first, optional reason / source filters) without scanning the
-- whole table. Additive only.
CREATE INDEX ix_rejected_processed ON integration.rejected_record (processed_at DESC, id);
CREATE INDEX ix_rejected_reason    ON integration.rejected_record (reason_code, processed_at DESC);
CREATE INDEX ix_rejected_source    ON integration.rejected_record (source_id, processed_at DESC);
