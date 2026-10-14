-- V66：把「界面功能没有独立端点」的几个功能点接上近似码。
--
-- V64 的规则是「ui 码不在 68 个细码里 → NULL + NOT_IMPLEMENTED」，
-- 而 ops-web 的 perm-map 对这几个另有安排（映到覆盖它的码）——
-- **两边各写了一套**：「支付流水核对」在菜单上灰着，而 can() 判它可用。
-- 浏览器点开权限树第一屏就看见了。真源现在收在 scripts/perm-endpoint-map.mjs
-- 的 NEAREST_CODE，由 ops-perm-matrix.test.ts 逐条比对。

UPDATE sys_function_point SET perm_code = 'product:category:update', backend_status = 'IMPLEMENTED' WHERE point_code = 'ACT_03';  -- category:manage
UPDATE sys_function_point SET perm_code = 'order:order:read', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_ORDER_04';  -- order:pay:read
UPDATE sys_function_point SET perm_code = 'order:order:modify', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_ORDER_05';  -- order:pay:repair
UPDATE sys_function_point SET perm_code = 'order:order:modify', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_ORDER_06';  -- order:pay:repair
UPDATE sys_function_point SET perm_code = 'product:sku:audit', backend_status = 'IMPLEMENTED' WHERE point_code = 'OPS_PRODUCT_03';  -- product:stock:update

-- 重建角色×功能点：上面几个点现在有码了，持有该码的角色要拿到它们
--
-- 三条，每条都让某个岗位少一样东西：
--  ① 售后裁决与极速退阈值：7 个角色 → 客服（裁决本职）+ 财务（只保留阈值，
--     它承担资金后果）。这两条原本挂在 order:view —— 一个「看单」的码 ——
--     所以风控、社区运营、活动运营今天都能批退款。
--     **读没有一起收**：查售后单是排查恶意退款的最低限度，风险不在一个量级。
--  ② 门店经营模式切换：财务 → BD。它此前落在财务手里只是因为挂在结算的粗码下。
--  ③ 封禁无需改配置：只在 BD 手里，而商家治理本来就归 BD ——
--     细化本身就是收益，从今天起它**能**被单独收回。
--
-- 改配置解决不了的一条：制单（finance:settle:execute）与付款
-- （finance:payout:execute）现在都在 FINANCE 一个角色上。真正的分离要第二个财务岗，
-- 那是产品决定。码已经分开，等那个岗位存在时改一行就行。

DELETE FROM sys_role_point WHERE end_code = 'OPS' AND role_code IN ('SUPER_ADMIN', 'BD', 'GOODS_OPS', 'SUPPORT', 'CAMPAIGN_OPS', 'COMMUNITY_OPS', 'AUDITOR', 'FINANCE', 'RISK', 'ANALYST', 'TECH_OPS');
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_08', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_09', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_AFTERSALE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROUP_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_IAM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MARKETING_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MERCHANT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_ORDER_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_SYSTEM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_12', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_14', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_15', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_16', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_17', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_18', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_19', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_20', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_21', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_22', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_23', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_24', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_25', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_26', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_27', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_28', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_29', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_30', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_31', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_32', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_33', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_34', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_STORE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FULFILLMENT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_GROWTH_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_FINANCE_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_MESSAGE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'OPS_RISK_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPER_ADMIN', 'ACT_10', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_08', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_GROUP_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_MERCHANT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'OPS_STORE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_26', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_27', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('BD', 'ACT_28', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_19', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_22', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('GOODS_OPS', 'ACT_23', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_09', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_MESSAGE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_ORDER_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_29', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('SUPPORT', 'ACT_30', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_07', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_GROUP_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_MARKETING_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_19', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_22', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('CAMPAIGN_OPS', 'ACT_23', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_05', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_COMMUNITY_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_12', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_31', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('COMMUNITY_OPS', 'ACT_32', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_CONTENT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_PRODUCT_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'OPS_REVIEW_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_29', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('AUDITOR', 'ACT_30', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_AFTERSALE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_FINANCE_06', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_14', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_15', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_16', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_17', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_18', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_24', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('FINANCE', 'ACT_25', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_AFTERSALE_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'OPS_ORDER_04', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'ACT_11', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('RISK', 'ACT_13', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('ANALYST', 'OPS_COMMUNITY_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_IAM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_01', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_02', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'OPS_SYSTEM_03', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'ACT_33', 'OPS', NOW(), NOW());
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES ('TECH_OPS', 'ACT_34', 'OPS', NOW(), NOW());
