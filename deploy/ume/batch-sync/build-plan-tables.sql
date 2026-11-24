-- UME 批量迁移：把三张原始导入表整理成「同步计划表」（TDD-商户迁移同步与渠道关联 §8 M1/M2 的数据底座）。
-- 2026-11-17。只新增 mig_ume_*_20261117 四张表，不改任何业务表；重跑前先 DROP（整理表可随时重建）。
-- 不复制个人资料：只存编号、状态、证号末 4 位、手机末 3 位和各类标记；渠道商户号是非秘密标识，M2 对外再掩码。
--
-- 来源：ai_shop_ume.stg_ume_active_stores_20261113（门店）、stg_ume_first_batch_store_accounts_20261113（店员账号）、
--       nearpay_prod.stg_ume_active_payment_merchants_20261112（主体要素、渠道）；nearshop 现状 mch_entity / mch_store / usr_identity。
-- 对应关系（2026-11-17 已核）：entity_no = 'MUME' + 原系统商户号（一商户一主体）；store_no = 'STUME' + 原系统门店号；
--       主体属主账号按邮箱对应 usr_identity(EMAIL)。
-- 渠道映射：linepay → LINEPAY（真正的渠道号在 provider_identifiers.linepay.channel_id）；卡 spgateway → NEWEBPAY；
--       其余当前 near-pay 没有对应通道，记 UNSUPPORTED / UNDECIDED，不丢。
SET NAMES utf8mb4;

