-- 方案 v3 §3.1：街道/镇自选即生效，只有区/市要审。
-- 此前街道级也进待审：商家勾了整个街道，保存后立刻看到「待审核」，而 C 端谁也看不到他。
UPDATE mch_service_area SET status = 'ACTIVE' WHERE level = 'STREET' AND status = 'PENDING';

-- 对应的待审单直接关掉，否则运营队列里留着一批已经生效的「待审」。
UPDATE mch_store_audit SET status = 'PASSED', decided_at = UNIX_TIMESTAMP() * 1000, decided_by = 'SYSTEM' WHERE kind = 'SERVICE_AREA' AND status = 'PENDING' AND content LIKE 'STREET:%';

-- 商家自建自提点要审（P1）：PENDING → ACTIVE / REJECTED。驳回理由原样回商家，不写他只会原样再提一次。
ALTER TABLE cmt_pickup_point ADD COLUMN reject_reason VARCHAR(255) NULL COMMENT '自建点被驳回的理由，原样回给商家';
