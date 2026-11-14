-- 账号经营地区 + 门店自填行业（TDD-注册地区与三步建店）。
--
-- usr_account.market：注册时定一次的经营地区，之后建店、方案都按它走。
-- 不回填：存量账号读到 NULL 时按部署默认（shop.merchant.default-market）回落，
-- 结果与今天一致；迁移里写死任何值都会在某个部署上错（生产默认 CN，台湾实例 TW）。
--
-- mch_store.industry_text / sub_industry_text：建店时行业或子业态选「其他」后老板自填的文字（界面限 20 字）。
-- 经营类型仍在 business_type_code，这两列只是说明，不参与任何判定。

ALTER TABLE usr_account ADD COLUMN market VARCHAR(8) NULL;

ALTER TABLE mch_store ADD COLUMN industry_text VARCHAR(40) NULL;

ALTER TABLE mch_store ADD COLUMN sub_industry_text VARCHAR(40) NULL;