-- ===================== ai-shop 库 =====================
DROP TABLE IF EXISTS ai_shop_ume.mig_ume_store_20261117;
CREATE TABLE ai_shop_ume.mig_ume_store_20261117 (
  source_store_id    VARCHAR(32)  NOT NULL,
  source_merchant_id VARCHAR(32)  NOT NULL,
  store_no           VARCHAR(40)  NOT NULL,
  entity_no          VARCHAR(40)  NULL,
  owner_user_no      VARCHAR(40)  NULL,
  store_exists       TINYINT      NOT NULL,
  entity_exists      TINYINT      NOT NULL,
  store_under_entity TINYINT      NOT NULL COMMENT '门店现在确实挂在该主体下',
  owner_email_ok     TINYINT      NOT NULL COMMENT '主体属主账号的邮箱与来源 merchant_email 一致',
  store_status       VARCHAR(20)  NULL,
  entity_status      VARCHAR(20)  NULL,
  eligible           TINYINT      NOT NULL COMMENT '来源已审核 audit_status=2',
  issues             VARCHAR(255) NOT NULL DEFAULT '',
  built_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (source_store_id),
  KEY idx_mig_store_merchant (source_merchant_id),
  KEY idx_mig_store_entity (entity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='UME 批量迁移·门店计划（M1 底座）';

INSERT INTO ai_shop_ume.mig_ume_store_20261117
  (source_store_id, source_merchant_id, store_no, entity_no, owner_user_no, store_exists, entity_exists, store_under_entity,
   owner_email_ok, store_status, entity_status, eligible, issues)
SELECT s.store_id, s.merchant_id, CONCAT('STUME', s.store_id), e.entity_no, e.owner_user_no,
       ms.store_no IS NOT NULL, e.entity_no IS NOT NULL, ms.entity_no = e.entity_no,
       EXISTS (SELECT 1 FROM ai_shop_ume.usr_identity i WHERE i.user_no = e.owner_user_no AND i.identity_type = 'EMAIL'
                 AND LOWER(i.identity_value) = LOWER(s.merchant_email) AND i.deleted = 0),
       ms.status, e.status,
       IFNULL(JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.audit_status')), '') = '2',
       CONCAT_WS(',',
         IF(ms.store_no IS NULL, 'STORE_MISSING', NULL),
         IF(e.entity_no IS NULL, 'ENTITY_MISSING', NULL),
         IF(ms.store_no IS NOT NULL AND e.entity_no IS NOT NULL AND ms.entity_no <> e.entity_no, 'STORE_UNDER_OTHER_ENTITY', NULL),
         IF(m.merchant_id IS NULL, 'NO_PAYMENT_STAGING', NULL))
FROM ai_shop_ume.stg_ume_active_stores_20261113 s
LEFT JOIN ai_shop_ume.mch_store ms ON ms.store_no = CONCAT('STUME', s.store_id) AND ms.deleted = 0
LEFT JOIN ai_shop_ume.mch_entity e ON e.entity_no = CONCAT('MUME', s.merchant_id) AND e.deleted = 0
LEFT JOIN nearpay_prod.stg_ume_active_payment_merchants_20261112 m ON m.merchant_id = s.merchant_id;

DROP TABLE IF EXISTS ai_shop_ume.mig_ume_account_20261117;
CREATE TABLE ai_shop_ume.mig_ume_account_20261117 (
  ume_account_id     VARCHAR(40)  NOT NULL,
  source_merchant_id VARCHAR(32)  NOT NULL,
  source_store_id    VARCHAR(32)  NULL,
  ume_login_usr_no   VARCHAR(64)  NULL,
  bra_user           VARCHAR(64)  NULL,
  account_status     VARCHAR(8)   NULL,
  user_type          VARCHAR(8)   NULL,
  has_mobile         TINYINT      NOT NULL,
  mobile_tail3       VARCHAR(3)   NULL,
  entity_no          VARCHAR(40)  NULL,
  active             TINYINT      NOT NULL COMMENT 'ume_account_status=1',
  eligible           TINYINT      NOT NULL,
  built_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (ume_account_id),
  KEY idx_mig_account_merchant (source_merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='UME 批量迁移·店员账号计划（M1 底座；店员账号是否开通待定，当前只整理）';

INSERT INTO ai_shop_ume.mig_ume_account_20261117
  (ume_account_id, source_merchant_id, source_store_id, ume_login_usr_no, bra_user, account_status, user_type, has_mobile,
   mobile_tail3, entity_no, active, eligible)
SELECT a.ume_account_id, a.merchant_id, a.store_id, a.ume_login_usr_no, a.bra_user, a.ume_account_status, a.ume_user_type,
       a.user_mobile IS NOT NULL AND a.user_mobile <> '',
       IF(a.user_mobile IS NULL OR a.user_mobile = '', NULL, RIGHT(a.user_mobile, 3)),
       e.entity_no, a.ume_account_status = '1',
       IFNULL(JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.audit_status')), '') = '2'
FROM ai_shop_ume.stg_ume_first_batch_store_accounts_20261113 a
LEFT JOIN ai_shop_ume.mch_entity e ON e.entity_no = CONCAT('MUME', a.merchant_id) AND e.deleted = 0
LEFT JOIN nearpay_prod.stg_ume_active_payment_merchants_20261112 m ON m.merchant_id = a.merchant_id;

-- ===================== near-pay 库 =====================
DROP TABLE IF EXISTS nearpay_prod.mig_ume_merchant_20261117;
CREATE TABLE nearpay_prod.mig_ume_merchant_20261117 (
  source_merchant_id   VARCHAR(32) NOT NULL,
  entity_no            VARCHAR(40) NULL,
  entity_exists        TINYINT     NOT NULL,
  entity_kind          VARCHAR(12) NOT NULL COMMENT 'INDIVIDUAL 个人工商户 / COMPANY 公司（individual_business）',
  audit_status         VARCHAR(4)  NOT NULL,
  eligible             TINYINT     NOT NULL COMMENT 'audit_status=2',
  mer_status           VARCHAR(4)  NULL,
  ume_status           VARCHAR(4)  NULL,
  auth_status          VARCHAR(4)  NULL,
  payment_apply_status VARCHAR(4)  NULL,
  has_name             TINYINT     NOT NULL,
  has_tax_no           TINYINT     NOT NULL COMMENT '来源 tax_no 有值（实测为 9 位，含义待原系统确认，不当统编用）',
  unified_no_valid     TINYINT     NOT NULL COMMENT '统一编号 = 来源 biz_lic 为 8 位数字（2026-11-17 核：已审核公司 78/79 有；mer_regid 全空）',
  unified_no_tail4     VARCHAR(4)  NULL,
  has_reg_id           TINYINT     NOT NULL,
  has_address          TINYINT     NOT NULL,
  store_count          INT         NOT NULL,
  channel_total        INT         NOT NULL,
  channel_ready        INT         NOT NULL COMMENT '已映射、来源启用、渠道号完整的渠道数',
  ready_for_entity     TINYINT     NOT NULL COMMENT '主体要素可进入代填：已审核 + 有名称 + 主体和门店都在',
  needs_ops_registration TINYINT   NOT NULL COMMENT 'D2：登记号需运营录入（个人无统编，或公司统一编号缺失/不合法）',
  issues               VARCHAR(255) NOT NULL DEFAULT '',
  built_at             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (source_merchant_id),
  KEY idx_mig_merchant_entity (entity_no),
  KEY idx_mig_merchant_eligible (eligible, ready_for_entity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='UME 批量迁移·商户（主体要素）计划（M2 底座）';

DROP TABLE IF EXISTS nearpay_prod.mig_ume_channel_20261117;
CREATE TABLE nearpay_prod.mig_ume_channel_20261117 (
  id                   BIGINT      NOT NULL AUTO_INCREMENT,
  source_merchant_id   VARCHAR(32) NOT NULL,
  entity_no            VARCHAR(40) NULL,
  kind                 VARCHAR(4)  NOT NULL COMMENT 'SCAN / CARD',
  source_channel_name  VARCHAR(64) NULL,
  source_alias         VARCHAR(64) NULL,
  txn_channel          VARCHAR(16) NULL,
  source_status        VARCHAR(4)  NULL,
  is_default           VARCHAR(4)  NULL,
  target_channel       VARCHAR(32) NULL COMMENT 'near-pay 渠道码；NULL = 当前没有对应通道',
  mapping_state        VARCHAR(12) NOT NULL COMMENT 'CONFIRMED 已用李昱達验证 / UNDECIDED 待定 / UNSUPPORTED 无对应通道',
  provider_merchant_id VARCHAR(64) NULL COMMENT '渠道商户号（LINE=channel_id，卡=channel_merchant_no）；非秘密，M2 对外掩码',
  provider_sub_id      VARCHAR(64) NULL,
  id_present           TINYINT     NOT NULL,
  dup_in_source        INT         NOT NULL DEFAULT 0 COMMENT '同渠道同商户号在导入数据里出现的商户数（>1 将撞 uk_pay_merchant_2）',
  exists_in_nearpay    TINYINT     NOT NULL DEFAULT 0 COMMENT '同渠道同商户号已有 near-pay 渠道账户',
  eligible             TINYINT     NOT NULL,
  ready                TINYINT     NOT NULL COMMENT '可进入渠道账户创建：已审核 + 已映射 + 来源启用 + 号完整 + 无重复 + 不在 near-pay',
  issues               VARCHAR(255) NOT NULL DEFAULT '',
  built_at             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_mig_channel_merchant (source_merchant_id),
  KEY idx_mig_channel_ready (ready, target_channel),
  KEY idx_mig_channel_provider (target_channel, provider_merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='UME 批量迁移·渠道计划（M2 底座）';

INSERT INTO nearpay_prod.mig_ume_channel_20261117
  (source_merchant_id, entity_no, kind, source_channel_name, source_alias, txn_channel, source_status, is_default, target_channel,
   mapping_state, provider_merchant_id, provider_sub_id, id_present, eligible, ready, issues)
SELECT x.merchant_id, x.entity_no, x.kind, x.name, x.alias, x.txn, x.st, x.def, x.target, x.mstate, x.pid, x.psub,
       x.pid IS NOT NULL AND x.pid <> '', x.eligible, 0,
       CONCAT_WS(',',
         IF(x.target IS NULL, IF(x.mstate = 'UNDECIDED', 'MAPPING_UNDECIDED', 'NO_NEARPAY_CHANNEL'), NULL),
         IF(x.st IS NULL OR x.st <> '1', 'SOURCE_DISABLED', NULL),
         IF(x.pid IS NULL OR x.pid = '', 'NO_PROVIDER_ID', NULL))
FROM (
  SELECT t.merchant_id, CONCAT('MUME', t.merchant_id) entity_no, 'SCAN' kind, a.name, a.alias, a.txn, a.st, a.def,
         CASE a.alias WHEN 'linepay' THEN 'LINEPAY' ELSE NULL END target,
         CASE WHEN a.alias = 'linepay' THEN 'CONFIRMED' WHEN a.alias IN ('piPay', 'ezPay') THEN 'UNDECIDED' ELSE 'UNSUPPORTED' END mstate,
         CASE a.alias WHEN 'linepay' THEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(a.pid, '$.linepay.channel_id')), 'null')
                      WHEN 'gamapay' THEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(a.pid, '$.gamapay.merchant_id')), 'null')
                      WHEN 'atome' THEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(a.pid, '$.atome.merchant_no')), 'null')
                      ELSE NULLIF(a.mid, '') END pid,
         CASE a.alias WHEN 'gamapay' THEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(a.pid, '$.gamapay.sub_merchant_id')), 'null') ELSE NULL END psub,
         IFNULL(JSON_UNQUOTE(JSON_EXTRACT(t.entity_data, '$.audit_status')), '') = '2' eligible
  FROM nearpay_prod.stg_ume_active_payment_merchants_20261112 t,
       JSON_TABLE(t.scan_channels, '$[*]' COLUMNS (
         name VARCHAR(64) PATH '$.channel_name', alias VARCHAR(64) PATH '$.channel_master_alias', txn VARCHAR(16) PATH '$.txn_channel',
         st VARCHAR(4) PATH '$.channel_merchant_status', def VARCHAR(4) PATH '$.is_default', mid VARCHAR(64) PATH '$.channel_merchant_id',
         pid JSON PATH '$.provider_identifiers')) a
  UNION ALL
  SELECT t.merchant_id, CONCAT('MUME', t.merchant_id), 'CARD', 'spgateway', b.alias, b.txn, b.st, b.def,
         CASE b.alias WHEN 'spgateway' THEN 'NEWEBPAY' ELSE NULL END,
         CASE b.alias WHEN 'spgateway' THEN 'CONFIRMED' ELSE 'UNSUPPORTED' END,
         NULLIF(b.mno, ''), NULL,
         IFNULL(JSON_UNQUOTE(JSON_EXTRACT(t.entity_data, '$.audit_status')), '') = '2'
  FROM nearpay_prod.stg_ume_active_payment_merchants_20261112 t,
       JSON_TABLE(t.card_channels, '$[*]' COLUMNS (
         alias VARCHAR(64) PATH '$.channel_alias', txn VARCHAR(16) PATH '$.txn_channel', st VARCHAR(4) PATH '$.channel_merchant_status',
         def VARCHAR(4) PATH '$.is_default', mno VARCHAR(64) PATH '$.channel_merchant_no')) b
) x;

-- 重复与已存在：同渠道同商户号在导入数据里被几个商户用；是否已有 near-pay 渠道账户
UPDATE nearpay_prod.mig_ume_channel_20261117 c
JOIN (SELECT target_channel, provider_merchant_id, COUNT(DISTINCT source_merchant_id) n
        FROM nearpay_prod.mig_ume_channel_20261117
       WHERE target_channel IS NOT NULL AND provider_merchant_id IS NOT NULL
       GROUP BY 1, 2) d ON d.target_channel = c.target_channel AND d.provider_merchant_id = c.provider_merchant_id
SET c.dup_in_source = d.n;

UPDATE nearpay_prod.mig_ume_channel_20261117 c
JOIN nearpay_prod.pay_merchant m ON m.pay_channel = c.target_channel AND m.channel_merchant_id = c.provider_merchant_id AND m.deleted = 0
SET c.exists_in_nearpay = 1;

UPDATE nearpay_prod.mig_ume_channel_20261117
SET issues = CONCAT_WS(',', NULLIF(issues, ''), IF(dup_in_source > 1, 'DUP_IN_SOURCE', NULL), IF(exists_in_nearpay = 1, 'EXISTS_IN_NEARPAY', NULL), IF(eligible = 0, 'NOT_AUDITED', NULL)),
    ready = (eligible = 1 AND target_channel IS NOT NULL AND source_status = '1' AND id_present = 1 AND dup_in_source <= 1 AND exists_in_nearpay = 0);

INSERT INTO nearpay_prod.mig_ume_merchant_20261117
  (source_merchant_id, entity_no, entity_exists, entity_kind, audit_status, eligible, mer_status, ume_status, auth_status, payment_apply_status,
   has_name, has_tax_no, unified_no_valid, unified_no_tail4, has_reg_id, has_address, store_count, channel_total, channel_ready,
   ready_for_entity, needs_ops_registration, issues)
SELECT m.merchant_id, e.entity_no, e.entity_no IS NOT NULL,
       IF(JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.individual_business')) = '1', 'INDIVIDUAL', 'COMPANY'),
       IFNULL(JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.audit_status')), ''),
       IFNULL(JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.audit_status')), '') = '2',
       JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.mer_status')), JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.ume_status')),
       JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.auth_status')), JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.payment_apply_status')),
       IFNULL(JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.mer_name')), '') <> '',
       IFNULL(JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.tax_no')), '') <> '',
       IFNULL(JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.biz_lic')), '') REGEXP '^[0-9]{8}$',
       IF(IFNULL(JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.biz_lic')), '') = '', NULL, RIGHT(JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.biz_lic')), 4)),
       IFNULL(JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.mer_regid')), '') <> '',
       IFNULL(JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.mer_regaddress')), '') <> '' OR IFNULL(JSON_UNQUOTE(JSON_EXTRACT(m.entity_data, '$.mer_bizaddress')), '') <> '',
       (SELECT COUNT(*) FROM ai_shop_ume.mig_ume_store_20261117 s WHERE s.source_merchant_id = m.merchant_id),
       (SELECT COUNT(*) FROM nearpay_prod.mig_ume_channel_20261117 c WHERE c.source_merchant_id = m.merchant_id),
       (SELECT COUNT(*) FROM nearpay_prod.mig_ume_channel_20261117 c WHERE c.source_merchant_id = m.merchant_id AND c.ready = 1),
       0, 0, ''
FROM nearpay_prod.stg_ume_active_payment_merchants_20261112 m
LEFT JOIN ai_shop_ume.mch_entity e ON e.entity_no = CONCAT('MUME', m.merchant_id) AND e.deleted = 0;

UPDATE nearpay_prod.mig_ume_merchant_20261117
SET ready_for_entity = (eligible = 1 AND has_name = 1 AND entity_exists = 1 AND store_count > 0),
    needs_ops_registration = (entity_kind = 'INDIVIDUAL' OR unified_no_valid = 0),
    issues = CONCAT_WS(',',
      IF(eligible = 0, 'NOT_AUDITED', NULL), IF(has_name = 0, 'NO_NAME', NULL), IF(entity_exists = 0, 'ENTITY_MISSING', NULL),
      IF(store_count = 0, 'NO_STORE', NULL), IF(entity_kind = 'COMPANY' AND unified_no_valid = 0, 'COMPANY_UNIFIED_NO_MISSING', NULL),
      IF(entity_kind = 'INDIVIDUAL', 'INDIVIDUAL_REG_BY_OPS', NULL));
