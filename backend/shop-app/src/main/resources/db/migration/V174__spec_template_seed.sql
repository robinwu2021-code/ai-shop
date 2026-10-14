-- 平台规格模板的初始数据。
--
-- 为什么需要这条迁移：`prd_spec_template` 在线上是**空表**（0 行），
-- 而端上「套用模板」那个入口挂在 `v-if="templates.length"` 上 ——
-- 于是这个功能从上线到现在**一次都没出现过**，看起来像没做，实际是没数据。
--
-- 后果不止是少一个入口：商家点「＋规格组」之后面对的是两个空框，
-- 而「规格名该填什么」恰恰是建品时最难的一步。没有模板，
-- 同一件大米在三家店会被写成「重量」「份量」「规格」三种维度名，
-- 平台侧再想按规格聚合就永远对不齐 —— 平台模板带 code，正是为了解决这个。
--
-- 粒度选**品类**而不是类目：端上 `mSpecTemplates(type)` 查的就是 category_type，
-- 现有代码一行不用改。类目粒度（prd_category.attr_template，24 个类目当前全空）
-- 更准，但要新增读取链路，等这批模板跑出真实使用数据再决定要不要下沉。
--
-- template_no 用可读的 SEED 前缀：`BizKey.next` 生成的是
-- `SPT + 时间戳 + 序号 + 随机数`，与这里的固定串不可能撞。
INSERT INTO prd_spec_template
(template_no, scope, category_type, name, options, entity_no, status,
 tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
-- 生鲜：按重量卖是主流，按包装卖是次选
('SPT_SEED_FRESH_WEIGHT', 'PLATFORM', 'FRESH', '重量',
 '[{"code":"W500G","label":"约1斤"},{"code":"W1KG","label":"约2斤"},{"code":"W2500G","label":"约5斤"},{"code":"W5KG","label":"约10斤"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_SEED_FRESH_PACK', 'PLATFORM', 'FRESH', '包装',
 '[{"code":"PBULK","label":"散装"},{"code":"PBAG","label":"袋装"},{"code":"PBOX","label":"盒装"},{"code":"PGIFT","label":"礼盒"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
-- 日用百货：按件数与包装形态
('SPT_SEED_NORMAL_COUNT', 'PLATFORM', 'NORMAL', '规格',
 '[{"code":"C1","label":"单件"},{"code":"C2","label":"2件装"},{"code":"C5","label":"5件装"},{"code":"CCASE","label":"整箱"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_SEED_NORMAL_PACK', 'PLATFORM', 'NORMAL', '包装',
 '[{"code":"PBAG","label":"袋装"},{"code":"PBOTTLE","label":"瓶装"},{"code":"PBOX","label":"盒装"},{"code":"PCAN","label":"罐装"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
-- 服务：时长与人数是两个真正会影响定价的维度
('SPT_SEED_SERVICE_DURATION', 'PLATFORM', 'SERVICE', '时长',
 '[{"code":"D30","label":"30分钟"},{"code":"D60","label":"60分钟"},{"code":"D90","label":"90分钟"},{"code":"D120","label":"120分钟"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0),
('SPT_SEED_SERVICE_HEADCOUNT', 'PLATFORM', 'SERVICE', '人数',
 '[{"code":"H1","label":"1人"},{"code":"H2","label":"2人"},{"code":"H3","label":"3人及以上"}]',
 NULL, 'ACTIVE', 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
