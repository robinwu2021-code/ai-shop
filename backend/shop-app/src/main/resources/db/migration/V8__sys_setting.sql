-- 平台可调参数（评分权重、快速退款阈值…）。
--
-- 为什么建一张通用 KV 表而不是给每套参数各建一张：
-- 这类参数的共同点是**少、结构各异、改动要留痕**。各建一表的结果是十几张
-- 只有一行的表，外加十几份几乎一样的读写代码；而真正重要的那件事
-- （谁在什么时候把它改成了什么）反而每张表各实现一遍，迟早漏掉一处。
--
-- 值用 JSON 存：参数的结构由使用方定义，这张表只负责「存住 + 留痕」。
-- 校验（比如三维权重之和必须为 100）留在各自的 Service 里 ——
-- 放进这张表就得为每种参数写一段判断，那就是把领域知识塞进基础设施。
CREATE TABLE IF NOT EXISTS sys_setting
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    setting_key VARCHAR(64) NOT NULL COMMENT '参数键，如 review.score-config',
    setting_value TEXT NOT NULL COMMENT 'JSON。结构由使用方定义',
    remark VARCHAR(255) DEFAULT NULL COMMENT '给运营看的说明：这组参数是干什么的',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL COMMENT '最后修改人（运营 staffNo）—— 改参数会改变历史数据的呈现，必须留痕',
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_setting_key (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='平台可调参数：一行一组，值为 JSON';

-- 评分算法参数（P-13.1.4）。三维权重之和必须为 100，由 Service 校验。
INSERT INTO sys_setting
(setting_key, setting_value, remark, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('review.score-config',
 '{"weightProduct":50,"weightFulfill":30,"weightService":20,"newMerchantProtectDays":30,"decayHalfLifeDays":180}',
 '评价三维权重与保护期。改它会改变历史评价的呈现（时效衰减是实时算的）',
 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
