-- UHF RFID 标签库 —— D1 表结构
--
-- 应用方式（部署时执行一次）：
--     wrangler d1 execute rfid-tags --remote --file=cloud/schema.sql
--
-- 设计要点：
--   * epc 唯一。同一颗标签重复上传时走「更新」而不是插一条新的，
--     否则反复读同一颗标签会把库撑爆。
--   * tid 单独存。这颗标签的 EPC 和 TID 只差第 9 位（EPC=0 / TID=2），
--     两个都留着才能做完整性校验和事后追溯。
--   * 异常（校验没过但数据稳定）用 status + anomaly_note 记录，不丢数据也不伪装成正常。
--   * operator 记录是谁传的（几个人共用一套库，要分得清）。

CREATE TABLE IF NOT EXISTS tags (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    epc           TEXT    NOT NULL,              -- 28 个 hex 字符，大写
    tid           TEXT    NOT NULL,              -- 同上
    name          TEXT,                          -- 未命名时为 NULL
    status        TEXT    NOT NULL DEFAULT 'ok', -- 'ok' | 'anomaly'
    anomaly_note  TEXT,                          -- 异常说明（为什么破例上传）
    operator      TEXT,                          -- 上传者/设备名
    reads         INTEGER NOT NULL DEFAULT 1,    -- 被上传过几次
    created_at    TEXT    NOT NULL DEFAULT (datetime('now')),
    updated_at    TEXT    NOT NULL DEFAULT (datetime('now'))
);

-- epc 唯一：重复上传只更新
CREATE UNIQUE INDEX IF NOT EXISTS idx_tags_epc ON tags(epc);

-- 列表默认按时间倒序
CREATE INDEX IF NOT EXISTS idx_tags_created ON tags(created_at DESC);

-- 没命名的排在前面，方便逐个命名
CREATE INDEX IF NOT EXISTS idx_tags_name ON tags(name);

-- 异常记录单独留一份审计，别只放在 tags 上被后续更新覆盖掉
CREATE TABLE IF NOT EXISTS anomalies (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    epc        TEXT NOT NULL,
    tid        TEXT NOT NULL,
    reason     TEXT NOT NULL,      -- 例如 'epc_tid_9th_mismatch'
    detail     TEXT,               -- 连续两次的实际读数
    operator   TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_anomalies_created ON anomalies(created_at DESC);
