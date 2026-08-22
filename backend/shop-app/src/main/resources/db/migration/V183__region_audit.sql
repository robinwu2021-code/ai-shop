-- 商家补录区划的**审核状态**，并修正 V182 的一处设计错误。
--
-- V182 把 owner_entity_no 同时当了两件事用：「谁提报的」与「可见性开关」，
-- 运营确认通过就把它置 NULL 转为全网可见 —— 于是**通过之后再也查不出这条是谁报的**。
-- 某个村名写错了要追源头，追不到。两件事必须拆开。
--
-- 拆开之后：
--   · owner_entity_no —— 谁提报的，**永久保留**，不再兼作可见性
--   · audit_status    —— PENDING / APPROVED / REJECTED，可见性只看它
--
-- 可见性规则：APPROVED 全网可见；PENDING 与 REJECTED 只对提报的那家店可见。
-- REJECTED 也要让他看得见 —— 连同理由。V182 的做法是驳回即删行，
-- 那样商家那边那个村凭空消失，而他不知道为什么，多半原样再录一遍。
--
-- 存量 66 万行全是官方数据，DEFAULT 'APPROVED' 已经兜住，不需要 UPDATE。
ALTER TABLE sys_region
    ADD COLUMN audit_status VARCHAR(16) NOT NULL DEFAULT 'APPROVED' COMMENT 'PENDING(待运营确认) / APPROVED(全网可见) / REJECTED(已驳回，仅提报方可见)' AFTER owner_entity_no,
    ADD COLUMN reject_reason VARCHAR(255) DEFAULT NULL COMMENT '驳回理由，原样回给商家 —— 不写的话他只会原样再提一次' AFTER audit_status;

-- 待确认队列按状态查，且每次下钻都要按状态过滤可见性
CREATE INDEX idx_sys_region_audit ON sys_region (audit_status);
