-- 迁移：记录"写过几次"（只有写成功才计一次）
--
--   write_count   成功写入的次数（EPC 和 TID 两区都写成功才算，写失败不计数）
--   last_written  最后一次写成功的时间
--
-- 执行：
--   wrangler d1 execute rfid-tags --remote --file=cloud/migrate_written.sql

ALTER TABLE tags ADD COLUMN write_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tags ADD COLUMN last_written TEXT;

CREATE INDEX IF NOT EXISTS idx_tags_last_written ON tags(last_written DESC);
