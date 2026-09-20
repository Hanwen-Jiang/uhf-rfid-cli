-- 迁移：把"上传次数"换成真正有意义的"读到过几次 / 最后读到时间"
--
-- 背景：原来的 tags.reads 统计的是**上传次数**。但 App 现在读到已存在的标签
-- 就不再重复上传了，所以那个数不会再涨，显示"读过 N 次"是误导。
--
-- 新增两列：
--   seen_count  被读到的次数（App 每次读到时回报，从 0 开始重新计）
--   last_seen   最后一次读到的时间
--
-- 执行：
--   wrangler d1 execute rfid-tags --remote --file=cloud/migrate_seen.sql

ALTER TABLE tags ADD COLUMN seen_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tags ADD COLUMN last_seen TEXT;

-- 老数据回填：以前每次上传都算一次"读到"，所以用 reads 起个步不至于从 0 开始，
-- 但要明白这是估算。updated_at 当最后时间用。
UPDATE tags SET seen_count = reads, last_seen = updated_at;

CREATE INDEX IF NOT EXISTS idx_tags_last_seen ON tags(last_seen DESC);
