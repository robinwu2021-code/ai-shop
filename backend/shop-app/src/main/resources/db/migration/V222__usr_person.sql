-- 平台人档：这个自然人。**不要求他注册过**。
--
-- 为什么现有两张表都不够（见 TDD-会员与营销-表结构与对象模型 §2.0）：
--   usr_account  要注册才有 —— 商家录进来的手机号还没有账号
--   usr_identity 的 user_no 非空 —— 同样要先有账号
--
-- 会员是「某个自然人 × 某家商家」的关系，所以必须先有「这个自然人」。
-- 人档以**已验证的手机号**为准：会员必须有手机号（2026-08-24 拍板），
-- 这一条把「线索转正要合并两行」从常规路径降级成罕见异常 ——
-- 商家先录了号、他后来才注册时，两边指向的从头到尾是同一份人档。
--
-- 手机号只在这里存一份：明文加密进 phone_enc，匹配用不可逆的 phone_hash。
-- 商家侧永远只拿得到后四位。散在各商家表里的手机号是最容易出事的那种数据。
CREATE TABLE IF NOT EXISTS usr_person
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    person_no VARCHAR(64) NOT NULL COMMENT '平台唯一身份。各域引用它，而不是各存一份手机号',
    phone_hash VARCHAR(64) NOT NULL COMMENT '手机号哈希，用来匹配同一个人。不可逆',
    phone_enc VARCHAR(255) DEFAULT NULL COMMENT '手机号密文，只有平台能解',
    phone_tail VARCHAR(8) DEFAULT NULL COMMENT '后四位。商家侧只看得到它，查询时不必解密',
    user_no VARCHAR(64) DEFAULT NULL COMMENT '他注册之后绑定的账号。没注册就是空 —— 人先于账号存在',
    merged_into VARCHAR(64) DEFAULT NULL COMMENT '换号/重复人档合并后指向的目标 person_no，保留不删',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / MERGED',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_person_no (person_no),
    UNIQUE KEY uk_person_phone (tenant_no, phone_hash),
    UNIQUE KEY uk_person_user (tenant_no, user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='平台人档：这个自然人，以已验证的手机号为准';

-- 合并留痕。**这张表常年应该是空的** —— 会员必须有手机号之后，
-- 只剩换号撞档与人工纠错两种；不空就说明别处错了。
CREATE TABLE IF NOT EXISTS usr_person_merge_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    from_person_no VARCHAR(64) NOT NULL,
    to_person_no VARCHAR(64) NOT NULL,
    reason VARCHAR(32) NOT NULL COMMENT 'BIND_PHONE 补号撞上线索档 / CHANGE_PHONE 换号 / OPS 人工',
    affected_members INT(11) NOT NULL DEFAULT 0,
    operator_no VARCHAR(64) DEFAULT NULL COMMENT '人工合并时是谁',
    merged_at BIGINT(20) NOT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_person_merge_from (from_person_no),
    KEY idx_person_merge_to (to_person_no, merged_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='人档合并留痕：合并不可逆';
