-- 供应商档案。**进货单此前只有一个名字字符串**（inv_inbound_order.supplier_name
-- varchar(64)），进货页上写着「仅作记录，不建立供应商档案」—— 这张表就是来改那句话的。
--
-- 为什么名字不够：同一家「老周粮油」会被打成「老周粮油店」「老周」「周老板」，
-- 进货报表按名字聚合，商家看到的是三个供应商，进货额被拆成三份。
-- 2026-08-29 真机当天量到同一商家 13 组重名货、207 件货号全空 —— 同一类病，
-- 只是换了个对象。方案见 docs/technical/design/进销存-供应商与发货要素.md
--
-- **owner_id 的隔离靠查询显式带，不靠平台 DataScope**：那套机制只覆盖平台迁移里的
-- 163 张表，inv_* 一张都不在（当场查过）。与既有的 15 处 .eq(ownerId) 同一口径 ——
-- 漏一处就是跨商家泄露，而它不会报错。

CREATE TABLE IF NOT EXISTS inv_supplier
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    supplier_no          VARCHAR(32)  NOT NULL,
    owner_id             VARCHAR(32)  NOT NULL,
    platform_supplier_no VARCHAR(32)  DEFAULT NULL COMMENT '空 = 商家自建；非空 = 引用平台档案，名称与联系方式商家只读。平台表这一轮不建，列先留 —— 事后补的代价是一次人工归并',
    name                 VARCHAR(128) NOT NULL,
    short_name           VARCHAR(32)  DEFAULT NULL COMMENT '单据列表上显示它，长名换行会把一行撑成两行',
    contact_name         VARCHAR(64)  DEFAULT NULL,
    contact_phone        VARCHAR(32)  DEFAULT NULL,
    status               VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED。**停用不删除** —— 历史单据要指得回去',
    remark               VARCHAR(255) DEFAULT NULL COMMENT '引用平台档案时这一列仍归商家写 —— 那是他自己的话',
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by           VARCHAR(64)  DEFAULT NULL,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by           VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sup_no (owner_id, supplier_no),
    UNIQUE KEY uk_sup_name (owner_id, name),
    KEY idx_sup_status (owner_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='供应商档案：进货单指向的那个稳定对象';

-- uk_sup_name 是这张表存在的理由，不是顺手加的索引：
-- 同一商家不许有两个同名供应商，否则漂移只是从「单据上的名字」换到「档案里的名字」
-- 继续长。跨商家同名是正常的，所以带 owner_id。

-- 进货单指向档案。**supplier_name 保留不删**：存量 206 张单里的名字是唯一的历史事实；
-- 而新单据继续写它，作为下单当时的名字快照 —— 供应商三个月后改名，
-- 历史单该显示当时那个名字，而不是跟着变。
ALTER TABLE inv_inbound_order
    ADD COLUMN supplier_no VARCHAR(32) DEFAULT NULL COMMENT '指向 inv_supplier；空 = 老单或未建档，此时以 supplier_name 为准' AFTER supplier_name;

CREATE INDEX idx_inb_supplier ON inv_inbound_order (owner_id, supplier_no);
