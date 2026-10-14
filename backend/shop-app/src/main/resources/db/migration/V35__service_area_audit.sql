-- 覆盖项的审核闭环（ADR-013 阶段三）。
--
-- 阶段二把区、街道级的覆盖存成 PENDING，却**没有任何审核入口** ——
-- 商家提交的每一条区级覆盖都停在待审，永远没人能裁。这一版把它接进
-- 已有的门面审核队列（mch_store_audit），而不是另造一套：
-- 运营的工作台上不该有两个长得一样、入口不同的「待审列表」。

-- ── 1. 覆盖项的业务键 ───────────────────────────────────────────────
--
-- 审核单要能**指回**具体哪一条覆盖。用自增 id 做外部标识是本仓库明令
-- 不做的事（id 会随重建库变，且它会跟着接口泄漏到端上）。

ALTER TABLE mch_service_area
    ADD COLUMN area_no VARCHAR(64) NULL COMMENT '业务键。审核单靠它指回本行' AFTER id;

-- 存量行补键：'SVA' + id 左补零。存量都是 ACTIVE，不进审核队列，
-- 这个键只为「以后每一行都有键」这条不变量成立
UPDATE mch_service_area SET area_no = CONCAT('SVA', LPAD(id, 12, '0')) WHERE area_no IS NULL;

ALTER TABLE mch_service_area
    MODIFY COLUMN area_no VARCHAR(64) NOT NULL COMMENT '业务键。审核单靠它指回本行',
    ADD UNIQUE KEY uk_service_area_no (area_no);

-- ── 2. 审核单指回业务记录 ───────────────────────────────────────────
--
-- NOTICE / BANNER 两种 kind 审的是单据自己带的 content，指不到别的行；
-- SERVICE_AREA 审的是另一张表里的一行，必须存指针。

ALTER TABLE mch_store_audit
    ADD COLUMN ref_no VARCHAR(64) NULL COMMENT '这张单指向哪条业务记录（kind=SERVICE_AREA 时是 mch_service_area.area_no）。NOTICE/BANNER 为空 —— 它们审的是单据自带的 content' AFTER kind;

CREATE INDEX idx_store_audit_ref ON mch_store_audit (ref_no);

-- ── 3. 给存量的待审覆盖项补单 ───────────────────────────────────────
--
-- 阶段二上线到这一版之间提交的 PENDING 覆盖项没有对应审核单 ——
-- 不补的话它们对运营是**不可见的**，会永远待审。
-- content 只写「层级:码」：展示名由服务端在读的时候拼（区划改名后单据仍要显示当前名）。

INSERT INTO mch_store_audit
    (audit_no, entity_no, kind, ref_no, content, status, submitted_at,
     tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT CONCAT('SA', LPAD(a.id, 12, '0')), a.entity_no, 'SERVICE_AREA', a.area_no,
       CONCAT(a.level, ':', a.ref_code), 'PENDING', UNIX_TIMESTAMP(a.created_at) * 1000,
       'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0
FROM mch_service_area a
WHERE a.status = 'PENDING'
  AND NOT EXISTS (SELECT 1 FROM mch_store_audit s WHERE s.ref_no = a.area_no AND s.deleted = 0);
